package io.github.hectorvent.floci.services.eks.csi;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Keeps each volume's data in its own directory of the node container, which pods see through a
 * bind mount, and moves that data in and out of the container as tar archives.
 */
@ApplicationScoped
public class DockerEbsCsiNodeDisk implements EbsCsiNodeDisk {

    static final String VOLUME_ROOT = "/var/lib/rancher/k3s/storage/floci-ebs-csi";
    private static final int EXEC_TIMEOUT_SECONDS = 60;

    // The scripts take every path as a positional parameter, so none is ever parsed as shell text.
    static final String PUBLISH_SCRIPT = """
            set -eu
            mkdir -p "$2"
            if ! grep -qs " $2 " /proc/mounts; then mount --bind "$1" "$2"; fi
            if [ "$3" = ro ]; then mount -o remount,bind,ro "$2"; fi
            """;
    static final String UNPUBLISH_SCRIPT = """
            set -eu
            if grep -qs " $1 " /proc/mounts; then umount "$1"; fi
            if [ -e "$1" ]; then rmdir "$1"; fi
            """;
    static final String STAGE_SCRIPT = """
            set -eu
            rm -rf "$1"
            mkdir -p -m 0777 "$1"
            """;

    private final ContainerLifecycleManager lifecycleManager;

    @Inject
    public DockerEbsCsiNodeDisk(ContainerLifecycleManager lifecycleManager) {
        this.lifecycleManager = lifecycleManager;
    }

    static String volumeDir(String volumeId) {
        return VOLUME_ROOT + "/" + volumeId;
    }

    @Override
    public void publish(EbsCsiNode node, String volumeId, String targetPath, boolean readOnly, Path seed) {
        if (!holds(node, volumeId)) {
            create(node, volumeId, seed);
        }
        run(node, "mount volume " + volumeId + " at " + targetPath,
                "sh", "-c", PUBLISH_SCRIPT, "sh", volumeDir(volumeId), targetPath, readOnly ? "ro" : "rw");
    }

    // The data lands in a staging directory first, so a seed cut short is never taken for the volume.
    private void create(EbsCsiNode node, String volumeId, Path seed) {
        String staging = volumeDir(volumeId) + ".new";
        run(node, "stage volume " + volumeId, "sh", "-c", STAGE_SCRIPT, "sh", staging);
        if (seed != null) {
            try (InputStream tar = Files.newInputStream(seed)) {
                docker().copyArchiveToContainerCmd(node.containerId())
                        .withTarInputStream(tar)
                        .withRemotePath(staging)
                        .exec();
            } catch (IOException e) {
                throw new UncheckedIOException("could not read the data of volume " + volumeId + " from " + seed, e);
            }
        }
        run(node, "create volume " + volumeId, "mv", staging, volumeDir(volumeId));
    }

    @Override
    public void unpublish(EbsCsiNode node, String targetPath) {
        run(node, "unmount " + targetPath, "sh", "-c", UNPUBLISH_SCRIPT, "sh", targetPath);
    }

    @Override
    public void delete(EbsCsiNode node, String volumeId) {
        run(node, "delete volume " + volumeId, "rm", "-rf", volumeDir(volumeId), volumeDir(volumeId) + ".new");
    }

    @Override
    public boolean holds(EbsCsiNode node, String volumeId) {
        ContainerExec.Result result = exec(node, "test", "-d", volumeDir(volumeId));
        return result.exitCode() == 0;
    }

    // Pausing the node freezes every writer, so the copy is consistent the way an EBS snapshot is.
    @Override
    public void capture(EbsCsiNode node, String volumeId, Path archive) {
        DockerClient docker = docker();
        try {
            Files.createDirectories(archive.getParent());
            docker.pauseContainerCmd(node.containerId()).exec();
            try (InputStream tar = docker.copyArchiveFromContainerCmd(node.containerId(),
                    volumeDir(volumeId) + "/.").exec()) {
                Files.copy(tar, archive, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                docker.unpauseContainerCmd(node.containerId()).exec();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not copy the data of volume " + volumeId + " to " + archive, e);
        }
    }

    private void run(EbsCsiNode node, String action, String... cmd) {
        ContainerExec.Result result = exec(node, cmd);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("could not " + action + " on the node of cluster "
                    + node.clusterName() + ": " + result.summary());
        }
    }

    private ContainerExec.Result exec(EbsCsiNode node, String... cmd) {
        return ContainerExec.run(docker(), node.containerId(), cmd, EXEC_TIMEOUT_SECONDS)
                .throwIfTimedOut(node.containerId());
    }

    private DockerClient docker() {
        return lifecycleManager.getDockerClient();
    }
}
