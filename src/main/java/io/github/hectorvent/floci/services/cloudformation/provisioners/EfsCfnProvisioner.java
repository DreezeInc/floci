package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.efs.EfsService;
import io.github.hectorvent.floci.services.efs.model.AccessPointDescription;
import io.github.hectorvent.floci.services.efs.model.BackupPolicy;
import io.github.hectorvent.floci.services.efs.model.CreateAccessPointRequest;
import io.github.hectorvent.floci.services.efs.model.CreateFileSystemRequest;
import io.github.hectorvent.floci.services.efs.model.CreateMountTargetRequest;
import io.github.hectorvent.floci.services.efs.model.CreationInfo;
import io.github.hectorvent.floci.services.efs.model.DescribeMountTargetsRequest;
import io.github.hectorvent.floci.services.efs.model.FileSystem;
import io.github.hectorvent.floci.services.efs.model.LifecyclePolicy;
import io.github.hectorvent.floci.services.efs.model.ModifyMountTargetSecurityGroupsRequest;
import io.github.hectorvent.floci.services.efs.model.MountTarget;
import io.github.hectorvent.floci.services.efs.model.PerformanceMode;
import io.github.hectorvent.floci.services.efs.model.PosixUser;
import io.github.hectorvent.floci.services.efs.model.RootDirectory;
import io.github.hectorvent.floci.services.efs.model.Tag;
import io.github.hectorvent.floci.services.efs.model.ThroughputMode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * CloudFormation provisioning for the EFS types, backed by {@link EfsService}:
 * {@code AWS::EFS::FileSystem} ({@code Ref} and {@code FileSystemId} the {@code fs-} id, {@code Arn}),
 * {@code AWS::EFS::MountTarget} ({@code Ref} and {@code Id} the {@code fsmt-} id, {@code IpAddress})
 * and {@code AWS::EFS::AccessPoint} ({@code Ref} and {@code AccessPointId} the {@code fsap-} id,
 * {@code Arn}), matching AWS.
 *
 * <p>Each type's createOnly properties are checked on update: an unchanged resource is reused and its
 * policies and mount-target security groups re-applied, while a changed one is refused as a
 * replacement. Tags are not reconciled on a reused resource.
 */
@ApplicationScoped
public class EfsCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(EfsCfnProvisioner.class);
    private static final String FILE_SYSTEM = "AWS::EFS::FileSystem";
    private static final String MOUNT_TARGET = "AWS::EFS::MountTarget";
    private static final String ACCESS_POINT = "AWS::EFS::AccessPoint";
    private static final String FILE_SYSTEM_NOT_FOUND = "FileSystemNotFound";
    private static final String MOUNT_TARGET_NOT_FOUND = "MountTargetNotFound";
    private static final String ACCESS_POINT_NOT_FOUND = "AccessPointNotFound";

    private final EfsService efsService;

    @Inject
    public EfsCfnProvisioner(EfsService efsService) {
        this.efsService = efsService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(FILE_SYSTEM, MOUNT_TARGET, ACCESS_POINT);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case FILE_SYSTEM -> provisionFileSystem(r, props, ctx);
            case MOUNT_TARGET -> provisionMountTarget(r, props, ctx);
            case ACCESS_POINT -> provisionAccessPoint(r, props, ctx);
            default -> throw new IllegalStateException("Unhandled type: " + r.getResourceType());
        }
    }

    private void provisionFileSystem(StackResource r, JsonNode props, ProvisionContext ctx) {
        String kmsKeyId = ctx.resolveOptional(props, "KmsKeyId");
        String performanceMode = ctx.resolveOptional(props, "PerformanceMode");
        String availabilityZoneName = ctx.resolveOptional(props, "AvailabilityZoneName");
        boolean encrypted = Boolean.parseBoolean(ctx.resolveOptional(props, "Encrypted"));

        if (ctx.isUpdate()) {
            FileSystem existing = findFileSystem(ctx.region(), ctx.priorPhysicalId());
            if (existing != null) {
                rejectIfChanged("Encrypted", String.valueOf(Boolean.TRUE.equals(existing.getEncrypted())),
                        String.valueOf(encrypted));
                rejectIfChanged("KmsKeyId", existing.getKmsKeyId(), kmsKeyId);
                rejectIfChanged("PerformanceMode", existing.getPerformanceMode(),
                        performanceMode == null ? PerformanceMode.generalPurpose.name() : performanceMode);
                rejectIfChanged("AvailabilityZoneName", existing.getAvailabilityZoneName(), availabilityZoneName);
                String backupStatus = backupPolicyStatus(props, ctx);
                if (backupStatus != null) {
                    BackupPolicy policy = new BackupPolicy();
                    policy.setStatus(backupStatus);
                    efsService.putBackupPolicy(ctx.region(), existing.getFileSystemId(), policy);
                }
                applyFileSystemPolicies(existing.getFileSystemId(), props, ctx);
                record(r, existing);
                return;
            }
        }

        CreateFileSystemRequest request = new CreateFileSystemRequest();
        request.setEncrypted(encrypted);
        request.setKmsKeyId(kmsKeyId);
        request.setAvailabilityZoneName(availabilityZoneName);
        request.setPerformanceMode(enumValue(PerformanceMode.class, "PerformanceMode", performanceMode));
        request.setThroughputMode(enumValue(ThroughputMode.class, "ThroughputMode",
                ctx.resolveOptional(props, "ThroughputMode")));
        String provisionedThroughput = ctx.resolveOptional(props, "ProvisionedThroughputInMibps");
        if (provisionedThroughput != null && !provisionedThroughput.isBlank()) {
            request.setProvisionedThroughputInMibps(Double.parseDouble(provisionedThroughput));
        }
        String backupStatus = backupPolicyStatus(props, ctx);
        if (backupStatus != null) {
            request.setBackup("ENABLED".equalsIgnoreCase(backupStatus));
        }
        request.setTags(tags(ctx.resolveTags(props, "FileSystemTags")));

        FileSystem fileSystem = efsService.createFileSystem(request, ctx.region());
        applyFileSystemPolicies(fileSystem.getFileSystemId(), props, ctx);
        record(r, fileSystem);
    }

    private static String backupPolicyStatus(JsonNode props, ProvisionContext ctx) {
        if (props == null || !props.hasNonNull("BackupPolicy")) {
            return null;
        }
        JsonNode resolved = ctx.engine().resolveNode(props.get("BackupPolicy"));
        return resolved == null ? null : ctx.resolveOptional(resolved, "Status");
    }

    private void applyFileSystemPolicies(String fileSystemId, JsonNode props, ProvisionContext ctx) {
        if (props != null && props.hasNonNull("FileSystemPolicy")) {
            efsService.putFileSystemPolicy(ctx.region(), fileSystemId,
                    ctx.engine().resolveJsonAttributeStrict(props.get("FileSystemPolicy")));
        }
        if (props != null && props.hasNonNull("LifecyclePolicies")) {
            JsonNode resolved = ctx.engine().resolveNode(props.get("LifecyclePolicies"));
            List<LifecyclePolicy> policies = new ArrayList<>();
            if (resolved != null && resolved.isArray()) {
                for (JsonNode node : resolved) {
                    LifecyclePolicy policy = new LifecyclePolicy();
                    policy.setTransitionToIA(ctx.resolveOptional(node, "TransitionToIA"));
                    policy.setTransitionToPrimaryStorageClass(
                            ctx.resolveOptional(node, "TransitionToPrimaryStorageClass"));
                    policy.setTransitionToArchive(ctx.resolveOptional(node, "TransitionToArchive"));
                    policies.add(policy);
                }
            }
            efsService.putLifecycleConfiguration(ctx.region(), fileSystemId, policies);
        }
    }

    private void provisionMountTarget(StackResource r, JsonNode props, ProvisionContext ctx) {
        String fileSystemId = ctx.resolveRequired(props, "FileSystemId", MOUNT_TARGET);
        String subnetId = ctx.resolveRequired(props, "SubnetId", MOUNT_TARGET);
        List<String> securityGroups = ctx.resolveStringList(props, "SecurityGroups");

        if (ctx.isUpdate()) {
            MountTarget existing = findMountTarget(ctx.region(), ctx.priorPhysicalId());
            if (existing != null) {
                rejectIfChanged("FileSystemId", existing.getFileSystemId(), fileSystemId);
                rejectIfChanged("SubnetId", existing.getSubnetId(), subnetId);
                if (!new HashSet<>(securityGroups).equals(new HashSet<>(existing.getSecurityGroups()))) {
                    ModifyMountTargetSecurityGroupsRequest modify = new ModifyMountTargetSecurityGroupsRequest();
                    modify.setSecurityGroups(securityGroups);
                    efsService.modifyMountTargetSecurityGroups(ctx.region(), existing.getMountTargetId(), modify);
                }
                record(r, existing);
                return;
            }
        }

        CreateMountTargetRequest request = new CreateMountTargetRequest();
        request.setFileSystemId(fileSystemId);
        request.setSubnetId(subnetId);
        request.setSecurityGroups(securityGroups);
        request.setIpAddress(ctx.resolveOptional(props, "IpAddress"));
        record(r, efsService.createMountTarget(request, ctx.region()));
    }

    private void provisionAccessPoint(StackResource r, JsonNode props, ProvisionContext ctx) {
        String fileSystemId = ctx.resolveRequired(props, "FileSystemId", ACCESS_POINT);

        if (ctx.isUpdate()) {
            AccessPointDescription existing = findAccessPoint(ctx.region(), ctx.priorPhysicalId());
            if (existing != null) {
                rejectIfChanged("FileSystemId", existing.getFileSystemId(), fileSystemId);
                record(r, existing);
                return;
            }
        }

        CreateAccessPointRequest request = new CreateAccessPointRequest();
        request.setFileSystemId(fileSystemId);
        request.setPosixUser(posixUser(props, ctx));
        request.setRootDirectory(rootDirectory(props, ctx));
        request.setTags(tags(ctx.resolveTags(props, "AccessPointTags")));
        record(r, efsService.createAccessPoint(ctx.region(), request));
    }

    private static PosixUser posixUser(JsonNode props, ProvisionContext ctx) {
        if (props == null || !props.hasNonNull("PosixUser")) {
            return null;
        }
        JsonNode resolved = ctx.engine().resolveNode(props.get("PosixUser"));
        if (resolved == null || !resolved.isObject()) {
            return null;
        }
        PosixUser user = new PosixUser();
        user.setUid(longOrNull(ctx.resolveOptional(resolved, "Uid")));
        user.setGid(longOrNull(ctx.resolveOptional(resolved, "Gid")));
        if (resolved.hasNonNull("SecondaryGids")) {
            List<Long> secondary = new ArrayList<>();
            for (String gid : ctx.resolveStringList(resolved, "SecondaryGids")) {
                secondary.add(Long.parseLong(gid));
            }
            user.setSecondaryGids(secondary);
        }
        return user;
    }

    private static RootDirectory rootDirectory(JsonNode props, ProvisionContext ctx) {
        if (props == null || !props.hasNonNull("RootDirectory")) {
            return null;
        }
        JsonNode resolved = ctx.engine().resolveNode(props.get("RootDirectory"));
        if (resolved == null || !resolved.isObject()) {
            return null;
        }
        RootDirectory directory = new RootDirectory();
        directory.setPath(ctx.resolveOptional(resolved, "Path"));
        if (resolved.hasNonNull("CreationInfo")) {
            JsonNode info = ctx.engine().resolveNode(resolved.get("CreationInfo"));
            CreationInfo creationInfo = new CreationInfo();
            creationInfo.setOwnerUid(longOrNull(ctx.resolveOptional(info, "OwnerUid")));
            creationInfo.setOwnerGid(longOrNull(ctx.resolveOptional(info, "OwnerGid")));
            creationInfo.setPermissions(ctx.resolveOptional(info, "Permissions"));
            directory.setCreationInfo(creationInfo);
        }
        return directory;
    }

    private static Long longOrNull(String value) {
        return value == null || value.isBlank() ? null : Long.parseLong(value);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String property, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw new AwsException("ValidationError", "Invalid " + property + ": " + value, 400);
        }
    }

    private static List<Tag> tags(Map<String, String> resolved) {
        List<Tag> tags = new ArrayList<>();
        for (Map.Entry<String, String> entry : resolved.entrySet()) {
            Tag tag = new Tag();
            tag.setKey(entry.getKey());
            tag.setValue(entry.getValue());
            tags.add(tag);
        }
        return tags;
    }

    private FileSystem findFileSystem(String region, String fileSystemId) {
        try {
            return efsService.getFileSystem(region, fileSystemId);
        } catch (AwsException e) {
            if (!FILE_SYSTEM_NOT_FOUND.equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("EFS file system {0} from the previous provision is gone, creating it again", fileSystemId);
            return null;
        }
    }

    private MountTarget findMountTarget(String region, String mountTargetId) {
        DescribeMountTargetsRequest request = new DescribeMountTargetsRequest();
        request.setMountTargetId(mountTargetId);
        return efsService.describeMountTargets(region, request).getMountTargets().stream().findFirst().orElse(null);
    }

    private AccessPointDescription findAccessPoint(String region, String accessPointId) {
        return efsService.describeAccessPoints(region, null, accessPointId).stream().findFirst().orElse(null);
    }

    private static void rejectIfChanged(String property, String existing, String requested) {
        String a = existing == null || existing.isBlank() ? null : existing;
        String b = requested == null || requested.isBlank() ? null : requested;
        if (!Objects.equals(a, b)) {
            throw CfnExceptions.replacementNotSupported(property);
        }
    }

    private static void record(StackResource r, FileSystem fileSystem) {
        r.setPhysicalId(fileSystem.getFileSystemId());
        r.getAttributes().put("FileSystemId", fileSystem.getFileSystemId());
        r.getAttributes().put("Arn", fileSystem.getFileSystemArn());
    }

    private static void record(StackResource r, MountTarget mountTarget) {
        r.setPhysicalId(mountTarget.getMountTargetId());
        r.getAttributes().put("Id", mountTarget.getMountTargetId());
        r.getAttributes().put("IpAddress", mountTarget.getIpAddress());
    }

    private static void record(StackResource r, AccessPointDescription accessPoint) {
        r.setPhysicalId(accessPoint.getAccessPointId());
        r.getAttributes().put("AccessPointId", accessPoint.getAccessPointId());
        r.getAttributes().put("Arn", accessPoint.getAccessPointArn());
    }

    /** A file system that still has mount targets refuses to go, as on AWS; that failure surfaces on the stack. */
    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (physicalId == null || physicalId.isBlank()) {
            return;
        }
        switch (resourceType) {
            case FILE_SYSTEM -> CfnDeletes.safeDelete("EFS file system", physicalId,
                    () -> efsService.deleteFileSystem(region, physicalId), FILE_SYSTEM_NOT_FOUND);
            case MOUNT_TARGET -> CfnDeletes.safeDelete("EFS mount target", physicalId,
                    () -> efsService.deleteMountTarget(region, physicalId), MOUNT_TARGET_NOT_FOUND);
            case ACCESS_POINT -> CfnDeletes.safeDelete("EFS access point", physicalId,
                    () -> efsService.deleteAccessPoint(region, physicalId), ACCESS_POINT_NOT_FOUND);
            default -> throw new IllegalStateException("Unhandled type: " + resourceType);
        }
    }
}
