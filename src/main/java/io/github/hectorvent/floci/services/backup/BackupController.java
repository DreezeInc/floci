package io.github.hectorvent.floci.services.backup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.backup.model.*;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BackupController {

    private static final Logger LOG = Logger.getLogger(BackupController.class);
    private static final int MAX_RESULTS = 1000;

    private final BackupService service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public BackupController(BackupService service, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    // ── Vault ──────────────────────────────────────────────────────────────────

    @PUT
    @Path("/backup-vaults/{backupVaultName}")
    public Response createBackupVault(@Context HttpHeaders headers,
                                       @PathParam("backupVaultName") String vaultName,
                                       String body) throws IOException {
        String region = regionResolver.resolveRegion(headers);
        JsonNode req = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        String encryptionKeyArn  = textOrNull(req, "EncryptionKeyArn");
        String creatorRequestId  = textOrNull(req, "CreatorRequestId");
        Map<String, String> tags = readStringMap(req, "BackupVaultTags");

        BackupVault vault = service.createBackupVault(vaultName, encryptionKeyArn, creatorRequestId, tags, region);

        ObjectNode out = objectMapper.createObjectNode();
        out.put("BackupVaultName", vault.getBackupVaultName());
        out.put("BackupVaultArn", vault.getBackupVaultArn());
        out.put("CreationDate", vault.getCreationDate());
        return Response.status(200).entity(out).build();
    }

    @PUT
    @Path(BackupRouteFilter.INTERNAL_PREFIX + "/logically-air-gapped-backup-vaults/{backupVaultName}")
    public Response createLogicallyAirGappedBackupVault(@Context HttpHeaders headers,
                                                        @PathParam("backupVaultName") String vaultName,
                                                        String body) throws IOException {
        String region = regionResolver.resolveRegion(headers);
        JsonNode req = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        BackupVault vault = service.createLogicallyAirGappedBackupVault(vaultName,
                stringOrNull(req, "EncryptionKeyArn"), textOrNull(req, "CreatorRequestId"),
                readStringMap(req, "BackupVaultTags"), longOrNull(req, "MinRetentionDays"),
                longOrNull(req, "MaxRetentionDays"), region);

        ObjectNode out = objectMapper.createObjectNode();
        out.put("BackupVaultName", vault.getBackupVaultName());
        out.put("BackupVaultArn", vault.getBackupVaultArn());
        out.put("CreationDate", vault.getCreationDate());
        out.put("VaultState", vault.getVaultState());
        return Response.ok(out).build();
    }

    @GET
    @Path("/backup-vaults/{backupVaultName}")
    public Response describeBackupVault(@Context HttpHeaders headers,
                                         @PathParam("backupVaultName") String vaultName,
                                         @QueryParam("backupVaultAccountId") String vaultAccountId) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(service.describeBackupVault(vaultName, vaultAccountId, region)).build();
    }

    @DELETE
    @Path("/backup-vaults/{backupVaultName}")
    public Response deleteBackupVault(@Context HttpHeaders headers,
                                       @PathParam("backupVaultName") String vaultName) {
        String region = regionResolver.resolveRegion(headers);
        service.deleteBackupVault(vaultName, region);
        return Response.noContent().build();
    }

    @GET
    @Path("/backup-vaults/")
    public Response listBackupVaults(@Context HttpHeaders headers,
                                     @QueryParam("vaultType") String vaultType,
                                     @QueryParam("shared") String shared,
                                     @QueryParam("maxResults") String maxResults,
                                     @QueryParam("nextToken") String nextToken) {
        String region = regionResolver.resolveRegion(headers);
        List<BackupVault> vaults = service.listBackupVaults(region, vaultType, booleanOrNull("shared", shared));
        return page(vaults, BackupVault::getBackupVaultArn, maxResults, nextToken, "backup-vaults",
                "BackupVaultList", (list, vault) -> list.addPOJO(vault));
    }

    // ── Vault access policy ────────────────────────────────────────────────────
    //
    // These three used to be one unconditional GET that always answered
    // ResourceNotFoundException, on the reasoning that a policy is "an optional,
    // never-configured aspect of a vault in the emulator". That error contract was
    // right and is kept below for the genuinely-unconfigured case; what was missing is
    // any way to configure one. A vault could be created and never given a policy, a
    // lock or a notification target, so every Gruntwork example that configures a vault
    // failed at apply.

    @PUT
    @Path("/backup-vaults/{backupVaultName}/access-policy")
    public Response putBackupVaultAccessPolicy(@Context HttpHeaders headers,
                                                @PathParam("backupVaultName") String vaultName,
                                                String body) throws IOException {
        String region = regionResolver.resolveRegion(headers);
        JsonNode req = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        // AWS accepts Policy as a JSON string. Terraform sends a string; the console
        // sends a string. An object is accepted too and re-serialised, because refusing
        // it would be stricter than AWS for no benefit to the caller.
        JsonNode policyNode = req.path("Policy");
        String policy = policyNode.isMissingNode() || policyNode.isNull() ? null
                : (policyNode.isTextual() ? policyNode.asText() : policyNode.toString());
        service.putBackupVaultAccessPolicy(vaultName, region, policy);
        return Response.noContent().build();
    }

    @GET
    @Path("/backup-vaults/{backupVaultName}/access-policy")
    public Response getBackupVaultAccessPolicy(@Context HttpHeaders headers,
                                                @PathParam("backupVaultName") String vaultName) {
        String region = regionResolver.resolveRegion(headers);
        String policy = service.getBackupVaultAccessPolicy(vaultName, region);
        BackupVault vault = service.describeBackupVault(vaultName, region);
        ObjectNode out = objectMapper.createObjectNode();
        out.put("BackupVaultName", vault.getBackupVaultName());
        out.put("BackupVaultArn", vault.getBackupVaultArn());
        out.put("Policy", policy);
        return Response.ok(out).build();
    }

    @DELETE
    @Path("/backup-vaults/{backupVaultName}/access-policy")
    public Response deleteBackupVaultAccessPolicy(@Context HttpHeaders headers,
                                                   @PathParam("backupVaultName") String vaultName) {
        String region = regionResolver.resolveRegion(headers);
        service.deleteBackupVaultAccessPolicy(vaultName, region);
        return Response.noContent().build();
    }

    // ── Vault notifications ────────────────────────────────────────────────────

    @PUT
    @Path("/backup-vaults/{backupVaultName}/notification-configuration")
    public Response putBackupVaultNotifications(@Context HttpHeaders headers,
                                                 @PathParam("backupVaultName") String vaultName,
                                                 String body) throws IOException {
        String region = regionResolver.resolveRegion(headers);
        JsonNode req = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        // null, not an empty list, when the member is absent: the service answers a missing
        // parameter with MissingParameterValueException and an empty one with
        // InvalidParameterValueException, and readStringList cannot tell them apart.
        JsonNode eventsNode = req.path("BackupVaultEvents");
        List<String> events = eventsNode.isMissingNode() || eventsNode.isNull() ? null
                : readStringList(eventsNode);
        service.putBackupVaultNotifications(vaultName, region, stringOrNull(req, "SNSTopicArn"), events);
        return Response.noContent().build();
    }

    @GET
    @Path("/backup-vaults/{backupVaultName}/notification-configuration")
    public Response getBackupVaultNotifications(@Context HttpHeaders headers,
                                                 @PathParam("backupVaultName") String vaultName) {
        String region = regionResolver.resolveRegion(headers);
        BackupVaultNotifications notifications = service.getBackupVaultNotifications(vaultName, region);
        BackupVault vault = service.describeBackupVault(vaultName, region);
        ObjectNode out = objectMapper.createObjectNode();
        out.put("BackupVaultName", vault.getBackupVaultName());
        out.put("BackupVaultArn", vault.getBackupVaultArn());
        out.put("SNSTopicArn", notifications.getSnsTopicArn());
        ArrayNode events = out.putArray("BackupVaultEvents");
        notifications.getBackupVaultEvents().forEach(events::add);
        return Response.ok(out).build();
    }

    @DELETE
    @Path("/backup-vaults/{backupVaultName}/notification-configuration")
    public Response deleteBackupVaultNotifications(@Context HttpHeaders headers,
                                                    @PathParam("backupVaultName") String vaultName) {
        String region = regionResolver.resolveRegion(headers);
        service.deleteBackupVaultNotifications(vaultName, region);
        return Response.noContent().build();
    }

    // ── Vault lock ─────────────────────────────────────────────────────────────

    @PUT
    @Path("/backup-vaults/{backupVaultName}/vault-lock")
    public Response putBackupVaultLockConfiguration(@Context HttpHeaders headers,
                                                     @PathParam("backupVaultName") String vaultName,
                                                     String body) throws IOException {
        String region = regionResolver.resolveRegion(headers);
        JsonNode req = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        service.putBackupVaultLockConfiguration(vaultName, region,
                longOrNull(req, "MinRetentionDays"),
                longOrNull(req, "MaxRetentionDays"),
                longOrNull(req, "ChangeableForDays"));
        return Response.noContent().build();
    }

    @DELETE
    @Path("/backup-vaults/{backupVaultName}/vault-lock")
    public Response deleteBackupVaultLockConfiguration(@Context HttpHeaders headers,
                                                        @PathParam("backupVaultName") String vaultName) {
        String region = regionResolver.resolveRegion(headers);
        service.deleteBackupVaultLockConfiguration(vaultName, region);
        return Response.noContent().build();
    }

    // ── Plan ───────────────────────────────────────────────────────────────────

    @PUT
    @Path("/backup/plans/")
    public Response createBackupPlan(@Context HttpHeaders headers, String body) throws IOException {
        String region = regionResolver.resolveRegion(headers);
        JsonNode req = objectMapper.readTree(body);
        JsonNode planNode = req.path("BackupPlan");
        String planName = planNode.path("BackupPlanName").asText();
        List<BackupRule> rules = readRules(planNode.path("Rules"));
        String creatorRequestId = textOrNull(req, "CreatorRequestId");

        BackupPlan plan = service.createBackupPlan(planName, rules, creatorRequestId, region);

        ObjectNode out = objectMapper.createObjectNode();
        out.put("BackupPlanId", plan.getBackupPlanId());
        out.put("BackupPlanArn", plan.getBackupPlanArn());
        out.put("CreationDate", plan.getCreationDate());
        out.put("VersionId", plan.getVersionId());
        return Response.status(200).entity(out).build();
    }

    @GET
    @Path("/backup/plans/{backupPlanId}/")
    public Response getBackupPlan(@PathParam("backupPlanId") String planId) {
        BackupPlan plan = service.getBackupPlan(planId);
        ObjectNode out = objectMapper.createObjectNode();
        out.put("BackupPlanId", plan.getBackupPlanId());
        out.put("BackupPlanArn", plan.getBackupPlanArn());
        out.put("CreationDate", plan.getCreationDate());
        out.put("VersionId", plan.getVersionId());
        ObjectNode planBody = out.putObject("BackupPlan");
        planBody.put("BackupPlanName", plan.getBackupPlanName());
        planBody.set("Rules", objectMapper.valueToTree(plan.getRules()));
        return Response.ok(out).build();
    }

    @POST
    @Path("/backup/plans/{backupPlanId}")
    public Response updateBackupPlan(@PathParam("backupPlanId") String planId, String body) throws IOException {
        JsonNode req = objectMapper.readTree(body);
        JsonNode planNode = req.path("BackupPlan");
        String planName = planNode.has("BackupPlanName") ? planNode.path("BackupPlanName").asText() : null;
        List<BackupRule> rules = readRules(planNode.path("Rules"));

        BackupPlan plan = service.updateBackupPlan(planId, planName, rules);

        ObjectNode out = objectMapper.createObjectNode();
        out.put("BackupPlanId", plan.getBackupPlanId());
        out.put("BackupPlanArn", plan.getBackupPlanArn());
        out.put("VersionId", plan.getVersionId());
        return Response.ok(out).build();
    }

    @DELETE
    @Path("/backup/plans/{backupPlanId}")
    public Response deleteBackupPlan(@PathParam("backupPlanId") String planId) {
        service.deleteBackupPlan(planId);
        return Response.noContent().build();
    }

    @GET
    @Path("/backup/plans/")
    public Response listBackupPlans() {
        List<BackupPlan> plans = service.listBackupPlans();
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode list = out.putArray("BackupPlansList");
        for (BackupPlan plan : plans) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("BackupPlanId", plan.getBackupPlanId());
            item.put("BackupPlanArn", plan.getBackupPlanArn());
            item.put("BackupPlanName", plan.getBackupPlanName());
            item.put("CreationDate", plan.getCreationDate());
            item.put("VersionId", plan.getVersionId());
            list.add(item);
        }
        return Response.ok(out).build();
    }

    // ── Selection ──────────────────────────────────────────────────────────────

    @PUT
    @Path("/backup/plans/{backupPlanId}/selections/")
    public Response createBackupSelection(@PathParam("backupPlanId") String planId,
                                           String body) throws IOException {
        JsonNode req = objectMapper.readTree(body);
        JsonNode selNode = req.path("BackupSelection");
        String selectionName    = selNode.path("SelectionName").asText();
        String iamRoleArn       = selNode.path("IamRoleArn").asText();
        List<String> resources  = readStringList(selNode.path("Resources"));
        List<String> notResources = readStringList(selNode.path("NotResources"));
        String creatorRequestId = textOrNull(req, "CreatorRequestId");

        BackupSelection sel = service.createBackupSelection(planId, selectionName, iamRoleArn,
                resources, notResources, creatorRequestId);

        ObjectNode out = objectMapper.createObjectNode();
        out.put("SelectionId", sel.getSelectionId());
        out.put("BackupPlanId", sel.getBackupPlanId());
        out.put("CreationDate", sel.getCreationDate());
        return Response.status(200).entity(out).build();
    }

    @GET
    @Path("/backup/plans/{backupPlanId}/selections/{selectionId}")
    public Response getBackupSelection(@PathParam("backupPlanId") String planId,
                                        @PathParam("selectionId") String selectionId) {
        BackupSelection sel = service.getBackupSelection(planId, selectionId);
        ObjectNode out = objectMapper.createObjectNode();
        out.put("BackupPlanId", sel.getBackupPlanId());
        out.put("SelectionId", sel.getSelectionId());
        out.put("CreationDate", sel.getCreationDate());
        ObjectNode selBody = out.putObject("BackupSelection");
        selBody.put("SelectionName", sel.getSelectionName());
        selBody.put("IamRoleArn", sel.getIamRoleArn());
        selBody.set("Resources", objectMapper.valueToTree(sel.getResources()));
        selBody.set("NotResources", objectMapper.valueToTree(sel.getNotResources()));
        return Response.ok(out).build();
    }

    @DELETE
    @Path("/backup/plans/{backupPlanId}/selections/{selectionId}")
    public Response deleteBackupSelection(@PathParam("backupPlanId") String planId,
                                           @PathParam("selectionId") String selectionId) {
        service.deleteBackupSelection(planId, selectionId);
        return Response.noContent().build();
    }

    @GET
    @Path("/backup/plans/{backupPlanId}/selections/")
    public Response listBackupSelections(@PathParam("backupPlanId") String planId) {
        List<BackupSelection> selections = service.listBackupSelections(planId);
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode list = out.putArray("BackupSelectionsList");
        for (BackupSelection sel : selections) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("SelectionId", sel.getSelectionId());
            item.put("SelectionName", sel.getSelectionName());
            item.put("BackupPlanId", sel.getBackupPlanId());
            item.put("IamRoleArn", sel.getIamRoleArn());
            item.put("CreationDate", sel.getCreationDate());
            list.add(item);
        }
        return Response.ok(out).build();
    }

    // ── Job ────────────────────────────────────────────────────────────────────

    @PUT
    @Path("/backup-jobs")
    public Response startBackupJob(@Context HttpHeaders headers, String body) throws IOException {
        String region = regionResolver.resolveRegion(headers);
        JsonNode req = objectMapper.readTree(body);
        String vaultName   = req.path("BackupVaultName").asText();
        String resourceArn = req.path("ResourceArn").asText();
        String iamRoleArn  = req.path("IamRoleArn").asText();
        Lifecycle lifecycle = req.has("Lifecycle")
                ? objectMapper.treeToValue(req.path("Lifecycle"), Lifecycle.class)
                : null;

        BackupJob job = service.startBackupJob(vaultName, resourceArn, iamRoleArn, lifecycle, region);

        ObjectNode out = objectMapper.createObjectNode();
        out.put("BackupJobId", job.getBackupJobId());
        out.put("BackupVaultArn", job.getBackupVaultArn());
        out.put("CreationDate", job.getCreationDate());
        out.put("RecoveryPointArn", "");
        return Response.status(200).entity(out).build();
    }

    @GET
    @Path("/backup-jobs/{backupJobId}")
    public Response describeBackupJob(@PathParam("backupJobId") String jobId) {
        return Response.ok(service.describeBackupJob(jobId)).build();
    }

    @POST
    @Path("/backup-jobs/{backupJobId}")
    public Response stopBackupJob(@PathParam("backupJobId") String jobId) {
        service.stopBackupJob(jobId);
        return Response.noContent().build();
    }

    @GET
    @Path("/backup-jobs/")
    public Response listBackupJobs(@QueryParam("byBackupVaultName") String byVaultName,
                                    @QueryParam("byState") String byState,
                                    @QueryParam("byResourceArn") String byResourceArn,
                                    @QueryParam("byResourceType") String byResourceType) {
        List<BackupJob> jobs = service.listBackupJobs(byVaultName, byState, byResourceArn, byResourceType);
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode list = out.putArray("BackupJobs");
        jobs.forEach(list::addPOJO);
        return Response.ok(out).build();
    }

    // ── Copy job ───────────────────────────────────────────────────────────────

    @PUT
    @Path(BackupRouteFilter.INTERNAL_PREFIX + "/copy-jobs")
    public Response startCopyJob(@Context HttpHeaders headers, String body) throws IOException {
        String region = regionResolver.resolveRegion(headers);
        JsonNode req = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        CopyJob job = service.startCopyJob(stringOrNull(req, "RecoveryPointArn"),
                stringOrNull(req, "SourceBackupVaultName"), stringOrNull(req, "DestinationBackupVaultArn"),
                stringOrNull(req, "IamRoleArn"), readLifecycle(req), textOrNull(req, "IdempotencyToken"), region);

        ObjectNode out = objectMapper.createObjectNode();
        out.put("CopyJobId", job.getCopyJobId());
        out.put("CreationDate", job.getCreationDate());
        out.put("IsParent", job.isParent());
        return Response.ok(out).build();
    }

    @GET
    @Path(BackupRouteFilter.INTERNAL_PREFIX + "/copy-jobs/{copyJobId}")
    public Response describeCopyJob(@PathParam("copyJobId") String copyJobId) {
        ObjectNode out = objectMapper.createObjectNode();
        out.putPOJO("CopyJob", service.describeCopyJob(copyJobId));
        return Response.ok(out).build();
    }

    @GET
    @Path(BackupRouteFilter.INTERNAL_PREFIX + "/copy-jobs/")
    public Response listCopyJobs(@QueryParam("resourceArn") String byResourceArn,
                                 @QueryParam("state") String byState,
                                 @QueryParam("resourceType") String byResourceType,
                                 @QueryParam("destinationVaultArn") String byDestinationVaultArn,
                                 @QueryParam("accountId") String byAccountId,
                                 @QueryParam("sourceRecoveryPointArn") String bySourceRecoveryPointArn,
                                 @QueryParam("createdBefore") String createdBefore,
                                 @QueryParam("createdAfter") String createdAfter,
                                 @QueryParam("maxResults") String maxResults,
                                 @QueryParam("nextToken") String nextToken) {
        List<CopyJob> jobs = service.listCopyJobs(byResourceArn, byState, byResourceType, byDestinationVaultArn,
                byAccountId, bySourceRecoveryPointArn, epochSecondsOrNull("createdBefore", createdBefore),
                epochSecondsOrNull("createdAfter", createdAfter));
        return page(jobs, CopyJob::getCopyJobId, maxResults, nextToken, "copy-jobs",
                "CopyJobs", (list, job) -> list.addPOJO(job));
    }

    // ── Recovery Point ─────────────────────────────────────────────────────────

    @GET
    @Path("/backup-vaults/{backupVaultName}/recovery-points/{recoveryPointArn}")
    public Response describeRecoveryPoint(@Context HttpHeaders headers,
                                           @PathParam("backupVaultName") String vaultName,
                                           @PathParam("recoveryPointArn") String recoveryPointArn,
                                           @QueryParam("backupVaultAccountId") String vaultAccountId) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(service.describeRecoveryPoint(vaultName, vaultAccountId, recoveryPointArn, region))
                .build();
    }

    @GET
    @Path("/backup-vaults/{backupVaultName}/recovery-points/")
    public Response listRecoveryPointsByBackupVault(@Context HttpHeaders headers,
                                                     @PathParam("backupVaultName") String vaultName,
                                                     @QueryParam("backupVaultAccountId") String vaultAccountId,
                                                     @QueryParam("resourceArn") String byResourceArn,
                                                     @QueryParam("resourceType") String byResourceType,
                                                     @QueryParam("backupPlanId") String byBackupPlanId,
                                                     @QueryParam("createdBefore") String createdBefore,
                                                     @QueryParam("createdAfter") String createdAfter,
                                                     @QueryParam("maxResults") String maxResults,
                                                     @QueryParam("nextToken") String nextToken) {
        String region = regionResolver.resolveRegion(headers);
        List<RecoveryPoint> points = service.listRecoveryPointsByBackupVault(vaultName, vaultAccountId, region,
                byResourceArn, byResourceType, byBackupPlanId, epochSecondsOrNull("createdBefore", createdBefore),
                epochSecondsOrNull("createdAfter", createdAfter));
        return page(points, RecoveryPoint::getRecoveryPointArn, maxResults, nextToken, "recovery-points",
                "RecoveryPoints", (list, point) -> list.addPOJO(point));
    }

    // ── Protected resources ────────────────────────────────────────────────────

    @GET
    @Path(BackupRouteFilter.INTERNAL_PREFIX + "/resources/{resourceArn: .+}/recovery-points/")
    public Response listRecoveryPointsByResource(@Context HttpHeaders headers,
                                                 @PathParam("resourceArn") String resourceArn,
                                                 @QueryParam("maxResults") String maxResults,
                                                 @QueryParam("nextToken") String nextToken) {
        String region = regionResolver.resolveRegion(headers);
        List<RecoveryPoint> points = service.listRecoveryPointsByResource(resourceArn, region);
        return page(points, RecoveryPoint::getRecoveryPointArn, maxResults, nextToken, "recovery-points",
                "RecoveryPoints", (list, point) -> {
                    ObjectNode node = list.addObject();
                    node.put("RecoveryPointArn", point.getRecoveryPointArn());
                    node.put("CreationDate", point.getCreationDate());
                    node.put("Status", point.getStatus());
                    putIfNotNull(node, "StatusMessage", point.getStatusMessage());
                    putIfNotNull(node, "EncryptionKeyArn", point.getEncryptionKeyArn());
                    if (point.getBackupSizeInBytes() != null) {
                        node.put("BackupSizeBytes", point.getBackupSizeInBytes());
                    }
                    node.put("BackupVaultName", point.getBackupVaultName());
                    node.put("IsParent", false);
                    putIfNotNull(node, "ResourceName", point.getResourceName());
                    putIfNotNull(node, "VaultType", point.getVaultType());
                    putIfNotNull(node, "EncryptionKeyType", point.getEncryptionKeyType());
                });
    }

    @GET
    @Path(BackupRouteFilter.INTERNAL_PREFIX + "/resources/")
    public Response listProtectedResources(@Context HttpHeaders headers,
                                           @QueryParam("maxResults") String maxResults,
                                           @QueryParam("nextToken") String nextToken) {
        String region = regionResolver.resolveRegion(headers);
        return page(service.listProtectedResources(region), RecoveryPoint::getResourceArn, maxResults, nextToken,
                "resources", "Results", (list, point) -> writeProtectedResource(list.addObject(), point));
    }

    @GET
    @Path(BackupRouteFilter.INTERNAL_PREFIX + "/resources/{resourceArn: .+}")
    public Response describeProtectedResource(@Context HttpHeaders headers,
                                              @PathParam("resourceArn") String resourceArn) {
        String region = regionResolver.resolveRegion(headers);
        ObjectNode out = objectMapper.createObjectNode();
        writeProtectedResource(out, service.describeProtectedResource(resourceArn, region));
        return Response.ok(out).build();
    }

    private static void writeProtectedResource(ObjectNode node, RecoveryPoint latest) {
        node.put("ResourceArn", latest.getResourceArn());
        putIfNotNull(node, "ResourceType", latest.getResourceType());
        node.put("LastBackupTime", latest.getCreationDate());
        putIfNotNull(node, "ResourceName", latest.getResourceName());
        node.put("LastBackupVaultArn", latest.getBackupVaultArn());
        node.put("LastRecoveryPointArn", latest.getRecoveryPointArn());
    }

    @DELETE
    @Path("/backup-vaults/{backupVaultName}/recovery-points/{recoveryPointArn}")
    public Response deleteRecoveryPoint(@Context HttpHeaders headers,
                                         @PathParam("backupVaultName") String vaultName,
                                         @PathParam("recoveryPointArn") String recoveryPointArn) {
        String region = regionResolver.resolveRegion(headers);
        service.deleteRecoveryPoint(vaultName, recoveryPointArn, region);
        return Response.noContent().build();
    }

    // ── Untag (POST /untag/{arn} with body — distinct from shared DELETE /tags pattern) ──

    @POST
    @Path("/untag/{resourceArn: .*}")
    public Response untagResource(@PathParam("resourceArn") String resourceArn,
                                   String body) throws IOException {
        JsonNode req = objectMapper.readTree(body);
        List<String> tagKeys = readStringList(req.path("TagKeyList"));
        service.untagResource(resourceArn, tagKeys);
        return Response.noContent().build();
    }

    // ── Supported resource types ───────────────────────────────────────────────

    @GET
    @Path("/supported-resource-types")
    public Response getSupportedResourceTypes() {
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode list = out.putArray("ResourceTypes");
        service.getSupportedResourceTypes().forEach(list::add);
        return Response.ok(out).build();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private <T> Response page(List<T> items, Function<T, String> cursorOf, String maxResults, String nextToken,
                              String namespace, String listName,
                              BiConsumer<ArrayNode, T> writer) {
        Integer limit = Pagination.parseMaxResults(maxResults, "InvalidParameterValueException");
        if (limit != null && (limit < 1 || limit > MAX_RESULTS)) {
            throw new AwsException("InvalidParameterValueException",
                    "MaxResults must be between 1 and " + MAX_RESULTS, 400);
        }
        PaginatedResult<T> result = Pagination.paginate(items, cursorOf, limit == null ? MAX_RESULTS : limit,
                nextToken, namespace, token -> new AwsException("InvalidParameterValueException",
                        "Invalid NextToken: " + token, 400));
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode list = out.putArray(listName);
        result.items().forEach(item -> writer.accept(list, item));
        if (result.nextToken() != null) {
            out.put("NextToken", result.nextToken());
        }
        return Response.ok(out).build();
    }

    private static void putIfNotNull(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private static Boolean booleanOrNull(String field, String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new AwsException("InvalidParameterValueException", field + " must be true or false", 400);
        }
        return Boolean.parseBoolean(value);
    }

    /** Query-string timestamps arrive as ISO 8601 from the SDKs; epoch seconds are accepted too. */
    private static Long epochSecondsOrNull(String field, String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(value).getEpochSecond();
        } catch (DateTimeParseException e) {
            try {
                return (long) Double.parseDouble(value);
            } catch (NumberFormatException ignored) {
                throw new AwsException("InvalidParameterValueException", field + " is not a valid timestamp", 400);
            }
        }
    }

    private Lifecycle readLifecycle(JsonNode req) throws IOException {
        JsonNode node = req.path("Lifecycle");
        return node.isMissingNode() || node.isNull() ? null : objectMapper.treeToValue(node, Lifecycle.class);
    }

    // Absent and null both mean "not supplied", which for these members is meaningful:
    // omitting ChangeableForDays is what selects compliance mode.
    //
    // A present value must be an INTEGRAL NUMBER, and is rejected rather than coerced.
    // asLong() would silently accept anything: 1.5 truncates to 1, true becomes 1, and
    // the string "7" becomes 7 -- so a request AWS rejects with a serialization error
    // would instead succeed here against a value the caller never asked for, and a
    // retention floor could be set from a number that was never sent. Matches the
    // existing shape in KinesisJsonHandler#optionalMaxRecordSize and
    // TimestreamInfluxDbValidation, which reject on !isIntegralNumber() for the same
    // reason. Found by Greptile on the fork PR.
    private static Long longOrNull(JsonNode node, String field) {
        JsonNode n = node.path(field);
        if (n.isMissingNode() || n.isNull()) {
            return null;
        }
        // canConvertToLong as well as isIntegralNumber, because the first alone leaves
        // one coercion open: 18446744073709551623 IS an integral number, and
        // longValue() keeps its low 64 bits, wrapping it to 7, which then passes the
        // retention checks as if the caller had sent it. Kinesis guards the same way
        // with canConvertToInt (KinesisJsonHandler#optionalMaxRecordSize). Rejecting
        // a value we cannot represent is the whole point of not coercing.
        if (!n.isIntegralNumber() || !n.canConvertToLong()) {
            throw new AwsException("InvalidParameterValueException",
                    field + " must be an integer", 400);
        }
        return n.longValue();
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode n = node.path(field);
        return n.isMissingNode() || n.isNull() ? null : n.asText();
    }

    /**
     * Like {@link #textOrNull} but refuses to invent a string out of something that is not one.
     *
     * <p>{@code asText()} coerces: a request carrying {@code "SNSTopicArn": 123} yields the
     * nonblank string {@code "123"}, which passes every present-and-nonblank check the service
     * makes and is stored as the topic. The configuration then reads back as valid while naming a
     * topic that cannot exist. This is the same coercion Greptile found in {@link #longOrNull}
     * above, in the other direction, and it gets the same answer: reject a value we would have to
     * fabricate rather than read.
     *
     * <p>Used for the fields where the fabricated value would survive validation. The advisory
     * ones -- CreatorRequestId and the like -- stay on textOrNull, because a coerced value there
     * is stored and echoed and misleads nobody.
     */
    private static String stringOrNull(JsonNode node, String field) {
        JsonNode n = node.path(field);
        if (n.isMissingNode() || n.isNull()) {
            return null;
        }
        if (!n.isTextual()) {
            throw new AwsException("InvalidParameterValueException",
                    field + " must be a string", 400);
        }
        return n.asText();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> readStringMap(JsonNode node, String field) {
        JsonNode mapNode = node.path(field);
        if (mapNode.isMissingNode() || mapNode.isNull()) {
            return new java.util.HashMap<>();
        }
        return objectMapper.convertValue(mapNode, Map.class);
    }

    private static List<String> readStringList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node == null || node.isMissingNode() || node.isNull()) {
            return result;
        }
        node.forEach(n -> result.add(n.asText()));
        return result;
    }

    private List<BackupRule> readRules(JsonNode rulesNode) throws IOException {
        List<BackupRule> rules = new ArrayList<>();
        if (rulesNode == null || rulesNode.isMissingNode() || !rulesNode.isArray()) {
            return rules;
        }
        for (JsonNode ruleNode : rulesNode) {
            rules.add(objectMapper.treeToValue(ruleNode, BackupRule.class));
        }
        return rules;
    }
}
