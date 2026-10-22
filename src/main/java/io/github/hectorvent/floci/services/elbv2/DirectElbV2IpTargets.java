package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.services.elbv2.model.TargetGroup;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Connects to every {@code ip} target at the address and port it was registered with.
 */
@DefaultBean
@ApplicationScoped
public class DirectElbV2IpTargets implements ElbV2IpTargets {

    @Override
    public Route reach(TargetGroup targetGroup, String ip, int port) {
        return new Route(ip, port);
    }
}
