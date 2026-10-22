package io.github.hectorvent.floci.services.backup;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.backup.model.*;
import io.github.hectorvent.floci.services.ram.RamService;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Pattern;

@ApplicationScoped
public class BackupService {

    private static final Logger LOG = Logger.getLogger(BackupService.class);

    private static final List<String> SUPPORTED_RESOURCE_TYPES = List.of(
            "S3", "RDS", "DynamoDB", "EFS", "EC2", "EBS",
            "Aurora", "DocumentDB", "Neptune", "FSx", "VirtualMachine"
    );

    static final String STANDARD_VAULT = "BACKUP_VAULT";
    static final String AIR_GAPPED_VAULT = "LOGICALLY_AIR_GAPPED_BACKUP_VAULT";
    private static final Set<String> VAULT_TYPES =
            Set.of(STANDARD_VAULT, AIR_GAPPED_VAULT, "RESTORE_ACCESS_BACKUP_VAULT");
    private static final Set<String> COPY_JOB_STATES = Set.of("CREATED", "RUNNING", "COMPLETED", "FAILED", "PARTIAL");
    private static final long AIR_GAPPED_MIN_RETENTION_FLOOR = 7;
    private static final String VAULT_RESOURCE_PREFIX = "backup-vault:";

    private final AccountAwareStorageBackend<BackupVault>   vaultStore;
    private final StorageBackend<String, BackupPlan>        planStore;
    private final StorageBackend<String, BackupSelection>   selectionStore;
    private final StorageBackend<String, BackupJob>         jobStore;
    private final AccountAwareStorageBackend<RecoveryPoint> recoveryStore;
    private final AccountAwareStorageBackend<String>        accessPolicyStore;
    private final StorageBackend<String, BackupVaultNotifications> notificationStore;
    private final StorageBackend<String, CopyJob>           copyJobStore;
    private final StorageBackend<String, String>            idempotencyStore;

    private final RamService ramService;
    private final RegionResolver regionResolver;
    private final int jobCompletionDelaySeconds;
    private final ObjectMapper objectMapper;

    // One monitor per vault key, so the check-then-write in createBackupVault and the
    // multi-store cleanup in deleteBackupVault are atomic for a given name. Same shape as
    // SecretsManagerService#lockFor and KinesisService's append locks.
    private final ConcurrentHashMap<String, Object> vaultLocks = new ConcurrentHashMap<>();

    private Object lockFor(String vaultKey) {
        return vaultLocks.computeIfAbsent(vaultKey, k -> new Object());
    }

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "backup-job-scheduler");
        t.setDaemon(true);
        return t;
    });

    @Inject
    public BackupService(StorageFactory storageFactory, EmulatorConfig config, RegionResolver regionResolver,
                         ObjectMapper objectMapper, RamService ramService) {
        this.vaultStore     = storageFactory.create("backup", "backup-vaults.json",     new TypeReference<>() {});
        this.planStore      = storageFactory.create("backup", "backup-plans.json",      new TypeReference<>() {});
        this.selectionStore = storageFactory.create("backup", "backup-selections.json", new TypeReference<>() {});
        this.jobStore       = storageFactory.create("backup", "backup-jobs.json",       new TypeReference<>() {});
        this.recoveryStore  = storageFactory.create("backup", "backup-recovery-points.json", new TypeReference<>() {});
        this.accessPolicyStore  = storageFactory.create("backup", "backup-vault-access-policies.json", new TypeReference<>() {});
        this.notificationStore  = storageFactory.create("backup", "backup-vault-notifications.json",   new TypeReference<>() {});
        this.copyJobStore       = storageFactory.create("backup", "backup-copy-jobs.json",             new TypeReference<>() {});
        this.idempotencyStore   = storageFactory.create("backup", "backup-idempotency-tokens.json",    new TypeReference<>() {});
        this.ramService = ramService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
        this.jobCompletionDelaySeconds = config.services().backup().jobCompletionDelaySeconds();
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    // ── Vault ──────────────────────────────────────────────────────────────────

    public BackupVault createBackupVault(String vaultName, String encryptionKeyArn,
                                         String creatorRequestId, Map<String, String> tags,
                                         String region) {
        String key = vaultKey(region, vaultName);
        // Check and write under one monitor. Unsynchronised, two creates of the same name can
        // both find the name free and both write, so AlreadyExistsException never fires and the
        // two vaults share a sub-resource key -- the second could then read configuration
        // applied to the first. AWS's CreateBackupVault is atomic for a name; this is what
        // makes it atomic here. Same per-key monitor pattern as SecretsManagerService#lockFor.
        //
        // No sub-resource sweep in here, deliberately. An earlier revision swept the two stores
        // at this point and that traded one race for a worse one: with two creates in flight the
        // later sweep could delete a configuration already applied against the earlier vault,
        // while both creates reported success. Deleting a configuration a caller had just
        // successfully applied is worse than the stale record the sweep was there to prevent.
        // Sub-resources are keyed per vault incarnation instead, so there is nothing to sweep.
        synchronized (lockFor(key)) {
            if (vaultStore.get(key).isPresent()) {
                throw new AwsException("AlreadyExistsException", "Backup vault already exists: " + vaultName, 400);
            }
            BackupVault vault = new BackupVault();
            vault.setBackupVaultName(vaultName);
            vault.setBackupVaultArn(regionResolver.buildArn("backup", region, VAULT_RESOURCE_PREFIX + vaultName));
            vault.setEncryptionKeyArn(encryptionKeyArn);
            vault.setCreationDate(Instant.now().getEpochSecond());
            vault.setCreatorRequestId(creatorRequestId);
            vault.setNumberOfRecoveryPoints(0);
            vault.setTags(tags);
            vaultStore.put(key, vault);
            LOG.infov("Created backup vault {0} in {1}", vaultName, region);
            return vault;
        }
    }

    public BackupVault createLogicallyAirGappedBackupVault(String vaultName, String encryptionKeyArn,
                                                           String creatorRequestId, Map<String, String> tags,
                                                           Long minRetentionDays, Long maxRetentionDays,
                                                           String region) {
        if (minRetentionDays == null) {
            throw new AwsException("MissingParameterValueException", "MinRetentionDays is required", 400);
        }
        if (maxRetentionDays == null) {
            throw new AwsException("MissingParameterValueException", "MaxRetentionDays is required", 400);
        }
        requireDayRange("MinRetentionDays", minRetentionDays, AIR_GAPPED_MIN_RETENTION_FLOOR, MAX_RETENTION_DAYS);
        requireDayRange("MaxRetentionDays", maxRetentionDays, AIR_GAPPED_MIN_RETENTION_FLOOR, MAX_RETENTION_DAYS);
        if (maxRetentionDays < minRetentionDays) {
            throw new AwsException("InvalidParameterValueException",
                    "MaxRetentionDays must be greater than or equal to MinRetentionDays", 400);
        }
        String key = vaultKey(region, vaultName);
        synchronized (lockFor(key)) {
            if (vaultStore.get(key).isPresent()) {
                throw new AwsException("AlreadyExistsException", "Backup vault already exists: " + vaultName, 400);
            }
            BackupVault vault = new BackupVault();
            vault.setBackupVaultName(vaultName);
            vault.setBackupVaultArn(regionResolver.buildArn("backup", region, VAULT_RESOURCE_PREFIX + vaultName));
            vault.setVaultType(AIR_GAPPED_VAULT);
            vault.setVaultState("CREATING");
            vault.setEncryptionKeyArn(encryptionKeyArn);
            vault.setEncryptionKeyType(encryptionKeyArn == null ? "AWS_OWNED_KMS_KEY" : "CUSTOMER_MANAGED_KMS_KEY");
            vault.setCreationDate(Instant.now().getEpochSecond());
            vault.setCreatorRequestId(creatorRequestId);
            vault.setNumberOfRecoveryPoints(0);
            vault.setTags(tags);
            vault.setLocked(true);
            vault.setMinRetentionDays(minRetentionDays);
            vault.setMaxRetentionDays(maxRetentionDays);
            vaultStore.put(key, vault);
            LOG.infov("Created logically air-gapped backup vault {0} in {1}", vaultName, region);
            return vault;
        }
    }

    public BackupVault describeBackupVault(String vaultName, String region) {
        String accountId = regionResolver.getAccountId();
        return vaultStore.get(vaultKey(region, vaultName))
                .map(vault -> settle(accountId, vault))
                .orElseThrow(() -> vaultNotFound(vaultName));
    }

    /** DescribeBackupVault with {@code BackupVaultAccountId}: another account's vault, if RAM shares it. */
    public BackupVault describeBackupVault(String vaultName, String vaultAccountId, String region) {
        return resolveVault(vaultName, vaultAccountId, region).vault();
    }

    private record VaultRef(String accountId, BackupVault vault) {}

    private VaultRef resolveVault(String vaultName, String vaultAccountId, String region) {
        String caller = regionResolver.getAccountId();
        if (vaultAccountId == null || vaultAccountId.isEmpty() || vaultAccountId.equals(caller)) {
            return new VaultRef(caller, describeBackupVault(vaultName, region));
        }
        BackupVault vault = vaultStore.getForAccount(vaultAccountId, vaultKey(region, vaultName))
                .filter(v -> isSharedWith(v, caller))
                .orElseThrow(() -> vaultNotFound(vaultName));
        return new VaultRef(vaultAccountId, settle(vaultAccountId, vault));
    }

    private boolean isSharedWith(BackupVault vault, String callerAccountId) {
        return AIR_GAPPED_VAULT.equals(vault.getVaultType())
                && ramService.isResourceSharedWith(vault.getBackupVaultArn(), callerAccountId);
    }

    private BackupVault settle(String accountId, BackupVault vault) {
        if ("CREATING".equals(vault.getVaultState())
                && Instant.now().getEpochSecond() > vault.getCreationDate()) {
            vault.setVaultState("AVAILABLE");
            vaultStore.putForAccount(accountId, vaultKey(vault), vault);
        }
        return vault;
    }

    private static AwsException vaultNotFound(String vaultName) {
        return new AwsException("ResourceNotFoundException", "Backup vault not found: " + vaultName, 404);
    }

    public void deleteBackupVault(String vaultName, String region) {
        // Under the same monitor as createBackupVault: the three deletes below must not
        // interleave with a create of this name, or the name could be recreated mid-cleanup.
        synchronized (lockFor(vaultKey(region, vaultName))) {
            deleteBackupVaultLocked(vaultName, region);
        }
    }

    private void deleteBackupVaultLocked(String vaultName, String region) {
        BackupVault vault = describeBackupVault(vaultName, region);
        if (vault.getNumberOfRecoveryPoints() > 0) {
            throw new AwsException("InvalidRequestException",
                    "Non-empty backup vault cannot be deleted: " + vaultName, 400);
        }
        // No lock check here, deliberately. A lock protects the RECOVERY POINTS, not the
        // vault shell: AWS's guide says the vault "can be deleted if it is empty and does
        // not contain any recovery points", even under a compliance lock. The non-empty
        // check above is already the case AWS refuses, so an added lock check would only
        // ever fire where AWS succeeds -- and it would break CloudFormation stack teardown,
        // which deletes vaults through BackupVaultCfnProvisioner and tolerates only
        // not-found.
        // Drop the sub-resources with the vault. They are keyed by vault name, so
        // leaving them behind would silently graft an old policy or notification
        // configuration onto the next vault created with the same name.
        //
        // Dependents first, the vault record last. There is no transaction across three
        // stores, so whatever sits between the first delete and the last is observable, and
        // this order keeps the observable state a vault with no configuration rather than a
        // configuration with no vault. The sub-resource keys carry this vault's creation date,
        // so these two deletes cannot reach a configuration belonging to a later vault that
        // has taken the same name -- which is what made the previous order lose data.
        accessPolicyStore.delete(subResourceKey(region, vault));
        notificationStore.delete(subResourceKey(region, vault));
        vaultStore.delete(vaultKey(region, vaultName));
    }

    /** {@code shared=true} lists the air-gapped vaults other accounts share with the caller through RAM. */
    public List<BackupVault> listBackupVaults(String region, String vaultType, Boolean shared) {
        if (vaultType != null && !VAULT_TYPES.contains(vaultType)) {
            throw new AwsException("InvalidParameterValueException", "Invalid vault type: " + vaultType, 400);
        }
        String caller = regionResolver.getAccountId();
        List<BackupVault> vaults;
        if (Boolean.TRUE.equals(shared)) {
            vaults = sharedVaults(caller, region);
        } else {
            String prefix = region + ":";
            vaults = vaultStore.scan(k -> k.startsWith(prefix)).stream()
                    .map(vault -> settle(caller, vault))
                    .toList();
        }
        return vaults.stream()
                .filter(vault -> vaultType == null || vaultType.equals(vault.getVaultType()))
                .toList();
    }

    private List<BackupVault> sharedVaults(String caller, String region) {
        List<BackupVault> vaults = new ArrayList<>();
        for (String arn : ramService.resourceArnsSharedWith(caller, region)) {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            if (!"backup".equals(parsed.service()) || !parsed.resource().startsWith(VAULT_RESOURCE_PREFIX)) {
                continue;
            }
            String name = parsed.resource().substring(VAULT_RESOURCE_PREFIX.length());
            vaultStore.getForAccount(parsed.accountId(), vaultKey(region, name))
                    .filter(vault -> arn.equals(vault.getBackupVaultArn()) && isSharedWith(vault, caller))
                    .map(vault -> settle(parsed.accountId(), vault))
                    .ifPresent(vaults::add);
        }
        return vaults;
    }

    // ── Vault sub-resources: access policy, notifications, lock ────────────────
    //
    // Each of these first resolves the vault through describeBackupVault, so a call
    // naming a vault that does not exist reports that, rather than reporting the
    // sub-resource as merely unconfigured. The two are different answers to different
    // questions and AWS distinguishes them; collapsing them would tell a caller its
    // typo'd vault name was fine.

    /**
     * Every BackupVaultEvent value the PutBackupVaultNotifications reference lists,
     * including the ones AWS marks deprecated -- they are still accepted values, and
     * rejecting one would fail an apply AWS allows.
     *
     * <p>All 30 of them. An earlier revision carried 17, which is the failure mode this
     * whole validation has to avoid: a list that rejects is only as good as it is
     * complete, and a stale one turns a valid configuration into a 400.
     */
    /**
     * The ceiling the PutBackupVaultLockConfiguration reference documents for two of the three
     * day values: MaxRetentionDays, "The longest maximum retention period you can specify is
     * 36500 days", and ChangeableForDays, "The maximum value you can specify is 36,500 days".
     * MinRetentionDays has no documented maximum and is deliberately not bounded by it.
     */
    private static final long MAX_RETENTION_DAYS = 36500;

    private static final Set<String> BACKUP_VAULT_EVENTS = Set.of(
            "BACKUP_JOB_STARTED", "BACKUP_JOB_COMPLETED", "BACKUP_JOB_SUCCESSFUL",
            "BACKUP_JOB_FAILED", "BACKUP_JOB_EXPIRED",
            "RESTORE_JOB_STARTED", "RESTORE_JOB_COMPLETED", "RESTORE_JOB_SUCCESSFUL",
            "RESTORE_JOB_FAILED",
            "COPY_JOB_STARTED", "COPY_JOB_SUCCESSFUL", "COPY_JOB_FAILED",
            "RECOVERY_POINT_MODIFIED",
            "BACKUP_PLAN_CREATED", "BACKUP_PLAN_MODIFIED",
            "S3_BACKUP_OBJECT_FAILED", "S3_RESTORE_OBJECT_FAILED",
            "CONTINUOUS_BACKUP_INTERRUPTED",
            "RECOVERY_POINT_INDEX_COMPLETED", "RECOVERY_POINT_INDEX_DELETED",
            "RECOVERY_POINT_INDEXING_FAILED",
            "EKS_RESTORE_OBJECT_FAILED", "EKS_RESTORE_OBJECT_SKIPPED",
            "EKS_BACKUP_OBJECT_FAILED",
            "ACCESS_POINT_AVAILABLE", "ACCESS_POINT_CREATION_FAILED",
            "ACCESS_POINT_DELETED", "ACCESS_POINT_DELETION_FAILED",
            "ACCESS_POINT_EXPIRED", "ACCESS_POINT_DISASSOCIATED");

    /**
     * The reference documents {@code Policy} as "Required: No", so a request that omits it is
     * not an error and must not be refused here. What AWS stores in that case is not documented
     * and we have not measured it; clearing is the choice taken, because it leaves the vault
     * carrying exactly the policy the request carried rather than one the caller did not send,
     * and it keeps the end state readable through GetBackupVaultAccessPolicy. An empty string is
     * a different case: it is present and invalid, so it is still rejected.
     */
    public void putBackupVaultAccessPolicy(String vaultName, String region, String policy) {
        BackupVault vault = describeBackupVault(vaultName, region);
        if (policy == null) {
            accessPolicyStore.delete(subResourceKey(region, vault));
            return;
        }
        if (policy.isBlank()) {
            throw new AwsException("InvalidParameterValueException",
                    "Policy must be a non-empty resource policy document", 400);
        }
        requireJsonObject(policy);
        accessPolicyStore.put(subResourceKey(region, vault), policy);
    }

    /**
     * A resource policy has to be a JSON object before anything else can be true of it.
     *
     * <p>Checking only for blankness accepted {@code not a policy}: PUT reported success and GET
     * returned the string back unchanged, so an unparseable policy looked applied. That is the
     * emulator being more permissive than AWS, which is the one direction that costs a user real
     * time -- the configuration passes locally and the deploy is where they find out.
     *
     * <p>Syntax only. Floci does not evaluate whether the document grants or denies anything, and
     * PutBackupVaultAccessPolicy does not list MalformedPolicyDocumentException among its errors,
     * so a bad document is reported as the invalid parameter value it is.
     */
    private void requireJsonObject(String policy) {
        try {
            if (!objectMapper.readTree(policy).isObject()) {
                throw new AwsException("InvalidParameterValueException",
                        "Policy must be a JSON object", 400);
            }
        } catch (JsonProcessingException e) {
            throw new AwsException("InvalidParameterValueException",
                    "Policy is not a valid JSON document: " + e.getOriginalMessage(), 400);
        }
    }

    public String getBackupVaultAccessPolicy(String vaultName, String region) {
        BackupVault vault = describeBackupVault(vaultName, region);
        return accessPolicyStore.get(subResourceKey(region, vault))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "No access policy found for backup vault: " + vaultName, 400));
    }

    /**
     * Deleting an access policy that is not set is a no-op, so Terraform destroying a
     * configuration twice does not fail the second time. That is Floci's choice and not a
     * measured AWS behaviour: the reference lists ResourceNotFoundException among this
     * operation's errors and does not say whether an unset policy raises it.
     */
    public void deleteBackupVaultAccessPolicy(String vaultName, String region) {
        BackupVault vault = describeBackupVault(vaultName, region);
        accessPolicyStore.delete(subResourceKey(region, vault));
    }

    public void putBackupVaultNotifications(String vaultName, String region,
                                            String snsTopicArn, List<String> events) {
        BackupVault vault = describeBackupVault(vaultName, region);
        // Absent and present-but-empty are different faults. PutBackupVaultNotifications lists
        // MissingParameterValueException, "Indicates that a required parameter is missing", in
        // its Errors section, which names the absent case. The reference does not name the
        // present-but-unusable case, so it keeps InvalidParameterValueException.
        if (snsTopicArn == null) {
            throw new AwsException("MissingParameterValueException",
                    "SNSTopicArn is required", 400);
        }
        if (snsTopicArn.isBlank()) {
            throw new AwsException("InvalidParameterValueException",
                    "SNSTopicArn must not be empty", 400);
        }
        if (events == null) {
            throw new AwsException("MissingParameterValueException",
                    "BackupVaultEvents is required", 400);
        }
        if (events.isEmpty()) {
            throw new AwsException("InvalidParameterValueException",
                    "BackupVaultEvents must name at least one event", 400);
        }
        // Reject unknown events rather than storing them. An emulator that accepts a
        // misspelt event and reports it back unchanged lets a configuration that real
        // AWS refuses pass a local test, which is the failure mode this corpus exists
        // to catch.
        List<String> unknown = events.stream().filter(e -> !BACKUP_VAULT_EVENTS.contains(e)).toList();
        if (!unknown.isEmpty()) {
            throw new AwsException("InvalidParameterValueException",
                    "Invalid backup vault event(s): " + String.join(", ", unknown), 400);
        }
        notificationStore.put(subResourceKey(region, vault),
                new BackupVaultNotifications(snsTopicArn, events));
    }

    public BackupVaultNotifications getBackupVaultNotifications(String vaultName, String region) {
        BackupVault vault = describeBackupVault(vaultName, region);
        return notificationStore.get(subResourceKey(region, vault))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "No notification configuration found for backup vault: " + vaultName, 400));
    }

    /** Idempotent, for the same reason as {@link #deleteBackupVaultAccessPolicy}. */
    public void deleteBackupVaultNotifications(String vaultName, String region) {
        BackupVault vault = describeBackupVault(vaultName, region);
        notificationStore.delete(subResourceKey(region, vault));
    }

    /**
     * Apply a Vault Lock.
     *
     * <p>AWS has two modes and {@code ChangeableForDays} is what selects them, in the
     * direction that reads backwards at first glance:
     *
     * <ul>
     *   <li><b>Absent → governance mode.</b> "If this parameter is not specified, you
     *       can delete Vault Lock from the vault using DeleteBackupVaultLockConfiguration
     *       or change the Vault Lock configuration using PutBackupVaultLockConfiguration
     *       at any time." No lock date is set and the lock never becomes immutable.</li>
     *   <li><b>Present → compliance mode.</b> The lock date is that many days out, and
     *       "before the lock date, you can delete Vault Lock ... On and after the lock
     *       date, the Vault Lock becomes immutable and cannot be changed or deleted."
     *       AWS enforces a 72-hour cooling-off period, hence the floor of 3.</li>
     * </ul>
     *
     * <p>An earlier revision of this method had the two the wrong way round, which made
     * AWS's freely removable governance lock permanent. Both quotations above are from
     * the PutBackupVaultLockConfiguration reference; the Vault Lock guide states the
     * mapping the same way ("If you wish to create a vault lock in governance mode, do
     * not include ChangeableForDays").
     */
    public BackupVault putBackupVaultLockConfiguration(String vaultName, String region,
                                                       Long minRetentionDays,
                                                       Long maxRetentionDays,
                                                       Long changeableForDays) {
        BackupVault vault = describeBackupVault(vaultName, region);
        requireStandardVault(vault, "its vault lock cannot be changed");
        if (vault.isLocked() && !lockIsStillChangeable(vault)) {
            throw new AwsException("InvalidRequestException",
                    "Backup vault lock is immutable and cannot be changed: " + vaultName, 400);
        }
        // "The shortest minimum retention period you can specify is 1 day." The reference states
        // no maximum for this field, so it gets none here: inventing a bound risks refusing a
        // value real AWS accepts, which is the divergence this work exists to remove, not create.
        requireDayRange("MinRetentionDays", minRetentionDays, 1, null);
        requireDayRange("MaxRetentionDays", maxRetentionDays, 1, MAX_RETENTION_DAYS);
        if (minRetentionDays != null && maxRetentionDays != null && maxRetentionDays < minRetentionDays) {
            throw new AwsException("InvalidParameterValueException",
                    "MaxRetentionDays must be greater than or equal to MinRetentionDays", 400);
        }
        // "You must set ChangeableForDays to 3 or greater": below it a governance lock would be
        // effectively immutable on creation, which is what compliance mode is for.
        requireDayRange("ChangeableForDays", changeableForDays, 3, MAX_RETENTION_DAYS);
        vault.setLocked(true);
        vault.setMinRetentionDays(minRetentionDays);
        vault.setMaxRetentionDays(maxRetentionDays);
        vault.setLockDate(changeableForDays == null ? null
                : Instant.now().plus(changeableForDays, ChronoUnit.DAYS).getEpochSecond());
        vaultStore.put(vaultKey(region, vaultName), vault);
        LOG.infov("Locked backup vault {0} in {1} ({2})", vaultName, region,
                changeableForDays == null
                        ? "governance mode, changeable at any time"
                        : "compliance mode, changeable for " + changeableForDays + " day(s)");
        return vault;
    }

    /**
     * Bound one Vault Lock day value. A null {@code ceiling} means the reference documents no
     * maximum for that field, and the value is only floored.
     *
     * <p>Where a ceiling is documented, two things go wrong without it, and the second is the
     * worse one. A value like 40,000 is accepted here and refused by AWS, which is the shape this
     * whole corpus exists to catch: a Terraform configuration that applies locally and fails for
     * real.
     *
     * <p>And {@code Long.MAX_VALUE} is a perfectly good long, so it survives the integral check in
     * the controller and reaches {@code Instant.plus}, which throws {@code ArithmeticException:
     * long overflow}. The only exception mapper in the tree handles {@link AwsException}, so that
     * escapes as an HTTP 500 rather than the documented 400. Bounding ChangeableForDays, the one
     * value that reaches {@code Instant.plus}, is what stops the overflow being reachable at all,
     * rather than catching it after the fact.
     */
    private static void requireDayRange(String field, Long value, long floor, Long ceiling) {
        if (value == null) {
            return;
        }
        if (value < floor || (ceiling != null && value > ceiling)) {
            throw new AwsException("InvalidParameterValueException",
                    ceiling == null
                            ? field + " must be " + floor + " or greater"
                            : field + " must be between " + floor + " and " + ceiling, 400);
        }
    }

    public void deleteBackupVaultLockConfiguration(String vaultName, String region) {
        BackupVault vault = describeBackupVault(vaultName, region);
        requireStandardVault(vault, "its vault lock cannot be deleted");
        if (vault.isLocked() && !lockIsStillChangeable(vault)) {
            throw new AwsException("InvalidRequestException",
                    "Backup vault lock is immutable and cannot be deleted: " + vaultName, 400);
        }
        vault.setLocked(false);
        vault.setLockDate(null);
        vault.setMinRetentionDays(null);
        vault.setMaxRetentionDays(null);
        vaultStore.put(vaultKey(region, vaultName), vault);
    }

    /**
     * Refuse a backup whose retention the vault's lock does not permit.
     *
     * <p>This is the other half of what a Vault Lock does, and the half that acts at write time:
     * "Backup jobs will fail if the lifecycle policy of the backup plan is outside the vault lock's
     * retention period." MinRetentionDays and MaxRetentionDays bound {@code DeleteAfterDays}, so a
     * plan retaining for 7 days cannot write into a vault whose lock demands 30.
     *
     * <p>Until this existed, {@code getMaxRetentionDays} had no reader anywhere in the tree: the
     * ceiling was accepted, stored, and reported by DescribeBackupVault while bounding nothing. A
     * Terraform configuration pairing a lock with a shorter plan lifecycle therefore applied
     * cleanly here and failed its first backup on real AWS, which is precisely the divergence this
     * emulator exists to remove rather than introduce.
     *
     * <p><b>An absent lifecycle is checked against the MAXIMUM, not waved through.</b> The first
     * version of this returned early whenever DeleteAfterDays was missing, on the reasoning that
     * indefinite retention cannot exceed a maximum. That is backwards, and it left the ceiling
     * bypassable by simply omitting the member: retaining forever is the LARGEST retention there
     * is, so it exceeds every finite maximum, and the recovery point outlives the limit the lock
     * was created to impose. A minimum is the other way round -- indefinite satisfies any floor --
     * so an absent lifecycle is refused only when a maximum is set.
     *
     * <p>That last point is Floci's reading rather than a quotation. The reference states the
     * rule for a lifecycle that falls outside the window and does not spell out the absent case;
     * the reading is forced by what a maximum retention period means, and it errs toward refusing
     * a request rather than storing a recovery point the lock forbids.
     */
    private static void requireLifecycleWithinLock(BackupVault vault, Lifecycle lifecycle) {
        if (!vault.isLocked()) {
            return;
        }
        Long min = vault.getMinRetentionDays();
        Long max = vault.getMaxRetentionDays();
        Long deleteAfterDays = lifecycle == null ? null : lifecycle.getDeleteAfterDays();
        if (deleteAfterDays == null) {
            if (max != null) {
                throw new AwsException("InvalidParameterValueException",
                        "Lifecycle DeleteAfterDays is required for a vault locked with a maximum "
                                + "retention of " + max + " day(s): retaining indefinitely would "
                                + "exceed it", 400);
            }
            return;
        }
        long deleteAfter = deleteAfterDays;
        if (min != null && deleteAfter < min) {
            throw new AwsException("InvalidParameterValueException",
                    "Lifecycle DeleteAfterDays of " + deleteAfter + " is below the vault lock's "
                            + "minimum retention of " + min + " day(s)", 400);
        }
        if (max != null && deleteAfter > max) {
            throw new AwsException("InvalidParameterValueException",
                    "Lifecycle DeleteAfterDays of " + deleteAfter + " exceeds the vault lock's "
                            + "maximum retention of " + max + " day(s)", 400);
        }
    }

    private static void requireStandardVault(BackupVault vault, String consequence) {
        if (AIR_GAPPED_VAULT.equals(vault.getVaultType())) {
            throw new AwsException("InvalidRequestException", "Backup vault " + vault.getBackupVaultName()
                    + " is logically air-gapped, so " + consequence, 400);
        }
    }

    /**
     * True while a lock can still be changed or removed.
     *
     * <p>A governance lock carries no LockDate and is always changeable. A compliance
     * lock is changeable only before its LockDate. Absent means governance, so a null
     * LockDate must answer TRUE here -- the inverse of this is the defect that made a
     * governance lock permanent.
     */
    private static boolean lockIsStillChangeable(BackupVault vault) {
        Long lockDate = vault.getLockDate();
        return lockDate == null || Instant.now().getEpochSecond() < lockDate;
    }

    // ── Plan ───────────────────────────────────────────────────────────────────

    public BackupPlan createBackupPlan(String planName, List<BackupRule> rules,
                                       String creatorRequestId, String region) {
        String planId = UUID.randomUUID().toString();
        BackupPlan plan = new BackupPlan();
        plan.setBackupPlanId(planId);
        plan.setBackupPlanArn(regionResolver.buildArn("backup", region, "backup-plan:" + planId));
        plan.setBackupPlanName(planName);
        plan.setCreationDate(Instant.now().getEpochSecond());
        plan.setVersionId(shortId());
        assignRuleIds(rules);
        plan.setRules(rules);
        planStore.put(planId, plan);
        return plan;
    }

    public BackupPlan getBackupPlan(String planId) {
        return planStore.get(planId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Backup plan not found: " + planId, 404));
    }

    public BackupPlan updateBackupPlan(String planId, String planName, List<BackupRule> rules) {
        BackupPlan plan = getBackupPlan(planId);
        if (planName != null) {
            plan.setBackupPlanName(planName);
        }
        assignRuleIds(rules);
        plan.setRules(rules);
        plan.setVersionId(shortId());
        planStore.put(planId, plan);
        return plan;
    }

    public void deleteBackupPlan(String planId) {
        getBackupPlan(planId);
        long selectionCount = selectionStore.scan(k -> true).stream()
                .filter(s -> planId.equals(s.getBackupPlanId()))
                .count();
        if (selectionCount > 0) {
            throw new AwsException("InvalidRequestException",
                    "Backup plan has active selections and cannot be deleted", 400);
        }
        planStore.delete(planId);
    }

    public List<BackupPlan> listBackupPlans() {
        return planStore.scan(k -> true);
    }

    // ── Selection ──────────────────────────────────────────────────────────────

    public BackupSelection createBackupSelection(String planId, String selectionName,
                                                  String iamRoleArn, List<String> resources,
                                                  List<String> notResources, String creatorRequestId) {
        getBackupPlan(planId);
        String selectionId = UUID.randomUUID().toString();
        BackupSelection selection = new BackupSelection();
        selection.setSelectionId(selectionId);
        selection.setSelectionName(selectionName);
        selection.setBackupPlanId(planId);
        selection.setIamRoleArn(iamRoleArn);
        selection.setResources(resources);
        selection.setNotResources(notResources);
        selection.setCreationDate(Instant.now().getEpochSecond());
        selection.setCreatorRequestId(creatorRequestId);
        selectionStore.put(selectionId, selection);
        return selection;
    }

    public BackupSelection getBackupSelection(String planId, String selectionId) {
        BackupSelection sel = selectionStore.get(selectionId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Backup selection not found: " + selectionId, 404));
        if (!planId.equals(sel.getBackupPlanId())) {
            throw new AwsException("ResourceNotFoundException", "Backup selection not found in plan: " + planId, 404);
        }
        return sel;
    }

    public void deleteBackupSelection(String planId, String selectionId) {
        getBackupSelection(planId, selectionId);
        selectionStore.delete(selectionId);
    }

    public List<BackupSelection> listBackupSelections(String planId) {
        return selectionStore.scan(k -> true).stream()
                .filter(s -> planId.equals(s.getBackupPlanId()))
                .toList();
    }

    // ── Job ────────────────────────────────────────────────────────────────────

    public BackupJob startBackupJob(String vaultName, String resourceArn, String iamRoleArn,
                                     Lifecycle lifecycle, String region) {
        BackupVault vault = describeBackupVault(vaultName, region);
        if (AIR_GAPPED_VAULT.equals(vault.getVaultType())) {
            throw new AwsException("InvalidParameterValueException", "Backup vault " + vaultName
                    + " is logically air-gapped: back up to a backup vault and copy the recovery point into it", 400);
        }
        requireLifecycleWithinLock(vault, lifecycle);

        String jobId = UUID.randomUUID().toString();
        long now = Instant.now().getEpochSecond();

        BackupJob job = new BackupJob();
        job.setBackupJobId(jobId);
        job.setBackupVaultName(vaultName);
        job.setBackupVaultArn(vault.getBackupVaultArn());
        job.setResourceArn(resourceArn);
        job.setResourceType(inferResourceType(resourceArn));
        job.setResourceName(inferResourceName(resourceArn));
        job.setRecoveryPointLifecycle(lifecycle);
        job.setIamRoleArn(iamRoleArn);
        job.setState("CREATED");
        job.setPercentDone("0.0");
        job.setCreationDate(now);
        job.setExpectedCompletionDate(now + jobCompletionDelaySeconds);
        job.setStartBy(now + 3600L);
        String accountId = regionResolver.getAccountId();
        job.setAccountId(accountId);
        jobStore.put(jobId, job);

        // The scheduler thread has no request context; run in the caller's account
        // so the account-aware stores and buildArn resolve the right partition.
        scheduler.schedule(() -> RequestScopes.runAs(accountId,
                () -> transitionJob(jobId, vaultName, region)), 1, TimeUnit.SECONDS);
        scheduler.schedule(() -> RequestScopes.runAs(accountId,
                () -> completeJob(jobId, vaultName, region)), jobCompletionDelaySeconds, TimeUnit.SECONDS);

        return job;
    }

    public BackupJob describeBackupJob(String jobId) {
        return jobStore.get(jobId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Backup job not found: " + jobId, 404));
    }

    public void stopBackupJob(String jobId) {
        BackupJob job = describeBackupJob(jobId);
        String state = job.getState();
        if ("COMPLETED".equals(state) || "ABORTED".equals(state) || "FAILED".equals(state)) {
            throw new AwsException("InvalidRequestException",
                    "Job cannot be stopped in state: " + state, 400);
        }
        job.setState("ABORTING");
        job.setStatusMessage("Job stop requested");
        jobStore.put(jobId, job);
        String accountId = regionResolver.getAccountId();
        scheduler.schedule(() -> RequestScopes.runAs(accountId, () -> abortJob(jobId)), 1, TimeUnit.SECONDS);
    }

    public List<BackupJob> listBackupJobs(String byVaultName, String byState,
                                           String byResourceArn, String byResourceType) {
        return jobStore.scan(k -> true).stream()
                .filter(j -> byVaultName == null || byVaultName.equals(j.getBackupVaultName()))
                .filter(j -> byState == null || byState.equals(j.getState()))
                .filter(j -> byResourceArn == null || byResourceArn.equals(j.getResourceArn()))
                .filter(j -> byResourceType == null || byResourceType.equals(j.getResourceType()))
                .toList();
    }

    // ── Recovery Point ─────────────────────────────────────────────────────────

    public RecoveryPoint describeRecoveryPoint(String vaultName, String recoveryPointArn, String region) {
        return describeRecoveryPoint(vaultName, null, recoveryPointArn, region);
    }

    public RecoveryPoint describeRecoveryPoint(String vaultName, String vaultAccountId,
                                               String recoveryPointArn, String region) {
        VaultRef ref = resolveVault(vaultName, vaultAccountId, region);
        return recoveryStore.getForAccount(ref.accountId(), recoveryPointArn)
                .filter(rp -> ref.vault().getBackupVaultArn().equals(rp.getBackupVaultArn()))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Recovery point not found: " + recoveryPointArn, 404));
    }

    public List<RecoveryPoint> listRecoveryPointsByBackupVault(String vaultName, String vaultAccountId,
                                                               String region, String byResourceArn,
                                                               String byResourceType, String byBackupPlanId,
                                                               Long createdBefore, Long createdAfter) {
        VaultRef ref = resolveVault(vaultName, vaultAccountId, region);
        String vaultArn = ref.vault().getBackupVaultArn();
        return recoveryStore.scanForAccount(ref.accountId(), k -> true).stream()
                .filter(rp -> vaultArn.equals(rp.getBackupVaultArn()))
                .filter(rp -> byResourceArn == null || byResourceArn.equals(rp.getResourceArn()))
                .filter(rp -> byResourceType == null || byResourceType.equals(rp.getResourceType()))
                // No recovery point here is created by a backup plan, so a plan filter matches none.
                .filter(rp -> byBackupPlanId == null)
                .filter(rp -> createdBefore == null || rp.getCreationDate() < createdBefore)
                .filter(rp -> createdAfter == null || rp.getCreationDate() > createdAfter)
                .toList();
    }

    public List<RecoveryPoint> listRecoveryPointsByResource(String resourceArn, String region) {
        return recoveryPointsInRegion(region).stream()
                .filter(rp -> resourceArn.equals(rp.getResourceArn()))
                .toList();
    }

    public List<RecoveryPoint> listProtectedResources(String region) {
        Map<String, RecoveryPoint> latest = new HashMap<>();
        for (RecoveryPoint rp : recoveryPointsInRegion(region)) {
            if (rp.getResourceArn() != null) {
                latest.merge(rp.getResourceArn(), rp,
                        (a, b) -> b.getCreationDate() > a.getCreationDate() ? b : a);
            }
        }
        return List.copyOf(latest.values());
    }

    public RecoveryPoint describeProtectedResource(String resourceArn, String region) {
        return listRecoveryPointsByResource(resourceArn, region).stream()
                .max(Comparator.comparingLong(RecoveryPoint::getCreationDate))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "No recovery point found for resource: " + resourceArn, 404));
    }

    private List<RecoveryPoint> recoveryPointsInRegion(String region) {
        return recoveryStore.scan(k -> true).stream()
                .filter(rp -> region.equals(AwsArnUtils.parse(rp.getRecoveryPointArn()).region()))
                .toList();
    }

    /**
     * Delete a recovery point, unless a Vault Lock still protects it.
     *
     * <p>This is what the lock is FOR. "Vault Lock ... prevents the deletion of recovery points
     * before their retention periods expire", and the minimum retention period is the floor: a
     * recovery point younger than MinRetentionDays cannot be deleted while the vault is locked.
     * Without this check the lock was decorative -- it governed only its own mutation, so
     * PutBackupVaultLockConfiguration succeeded, DescribeBackupVault reported Locked, and every
     * recovery point the lock was supposed to protect deleted exactly as before. An empty vault
     * then deletes too, so the whole protection came apart from the one operation it names.
     *
     * <p>Both lock modes enforce retention. Governance mode differs on real AWS in that a
     * principal holding {@code backup:DisableGovernanceRetention} can override it; Floci does not
     * model that permission, so governance mode enforces here as compliance mode does. That is
     * stricter than AWS for an unusually-privileged caller and identical for every other one,
     * which is the safer direction: a local test that deletes a protected recovery point and
     * passes would be describing AWS behaviour that needs a specific permission to reproduce.
     */
    public void deleteRecoveryPoint(String vaultName, String recoveryPointArn, String region) {
        BackupVault vault = describeBackupVault(vaultName, region);
        RecoveryPoint rp = describeRecoveryPoint(vaultName, recoveryPointArn, region);
        if (vault.isLocked() && vault.getMinRetentionDays() != null) {
            long ageDays = ChronoUnit.DAYS.between(
                    Instant.ofEpochSecond(rp.getCreationDate()), Instant.now());
            if (ageDays < vault.getMinRetentionDays()) {
                throw new AwsException("InvalidRequestException",
                        "Recovery point is protected by a vault lock until its minimum retention of "
                                + vault.getMinRetentionDays() + " day(s) has elapsed: " + recoveryPointArn,
                        400);
            }
        }
        recoveryStore.delete(recoveryPointArn);
        decrementVaultCount(vaultName, region);
    }

    // ── Copy job ───────────────────────────────────────────────────────────────

    /** Destination problems fail the job, not the request: the caller may not see that vault. */
    public CopyJob startCopyJob(String recoveryPointArn, String sourceVaultName, String destinationVaultArn,
                                String iamRoleArn, Lifecycle lifecycle, String idempotencyToken, String region) {
        requireParameter("RecoveryPointArn", recoveryPointArn);
        requireParameter("SourceBackupVaultName", sourceVaultName);
        requireParameter("DestinationBackupVaultArn", destinationVaultArn);
        requireParameter("IamRoleArn", iamRoleArn);
        parseVaultArn(destinationVaultArn);
        String tokenKey = idempotencyToken == null ? null : "copy:" + idempotencyToken;
        if (tokenKey != null) {
            Optional<CopyJob> existing = idempotencyStore.get(tokenKey).flatMap(copyJobStore::get);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        BackupVault source = describeBackupVault(sourceVaultName, region);
        RecoveryPoint rp = recoveryStore.get(recoveryPointArn)
                .filter(p -> source.getBackupVaultArn().equals(p.getBackupVaultArn()))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Recovery point not found: " + recoveryPointArn, 404));
        if (!"COMPLETED".equals(rp.getStatus())) {
            throw new AwsException("InvalidRequestException",
                    "Recovery point " + recoveryPointArn + " is " + rp.getStatus() + ", not COMPLETED", 400);
        }

        String accountId = regionResolver.getAccountId();
        CopyJob job = new CopyJob();
        job.setCopyJobId(UUID.randomUUID().toString());
        job.setAccountId(accountId);
        job.setSourceBackupVaultArn(source.getBackupVaultArn());
        job.setSourceRecoveryPointArn(recoveryPointArn);
        job.setDestinationBackupVaultArn(destinationVaultArn);
        job.setDestinationRecoveryPointLifecycle(lifecycle);
        job.setResourceArn(rp.getResourceArn());
        job.setResourceType(rp.getResourceType());
        job.setResourceName(rp.getResourceName());
        job.setBackupSizeInBytes(rp.getBackupSizeInBytes());
        job.setIamRoleArn(iamRoleArn);
        job.setCreationDate(Instant.now().getEpochSecond());
        job.setState("CREATED");
        String jobId = job.getCopyJobId();
        copyJobStore.put(jobId, job);
        if (tokenKey != null) {
            idempotencyStore.put(tokenKey, jobId);
        }

        scheduler.schedule(() -> RequestScopes.runAs(accountId, () -> {
            copyJobStore.get(jobId).filter(j -> "CREATED".equals(j.getState())).ifPresent(j -> {
                j.setState("RUNNING");
                copyJobStore.put(jobId, j);
            });
        }), 1, TimeUnit.SECONDS);
        scheduler.schedule(() -> RequestScopes.runAs(accountId, () -> completeCopyJob(jobId)),
                jobCompletionDelaySeconds, TimeUnit.SECONDS);
        return job;
    }

    public CopyJob describeCopyJob(String copyJobId) {
        return copyJobStore.get(copyJobId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Copy job not found: " + copyJobId, 404));
    }

    public List<CopyJob> listCopyJobs(String byResourceArn, String byState, String byResourceType,
                                      String byDestinationVaultArn, String byAccountId,
                                      String bySourceRecoveryPointArn, Long createdBefore, Long createdAfter) {
        if (byState != null && !COPY_JOB_STATES.contains(byState)) {
            throw new AwsException("InvalidParameterValueException", "Invalid copy job state: " + byState, 400);
        }
        return copyJobStore.scan(k -> true).stream()
                .filter(j -> byResourceArn == null || byResourceArn.equals(j.getResourceArn()))
                .filter(j -> byState == null || byState.equals(j.getState()))
                .filter(j -> byResourceType == null || byResourceType.equals(j.getResourceType()))
                .filter(j -> byDestinationVaultArn == null || byDestinationVaultArn.equals(j.getDestinationBackupVaultArn()))
                .filter(j -> byAccountId == null || byAccountId.equals(j.getAccountId()))
                .filter(j -> bySourceRecoveryPointArn == null || bySourceRecoveryPointArn.equals(j.getSourceRecoveryPointArn()))
                .filter(j -> createdBefore == null || j.getCreationDate() < createdBefore)
                .filter(j -> createdAfter == null || j.getCreationDate() > createdAfter)
                .toList();
    }

    private void completeCopyJob(String jobId) {
        copyJobStore.get(jobId).filter(j -> "RUNNING".equals(j.getState())).ifPresent(job -> {
            try {
                copyRecoveryPoint(job);
                job.setState("COMPLETED");
            } catch (AwsException e) {
                job.setState("FAILED");
                job.setStatusMessage(e.getMessage());
            }
            job.setCompletionDate(Instant.now().getEpochSecond());
            copyJobStore.put(jobId, job);
            LOG.infov("Copy job {0} {1}", jobId, job.getState());
        });
    }

    private void copyRecoveryPoint(CopyJob job) {
        RecoveryPoint source = recoveryStore.get(job.getSourceRecoveryPointArn())
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Source recovery point no longer exists: " + job.getSourceRecoveryPointArn(), 404));
        AwsArnUtils.Arn dest = parseVaultArn(job.getDestinationBackupVaultArn());
        String destName = dest.resource().substring(VAULT_RESOURCE_PREFIX.length());
        BackupVault destVault = vaultStore.getForAccount(dest.accountId(), vaultKey(dest.region(), destName))
                .map(vault -> settle(dest.accountId(), vault))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Destination backup vault not found: " + job.getDestinationBackupVaultArn(), 404));
        job.setDestinationVaultType(destVault.getVaultType());
        job.setDestinationEncryptionKeyArn(destVault.getEncryptionKeyArn());
        if ("CREATING".equals(destVault.getVaultState())) {
            throw new AwsException("InvalidRequestException", "Destination backup vault is still being created", 400);
        }
        if (!dest.accountId().equals(job.getAccountId())) {
            requireCopyIntoVaultAllowed(dest, destVault, job.getAccountId());
        }
        Lifecycle lifecycle = job.getDestinationRecoveryPointLifecycle();
        requireLifecycleWithinLock(destVault, lifecycle);

        String rpArn = regionResolver.buildArn("backup", dest.region(), dest.accountId(),
                "recovery-point:" + UUID.randomUUID());
        RecoveryPoint copy = new RecoveryPoint();
        copy.setRecoveryPointArn(rpArn);
        copy.setBackupVaultName(destName);
        copy.setBackupVaultArn(destVault.getBackupVaultArn());
        copy.setSourceBackupVaultArn(job.getSourceBackupVaultArn());
        copy.setVaultType(destVault.getVaultType());
        copy.setResourceArn(source.getResourceArn());
        copy.setResourceType(source.getResourceType());
        copy.setResourceName(source.getResourceName());
        copy.setIamRoleArn(job.getIamRoleArn());
        copy.setStatus("COMPLETED");
        copy.setCreationDate(source.getCreationDate());
        copy.setCompletionDate(Instant.now().getEpochSecond());
        copy.setBackupSizeInBytes(source.getBackupSizeInBytes());
        copy.setLifecycle(lifecycle);
        copy.setCalculatedLifecycle(calculatedLifecycle(source.getCreationDate(), lifecycle));
        copy.setStorageClass("WARM");
        copy.setEncryptionKeyArn(destVault.getEncryptionKeyArn());
        copy.setEncryptionKeyType(destVault.getEncryptionKeyType());
        copy.setEncrypted(AIR_GAPPED_VAULT.equals(destVault.getVaultType()) || destVault.getEncryptionKeyArn() != null);
        recoveryStore.putForAccount(dest.accountId(), rpArn, copy);
        adjustVaultCount(dest.accountId(), dest.region(), destName, 1);
        job.setDestinationRecoveryPointArn(rpArn);
    }

    /** Policy conditions are not evaluated. */
    private void requireCopyIntoVaultAllowed(AwsArnUtils.Arn dest, BackupVault destVault, String sourceAccountId) {
        String policy = accessPolicyStore.getForAccount(dest.accountId(), subResourceKey(dest.region(), destVault))
                .orElse(null);
        if (policy == null || !policyAllows(policy, "backup:CopyIntoBackupVault", sourceAccountId)) {
            throw new AwsException("AccessDeniedException", "The access policy of backup vault "
                    + destVault.getBackupVaultArn() + " does not allow backup:CopyIntoBackupVault from account "
                    + sourceAccountId, 403);
        }
    }

    private boolean policyAllows(String policy, String action, String accountId) {
        JsonNode statements;
        try {
            statements = objectMapper.readTree(policy).path("Statement");
        } catch (JsonProcessingException e) {
            return false;
        }
        boolean allowed = false;
        for (JsonNode statement : statements.isArray() ? statements : List.of(statements)) {
            if (!matchesAny(statement.path("Action"), value -> actionMatches(value, action))
                    || !principalMatches(statement.path("Principal"), accountId)) {
                continue;
            }
            if ("Deny".equals(statement.path("Effect").asText())) {
                return false;
            }
            allowed |= "Allow".equals(statement.path("Effect").asText());
        }
        return allowed;
    }

    private static boolean actionMatches(String pattern, String action) {
        String regex = Pattern.quote(pattern.toLowerCase(Locale.ROOT)).replace("*", "\\E.*\\Q");
        return action.toLowerCase(Locale.ROOT).matches(regex);
    }

    private static boolean principalMatches(JsonNode principal, String accountId) {
        if (principal.isTextual()) {
            return "*".equals(principal.asText());
        }
        return matchesAny(principal.path("AWS"), value -> "*".equals(value) || accountId.equals(value)
                || (AwsArnUtils.isArnFor(value, "iam") && accountId.equals(AwsArnUtils.parse(value).accountId())));
    }

    private static boolean matchesAny(JsonNode node, Predicate<String> test) {
        if (node.isTextual()) {
            return test.test(node.asText());
        }
        for (JsonNode element : node) {
            if (element.isTextual() && test.test(element.asText())) {
                return true;
            }
        }
        return false;
    }

    private static AwsArnUtils.Arn parseVaultArn(String vaultArn) {
        String resource = AwsArnUtils.resourceIfArnFor(vaultArn, "backup").orElse("");
        if (!resource.startsWith(VAULT_RESOURCE_PREFIX) || resource.length() == VAULT_RESOURCE_PREFIX.length()) {
            throw new AwsException("InvalidParameterValueException", "Invalid backup vault ARN: " + vaultArn, 400);
        }
        return AwsArnUtils.parse(vaultArn);
    }

    private static void requireParameter(String name, String value) {
        if (value == null || value.isEmpty()) {
            throw new AwsException("MissingParameterValueException", name + " is required", 400);
        }
    }

    static CalculatedLifecycle calculatedLifecycle(long creationDate, Lifecycle lifecycle) {
        if (lifecycle == null
                || (lifecycle.getDeleteAfterDays() == null && lifecycle.getMoveToColdStorageAfterDays() == null)) {
            return null;
        }
        return new CalculatedLifecycle(
                plusDays(creationDate, lifecycle.getMoveToColdStorageAfterDays()),
                plusDays(creationDate, lifecycle.getDeleteAfterDays()));
    }

    private static Long plusDays(long epochSecond, Long days) {
        return days == null ? null : Instant.ofEpochSecond(epochSecond).plus(days, ChronoUnit.DAYS).getEpochSecond();
    }

    // ── Tags ───────────────────────────────────────────────────────────────────

    public Map<String, String> listTags(String resourceArn) {
        return findTagsByArn(resourceArn);
    }

    public void tagResource(String resourceArn, Map<String, String> tags) {
        applyTags(resourceArn, tags);
    }

    public void untagResource(String resourceArn, List<String> tagKeys) {
        removeTags(resourceArn, tagKeys);
    }

    // ── Supported resource types ───────────────────────────────────────────────

    public List<String> getSupportedResourceTypes() {
        return SUPPORTED_RESOURCE_TYPES;
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private void transitionJob(String jobId, String vaultName, String region) {
        jobStore.get(jobId).ifPresent(job -> {
            if ("CREATED".equals(job.getState())) {
                job.setState("RUNNING");
                job.setPercentDone("50.0");
                jobStore.put(jobId, job);
            }
        });
    }

    private void completeJob(String jobId, String vaultName, String region) {
        jobStore.get(jobId).ifPresent(job -> {
            if ("RUNNING".equals(job.getState())) {
                long now = Instant.now().getEpochSecond();
                String rpArn = regionResolver.buildArn("backup", region,
                        "recovery-point:" + UUID.randomUUID());

                job.setState("COMPLETED");
                job.setPercentDone("100.0");
                job.setCompletionDate(now);
                job.setRecoveryPointArn(rpArn);
                job.setBackupSizeInBytes(0L);
                job.setBytesTransferred(0L);
                jobStore.put(jobId, job);

                RecoveryPoint rp = new RecoveryPoint();
                rp.setRecoveryPointArn(rpArn);
                rp.setBackupVaultName(vaultName);
                rp.setBackupVaultArn(job.getBackupVaultArn());
                rp.setResourceArn(job.getResourceArn());
                rp.setResourceType(job.getResourceType());
                rp.setResourceName(job.getResourceName());
                rp.setVaultType(STANDARD_VAULT);
                rp.setIamRoleArn(job.getIamRoleArn());
                rp.setStatus("COMPLETED");
                rp.setCreationDate(job.getCreationDate());
                rp.setCompletionDate(now);
                rp.setBackupSizeInBytes(0L);
                rp.setLifecycle(job.getRecoveryPointLifecycle());
                rp.setCalculatedLifecycle(calculatedLifecycle(job.getCreationDate(), job.getRecoveryPointLifecycle()));
                rp.setStorageClass("WARM");
                rp.setEncrypted(false);
                recoveryStore.put(rpArn, rp);

                incrementVaultCount(vaultName, region);
                LOG.infov("Backup job {0} completed, recovery point: {1}", jobId, rpArn);
            }
        });
    }

    private void abortJob(String jobId) {
        jobStore.get(jobId).ifPresent(job -> {
            if ("ABORTING".equals(job.getState())) {
                job.setState("ABORTED");
                job.setCompletionDate(Instant.now().getEpochSecond());
                jobStore.put(jobId, job);
            }
        });
    }

    private void incrementVaultCount(String vaultName, String region) {
        adjustVaultCount(regionResolver.getAccountId(), region, vaultName, 1);
    }

    private void decrementVaultCount(String vaultName, String region) {
        adjustVaultCount(regionResolver.getAccountId(), region, vaultName, -1);
    }

    private void adjustVaultCount(String accountId, String region, String vaultName, int delta) {
        String key = vaultKey(region, vaultName);
        synchronized (lockFor(key)) {
            vaultStore.getForAccount(accountId, key).ifPresent(vault -> {
                vault.setNumberOfRecoveryPoints(Math.max(0, vault.getNumberOfRecoveryPoints() + delta));
                vaultStore.putForAccount(accountId, key, vault);
            });
        }
    }

    private Map<String, String> findTagsByArn(String arn) {
        Optional<BackupVault> vault = vaultStore.scan(k -> true).stream()
                .filter(v -> arn.equals(v.getBackupVaultArn()))
                .findFirst();
        if (vault.isPresent()) {
            return vault.get().getTags();
        }
        Optional<BackupPlan> plan = planStore.scan(k -> true).stream()
                .filter(p -> arn.equals(p.getBackupPlanArn()))
                .findFirst();
        if (plan.isPresent()) {
            return new HashMap<>();
        }
        throw new AwsException("ResourceNotFoundException", "Resource not found: " + arn, 404);
    }

    private void applyTags(String arn, Map<String, String> newTags) {
        Optional<BackupVault> vaultOpt = vaultStore.scan(k -> true).stream()
                .filter(v -> arn.equals(v.getBackupVaultArn()))
                .findFirst();
        if (vaultOpt.isPresent()) {
            BackupVault vault = vaultOpt.get();
            vault.getTags().putAll(newTags);
            vaultStore.put(vaultKey(vault), vault);
            return;
        }
        throw new AwsException("ResourceNotFoundException", "Resource not found: " + arn, 404);
    }

    private void removeTags(String arn, List<String> tagKeys) {
        Optional<BackupVault> vaultOpt = vaultStore.scan(k -> true).stream()
                .filter(v -> arn.equals(v.getBackupVaultArn()))
                .findFirst();
        if (vaultOpt.isPresent()) {
            BackupVault vault = vaultOpt.get();
            tagKeys.forEach(vault.getTags()::remove);
            vaultStore.put(vaultKey(vault), vault);
            return;
        }
        throw new AwsException("ResourceNotFoundException", "Resource not found: " + arn, 404);
    }

    private static void assignRuleIds(List<BackupRule> rules) {
        if (rules == null) {
            return;
        }
        for (BackupRule rule : rules) {
            if (rule.getRuleId() == null) {
                rule.setRuleId(UUID.randomUUID().toString());
            }
        }
    }

    private static String inferResourceType(String resourceArn) {
        if (resourceArn == null) {
            return null;
        }
        if (resourceArn.contains(":s3:::")) {
            return "S3";
        }
        if (resourceArn.contains(":rds:")) {
            return "RDS";
        }
        if (resourceArn.contains(":dynamodb:")) {
            return "DynamoDB";
        }
        if (resourceArn.contains(":ec2:")) {
            return "EC2";
        }
        if (resourceArn.contains(":elasticfilesystem:")) {
            return "EFS";
        }
        return null;
    }

    private static String inferResourceName(String resourceArn) {
        return AwsArnUtils.resourceIfArnFor(resourceArn, "s3")
                .filter(bucket -> !bucket.isEmpty() && bucket.indexOf('/') < 0)
                .orElse(null);
    }

    private static String vaultKey(String region, String vaultName) {
        return region + ":" + vaultName;
    }

    /**
     * The key a vault's sub-resources are stored under: the vault key plus the vault's creation
     * date, so it identifies THIS vault rather than merely this NAME.
     *
     * <p>Keying by name alone is what made delete-and-recreate hazardous in both directions. A
     * record surviving a delete grafted an old policy onto the next vault to take the name, and
     * cleaning up by name could erase a live configuration belonging to a vault that had already
     * replaced the one being deleted. Sweeping stale records on create fixed the first and made
     * the second worse, because two creates racing the existence check would both sweep, and the
     * later sweep would delete a configuration applied against the earlier vault.
     *
     * <p>Including the creation date removes the shared key instead of policing it. A new vault
     * cannot read, and cleanup for an old vault cannot touch, a record belonging to a different
     * incarnation of the name -- no sweep, and no dependence on the order two stores are written
     * in. Deleting a vault still removes its own records, now because they are its own rather
     * than because nothing else has claimed them yet.
     *
     * <p>The creation date is only unique per name because {@code createBackupVault} holds a
     * per-name monitor across its check and its write, so two vaults of one name cannot exist
     * and cannot be created in the same second. Without that lock this key would still collide
     * and the two vaults could read each other's configuration. The delete path also removes its
     * own sub-resources, which covers a record surviving for any other reason.
     */
    private static String subResourceKey(String region, BackupVault vault) {
        return vaultKey(region, vault.getBackupVaultName()) + ":" + vault.getCreationDate();
    }

    private static String vaultKey(BackupVault vault) {
        String region = AwsArnUtils.parse(vault.getBackupVaultArn()).region();
        return region + ":" + vault.getBackupVaultName();
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
