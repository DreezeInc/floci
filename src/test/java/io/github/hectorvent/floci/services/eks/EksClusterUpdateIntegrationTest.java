package io.github.hectorvent.floci.services.eks;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class EksClusterUpdateIntegrationTest {

    private static final String ACCOUNT = "123456789012";

    @Test
    void clusterUpdatesRoundTrip() {
        String name = "cluster-update-it-" + UUID.randomUUID().toString().substring(0, 8);
        createCluster(name);
        try {
            String vpcUpdate = eks().body(Map.of("resourcesVpcConfig", Map.of(
                            "endpointPublicAccess", false,
                            "endpointPrivateAccess", true,
                            "publicAccessCidrs", List.of("203.0.113.0/24"))))
                    .post("/clusters/" + name + "/update-config")
                    .then().statusCode(200)
                    .contentType(containsString("application/json"))
                    .body("update.type", equalTo("VpcConfigUpdate"))
                    .body("update.status", equalTo("Successful"))
                    .body("update.errors", empty())
                    .body("update.createdAt", instanceOf(Number.class))
                    .body("update.params.type", containsInAnyOrder(
                            "EndpointPublicAccess", "EndpointPrivateAccess", "PublicAccessCidrs"))
                    .body("update.params.find { it.type == 'PublicAccessCidrs' }.value",
                            equalTo("[\"203.0.113.0/24\"]"))
                    .extract().path("update.id");

            eks().get("/clusters/" + name)
                    .then().statusCode(200)
                    .body("cluster.resourcesVpcConfig.endpointPublicAccess", equalTo(false))
                    .body("cluster.resourcesVpcConfig.endpointPrivateAccess", equalTo(true))
                    .body("cluster.resourcesVpcConfig.publicAccessCidrs", contains("203.0.113.0/24"));

            String loggingUpdate = eks().body(Map.of("logging", Map.of("clusterLogging",
                            List.of(Map.of("types", List.of("api", "audit"), "enabled", true)))))
                    .post("/clusters/" + name + "/update-config")
                    .then().statusCode(200)
                    .body("update.type", equalTo("LoggingUpdate"))
                    .body("update.status", equalTo("Successful"))
                    .body("update.params[0].type", equalTo("ClusterLogging"))
                    .extract().path("update.id");

            eks().get("/clusters/" + name)
                    .then().statusCode(200)
                    .body("cluster.logging.clusterLogging.find { it.enabled == true }.types", contains("api", "audit"))
                    .body("cluster.logging.clusterLogging.find { it.enabled == false }.types",
                            contains("authenticator", "controllerManager", "scheduler"));

            String accessUpdate = eks().body(Map.of("accessConfig", Map.of("authenticationMode", "API_AND_CONFIG_MAP")))
                    .post("/clusters/" + name + "/update-config")
                    .then().statusCode(200)
                    .body("update.type", equalTo("AccessConfigUpdate"))
                    .body("update.params[0].type", equalTo("AuthenticationMode"))
                    .body("update.params[0].value", equalTo("API_AND_CONFIG_MAP"))
                    .extract().path("update.id");

            eks().get("/clusters/" + name)
                    .then().statusCode(200)
                    .body("cluster.accessConfig.authenticationMode", equalTo("API_AND_CONFIG_MAP"))
                    .body("cluster.accessConfig.bootstrapClusterCreatorAdminPermissions", equalTo(true));

            eks().body(Map.of(
                            "resourcesVpcConfig", Map.of("endpointPrivateAccess", true),
                            "logging", Map.of("clusterLogging", List.of(Map.of("types", List.of("api"), "enabled", true)))))
                    .post("/clusters/" + name + "/update-config")
                    .then().statusCode(400)
                    .contentType(containsString("application/json"))
                    .body("__type", equalTo("InvalidParameterException"));

            eks().body(Map.of("clientRequestToken", "nothing-to-do"))
                    .post("/clusters/" + name + "/update-config")
                    .then().statusCode(400)
                    .body("__type", equalTo("InvalidParameterException"));

            String versionUpdate = eks().body(Map.of("version", "1.30"))
                    .post("/clusters/" + name + "/updates")
                    .then().statusCode(200)
                    .body("update.type", equalTo("VersionUpdate"))
                    .body("update.status", equalTo("Successful"))
                    .body("update.params[0].type", equalTo("Version"))
                    .body("update.params[0].value", equalTo("1.30"))
                    .extract().path("update.id");

            eks().get("/clusters/" + name)
                    .then().statusCode(200)
                    .body("cluster.version", equalTo("1.30"))
                    .body("cluster", not(hasKey("explicitVersion")));

            eks().body(Map.of("version", "1.33"))
                    .post("/clusters/" + name + "/updates")
                    .then().statusCode(400)
                    .body("__type", equalTo("InvalidParameterException"));

            eks().body(Map.of("addonName", "vpc-cni"))
                    .post("/clusters/" + name + "/addons")
                    .then().statusCode(200);
            String addonUpdate = eks().body(Map.of("addonVersion", "v1.18.5-eksbuild.1"))
                    .post("/clusters/" + name + "/addons/vpc-cni/update")
                    .then().statusCode(200)
                    .body("update.type", equalTo("AddonUpdate"))
                    .extract().path("update.id");

            eks().get("/clusters/" + name + "/updates")
                    .then().statusCode(200)
                    .contentType(containsString("application/json"))
                    .body("updateIds", containsInAnyOrder(vpcUpdate, loggingUpdate, accessUpdate, versionUpdate, addonUpdate))
                    .body("$", not(hasKey("nextToken")));

            eks().queryParam("addonName", "vpc-cni")
                    .get("/clusters/" + name + "/updates")
                    .then().statusCode(200)
                    .body("updateIds", contains(addonUpdate));

            eks().queryParam("nodegroupName", "workers")
                    .get("/clusters/" + name + "/updates")
                    .then().statusCode(200)
                    .body("updateIds", empty());

            String nextToken = eks().queryParam("maxResults", "2")
                    .get("/clusters/" + name + "/updates")
                    .then().statusCode(200)
                    .body("updateIds", hasSize(2))
                    .body("nextToken", notNullValue())
                    .extract().path("nextToken");
            eks().queryParam("maxResults", "3").queryParam("nextToken", nextToken)
                    .get("/clusters/" + name + "/updates")
                    .then().statusCode(200)
                    .body("updateIds", hasSize(3))
                    .body("$", not(hasKey("nextToken")));

            eks().get("/clusters/" + name + "/updates/" + versionUpdate)
                    .then().statusCode(200)
                    .body("update.id", equalTo(versionUpdate))
                    .body("update.type", equalTo("VersionUpdate"));

            eks().get("/clusters/" + name + "/updates/" + addonUpdate)
                    .then().statusCode(200)
                    .body("update.id", equalTo(addonUpdate))
                    .body("update.type", equalTo("AddonUpdate"));

            eks().queryParam("addonName", "vpc-cni")
                    .get("/clusters/" + name + "/updates/" + addonUpdate)
                    .then().statusCode(200)
                    .body("update.type", equalTo("AddonUpdate"));

            eks().queryParam("addonName", "vpc-cni")
                    .get("/clusters/" + name + "/updates/" + versionUpdate)
                    .then().statusCode(404)
                    .body("__type", equalTo("ResourceNotFoundException"));

            eks().get("/clusters/" + name + "/updates/" + UUID.randomUUID())
                    .then().statusCode(404)
                    .contentType(containsString("application/json"))
                    .body("__type", equalTo("ResourceNotFoundException"));

            eks().get("/clusters/no-such-cluster/updates")
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
                        "version", "1.29",
                        "accessConfig", Map.of("authenticationMode", "CONFIG_MAP",
                                "bootstrapClusterCreatorAdminPermissions", true)))
                .post("/clusters")
                .then().statusCode(200)
                .body("cluster.name", equalTo(name))
                .body("cluster.status", equalTo("ACTIVE"));
    }
}
