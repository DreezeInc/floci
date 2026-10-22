package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.model.BlockDeviceMapping;
import io.github.hectorvent.floci.services.ec2.model.EbsBlockDevice;
import io.github.hectorvent.floci.services.ec2.model.Image;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.model.Snapshot;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ec2.model.Volume;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** No KMS service is wired here, so key ids pass through unvalidated. */
class Ec2EbsSnapshotServiceTest {

    private static final String REGION = "us-east-1";
    private static final String OTHER_REGION = "us-west-2";
    private static final String OWNER = "111111111111";
    private static final String RECIPIENT = "222222222222";
    private static final String THIRD = "123456789012";
    private static final String OWNER_KEY =
            "arn:aws:kms:us-east-1:111111111111:key/1234abcd-12ab-34cd-56ef-1234567890ab";
    private static final String RECIPIENT_KEY =
            "arn:aws:kms:us-east-1:222222222222:key/abcd1234-12ab-34cd-56ef-1234567890ab";
    private static final String DEFAULT_KEY = "alias/aws/ebs";

    private final AtomicReference<String> caller = new AtomicReference<>(OWNER);
    private Ec2Service service;

    @BeforeEach
    void setUp() {
        service = newService();
    }

    @Test
    void createSnapshotReportsPendingAndThenCompletedWithTheVolumesEncryption() {
        Volume volume = volume(16, true, OWNER_KEY);

        Snapshot created = service.createSnapshot(REGION, volume.getVolumeId(), "nightly",
                List.of(new Tag("team", "storage")));
        Snapshot described = describeOne(created.getSnapshotId());

        assertTrue(created.getSnapshotId().matches("snap-[0-9a-f]{17}"), created.getSnapshotId());
        assertEquals("pending", created.getState());
        assertEquals("completed", described.getState());
        assertEquals("100%", described.getProgress());
        assertEquals(OWNER, described.getOwnerId());
        assertEquals(volume.getVolumeId(), described.getVolumeId());
        assertEquals(16, described.getVolumeSize());
        assertTrue(described.isEncrypted());
        assertEquals(OWNER_KEY, described.getKmsKeyId());
        assertEquals("nightly", described.getDescription());
        assertEquals(Map.of("team", "storage"), tagMap(described.getTags()));
    }

    @Test
    void snapshotOfAnUnencryptedVolumeCarriesNoKey() {
        Snapshot described = describeOne(snapshot(volume(8, false, null)));

        assertFalse(described.isEncrypted());
        assertNull(described.getKmsKeyId());
        assertEquals("", described.getDescription());
    }

    @Test
    void snapshotOfAnUnknownVolumeIsRejected() {
        assertError("InvalidVolume.NotFound",
                () -> service.createSnapshot(REGION, "vol-0123456789abcdef0", null, List.of()));
        assertError("MissingParameter", () -> service.createSnapshot(REGION, null, null, List.of()));
    }

    @Test
    void sharedSnapshotIsCopiedAndRestoredAcrossThreeAccounts() {
        String original = service.createSnapshot(REGION, volume(8, true, OWNER_KEY).getVolumeId(), null,
                List.of(new Tag("Name", "source"))).getSnapshotId();

        caller.set(RECIPIENT);
        assertError("InvalidSnapshot.NotFound", () -> describe(List.of(original)));
        assertError("InvalidSnapshot.NotFound",
                () -> service.copySnapshot(REGION, REGION, original, null, true, RECIPIENT_KEY, List.of()));

        caller.set(OWNER);
        share(original, RECIPIENT);

        caller.set(RECIPIENT);
        Snapshot shared = describeOne(original);
        assertEquals(OWNER, shared.getOwnerId());
        assertTrue(shared.getTags().isEmpty(), "the owner's tags are not the recipient's");
        assertTrue(ids(describe(List.of())).contains(original));
        assertFalse(ids(service.describeSnapshots(REGION, List.of(), List.of("self"), Map.of())).contains(original));

        String copyId = service.copySnapshot(REGION, REGION, original, null, true, RECIPIENT_KEY,
                List.of(new Tag("copy", "yes"))).getSnapshotId();
        Snapshot copy = describeOne(copyId);
        assertEquals(RECIPIENT, copy.getOwnerId());
        assertTrue(copy.isEncrypted());
        assertEquals(RECIPIENT_KEY, copy.getKmsKeyId());
        assertEquals("vol-ffffffff", copy.getVolumeId());
        assertEquals(8, copy.getVolumeSize());
        assertEquals("[Copied " + original + " from us-east-1]", copy.getDescription());
        assertEquals(Map.of("copy", "yes"), tagMap(copy.getTags()));
        share(copyId, THIRD);

        caller.set(THIRD);
        assertError("InvalidSnapshot.NotFound", () -> restore(original, 0, null));
        Volume restored = restore(copyId, 0, null);
        assertEquals(8, restored.getSize());
        assertTrue(restored.isEncrypted());
        assertEquals(RECIPIENT_KEY, restored.getKmsKeyId());
        assertEquals(copyId, restored.getSnapshotId());
        Snapshot copyOfCopy = describeOne(
                service.copySnapshot(REGION, REGION, copyId, "third", false, null, List.of()).getSnapshotId());
        assertEquals(THIRD, copyOfCopy.getOwnerId());
        assertEquals(DEFAULT_KEY, copyOfCopy.getKmsKeyId(), "a shared source's key is not the copier's to reuse");
    }

    @Test
    void revokingTheGrantHidesTheSnapshotAgain() {
        String snapshotId = snapshot(volume(8, false, null));
        share(snapshotId, RECIPIENT);
        service.modifySnapshotAttribute(REGION, snapshotId, null, List.of(), List.of(), List.of(RECIPIENT),
                List.of(), false);

        caller.set(RECIPIENT);

        assertError("InvalidSnapshot.NotFound", () -> describe(List.of(snapshotId)));
        assertError("InvalidSnapshot.NotFound", () -> restore(snapshotId, 0, null));
    }

    @Test
    void copyPicksItsKeyFromTheRequestTheSourceOrTheRegionDefault() {
        String encrypted = snapshot(volume(8, true, OWNER_KEY));
        String plain = snapshot(volume(8, false, null));

        assertEquals(OWNER_KEY, copyKey(REGION, encrypted, false, null));
        assertEquals(DEFAULT_KEY, copyKey(OTHER_REGION, encrypted, false, null));
        assertEquals(RECIPIENT_KEY, copyKey(OTHER_REGION, encrypted, false, RECIPIENT_KEY));
        assertEquals(DEFAULT_KEY, copyKey(REGION, plain, true, null));
        assertNull(copyKey(REGION, plain, false, null));
        assertError("MissingParameter",
                () -> service.copySnapshot(REGION, null, plain, null, false, null, List.of()));
    }

    @Test
    void contentFlowsFromTheCaptureHookThroughCopiesIntoRestoredVolumes() {
        List<String> captured = new ArrayList<>();
        service.setEbsVolumeContent((account, region, volume, snapshotId) -> {
            captured.add(account + " " + region + " " + volume.getVolumeId() + " " + snapshotId);
            return "content-of-" + volume.getVolumeId();
        });
        Volume source = volume(8, false, null);

        String snapshotId = snapshot(source);
        String copyId = service.copySnapshot(REGION, REGION, snapshotId, null, false, null, List.of())
                .getSnapshotId();
        Volume restored = restore(copyId, 0, null);

        assertEquals(List.of(OWNER + " " + REGION + " " + source.getVolumeId() + " " + snapshotId), captured);
        assertEquals("content-of-" + source.getVolumeId(), describeOne(snapshotId).getContentRef());
        assertEquals("content-of-" + source.getVolumeId(), describeOne(copyId).getContentRef());
        assertEquals("content-of-" + source.getVolumeId(), restored.getSeedContentRef());

        service.setEbsVolumeContent(new DefaultEbsVolumeContent());
        assertEquals("content-of-" + source.getVolumeId(), describeOne(snapshot(restored)).getContentRef(),
                "the default hook carries a restored volume's content into its next snapshot");
        assertNull(describeOne(snapshot(volume(8, false, null))).getContentRef());
    }

    @Test
    void createSnapshotsCoversAttachedVolumesWithExclusionsAndCopiedTags() {
        Instance instance = service.runInstances(REGION, "ami-test",
                "t3.micro", 1, 1, null, List.of(), null, null, List.of(), null, null).getInstances().getFirst();
        instance.setState(InstanceState.running());
        String az = instance.getPlacement().getAvailabilityZone();
        Volume first = service.createVolume(REGION, az, "gp3", 20, false, null, 0, null, null,
                List.of(new Tag("app", "db"), new Tag("Name", "data")));
        Volume second = service.createVolume(REGION, az, "gp3", 30, false, null, 0, null, null, List.of());
        service.attachVolume(REGION, first.getVolumeId(), instance.getInstanceId(), "/dev/sdf");
        service.attachVolume(REGION, second.getVolumeId(), instance.getInstanceId(), "/dev/sdg");
        volume(5, false, null);

        List<Snapshot> all = service.createSnapshots(REGION, instance.getInstanceId(), false, List.of(), "all",
                List.of(new Tag("Name", "backup")), true);

        assertEquals(instance.getRootVolumeId(), all.getFirst().getVolumeId());
        assertEquals(Set.of(instance.getRootVolumeId(), first.getVolumeId(), second.getVolumeId()),
                all.stream().map(Snapshot::getVolumeId).collect(Collectors.toSet()));
        assertTrue(all.stream().allMatch(s -> "pending".equals(s.getState()) && "all".equals(s.getDescription())));
        Snapshot ofFirst = all.stream().filter(s -> first.getVolumeId().equals(s.getVolumeId())).findFirst()
                .orElseThrow();
        assertEquals(Map.of("app", "db", "Name", "backup"), tagMap(ofFirst.getTags()));
        assertEquals(20, describeOne(ofFirst.getSnapshotId()).getVolumeSize());

        List<Snapshot> dataOnly = service.createSnapshots(REGION, instance.getInstanceId(), true,
                List.of(second.getVolumeId()), null, List.of(), false);
        assertEquals(List.of(first.getVolumeId()), dataOnly.stream().map(Snapshot::getVolumeId).toList());
        assertTrue(dataOnly.getFirst().getTags().isEmpty());

        assertError("InvalidInstanceID.NotFound", () -> service.createSnapshots(REGION, "i-0123456789abcdef0",
                false, List.of(), null, List.of(), false));
    }

    @Test
    void describeSnapshotsPagesInIdOrderAndValidatesMaxResults() {
        Volume volume = volume(8, false, null);
        for (int i = 0; i < 7; i++) {
            snapshot(volume);
        }

        Ec2Service.SnapshotPage first = service.describeSnapshots(REGION, List.of(), List.of(), List.of(),
                Map.of(), 5, null);
        Ec2Service.SnapshotPage second = service.describeSnapshots(REGION, List.of(), List.of(), List.of(),
                Map.of(), 5, first.nextToken());

        assertEquals(5, first.snapshots().size());
        assertEquals(2, second.snapshots().size());
        assertNull(second.nextToken());
        List<String> paged = new ArrayList<>(ids(first.snapshots()));
        paged.addAll(ids(second.snapshots()));
        assertEquals(paged.stream().sorted().toList(), paged);
        assertError("InvalidParameterValue", () -> service.describeSnapshots(REGION, List.of(), List.of(),
                List.of(), Map.of(), 4, null));
        assertError("InvalidParameterCombination", () -> service.describeSnapshots(REGION, List.of(paged.getFirst()),
                List.of(), List.of(), Map.of(), 5, null));
    }

    @Test
    void ownerRestorableByAndFiltersSelectByGrant() {
        String sharedWithRecipient = snapshot(volume(8, false, null));
        String madePublic = snapshot(volume(8, false, null));
        String kept = snapshot(volume(8, true, OWNER_KEY));
        share(sharedWithRecipient, RECIPIENT);
        service.modifySnapshotAttribute(REGION, madePublic, null, List.of(), List.of("all"), List.of(), List.of(),
                false);

        assertEquals(Set.of(sharedWithRecipient), restorableBy(List.of(RECIPIENT)));
        assertEquals(Set.of(madePublic), restorableBy(List.of("all")));
        assertEquals(Set.of(kept), Set.copyOf(ids(service.describeSnapshots(REGION, List.of(), List.of("self"),
                Map.of("encrypted", List.of("true"))))));

        caller.set(RECIPIENT);
        assertEquals(Set.of(sharedWithRecipient, madePublic), Set.copyOf(ids(describe(List.of()))));
        assertEquals(Set.of(sharedWithRecipient), restorableBy(List.of("self")));
        assertEquals(Set.of(sharedWithRecipient, madePublic),
                Set.copyOf(ids(service.describeSnapshots(REGION, List.of(), List.of(OWNER), Map.of()))));
        assertTrue(service.describeSnapshots(REGION, List.of(), List.of("self"), Map.of()).isEmpty());
        assertEquals(Set.of(madePublic), Set.copyOf(ids(service.describeSnapshots(REGION, List.of(), List.of(),
                Map.of("snapshot-id", List.of(madePublic), "storage-tier", List.of("standard"))))));
    }

    @Test
    void sharingFollowsTheAwsRules() {
        String withCmk = snapshot(volume(8, true, OWNER_KEY));
        String withDefaultKey = snapshot(volume(8, true, null));
        String plain = snapshot(volume(8, false, null));

        assertError("InvalidParameter", () -> service.modifySnapshotAttribute(REGION, withCmk, null, List.of(),
                List.of("all"), List.of(), List.of(), false));
        assertError("InvalidParameter", () -> share(withDefaultKey, RECIPIENT));
        assertError("InvalidUserID.Malformed", () -> share(plain, "12345"));
        assertError("InvalidParameterValue", () -> service.modifySnapshotAttribute(REGION, plain, null, List.of(),
                List.of("everyone"), List.of(), List.of(), false));
        assertError("InvalidParameterValue", () -> service.modifySnapshotAttribute(REGION, plain, "productCodes",
                List.of(RECIPIENT), List.of(), List.of(), List.of(), false));
        assertError("OperationNotPermitted", () -> service.modifySnapshotAttribute(REGION, plain, null, List.of(),
                List.of("all"), List.of(), List.of(), true));
        share(withCmk, RECIPIENT);
        share(plain, RECIPIENT);

        caller.set(RECIPIENT);
        assertError("InvalidSnapshot.NotFound", () -> share(plain, THIRD));
    }

    @Test
    void describeAndResetSnapshotAttribute() {
        String snapshotId = snapshot(volume(8, false, null));
        share(snapshotId, RECIPIENT);
        service.modifySnapshotAttribute(REGION, snapshotId, null, List.of(), List.of("all"), List.of(), List.of(),
                false);

        Snapshot attribute = service.describeSnapshotAttribute(REGION, snapshotId, "createVolumePermission");
        assertEquals(List.of(RECIPIENT), attribute.getCreateVolumePermissionUserIds());
        assertEquals(List.of("all"), attribute.getCreateVolumePermissionGroups());

        service.resetSnapshotAttribute(REGION, snapshotId, "createVolumePermission");

        Snapshot reset = service.describeSnapshotAttribute(REGION, snapshotId, "createVolumePermission");
        assertTrue(reset.getCreateVolumePermissionUserIds().isEmpty());
        assertTrue(reset.getCreateVolumePermissionGroups().isEmpty());
        assertError("MissingParameter", () -> service.describeSnapshotAttribute(REGION, snapshotId, null));
        assertError("InvalidParameterValue",
                () -> service.describeSnapshotAttribute(REGION, snapshotId, "description"));
        assertError("InvalidParameterValue",
                () -> service.resetSnapshotAttribute(REGION, snapshotId, "productCodes"));
    }

    @Test
    void deleteSnapshotIsOwnerOnlyAndRefusesASnapshotARegisteredImageUses() {
        String snapshotId = snapshot(volume(8, false, null));
        service.createTags(REGION, List.of(snapshotId), List.of(new Tag("Name", "gone")));
        Image image = service.registerImage(REGION, "uses-snapshot", null, null, "/dev/xvda",
                List.of(mapping(snapshotId)));
        share(snapshotId, RECIPIENT);

        assertError("InvalidSnapshot.InUse", () -> service.deleteSnapshot(REGION, snapshotId));
        caller.set(RECIPIENT);
        assertError("InvalidSnapshot.NotFound", () -> service.deleteSnapshot(REGION, snapshotId));

        caller.set(OWNER);
        service.deregisterImage(REGION, image.getImageId(), false);
        service.deleteSnapshot(REGION, snapshotId);

        assertError("InvalidSnapshot.NotFound", () -> describe(List.of(snapshotId)));
        assertTrue(service.describeTags(REGION, Map.of("resource-id", List.of(snapshotId))).isEmpty());
    }

    @Test
    void createVolumeTakesItsSizeAndEncryptionFromTheSnapshot() {
        String snapshotId = snapshot(volume(20, true, OWNER_KEY));

        Volume defaulted = restore(snapshotId, 0, null);
        Volume larger = restore(snapshotId, 50, RECIPIENT_KEY);

        assertEquals(20, defaulted.getSize());
        assertEquals(OWNER_KEY, defaulted.getKmsKeyId());
        assertEquals(50, larger.getSize());
        assertEquals(RECIPIENT_KEY, larger.getKmsKeyId());
        assertError("InvalidParameterValue", () -> restore(snapshotId, 10, null));
        assertError("InvalidSnapshot.NotFound", () -> restore("snap-0123456789abcdef0", 0, null));
        assertEquals(8, volume(0, false, null).getSize());
    }

    @Test
    void snapshotTagsArePerAccountAndVisibleToTagApis() {
        String snapshotId = snapshot(volume(8, false, null));
        share(snapshotId, RECIPIENT);

        service.createTags(REGION, List.of(snapshotId), List.of(new Tag("Name", "owner-name")));
        caller.set(RECIPIENT);
        service.createTags(REGION, List.of(snapshotId), List.of(new Tag("Name", "recipient-name")));
        assertEquals(Map.of("Name", "recipient-name"), tagMap(describeOne(snapshotId).getTags()));

        caller.set(OWNER);
        assertEquals(Map.of("Name", "owner-name"), tagMap(describeOne(snapshotId).getTags()));
        assertEquals(Set.of(snapshotId), Set.copyOf(ids(service.describeSnapshots(REGION, List.of(), List.of(),
                Map.of("tag:Name", List.of("owner-*"))))));
        List<Map<String, String>> described = service.describeTags(REGION,
                Map.of("resource-type", List.of("snapshot")));
        assertTrue(described.stream().anyMatch(tag -> snapshotId.equals(tag.get("resourceId"))
                && "owner-name".equals(tag.get("value"))));

        service.deleteTags(REGION, List.of(snapshotId), List.of(new Tag("Name", null)));
        assertTrue(describeOne(snapshotId).getTags().isEmpty());
    }

    private Volume volume(int size, boolean encrypted, String kmsKeyId) {
        return service.createVolume(REGION, REGION + "a", "gp3", size, encrypted, kmsKeyId, 0, null, null,
                List.of());
    }

    private Volume restore(String snapshotId, int size, String kmsKeyId) {
        return service.createVolume(REGION, REGION + "a", "gp3", size, false, kmsKeyId, 0, null, snapshotId,
                List.of());
    }

    private String snapshot(Volume volume) {
        return service.createSnapshot(REGION, volume.getVolumeId(), null, List.of()).getSnapshotId();
    }

    private void share(String snapshotId, String account) {
        service.modifySnapshotAttribute(REGION, snapshotId, "createVolumePermission", List.of(account), List.of(),
                List.of(), List.of(), false);
    }

    private String copyKey(String region, String sourceSnapshotId, boolean encrypted, String kmsKeyId) {
        String copyId = service.copySnapshot(region, REGION, sourceSnapshotId, null, encrypted, kmsKeyId,
                List.of()).getSnapshotId();
        return service.describeSnapshots(region, List.of(copyId), List.of(), Map.of()).getFirst().getKmsKeyId();
    }

    private List<Snapshot> describe(List<String> snapshotIds) {
        return service.describeSnapshots(REGION, snapshotIds, List.of(), Map.of());
    }

    private Snapshot describeOne(String snapshotId) {
        List<Snapshot> found = describe(List.of(snapshotId));
        assertEquals(1, found.size());
        return found.getFirst();
    }

    private Set<String> restorableBy(List<String> accounts) {
        return Set.copyOf(ids(service.describeSnapshots(REGION, List.of(), List.of(), accounts, Map.of(), null, null)
                .snapshots()));
    }

    private static Map<String, String> tagMap(List<Tag> tags) {
        return tags.stream().collect(Collectors.toMap(Tag::getKey, Tag::getValue));
    }

    private static List<String> ids(List<Snapshot> snapshots) {
        return snapshots.stream().map(Snapshot::getSnapshotId).toList();
    }

    private static BlockDeviceMapping mapping(String snapshotId) {
        EbsBlockDevice ebs = new EbsBlockDevice();
        ebs.setSnapshotId(snapshotId);
        ebs.setVolumeSize(8);
        BlockDeviceMapping mapping = new BlockDeviceMapping();
        mapping.setDeviceName("/dev/xvda");
        mapping.setEbs(ebs);
        return mapping;
    }

    private static void assertError(String code, Executable call) {
        AwsException error = assertThrows(AwsException.class, call);
        assertEquals(code, error.getErrorCode(), error.getMessage());
    }

    @SuppressWarnings("unchecked")
    private Ec2Service newService() {
        RequestContext requestContext = mock(RequestContext.class);
        when(requestContext.getAccountId()).thenAnswer(invocation -> caller.get());
        jakarta.enterprise.inject.Instance<RequestContext> contextInstance =
                mock(jakarta.enterprise.inject.Instance.class);
        when(contextInstance.get()).thenReturn(requestContext);

        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.Ec2ServiceConfig ec2 = mock(EmulatorConfig.Ec2ServiceConfig.class);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(config.services()).thenReturn(services);
        when(services.ec2()).thenReturn(ec2);
        when(ec2.mock()).thenReturn(true);

        return new Ec2Service(config, mock(Ec2ContainerManager.class), mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new AccountAwareStorageFactory(contextInstance), contextInstance);
    }

    private static final class AccountAwareStorageFactory extends StorageFactory {
        private final jakarta.enterprise.inject.Instance<RequestContext> requestContextInstance;

        private AccountAwareStorageFactory(jakarta.enterprise.inject.Instance<RequestContext> requestContextInstance) {
            super(null, null);
            this.requestContextInstance = requestContextInstance;
        }

        @Override
        public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                    TypeReference<Map<String, V>> typeReference) {
            return new AccountAwareStorageBackend<>(new InMemoryStorage<>(), requestContextInstance, "000000000000");
        }
    }
}
