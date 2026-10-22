package io.github.hectorvent.floci.services.backup;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** One account backs an S3 bucket up into an air-gapped vault it shares; another restores from it. */
@QuarkusTest
class BackupS3RestoreIntegrationTest {

    private static final String OWNER = "111111111111";
    private static final String RECIPIENT = "222222222222";
    private static final String OUTSIDER = "123456789012";
    private static final String VERSIONING_ENABLED =
            "<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>";
    private static final byte[] BINARY = new byte[256];

    static {
        for (int i = 0; i < BINARY.length; i++) {
            BINARY[i] = (byte) i;
        }
    }

    private static String auth(String service, String accountId) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260101/us-east-1/" + service + "/aws4_request";
    }

    private static String backup(String accountId) {
        return auth("backup", accountId);
    }

    private static String s3(String accountId) {
        return auth("s3", accountId);
    }

    private static String ram(String accountId) {
        return auth("ram", accountId);
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String vaultArn(String accountId, String name) {
        return "arn:aws:backup:us-east-1:" + accountId + ":backup-vault:" + name;
    }

    private static void createBucket(String accountId, String bucket, boolean versioned) {
        given().header("Authorization", s3(accountId)).when().put("/" + bucket).then().statusCode(200);
        if (versioned) {
            given().header("Authorization", s3(accountId)).body(VERSIONING_ENABLED)
            .when().put("/" + bucket + "?versioning").then().statusCode(200);
        }
    }

    private static void putText(String accountId, String bucket, String key, String text) {
        given().header("Authorization", s3(accountId)).contentType("text/plain").body(text)
        .when().put("/" + bucket + "/" + key).then().statusCode(200);
    }

    private static List<String> keys(String accountId, String bucket) {
        return given().header("Authorization", s3(accountId))
            .when().get("/" + bucket + "?list-type=2")
            .then().statusCode(200).extract().xmlPath().getList("ListBucketResult.Contents.Key");
    }

    private static String awaitTerminal(String accountId, String path, String statePath) {
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50))
            .until(() -> {
                String state = given().header("Authorization", backup(accountId))
                    .when().get(path).then().statusCode(200).extract().path(statePath);
                return List.of("COMPLETED", "FAILED", "ABORTED").contains(state);
            });
        return given().header("Authorization", backup(accountId))
            .when().get(path).then().statusCode(200).extract().path(statePath);
    }

    private static String startBackup(String accountId, String vault, String bucket) {
        return given().header("Authorization", backup(accountId)).contentType("application/json")
            .body("""
                {"BackupVaultName": "%s", "ResourceArn": "arn:aws:s3:::%s",
                 "IamRoleArn": "arn:aws:iam::%s:role/backup-role", "Lifecycle": {"DeleteAfterDays": 1}}
                """.formatted(vault, bucket, accountId))
        .when().put("/backup-jobs").then().statusCode(200).extract().path("BackupJobId");
    }

    private static void createVaults(String lag, String staging) {
        given().header("Authorization", backup(OWNER)).contentType("application/json")
            .body("{\"MinRetentionDays\": 7, \"MaxRetentionDays\": 365}")
        .when().put("/logically-air-gapped-backup-vaults/" + lag)
        .then().statusCode(200).body("VaultState", equalTo("CREATING"));
        given().header("Authorization", backup(OWNER)).contentType("application/json").body("{}")
        .when().put("/backup-vaults/" + staging).then().statusCode(200);
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
            .until(() -> "AVAILABLE".equals(given().header("Authorization", backup(OWNER))
                .when().get("/backup-vaults/" + lag).then().statusCode(200).extract().path("VaultState")));
    }

    private static Response startRestore(String accountId, String recoveryPointArn, String metadata, String token) {
        return given().header("Authorization", backup(accountId)).contentType("application/json")
            .body("""
                {"RecoveryPointArn": "%s", "Metadata": %s, "IamRoleArn": "arn:aws:iam::%s:role/restore-role",
                 "IdempotencyToken": "%s", "ResourceType": "S3"}
                """.formatted(recoveryPointArn, metadata, accountId, token))
        .when().put("/restore-jobs");
    }

    @Test
    void anAcceptingAccountRestoresTheBucketAsItWasAtBackupTime() {
        String source = unique("source-data");
        String lag = unique("lag");
        String staging = unique("staging");
        String target = unique("restored");
        String shareName = unique("lag-share");
        String lagArn = vaultArn(OWNER, lag);
        String resourceArn = "arn:aws:s3:::" + source;

        createBucket(OWNER, source, true);
        putText(OWNER, source, "a.txt", "first version");
        given().header("Authorization", s3(OWNER)).contentType("application/octet-stream")
            .header("x-amz-meta-origin", "seed").header("x-amz-tagging", "class=keep").body(BINARY)
        .when().put("/" + source + "/dir/b.bin").then().statusCode(200);

        createVaults(lag, staging);
        String shareArn = given().contentType("application/json").header("Authorization", ram(OWNER))
            .body("{\"name\": \"%s\", \"allowExternalPrincipals\": true, \"resourceArns\": [\"%s\"]}"
                .formatted(shareName, lagArn))
        .when().post("/createresourceshare")
        .then().statusCode(200).extract().path("resourceShare.resourceShareArn");

        String backupJobId = startBackup(OWNER, staging, source);
        assertEquals("COMPLETED", awaitTerminal(OWNER, "/backup-jobs/" + backupJobId, "State"));
        String stagingPoint = given().header("Authorization", backup(OWNER))
            .when().get("/backup-jobs/" + backupJobId)
            .then().statusCode(200)
                .body("BackupSizeInBytes", equalTo(13 + 256))
                .body("ResourceName", equalTo(source))
                .extract().path("RecoveryPointArn");

        putText(OWNER, source, "a.txt", "second version");
        putText(OWNER, source, "c.txt", "added after the backup");
        given().header("Authorization", s3(OWNER)).when().delete("/" + source + "/dir/b.bin").then().statusCode(204);

        String copyJobId = given().header("Authorization", backup(OWNER)).contentType("application/json")
            .body("""
                {"RecoveryPointArn": "%s", "SourceBackupVaultName": "%s", "DestinationBackupVaultArn": "%s",
                 "IamRoleArn": "arn:aws:iam::%s:role/backup-role", "Lifecycle": {"DeleteAfterDays": 180}}
                """.formatted(stagingPoint, staging, lagArn, OWNER))
        .when().put("/copy-jobs").then().statusCode(200).extract().path("CopyJobId");
        assertEquals("COMPLETED", awaitTerminal(OWNER, "/copy-jobs/" + copyJobId, "CopyJob.State"));

        String lagPoint = given().header("Authorization", backup(OWNER)).queryParam("resourceArn", resourceArn)
        .when().get("/backup-vaults/" + lag + "/recovery-points/")
        .then().statusCode(200)
            .body("RecoveryPoints", hasSize(1))
            .body("RecoveryPoints[0].Status", equalTo("COMPLETED"))
            .body("RecoveryPoints[0].Lifecycle.DeleteAfterDays", equalTo(180))
            .body("RecoveryPoints[0].CalculatedLifecycle.DeleteAt", notNullValue())
            .body("RecoveryPoints[0].BackupSizeInBytes", equalTo(13 + 256))
            .extract().path("RecoveryPoints[0].RecoveryPointArn");

        given().contentType("application/json").header("Authorization", ram(OWNER))
            .body("{\"resourceOwner\": \"SELF\", \"name\": \"%s\"}".formatted(shareName))
        .when().post("/getresourceshares")
        .then().statusCode(200).body("resourceShares[0].status", equalTo("ACTIVE"));
        given().contentType("application/json").header("Authorization", ram(OWNER))
            .body("{\"resourceShareArn\": \"%s\", \"principals\": [\"%s\"]}".formatted(shareArn, RECIPIENT))
        .when().post("/associateresourceshare").then().statusCode(200);
        String invitationArn = given().contentType("application/json").header("Authorization", ram(RECIPIENT))
            .body("{\"resourceShareArns\": [\"%s\"]}".formatted(shareArn))
        .when().post("/getresourceshareinvitations")
        .then().statusCode(200)
            .body("resourceShareInvitations[0].status", equalTo("PENDING"))
            .extract().path("resourceShareInvitations[0].resourceShareInvitationArn");
        given().contentType("application/json").header("Authorization", ram(RECIPIENT))
            .body("{\"resourceShareInvitationArn\": \"%s\"}".formatted(invitationArn))
        .when().post("/acceptresourceshareinvitation").then().statusCode(200);
        given().contentType("application/json").header("Authorization", ram(OWNER))
            .body("{\"associationType\": \"PRINCIPAL\", \"resourceShareArns\": [\"%s\"]}".formatted(shareArn))
        .when().post("/getresourceshareassociations")
        .then().statusCode(200)
            .body("resourceShareAssociations[0].associatedEntity", equalTo(RECIPIENT))
            .body("resourceShareAssociations[0].status", equalTo("ASSOCIATED"));

        given().header("Authorization", backup(RECIPIENT))
            .queryParam("shared", "true").queryParam("vaultType", "LOGICALLY_AIR_GAPPED_BACKUP_VAULT")
        .when().get("/backup-vaults/")
        .then().statusCode(200).body("BackupVaultList.BackupVaultName", hasItem(lag));
        given().header("Authorization", backup(RECIPIENT))
            .queryParam("backupVaultAccountId", OWNER).queryParam("resourceArn", resourceArn)
        .when().get("/backup-vaults/" + lag + "/recovery-points/")
        .then().statusCode(200).body("RecoveryPoints.RecoveryPointArn", contains(lagPoint));
        given().header("Authorization", backup(RECIPIENT)).queryParam("backupVaultAccountId", OWNER)
        .when().get("/backup-vaults/" + lag + "/recovery-points/" + lagPoint + "/restore-metadata")
        .then().statusCode(200)
            .body("BackupVaultArn", equalTo(lagArn))
            .body("RecoveryPointArn", equalTo(lagPoint))
            .body("ResourceType", equalTo("S3"))
            .body("RestoreMetadata.DestinationBucketName", equalTo(source))
            .body("RestoreMetadata.'aws:backup:request-id'", notNullValue());

        createBucket(RECIPIENT, target, true);
        String metadata = """
            {"DestinationBucketName": "%s", "NewBucket": "false", "EncryptionType": "SSE_KMS",
             "KMSKey": "arn:aws:kms:us-east-1:%s:key/restore-key"}
            """.formatted(target, RECIPIENT);
        String token = UUID.randomUUID().toString();
        String restoreJobId = startRestore(RECIPIENT, lagPoint, metadata, token)
            .then().statusCode(200).extract().path("RestoreJobId");
        startRestore(RECIPIENT, lagPoint, metadata, token)
            .then().statusCode(200).body("RestoreJobId", equalTo(restoreJobId));

        assertEquals("COMPLETED", awaitTerminal(RECIPIENT, "/restore-jobs/" + restoreJobId, "Status"));
        given().header("Authorization", backup(RECIPIENT))
        .when().get("/restore-jobs/" + restoreJobId)
        .then().statusCode(200)
            .body("AccountId", equalTo(RECIPIENT))
            .body("CreatedResourceArn", equalTo("arn:aws:s3:::" + target))
            .body("SourceResourceArn", equalTo(resourceArn))
            .body("BackupVaultArn", equalTo(lagArn))
            .body("ResourceType", equalTo("S3"))
            .body("PercentDone", equalTo("100.00%"))
            .body("BackupSizeInBytes", equalTo(13 + 256))
            .body("RecoveryPointCreationDate", notNullValue())
            .body("CompletionDate", notNullValue());

        assertEquals(List.of("a.txt", "dir/b.bin"), keys(RECIPIENT, target));
        given().header("Authorization", s3(RECIPIENT))
        .when().get("/" + target + "/a.txt")
        .then().statusCode(200)
            .header("x-amz-server-side-encryption", "aws:kms")
            .body(equalTo("first version"));
        Response binary = given().header("Authorization", s3(RECIPIENT)).when().get("/" + target + "/dir/b.bin");
        binary.then().statusCode(200)
            .header("Content-Type", startsWith("application/octet-stream"))
            .header("x-amz-meta-origin", "seed");
        assertArrayEquals(BINARY, binary.asByteArray());
        given().header("Authorization", s3(RECIPIENT))
        .when().get("/" + target + "/dir/b.bin?tagging")
        .then().statusCode(200).body(containsString("<Key>class</Key>"));

        given().header("Authorization", backup(RECIPIENT)).queryParam("status", "COMPLETED")
        .when().get("/restore-jobs/")
        .then().statusCode(200).body("RestoreJobs.RestoreJobId", hasItem(restoreJobId));
        given().header("Authorization", backup(OWNER))
        .when().get("/backup-vaults/" + lag + "/recovery-points/" + lagPoint)
        .then().statusCode(200).body("LastRestoreTime", notNullValue());

        String selected = unique("selected");
        String selectiveJobId = startRestore(RECIPIENT, lagPoint, """
            {"DestinationBucketName": "%s", "NewBucket": "true", "ItemsToRestore": "[\\"s3://%s/dir/\\"]"}
            """.formatted(selected, source), UUID.randomUUID().toString())
            .then().statusCode(200).extract().path("RestoreJobId");
        assertEquals("COMPLETED", awaitTerminal(RECIPIENT, "/restore-jobs/" + selectiveJobId, "Status"));
        assertEquals(List.of("dir/b.bin"), keys(RECIPIENT, selected));
        given().header("Authorization", s3(RECIPIENT))
        .when().get("/" + selected + "?versioning")
        .then().statusCode(200).body(containsString("<Status>Enabled</Status>"));

        startRestore(OUTSIDER, lagPoint, metadata, UUID.randomUUID().toString())
            .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void s3BackupAndRestoreNeedVersionedBuckets() {
        String unversioned = unique("unversioned");
        String source = unique("versioned");
        String vault = unique("s3-vault");
        createBucket(OUTSIDER, unversioned, false);
        createBucket(OUTSIDER, source, true);
        putText(OUTSIDER, source, "k.txt", "v");
        given().header("Authorization", backup(OUTSIDER)).contentType("application/json").body("{}")
        .when().put("/backup-vaults/" + vault).then().statusCode(200);

        String failedJob = startBackup(OUTSIDER, vault, unversioned);
        assertEquals("FAILED", awaitTerminal(OUTSIDER, "/backup-jobs/" + failedJob, "State"));
        given().header("Authorization", backup(OUTSIDER))
        .when().get("/backup-jobs/" + failedJob)
        .then().statusCode(200)
            .body("StatusMessage", containsString("Versioning"))
            .body("RecoveryPointArn", nullValue());

        String missingJob = startBackup(OUTSIDER, vault, unique("missing"));
        assertEquals("FAILED", awaitTerminal(OUTSIDER, "/backup-jobs/" + missingJob, "State"));

        String jobId = startBackup(OUTSIDER, vault, source);
        assertEquals("COMPLETED", awaitTerminal(OUTSIDER, "/backup-jobs/" + jobId, "State"));
        String point = given().header("Authorization", backup(OUTSIDER))
            .when().get("/backup-jobs/" + jobId).then().extract().path("RecoveryPointArn");

        given().header("Authorization", backup(OUTSIDER))
        .when().get("/backup-vaults/" + vault + "/recovery-points/" + point + "/restore-metadata")
        .then().statusCode(200)
            .body("RestoreMetadata", hasKey("aws:backup:request-id"))
            .body("RestoreMetadata.DestinationBucketName", equalTo(source));

        String restoreJobId = startRestore(OUTSIDER, point,
                "{\"DestinationBucketName\": \"%s\"}".formatted(unversioned), UUID.randomUUID().toString())
            .then().statusCode(200).extract().path("RestoreJobId");
        assertEquals("FAILED", awaitTerminal(OUTSIDER, "/restore-jobs/" + restoreJobId, "Status"));
        given().header("Authorization", backup(OUTSIDER))
        .when().get("/restore-jobs/" + restoreJobId)
        .then().statusCode(200).body("StatusMessage", containsString("Versioning"));
        assertEquals(List.of(), keys(OUTSIDER, unversioned));

        startRestore(OUTSIDER, point, "{\"NewBucket\": \"true\"}", UUID.randomUUID().toString())
            .then().statusCode(400).body("__type", equalTo("MissingParameterValueException"));
        given().header("Authorization", backup(OUTSIDER)).contentType("application/json")
            .body(Map.of("RecoveryPointArn", point))
        .when().put("/restore-jobs")
        .then().statusCode(400).body("__type", equalTo("MissingParameterValueException"));
    }
}
