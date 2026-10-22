package io.github.hectorvent.floci.services.eks.csi;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ec2.model.Volume;
import io.github.hectorvent.floci.services.eks.csi.v1.ControllerGetCapabilitiesRequest;
import io.github.hectorvent.floci.services.eks.csi.v1.ControllerGetCapabilitiesResponse;
import io.github.hectorvent.floci.services.eks.csi.v1.ControllerGrpc;
import io.github.hectorvent.floci.services.eks.csi.v1.ControllerServiceCapability;
import io.github.hectorvent.floci.services.eks.csi.v1.CreateVolumeRequest;
import io.github.hectorvent.floci.services.eks.csi.v1.CreateVolumeResponse;
import io.github.hectorvent.floci.services.eks.csi.v1.DeleteVolumeRequest;
import io.github.hectorvent.floci.services.eks.csi.v1.DeleteVolumeResponse;
import io.github.hectorvent.floci.services.eks.csi.v1.ValidateVolumeCapabilitiesRequest;
import io.github.hectorvent.floci.services.eks.csi.v1.ValidateVolumeCapabilitiesResponse;
import io.github.hectorvent.floci.services.eks.csi.v1.VolumeCapability;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Creates and deletes the EC2 volumes behind claims, in the account and zone of the cluster that
 * asks, tagged as the EBS CSI driver tags them.
 */
final class EbsCsiControllerService extends ControllerGrpc.ControllerImplBase {

    private static final Logger LOG = Logger.getLogger(EbsCsiControllerService.class);

    static final long GIB = 1024L * 1024L * 1024L;
    static final String NAME_TAG = "CSIVolumeName";
    static final String DRIVER_TAG = "ebs.csi.aws.com/cluster";
    static final String PVC_NAME_PARAMETER = "csi.storage.k8s.io/pvc/name";
    static final String PVC_NAMESPACE_PARAMETER = "csi.storage.k8s.io/pvc/namespace";
    static final String PV_NAME_PARAMETER = "csi.storage.k8s.io/pv/name";
    private static final Map<String, String> PARAMETER_TAGS = Map.of(
            PVC_NAME_PARAMETER, "kubernetes.io/created-for/pvc/name",
            PVC_NAMESPACE_PARAMETER, "kubernetes.io/created-for/pvc/namespace",
            PV_NAME_PARAMETER, "kubernetes.io/created-for/pv/name");

    private final EbsCsiNodes nodes;
    private final EbsCsiNodeDisk disk;
    private final Ec2Service ec2;

    EbsCsiControllerService(EbsCsiNodes nodes, EbsCsiNodeDisk disk, Ec2Service ec2) {
        this.nodes = nodes;
        this.disk = disk;
        this.ec2 = ec2;
    }

    @Override
    public void createVolume(CreateVolumeRequest request, StreamObserver<CreateVolumeResponse> observer) {
        CsiCalls.answer("CreateVolume", observer, () -> {
            if (request.getName().isBlank()) {
                throw CsiCalls.invalid("a volume name is required");
            }
            if (request.getVolumeCapabilitiesCount() == 0) {
                throw CsiCalls.invalid("volume capabilities are required");
            }
            String unsupported = unsupportedCapability(request.getVolumeCapabilitiesList());
            if (unsupported != null) {
                throw CsiCalls.invalid(unsupported);
            }
            if (request.hasVolumeContentSource()) {
                throw CsiCalls.invalid("creating a volume from a snapshot or another volume is not supported");
            }
            EbsCsiNode node = CsiCalls.callingNode(nodes);
            int sizeGiB = sizeGiB(request);
            Volume volume = existing(node, request.getName())
                    .map(found -> requireSize(found, sizeGiB, request.getName()))
                    .orElseGet(() -> create(node, request, sizeGiB));
            return CreateVolumeResponse.newBuilder()
                    .setVolume(io.github.hectorvent.floci.services.eks.csi.v1.Volume.newBuilder()
                            .setVolumeId(volume.getVolumeId())
                            .setCapacityBytes(volume.getSize() * GIB))
                    .build();
        });
    }

    @Override
    public void deleteVolume(DeleteVolumeRequest request, StreamObserver<DeleteVolumeResponse> observer) {
        CsiCalls.answer("DeleteVolume", observer, () -> {
            if (request.getVolumeId().isBlank()) {
                throw CsiCalls.invalid("a volume id is required");
            }
            EbsCsiNode node = CsiCalls.callingNode(nodes);
            if (find(node, request.getVolumeId()).isPresent()) {
                RequestScopes.runAs(node.accountId(), node.region(),
                        () -> ec2.deleteVolume(node.region(), request.getVolumeId()));
                LOG.infov("Deleted EBS volume {0} for cluster {1}", request.getVolumeId(), node.clusterName());
            }
            disk.delete(node, request.getVolumeId());
            return DeleteVolumeResponse.getDefaultInstance();
        });
    }

    @Override
    public void validateVolumeCapabilities(ValidateVolumeCapabilitiesRequest request,
                                           StreamObserver<ValidateVolumeCapabilitiesResponse> observer) {
        CsiCalls.answer("ValidateVolumeCapabilities", observer, () -> {
            EbsCsiNode node = CsiCalls.callingNode(nodes);
            if (find(node, request.getVolumeId()).isEmpty()) {
                throw Status.NOT_FOUND.withDescription("volume " + request.getVolumeId() + " does not exist")
                        .asRuntimeException();
            }
            String unsupported = unsupportedCapability(request.getVolumeCapabilitiesList());
            if (unsupported != null) {
                return ValidateVolumeCapabilitiesResponse.newBuilder().setMessage(unsupported).build();
            }
            return ValidateVolumeCapabilitiesResponse.newBuilder()
                    .setConfirmed(ValidateVolumeCapabilitiesResponse.Confirmed.newBuilder()
                            .addAllVolumeCapabilities(request.getVolumeCapabilitiesList()))
                    .build();
        });
    }

    @Override
    public void controllerGetCapabilities(ControllerGetCapabilitiesRequest request,
                                          StreamObserver<ControllerGetCapabilitiesResponse> observer) {
        CsiCalls.answer("ControllerGetCapabilities", observer, () -> ControllerGetCapabilitiesResponse.newBuilder()
                .addCapabilities(ControllerServiceCapability.newBuilder()
                        .setRpc(ControllerServiceCapability.RPC.newBuilder()
                                .setType(ControllerServiceCapability.RPC.Type.CREATE_DELETE_VOLUME)))
                .build());
    }

    /** Why the driver cannot serve one of these capabilities, or null when it serves them all. */
    static String unsupportedCapability(List<VolumeCapability> capabilities) {
        for (VolumeCapability capability : capabilities) {
            if (capability.hasBlock()) {
                return "raw block volumes are not supported";
            }
            switch (capability.getAccessMode().getMode()) {
                case SINGLE_NODE_WRITER, SINGLE_NODE_READER_ONLY, SINGLE_NODE_SINGLE_WRITER,
                     SINGLE_NODE_MULTI_WRITER -> {
                }
                default -> {
                    return "access mode " + capability.getAccessMode().getMode()
                            + " is not supported: an EBS volume attaches to one node";
                }
            }
        }
        return null;
    }

    // EBS sizes volumes in whole GiB; the CSI default when a claim asks for no size is 1 GiB here.
    static int sizeGiB(CreateVolumeRequest request) {
        long required = request.getCapacityRange().getRequiredBytes();
        long limit = request.getCapacityRange().getLimitBytes();
        long bytes = required > 0 ? required : GIB;
        long sizeGiB = (bytes + GIB - 1) / GIB;
        if (limit > 0 && sizeGiB * GIB > limit) {
            throw Status.OUT_OF_RANGE.withDescription("no whole number of GiB lies between " + required
                    + " and " + limit + " bytes").asRuntimeException();
        }
        return Math.toIntExact(sizeGiB);
    }

    private Volume requireSize(Volume volume, int sizeGiB, String name) {
        if (volume.getSize() != sizeGiB) {
            throw Status.ALREADY_EXISTS.withDescription("volume " + name + " exists as " + volume.getVolumeId()
                    + " with " + volume.getSize() + " GiB, not " + sizeGiB + " GiB").asRuntimeException();
        }
        return volume;
    }

    private Volume create(EbsCsiNode node, CreateVolumeRequest request, int sizeGiB) {
        Map<String, String> parameters = lowerCaseKeys(request.getParametersMap());
        String type = parameters.getOrDefault("type", "gp3");
        boolean encrypted = Boolean.parseBoolean(parameters.getOrDefault("encrypted", "false"));
        int iops = parseInt(parameters.get("iops"), "iops");
        Integer throughput = parameters.containsKey("throughput")
                ? parseInt(parameters.get("throughput"), "throughput")
                : null;
        List<Tag> tags = new ArrayList<>();
        tags.add(new Tag(NAME_TAG, request.getName()));
        tags.add(new Tag(DRIVER_TAG, "true"));
        PARAMETER_TAGS.forEach((parameter, tag) -> {
            String value = request.getParametersMap().get(parameter);
            if (value != null && !value.isBlank()) {
                tags.add(new Tag(tag, value));
            }
        });
        Volume volume = RequestScopes.callAs(node.accountId(), node.region(), () -> ec2.createVolume(
                node.region(), node.availabilityZone(), type, sizeGiB, encrypted, iops, throughput, null, tags));
        LOG.infov("Created EBS volume {0} ({1} GiB {2}) for {3} in cluster {4}", volume.getVolumeId(), sizeGiB,
                type, request.getName(), node.clusterName());
        return volume;
    }

    private Optional<Volume> existing(EbsCsiNode node, String name) {
        List<Volume> found = RequestScopes.callAs(node.accountId(), node.region(),
                () -> ec2.describeVolumes(node.region(), null, Map.of("tag:" + NAME_TAG, List.of(name))));
        return found.stream().findFirst();
    }

    Optional<Volume> find(EbsCsiNode node, String volumeId) {
        try {
            return RequestScopes.callAs(node.accountId(), node.region(),
                    () -> ec2.describeVolumes(node.region(), List.of(volumeId), null)).stream().findFirst();
        } catch (AwsException e) {
            if ("InvalidVolume.NotFound".equals(e.getErrorCode())) {
                return Optional.empty();
            }
            throw e;
        }
    }

    private static Map<String, String> lowerCaseKeys(Map<String, String> parameters) {
        Map<String, String> lower = new HashMap<>();
        parameters.forEach((key, value) -> lower.put(key.toLowerCase(Locale.ROOT), value));
        return lower;
    }

    private static int parseInt(String value, String parameter) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw CsiCalls.invalid("parameter " + parameter + " is not a whole number: " + value);
        }
    }
}
