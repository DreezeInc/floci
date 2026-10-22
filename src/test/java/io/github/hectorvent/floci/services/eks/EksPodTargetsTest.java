package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.services.elbv2.ElbV2IpTargets.Route;
import io.github.hectorvent.floci.services.elbv2.model.TargetGroup;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EksPodTargetsTest {

    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-2";
    private static final EksPodNetwork SOURCE = new EksPodNetwork(ACCOUNT, REGION, "vpc-source", "10.42.0.0/16",
            "source-node", "172.17.0.3");
    private static final EksPodNetwork OTHER_VPC = new EksPodNetwork(ACCOUNT, REGION, "vpc-other", "10.42.0.0/16",
            "other-node", "172.17.0.4");

    private final List<String> forwards = new ArrayList<>();
    private final EksNodePortForwards recorder = (network, nodePort, podAddress, podPort, reset) ->
            forwards.add(network.containerId() + " " + nodePort + " " + podAddress + ":" + podPort
                    + (reset ? " reset" : ""));

    @Test
    void podAddressIsReachedThroughAPortOnItsClustersNode() {
        EksPodTargets targets = new EksPodTargets(() -> List.of(OTHER_VPC, SOURCE), recorder);

        Route route = targets.reach(targetGroup(ACCOUNT, "vpc-source"), "10.42.0.15", 8080);

        assertEquals(new Route("172.17.0.3", EksPodTargets.FIRST_NODE_PORT), route);
        assertEquals(List.of("source-node 20000 10.42.0.15:8080 reset"), forwards);
    }

    @Test
    void eachTargetKeepsItsPortAndANewTargetTakesTheNextOne() {
        EksPodTargets targets = new EksPodTargets(() -> List.of(SOURCE), recorder);
        TargetGroup group = targetGroup(ACCOUNT, "vpc-source");

        Route first = targets.reach(group, "10.42.0.15", 8080);
        Route again = targets.reach(group, "10.42.0.15", 8080);
        Route second = targets.reach(group, "10.42.0.16", 8080);

        assertEquals(first, again);
        assertEquals(new Route("172.17.0.3", 20001), second);
        assertEquals(List.of("source-node 20000 10.42.0.15:8080 reset", "source-node 20001 10.42.0.16:8080"),
                forwards);
    }

    @Test
    void addressOutsideEveryPodNetworkIsReachedDirectly() {
        EksPodTargets targets = new EksPodTargets(() -> List.of(SOURCE), recorder);

        Route route = targets.reach(targetGroup(ACCOUNT, "vpc-source"), "192.168.1.20", 80);

        assertEquals(new Route("192.168.1.20", 80), route);
        assertTrue(forwards.isEmpty());
    }

    @Test
    void clusterInAnotherAccountOrVpcDoesNotClaimThePodAddress() {
        EksPodTargets targets = new EksPodTargets(() -> List.of(SOURCE), recorder);

        assertEquals(new Route("10.42.0.15", 8080),
                targets.reach(targetGroup("222222222222", "vpc-source"), "10.42.0.15", 8080));
        assertEquals(new Route("10.42.0.15", 8080),
                targets.reach(targetGroup(ACCOUNT, "vpc-target"), "10.42.0.15", 8080));
        assertTrue(forwards.isEmpty());
    }

    @Test
    void forwardCommandPassesEveryValueAsAPositionalParameter() {
        String[] command = DockerEksNodePortForwards.command(SOURCE, 20000, "10.42.0.15", 8080, true);

        assertEquals(List.of("sh", "-c", DockerEksNodePortForwards.FORWARD_SCRIPT, "sh", "172.17.0.3", "20000",
                "10.42.0.15:8080", "reset"), List.of(command));
    }

    private static TargetGroup targetGroup(String accountId, String vpcId) {
        TargetGroup group = new TargetGroup();
        group.setTargetGroupArn("arn:aws:elasticloadbalancing:" + REGION + ":" + accountId
                + ":targetgroup/k8s-app/0123456789abcdef");
        group.setTargetType("ip");
        group.setVpcId(vpcId);
        return group;
    }
}
