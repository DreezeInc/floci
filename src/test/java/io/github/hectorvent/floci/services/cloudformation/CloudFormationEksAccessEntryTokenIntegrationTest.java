package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.services.eks.EksService;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.github.hectorvent.floci.testutil.SigV4TokenTestHelper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.path.xml.XmlPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudFormationEksAccessEntryTokenIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String STS_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/sts/aws4_request";

    @Inject
    EksService eksService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void stackAccessEntryWithClusterAdminAuthenticatesTheRolesAssumedSessionAsClusterAdmin() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String clusterName = "demo-cluster-" + suffix;
        String adminRole = "deployer-role-" + suffix;
        String otherRole = "reader-role-" + suffix;
        String stackName = "demo-stack-" + suffix;
        given().contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", "CreateStack")
                .formParam("StackName", stackName)
                .formParam("Capabilities.member.1", "CAPABILITY_NAMED_IAM")
                .formParam("TemplateBody", template(clusterName, adminRole, otherRole))
                .post("/").then()
                .statusCode(200);
        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stackName, CFN_AUTH);
        assertEquals("CREATE_COMPLETE", state.status(), state.reason());
        Cluster cluster = eksService.findAuthenticationCluster(ACCOUNT, clusterName).orElseThrow();
        String adminArn = "arn:aws:iam::" + ACCOUNT + ":role/platform/" + adminRole;

        JsonPath admin = review(cluster, adminArn, "ci-run");
        assertTrue(admin.getBoolean("status.authenticated"), admin.prettify());
        assertEquals("arn:aws:sts::" + ACCOUNT + ":assumed-role/" + adminRole + "/ci-run",
                admin.getString("status.user.username"));
        assertTrue(admin.getList("status.user.groups").contains("system:masters"), admin.prettify());
        assertEquals(List.of("arn:aws:iam::" + ACCOUNT + ":role/" + adminRole),
                admin.getList("status.user.extra.canonicalArn"));

        JsonPath other = review(cluster, "arn:aws:iam::" + ACCOUNT + ":role/" + otherRole, "ci-run");
        assertFalse(other.getBoolean("status.authenticated"), "A role of the stack without an access entry stays out");
    }

    private JsonPath review(Cluster cluster, String roleArn, String sessionName) throws Exception {
        XmlPath assumed = given().contentType("application/x-www-form-urlencoded")
                .header("Authorization", STS_AUTH)
                .formParam("Action", "AssumeRole")
                .formParam("RoleArn", roleArn)
                .formParam("RoleSessionName", sessionName)
                .post("/").then().statusCode(200)
                .extract().xmlPath();
        String credentials = "AssumeRoleResponse.AssumeRoleResult.Credentials.";
        String token = SigV4TokenTestHelper.createEksToken(cluster.getName(),
                assumed.getString(credentials + "AccessKeyId"), assumed.getString(credentials + "SecretAccessKey"),
                Instant.now(), 60, assumed.getString(credentials + "SessionToken"));
        String path = "/_floci/eks/clusters/" + cluster.getName() + "/token-webhook/scope/" + ACCOUNT + "/us-east-1/"
                + cluster.getCreatedAt();
        return given().contentType("application/json")
                .body(Map.of("apiVersion", "authentication.k8s.io/v1", "kind", "TokenReview",
                        "spec", Map.of("token", token)))
                .post(path).then().statusCode(200)
                .extract().jsonPath();
    }

    private static String template(String clusterName, String adminRole, String otherRole) {
        return """
                {
                  "Resources": {
                    "DeployerRole": {
                      "Type": "AWS::IAM::Role",
                      "Properties": {
                        "RoleName": "%2$s",
                        "Path": "/platform/",
                        "AssumeRolePolicyDocument": {
                          "Version": "2012-10-17",
                          "Statement": [{"Effect": "Allow", "Principal": {"AWS": "*"}, "Action": "sts:AssumeRole"}]
                        }
                      }
                    },
                    "ReaderRole": {
                      "Type": "AWS::IAM::Role",
                      "Properties": {
                        "RoleName": "%3$s",
                        "AssumeRolePolicyDocument": {
                          "Version": "2012-10-17",
                          "Statement": [{"Effect": "Allow", "Principal": {"AWS": "*"}, "Action": "sts:AssumeRole"}]
                        }
                      }
                    },
                    "Cluster": {
                      "Type": "AWS::EKS::Cluster",
                      "Properties": {
                        "Name": "%1$s",
                        "RoleArn": {"Fn::GetAtt": ["DeployerRole", "Arn"]},
                        "AccessConfig": {"AuthenticationMode": "API_AND_CONFIG_MAP"}
                      }
                    },
                    "DeployerAccess": {
                      "Type": "AWS::EKS::AccessEntry",
                      "Properties": {
                        "ClusterName": {"Ref": "Cluster"},
                        "PrincipalArn": {"Fn::GetAtt": ["DeployerRole", "Arn"]},
                        "Type": "STANDARD",
                        "AccessPolicies": [{"AccessScope": {"Type": "cluster"},
                                            "PolicyArn": "arn:aws:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy"}]
                      }
                    }
                  }
                }
                """.formatted(clusterName, adminRole, otherRole);
    }
}
