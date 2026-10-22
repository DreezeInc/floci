package io.github.hectorvent.floci.services.backup;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class BackupAirGappedVaultIntegrationTest {

    private static final String OWNER = "111111111111";
    private static final String RECIPIENT = "222222222222";
    private static final String OUTSIDER = "123456789012";
    private static final long DAY = 86_400;

    private static String auth(String accountId) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260101/us-east-1/backup/aws4_request";
    }

    private static String ramAuth(String accountId) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260101/us-east-1/ram/aws4_request";
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String vaultArn(String accountId, String name) {
        return "arn:aws:backup:us-east-1:" + accountId + ":backup-vault:" + name;
    }

    private static void createVault(String accountId, String name) {
        given().header("Authorization", auth(accountId)).contentType("application/json").body("{}")
        .when().put("/backup-vaults/" + name).then().statusCode(200);
    }

    private static void createAirGappedVault(String accountId, String name, int min, int max) {
        given().header("Authorization", auth(accountId)).contentType("application/json")
            .body("{\"MinRetentionDays\": %d, \"MaxRetentionDays\": %d}".formatted(min, max))
        .when().put("/logically-air-gapped-backup-vaults/" + name)
        .then().statusCode(200)
            .body("VaultState", equalTo("CREATING"));
        awaitVaultAvailable(accountId, name);
    }

    private static void awaitVaultAvailable(String accountId, String name) {
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
            .until(() -> "AVAILABLE".equals(given().header("Authorization", auth(accountId))
                .when().get("/backup-vaults/" + name)
                .then().statusCode(200).extract().path("VaultState")));
    }

    private static String backUp(String accountId, String vault, String resourceArn, int deleteAfterDays) {
        String jobId = given().header("Authorization", auth(accountId)).contentType("application/json")
            .body("""
                {"BackupVaultName": "%s", "ResourceArn": "%s",
                 "IamRoleArn": "arn:aws:iam::%s:role/backup-role", "Lifecycle": {"DeleteAfterDays": %d}}
                """.formatted(vault, resourceArn, accountId, deleteAfterDays))
        .when().put("/backup-jobs").then().statusCode(200).extract().path("BackupJobId");
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50))
            .until(() -> "COMPLETED".equals(given().header("Authorization", auth(accountId))
                .when().get("/backup-jobs/" + jobId).then().statusCode(200).extract().path("State")));
        return given().header("Authorization", auth(accountId))
            .when().get("/backup-jobs/" + jobId).then().statusCode(200).extract().path("RecoveryPointArn");
    }

    private static String startCopy(String accountId, String recoveryPointArn, String sourceVault,
                                    String destinationVaultArn, int deleteAfterDays) {
        return given().header("Authorization", auth(accountId)).contentType("application/json")
            .body("""
                {"RecoveryPointArn": "%s", "SourceBackupVaultName": "%s", "DestinationBackupVaultArn": "%s",
                 "IamRoleArn": "arn:aws:iam::%s:role/backup-role", "Lifecycle": {"DeleteAfterDays": %d}}
                """.formatted(recoveryPointArn, sourceVault, destinationVaultArn, accountId, deleteAfterDays))
        .when().put("/copy-jobs")
        .then().statusCode(200)
            .body("CreationDate", notNullValue())
            .body("IsParent", equalTo(false))
            .extract().path("CopyJobId");
    }

    private static void awaitCopyState(String accountId, String copyJobId, String expected) {
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50))
            .until(() -> {
                String state = given().header("Authorization", auth(accountId))
                    .when().get("/copy-jobs/" + copyJobId).then().statusCode(200).extract().path("CopyJob.State");
                return expected.equals(state) || "FAILED".equals(state) || "COMPLETED".equals(state);
            });
        given().header("Authorization", auth(accountId))
        .when().get("/copy-jobs/" + copyJobId)
        .then().statusCode(200).body("CopyJob.State", equalTo(expected));
    }

    private static void shareVault(String owner, String recipient, String vaultArn) {
        String shareArn = given().contentType("application/json").header("Authorization", ramAuth(owner))
            .body("{\"name\": \"%s\", \"allowExternalPrincipals\": true, \"resourceArns\": [\"%s\"]}"
                .formatted(unique("vault-share"), vaultArn))
        .when().post("/createresourceshare")
        .then().statusCode(200).extract().path("resourceShare.resourceShareArn");
        given().contentType("application/json").header("Authorization", ramAuth(owner))
            .body("{\"resourceShareArn\": \"%s\", \"principals\": [\"%s\"]}".formatted(shareArn, recipient))
        .when().post("/associateresourceshare").then().statusCode(200);
        String invitationArn = given().contentType("application/json").header("Authorization", ramAuth(recipient))
            .body("{\"resourceShareArns\": [\"%s\"]}".formatted(shareArn))
        .when().post("/getresourceshareinvitations")
        .then().statusCode(200)
            .body("resourceShareInvitations[0].status", equalTo("PENDING"))
            .extract().path("resourceShareInvitations[0].resourceShareInvitationArn");

        given().header("Authorization", auth(recipient))
        .when().get("/backup-vaults/?shared=true")
        .then().statusCode(200).body("BackupVaultList.BackupVaultArn", not(hasItem(vaultArn)));

        given().contentType("application/json").header("Authorization", ramAuth(recipient))
            .body("{\"resourceShareInvitationArn\": \"%s\"}".formatted(invitationArn))
        .when().post("/acceptresourceshareinvitation").then().statusCode(200);
    }

    @Test
    void airGappedVaultIsCreatedLockedWithItsRetentionWindow() {
        String name = unique("lag");
        createAirGappedVault(OWNER, name, 7, 365);

        given().header("Authorization", auth(OWNER))
        .when().get("/backup-vaults/" + name)
        .then().statusCode(200)
            .body("BackupVaultArn", equalTo(vaultArn(OWNER, name)))
            .body("VaultType", equalTo("LOGICALLY_AIR_GAPPED_BACKUP_VAULT"))
            .body("VaultState", equalTo("AVAILABLE"))
            .body("EncryptionKeyType", equalTo("AWS_OWNED_KMS_KEY"))
            .body("Locked", equalTo(true))
            .body("MinRetentionDays", equalTo(7))
            .body("MaxRetentionDays", equalTo(365))
            .body("NumberOfRecoveryPoints", equalTo(0));

        given().header("Authorization", auth(OWNER))
        .when().get("/backup-vaults/?vaultType=LOGICALLY_AIR_GAPPED_BACKUP_VAULT")
        .then().statusCode(200)
            .body("BackupVaultList.BackupVaultName", hasItem(name))
            .body("BackupVaultList.VaultType", everyItem(equalTo("LOGICALLY_AIR_GAPPED_BACKUP_VAULT")));
    }

    @Test
    void airGappedVaultRequiresAValidRetentionWindowAndAFreeName() {
        String name = unique("lag-invalid");
        given().header("Authorization", auth(OWNER)).contentType("application/json")
            .body("{\"MaxRetentionDays\": 30}")
        .when().put("/logically-air-gapped-backup-vaults/" + name)
        .then().statusCode(400).body("__type", equalTo("MissingParameterValueException"));

        given().header("Authorization", auth(OWNER)).contentType("application/json")
            .body("{\"MinRetentionDays\": 6, \"MaxRetentionDays\": 30}")
        .when().put("/logically-air-gapped-backup-vaults/" + name)
        .then().statusCode(400).body("__type", equalTo("InvalidParameterValueException"));

        given().header("Authorization", auth(OWNER)).contentType("application/json")
            .body("{\"MinRetentionDays\": 30, \"MaxRetentionDays\": 7}")
        .when().put("/logically-air-gapped-backup-vaults/" + name)
        .then().statusCode(400).body("__type", equalTo("InvalidParameterValueException"));

        createVault(OWNER, name);
        given().header("Authorization", auth(OWNER)).contentType("application/json")
            .body("{\"MinRetentionDays\": 7, \"MaxRetentionDays\": 30}")
        .when().put("/logically-air-gapped-backup-vaults/" + name)
        .then().statusCode(400).body("__type", equalTo("AlreadyExistsException"));
    }

    @Test
    void airGappedVaultTakesNoDirectBackupsAndNoLockChanges() {
        String name = unique("lag-direct");
        createAirGappedVault(OWNER, name, 7, 30);

        given().header("Authorization", auth(OWNER)).contentType("application/json")
            .body("""
                {"BackupVaultName": "%s", "ResourceArn": "arn:aws:dynamodb:us-east-1:%s:table/t",
                 "IamRoleArn": "arn:aws:iam::%s:role/backup-role", "Lifecycle": {"DeleteAfterDays": 10}}
                """.formatted(name, OWNER, OWNER))
        .when().put("/backup-jobs")
        .then().statusCode(400).body("__type", equalTo("InvalidParameterValueException"));

        given().header("Authorization", auth(OWNER)).contentType("application/json")
            .body("{\"MinRetentionDays\": 1}")
        .when().put("/backup-vaults/" + name + "/vault-lock")
        .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));

        given().header("Authorization", auth(OWNER))
        .when().delete("/backup-vaults/" + name + "/vault-lock")
        .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    void copyJobCopiesARecoveryPointIntoTheAirGappedVaultWithItsOwnLifecycle() {
        String staging = unique("staging");
        String lag = unique("lag-copy");
        String resourceArn = "arn:aws:dynamodb:us-east-1:" + OWNER + ":table/" + unique("table");
        createVault(OWNER, staging);
        createAirGappedVault(OWNER, lag, 7, 365);
        String sourceArn = backUp(OWNER, staging, resourceArn, 1);

        long sourceCreated = ((Number) given().header("Authorization", auth(OWNER))
            .when().get("/backup-vaults/" + staging + "/recovery-points/" + sourceArn)
            .then().statusCode(200)
                .body("Lifecycle.DeleteAfterDays", equalTo(1))
                .body("VaultType", equalTo("BACKUP_VAULT"))
                .extract().path("CreationDate")).longValue();

        String copyJobId = startCopy(OWNER, sourceArn, staging, vaultArn(OWNER, lag), 180);
        awaitCopyState(OWNER, copyJobId, "COMPLETED");

        String copyArn = given().header("Authorization", auth(OWNER))
            .when().get("/copy-jobs/" + copyJobId)
            .then().statusCode(200)
                .body("CopyJob.AccountId", equalTo(OWNER))
                .body("CopyJob.SourceRecoveryPointArn", equalTo(sourceArn))
                .body("CopyJob.SourceBackupVaultArn", equalTo(vaultArn(OWNER, staging)))
                .body("CopyJob.DestinationBackupVaultArn", equalTo(vaultArn(OWNER, lag)))
                .body("CopyJob.DestinationVaultType", equalTo("LOGICALLY_AIR_GAPPED_BACKUP_VAULT"))
                .body("CopyJob.DestinationRecoveryPointLifecycle.DeleteAfterDays", equalTo(180))
                .body("CopyJob.ResourceArn", equalTo(resourceArn))
                .body("CopyJob.CompletionDate", notNullValue())
                .extract().path("CopyJob.DestinationRecoveryPointArn");

        given().header("Authorization", auth(OWNER))
            .queryParam("resourceArn", resourceArn)
        .when().get("/backup-vaults/" + lag + "/recovery-points/")
        .then().statusCode(200)
            .body("RecoveryPoints", hasSize(1))
            .body("RecoveryPoints[0].RecoveryPointArn", equalTo(copyArn))
            .body("RecoveryPoints[0].Status", equalTo("COMPLETED"))
            .body("RecoveryPoints[0].VaultType", equalTo("LOGICALLY_AIR_GAPPED_BACKUP_VAULT"))
            .body("RecoveryPoints[0].SourceBackupVaultArn", equalTo(vaultArn(OWNER, staging)))
            .body("RecoveryPoints[0].Lifecycle.DeleteAfterDays", equalTo(180))
            .body("RecoveryPoints[0].CreationDate", equalTo((int) sourceCreated))
            .body("RecoveryPoints[0].CalculatedLifecycle.DeleteAt", equalTo((int) (sourceCreated + 180 * DAY)))
            .body("RecoveryPoints[0].CompletionDate", notNullValue());

        given().header("Authorization", auth(OWNER))
        .when().get("/backup-vaults/" + lag)
        .then().statusCode(200).body("NumberOfRecoveryPoints", equalTo(1));

        given().header("Authorization", auth(OWNER))
            .queryParam("sourceRecoveryPointArn", sourceArn)
            .queryParam("state", "COMPLETED")
        .when().get("/copy-jobs/")
        .then().statusCode(200)
            .body("CopyJobs.CopyJobId", contains(copyJobId));

        given().header("Authorization", auth(OWNER))
        .when().delete("/backup-vaults/" + lag + "/recovery-points/" + copyArn)
        .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    void copyJobOutsideTheVaultRetentionWindowFails() {
        String staging = unique("staging");
        String lag = unique("lag-window");
        createVault(OWNER, staging);
        createAirGappedVault(OWNER, lag, 7, 365);
        String sourceArn = backUp(OWNER, staging, "arn:aws:dynamodb:us-east-1:" + OWNER + ":table/window", 1);

        String copyJobId = startCopy(OWNER, sourceArn, staging, vaultArn(OWNER, lag), 400);
        awaitCopyState(OWNER, copyJobId, "FAILED");
        given().header("Authorization", auth(OWNER))
        .when().get("/copy-jobs/" + copyJobId)
        .then().statusCode(200).body("CopyJob.StatusMessage", containsString("365"));

        given().header("Authorization", auth(OWNER)).contentType("application/json")
            .body("""
                {"RecoveryPointArn": "%s", "SourceBackupVaultName": "%s",
                 "DestinationBackupVaultArn": "arn:aws:s3:::not-a-vault", "IamRoleArn": "arn:aws:iam::%s:role/r"}
                """.formatted(sourceArn, staging, OWNER))
        .when().put("/copy-jobs")
        .then().statusCode(400).body("__type", equalTo("InvalidParameterValueException"));
    }

    @Test
    void sharedAirGappedVaultIsReadableByTheAcceptingAccountOnly() {
        String staging = unique("staging");
        String lag = unique("lag-shared");
        String resourceArn = "arn:aws:dynamodb:us-east-1:" + OWNER + ":table/" + unique("table");
        createVault(OWNER, staging);
        createAirGappedVault(OWNER, lag, 7, 365);
        String sourceArn = backUp(OWNER, staging, resourceArn, 1);
        String copyJobId = startCopy(OWNER, sourceArn, staging, vaultArn(OWNER, lag), 30);
        awaitCopyState(OWNER, copyJobId, "COMPLETED");
        String copyArn = given().header("Authorization", auth(OWNER))
            .when().get("/copy-jobs/" + copyJobId).then().extract().path("CopyJob.DestinationRecoveryPointArn");

        given().header("Authorization", auth(RECIPIENT)).queryParam("backupVaultAccountId", OWNER)
        .when().get("/backup-vaults/" + lag)
        .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));

        shareVault(OWNER, RECIPIENT, vaultArn(OWNER, lag));

        given().header("Authorization", auth(RECIPIENT))
            .queryParam("shared", "true")
            .queryParam("vaultType", "LOGICALLY_AIR_GAPPED_BACKUP_VAULT")
        .when().get("/backup-vaults/")
        .then().statusCode(200)
            .body("BackupVaultList.BackupVaultName", hasItem(lag))
            .body("BackupVaultList.BackupVaultArn", hasItem(vaultArn(OWNER, lag)));

        given().header("Authorization", auth(RECIPIENT))
        .when().get("/backup-vaults/")
        .then().statusCode(200).body("BackupVaultList.BackupVaultName", not(hasItem(lag)));

        given().header("Authorization", auth(RECIPIENT)).queryParam("backupVaultAccountId", OWNER)
        .when().get("/backup-vaults/" + lag)
        .then().statusCode(200)
            .body("BackupVaultArn", equalTo(vaultArn(OWNER, lag)))
            .body("NumberOfRecoveryPoints", equalTo(1));

        given().header("Authorization", auth(RECIPIENT))
            .queryParam("backupVaultAccountId", OWNER)
            .queryParam("resourceArn", resourceArn)
        .when().get("/backup-vaults/" + lag + "/recovery-points/")
        .then().statusCode(200)
            .body("RecoveryPoints.RecoveryPointArn", contains(copyArn));

        given().header("Authorization", auth(RECIPIENT)).queryParam("backupVaultAccountId", OWNER)
        .when().get("/backup-vaults/" + lag + "/recovery-points/" + copyArn)
        .then().statusCode(200)
            .body("RecoveryPointArn", equalTo(copyArn))
            .body("Lifecycle.DeleteAfterDays", equalTo(30));

        given().header("Authorization", auth(RECIPIENT))
        .when().get("/backup-vaults/" + lag + "/recovery-points/")
        .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));

        given().header("Authorization", auth(OUTSIDER)).queryParam("backupVaultAccountId", OWNER)
        .when().get("/backup-vaults/" + lag + "/recovery-points/")
        .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));

        given().header("Authorization", auth(OUTSIDER)).queryParam("shared", "true")
        .when().get("/backup-vaults/")
        .then().statusCode(200).body("BackupVaultList.BackupVaultName", not(hasItem(lag)));
    }

    @Test
    void sharingAStandardVaultGrantsNothing() {
        String vault = unique("standard-shared");
        createVault(OWNER, vault);
        shareVault(OWNER, RECIPIENT, vaultArn(OWNER, vault));

        given().header("Authorization", auth(RECIPIENT)).queryParam("backupVaultAccountId", OWNER)
        .when().get("/backup-vaults/" + vault)
        .then().statusCode(404);
        given().header("Authorization", auth(RECIPIENT)).queryParam("shared", "true")
        .when().get("/backup-vaults/")
        .then().statusCode(200).body("BackupVaultList.BackupVaultName", not(hasItem(vault)));
    }

    @Test
    void crossAccountCopyNeedsTheDestinationVaultPolicy() {
        String staging = unique("staging");
        String destination = unique("central");
        createVault(OWNER, staging);
        createVault(RECIPIENT, destination);
        String sourceArn = backUp(OWNER, staging, "arn:aws:dynamodb:us-east-1:" + OWNER + ":table/central", 1);

        String denied = startCopy(OWNER, sourceArn, staging, vaultArn(RECIPIENT, destination), 30);
        awaitCopyState(OWNER, denied, "FAILED");
        given().header("Authorization", auth(OWNER))
        .when().get("/copy-jobs/" + denied)
        .then().statusCode(200).body("CopyJob.StatusMessage", containsString("backup:CopyIntoBackupVault"));

        String policy = """
            {"Version": "2012-10-17", "Statement": [{"Effect": "Allow", "Action": "backup:CopyIntoBackupVault",
             "Principal": {"AWS": "arn:aws:iam::%s:root"}, "Resource": "*"}]}
            """.formatted(OWNER).replace("\"", "\\\"").replace("\n", " ");
        given().header("Authorization", auth(RECIPIENT)).contentType("application/json")
            .body("{\"Policy\": \"" + policy + "\"}")
        .when().put("/backup-vaults/" + destination + "/access-policy").then().statusCode(204);

        String allowed = startCopy(OWNER, sourceArn, staging, vaultArn(RECIPIENT, destination), 30);
        awaitCopyState(OWNER, allowed, "COMPLETED");
        String copyArn = given().header("Authorization", auth(OWNER))
            .when().get("/copy-jobs/" + allowed).then().extract().path("CopyJob.DestinationRecoveryPointArn");
        assertEquals("arn:aws:backup:us-east-1:" + RECIPIENT + ":recovery-point",
                copyArn.substring(0, copyArn.lastIndexOf(':')));

        given().header("Authorization", auth(RECIPIENT))
        .when().get("/backup-vaults/" + destination + "/recovery-points/")
        .then().statusCode(200).body("RecoveryPoints.RecoveryPointArn", contains(copyArn));
    }

    @Test
    void s3BucketsNamedLikeBackupPathsStayS3Buckets() {
        String s3 = "AWS4-HMAC-SHA256 Credential=" + OUTSIDER + "/20260101/us-east-1/s3/aws4_request";
        for (String bucket : List.of("copy-jobs", "resources", "logically-air-gapped-backup-vaults")) {
            given().header("Authorization", s3).when().put("/" + bucket).then().statusCode(200);
            given().header("Authorization", s3).body("object in " + bucket)
            .when().put("/" + bucket + "/key").then().statusCode(200);
            given().header("Authorization", s3)
            .when().get("/" + bucket + "/key").then().statusCode(200).body(equalTo("object in " + bucket));
        }
    }

    @Test
    void recoveryPointsAndProtectedResourcesAreListedByResource() {
        String vault = unique("by-resource");
        String resourceArn = "arn:aws:dynamodb:us-east-1:" + OUTSIDER + ":table/" + unique("table");
        createVault(OUTSIDER, vault);
        String first = backUp(OUTSIDER, vault, resourceArn, 5);
        String second = backUp(OUTSIDER, vault, resourceArn, 5);

        List<String> arns = given().header("Authorization", auth(OUTSIDER))
        .when().get("/resources/" + resourceArn + "/recovery-points/")
        .then().statusCode(200)
            .body("RecoveryPoints.BackupVaultName", everyItem(equalTo(vault)))
            .body("RecoveryPoints.BackupSizeBytes", everyItem(equalTo(0)))
            .extract().path("RecoveryPoints.RecoveryPointArn");
        assertEquals(2, arns.size());
        assertEquals(true, arns.containsAll(List.of(first, second)));

        String token = given().header("Authorization", auth(OUTSIDER)).queryParam("maxResults", 1)
        .when().get("/backup-vaults/" + vault + "/recovery-points/")
        .then().statusCode(200).body("RecoveryPoints", hasSize(1)).extract().path("NextToken");
        given().header("Authorization", auth(OUTSIDER)).queryParam("maxResults", 1).queryParam("nextToken", token)
        .when().get("/backup-vaults/" + vault + "/recovery-points/")
        .then().statusCode(200).body("RecoveryPoints", hasSize(1)).body("NextToken", nullValue());

        given().header("Authorization", auth(OUTSIDER))
        .when().get("/resources/")
        .then().statusCode(200)
            .body("Results.find { it.ResourceArn == '%s' }.LastBackupVaultArn".formatted(resourceArn),
                equalTo(vaultArn(OUTSIDER, vault)));

        given().header("Authorization", auth(OUTSIDER))
        .when().get("/resources/" + resourceArn)
        .then().statusCode(200)
            .body("ResourceArn", equalTo(resourceArn))
            .body("ResourceType", equalTo("DynamoDB"))
            .body("LastRecoveryPointArn", anyOf(equalTo(first), equalTo(second)));

        given().header("Authorization", auth(OUTSIDER))
        .when().get("/resources/arn:aws:dynamodb:us-east-1:" + OUTSIDER + ":table/never-backed-up")
        .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
    }
}
