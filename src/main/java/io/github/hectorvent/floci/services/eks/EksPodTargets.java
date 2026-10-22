package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.services.ec2.net.Cidr4;
import io.github.hectorvent.floci.services.elbv2.ElbV2IpTargets;
import io.github.hectorvent.floci.services.elbv2.model.TargetGroup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Reaches {@code ip} targets that are pod addresses of an EKS cluster, as the AWS Load Balancer
 * Controller registers them. The pod network exists only inside the cluster's k3s container, so
 * each pod address and port gets its own port on the node container, which forwards to the pod.
 * An address outside every cluster's pod network is reached directly.
 */
@ApplicationScoped
public class EksPodTargets implements ElbV2IpTargets {

    static final int FIRST_NODE_PORT = 20000;
    static final int LAST_NODE_PORT = 29999;

    private final EksPodNetworks networks;
    private final EksNodePortForwards forwards;
    private final Map<String, Integer> nodePorts = new HashMap<>();
    private final Map<String, Integer> nextNodePorts = new HashMap<>();

    @Inject
    public EksPodTargets(EksPodNetworks networks, EksNodePortForwards forwards) {
        this.networks = networks;
        this.forwards = forwards;
    }

    @Override
    public Route reach(TargetGroup targetGroup, String ip, int port) {
        return networkHolding(targetGroup, ip)
                .map(network -> new Route(network.nodeAddress(), nodePort(network, ip, port)))
                .orElseGet(() -> new Route(ip, port));
    }

    // The target group's account, region and VPC pick the cluster, because clusters reuse pod CIDRs.
    private Optional<EksPodNetwork> networkHolding(TargetGroup targetGroup, String ip) {
        Optional<Cidr4> address = Cidr4.parse(ip + "/32");
        if (address.isEmpty() || targetGroup == null || targetGroup.getTargetGroupArn() == null) {
            return Optional.empty();
        }
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(targetGroup.getTargetGroupArn());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return networks.podNetworks().stream()
                .filter(network -> arn.accountId().equals(network.accountId()))
                .filter(network -> arn.region().equals(network.region()))
                .filter(network -> targetGroup.getVpcId() == null || targetGroup.getVpcId().equals(network.vpcId()))
                .filter(network -> Cidr4.parse(network.podCidr())
                        .map(cidr -> cidr.contains(address.get()))
                        .orElse(false))
                .findFirst();
    }

    private synchronized int nodePort(EksPodNetwork network, String ip, int port) {
        String key = network.containerId() + "/" + ip + ":" + port;
        Integer known = nodePorts.get(key);
        if (known != null) {
            return known;
        }
        boolean firstOnNode = !nextNodePorts.containsKey(network.containerId());
        int nodePort = nextNodePorts.getOrDefault(network.containerId(), FIRST_NODE_PORT);
        if (nodePort > LAST_NODE_PORT) {
            throw new IllegalStateException("container " + network.containerId() + " has no free port left in "
                    + FIRST_NODE_PORT + "-" + LAST_NODE_PORT + " to forward to " + ip + ":" + port);
        }
        forwards.forward(network, nodePort, ip, port, firstOnNode);
        nextNodePorts.put(network.containerId(), nodePort + 1);
        nodePorts.put(key, nodePort);
        return nodePort;
    }
}
