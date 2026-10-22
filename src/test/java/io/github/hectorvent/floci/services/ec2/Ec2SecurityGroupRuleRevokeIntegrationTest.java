package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * A revoke, by rule id or by permission, removes the rule record and its tags along with the
 * permission, so the same rule can be authorized again and a tag lookup finds only the live one.
 */
@QuarkusTest
class Ec2SecurityGroupRuleRevokeIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";
    private static final String RULES = "DescribeSecurityGroupRulesResponse.securityGroupRuleSet.item";
    private static final String INGRESS_REVOKED =
            "RevokeSecurityGroupIngressResponse.revokedSecurityGroupRuleSet.item";

    @Test
    void revokingByRuleIdReturnsTheRuleAndRemovesItEverywhere() {
        String groupId = createSecurityGroup();
        String name = "allow-https-" + UUID.randomUUID();
        String ruleId = authorize("AuthorizeSecurityGroupIngress", groupId, name);

        given()
            .formParam("Action", "RevokeSecurityGroupIngress")
            .formParam("GroupId", groupId)
            .formParam("SecurityGroupRuleId.1", ruleId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then()
            .statusCode(200)
            .body("RevokeSecurityGroupIngressResponse.return", equalTo("true"))
            .body(INGRESS_REVOKED + ".size()", equalTo(1))
            .body(INGRESS_REVOKED + ".securityGroupRuleId", equalTo(ruleId))
            .body(INGRESS_REVOKED + ".groupId", equalTo(groupId))
            .body(INGRESS_REVOKED + ".isEgress", equalTo("false"))
            .body(INGRESS_REVOKED + ".ipProtocol", equalTo("tcp"))
            .body(INGRESS_REVOKED + ".fromPort", equalTo("443"))
            .body(INGRESS_REVOKED + ".cidrIpv4", equalTo("10.0.0.0/8"))
            .body("RevokeSecurityGroupIngressResponse.unknownIpPermissionSet.item.size()", equalTo(0));

        assertGone(groupId, ruleId, name);
    }

    @Test
    void revokingByPermissionRemovesTheRuleAndReportsUnknownSources() {
        String groupId = createSecurityGroup();
        String name = "allow-https-" + UUID.randomUUID();
        String ruleId = authorize("AuthorizeSecurityGroupIngress", groupId, name);

        given()
            .formParam("Action", "RevokeSecurityGroupIngress")
            .formParam("GroupId", groupId)
            .formParam("IpPermissions.1.IpProtocol", "tcp")
            .formParam("IpPermissions.1.FromPort", "443")
            .formParam("IpPermissions.1.ToPort", "443")
            .formParam("IpPermissions.1.IpRanges.1.CidrIp", "10.0.0.0/8")
            .formParam("IpPermissions.1.IpRanges.2.CidrIp", "172.16.0.0/12")
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then()
            .statusCode(200)
            .body("RevokeSecurityGroupIngressResponse.return", equalTo("true"))
            .body(INGRESS_REVOKED + ".securityGroupRuleId", equalTo(ruleId))
            .body("RevokeSecurityGroupIngressResponse.unknownIpPermissionSet.item.fromPort", equalTo("443"))
            .body("RevokeSecurityGroupIngressResponse.unknownIpPermissionSet.item.ipRanges.item.cidrIp",
                    equalTo("172.16.0.0/12"));

        assertGone(groupId, ruleId, name);
    }

    @Test
    void anUnknownRuleIdFailsTheCallAndRevokesNothing() {
        String groupId = createSecurityGroup();
        String ruleId = authorize("AuthorizeSecurityGroupIngress", groupId, null);

        given()
            .formParam("Action", "RevokeSecurityGroupIngress")
            .formParam("GroupId", groupId)
            .formParam("SecurityGroupRuleId.1", ruleId)
            .formParam("SecurityGroupRuleId.2", "sgr-0123456789abcdef0")
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("InvalidSecurityGroupRuleId.NotFound"))
            .body(containsString("sgr-0123456789abcdef0"));

        describeRules("security-group-rule-id", ruleId)
            .statusCode(200)
            .body(RULES + ".size()", equalTo(1));
    }

    @Test
    void destroyThenRecreateLeavesExactlyOneRuleMatchingTheTag() {
        String groupId = createSecurityGroup();
        String name = "allow-https-" + UUID.randomUUID();
        String first = authorize("AuthorizeSecurityGroupIngress", groupId, name);

        revokeById("RevokeSecurityGroupIngress", groupId, first);
        String second = authorize("AuthorizeSecurityGroupIngress", groupId, name);

        describeRules("tag:Name", name)
            .statusCode(200)
            .body(RULES + ".size()", equalTo(1))
            .body(RULES + ".securityGroupRuleId", equalTo(second))
            .body(RULES + ".securityGroupRuleId", not(equalTo(first)));

        given()
            .formParam("Action", "RevokeSecurityGroupIngress")
            .formParam("GroupId", groupId)
            .formParam("IpPermissions.1.IpProtocol", "tcp")
            .formParam("IpPermissions.1.FromPort", "443")
            .formParam("IpPermissions.1.ToPort", "443")
            .formParam("IpPermissions.1.IpRanges.1.CidrIp", "10.0.0.0/8")
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200);
        String third = authorize("AuthorizeSecurityGroupIngress", groupId, name);

        describeRules("tag:Name", name)
            .statusCode(200)
            .body(RULES + ".size()", equalTo(1))
            .body(RULES + ".securityGroupRuleId", equalTo(third));
    }

    @Test
    void revokingAnEgressRuleByIdMarksItAsEgress() {
        String groupId = createSecurityGroup();
        String name = "to-database-" + UUID.randomUUID();
        String ruleId = authorize("AuthorizeSecurityGroupEgress", groupId, name);

        revokeById("RevokeSecurityGroupEgress", groupId, ruleId)
            .body("RevokeSecurityGroupEgressResponse.return", equalTo("true"))
            .body("RevokeSecurityGroupEgressResponse.revokedSecurityGroupRuleSet.item.securityGroupRuleId",
                    equalTo(ruleId))
            .body("RevokeSecurityGroupEgressResponse.revokedSecurityGroupRuleSet.item.isEgress", equalTo("true"));

        describeRules("tag:Name", name)
            .statusCode(200)
            .body(RULES + ".size()", equalTo(0));
    }

    @Test
    void deletingTheGroupDropsItsRulesAndTheirTags() {
        String groupId = createSecurityGroup();
        String name = "allow-https-" + UUID.randomUUID();
        String ruleId = authorize("AuthorizeSecurityGroupIngress", groupId, name);

        given()
            .formParam("Action", "DeleteSecurityGroup")
            .formParam("GroupId", groupId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200);

        describeRules("group-id", groupId)
            .statusCode(200)
            .body(RULES + ".size()", equalTo(0));
        describeRules("tag:Name", name)
            .statusCode(200)
            .body(RULES + ".size()", equalTo(0));
        describeTags(ruleId)
            .statusCode(200)
            .body("DescribeTagsResponse.tagSet.item.size()", equalTo(0));
    }

    private static void assertGone(String groupId, String ruleId, String name) {
        describeRules("tag:Name", name)
            .statusCode(200)
            .body(RULES + ".size()", equalTo(0));
        describeRules("security-group-rule-id", ruleId)
            .statusCode(200)
            .body(RULES + ".size()", equalTo(0));
        describeTags(ruleId)
            .statusCode(200)
            .body("DescribeTagsResponse.tagSet.item.size()", equalTo(0));
        given()
            .formParam("Action", "DescribeSecurityGroups")
            .formParam("GroupId.1", groupId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then()
            .statusCode(200)
            .body("DescribeSecurityGroupsResponse.securityGroupInfo.item.ipPermissions.item.size()", equalTo(0));
    }

    private static ValidatableResponse revokeById(String action, String groupId, String ruleId) {
        return given()
            .formParam("Action", action)
            .formParam("GroupId", groupId)
            .formParam("SecurityGroupRuleId.1", ruleId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200);
    }

    private static ValidatableResponse describeRules(String filterName, String value) {
        return given()
            .formParam("Action", "DescribeSecurityGroupRules")
            .formParam("Filter.1.Name", filterName)
            .formParam("Filter.1.Value.1", value)
            .header("Authorization", AUTH_HEADER)
        .when().post("/").then();
    }

    private static ValidatableResponse describeTags(String resourceId) {
        return given()
            .formParam("Action", "DescribeTags")
            .formParam("Filter.1.Name", "resource-id")
            .formParam("Filter.1.Value.1", resourceId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/").then();
    }

    private static String createSecurityGroup() {
        return given()
            .formParam("Action", "CreateSecurityGroup")
            .formParam("GroupName", "rule-revoke-" + UUID.randomUUID())
            .formParam("GroupDescription", "rule revoke")
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .extract().path("CreateSecurityGroupResponse.groupId");
    }

    private static String authorize(String action, String groupId, String nameTag) {
        String port = action.endsWith("Egress") ? "5432" : "443";
        RequestSpecification request = given()
            .formParam("Action", action)
            .formParam("GroupId", groupId)
            .formParam("IpPermissions.1.IpProtocol", "tcp")
            .formParam("IpPermissions.1.FromPort", port)
            .formParam("IpPermissions.1.ToPort", port)
            .formParam("IpPermissions.1.IpRanges.1.CidrIp", "10.0.0.0/8")
            .header("Authorization", AUTH_HEADER);
        if (nameTag != null) {
            request.formParam("TagSpecification.1.ResourceType", "security-group-rule")
                .formParam("TagSpecification.1.Tag.1.Key", "Name")
                .formParam("TagSpecification.1.Tag.1.Value", nameTag);
        }
        return request.when().post("/")
            .then().statusCode(200)
            .extract().path(action + "Response.securityGroupRuleSet.item.securityGroupRuleId");
    }
}
