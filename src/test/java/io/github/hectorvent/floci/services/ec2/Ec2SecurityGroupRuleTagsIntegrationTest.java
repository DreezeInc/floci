package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * Security group rules carry tags on the wire, and DescribeSecurityGroupRules narrows by them. A tag
 * filter that was ignored returned every rule in the region, so a lookup expecting one match failed.
 */
@QuarkusTest
class Ec2SecurityGroupRuleTagsIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";
    private static final String RULES = "DescribeSecurityGroupRulesResponse.securityGroupRuleSet.item";

    @Test
    void aRuleTaggedOnIngressIsTheOnlyMatchForItsTagFilter() {
        String groupId = createSecurityGroup();
        String name = "allow-https-" + UUID.randomUUID();
        String tagged = authorize("AuthorizeSecurityGroupIngress", groupId, "443", name);
        authorize("AuthorizeSecurityGroupIngress", groupId, "80", null);

        describeRules("tag:Name", name)
            .statusCode(200)
            .body(RULES + ".size()", equalTo(1))
            .body(RULES + ".securityGroupRuleId", equalTo(tagged))
            .body(RULES + ".tagSet.item.key", equalTo("Name"))
            .body(RULES + ".tagSet.item.value", equalTo(name));
    }

    @Test
    void aRuleTaggedOnEgressKeepsItsTags() {
        String groupId = createSecurityGroup();
        String name = "to-database-" + UUID.randomUUID();
        String tagged = authorize("AuthorizeSecurityGroupEgress", groupId, "5432", name);

        given()
            .formParam("Action", "DescribeSecurityGroupRules")
            .formParam("SecurityGroupRuleId.1", tagged)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(RULES + ".isEgress", equalTo("true"))
            .body(RULES + ".tagSet.item.key", equalTo("Name"))
            .body(RULES + ".tagSet.item.value", equalTo(name));

        describeRules("tag:Name", name)
            .statusCode(200)
            .body(RULES + ".size()", equalTo(1))
            .body(RULES + ".securityGroupRuleId", equalTo(tagged));
    }

    @Test
    void tagKeyFilterMatchesOnlyTheRuleCarryingTheKey() {
        String groupId = createSecurityGroup();
        String key = "owner-" + UUID.randomUUID();
        String tagged = authorize("AuthorizeSecurityGroupIngress", groupId, "443", null);
        authorize("AuthorizeSecurityGroupIngress", groupId, "80", null);
        createTags(tagged, key, "platform");

        describeRules("tag-key", key)
            .statusCode(200)
            .body(RULES + ".size()", equalTo(1))
            .body(RULES + ".securityGroupRuleId", equalTo(tagged));

        given()
            .formParam("Action", "DescribeSecurityGroupRules")
            .formParam("Filter.1.Name", "group-id")
            .formParam("Filter.1.Value.1", groupId)
            .formParam("Filter.2.Name", "tag-key")
            .formParam("Filter.2.Value.1", key)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(RULES + ".size()", equalTo(1))
            .body(RULES + ".securityGroupRuleId", equalTo(tagged));
    }

    @Test
    void createTagsAndDeleteTagsOnARuleIdRoundTrip() {
        String groupId = createSecurityGroup();
        String key = "owner-" + UUID.randomUUID();
        String ruleId = authorize("AuthorizeSecurityGroupIngress", groupId, "443", null);

        createTags(ruleId, key, "platform");

        given()
            .formParam("Action", "DescribeSecurityGroupRules")
            .formParam("SecurityGroupRuleId.1", ruleId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(RULES + ".tagSet.item.key", equalTo(key))
            .body(RULES + ".tagSet.item.value", equalTo("platform"));
        given()
            .formParam("Action", "DescribeTags")
            .formParam("Filter.1.Name", "resource-id")
            .formParam("Filter.1.Value.1", ruleId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then()
            .statusCode(200)
            .body("DescribeTagsResponse.tagSet.item.resourceType", equalTo("security-group-rule"))
            .body("DescribeTagsResponse.tagSet.item.key", equalTo(key));

        given()
            .formParam("Action", "DeleteTags")
            .formParam("ResourceId.1", ruleId)
            .formParam("Tag.1.Key", key)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then()
            .statusCode(200)
            .body("DeleteTagsResponse.return", equalTo("true"));

        describeRules("tag:" + key, "platform")
            .statusCode(200)
            .body(RULES + ".size()", equalTo(0));
        given()
            .formParam("Action", "DescribeTags")
            .formParam("Filter.1.Name", "resource-id")
            .formParam("Filter.1.Value.1", ruleId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then()
            .statusCode(200)
            .body("DescribeTagsResponse.tagSet.item.size()", equalTo(0));
    }

    @Test
    void anUnsupportedFilterIsRefused() {
        describeRules("ip-protocol", "tcp")
            .statusCode(400)
            .body(containsString("InvalidParameterValue"))
            .body(containsString("ip-protocol"));
    }

    private static ValidatableResponse describeRules(String filterName, String value) {
        return given()
            .formParam("Action", "DescribeSecurityGroupRules")
            .formParam("Filter.1.Name", filterName)
            .formParam("Filter.1.Value.1", value)
            .header("Authorization", AUTH_HEADER)
        .when().post("/").then();
    }

    private static String createSecurityGroup() {
        return given()
            .formParam("Action", "CreateSecurityGroup")
            .formParam("GroupName", "rule-tags-" + UUID.randomUUID())
            .formParam("GroupDescription", "rule tags")
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .extract().path("CreateSecurityGroupResponse.groupId");
    }

    private static String authorize(String action, String groupId, String port, String nameTag) {
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

    private static void createTags(String resourceId, String key, String value) {
        given()
            .formParam("Action", "CreateTags")
            .formParam("ResourceId.1", resourceId)
            .formParam("Tag.1.Key", key)
            .formParam("Tag.1.Value", value)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200);
    }
}
