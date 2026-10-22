package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.testing.MutableClock;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class Ec2EbsSnapshotLockIntegrationTest {

    private static final String ACCOUNT = "918273645012";
    private static final String REGION = "us-east-1";
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    @Inject
    MutableClock clock;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void governanceLockIsDescribedRefusesDeleteAndUnlocks() {
        String snapshotId = snapshot();

        ec2()
            .formParam("Action", "LockSnapshot")
            .formParam("SnapshotId", snapshotId)
            .formParam("LockMode", "governance")
            .formParam("LockDuration", "3")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("LockSnapshotResponse.requestId", notNullValue())
            .body("LockSnapshotResponse.snapshotId", equalTo(snapshotId))
            .body("LockSnapshotResponse.lockState", equalTo("governance"))
            .body("LockSnapshotResponse.lockDuration", equalTo("3"))
            .body("LockSnapshotResponse.lockCreatedOn", notNullValue())
            .body("LockSnapshotResponse.lockExpiresOn", notNullValue())
            .body("LockSnapshotResponse.lockDurationStartTime", notNullValue())
            .body(not(containsString("coolOffPeriod")));

        ec2()
            .formParam("Action", "DeleteSnapshot")
            .formParam("SnapshotId", snapshotId)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("SnapshotLocked"));

        ec2()
            .formParam("Action", "DescribeLockedSnapshots")
            .formParam("SnapshotId.1", snapshotId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeLockedSnapshotsResponse.snapshotSet.item.ownerId", equalTo(ACCOUNT))
            .body("DescribeLockedSnapshotsResponse.snapshotSet.item.snapshotId", equalTo(snapshotId))
            .body("DescribeLockedSnapshotsResponse.snapshotSet.item.lockState", equalTo("governance"))
            .body("DescribeLockedSnapshotsResponse.snapshotSet.item.lockDuration", equalTo("3"));

        ec2()
            .formParam("Action", "DescribeLockedSnapshots")
            .formParam("SnapshotId.1", snapshotId)
            .formParam("Filter.1.Name", "lock-state")
            .formParam("Filter.1.Value.1", "compliance")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("<item>")));

        ec2()
            .formParam("Action", "UnlockSnapshot")
            .formParam("SnapshotId", snapshotId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("UnlockSnapshotResponse.snapshotId", equalTo(snapshotId));

        ec2()
            .formParam("Action", "DescribeLockedSnapshots")
            .formParam("SnapshotId.1", snapshotId)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("SnapshotLockNotFound"));

        ec2()
            .formParam("Action", "DeleteSnapshot")
            .formParam("SnapshotId", snapshotId)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    void complianceLockLeavesItsCoolOffPeriodAndExpiresWithTheClock() {
        String snapshotId = snapshot();

        ec2()
            .formParam("Action", "LockSnapshot")
            .formParam("SnapshotId", snapshotId)
            .formParam("LockMode", "compliance")
            .formParam("LockDuration", "1")
            .formParam("CoolOffPeriod", "1")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("LockSnapshotResponse.lockState", equalTo("compliance-cooloff"))
            .body("LockSnapshotResponse.coolOffPeriod", equalTo("1"))
            .body("LockSnapshotResponse.coolOffPeriodExpiresOn", notNullValue());

        clock.advance(Duration.ofHours(2));
        describeState(snapshotId, "compliance");
        ec2()
            .formParam("Action", "UnlockSnapshot")
            .formParam("SnapshotId", snapshotId)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("SnapshotLocked"));

        clock.advance(Duration.ofDays(1));
        describeState(snapshotId, "expired");
        ec2()
            .formParam("Action", "DeleteSnapshot")
            .formParam("SnapshotId", snapshotId)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    void lockSnapshotTakesAnExpirationDateAndRejectsBadRequests() {
        String snapshotId = snapshot();
        String expiresOn = ISO.format(clock.instant().truncatedTo(ChronoUnit.SECONDS).plus(Duration.ofHours(1)));

        ec2()
            .formParam("Action", "LockSnapshot")
            .formParam("SnapshotId", snapshotId)
            .formParam("LockMode", "governance")
            .formParam("LockDuration", "1")
            .formParam("ExpirationDate", expiresOn)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidParameterCombination"));

        ec2()
            .formParam("Action", "LockSnapshot")
            .formParam("SnapshotId", "snap-0123456789abcdef0")
            .formParam("LockMode", "governance")
            .formParam("LockDuration", "1")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidSnapshot.NotFound"));

        ec2()
            .formParam("Action", "LockSnapshot")
            .formParam("SnapshotId", snapshotId)
            .formParam("LockMode", "governance")
            .formParam("ExpirationDate", expiresOn)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("LockSnapshotResponse.lockExpiresOn", equalTo(expiresOn))
            .body(not(containsString("<lockDuration>")));

        ec2()
            .formParam("Action", "UnlockSnapshot")
            .formParam("SnapshotId", snapshotId)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private void describeState(String snapshotId, String lockState) {
        ec2()
            .formParam("Action", "DescribeLockedSnapshots")
            .formParam("SnapshotId.1", snapshotId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeLockedSnapshotsResponse.snapshotSet.item.lockState", equalTo(lockState));
    }

    private static String snapshot() {
        String volumeId = ec2()
            .formParam("Action", "CreateVolume")
            .formParam("AvailabilityZone", REGION + "a")
            .formParam("Size", "1")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVolumeResponse.volumeId");
        return ec2()
            .formParam("Action", "CreateSnapshot")
            .formParam("VolumeId", volumeId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateSnapshotResponse.snapshotId");
    }

    private static RequestSpecification ec2() {
        return given().header("Authorization",
                "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260215/" + REGION + "/ec2/aws4_request");
    }
}
