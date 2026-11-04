package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.testing.RealElbV2DataPlaneProfile;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(RealElbV2DataPlaneProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ElbV2WafV2EnforcementIntegrationTest {

    private static final String ELB_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260629/us-east-1/elasticloadbalancing/aws4_request";
    private static final String WAF_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260629/us-east-1/wafv2/aws4_request";
    private static final int LISTENER_PORT = 7797;
    private static final String HOST = "waf-front.example.test";

    private static String lbArn;
    private static String listenerArn;
    private static String webAclArn;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void associateWebAclWithLoadBalancer() {
        lbArn = given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", "waf-enforcement")
                .formParam("Type", "application")
                .formParam("Subnets.member.1", Ec2Service.defaultSubnetId("us-east-1", "a"))
                .formParam("Subnets.member.2", Ec2Service.defaultSubnetId("us-east-1", "b"))
                .formParam("Scheme", "internet-facing")
                .header("Authorization", ELB_AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.LoadBalancerArn");
        listenerArn = given()
                .formParam("Action", "CreateListener")
                .formParam("LoadBalancerArn", lbArn)
                .formParam("Protocol", "HTTP")
                .formParam("Port", String.valueOf(LISTENER_PORT))
                .formParam("DefaultActions.member.1.Type", "fixed-response")
                .formParam("DefaultActions.member.1.FixedResponseConfig.StatusCode", "200")
                .formParam("DefaultActions.member.1.FixedResponseConfig.ContentType", "text/plain")
                .formParam("DefaultActions.member.1.FixedResponseConfig.MessageBody", "target")
                .header("Authorization", ELB_AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateListenerResponse.CreateListenerResult.Listeners.member.ListenerArn");

        String ipSetArn = waf("CreateIPSet", """
                {"Name": "waf-enforcement-ips", "Scope": "REGIONAL", "IPAddressVersion": "IPV4",
                 "Addresses": ["192.0.2.0/24"]}
                """).jsonPath().getString("Summary.ARN");
        String regexSetArn = waf("CreateRegexPatternSet", """
                {"Name": "waf-enforcement-paths", "Scope": "REGIONAL",
                 "RegularExpressionList": [{"RegexString": "^/blocked"}]}
                """).jsonPath().getString("Summary.ARN");
        Response group = waf("CreateRuleGroup", """
                {"Name": "waf-enforcement-group", "Scope": "REGIONAL", "Capacity": 10,
                 "VisibilityConfig": %1$s,
                 "Rules": [
                   {"Name": "block-group", "Priority": 0, "Action": {"Block": {}},
                    "Statement": %2$s, "VisibilityConfig": %1$s},
                   {"Name": "label-group", "Priority": 1, "Action": {"Count": {}},
                    "RuleLabels": [{"Name": "flagged"}],
                    "Statement": %3$s, "VisibilityConfig": %1$s}
                 ]}
                """.formatted(visibility(), probeHeader("rule-group"), probeHeader("rule-group-label")));
        String groupArn = group.jsonPath().getString("Summary.ARN");
        String groupNamespace = waf("GetRuleGroup", "{\"Name\": \"waf-enforcement-group\", \"Scope\": \"REGIONAL\", "
                + "\"Id\": \"" + group.jsonPath().getString("Summary.Id") + "\"}")
                .jsonPath().getString("RuleGroup.LabelNamespace");

        webAclArn = waf("CreateWebACL", """
                {"Name": "waf-enforcement-acl", "Scope": "REGIONAL", "DefaultAction": {"Allow": {}},
                 "VisibilityConfig": %1$s,
                 "CustomResponseBodies": {"slow-down": {"ContentType": "TEXT_PLAIN", "Content": "slow down"}},
                 "Rules": [
                   {"Name": "header", "Priority": 0, "Action": {"Block": {}},
                    "Statement": %2$s, "VisibilityConfig": %1$s},
                   {"Name": "forwarded-ip", "Priority": 1, "Action": {"Block": {}},
                    "Statement": {"IPSetReferenceStatement": {"ARN": "%3$s",
                      "IPSetForwardedIPConfig": {"HeaderName": "X-Forwarded-For", "FallbackBehavior": "NO_MATCH",
                                                 "Position": "FIRST"}}},
                    "VisibilityConfig": %1$s},
                   {"Name": "path", "Priority": 2, "Action": {"Block": {}},
                    "Statement": {"RegexPatternSetReferenceStatement": {"ARN": "%4$s",
                      "FieldToMatch": {"UriPath": {}},
                      "TextTransformations": [{"Priority": 0, "Type": "NONE"}]}},
                    "VisibilityConfig": %1$s},
                   {"Name": "rate", "Priority": 3, "Action": {"Count": {}},
                    "Statement": {"RateBasedStatement": {"Limit": 100, "AggregateKeyType": "IP"}},
                    "VisibilityConfig": %1$s},
                   {"Name": "common", "Priority": 4, "OverrideAction": {"None": {}},
                    "Statement": {"ManagedRuleGroupStatement": {"VendorName": "AWS",
                      "Name": "AWSManagedRulesCommonRuleSet",
                      "RuleActionOverrides": [{"Name": "SizeRestrictions_BODY", "ActionToUse": {"Count": {}}}]}},
                    "VisibilityConfig": %1$s},
                   {"Name": "group", "Priority": 5, "OverrideAction": {"None": {}},
                    "Statement": {"RuleGroupReferenceStatement": {"ARN": "%5$s"}},
                    "VisibilityConfig": %1$s},
                   {"Name": "group-label", "Priority": 6, "Action": {"Block": {}},
                    "Statement": {"LabelMatchStatement": {"Scope": "LABEL", "Key": "%6$sflagged"}},
                    "VisibilityConfig": %1$s},
                   {"Name": "custom", "Priority": 7,
                    "Action": {"Block": {"CustomResponse": {"ResponseCode": 429, "CustomResponseBodyKey": "slow-down",
                      "ResponseHeaders": [{"Name": "retry-after", "Value": "5"}]}}},
                    "Statement": %7$s, "VisibilityConfig": %1$s}
                 ]}
                """.formatted(visibility(), probeHeader("block"), ipSetArn, regexSetArn, groupArn, groupNamespace,
                        probeHeader("throttle"))).jsonPath().getString("Summary.ARN");

        waf("AssociateWebACL", "{\"WebACLArn\": \"" + webAclArn + "\", \"ResourceArn\": \"" + lbArn + "\"}");
    }

    @Test
    @Order(2)
    void requestNoRuleMatchesReachesTheTarget() {
        probe().get("/").then().statusCode(200).body(equalTo("target"));
    }

    @Test
    @Order(3)
    void byteMatchOnAHeaderBlocks() {
        probe().header("x-probe", "block").get("/")
            .then().statusCode(403).body(containsString("403 Forbidden"));
        probe().header("x-probe", "blocked").get("/").then().statusCode(200);
    }

    @Test
    @Order(4)
    void forwardedClientIpInTheIpSetBlocks() {
        probe().header("X-Forwarded-For", "192.0.2.7, 10.0.0.1").get("/").then().statusCode(403);
        probe().header("X-Forwarded-For", "10.0.0.1, 192.0.2.7").get("/").then().statusCode(200);
    }

    @Test
    @Order(5)
    void regexPatternSetOnTheUriPathBlocks() {
        probe().get("/blocked/page").then().statusCode(403);
        probe().get("/open/blocked").then().statusCode(200);
    }

    @Test
    @Order(6)
    void commonRuleSetBlocksCrossSiteScriptingInAQueryArgument() {
        probe().queryParam("q", "<script>alert(1)</script>").get("/").then().statusCode(403);
        probe().queryParam("q", "plain words").get("/").then().statusCode(200);
    }

    @Test
    @Order(7)
    void ruleGroupBlockingRuleBlocks() {
        probe().header("x-probe", "rule-group").get("/").then().statusCode(403);
    }

    @Test
    @Order(8)
    void labelACountingRuleGroupRuleAddsMatchesALaterWebAclRule() {
        probe().header("x-probe", "rule-group-label").get("/").then().statusCode(403);
    }

    @Test
    @Order(9)
    void customResponseCarriesItsCodeBodyAndHeaders() {
        probe().header("x-probe", "throttle").get("/")
            .then()
                .statusCode(429)
                .header("retry-after", "5")
                .body(equalTo("slow down"));
    }

    @Test
    @Order(10)
    void disassociatedWebAclStopsBlocking() {
        waf("DisassociateWebACL", "{\"ResourceArn\": \"" + lbArn + "\"}");

        probe().header("x-probe", "block").get("/").then().statusCode(200).body(equalTo("target"));
    }

    @Test
    @Order(11)
    void deleteLoadBalancer() {
        given()
                .formParam("Action", "DeleteListener")
                .formParam("ListenerArn", listenerArn)
                .header("Authorization", ELB_AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);
        given()
                .formParam("Action", "DeleteLoadBalancer")
                .formParam("LoadBalancerArn", lbArn)
                .header("Authorization", ELB_AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);
    }

    private static RequestSpecification probe() {
        return given()
                .baseUri("http://127.0.0.1")
                .port(LISTENER_PORT)
                .header("Host", HOST)
                .urlEncodingEnabled(true);
    }

    private static Response waf(String action, String body) {
        Response response = given()
                .contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AWSWAF_20190729." + action)
                .header("Authorization", WAF_AUTH)
                .body(body)
            .when()
                .post("/");
        response.then().statusCode(200);
        return response;
    }

    private static String probeHeader(String value) {
        return """
                {"ByteMatchStatement": {"SearchString": "%s", "FieldToMatch": {"SingleHeader": {"Name": "x-probe"}},
                  "TextTransformations": [{"Priority": 0, "Type": "NONE"}], "PositionalConstraint": "EXACTLY"}}
                """.formatted(Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String visibility() {
        return "{\"SampledRequestsEnabled\": true, \"CloudWatchMetricsEnabled\": true, \"MetricName\": \"waf\"}";
    }
}
