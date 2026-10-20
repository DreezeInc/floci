package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The resource types an EKS workload stack declares around its cluster, provisioned for real through
 * CloudFormation: EKS addon, access entry and pod identity association, the IAM OIDC provider for
 * the cluster issuer, the EFS file system with its access point and mount target, and an EBS volume.
 * EKS runs in mock mode under test, so no cluster container starts.
 */
@QuarkusTest
class CloudFormationEksSubResourcesIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/iam/aws4_request";
    private static final String EC2_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";
    private static final String STUB_ARN_PREFIX = "arn:aws:stub:::";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void stackCreatesEveryWorkloadResourceForRealAndDeleteRemovesThem() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String clusterName = "cfn-eks-sub-" + suffix;
        String roleName = "cfn-eks-sub-role-" + suffix;
        String stackName = "cfn-eks-sub-stack-" + suffix;

        createStack(stackName, template(clusterName, roleName));
        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stackName, CFN_AUTH);
        assertEquals("CREATE_COMPLETE", state.status(), state.reason());

        String described = describeStack(stackName);
        assertThat(described, not(containsString(STUB_ARN_PREFIX)));
        Map<String, String> outputs = XmlParser.extractPairs(described, "Outputs", "OutputKey", "OutputValue");

        String roleArn = outputs.get("RoleArn");
        assertThat(roleArn, startsWith("arn:aws:iam::"));
        assertEquals(clusterName + "|vpc-cni", outputs.get("AddonRef"));
        assertThat(outputs.get("AddonArn"), startsWith("arn:aws:eks:us-east-1:"));
        assertEquals(roleArn + "|" + clusterName, outputs.get("AccessEntryRef"));
        assertThat(outputs.get("AccessEntryArn"), containsString(":access-entry/" + clusterName + "/"));
        assertThat(outputs.get("AssociationArn"), containsString(":podidentityassociation/" + clusterName + "/"));
        assertEquals(outputs.get("AssociationArn"), outputs.get("AssociationRef"));
        assertThat(outputs.get("AssociationId"), startsWith("a-"));
        assertThat(outputs.get("OidcArn"), startsWith("arn:aws:iam::"));
        assertThat(outputs.get("OidcArn"), containsString(":oidc-provider/"));
        assertEquals(outputs.get("OidcArn"), outputs.get("OidcRef"));
        assertThat(outputs.get("FileSystemId"), startsWith("fs-"));
        assertEquals(outputs.get("FileSystemId"), outputs.get("FileSystemRef"));
        assertThat(outputs.get("FileSystemArn"), containsString(":file-system/" + outputs.get("FileSystemId")));
        assertThat(outputs.get("AccessPointId"), startsWith("fsap-"));
        assertEquals(outputs.get("AccessPointId"), outputs.get("AccessPointRef"));
        assertThat(outputs.get("AccessPointArn"), containsString(":access-point/" + outputs.get("AccessPointId")));
        assertThat(outputs.get("MountTargetId"), startsWith("fsmt-"));
        assertEquals(outputs.get("MountTargetId"), outputs.get("MountTargetRef"));
        assertThat(outputs.get("MountTargetIp"), not(equalTo("")));
        assertThat(outputs.get("VolumeId"), startsWith("vol-"));
        assertEquals(outputs.get("VolumeId"), outputs.get("VolumeRef"));

        given().get("/clusters/" + clusterName + "/addons/vpc-cni").then()
                .statusCode(200)
                .body("addon.addonArn", equalTo(outputs.get("AddonArn")))
                .body("addon.addonVersion", equalTo("v1.18.5-eksbuild.1"))
                .body("addon.serviceAccountRoleArn", equalTo(roleArn))
                .body("addon.tags.Name", equalTo("cni"));
        given().urlEncodingEnabled(false).get("/clusters/" + clusterName + "/access-entries/" + roleArn).then()
                .statusCode(200)
                .body("accessEntry.accessEntryArn", equalTo(outputs.get("AccessEntryArn")))
                .body("accessEntry.type", equalTo("STANDARD"))
                .body("accessEntry.kubernetesGroups", equalTo(List.of("viewers")));
        given().get("/clusters/" + clusterName + "/pod-identity-associations/" + outputs.get("AssociationId")).then()
                .statusCode(200)
                .body("association.associationArn", equalTo(outputs.get("AssociationArn")))
                .body("association.namespace", equalTo("default"))
                .body("association.serviceAccount", equalTo("app"))
                .body("association.roleArn", equalTo(roleArn));
        given().get("/clusters/" + clusterName).then()
                .statusCode(200)
                .body("cluster.accessConfig.authenticationMode", equalTo("API_AND_CONFIG_MAP"))
                .body("cluster.tags.application", equalTo("demo"));
        given().header("Authorization", IAM_AUTH)
                .formParam("Action", "GetOpenIDConnectProvider")
                .formParam("OpenIDConnectProviderArn", outputs.get("OidcArn"))
                .post("/").then()
                .statusCode(200)
                .body("GetOpenIDConnectProviderResponse.GetOpenIDConnectProviderResult.ClientIDList.member",
                        equalTo("sts.amazonaws.com"));
        given().queryParam("FileSystemId", outputs.get("FileSystemId")).get("/2015-02-01/file-systems").then()
                .statusCode(200)
                .body("FileSystems.size()", equalTo(1))
                .body("FileSystems[0].Encrypted", equalTo(true))
                .body("FileSystems[0].PerformanceMode", equalTo("generalPurpose"));
        given().get("/2015-02-01/file-systems/" + outputs.get("FileSystemId") + "/backup-policy").then()
                .statusCode(200)
                .body("BackupPolicy.Status", equalTo("ENABLED"));
        given().get("/2015-02-01/file-systems/" + outputs.get("FileSystemId") + "/policy").then()
                .statusCode(200)
                .body("Policy", containsString("elasticfilesystem:ClientMount"));
        given().get("/2015-02-01/file-systems/" + outputs.get("FileSystemId") + "/lifecycle-configuration").then()
                .statusCode(200)
                .body("LifecyclePolicies[0].TransitionToIA", equalTo("AFTER_30_DAYS"));
        given().queryParam("AccessPointId", outputs.get("AccessPointId")).get("/2015-02-01/access-points").then()
                .statusCode(200)
                .body("AccessPoints.size()", equalTo(1))
                .body("AccessPoints[0].FileSystemId", equalTo(outputs.get("FileSystemId")))
                .body("AccessPoints[0].PosixUser.Uid", equalTo(1000))
                .body("AccessPoints[0].RootDirectory.Path", equalTo("/eks-data"));
        given().queryParam("MountTargetId", outputs.get("MountTargetId")).get("/2015-02-01/mount-targets").then()
                .statusCode(200)
                .body("MountTargets.size()", equalTo(1))
                .body("MountTargets[0].SubnetId", equalTo("subnet-aaaa1111"))
                .body("MountTargets[0].IpAddress", equalTo(outputs.get("MountTargetIp")));
        given().header("Authorization", EC2_AUTH)
                .formParam("Action", "DescribeVolumes")
                .formParam("VolumeId.1", outputs.get("VolumeId"))
                .post("/").then()
                .statusCode(200)
                .body("DescribeVolumesResponse.volumeSet.item.volumeType", equalTo("gp3"))
                .body("DescribeVolumesResponse.volumeSet.item.size", equalTo("10"))
                .body("DescribeVolumesResponse.volumeSet.item.encrypted", equalTo("true"));

        given().contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", "DeleteStack")
                .formParam("StackName", stackName)
                .post("/").then()
                .statusCode(200);
        CfnStackWaits.awaitStackDeleted(stackName, CFN_AUTH);

        given().get("/clusters/" + clusterName).then().statusCode(404);
        given().header("Authorization", IAM_AUTH)
                .formParam("Action", "GetOpenIDConnectProvider")
                .formParam("OpenIDConnectProviderArn", outputs.get("OidcArn"))
                .post("/").then()
                .statusCode(404);
        given().header("Authorization", IAM_AUTH)
                .formParam("Action", "GetRole")
                .formParam("RoleName", roleName)
                .post("/").then()
                .statusCode(404);
        given().queryParam("FileSystemId", outputs.get("FileSystemId")).get("/2015-02-01/file-systems").then()
                .statusCode(404)
                .body("__type", equalTo("FileSystemNotFound"));
        given().queryParam("AccessPointId", outputs.get("AccessPointId")).get("/2015-02-01/access-points").then()
                .statusCode(200)
                .body("AccessPoints.size()", equalTo(0));
        given().queryParam("MountTargetId", outputs.get("MountTargetId")).get("/2015-02-01/mount-targets").then()
                .statusCode(200)
                .body("MountTargets.size()", equalTo(0));
        given().header("Authorization", EC2_AUTH)
                .formParam("Action", "DescribeVolumes")
                .formParam("VolumeId.1", outputs.get("VolumeId"))
                .post("/").then()
                .statusCode(400)
                .body(containsString("InvalidVolume.NotFound"));
    }

    private static void createStack(String stackName, String template) {
        given().contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", "CreateStack")
                .formParam("StackName", stackName)
                .formParam("Capabilities.member.1", "CAPABILITY_NAMED_IAM")
                .formParam("TemplateBody", template)
                .post("/").then()
                .statusCode(200);
    }

    private static String describeStack(String stackName) {
        return given().contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stackName)
                .post("/").then()
                .statusCode(200)
                .extract().asString();
    }

    private static String template(String clusterName, String roleName) {
        return """
                {
                  "Resources": {
                    "Role": {
                      "Type": "AWS::IAM::Role",
                      "Properties": {
                        "RoleName": "%2$s",
                        "AssumeRolePolicyDocument": {
                          "Version": "2012-10-17",
                          "Statement": [{"Effect": "Allow", "Principal": {"Service": "eks.amazonaws.com"},
                                         "Action": "sts:AssumeRole"}]
                        }
                      }
                    },
                    "Cluster": {
                      "Type": "AWS::EKS::Cluster",
                      "Properties": {
                        "Name": "%1$s",
                        "Version": "1.31",
                        "RoleArn": {"Fn::GetAtt": ["Role", "Arn"]},
                        "AccessConfig": {"AuthenticationMode": "API_AND_CONFIG_MAP",
                                         "BootstrapClusterCreatorAdminPermissions": false},
                        "Tags": [{"Key": "application", "Value": "demo"}]
                      }
                    },
                    "Oidc": {
                      "Type": "AWS::IAM::OIDCProvider",
                      "Properties": {
                        "Url": {"Fn::GetAtt": ["Cluster", "OpenIdConnectIssuerUrl"]},
                        "ClientIdList": ["sts.amazonaws.com"],
                        "ThumbprintList": ["9e99a48a9960b14926bb7f3b02e22da2b0ab7280"],
                        "Tags": [{"Key": "Name", "Value": "%1$s-oidc"}]
                      }
                    },
                    "Cni": {
                      "Type": "AWS::EKS::Addon",
                      "Properties": {
                        "ClusterName": {"Ref": "Cluster"},
                        "AddonName": "vpc-cni",
                        "AddonVersion": "v1.18.5-eksbuild.1",
                        "ServiceAccountRoleArn": {"Fn::GetAtt": ["Role", "Arn"]},
                        "ResolveConflicts": "OVERWRITE",
                        "ConfigurationValues": "{\\"env\\":{\\"ENABLE_PREFIX_DELEGATION\\":\\"true\\"}}",
                        "Tags": [{"Key": "Name", "Value": "cni"}]
                      }
                    },
                    "Ops": {
                      "Type": "AWS::EKS::AccessEntry",
                      "Properties": {
                        "ClusterName": {"Ref": "Cluster"},
                        "PrincipalArn": {"Fn::GetAtt": ["Role", "Arn"]},
                        "Type": "STANDARD",
                        "KubernetesGroups": ["viewers"],
                        "AccessPolicies": [{"AccessScope": {"Type": "cluster"},
                                            "PolicyArn": "arn:aws:eks::aws:cluster-access-policy/AmazonEKSViewPolicy"}],
                        "Tags": [{"Key": "Name", "Value": "ops"}]
                      }
                    },
                    "Pia": {
                      "Type": "AWS::EKS::PodIdentityAssociation",
                      "Properties": {
                        "ClusterName": {"Ref": "Cluster"},
                        "Namespace": "default",
                        "ServiceAccount": "app",
                        "RoleArn": {"Fn::GetAtt": ["Role", "Arn"]},
                        "Tags": [{"Key": "Name", "Value": "pia"}]
                      }
                    },
                    "Fs": {
                      "Type": "AWS::EFS::FileSystem",
                      "Properties": {
                        "Encrypted": true,
                        "PerformanceMode": "generalPurpose",
                        "ThroughputMode": "bursting",
                        "FileSystemTags": [{"Key": "Name", "Value": "%1$s-efs"}],
                        "BackupPolicy": {"Status": "ENABLED"},
                        "LifecyclePolicies": [{"TransitionToIA": "AFTER_30_DAYS"}],
                        "FileSystemPolicy": {
                          "Version": "2012-10-17",
                          "Statement": [{"Effect": "Allow", "Principal": {"AWS": "*"},
                                         "Action": ["elasticfilesystem:ClientMount"], "Resource": "*"}]
                        }
                      }
                    },
                    "Ap": {
                      "Type": "AWS::EFS::AccessPoint",
                      "Properties": {
                        "FileSystemId": {"Ref": "Fs"},
                        "PosixUser": {"Uid": "1000", "Gid": "1000"},
                        "RootDirectory": {"Path": "/eks-data",
                                          "CreationInfo": {"OwnerUid": "1000", "OwnerGid": "1000", "Permissions": "755"}},
                        "AccessPointTags": [{"Key": "Name", "Value": "%1$s-ap"}]
                      }
                    },
                    "Mt": {
                      "Type": "AWS::EFS::MountTarget",
                      "Properties": {
                        "FileSystemId": {"Ref": "Fs"},
                        "SubnetId": "subnet-aaaa1111",
                        "SecurityGroups": ["sg-aaaa1111"]
                      }
                    },
                    "Vol": {
                      "Type": "AWS::EC2::Volume",
                      "Properties": {
                        "AvailabilityZone": "us-east-1a",
                        "Size": 10,
                        "VolumeType": "gp3",
                        "Encrypted": true,
                        "KmsKeyId": "alias/aws/ebs",
                        "Tags": [{"Key": "Name", "Value": "%1$s-data"}]
                      }
                    }
                  },
                  "Outputs": {
                    "RoleArn": {"Value": {"Fn::GetAtt": ["Role", "Arn"]}},
                    "AddonRef": {"Value": {"Ref": "Cni"}},
                    "AddonArn": {"Value": {"Fn::GetAtt": ["Cni", "Arn"]}},
                    "AccessEntryRef": {"Value": {"Ref": "Ops"}},
                    "AccessEntryArn": {"Value": {"Fn::GetAtt": ["Ops", "AccessEntryArn"]}},
                    "AssociationRef": {"Value": {"Ref": "Pia"}},
                    "AssociationArn": {"Value": {"Fn::GetAtt": ["Pia", "AssociationArn"]}},
                    "AssociationId": {"Value": {"Fn::GetAtt": ["Pia", "AssociationId"]}},
                    "OidcRef": {"Value": {"Ref": "Oidc"}},
                    "OidcArn": {"Value": {"Fn::GetAtt": ["Oidc", "Arn"]}},
                    "FileSystemRef": {"Value": {"Ref": "Fs"}},
                    "FileSystemId": {"Value": {"Fn::GetAtt": ["Fs", "FileSystemId"]}},
                    "FileSystemArn": {"Value": {"Fn::GetAtt": ["Fs", "Arn"]}},
                    "AccessPointRef": {"Value": {"Ref": "Ap"}},
                    "AccessPointId": {"Value": {"Fn::GetAtt": ["Ap", "AccessPointId"]}},
                    "AccessPointArn": {"Value": {"Fn::GetAtt": ["Ap", "Arn"]}},
                    "MountTargetRef": {"Value": {"Ref": "Mt"}},
                    "MountTargetId": {"Value": {"Fn::GetAtt": ["Mt", "Id"]}},
                    "MountTargetIp": {"Value": {"Fn::GetAtt": ["Mt", "IpAddress"]}},
                    "VolumeRef": {"Value": {"Ref": "Vol"}},
                    "VolumeId": {"Value": {"Fn::GetAtt": ["Vol", "VolumeId"]}}
                  }
                }
                """.formatted(clusterName, roleName);
    }
}
