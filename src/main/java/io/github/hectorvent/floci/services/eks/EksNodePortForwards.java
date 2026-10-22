package io.github.hectorvent.floci.services.eks;

/** Forwards ports of a cluster's node container to pod addresses behind it. */
public interface EksNodePortForwards {

    /**
     * Forwards TCP traffic for {@code nodePort} on the node address to {@code podAddress:podPort}.
     * With {@code reset}, the forwards that the node held before are removed first.
     */
    void forward(EksPodNetwork network, int nodePort, String podAddress, int podPort, boolean reset);
}
