package io.github.hectorvent.floci.services.eks;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class EksIdentityProviderConfigIntegrationTest {

    private static final String ACCOUNT = "123456789012";

    @Test
    void identityProviderConfigRoundTrip() {
        String name = "idp-it-" + UUID.randomUUID().toString().substring(0, 8);
        String configName = name + "-oidc-idp";
        Map<String, Object> handle = Map.of("identityProviderConfig", Map.of("type", "oidc", "name", configName));
        createCluster(name);
        try {
            eks().get("/clusters/" + name + "/identity-provider-configs")
                    .then().statusCode(200)
                    .body("identityProviderConfigs", empty());

            String associateUpdate = eks().body(Map.of(
                            "oidc", Map.of(
                                    "identityProviderConfigName", configName,
                                    "issuerUrl", "https://accounts.google.com",
                                    "clientId", "example-client",
                                    "usernameClaim", "email",
                                    "usernamePrefix", "google:",
                                    "groupsClaim", "groups",
                                    "groupsPrefix", "gid:",
                                    "requiredClaims", Map.of("hd", "example.com")),
                            "tags", Map.of("Name", configName)))
                    .post("/clusters/" + name + "/identity-provider-configs/associate")
                    .then().statusCode(200)
                    .contentType(containsString("application/json"))
                    .body("update.type", equalTo("AssociateIdentityProviderConfig"))
                    .body("update.status", equalTo("Successful"))
                    .body("update.errors", empty())
                    .body("update.params[0].type", equalTo("IdentityProviderConfig"))
                    .body("update.params[0].value", containsString("\"name\":\"" + configName + "\""))
                    .body("tags.Name", equalTo(configName))
                    .extract().path("update.id");

            eks().get("/clusters/" + name + "/identity-provider-configs")
                    .then().statusCode(200)
                    .body("identityProviderConfigs.type", contains("oidc"))
                    .body("identityProviderConfigs.name", contains(configName))
                    .body("$", not(hasKey("nextToken")));

            String arn = eks().body(handle)
                    .post("/clusters/" + name + "/identity-provider-configs/describe")
                    .then().statusCode(200)
                    .body("identityProviderConfig.oidc.identityProviderConfigName", equalTo(configName))
                    .body("identityProviderConfig.oidc.clusterName", equalTo(name))
                    .body("identityProviderConfig.oidc.status", equalTo("ACTIVE"))
                    .body("identityProviderConfig.oidc.issuerUrl", equalTo("https://accounts.google.com"))
                    .body("identityProviderConfig.oidc.clientId", equalTo("example-client"))
                    .body("identityProviderConfig.oidc.usernameClaim", equalTo("email"))
                    .body("identityProviderConfig.oidc.usernamePrefix", equalTo("google:"))
                    .body("identityProviderConfig.oidc.groupsClaim", equalTo("groups"))
                    .body("identityProviderConfig.oidc.groupsPrefix", equalTo("gid:"))
                    .body("identityProviderConfig.oidc.requiredClaims.hd", equalTo("example.com"))
                    .body("identityProviderConfig.oidc.tags.Name", equalTo(configName))
                    .extract().path("identityProviderConfig.oidc.identityProviderConfigArn");
            org.hamcrest.MatcherAssert.assertThat(arn, startsWith("arn:aws:eks:us-east-1:" + ACCOUNT
                    + ":identityproviderconfig/" + name + "/oidc/" + configName + "/"));

            eks().get("/clusters/" + name + "/updates/" + associateUpdate)
                    .then().statusCode(200)
                    .body("update.id", equalTo(associateUpdate))
                    .body("update.type", equalTo("AssociateIdentityProviderConfig"));

            eks().body(Map.of("oidc", Map.of("identityProviderConfigName", "second",
                            "issuerUrl", "https://accounts.google.com", "clientId", "other")))
                    .post("/clusters/" + name + "/identity-provider-configs/associate")
                    .then().statusCode(409)
                    .body("__type", equalTo("ResourceInUseException"));

            String disassociateUpdate = eks().body(handle)
                    .post("/clusters/" + name + "/identity-provider-configs/disassociate")
                    .then().statusCode(200)
                    .body("update.type", equalTo("DisassociateIdentityProviderConfig"))
                    .body("update.status", equalTo("Successful"))
                    .extract().path("update.id");

            eks().body(handle)
                    .post("/clusters/" + name + "/identity-provider-configs/describe")
                    .then().statusCode(404)
                    .contentType(containsString("application/json"))
                    .body("__type", equalTo("ResourceNotFoundException"));
            eks().body(handle)
                    .post("/clusters/" + name + "/identity-provider-configs/disassociate")
                    .then().statusCode(404)
                    .body("__type", equalTo("ResourceNotFoundException"));
            eks().get("/clusters/" + name + "/identity-provider-configs")
                    .then().statusCode(200)
                    .body("identityProviderConfigs", empty());

            eks().get("/clusters/" + name + "/updates")
                    .then().statusCode(200)
                    .body("updateIds", containsInAnyOrder(associateUpdate, disassociateUpdate));
            eks().get("/clusters/" + name + "/updates/" + disassociateUpdate)
                    .then().statusCode(200)
                    .body("update.type", equalTo("DisassociateIdentityProviderConfig"));
            eks().queryParam("addonName", "vpc-cni")
                    .get("/clusters/" + name + "/updates/" + associateUpdate)
                    .then().statusCode(404);

            eks().body(Map.of("oidc", Map.of("identityProviderConfigName", configName,
                            "issuerUrl", "http://insecure", "clientId", "c")))
                    .post("/clusters/" + name + "/identity-provider-configs/associate")
                    .then().statusCode(400)
                    .body("__type", equalTo("InvalidParameterException"));
            eks().get("/clusters/no-such-cluster/identity-provider-configs")
                    .then().statusCode(404)
                    .body("__type", equalTo("ResourceNotFoundException"));
        } finally {
            eks().delete("/clusters/" + name);
        }
    }

    private static RequestSpecification eks() {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
                        + "/20261020/us-east-1/eks/aws4_request, SignedHeaders=host, Signature=fake")
                .contentType("application/json");
    }

    private static void createCluster(String name) {
        eks().body(Map.of(
                        "name", name,
                        "roleArn", "arn:aws:iam::" + ACCOUNT + ":role/eks-role",
                        "version", "1.31"))
                .post("/clusters")
                .then().statusCode(200)
                .body("cluster.status", equalTo("ACTIVE"));
    }
}
