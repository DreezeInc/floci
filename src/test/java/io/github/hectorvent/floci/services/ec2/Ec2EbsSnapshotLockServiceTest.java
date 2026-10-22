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
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Ec2EbsSnapshotLockServiceTest {

    private static final String REGION = "us-east-1";
    private static final String OWNER = "111111111111";
    private static final String RECIPIENT = "222222222222";
    private static final Instant T0 = Instant.parse("2026-03-01T00:00:00Z");

    private final AtomicReference<String> caller = new AtomicReference<>(OWNER);
    private Ec2Service service;

    @BeforeEach
    void setUp() {
        service = newService();
        at(T0);
    }

    @Test
    void governanceLockWithADurationRefusesDeleteUntilItExpires() {
        String snapshotId = snapshot();

        Ec2Service.LockedSnapshot locked = service.lockSnapshot(REGION, snapshotId, "governance", 2, null, null);

        assertEquals(snapshotId, locked.snapshotId());
        assertEquals(OWNER, locked.ownerId());
        assertEquals("governance", locked.lockState());
        assertEquals(2, locked.lock().getLockDuration());
        assertNull(locked.lock().getCoolOffPeriod());
        assertNull(locked.lock().getCoolOffPeriodExpiresOn());
        assertEquals(T0, locked.lock().getLockCreatedOn());
        assertEquals(T0, locked.lock().getLockDurationStartTime());
        assertEquals(T0.plus(Duration.ofDays(2)), locked.lock().getLockExpiresOn());
        assertError("SnapshotLocked", () -> service.deleteSnapshot(REGION, snapshotId));

        at(T0.plus(Duration.ofDays(2)).minusSeconds(1));
        assertError("SnapshotLocked", () -> service.deleteSnapshot(REGION, snapshotId));

        at(T0.plus(Duration.ofDays(2)));
        assertEquals("expired", describeOne(snapshotId).lockState());
        service.deleteSnapshot(REGION, snapshotId);
        assertError("InvalidSnapshot.NotFound", () -> service.unlockSnapshot(REGION, snapshotId));
    }

    @Test
    void governanceLockWithAnExpirationDateCanBeShortenedMadeComplianceOrUnlocked() {
        String snapshotId = snapshot();
        Instant expiresOn = T0.plus(Duration.ofMinutes(90));

        Ec2Service.LockedSnapshot locked = service.lockSnapshot(REGION, snapshotId, "governance", null, expiresOn,
                null);
        assertNull(locked.lock().getLockDuration());
        assertEquals(expiresOn, locked.lock().getLockExpiresOn());

        at(T0.plus(Duration.ofMinutes(10)));
        Ec2Service.LockedSnapshot shortened = service.lockSnapshot(REGION, snapshotId, "governance", null,
                T0.plus(Duration.ofMinutes(30)), null);
        assertEquals(T0.plus(Duration.ofMinutes(30)), shortened.lock().getLockExpiresOn());
        assertEquals(T0, shortened.lock().getLockCreatedOn());
        assertEquals(T0.plus(Duration.ofMinutes(10)), shortened.lock().getLockDurationStartTime());

        Ec2Service.LockedSnapshot compliance = service.lockSnapshot(REGION, snapshotId, "compliance", 1, null, 1);
        assertEquals("compliance-cooloff", compliance.lockState());
        assertEquals(T0.plus(Duration.ofMinutes(70)), compliance.lock().getCoolOffPeriodExpiresOn());

        Ec2Service.LockedSnapshot backToGovernance = service.lockSnapshot(REGION, snapshotId, "governance", 1,
                null, null);
        assertEquals("governance", backToGovernance.lockState());
        assertNull(backToGovernance.lock().getCoolOffPeriod());

        service.unlockSnapshot(REGION, snapshotId);
        assertError("SnapshotLockNotFound", () -> describe(List.of(snapshotId)));
        service.deleteSnapshot(REGION, snapshotId);
    }

    @Test
    void complianceLockCanChangeDuringCoolOffAndOnlyExtendAfterIt() {
        String snapshotId = snapshot();

        Ec2Service.LockedSnapshot locked = service.lockSnapshot(REGION, snapshotId, "compliance", 30, null, 24);
        assertEquals("compliance-cooloff", locked.lockState());
        assertEquals(24, locked.lock().getCoolOffPeriod());
        assertEquals(T0.plus(Duration.ofHours(24)), locked.lock().getCoolOffPeriodExpiresOn());
        assertError("SnapshotLocked", () -> service.deleteSnapshot(REGION, snapshotId));

        at(T0.plus(Duration.ofHours(1)));
        Ec2Service.LockedSnapshot changed = service.lockSnapshot(REGION, snapshotId, "compliance", 10, null, 48);
        assertEquals("compliance-cooloff", changed.lockState());
        assertEquals(T0.plus(Duration.ofHours(48)), changed.lock().getCoolOffPeriodExpiresOn());
        Instant shortenedExpiry = T0.plus(Duration.ofHours(1)).plus(Duration.ofDays(10));
        assertEquals(shortenedExpiry, changed.lock().getLockExpiresOn());

        at(T0.plus(Duration.ofHours(48)));
        assertEquals("compliance", describeOne(snapshotId).lockState());
        assertError("SnapshotLocked", () -> service.unlockSnapshot(REGION, snapshotId));
        assertError("SnapshotLocked", () -> service.deleteSnapshot(REGION, snapshotId));
        assertError("SnapshotLocked",
                () -> service.lockSnapshot(REGION, snapshotId, "governance", 30, null, null));
        assertError("SnapshotLocked",
                () -> service.lockSnapshot(REGION, snapshotId, "compliance", 1, null, null));
        assertError("SnapshotLocked",
                () -> service.lockSnapshot(REGION, snapshotId, "compliance", 30, null, 1));

        Ec2Service.LockedSnapshot extended = service.lockSnapshot(REGION, snapshotId, "compliance", 20, null, null);
        assertEquals("compliance", extended.lockState());
        assertEquals(T0, extended.lock().getLockCreatedOn());
        assertEquals(48, extended.lock().getCoolOffPeriod());
        assertEquals(T0.plus(Duration.ofHours(48)), extended.lock().getCoolOffPeriodExpiresOn());
        Instant extendedExpiry = T0.plus(Duration.ofHours(48)).plus(Duration.ofDays(20));
        assertEquals(extendedExpiry, extended.lock().getLockExpiresOn());
        service.lockSnapshot(REGION, snapshotId, "compliance", null, extendedExpiry, null);

        at(extendedExpiry);
        assertEquals("expired", describeOne(snapshotId).lockState());
        service.deleteSnapshot(REGION, snapshotId);
    }

    @Test
    void complianceLockWithoutACoolOffPeriodIsFinalAtOnce() {
        String snapshotId = snapshot();

        Ec2Service.LockedSnapshot locked = service.lockSnapshot(REGION, snapshotId, "compliance", 1, null, null);

        assertEquals("compliance", locked.lockState());
        assertNull(locked.lock().getCoolOffPeriod());
        assertNull(locked.lock().getCoolOffPeriodExpiresOn());
        assertError("SnapshotLocked", () -> service.unlockSnapshot(REGION, snapshotId));
    }

    @Test
    void complianceLockCanBeUnlockedDuringItsCoolOffPeriod() {
        String snapshotId = snapshot();
        service.lockSnapshot(REGION, snapshotId, "compliance", 7, null, 1);

        at(T0.plus(Duration.ofMinutes(59)));
        service.unlockSnapshot(REGION, snapshotId);

        assertError("SnapshotLockNotFound", () -> service.unlockSnapshot(REGION, snapshotId));
        service.deleteSnapshot(REGION, snapshotId);
    }

    @Test
    void expiredLockIsReplacedByANewOne() {
        String snapshotId = snapshot();
        service.lockSnapshot(REGION, snapshotId, "compliance", 1, null, null);

        at(T0.plus(Duration.ofDays(2)));
        Ec2Service.LockedSnapshot relocked = service.lockSnapshot(REGION, snapshotId, "governance", 1, null, null);

        assertEquals("governance", relocked.lockState());
        assertEquals(T0.plus(Duration.ofDays(2)), relocked.lock().getLockCreatedOn());
        service.unlockSnapshot(REGION, snapshotId);
    }

    @Test
    void lockRequestsAreValidated() {
        String snapshotId = snapshot();
        Instant future = T0.plus(Duration.ofDays(1));

        assertError("MissingParameter", () -> service.lockSnapshot(REGION, null, "governance", 1, null, null));
        assertError("MissingParameter", () -> service.lockSnapshot(REGION, snapshotId, null, 1, null, null));
        assertError("InvalidParameterValue",
                () -> service.lockSnapshot(REGION, snapshotId, "legal-hold", 1, null, null));
        assertError("InvalidParameterCombination",
                () -> service.lockSnapshot(REGION, snapshotId, "governance", null, null, null));
        assertError("InvalidParameterCombination",
                () -> service.lockSnapshot(REGION, snapshotId, "governance", 1, future, null));
        assertError("InvalidParameterValue",
                () -> service.lockSnapshot(REGION, snapshotId, "governance", 0, null, null));
        assertError("InvalidParameterValue",
                () -> service.lockSnapshot(REGION, snapshotId, "governance", 36501, null, null));
        assertError("InvalidParameterValue",
                () -> service.lockSnapshot(REGION, snapshotId, "governance", null, T0, null));
        assertError("InvalidParameterCombination",
                () -> service.lockSnapshot(REGION, snapshotId, "governance", 1, null, 1));
        assertError("InvalidParameterValue",
                () -> service.lockSnapshot(REGION, snapshotId, "compliance", 1, null, 0));
        assertError("InvalidParameterValue",
                () -> service.lockSnapshot(REGION, snapshotId, "compliance", 1, null, 73));
        assertError("InvalidSnapshot.NotFound",
                () -> service.lockSnapshot(REGION, "snap-0123456789abcdef0", "governance", 1, null, null));
        assertError("SnapshotLockNotFound", () -> service.unlockSnapshot(REGION, snapshotId));

        assertEquals(T0.plus(Duration.ofDays(36500)),
                service.lockSnapshot(REGION, snapshotId, "compliance", 36500, null, 72).lock().getLockExpiresOn());
    }

    @Test
    void describeLockedSnapshotsFiltersByLockStateAndPages() {
        String governance = snapshot();
        String coolingOff = snapshot();
        String compliance = snapshot();
        String expired = snapshot();
        String unlocked = snapshot();
        service.lockSnapshot(REGION, governance, "governance", 5, null, null);
        service.lockSnapshot(REGION, coolingOff, "compliance", 5, null, 72);
        service.lockSnapshot(REGION, compliance, "compliance", 5, null, null);
        service.lockSnapshot(REGION, expired, "governance", null, T0.plusSeconds(60), null);
        at(T0.plusSeconds(60));

        assertEquals(Map.of(governance, "governance", coolingOff, "compliance-cooloff",
                compliance, "compliance", expired, "expired"), states(describe(List.of())));
        assertEquals(Set.of(coolingOff), ids(describe(Map.of("lock-state", List.of("compliance-cooloff")))));
        assertEquals(Set.of(governance, expired),
                ids(describe(Map.of("lock-state", List.of("governance", "expired")))));
        assertEquals(Set.of(), ids(service.describeLockedSnapshots(REGION, List.of(governance),
                Map.of("lock-state", List.of("compliance")), null, null).snapshots()));
        assertEquals(Set.of(governance, compliance), ids(describe(List.of(governance, compliance))));
        assertError("SnapshotLockNotFound", () -> describe(List.of(governance, unlocked)));
        assertError("InvalidSnapshot.NotFound", () -> describe(List.of("snap-0123456789abcdef0")));
        assertError("InvalidParameterValue", () -> describe(Map.of("lock-mode", List.of("governance"))));
        assertError("InvalidParameterValue",
                () -> service.describeLockedSnapshots(REGION, List.of(), Map.of(), 4, null));

        List<String> more = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            more.add(snapshot());
            service.lockSnapshot(REGION, more.getLast(), "governance", 1, null, null);
        }
        Ec2Service.LockedSnapshotPage first = service.describeLockedSnapshots(REGION, List.of(), Map.of(), 5, null);
        Ec2Service.LockedSnapshotPage second = service.describeLockedSnapshots(REGION, List.of(), Map.of(), 5,
                first.nextToken());
        assertEquals(5, first.snapshots().size());
        assertEquals(2, second.snapshots().size());
        assertNull(second.nextToken());
        Set<String> paged = new HashSet<>(ids(first.snapshots()));
        paged.addAll(ids(second.snapshots()));
        assertEquals(7, paged.size());
    }

    @Test
    void locksBelongToTheOwningAccount() {
        String snapshotId = snapshot();
        service.lockSnapshot(REGION, snapshotId, "governance", 1, null, null);
        service.modifySnapshotAttribute(REGION, snapshotId, "createVolumePermission", List.of(RECIPIENT),
                List.of(), List.of(), List.of(), false);

        caller.set(RECIPIENT);

        assertTrue(describe(List.of()).isEmpty());
        assertError("InvalidSnapshot.NotFound", () -> describe(List.of(snapshotId)));
        assertError("InvalidSnapshot.NotFound",
                () -> service.lockSnapshot(REGION, snapshotId, "governance", 1, null, null));
        assertError("InvalidSnapshot.NotFound", () -> service.unlockSnapshot(REGION, snapshotId));
        assertTrue(service.describeLockedSnapshots("us-west-2", List.of(), Map.of(), null, null)
                .snapshots().isEmpty());
    }

    @Test
    void deregisterImageKeepsALockedSnapshotAndReportsAClientError() {
        String snapshotId = snapshot();
        Image image = service.registerImage(REGION, "locked-backing", null, null, "/dev/xvda",
                List.of(mapping(snapshotId)));
        service.lockSnapshot(REGION, snapshotId, "governance", 1, null, null);

        List<Ec2Service.SnapshotDeletion> deletions = service.deregisterImage(REGION, image.getImageId(), true);

        assertEquals(List.of(new Ec2Service.SnapshotDeletion(snapshotId, "client-error")), deletions);
        assertEquals("governance", describeOne(snapshotId).lockState());
        service.unlockSnapshot(REGION, snapshotId);
        service.deleteSnapshot(REGION, snapshotId);
    }

    private void at(Instant instant) {
        service.setClock(Clock.fixed(instant, ZoneOffset.UTC));
    }

    private String snapshot() {
        String volumeId = service.createVolume(REGION, REGION + "a", "gp3", 8, false, null, 0, null, null,
                List.of()).getVolumeId();
        return service.createSnapshot(REGION, volumeId, null, List.of()).getSnapshotId();
    }

    private List<Ec2Service.LockedSnapshot> describe(List<String> snapshotIds) {
        return service.describeLockedSnapshots(REGION, snapshotIds, Map.of(), null, null).snapshots();
    }

    private List<Ec2Service.LockedSnapshot> describe(Map<String, List<String>> filters) {
        return service.describeLockedSnapshots(REGION, List.of(), filters, null, null).snapshots();
    }

    private Ec2Service.LockedSnapshot describeOne(String snapshotId) {
        List<Ec2Service.LockedSnapshot> found = describe(List.of(snapshotId));
        assertEquals(1, found.size());
        return found.getFirst();
    }

    private static Set<String> ids(List<Ec2Service.LockedSnapshot> snapshots) {
        return snapshots.stream().map(Ec2Service.LockedSnapshot::snapshotId).collect(Collectors.toSet());
    }

    private static Map<String, String> states(List<Ec2Service.LockedSnapshot> snapshots) {
        return snapshots.stream().collect(Collectors.toMap(Ec2Service.LockedSnapshot::snapshotId,
                Ec2Service.LockedSnapshot::lockState));
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
        Instance<RequestContext> contextInstance = mock(Instance.class);
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
        private final Instance<RequestContext> requestContextInstance;

        private AccountAwareStorageFactory(Instance<RequestContext> requestContextInstance) {
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
