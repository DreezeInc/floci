package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@code AWS::IAM::Group} and {@code AWS::IAM::UserToGroupAddition} through the stack lifecycle:
 * the group gets its managed and inline policies and the addition's users, Ref and Fn::GetAtt
 * resolve to the real name and arn (not the literal {@code "Group.Arn"}), an update drops a user
 * the addition no longer declares, and deleting the stack removes the memberships, the policies and
 * the group so the same names can be created again.
 */
@QuarkusTest
class CloudFormationIamGroupIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=111122223333/20260205/us-east-1/cloudformation/aws4_request";
    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=111122223333/20260205/us-east-1/iam/aws4_request";

    @Test
    void groupWithPoliciesAndMembersIsProvisionedUpdatedAndDeleted() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String groupName = "probe-group-" + suffix;
        String stackName = "cfn-group-stack-" + suffix;
        String template = template(groupName, suffix, "[{\"Ref\": \"Alice\"}, {\"Ref\": \"Bob\"}]");

        String stackId = createStack(stackName, template);
        awaitStackStatus(stackId, "CREATE_COMPLETE");

        String stackXml = describeStacks(stackId);
        assertEquals(groupName, outputValue(stackXml, "GroupRef"));
        String expectedArn = "arn:aws:iam::111122223333:group/probe/" + groupName;
        assertEquals(expectedArn, outputValue(stackXml, "GroupArn"));
        assertTrue(outputValue(stackXml, "AdditionRef").contains("Membership"),
                "Ref of the addition is its generated resource name: " + outputValue(stackXml, "AdditionRef"));

        String group = iam("GetGroup", "GroupName", groupName);
        assertThat(group, containsString("<GroupName>" + groupName + "</GroupName>"));
        assertThat(group, containsString("<Arn>" + expectedArn + "</Arn>"));
        assertThat(group, containsString("<UserName>alice-" + suffix + "</UserName>"));
        assertThat(group, containsString("<UserName>bob-" + suffix + "</UserName>"));
        assertThat(iam("ListAttachedGroupPolicies", "GroupName", groupName),
                containsString("group-policy-" + suffix));
        assertThat(iam("ListGroupPolicies", "GroupName", groupName),
                containsString("<member>group-inline</member>"));
        assertThat(iam("ListGroupsForUser", "UserName", "alice-" + suffix), containsString(groupName));

        updateStack(stackName, template(groupName, suffix, "[{\"Ref\": \"Alice\"}]"));
        awaitStackStatus(stackId, "UPDATE_COMPLETE");

        String updated = iam("GetGroup", "GroupName", groupName);
        assertThat(updated, containsString("<UserName>alice-" + suffix + "</UserName>"));
        assertThat(updated, not(containsString("<UserName>bob-" + suffix + "</UserName>")));

        deleteStack(stackName);
        awaitStackStatus(stackId, "DELETE_COMPLETE");

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "GetGroup")
            .formParam("GroupName", groupName)
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchEntity"));

        String recreated = createStack(stackName + "-again", template);
        awaitStackStatus(recreated, "CREATE_COMPLETE");
        deleteStack(stackName + "-again");
        awaitStackStatus(recreated, "DELETE_COMPLETE");
    }

    private static String template(String groupName, String suffix, String users) {
        return """
                {
                  "Resources": {
                    "GroupPolicy": {
                      "Type": "AWS::IAM::ManagedPolicy",
                      "Properties": {
                        "ManagedPolicyName": "group-policy-%s",
                        "PolicyDocument": {"Version": "2012-10-17", "Statement": [
                          {"Effect": "Allow", "Action": "s3:ListBucket", "Resource": "*"}]}
                      }
                    },
                    "Group": {
                      "Type": "AWS::IAM::Group",
                      "Properties": {
                        "GroupName": "%s",
                        "Path": "/probe/",
                        "ManagedPolicyArns": [{"Ref": "GroupPolicy"}],
                        "Policies": [
                          {
                            "PolicyName": "group-inline",
                            "PolicyDocument": {"Version": "2012-10-17", "Statement": [
                              {"Effect": "Allow", "Action": "ec2:DescribeInstances", "Resource": "*"}]}
                          }
                        ]
                      }
                    },
                    "Alice": {"Type": "AWS::IAM::User", "Properties": {"UserName": "alice-%s"}},
                    "Bob": {"Type": "AWS::IAM::User", "Properties": {"UserName": "bob-%s"}},
                    "Membership": {
                      "Type": "AWS::IAM::UserToGroupAddition",
                      "Properties": {"GroupName": {"Ref": "Group"}, "Users": %s}
                    }
                  },
                  "Outputs": {
                    "GroupRef": {"Value": {"Ref": "Group"}},
                    "GroupArn": {"Value": {"Fn::GetAtt": ["Group", "Arn"]}},
                    "AdditionRef": {"Value": {"Ref": "Membership"}}
                  }
                }
                """.formatted(suffix, groupName, suffix, suffix, users);
    }

    private static String iam(String action, String paramName, String paramValue) {
        return given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", IAM_AUTH)
                .formParam("Action", action)
                .formParam(paramName, paramValue)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .asString();
    }

    private static String createStack(String stackName, String template) {
        return cfnQuery("CreateStack", stackName, template)
                .then()
                .statusCode(200)
                .extract()
                .xmlPath()
                .getString("CreateStackResponse.CreateStackResult.StackId");
    }

    private static void updateStack(String stackName, String template) {
        cfnQuery("UpdateStack", stackName, template)
                .then()
                .statusCode(200);
    }

    private static void deleteStack(String stackName) {
        cfnQuery("DeleteStack", stackName)
                .then()
                .statusCode(200);
    }

    private static void awaitStackStatus(String stackId, String status) throws InterruptedException {
        String xml = "";
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            xml = describeStacks(stackId);
            if (xml.contains("<StackStatus>" + status + "</StackStatus>")) {
                return;
            }
            Thread.sleep(50);
        }
        fail("stack " + stackId + " never reached " + status + ": " + xml);
    }

    private static String describeStacks(String stackId) {
        return cfnQuery("DescribeStacks", stackId).then().statusCode(200).extract().asString();
    }

    private static Response cfnQuery(String action, String stackName) {
        return cfnQuery(action, stackName, null);
    }

    private static Response cfnQuery(String action, String stackName, String template) {
        RequestSpecification req = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", action)
                .formParam("StackName", stackName);
        if (template != null) {
            req.formParam("TemplateBody", template);
        }
        return req.when().post("/");
    }

    private static String outputValue(String xml, String key) {
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue").get(key);
    }
}
