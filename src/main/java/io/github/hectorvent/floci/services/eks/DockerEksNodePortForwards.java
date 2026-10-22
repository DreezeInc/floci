package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Forwards node ports with iptables DNAT rules in the node container's own network namespace,
 * which routes the pod CIDR. Replies return through the same connection tracking entry.
 */
@ApplicationScoped
public class DockerEksNodePortForwards implements EksNodePortForwards {

    static final String CHAIN = "FLOCI-ELB-TARGETS";
    private static final int EXEC_TIMEOUT_SECONDS = 15;

    // The script takes every value as a positional parameter, so none is ever parsed as shell text.
    static final String FORWARD_SCRIPT = """
            set -eu
            IPT=$(command -v iptables 2>/dev/null || command -v /bin/aux/iptables 2>/dev/null || true)
            if [ -z "$IPT" ]; then
              echo 'iptables not found in container' >&2
              exit 1
            fi
            $IPT -t nat -N %1$s 2>/dev/null || true
            if [ "$4" = reset ]; then $IPT -t nat -F %1$s; fi
            $IPT -t nat -C PREROUTING -j %1$s 2>/dev/null || $IPT -t nat -I PREROUTING 1 -j %1$s
            $IPT -t nat -A %1$s -d "$1" -p tcp --dport "$2" -j DNAT --to-destination "$3"
            """.formatted(CHAIN);

    private final ContainerLifecycleManager lifecycleManager;

    @Inject
    public DockerEksNodePortForwards(ContainerLifecycleManager lifecycleManager) {
        this.lifecycleManager = lifecycleManager;
    }

    @Override
    public void forward(EksPodNetwork network, int nodePort, String podAddress, int podPort, boolean reset) {
        ContainerExec.Result result = ContainerExec.runMerged(lifecycleManager.getDockerClient(),
                network.containerId(), command(network, nodePort, podAddress, podPort, reset), EXEC_TIMEOUT_SECONDS)
                .throwIfTimedOut(network.containerId());
        if (result.exitCode() != 0) {
            throw new IllegalStateException("could not forward node port " + nodePort + " to "
                    + podAddress + ":" + podPort + " in container " + network.containerId() + ": "
                    + result.summary());
        }
    }

    static String[] command(EksPodNetwork network, int nodePort, String podAddress, int podPort, boolean reset) {
        return new String[]{"sh", "-c", FORWARD_SCRIPT, "sh", network.nodeAddress(), String.valueOf(nodePort),
                podAddress + ":" + podPort, reset ? "reset" : "keep"};
    }
}
