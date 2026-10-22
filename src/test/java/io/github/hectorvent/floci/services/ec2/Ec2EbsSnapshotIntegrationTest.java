package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/** Shares, copies and restores an encrypted EBS snapshot across three accounts. */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2EbsSnapshotIntegrationTest {

    private static final String OWNER = "111111111111";
    private static final String RECIPIENT = "222222222222";
    private static final String THIRD = "123456789012";
    private static final String REGION = "us-east-1";
    private static final String JSON_1_1 = "application/x-amz-json-1.1";

    private static String ownerKeyArn;
    private static String recipientKeyArn;
    private static String volumeId;
    private static String snapshotId;
    private static String copyId;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void ownerSnapshotsAVolumeEncryptedWithItsOwnKey() {
        ownerKeyArn = createKey(OWNER);
        String ownerKeyId = ownerKeyArn.substring(ownerKeyArn.lastIndexOf('/') + 1);
        volumeId = ec2(OWNER)
            .formParam("Action", "CreateVolume")
            .formParam("AvailabilityZone", "us-east-1a")
            .formParam("Size", "4")
            .formParam("Encrypted", "true")
            .formParam("KmsKeyId", ownerKeyId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateVolumeResponse.kmsKeyId", equalTo(ownerKeyArn))
            .extract().path("CreateVolumeResponse.volumeId");

        snapshotId = ec2(OWNER)
            .formParam("Action", "CreateSnapshot")
            .formParam("VolumeId", volumeId)
            .formParam("Description", "before upgrade")
            .formParam("TagSpecification.1.ResourceType", "snapshot")
            .formParam("TagSpecification.1.Tag.1.Key", "Name")
            .formParam("TagSpecification.1.Tag.1.Value", "nightly")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateSnapshotResponse.status", equalTo("pending"))
            .body("CreateSnapshotResponse.volumeId", equalTo(volumeId))
            .body("CreateSnapshotResponse.volumeSize", equalTo("4"))
            .body("CreateSnapshotResponse.encrypted", equalTo("true"))
            .body("CreateSnapshotResponse.kmsKeyId", equalTo(ownerKeyArn))
            .body("CreateSnapshotResponse.ownerId", equalTo(OWNER))
            .body("CreateSnapshotResponse.tagSet.item.value", equalTo("nightly"))
            .extract().path("CreateSnapshotResponse.snapshotId");

        ec2(OWNER)
            .formParam("Action", "DescribeSnapshots")
            .formParam("SnapshotId.1", snapshotId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeSnapshotsResponse.snapshotSet.item.status", equalTo("completed"))
            .body("DescribeSnapshotsResponse.snapshotSet.item.progress", equalTo("100%"))
            .body("DescribeSnapshotsResponse.snapshotSet.item.description", equalTo("before upgrade"))
            .body("DescribeSnapshotsResponse.snapshotSet.item.storageTier", equalTo("standard"))
            .body("DescribeSnapshotsResponse.snapshotSet.item.tagSet.item.value", equalTo("nightly"));
    }

    @Test
    @Order(2)
    void anotherAccountCannotReachAnUnsharedSnapshot() {
        ec2(RECIPIENT)
            .formParam("Action", "DescribeSnapshots")
            .formParam("SnapshotId.1", snapshotId)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidSnapshot.NotFound"));

        copy(RECIPIENT, snapshotId)
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidSnapshot.NotFound"));
    }

    @Test
    @Order(3)
    void ownerGrantsCreateVolumePermission() {
        ec2(OWNER)
            .formParam("Action", "ModifySnapshotAttribute")
            .formParam("SnapshotId", snapshotId)
            .formParam("Attribute", "createVolumePermission")
            .formParam("OperationType", "add")
            .formParam("UserId.1", RECIPIENT)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ModifySnapshotAttributeResponse.return", equalTo("true"));

        ec2(OWNER)
            .formParam("Action", "DescribeSnapshotAttribute")
            .formParam("SnapshotId", snapshotId)
            .formParam("Attribute", "createVolumePermission")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeSnapshotAttributeResponse.snapshotId", equalTo(snapshotId))
            .body("DescribeSnapshotAttributeResponse.createVolumePermission.item.userId", equalTo(RECIPIENT));
    }

    @Test
    @Order(4)
    void recipientFindsTheSharedSnapshotAndCopiesItUnderItsOwnKey() {
        ec2(RECIPIENT)
            .formParam("Action", "DescribeSnapshots")
            .formParam("RestorableBy.1", "self")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString(snapshotId));
        ec2(RECIPIENT)
            .formParam("Action", "DescribeSnapshots")
            .formParam("Owner.1", "self")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString(snapshotId)));

        recipientKeyArn = createKey(RECIPIENT);
        copyId = ec2(RECIPIENT)
            .formParam("Action", "CopySnapshot")
            .formParam("SourceSnapshotId", snapshotId)
            .formParam("SourceRegion", REGION)
            .formParam("DestinationRegion", REGION)
            .formParam("Encrypted", "true")
            .formParam("KmsKeyId", recipientKeyArn)
            .formParam("TagSpecification.1.ResourceType", "snapshot")
            .formParam("TagSpecification.1.Tag.1.Key", "copied-from")
            .formParam("TagSpecification.1.Tag.1.Value", snapshotId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CopySnapshotResponse.tagSet.item.value", equalTo(snapshotId))
            .extract().path("CopySnapshotResponse.snapshotId");

        ec2(RECIPIENT)
            .formParam("Action", "DescribeSnapshots")
            .formParam("SnapshotId.1", copyId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeSnapshotsResponse.snapshotSet.item.ownerId", equalTo(RECIPIENT))
            .body("DescribeSnapshotsResponse.snapshotSet.item.status", equalTo("completed"))
            .body("DescribeSnapshotsResponse.snapshotSet.item.encrypted", equalTo("true"))
            .body("DescribeSnapshotsResponse.snapshotSet.item.kmsKeyId", equalTo(recipientKeyArn))
            .body("DescribeSnapshotsResponse.snapshotSet.item.volumeId", equalTo("vol-ffffffff"))
            .body("DescribeSnapshotsResponse.snapshotSet.item.volumeSize", equalTo("4"))
            .body("DescribeSnapshotsResponse.snapshotSet.item.description",
                    equalTo("[Copied " + snapshotId + " from us-east-1]"));
    }

    @Test
    @Order(5)
    void recipientSharesTheCopyWithAThirdAccount() {
        ec2(RECIPIENT)
            .formParam("Action", "ModifySnapshotAttribute")
            .formParam("SnapshotId", copyId)
            .formParam("Attribute", "createVolumePermission")
            .formParam("CreateVolumePermission.Add.1.UserId", THIRD)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(6)
    void thirdAccountRestoresAVolumeFromTheCopyAndCopiesItAgain() {
        ec2(THIRD)
            .formParam("Action", "CreateVolume")
            .formParam("AvailabilityZone", "us-east-1b")
            .formParam("SnapshotId", copyId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateVolumeResponse.snapshotId", equalTo(copyId))
            .body("CreateVolumeResponse.size", equalTo("4"))
            .body("CreateVolumeResponse.encrypted", equalTo("true"))
            .body("CreateVolumeResponse.kmsKeyId", equalTo(recipientKeyArn));

        ec2(THIRD)
            .formParam("Action", "CreateVolume")
            .formParam("AvailabilityZone", "us-east-1b")
            .formParam("SnapshotId", copyId)
            .formParam("Size", "2")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));

        ec2(THIRD)
            .formParam("Action", "CreateVolume")
            .formParam("AvailabilityZone", "us-east-1b")
            .formParam("SnapshotId", snapshotId)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidSnapshot.NotFound"));

        String secondCopy = copy(THIRD, copyId)
        .then()
            .statusCode(200)
            .extract().path("CopySnapshotResponse.snapshotId");
        ec2(THIRD)
            .formParam("Action", "DescribeSnapshots")
            .formParam("SnapshotId.1", secondCopy)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeSnapshotsResponse.snapshotSet.item.ownerId", equalTo(THIRD))
            .body("DescribeSnapshotsResponse.snapshotSet.item.kmsKeyId",
                    startsWith("arn:aws:kms:us-east-1:" + THIRD + ":key/"));
    }

    @Test
    @Order(7)
    void sharingAndKeyRulesAreEnforced() {
        ec2(OWNER)
            .formParam("Action", "ModifySnapshotAttribute")
            .formParam("SnapshotId", snapshotId)
            .formParam("Attribute", "createVolumePermission")
            .formParam("OperationType", "add")
            .formParam("UserGroup.1", "all")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidParameter"));

        ec2(RECIPIENT)
            .formParam("Action", "CopySnapshot")
            .formParam("SourceSnapshotId", snapshotId)
            .formParam("SourceRegion", REGION)
            .formParam("Encrypted", "true")
            .formParam("KmsKeyId", "alias/no-such-key")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));
    }

    @Test
    @Order(8)
    void revokingAndDeletingTheSourceLeavesTheCopyInPlace() {
        ec2(OWNER)
            .formParam("Action", "ModifySnapshotAttribute")
            .formParam("SnapshotId", snapshotId)
            .formParam("CreateVolumePermission.Remove.1.UserId", RECIPIENT)
        .when()
            .post("/")
        .then()
            .statusCode(200);
        copy(RECIPIENT, snapshotId)
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidSnapshot.NotFound"));

        ec2(RECIPIENT)
            .formParam("Action", "DeleteSnapshot")
            .formParam("SnapshotId", snapshotId)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidSnapshot.NotFound"));
        ec2(OWNER)
            .formParam("Action", "DeleteSnapshot")
            .formParam("SnapshotId", snapshotId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeleteSnapshotResponse.return", equalTo("true"));

        ec2(OWNER)
            .formParam("Action", "DescribeSnapshots")
            .formParam("SnapshotId.1", snapshotId)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidSnapshot.NotFound"));
        ec2(RECIPIENT)
            .formParam("Action", "DescribeSnapshots")
            .formParam("SnapshotId.1", copyId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeSnapshotsResponse.snapshotSet.item.snapshotId", equalTo(copyId));
    }

    @Test
    @Order(9)
    void createSnapshotsSnapshotsEveryVolumeAttachedToAnInstance() {
        String instanceId = ec2(OWNER)
            .formParam("Action", "RunInstances")
            .formParam("ImageId", "ami-0abcdef1234567890")
            .formParam("InstanceType", "t3.micro")
            .formParam("MinCount", "1")
            .formParam("MaxCount", "1")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
        String zone = ec2(OWNER)
            .formParam("Action", "DescribeInstances")
            .formParam("InstanceId.1", instanceId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("DescribeInstancesResponse.reservationSet.item.instancesSet.item"
                    + ".placement.availabilityZone");
        String dataVolumeId = ec2(OWNER)
            .formParam("Action", "CreateVolume")
            .formParam("AvailabilityZone", zone)
            .formParam("Size", "10")
            .formParam("TagSpecification.1.ResourceType", "volume")
            .formParam("TagSpecification.1.Tag.1.Key", "app")
            .formParam("TagSpecification.1.Tag.1.Value", "db")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVolumeResponse.volumeId");
        ec2(OWNER)
            .formParam("Action", "AttachVolume")
            .formParam("VolumeId", dataVolumeId)
            .formParam("InstanceId", instanceId)
            .formParam("Device", "/dev/sdf")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        ec2(OWNER)
            .formParam("Action", "CreateSnapshots")
            .formParam("InstanceSpecification.InstanceId", instanceId)
            .formParam("Description", "instance backup")
            .formParam("CopyTagsFromSource", "volume")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateSnapshotsResponse.snapshotSet.item.size()", equalTo(2))
            .body("CreateSnapshotsResponse.snapshotSet.item[1].volumeId", equalTo(dataVolumeId))
            .body("CreateSnapshotsResponse.snapshotSet.item[1].state", equalTo("pending"))
            .body("CreateSnapshotsResponse.snapshotSet.item[1].tagSet.item.key", equalTo("app"));

        ec2(OWNER)
            .formParam("Action", "CreateSnapshots")
            .formParam("InstanceSpecification.InstanceId", instanceId)
            .formParam("InstanceSpecification.ExcludeBootVolume", "true")
            .formParam("InstanceSpecification.ExcludeDataVolumeId.1", dataVolumeId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("<item>")));

        ec2(OWNER)
            .formParam("Action", "DescribeTags")
            .formParam("Filter.1.Name", "resource-id")
            .formParam("Filter.1.Value.1", dataVolumeId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeTagsResponse.tagSet.item.resourceType", equalTo("volume"));

        ec2(OWNER)
            .formParam("Action", "TerminateInstances")
            .formParam("InstanceId.1", instanceId)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private static RequestSpecification ec2(String account) {
        return given().header("Authorization", auth(account, "ec2"));
    }

    private static Response copy(String account, String sourceSnapshotId) {
        return ec2(account)
            .formParam("Action", "CopySnapshot")
            .formParam("SourceSnapshotId", sourceSnapshotId)
            .formParam("SourceRegion", REGION)
        .when()
            .post("/");
    }

    private static String createKey(String account) {
        return given()
            .header("Authorization", auth(account, "kms"))
            .header("X-Amz-Target", "TrentService.CreateKey")
            .contentType(JSON_1_1)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("KeyMetadata.Arn");
    }

    private static String auth(String account, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20260215/" + REGION + "/" + service + "/aws4_request";
    }
}
