package io.github.hectorvent.floci.services.elbv2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class AvailabilityZone {

    private String subnetId;
    private String zoneName;
    private List<LoadBalancerAddress> loadBalancerAddresses = new ArrayList<>();

    public AvailabilityZone() {}

    public String getSubnetId() { return subnetId; }
    public void setSubnetId(String subnetId) { this.subnetId = subnetId; }

    public String getZoneName() { return zoneName; }
    public void setZoneName(String zoneName) { this.zoneName = zoneName; }

    public List<LoadBalancerAddress> getLoadBalancerAddresses() { return loadBalancerAddresses; }
    public void setLoadBalancerAddresses(List<LoadBalancerAddress> loadBalancerAddresses) { this.loadBalancerAddresses = loadBalancerAddresses; }
}
