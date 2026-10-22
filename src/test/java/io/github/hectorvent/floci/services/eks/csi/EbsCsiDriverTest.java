package io.github.hectorvent.floci.services.eks.csi;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.AmiImageResolver;
import io.github.hectorvent.floci.services.ec2.Ec2ContainerManager;
import io.github.hectorvent.floci.services.ec2.Ec2ImageCatalog;
import io.github.hectorvent.floci.services.ec2.Ec2InstanceTypeCatalog;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ec2.model.Volume;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import io.github.hectorvent.floci.services.eks.csi.registration.InfoRequest;
import io.github.hectorvent.floci.services.eks.csi.registration.PluginInfo;
import io.github.hectorvent.floci.services.eks.csi.registration.RegistrationGrpc;
import io.github.hectorvent.floci.services.eks.csi.v1.CapacityRange;
import io.github.hectorvent.floci.services.eks.csi.v1.ControllerGrpc;
import io.github.hectorvent.floci.services.eks.csi.v1.CreateVolumeRequest;
import io.github.hectorvent.floci.services.eks.csi.v1.CreateVolumeResponse;
import io.github.hectorvent.floci.services.eks.csi.v1.DeleteVolumeRequest;
import io.github.hectorvent.floci.services.eks.csi.v1.GetPluginCapabilitiesRequest;
import io.github.hectorvent.floci.services.eks.csi.v1.GetPluginInfoRequest;
import io.github.hectorvent.floci.services.eks.csi.v1.IdentityGrpc;
import io.github.hectorvent.floci.services.eks.csi.v1.NodeGetCapabilitiesRequest;
import io.github.hectorvent.floci.services.eks.csi.v1.NodeGetInfoRequest;
import io.github.hectorvent.floci.services.eks.csi.v1.NodeGrpc;
import io.github.hectorvent.floci.services.eks.csi.v1.NodePublishVolumeRequest;
import io.github.hectorvent.floci.services.eks.csi.v1.NodeUnpublishVolumeRequest;
import io.github.hectorvent.floci.services.eks.csi.v1.PluginCapability;
import io.github.hectorvent.floci.services.eks.csi.v1.VolumeCapability;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EbsCsiDriverTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String NODE_ADDRESS = "172.18.0.2";
    private static final EbsCsiNode NODE = new EbsCsiNode("demo", ACCOUNT, REGION, "us-east-1b", "container-1",
            "i-0123456789abcdef0");
    private static final VolumeCapability MOUNT_RWO = VolumeCapability.newBuilder()
            .setMount(VolumeCapability.MountVolume.newBuilder().setFsType("ext4"))
            .setAccessMode(VolumeCapability.AccessMode.newBuilder()
                    .setMode(VolumeCapability.AccessMode.Mode.SINGLE_NODE_WRITER))
            .build();

    private final Map<String, EbsCsiNode> nodesByAddress = new HashMap<>();
    private final RecordingDisk disk = new RecordingDisk();
    private final AtomicReference<String> callerAddress = new AtomicReference<>(NODE_ADDRESS);
    private final AtomicReference<Path> seed = new AtomicReference<>();
    private Ec2Service ec2;
    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void setUp() throws Exception {
        nodesByAddress.put(NODE_ADDRESS, NODE);
        ec2 = realEc2Service();
        EbsCsiNodes nodes = new FixedNodes(nodesByAddress);
        EbsCsiControllerService controller = new EbsCsiControllerService(nodes, disk, ec2);
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor()
                .intercept(new FixedCallerAddress(callerAddress))
                .addService(new EbsCsiIdentityService())
                .addService(controller)
                .addService(new EbsCsiNodeService(nodes, disk, controller, volume -> seed.get()))
                .addService(new KubeletRegistrationService())
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    @Test
    void identityNamesTheEbsDriverWithAControllerService() {
        IdentityGrpc.IdentityBlockingStub identity = IdentityGrpc.newBlockingStub(channel);

        assertEquals("ebs.csi.aws.com", identity.getPluginInfo(GetPluginInfoRequest.getDefaultInstance()).getName());
        assertEquals(PluginCapability.Service.Type.CONTROLLER_SERVICE, identity
                .getPluginCapabilities(GetPluginCapabilitiesRequest.getDefaultInstance())
                .getCapabilities(0).getService().getType());
    }

    @Test
    void registrationPointsTheKubeletAtTheDriverSocket() {
        PluginInfo info = RegistrationGrpc.newBlockingStub(channel).getInfo(InfoRequest.getDefaultInstance());

        assertEquals("CSIPlugin", info.getType());
        assertEquals("ebs.csi.aws.com", info.getName());
        assertEquals("/var/lib/kubelet/plugins/ebs.csi.aws.com/csi.sock", info.getEndpoint());
        assertEquals(List.of("1.0.0"), info.getSupportedVersionsList());
    }

    @Test
    void createVolumeMakesAnEc2VolumeTaggedForItsClaimInTheNodeZone() {
        CreateVolumeResponse response = controller().createVolume(createRequest("pvc-1", 2_500_000_000L,
                Map.of("type", "gp3",
                        "csi.storage.k8s.io/pvc/name", "data-db-0",
                        "csi.storage.k8s.io/pvc/namespace", "shop",
                        "csi.storage.k8s.io/pv/name", "pvc-1")));

        Volume volume = onlyVolume();
        assertEquals(volume.getVolumeId(), response.getVolume().getVolumeId());
        assertEquals(3L * EbsCsiControllerService.GIB, response.getVolume().getCapacityBytes());
        assertEquals(3, volume.getSize());
        assertEquals("gp3", volume.getVolumeType());
        assertEquals("us-east-1b", volume.getAvailabilityZone());
        assertEquals(Map.of("CSIVolumeName", "pvc-1",
                "ebs.csi.aws.com/cluster", "true",
                "kubernetes.io/created-for/pvc/name", "data-db-0",
                "kubernetes.io/created-for/pvc/namespace", "shop",
                "kubernetes.io/created-for/pv/name", "pvc-1"), tags(volume));
        assertEquals(0, response.getVolume().getAccessibleTopologyCount());
    }

    @Test
    void createVolumeReturnsTheVolumeAlreadyMadeForTheSameName() {
        String first = controller().createVolume(createRequest("pvc-1", EbsCsiControllerService.GIB, Map.of()))
                .getVolume().getVolumeId();
        String second = controller().createVolume(createRequest("pvc-1", EbsCsiControllerService.GIB, Map.of()))
                .getVolume().getVolumeId();

        assertEquals(first, second);
        assertEquals(1, ec2.describeVolumes(REGION, null, null).size());
    }

    @Test
    void createVolumeRefusesADifferentSizeForANameInUse() {
        controller().createVolume(createRequest("pvc-1", EbsCsiControllerService.GIB, Map.of()));

        StatusRuntimeException e = assertThrows(StatusRuntimeException.class, () ->
                controller().createVolume(createRequest("pvc-1", 2 * EbsCsiControllerService.GIB, Map.of())));

        assertEquals(Status.Code.ALREADY_EXISTS, e.getStatus().getCode());
    }

    @Test
    void createVolumeRefusesBlockAndMultiNodeVolumes() {
        VolumeCapability block = VolumeCapability.newBuilder()
                .setBlock(VolumeCapability.BlockVolume.getDefaultInstance())
                .setAccessMode(MOUNT_RWO.getAccessMode())
                .build();
        VolumeCapability shared = MOUNT_RWO.toBuilder()
                .setAccessMode(VolumeCapability.AccessMode.newBuilder()
                        .setMode(VolumeCapability.AccessMode.Mode.MULTI_NODE_MULTI_WRITER))
                .build();

        for (VolumeCapability capability : List.of(block, shared)) {
            StatusRuntimeException e = assertThrows(StatusRuntimeException.class, () -> controller().createVolume(
                    createRequest("pvc-1", EbsCsiControllerService.GIB, Map.of()).toBuilder()
                            .clearVolumeCapabilities().addVolumeCapabilities(capability).build()));
            assertEquals(Status.Code.INVALID_ARGUMENT, e.getStatus().getCode());
        }
        assertTrue(ec2.describeVolumes(REGION, null, null).isEmpty());
    }

    @Test
    void aCallFromAnAddressNoClusterNodeHoldsFailsItsPrecondition() {
        callerAddress.set("10.9.9.9");

        StatusRuntimeException e = assertThrows(StatusRuntimeException.class, () ->
                controller().createVolume(createRequest("pvc-1", EbsCsiControllerService.GIB, Map.of())));

        assertEquals(Status.Code.FAILED_PRECONDITION, e.getStatus().getCode());
        assertTrue(e.getStatus().getDescription().contains("10.9.9.9"));
    }

    @Test
    void deleteVolumeRemovesTheEc2VolumeAndItsDataAndCanBeRepeated() {
        String volumeId = controller().createVolume(createRequest("pvc-1", EbsCsiControllerService.GIB, Map.of()))
                .getVolume().getVolumeId();

        controller().deleteVolume(DeleteVolumeRequest.newBuilder().setVolumeId(volumeId).build());
        controller().deleteVolume(DeleteVolumeRequest.newBuilder().setVolumeId(volumeId).build());

        assertTrue(ec2.describeVolumes(REGION, null, null).isEmpty());
        assertEquals(List.of(volumeId, volumeId), disk.deleted);
    }

    @Test
    void nodeInfoNamesTheNodeInstanceAndNoStageStep() {
        NodeGrpc.NodeBlockingStub node = NodeGrpc.newBlockingStub(channel);

        assertEquals("i-0123456789abcdef0", node.nodeGetInfo(NodeGetInfoRequest.getDefaultInstance()).getNodeId());
        assertEquals(0, node.nodeGetCapabilities(NodeGetCapabilitiesRequest.getDefaultInstance()).getCapabilitiesCount());
    }

    @Test
    void publishMountsTheVolumeWithItsSeedAndUnpublishUnmountsIt() {
        String volumeId = controller().createVolume(createRequest("pvc-1", EbsCsiControllerService.GIB, Map.of()))
                .getVolume().getVolumeId();
        seed.set(Path.of("/data/snap-1.tar"));
        String target = "/var/lib/kubelet/pods/uid/volumes/kubernetes.io~csi/pvc-1/mount";
        NodeGrpc.NodeBlockingStub node = NodeGrpc.newBlockingStub(channel);

        node.nodePublishVolume(NodePublishVolumeRequest.newBuilder()
                .setVolumeId(volumeId).setTargetPath(target).setVolumeCapability(MOUNT_RWO).setReadonly(true).build());
        node.nodeUnpublishVolume(NodeUnpublishVolumeRequest.newBuilder()
                .setVolumeId(volumeId).setTargetPath(target).build());

        assertEquals(List.of(volumeId + " " + target + " ro /data/snap-1.tar"), disk.published);
        assertEquals(List.of(target), disk.unpublished);
    }

    @Test
    void publishingAVolumeTheNodeAccountDoesNotHoldIsNotFound() {
        StatusRuntimeException e = assertThrows(StatusRuntimeException.class, () -> NodeGrpc.newBlockingStub(channel)
                .nodePublishVolume(NodePublishVolumeRequest.newBuilder().setVolumeId("vol-0000000000000000a")
                        .setTargetPath("/target").setVolumeCapability(MOUNT_RWO).build()));

        assertEquals(Status.Code.NOT_FOUND, e.getStatus().getCode());
        assertTrue(disk.published.isEmpty());
    }

    @Test
    void theServerNamesTheCallingNodeByTheAddressItConnectsFrom() throws Exception {
        nodesByAddress.put("127.0.0.1", NODE);
        EmulatorConfig config = mock(EmulatorConfig.class);
        EbsCsiServer csi = new EbsCsiServer(config, new FixedNodes(nodesByAddress), disk, ec2);
        csi.start(0);
        ManagedChannel tcp = NettyChannelBuilder.forAddress("127.0.0.1", csi.port()).usePlaintext().build();
        try {
            assertEquals("i-0123456789abcdef0", NodeGrpc.newBlockingStub(tcp)
                    .nodeGetInfo(NodeGetInfoRequest.getDefaultInstance()).getNodeId());
        } finally {
            tcp.shutdownNow();
            csi.stop();
        }
        assertEquals(-1, csi.port());
    }

    private ControllerGrpc.ControllerBlockingStub controller() {
        return ControllerGrpc.newBlockingStub(channel);
    }

    private static CreateVolumeRequest createRequest(String name, long bytes, Map<String, String> parameters) {
        return CreateVolumeRequest.newBuilder()
                .setName(name)
                .setCapacityRange(CapacityRange.newBuilder().setRequiredBytes(bytes))
                .addVolumeCapabilities(MOUNT_RWO)
                .putAllParameters(parameters)
                .build();
    }

    private Volume onlyVolume() {
        List<Volume> volumes = ec2.describeVolumes(REGION, null, null);
        assertEquals(1, volumes.size());
        return volumes.get(0);
    }

    private static Map<String, String> tags(Volume volume) {
        Map<String, String> tags = new HashMap<>();
        for (Tag tag : volume.getTags()) {
            tags.put(tag.getKey(), tag.getValue());
        }
        return tags;
    }

    private static Ec2Service realEc2Service() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.Ec2ServiceConfig ec2Config = mock(EmulatorConfig.Ec2ServiceConfig.class);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.services()).thenReturn(services);
        when(services.ec2()).thenReturn(ec2Config);
        when(ec2Config.mock()).thenReturn(true);
        StorageFactory storageFactory = new StorageFactory(null, null) {
            @Override
            public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                    TypeReference<Map<String, V>> typeReference) {
                return AccountAwareStorageBackend.inMemory(ACCOUNT);
            }
        };
        return new Ec2Service(config, mock(Ec2ContainerManager.class), mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                storageFactory);
    }

    private record FixedNodes(Map<String, EbsCsiNode> byAddress) implements EbsCsiNodes {

        @Override
        public Optional<EbsCsiNode> nodeAt(String address) {
            return Optional.ofNullable(address == null ? null : byAddress.get(address));
        }

        @Override
        public List<EbsCsiNode> nodes() {
            return new ArrayList<>(byAddress.values());
        }
    }

    private record FixedCallerAddress(AtomicReference<String> address) implements ServerInterceptor {

        @Override
        public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                             ServerCallHandler<Q, R> next) {
            return Contexts.interceptCall(Context.current().withValue(CsiCallerAddress.ADDRESS, address.get()),
                    call, headers, next);
        }
    }

    private static final class RecordingDisk implements EbsCsiNodeDisk {

        final List<String> published = new ArrayList<>();
        final List<String> unpublished = new ArrayList<>();
        final List<String> deleted = new ArrayList<>();
        final Set<String> held = new HashSet<>();

        @Override
        public void publish(EbsCsiNode node, String volumeId, String targetPath, boolean readOnly, Path seed) {
            published.add(volumeId + " " + targetPath + " " + (readOnly ? "ro" : "rw") + " " + seed);
            held.add(volumeId);
        }

        @Override
        public void unpublish(EbsCsiNode node, String targetPath) {
            unpublished.add(targetPath);
        }

        @Override
        public void delete(EbsCsiNode node, String volumeId) {
            deleted.add(volumeId);
            held.remove(volumeId);
        }

        @Override
        public boolean holds(EbsCsiNode node, String volumeId) {
            return held.contains(volumeId);
        }

        @Override
        public void capture(EbsCsiNode node, String volumeId, Path archive) {
            throw new UnsupportedOperationException();
        }
    }
}
