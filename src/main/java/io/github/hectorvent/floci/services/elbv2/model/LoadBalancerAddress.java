package io.github.hectorvent.floci.services.elbv2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class LoadBalancerAddress {

    private String ipAddress;
    private String allocationId;
    private String privateIPv4Address;

    public LoadBalancerAddress() {}

    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    public String getAllocationId() { return allocationId; }
    public void setAllocationId(String allocationId) { this.allocationId = allocationId; }

    public String getPrivateIPv4Address() { return privateIPv4Address; }
    public void setPrivateIPv4Address(String privateIPv4Address) { this.privateIPv4Address = privateIPv4Address; }
}
