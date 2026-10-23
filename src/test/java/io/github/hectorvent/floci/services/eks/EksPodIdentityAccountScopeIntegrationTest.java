package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.core.common.RequestScopes;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The webhook and the credentials endpoint get no AWS credentials, yet serve any account. */
@QuarkusTest
class EksPodIdentityAccountScopeIntegrationTest {

    private static final String ACCOUNT = "111122223333";
    private static final String NAMESPACE = "apps";
    private static final String SERVICE_ACCOUNT = "reader";

    @Inject
    EksOidcService oidcService;

    @Test
    void webhookAndCredentialsServeAClusterOutsideTheDefaultAccount() {
        String name = "pia-acct-" + UUID.randomUUID().toString().substring(0, 8);
        String roleName = "role-" + name;
        createRole(ACCOUNT, roleName);
        createCluster(ACCOUNT, name);
        try {
            given().header("Authorization", auth(ACCOUNT, "eks"))
                    .contentType("application/json")
                    .body(Map.of("addonName", EksPodIdentityWebhook.POD_IDENTITY_ADDON))
                    .post("/clusters/" + name + "/addons")
                    .then().statusCode(200);
            given().header("Authorization", auth(ACCOUNT, "eks"))
                    .contentType("application/json")
                    .body(Map.of("namespace", NAMESPACE, "serviceAccount", SERVICE_ACCOUNT,
                            "roleArn", "arn:aws:iam::" + ACCOUNT + ":role/" + roleName))
                    .post("/clusters/" + name + "/pod-identity-associations")
                    .then().statusCode(200);

            String patch = given().contentType("application/json")
                    .body(admissionReview())
                    .post("/_floci/eks/clusters/" + name + "/pod-identity-webhook/scope/" + ACCOUNT)
                    .then().statusCode(200)
                    .body("response.allowed", equalTo(true))
                    .body("response.patchType", equalTo("JSONPatch"))
                    .extract().path("response.patch");
            String decoded = new String(Base64.getDecoder().decode(patch), StandardCharsets.UTF_8);
            assertTrue(decoded.contains(EksPodIdentityWebhook.CREDENTIALS_FULL_URI), decoded);

            String issuer = given().header("Authorization", auth(ACCOUNT, "eks"))
                    .get("/clusters/" + name)
                    .then().statusCode(200)
                    .extract().path("cluster.identity.oidc.issuer");
            String token = RequestScopes.callAs(ACCOUNT, () -> oidcService.mintServiceAccountToken(
                    name, issuer, NAMESPACE, SERVICE_ACCOUNT, EksPodIdentityWebhook.TOKEN_AUDIENCE, null));

            given().header("Authorization", token)
                    .get("/v1/credentials")
                    .then().statusCode(200)
                    .contentType(containsString("application/json"))
                    .body("AccountId", equalTo(ACCOUNT));
        } finally {
            given().header("Authorization", auth(ACCOUNT, "eks"))
                    .delete("/clusters/" + name).then().statusCode(200);
        }
    }

    private static Map<String, Object> admissionReview() {
        Map<String, Object> pod = Map.of(
                "metadata", Map.of("name", "probe", "namespace", NAMESPACE),
                "spec", Map.of(
                        "serviceAccountName", SERVICE_ACCOUNT,
                        "containers", List.of(Map.of("name", "main", "image", "busybox"))));
        return Map.of(
                "apiVersion", "admission.k8s.io/v1",
                "kind", "AdmissionReview",
                "request", Map.of("uid", "uid-1", "namespace", NAMESPACE, "object", pod));
    }

    private static void createRole(String account, String roleName) {
        given().header("Authorization", auth(account, "iam"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateRole")
                .formParam("RoleName", roleName)
                .formParam("Path", "/")
                .formParam("AssumeRolePolicyDocument", "{}")
                .post("/").then().statusCode(200);
    }

    private static void createCluster(String account, String name) {
        given().header("Authorization", auth(account, "eks"))
                .contentType("application/json")
                .body(Map.of("name", name, "roleArn", "arn:aws:iam::" + account + ":role/cluster"))
                .post("/clusters").then().statusCode(200);
    }

    private static String auth(String account, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + account
                + "/20260920/us-east-1/" + service + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
