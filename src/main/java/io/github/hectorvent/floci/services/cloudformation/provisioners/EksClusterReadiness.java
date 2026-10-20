package io.github.hectorvent.floci.services.cloudformation.provisioners;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.eks.EksService;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ClusterStatus;

import java.time.Duration;

/**
 * A real-mode cluster boots (and, on its first nodegroup, reboots) a k3s container and is
 * CREATING meanwhile. CloudFormation never lets a dependant touch a cluster in that state, so
 * every EKS provisioner reads the cluster through here and waits for ACTIVE first.
 */
final class EksClusterReadiness {

    static final Duration ACTIVE_TIMEOUT = Duration.ofMinutes(15);
    static final Duration ACTIVE_POLL = Duration.ofSeconds(3);

    private EksClusterReadiness() {
    }

    static Cluster awaitActive(EksService eksService, String name) {
        return awaitActive(eksService, name, ACTIVE_TIMEOUT, ACTIVE_POLL);
    }

    static Cluster awaitActive(EksService eksService, String name, Duration timeout, Duration poll) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            Cluster current = eksService.describeCluster(name);
            if (current == null || current.getStatus() != ClusterStatus.CREATING) {
                if (current != null && current.getStatus() == ClusterStatus.FAILED) {
                    throw new AwsException("ResourceNotReady", "Cluster " + name + " did not become ACTIVE: "
                            + current.getStatus(), 400);
                }
                return current;
            }
            if (System.nanoTime() - deadline >= 0) {
                throw new AwsException("ResourceNotReady", "Cluster " + name + " was still CREATING after "
                        + timeout.toSeconds() + " seconds", 400);
            }
            try {
                Thread.sleep(poll.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AwsException("ResourceNotReady", "Interrupted while waiting for cluster " + name, 400);
            }
        }
    }
}
