package io.github.hectorvent.floci.services.eks.csi;

import io.github.hectorvent.floci.services.ec2.model.Volume;
import io.github.hectorvent.floci.services.eks.csi.v1.NodeGetCapabilitiesRequest;
import io.github.hectorvent.floci.services.eks.csi.v1.NodeGetCapabilitiesResponse;
import io.github.hectorvent.floci.services.eks.csi.v1.NodeGetInfoRequest;
import io.github.hectorvent.floci.services.eks.csi.v1.NodeGetInfoResponse;
import io.github.hectorvent.floci.services.eks.csi.v1.NodeGrpc;
import io.github.hectorvent.floci.services.eks.csi.v1.NodePublishVolumeRequest;
import io.github.hectorvent.floci.services.eks.csi.v1.NodePublishVolumeResponse;
import io.github.hectorvent.floci.services.eks.csi.v1.NodeUnpublishVolumeRequest;
import io.github.hectorvent.floci.services.eks.csi.v1.NodeUnpublishVolumeResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Mounts a volume's directory into the pods that claim it. With no stage step, the kubelet goes
 * straight to publishing at each pod's target path. The first publish on the node attaches the
 * volume to the node's instance and the last unpublish detaches it, as the attach/detach
 * controller does on AWS.
 */
final class EbsCsiNodeService extends NodeGrpc.NodeImplBase {

    // Nitro instances attach at most 28 volumes, one of them the root volume.
    static final long MAX_VOLUMES_PER_NODE = 27;

    private final EbsCsiNodes nodes;
    private final EbsCsiNodeDisk disk;
    private final EbsCsiControllerService volumes;
    private final Function<Volume, Path> seedOf;
    private final Map<String, Set<String>> publishedTargets = new ConcurrentHashMap<>();
    private final Map<String, Object> volumeLocks = new ConcurrentHashMap<>();

    EbsCsiNodeService(EbsCsiNodes nodes, EbsCsiNodeDisk disk, EbsCsiControllerService volumes,
                      Function<Volume, Path> seedOf) {
        this.nodes = nodes;
        this.disk = disk;
        this.volumes = volumes;
        this.seedOf = seedOf;
    }

    @Override
    public void nodeGetCapabilities(NodeGetCapabilitiesRequest request,
                                    StreamObserver<NodeGetCapabilitiesResponse> observer) {
        CsiCalls.answer("NodeGetCapabilities", observer, NodeGetCapabilitiesResponse::getDefaultInstance);
    }

    @Override
    public void nodeGetInfo(NodeGetInfoRequest request, StreamObserver<NodeGetInfoResponse> observer) {
        CsiCalls.answer("NodeGetInfo", observer, () -> NodeGetInfoResponse.newBuilder()
                .setNodeId(CsiCalls.callingNode(nodes).instanceId())
                .setMaxVolumesPerNode(MAX_VOLUMES_PER_NODE)
                .build());
    }

    @Override
    public void nodePublishVolume(NodePublishVolumeRequest request,
                                  StreamObserver<NodePublishVolumeResponse> observer) {
        CsiCalls.answer("NodePublishVolume", observer, () -> {
            if (request.getVolumeId().isBlank()) {
                throw CsiCalls.invalid("a volume id is required");
            }
            if (request.getTargetPath().isBlank()) {
                throw CsiCalls.invalid("a target path is required");
            }
            if (!request.hasVolumeCapability()) {
                throw CsiCalls.invalid("a volume capability is required");
            }
            String unsupported = EbsCsiControllerService.unsupportedCapability(List.of(request.getVolumeCapability()));
            if (unsupported != null) {
                throw CsiCalls.invalid(unsupported);
            }
            EbsCsiNode node = CsiCalls.callingNode(nodes);
            Volume volume = volumes.find(node, request.getVolumeId())
                    .orElseThrow(() -> Status.NOT_FOUND
                            .withDescription("volume " + request.getVolumeId() + " does not exist in account "
                                    + node.accountId() + " " + node.region())
                            .asRuntimeException());
            synchronized (lockOf(node, volume.getVolumeId())) {
                volumes.attach(node, volume.getVolumeId());
                try {
                    disk.publish(node, volume.getVolumeId(), request.getTargetPath(), request.getReadonly(),
                            seedOf.apply(volume));
                } catch (RuntimeException e) {
                    detachIfUnpublished(node, volume.getVolumeId());
                    throw e;
                }
                targetsOf(node, volume.getVolumeId()).add(request.getTargetPath());
            }
            return NodePublishVolumeResponse.getDefaultInstance();
        });
    }

    @Override
    public void nodeUnpublishVolume(NodeUnpublishVolumeRequest request,
                                    StreamObserver<NodeUnpublishVolumeResponse> observer) {
        CsiCalls.answer("NodeUnpublishVolume", observer, () -> {
            if (request.getVolumeId().isBlank()) {
                throw CsiCalls.invalid("a volume id is required");
            }
            if (request.getTargetPath().isBlank()) {
                throw CsiCalls.invalid("a target path is required");
            }
            EbsCsiNode node = CsiCalls.callingNode(nodes);
            synchronized (lockOf(node, request.getVolumeId())) {
                disk.unpublish(node, request.getTargetPath());
                targetsOf(node, request.getVolumeId()).remove(request.getTargetPath());
                detachIfUnpublished(node, request.getVolumeId());
            }
            return NodeUnpublishVolumeResponse.getDefaultInstance();
        });
    }

    private static String volumeKey(EbsCsiNode node, String volumeId) {
        return node.clusterName() + "/" + volumeId;
    }

    private Object lockOf(EbsCsiNode node, String volumeId) {
        return volumeLocks.computeIfAbsent(volumeKey(node, volumeId), key -> new Object());
    }

    private Set<String> targetsOf(EbsCsiNode node, String volumeId) {
        return publishedTargets.computeIfAbsent(volumeKey(node, volumeId), key -> ConcurrentHashMap.newKeySet());
    }

    private void detachIfUnpublished(EbsCsiNode node, String volumeId) {
        if (publishedTargets.computeIfPresent(volumeKey(node, volumeId),
                (key, targets) -> targets.isEmpty() ? null : targets) == null) {
            volumes.detach(node, volumeId);
        }
    }
}
