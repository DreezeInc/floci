package io.github.hectorvent.floci.services.eks;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class EksAccessPolicyIntegrationTest {
    private static final String ACCOUNT = "123456789012";
    private static final String VIEW = "arn:aws:eks::aws:cluster-access-policy/AmazonEKSViewPolicy";
    private static final String ADMIN = "arn:aws:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy";

    @Test
    void associationLifecycleUsesEncodedPrincipalAndPolicyPaths() {
        String name = "policies-" + UUID.randomUUID().toString().substring(0, 8);
        String role = "admin-" + name;
        String principal = "arn:aws:iam::" + ACCOUNT + ":role/team/" + role;
        String entryPath = "/clusters/" + name + "/access-entries/" + encode(principal);
        String policiesPath = entryPath + "/access-policies";
        createRole(role);
        createCluster(name);
        try {
            createEntry(name, principal);
            Object associatedAt = eks().urlEncodingEnabled(false).contentType("application/json")
                    .body(Map.of("policyArn", VIEW,
                            "accessScope", Map.of("type", "namespace", "namespaces", List.of("dev-*", "team"))))
                    .post(policiesPath).then().statusCode(200)
                    .body("clusterName", equalTo(name))
                    .body("principalArn", equalTo(principal))
                    .body("associatedAccessPolicy.policyArn", equalTo(VIEW))
                    .body("associatedAccessPolicy.accessScope.type", equalTo("namespace"))
                    .body("associatedAccessPolicy.accessScope.namespaces", contains("dev-*", "team"))
                    .body("associatedAccessPolicy.associatedAt", instanceOf(Number.class))
                    .body("associatedAccessPolicy.modifiedAt", instanceOf(Number.class))
                    .extract().path("associatedAccessPolicy.associatedAt");
            eks().urlEncodingEnabled(false).contentType("application/json")
                    .body(Map.of("policyArn", VIEW, "accessScope", Map.of("type", "cluster", "namespaces", List.of())))
                    .post(policiesPath).then().statusCode(200)
                    .body("associatedAccessPolicy.accessScope.type", equalTo("cluster"))
                    .body("associatedAccessPolicy.accessScope.namespaces", empty())
                    .body("associatedAccessPolicy.associatedAt", equalTo(associatedAt));
            eks().urlEncodingEnabled(false).contentType("application/json")
                    .body(Map.of("policyArn", ADMIN, "accessScope", Map.of("type", "cluster")))
                    .post(policiesPath).then().statusCode(200);

            String token = eks().urlEncodingEnabled(false).queryParam("maxResults", 1)
                    .get(policiesPath).then().statusCode(200)
                    .body("clusterName", equalTo(name))
                    .body("principalArn", equalTo(principal))
                    .body("associatedAccessPolicies.policyArn", contains(ADMIN))
                    .body("nextToken", notNullValue())
                    .extract().path("nextToken");
            eks().urlEncodingEnabled(false).queryParam("maxResults", 1).queryParam("nextToken", token)
                    .get(policiesPath).then().statusCode(200)
                    .body("associatedAccessPolicies.policyArn", contains(VIEW))
                    .body("associatedAccessPolicies[0].accessScope.type", equalTo("cluster"))
                    .body("nextToken", nullValue());

            eks().urlEncodingEnabled(false).delete(policiesPath + "/" + encode(ADMIN))
                    .then().statusCode(200).body(equalTo("{}"));
            eks().urlEncodingEnabled(false).delete(policiesPath + "/" + encode(ADMIN))
                    .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
            eks().urlEncodingEnabled(false).get(policiesPath).then().statusCode(200)
                    .body("associatedAccessPolicies.policyArn", contains(VIEW));

            eks().urlEncodingEnabled(false).contentType("application/json")
                    .body(Map.of("kubernetesGroups", List.of("editors"), "clientRequestToken", "update-token"))
                    .post(entryPath).then().statusCode(200)
                    .body("accessEntry.principalArn", equalTo(principal))
                    .body("accessEntry.kubernetesGroups", contains("editors"))
                    .body("accessEntry.username",
                            equalTo("arn:aws:sts::" + ACCOUNT + ":assumed-role/" + role + "/{{SessionName}}"))
                    .body("accessEntry.modifiedAt", instanceOf(Number.class));
            eks().urlEncodingEnabled(false).get(entryPath).then().statusCode(200)
                    .body("accessEntry.kubernetesGroups", contains("editors"));
            eks().urlEncodingEnabled(false).get(policiesPath).then().statusCode(200)
                    .body("associatedAccessPolicies.policyArn", contains(VIEW));

            eks().urlEncodingEnabled(false).delete(entryPath).then().statusCode(200);
            eks().urlEncodingEnabled(false).get(policiesPath).then().statusCode(404)
                    .body("__type", equalTo("ResourceNotFoundException"))
                    .body("message", equalTo("The specified principalArn could not be found."));
            createEntry(name, principal);
            eks().urlEncodingEnabled(false).get(policiesPath).then().statusCode(200)
                    .body("associatedAccessPolicies", empty());
        } finally {
            deleteClusterAndRole(name, role);
        }
    }

    @Test
    void invalidAssociationsAndUnknownEntriesReturnAwsJsonErrors() {
        String name = "policy-errors-" + UUID.randomUUID().toString().substring(0, 8);
        String role = "reader-" + name;
        String principal = "arn:aws:iam::" + ACCOUNT + ":role/team/" + role;
        String policiesPath = "/clusters/" + name + "/access-entries/" + encode(principal) + "/access-policies";
        createRole(role);
        createCluster(name);
        try {
            createEntry(name, principal);
            for (Map<String, Object> body : List.<Map<String, Object>>of(
                    Map.of("policyArn", "arn:aws:eks::aws:cluster-access-policy/AmazonEKSUnknownPolicy",
                            "accessScope", Map.of("type", "cluster")),
                    Map.of("policyArn", VIEW),
                    Map.of("policyArn", VIEW, "accessScope", Map.of("type", "namespace")),
                    Map.of("policyArn", VIEW, "accessScope", Map.of("type", "cluster", "namespaces", List.of("a"))),
                    Map.of("policyArn", VIEW, "accessScope", Map.of("type", "account")))) {
                eks().urlEncodingEnabled(false).contentType("application/json").body(body)
                        .post(policiesPath).then().statusCode(400)
                        .contentType(containsString("application/json"))
                        .body("__type", equalTo("InvalidParameterException"));
            }
            eks().urlEncodingEnabled(false).queryParam("maxResults", "abc").get(policiesPath)
                    .then().statusCode(400).body("__type", equalTo("InvalidParameterException"));
            eks().urlEncodingEnabled(false).contentType("application/json")
                    .body(Map.of("policyArn", VIEW, "accessScope", Map.of("type", "cluster")))
                    .post(policiesPath.replace(encode(role), encode(role + "-missing")))
                    .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
            eks().urlEncodingEnabled(false).contentType("application/json")
                    .body(Map.of("policyArn", VIEW, "accessScope", Map.of("type", "cluster")))
                    .post("/clusters/" + name + "-missing/access-entries/" + encode(principal) + "/access-policies")
                    .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
        } finally {
            deleteClusterAndRole(name, role);
        }
    }

    @Test
    void listAccessPoliciesReturnsTheManagedCatalogInPages() {
        eks().get("/access-policies").then().statusCode(200)
                .contentType(containsString("application/json"))
                .body("accessPolicies.name", hasItems("AmazonEKSClusterAdminPolicy", "AmazonEKSAdminPolicy",
                        "AmazonEKSEditPolicy", "AmazonEKSViewPolicy"))
                .body("accessPolicies.find { it.name == 'AmazonEKSClusterAdminPolicy' }.arn", equalTo(ADMIN))
                .body("nextToken", nullValue());
        String token = eks().queryParam("maxResults", 2).get("/access-policies").then().statusCode(200)
                .body("accessPolicies", hasSize(2))
                .extract().path("nextToken");
        eks().queryParam("maxResults", 2).queryParam("nextToken", token).get("/access-policies")
                .then().statusCode(200).body("accessPolicies", hasSize(2));
        eks().queryParam("maxResults", 0).get("/access-policies").then().statusCode(400)
                .body("__type", equalTo("InvalidParameterException"));
    }

    private static void createEntry(String cluster, String principal) {
        eks().contentType("application/json")
                .body(Map.of("principalArn", principal, "kubernetesGroups", List.of("readers")))
                .post("/clusters/" + cluster + "/access-entries").then().statusCode(200);
    }

    private static void createRole(String role) {
        given().header("Authorization", auth("iam")).contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateRole").formParam("RoleName", role).formParam("Path", "/team/")
                .formParam("AssumeRolePolicyDocument", "{}").post("/").then().statusCode(200);
    }

    private static void createCluster(String name) {
        eks().contentType("application/json")
                .body(Map.of("name", name, "roleArn", "arn:aws:iam::" + ACCOUNT + ":role/cluster",
                        "accessConfig", Map.of("authenticationMode", "API",
                                "bootstrapClusterCreatorAdminPermissions", false)))
                .post("/clusters").then().statusCode(200);
    }

    private static void deleteClusterAndRole(String name, String role) {
        eks().delete("/clusters/" + name).then().statusCode(200);
        given().header("Authorization", auth("iam")).contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteRole").formParam("RoleName", role).post("/").then().statusCode(200);
    }

    private static RequestSpecification eks() {
        return given().header("Authorization", auth("eks"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String auth(String service) {
        return "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
                + "/20260916/us-east-1/" + service + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
