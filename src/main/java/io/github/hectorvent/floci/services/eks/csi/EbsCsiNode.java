package io.github.hectorvent.floci.services.eks.csi;

/**
 * The k3s container that runs an EKS cluster's pods, and the account, region and zone in which the
 * EBS volumes it provisions are created.
 */
public record EbsCsiNode(String clusterName, String accountId, String region, String availabilityZone,
                         String containerId, String instanceId) {
}
