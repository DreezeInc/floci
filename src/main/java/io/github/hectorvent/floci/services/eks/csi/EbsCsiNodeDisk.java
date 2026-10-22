package io.github.hectorvent.floci.services.eks.csi;

import java.nio.file.Path;

/** The directories inside a node container that hold the data of its EBS volumes. */
public interface EbsCsiNodeDisk {

    /**
     * Bind-mounts the volume's directory at {@code targetPath}, creating the directory on first use
     * and filling it from {@code seed}, a tar archive, when the volume was created from a snapshot.
     */
    void publish(EbsCsiNode node, String volumeId, String targetPath, boolean readOnly, Path seed);

    void unpublish(EbsCsiNode node, String targetPath);

    void delete(EbsCsiNode node, String volumeId);

    boolean holds(EbsCsiNode node, String volumeId);

    /** Writes the volume directory's contents, as of one instant, to {@code archive} as a tar. */
    void capture(EbsCsiNode node, String volumeId, Path archive);
}
