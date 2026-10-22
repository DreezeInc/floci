package io.github.hectorvent.floci.services.eks;

/**
 * The pod network of one running EKS cluster: the account, region and VPC the cluster was created
 * in, its pod CIDR, and the k3s container that routes it, which Floci reaches on {@code nodeAddress}.
 */
public record EksPodNetwork(String accountId, String region, String vpcId, String podCidr,
                            String containerId, String nodeAddress) {
}
