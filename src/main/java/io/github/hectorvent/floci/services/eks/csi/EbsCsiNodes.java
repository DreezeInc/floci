package io.github.hectorvent.floci.services.eks.csi;

import java.util.List;
import java.util.Optional;

/** The cluster nodes the EBS CSI driver serves. */
public interface EbsCsiNodes {

    /**
     * The node whose container answers on {@code address}. Every CSI call reaches Floci from a
     * node's own network namespace, so its source address names the cluster and account it acts for.
     */
    Optional<EbsCsiNode> nodeAt(String address);

    List<EbsCsiNode> nodes();
}
