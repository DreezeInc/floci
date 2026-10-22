package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * CreateTags and DeleteTags on a volume reach DescribeVolumes. They used to land only in the tag
 * store, so DescribeTags listed a tag that DescribeVolumes neither returned nor filtered on.
 */
@QuarkusTest
class Ec2VolumeTagsIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";
    private static final String VOLUMES = "DescribeVolumesResponse.volumeSet.item";

    @Test
    void createTagsIsReturnedAndFilteredByDescribeVolumes() {
        String tagged = createVolume();
        createVolume();
        String key = "env-" + UUID.randomUUID();

        given()
            .formParam("Action", "CreateTags")
            .formParam("ResourceId.1", tagged)
            .formParam("Tag.1.Key", key)
            .formParam("Tag.1.Value", "prod")
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200);

        given()
            .formParam("Action", "DescribeVolumes")
            .formParam("VolumeId.1", tagged)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(VOLUMES + ".tagSet.item.key", equalTo(key))
            .body(VOLUMES + ".tagSet.item.value", equalTo("prod"));
        describeVolumes("tag:" + key, "prod")
            .statusCode(200)
            .body(VOLUMES + ".size()", equalTo(1))
            .body(VOLUMES + ".volumeId", equalTo(tagged));
        describeVolumes("tag-key", key)
            .statusCode(200)
            .body(VOLUMES + ".size()", equalTo(1))
            .body(VOLUMES + ".volumeId", equalTo(tagged));
        describeVolumes("tag:" + key, "staging")
            .statusCode(200)
            .body(VOLUMES + ".size()", equalTo(0));
    }

    @Test
    void deleteTagsTakesTheVolumeOutOfItsTagFilter() {
        String volumeId = createVolume();
        String key = "env-" + UUID.randomUUID();
        given()
            .formParam("Action", "CreateTags")
            .formParam("ResourceId.1", volumeId)
            .formParam("Tag.1.Key", key)
            .formParam("Tag.1.Value", "prod")
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200);

        given()
            .formParam("Action", "DeleteTags")
            .formParam("ResourceId.1", volumeId)
            .formParam("Tag.1.Key", key)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200);

        describeVolumes("tag-key", key)
            .statusCode(200)
            .body(VOLUMES + ".size()", equalTo(0));
        given()
            .formParam("Action", "DescribeVolumes")
            .formParam("VolumeId.1", volumeId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then()
            .statusCode(200)
            .body(VOLUMES + ".tagSet.item.size()", equalTo(0));
    }

    @Test
    void deletingAVolumeDropsItsTags() {
        String volumeId = createVolume();
        given()
            .formParam("Action", "CreateTags")
            .formParam("ResourceId.1", volumeId)
            .formParam("Tag.1.Key", "env")
            .formParam("Tag.1.Value", "prod")
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200);

        given()
            .formParam("Action", "DeleteVolume")
            .formParam("VolumeId", volumeId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200);

        given()
            .formParam("Action", "DescribeTags")
            .formParam("Filter.1.Name", "resource-id")
            .formParam("Filter.1.Value.1", volumeId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then()
            .statusCode(200)
            .body("DescribeTagsResponse.tagSet.item.size()", equalTo(0));
    }

    private static String createVolume() {
        return given()
            .formParam("Action", "CreateVolume")
            .formParam("AvailabilityZone", "us-east-1a")
            .formParam("VolumeType", "gp3")
            .formParam("Size", "8")
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .extract().path("CreateVolumeResponse.volumeId");
    }

    private static ValidatableResponse describeVolumes(String filterName, String value) {
        return given()
            .formParam("Action", "DescribeVolumes")
            .formParam("Filter.1.Name", filterName)
            .formParam("Filter.1.Value.1", value)
            .header("Authorization", AUTH_HEADER)
        .when().post("/").then();
    }
}
