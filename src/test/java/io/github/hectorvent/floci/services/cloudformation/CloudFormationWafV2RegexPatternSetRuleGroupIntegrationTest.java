package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.CfnRequests;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static io.github.hectorvent.floci.testing.WafV2Requests.waf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AWS::WAFv2::RegexPatternSet and AWS::WAFv2::RuleGroup through a stack: Ref is
 * {@code name|id|scope}, {@code Fn::GetAtt} resolves the real attributes, a content change updates
 * in place keeping the id, a rename replaces the entity, deleting the stack deletes it, and a Web ACL
 * in the same stack can reference both. Every assertion reads WAFv2 back, not only the stack.
 */
@QuarkusTest
class CloudFormationWafV2RegexPatternSetRuleGroupIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void regexPatternSetFollowsTheTemplateAcrossCreateUpdateAndStackDelete() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-wafv2-regex-" + suffix;
        String nameA = "cfn-regex-a-" + suffix;
        String nameB = "cfn-regex-b-" + suffix;

        cfn("CreateStack", stackName, regexTemplate(nameA, "^/admin"));
        String created = completedStack(stackName, "CREATE_COMPLETE");
        String id = output(created, "RegexId");
        String arn = output(created, "RegexArn");

        assertFalse(id.contains("RegexSet."), "Fn::GetAtt Id resolved to a literal placeholder");
        assertFalse(arn.contains("RegexSet."), "Fn::GetAtt Arn resolved to a literal placeholder");
        assertEquals(nameA + "|" + id + "|REGIONAL", output(created, "RegexRef"));
        regexSet(nameA, id)
            .body("RegexPatternSet.ARN", equalTo(arn))
            .body("RegexPatternSet.RegularExpressionList.RegexString", contains("^/admin"));

        cfn("UpdateStack", stackName, regexTemplate(nameA, "^/internal"));
        String updated = completedStack(stackName, "UPDATE_COMPLETE");

        assertEquals(id, output(updated, "RegexId"));
        regexSet(nameA, id).body("RegexPatternSet.RegularExpressionList.RegexString", contains("^/internal"));

        cfn("UpdateStack", stackName, regexTemplate(nameB, "^/internal"));
        String replaced = completedStack(stackName, "UPDATE_COMPLETE");

        String replacementId = output(replaced, "RegexId");
        String replacementArn = output(replaced, "RegexArn");
        assertNotEquals(id, replacementId, "a renamed regex pattern set must be replaced, not updated in place");
        listRegexSets()
            .body("RegexPatternSets.ARN", not(hasItem(arn)))
            .body("RegexPatternSets.ARN", hasItem(replacementArn));

        cfn("DeleteStack", stackName, null);

        listRegexSets().body("RegexPatternSets.ARN", not(hasItem(replacementArn)));
    }

    @Test
    void ruleGroupFollowsTheTemplateAcrossCreateUpdateAndStackDelete() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-wafv2-rulegroup-" + suffix;
        String nameA = "cfn-rulegroup-a-" + suffix;
        String nameB = "cfn-rulegroup-b-" + suffix;

        cfn("CreateStack", stackName, ruleGroupTemplate(nameA, "first"));
        String created = completedStack(stackName, "CREATE_COMPLETE");
        String id = output(created, "GroupId");
        String arn = output(created, "GroupArn");
        String labelNamespace = output(created, "GroupLabelNamespace");

        assertFalse(id.contains("Group."), "Fn::GetAtt Id resolved to a literal placeholder");
        assertFalse(arn.contains("Group."), "Fn::GetAtt Arn resolved to a literal placeholder");
        assertTrue(labelNamespace.startsWith("awswaf:") && labelNamespace.endsWith(":rulegroup:" + nameA + ":"),
                "unexpected LabelNamespace " + labelNamespace);
        assertEquals(nameA + "|" + id + "|REGIONAL", output(created, "GroupRef"));
        ruleGroup(nameA, id)
            .body("RuleGroup.ARN", equalTo(arn))
            .body("RuleGroup.Capacity", equalTo(10))
            .body("RuleGroup.LabelNamespace", equalTo(labelNamespace))
            .body("RuleGroup.Rules[0].Statement.ByteMatchStatement.SearchString", equalTo(base64("first")));

        cfn("UpdateStack", stackName, ruleGroupTemplate(nameA, "second"));
        String updated = completedStack(stackName, "UPDATE_COMPLETE");

        assertEquals(id, output(updated, "GroupId"));
        ruleGroup(nameA, id)
            .body("RuleGroup.Capacity", equalTo(10))
            .body("RuleGroup.Rules[0].Statement.ByteMatchStatement.SearchString", equalTo(base64("second")));

        cfn("UpdateStack", stackName, ruleGroupTemplate(nameB, "second"));
        String replaced = completedStack(stackName, "UPDATE_COMPLETE");

        String replacementId = output(replaced, "GroupId");
        String replacementArn = output(replaced, "GroupArn");
        assertNotEquals(id, replacementId, "a renamed rule group must be replaced, not updated in place");
        listRuleGroups()
            .body("RuleGroups.ARN", not(hasItem(arn)))
            .body("RuleGroups.ARN", hasItem(replacementArn));

        cfn("DeleteStack", stackName, null);

        listRuleGroups().body("RuleGroups.ARN", not(hasItem(replacementArn)));
    }

    @Test
    void webAclInTheSameStackReferencesTheRegexSetAndRuleGroup() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-wafv2-refs-" + suffix;
        String template = """
                {
                  "Resources": {
                    "RegexSet": {
                      "Type": "AWS::WAFv2::RegexPatternSet",
                      "Properties": {"Scope": "REGIONAL", "RegularExpressionList": ["^/blocked"]}
                    },
                    "Group": {
                      "Type": "AWS::WAFv2::RuleGroup",
                      "Properties": %1$s
                    },
                    "Acl": {
                      "Type": "AWS::WAFv2::WebACL",
                      "Properties": {
                        "Scope": "REGIONAL",
                        "DefaultAction": {"Allow": {}},
                        "VisibilityConfig": %2$s,
                        "Rules": [
                          {
                            "Name": "regex", "Priority": 0, "Action": {"Block": {}},
                            "Statement": {"RegexPatternSetReferenceStatement": {
                              "Arn": {"Fn::GetAtt": ["RegexSet", "Arn"]},
                              "FieldToMatch": {"UriPath": {}},
                              "TextTransformations": [{"Priority": 0, "Type": "NONE"}]}},
                            "VisibilityConfig": %2$s
                          },
                          {
                            "Name": "group", "Priority": 1, "OverrideAction": {"None": {}},
                            "Statement": {"RuleGroupReferenceStatement": {
                              "Arn": {"Fn::GetAtt": ["Group", "Arn"]}}},
                            "VisibilityConfig": %2$s
                          },
                          {
                            "Name": "label", "Priority": 2, "Action": {"Block": {}},
                            "Statement": {"LabelMatchStatement": {"Scope": "LABEL",
                              "Key": {"Fn::Sub": "${Group.LabelNamespace}matched"}}},
                            "VisibilityConfig": %2$s
                          }
                        ]
                      }
                    }
                  },
                  "Outputs": {
                    "RegexArn": {"Value": {"Fn::GetAtt": ["RegexSet", "Arn"]}},
                    "GroupArn": {"Value": {"Fn::GetAtt": ["Group", "Arn"]}},
                    "GroupLabelNamespace": {"Value": {"Fn::GetAtt": ["Group", "LabelNamespace"]}},
                    "AclRef": {"Value": {"Ref": "Acl"}}
                  }
                }
                """.formatted(ruleGroupProperties(null, "matched"), visibility("acl"));

        cfn("CreateStack", stackName, template);
        String created = completedStack(stackName, "CREATE_COMPLETE");
        String[] acl = output(created, "AclRef").split("\\|");

        waf("GetWebACL", "{\"Name\":\"" + acl[0] + "\",\"Scope\":\"REGIONAL\",\"Id\":\"" + acl[1] + "\"}")
            .statusCode(200)
            .body("WebACL.Rules[0].Statement.RegexPatternSetReferenceStatement.ARN",
                    equalTo(output(created, "RegexArn")))
            .body("WebACL.Rules[1].Statement.RuleGroupReferenceStatement.ARN",
                    equalTo(output(created, "GroupArn")))
            .body("WebACL.Rules[2].Statement.LabelMatchStatement.Key",
                    equalTo(output(created, "GroupLabelNamespace") + "matched"));

        cfn("DeleteStack", stackName, null);

        listRegexSets().body("RegexPatternSets.ARN", not(hasItem(output(created, "RegexArn"))));
        listRuleGroups().body("RuleGroups.ARN", not(hasItem(output(created, "GroupArn"))));
    }

    @Test
    void webAclByteMatchSearchStringsReachTheApiBase64Encoded() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-wafv2-bytes-" + suffix;
        String template = """
                {
                  "Resources": {
                    "Acl": {
                      "Type": "AWS::WAFv2::WebACL",
                      "Properties": {
                        "Scope": "REGIONAL",
                        "DefaultAction": {"Allow": {}},
                        "VisibilityConfig": %1$s,
                        "Rules": [
                          {
                            "Name": "both", "Priority": 0, "Action": {"Block": {}},
                            "Statement": {"AndStatement": {"Statements": [
                              {"ByteMatchStatement": {
                                "SearchString": "block",
                                "FieldToMatch": {"SingleHeader": {"Name": "x-probe"}},
                                "TextTransformations": [{"Priority": 0, "Type": "NONE"}],
                                "PositionalConstraint": "EXACTLY"}},
                              {"ByteMatchStatement": {
                                "SearchStringBase64": "%2$s",
                                "FieldToMatch": {"UriPath": {}},
                                "TextTransformations": [{"Priority": 0, "Type": "NONE"}],
                                "PositionalConstraint": "STARTS_WITH"}}
                            ]}},
                            "VisibilityConfig": %1$s
                          }
                        ]
                      }
                    }
                  },
                  "Outputs": {"AclRef": {"Value": {"Ref": "Acl"}}}
                }
                """.formatted(visibility("acl"), base64("/admin"));

        cfn("CreateStack", stackName, template);
        String[] acl = output(completedStack(stackName, "CREATE_COMPLETE"), "AclRef").split("\\|");

        waf("GetWebACL", "{\"Name\":\"" + acl[0] + "\",\"Scope\":\"REGIONAL\",\"Id\":\"" + acl[1] + "\"}")
            .statusCode(200)
            .body("WebACL.Rules[0].Statement.AndStatement.Statements[0].ByteMatchStatement.SearchString",
                    equalTo(base64("block")))
            .body("WebACL.Rules[0].Statement.AndStatement.Statements[1].ByteMatchStatement.SearchString",
                    equalTo(base64("/admin")))
            .body(not(containsString("SearchStringBase64")));

        cfn("DeleteStack", stackName, null);
    }

    @Test
    void createStackWithoutRuleGroupCapacityFailsWithRequiredPropertyReason() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-wafv2-rulegroup-required-" + suffix;
        String template = """
                {
                  "Resources": {
                    "Group": {
                      "Type": "AWS::WAFv2::RuleGroup",
                      "Properties": {"Scope": "REGIONAL", "VisibilityConfig": %s}
                    }
                  }
                }
                """.formatted(visibility("group"));

        cfn("CreateStack", stackName, template);

        describeStackEvents(stackName)
            .body(containsString("<ResourceStatus>CREATE_FAILED</ResourceStatus>"))
            .body(containsString(
                    "<ResourceStatusReason>AWS::WAFv2::RuleGroup requires Capacity</ResourceStatusReason>"));
    }

    private static String regexTemplate(String name, String expression) {
        return """
                {
                  "Resources": {
                    "RegexSet": {
                      "Type": "AWS::WAFv2::RegexPatternSet",
                      "Properties": {
                        "Name": "%1$s",
                        "Scope": "REGIONAL",
                        "RegularExpressionList": ["%2$s"],
                        "Description": "cfn regex set"
                      }
                    }
                  },
                  "Outputs": {
                    "RegexRef": {"Value": {"Ref": "RegexSet"}},
                    "RegexArn": {"Value": {"Fn::GetAtt": ["RegexSet", "Arn"]}},
                    "RegexId": {"Value": {"Fn::GetAtt": ["RegexSet", "Id"]}}
                  }
                }
                """.formatted(name, expression);
    }

    private static String ruleGroupTemplate(String name, String searchString) {
        return """
                {
                  "Resources": {
                    "Group": {
                      "Type": "AWS::WAFv2::RuleGroup",
                      "Properties": %s
                    }
                  },
                  "Outputs": {
                    "GroupRef": {"Value": {"Ref": "Group"}},
                    "GroupArn": {"Value": {"Fn::GetAtt": ["Group", "Arn"]}},
                    "GroupId": {"Value": {"Fn::GetAtt": ["Group", "Id"]}},
                    "GroupLabelNamespace": {"Value": {"Fn::GetAtt": ["Group", "LabelNamespace"]}}
                  }
                }
                """.formatted(ruleGroupProperties(name, searchString));
    }

    private static String ruleGroupProperties(String name, String searchString) {
        String nameProperty = name == null ? "" : "\"Name\": \"" + name + "\",";
        return """
                {
                  %1$s
                  "Scope": "REGIONAL",
                  "Capacity": 10,
                  "VisibilityConfig": %3$s,
                  "Rules": [
                    {
                      "Name": "match-header", "Priority": 0, "Action": {"Count": {}},
                      "RuleLabels": [{"Name": "matched"}],
                      "Statement": {"ByteMatchStatement": {
                        "SearchString": "%2$s",
                        "FieldToMatch": {"SingleHeader": {"Name": "x-probe"}},
                        "TextTransformations": [{"Priority": 0, "Type": "NONE"}],
                        "PositionalConstraint": "EXACTLY"}},
                      "VisibilityConfig": %3$s
                    }
                  ]
                }
                """.formatted(nameProperty, searchString, visibility("rule"));
    }

    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String visibility(String metricName) {
        return "{\"SampledRequestsEnabled\": true, \"CloudWatchMetricsEnabled\": true, \"MetricName\": \""
                + metricName + "\"}";
    }

    private static String output(String describeXml, String key) {
        return XmlPath.from(describeXml).getString("DescribeStacksResponse.DescribeStacksResult.Stacks.member"
                + ".Outputs.member.find { it.OutputKey == '" + key + "' }.OutputValue");
    }

    private String completedStack(String stackName, String status) {
        return describeStack(stackName)
            .body(containsString("<StackStatus>" + status + "</StackStatus>"))
            .body(not(containsString("ROLLBACK")))
            .extract().asString();
    }

    private void cfn(String action, String stackName, String template) {
        RequestSpecification request = CfnRequests.cfn(action, stackName).header("Authorization", CFN_AUTH);
        if (template != null) {
            request.formParam("TemplateBody", template);
        }
        request.when().post("/").then().statusCode(200);
    }

    private ValidatableResponse describeStack(String stackName) {
        return CfnRequests.cfn("DescribeStacks", stackName)
            .header("Authorization", CFN_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private ValidatableResponse describeStackEvents(String stackName) {
        return CfnRequests.cfn("DescribeStackEvents", stackName)
            .header("Authorization", CFN_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private ValidatableResponse regexSet(String name, String id) {
        return waf("GetRegexPatternSet", "{\"Name\":\"" + name + "\",\"Scope\":\"REGIONAL\",\"Id\":\"" + id + "\"}")
            .statusCode(200);
    }

    private ValidatableResponse ruleGroup(String name, String id) {
        return waf("GetRuleGroup", "{\"Name\":\"" + name + "\",\"Scope\":\"REGIONAL\",\"Id\":\"" + id + "\"}")
            .statusCode(200);
    }

    private ValidatableResponse listRegexSets() {
        return waf("ListRegexPatternSets", "{\"Scope\":\"REGIONAL\"}").statusCode(200);
    }

    private ValidatableResponse listRuleGroups() {
        return waf("ListRuleGroups", "{\"Scope\":\"REGIONAL\"}").statusCode(200);
    }
}
