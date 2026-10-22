package io.github.hectorvent.floci.services.eks.csi;

import io.github.hectorvent.floci.services.ec2.model.Volume;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EbsCsiVolumeContentTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final EbsCsiNode NODE = new EbsCsiNode("demo", ACCOUNT, REGION, "us-east-1a", "container-1",
            "i-0123456789abcdef0");
    private static final EbsCsiNode OTHER_ACCOUNT_NODE = new EbsCsiNode("demo", "123456789012", REGION,
            "us-east-1a", "container-2", "i-0fedcba9876543210");

    @TempDir
    Path archives;

    @Test
    void aPublishedVolumeIsCapturedFromTheNodeThatHoldsIt() {
        CapturingDisk disk = new CapturingDisk(Set.of("vol-1"));
        EbsCsiVolumeContent content = new EbsCsiVolumeContent(nodes(OTHER_ACCOUNT_NODE, NODE), disk, archives);

        String ref = content.capture(ACCOUNT, REGION, volume("vol-1", "/data/earlier.tar"), "snap-1");

        assertEquals(archives.resolve("snap-1.tar").toAbsolutePath().toString(), ref);
        assertEquals(List.of("container-1 vol-1 " + ref), disk.captured);
    }

    @Test
    void aVolumeNoNodeHoldsPassesOnItsSeed() {
        CapturingDisk disk = new CapturingDisk(Set.of());
        EbsCsiVolumeContent content = new EbsCsiVolumeContent(nodes(NODE), disk, archives);

        assertEquals("/data/earlier.tar",
                content.capture(ACCOUNT, REGION, volume("vol-1", "/data/earlier.tar"), "snap-1"));
        assertNull(content.capture(ACCOUNT, REGION, volume("vol-2", null), "snap-2"));
        assertTrue(disk.captured.isEmpty());
    }

    @Test
    void aNodeOfAnotherAccountOrRegionIsNotAsked() {
        CapturingDisk disk = new CapturingDisk(Set.of("vol-1"));
        EbsCsiVolumeContent content = new EbsCsiVolumeContent(nodes(OTHER_ACCOUNT_NODE), disk, archives);

        assertNull(content.capture(ACCOUNT, REGION, volume("vol-1", null), "snap-1"));
        assertNull(new EbsCsiVolumeContent(nodes(NODE), disk, archives)
                .capture(ACCOUNT, "eu-west-1", volume("vol-1", null), "snap-1"));
        assertTrue(disk.captured.isEmpty());
    }

    @Test
    void theSeedOfAVolumeIsTheArchiveItWasRestoredFrom() {
        assertEquals(Path.of("/data/snap-1.tar"), EbsCsiVolumeContent.seedOf(volume("vol-1", "/data/snap-1.tar")));
        assertNull(EbsCsiVolumeContent.seedOf(volume("vol-2", null)));
    }

    private static Volume volume(String volumeId, String seedContentRef) {
        Volume volume = new Volume();
        volume.setVolumeId(volumeId);
        volume.setSeedContentRef(seedContentRef);
        return volume;
    }

    private static EbsCsiNodes nodes(EbsCsiNode... nodes) {
        return new EbsCsiNodes() {
            @Override
            public Optional<EbsCsiNode> nodeAt(String address) {
                return Optional.empty();
            }

            @Override
            public List<EbsCsiNode> nodes() {
                return List.of(nodes);
            }
        };
    }

    private static final class CapturingDisk implements EbsCsiNodeDisk {

        final List<String> captured = new ArrayList<>();
        private final Set<String> held;

        CapturingDisk(Set<String> held) {
            this.held = held;
        }

        @Override
        public void publish(EbsCsiNode node, String volumeId, String targetPath, boolean readOnly, Path seed) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void unpublish(EbsCsiNode node, String targetPath) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(EbsCsiNode node, String volumeId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean holds(EbsCsiNode node, String volumeId) {
            return node.accountId().equals(ACCOUNT) && held.contains(volumeId);
        }

        @Override
        public void capture(EbsCsiNode node, String volumeId, Path archive) {
            captured.add(node.containerId() + " " + volumeId + " " + archive);
        }
    }
}
