package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.services.eks.model.Cluster;

import java.util.List;

/** What the in-tree cloud provider reads from and writes to a running cluster for its load balancers. */
public interface EksClusterServices {

    List<EksLoadBalancerService> loadBalancerServices(Cluster cluster);

    /** The instance ids of the cluster's Ready nodes that may receive load balancer traffic. */
    List<String> loadBalancerNodeInstanceIds(Cluster cluster);

    /** Replaces the Service's finalizers, failing when the Service changed since it was read. */
    void setFinalizers(Cluster cluster, EksLoadBalancerService service, List<String> finalizers);

    void publishHostname(Cluster cluster, EksLoadBalancerService service, String hostname);
}
