package io.github.hectorvent.floci.services.eks.csi;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.ec2.EbsVolumeContent;
import io.github.hectorvent.floci.services.ec2.model.Volume;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * A snapshot of a volume the EBS CSI driver has published holds a tar of the volume's directory on
 * the node; any other volume passes on the content it was restored from.
 */
@ApplicationScoped
public class EbsCsiVolumeContent implements EbsVolumeContent {

    private final EbsCsiNodes nodes;
    private final EbsCsiNodeDisk disk;
    private final Path archives;

    @Inject
    public EbsCsiVolumeContent(EmulatorConfig config, EbsCsiNodes nodes, EbsCsiNodeDisk disk) {
        this(nodes, disk, Paths.get(config.services().eks().dataPath(), "ebs-snapshots"));
    }

    EbsCsiVolumeContent(EbsCsiNodes nodes, EbsCsiNodeDisk disk, Path archives) {
        this.nodes = nodes;
        this.disk = disk;
        this.archives = archives;
    }

    @Override
    public String capture(String accountId, String region, Volume volume, String snapshotId) {
        Optional<EbsCsiNode> holder = nodes.nodes().stream()
                .filter(node -> node.accountId().equals(accountId) && node.region().equals(region))
                .filter(node -> disk.holds(node, volume.getVolumeId()))
                .findFirst();
        if (holder.isEmpty()) {
            return volume.getSeedContentRef();
        }
        Path archive = archives.resolve(snapshotId + ".tar").toAbsolutePath();
        disk.capture(holder.get(), volume.getVolumeId(), archive);
        return archive.toString();
    }

    static Path seedOf(Volume volume) {
        String ref = volume.getSeedContentRef();
        return ref == null ? null : Path.of(ref);
    }
}
