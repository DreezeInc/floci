package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.services.elbv2.model.TargetGroup;

/**
 * Decides where Floci connects to reach an {@code ip} target. Some target addresses sit on a
 * network that Floci cannot route to directly, for example a pod network inside a cluster container.
 */
public interface ElbV2IpTargets {

    /** The address and port Floci connects to. */
    record Route(String host, int port) {}

    /**
     * The route to {@code ip:port}, a target registered in {@code targetGroup}. This may block
     * while a forward to the target is set up, so call it off the event loop.
     */
    Route reach(TargetGroup targetGroup, String ip, int port);
}
