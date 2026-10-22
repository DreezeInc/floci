package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * DescribeNetworkAcls narrows by tag, and CreateNetworkAcl applies its TagSpecification. The tag
 * filters used to fall through to a match-everything arm, and create-time tags were dropped.
 */
@QuarkusTest
class Ec2NetworkAclTagFilterIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";
    private static final String ACLS = "DescribeNetworkAclsResponse.networkAclSet.item";

    @Test
    void tagFiltersMatchOnlyTheTaggedAcl() {
        String vpcId = createVpc("10.81.0.0/16");
        String tagged = createNetworkAcl(vpcId);
        createNetworkAcl(vpcId);
        String key = "tier-" + UUID.randomUUID();
        given()
            .formParam("Action", "CreateTags")
            .formParam("ResourceId.1", tagged)
            .formParam("Tag.1.Key", key)
            .formParam("Tag.1.Value", "private")
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200);

        describeNetworkAcls("tag:" + key, "private")
            .statusCode(200)
            .body(ACLS + ".size()", equalTo(1))
            .body(ACLS + ".networkAclId", equalTo(tagged));
        describeNetworkAcls("tag-key", key)
            .statusCode(200)
            .body(ACLS + ".size()", equalTo(1))
            .body(ACLS + ".networkAclId", equalTo(tagged));
        describeNetworkAcls("tag:" + key, "public")
            .statusCode(200)
            .body(ACLS + ".size()", equalTo(0));
    }

    @Test
    void tagSpecificationOnCreateTagsTheAcl() {
        String vpcId = createVpc("10.83.0.0/16");
        String key = "tier-" + UUID.randomUUID();

        String aclId = given()
            .formParam("Action", "CreateNetworkAcl")
            .formParam("VpcId", vpcId)
            .formParam("TagSpecification.1.ResourceType", "network-acl")
            .formParam("TagSpecification.1.Tag.1.Key", key)
            .formParam("TagSpecification.1.Tag.1.Value", "private")
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .extract().path("CreateNetworkAclResponse.networkAcl.networkAclId");

        describeNetworkAcls("tag:" + key, "private")
            .statusCode(200)
            .body(ACLS + ".size()", equalTo(1))
            .body(ACLS + ".networkAclId", equalTo(aclId))
            .body(ACLS + ".tagSet.item.key", equalTo(key));
        given()
            .formParam("Action", "DescribeTags")
            .formParam("Filter.1.Name", "resource-id")
            .formParam("Filter.1.Value.1", aclId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then()
            .statusCode(200)
            .body("DescribeTagsResponse.tagSet.item.resourceType", equalTo("network-acl"))
            .body("DescribeTagsResponse.tagSet.item.key", equalTo(key));
    }

    private static String createVpc(String cidr) {
        return given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", cidr)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");
    }

    private static String createNetworkAcl(String vpcId) {
        return given()
            .formParam("Action", "CreateNetworkAcl")
            .formParam("VpcId", vpcId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .extract().path("CreateNetworkAclResponse.networkAcl.networkAclId");
    }

    private static ValidatableResponse describeNetworkAcls(String filterName, String value) {
        return given()
            .formParam("Action", "DescribeNetworkAcls")
            .formParam("Filter.1.Name", filterName)
            .formParam("Filter.1.Value.1", value)
            .header("Authorization", AUTH_HEADER)
        .when().post("/").then();
    }
}
