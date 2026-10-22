package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ec2.model.Volume;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::EC2::Volume}, backed by {@link Ec2Service}. {@code Ref}
 * and {@code Fn::GetAtt VolumeId} are the {@code vol-} id, matching AWS.
 *
 * <p>AvailabilityZone and SnapshotId are createOnly: an update that keeps them reuses the volume it
 * already has (Size, VolumeType, Iops, Throughput and Tags are not reconciled), and a change to
 * either is refused as a replacement. {@code KmsKeyId} is accepted and not stored, because the EC2
 * volume model carries no key; the omission is logged once per volume.
 */
@ApplicationScoped
public class Ec2VolumeCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(Ec2VolumeCfnProvisioner.class);
    private static final String TYPE = "AWS::EC2::Volume";
    private static final String NOT_FOUND = "InvalidVolume.NotFound";

    private final Ec2Service ec2Service;

    @Inject
    public Ec2VolumeCfnProvisioner(Ec2Service ec2Service) {
        this.ec2Service = ec2Service;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        String availabilityZone = ctx.resolveRequired(props, "AvailabilityZone", TYPE);
        String snapshotId = ctx.resolveOptional(props, "SnapshotId");
        if (ctx.resolveOptional(props, "KmsKeyId") != null) {
            LOG.warnv("{0} {1}: KmsKeyId accepted but not stored; EC2 volumes carry no KMS key in Floci",
                    TYPE, r.getLogicalId());
        }

        if (ctx.isUpdate()) {
            Volume existing = findVolume(ctx.region(), ctx.priorPhysicalId());
            if (existing != null) {
                rejectIfChanged("AvailabilityZone", existing.getAvailabilityZone(), availabilityZone);
                rejectIfChanged("SnapshotId", existing.getSnapshotId(), snapshotId);
                record(r, existing);
                return;
            }
        }

        List<Tag> tags = new ArrayList<>();
        for (Map.Entry<String, String> tag : ctx.resolveTags(props, "Tags").entrySet()) {
            tags.add(new Tag(tag.getKey(), tag.getValue()));
        }
        String throughput = ctx.resolveOptional(props, "Throughput");
        record(r, ec2Service.createVolume(ctx.region(), availabilityZone,
                ctx.resolveOptional(props, "VolumeType"),
                intOrZero(ctx.resolveOptional(props, "Size")),
                Boolean.parseBoolean(ctx.resolveOptional(props, "Encrypted")),
                intOrZero(ctx.resolveOptional(props, "Iops")),
                throughput == null || throughput.isBlank() ? null : Integer.parseInt(throughput),
                snapshotId, tags));
    }

    private static int intOrZero(String value) {
        return value == null || value.isBlank() ? 0 : Integer.parseInt(value);
    }

    private Volume findVolume(String region, String volumeId) {
        try {
            return ec2Service.describeVolumes(region, List.of(volumeId), null).stream().findFirst().orElse(null);
        } catch (AwsException e) {
            if (!NOT_FOUND.equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("EBS volume {0} from the previous provision is gone, creating it again", volumeId);
            return null;
        }
    }

    private static void rejectIfChanged(String property, String existing, String requested) {
        String a = existing == null || existing.isBlank() ? null : existing;
        String b = requested == null || requested.isBlank() ? null : requested;
        if (!Objects.equals(a, b)) {
            throw CfnExceptions.replacementNotSupported(property);
        }
    }

    private static void record(StackResource r, Volume volume) {
        r.setPhysicalId(volume.getVolumeId());
        r.getAttributes().put("VolumeId", volume.getVolumeId());
    }

    /** Without this the volume outlives its stack; one still attached fails the delete, as on AWS. */
    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (physicalId == null || physicalId.isBlank()) {
            return;
        }
        CfnDeletes.safeDelete("EBS volume", physicalId, () -> ec2Service.deleteVolume(region, physicalId), NOT_FOUND);
    }
}
