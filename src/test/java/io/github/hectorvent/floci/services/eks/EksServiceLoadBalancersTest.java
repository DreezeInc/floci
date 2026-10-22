package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.eks.EksLoadBalancerService.Port;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ResourcesVpcConfig;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import io.github.hectorvent.floci.services.elbv2.ElbV2TestServices;
import io.github.hectorvent.floci.services.elbv2.model.AvailabilityZone;
import io.github.hectorvent.floci.services.elbv2.model.Listener;
import io.github.hectorvent.floci.services.elbv2.model.LoadBalancer;
import io.github.hectorvent.floci.services.elbv2.model.TargetGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EksServiceLoadBalancersTest {

    private static final String REGION = "us-west-2";
    private static final String ACCOUNT = "000000000000";
    private static final String UID = "5f2c1a7e-0b3d-4c8e-9a61-7d2e4f8b1c90";
    private static final String NLB = "service.beta.kubernetes.io/aws-load-balancer-type";

    private final Ec2Service ec2 = mock(Ec2Service.class);
    private final FakeClusterServices clusterServices = new FakeClusterServices();
    private ElbV2Service elb;
    private EksServiceLoadBalancers loadBalancers;
    private Cluster cluster;

    @BeforeEach
    void setUp() {
        List<Subnet> subnets = List.of(
                subnet("subnet-a1", "vpc-1", "us-west-2a", "kubernetes.io/role/elb"),
                subnet("subnet-a9", "vpc-1", "us-west-2a", "kubernetes.io/role/elb", "kubernetes.io/cluster/demo"),
                subnet("subnet-b0", "vpc-1", "us-west-2b", "kubernetes.io/role/elb", "kubernetes.io/cluster/other"),
                subnet("subnet-b1", "vpc-1", "us-west-2b", "kubernetes.io/role/elb"),
                subnet("subnet-c1", "vpc-1", "us-west-2c", "kubernetes.io/role/internal-elb"),
                subnet("subnet-x1", "vpc-2", "us-west-2a", "kubernetes.io/role/elb"));
        when(ec2.describeSubnets(eq(REGION), anyList(), anyMap())).thenAnswer(invocation -> {
            List<String> ids = invocation.getArgument(1);
            return subnets.stream().filter(s -> ids.isEmpty() || ids.contains(s.getSubnetId())).toList();
        });
        elb = ElbV2TestServices.inMemory(REGION, ACCOUNT, ec2);
        loadBalancers = new EksServiceLoadBalancers(true, elb, ec2, clusterServices);
        cluster = new Cluster();
        cluster.setName("demo");
        cluster.setArn("arn:aws:eks:" + REGION + ":" + ACCOUNT + ":cluster/demo");
        cluster.setContainerId("k3s-demo");
        ResourcesVpcConfig vpcConfig = new ResourcesVpcConfig();
        vpcConfig.setVpcId("vpc-1");
        vpcConfig.setSubnetIds(List.of("subnet-a1", "subnet-c1"));
        cluster.setResourcesVpcConfig(vpcConfig);
    }

    @Test
    void nlbServiceGetsANetworkLoadBalancerWithAListenerAndTargetGroupPerPort() {
        clusterServices.put(service(Map.of(NLB, "nlb",
                EksLoadBalancerService.ADDITIONAL_TAGS_ANNOTATION, "team=edge, cost-center = 42 ,bare"),
                new Port("TCP", 80, 30080), new Port("TCP", 443, 30443)));

        loadBalancers.reconcile(cluster);

        LoadBalancer lb = onlyLoadBalancer();
        assertEquals("a5f2c1a7e0b3d4c8e9a617d2e4f8b1c9", lb.getLoadBalancerName());
        assertEquals("network", lb.getType());
        assertEquals("internet-facing", lb.getScheme());
        assertEquals("vpc-1", lb.getVpcId());
        assertEquals(List.of("subnet-a9", "subnet-b1"),
                lb.getAvailabilityZones().stream().map(AvailabilityZone::getSubnetId).toList());
        Map<String, String> expectedTags = new LinkedHashMap<>();
        expectedTags.put("team", "edge");
        expectedTags.put("cost-center", "42");
        expectedTags.put("bare", "");
        expectedTags.put("kubernetes.io/cluster/demo", "owned");
        expectedTags.put("kubernetes.io/service-name", "ingress/nginx");
        assertEquals(expectedTags, tagsOf(lb.getLoadBalancerArn()));

        List<Listener> listeners = listeners(lb);
        assertEquals(List.of(80, 443), listeners.stream().map(Listener::getPort).toList());
        for (Listener listener : listeners) {
            assertEquals("TCP", listener.getProtocol());
            TargetGroup group = targetGroup(listener);
            int nodePort = listener.getPort() == 80 ? 30080 : 30443;
            assertEquals("instance", group.getTargetType());
            assertEquals("TCP", group.getProtocol());
            assertEquals(nodePort, group.getPort());
            assertEquals("TCP", group.getHealthCheckProtocol());
            assertEquals("traffic-port", group.getHealthCheckPort());
            assertEquals("vpc-1", group.getVpcId());
            assertEquals(List.of("i-node1:" + nodePort), targets(group));
            assertEquals(expectedTags, tagsOf(group.getTargetGroupArn()));
        }

        assertEquals(List.of(
                "finalizers ingress/nginx [service.kubernetes.io/load-balancer-cleanup] with 0 load balancers",
                "hostname ingress/nginx " + lb.getDnsName()), clusterServices.events);
    }

    @Test
    void reconcilingAgainChangesNothing() {
        clusterServices.put(service(Map.of(NLB, "nlb"), new Port("TCP", 80, 30080)));
        loadBalancers.reconcile(cluster);
        LoadBalancer lb = onlyLoadBalancer();
        String listenerArn = listeners(lb).getFirst().getListenerArn();
        clusterServices.events.clear();

        loadBalancers.reconcile(cluster);

        assertEquals(lb.getLoadBalancerArn(), onlyLoadBalancer().getLoadBalancerArn());
        assertEquals(List.of(listenerArn), listeners(lb).stream().map(Listener::getListenerArn).toList());
        assertEquals(1, elb.describeTargetGroups(REGION, null, null, null).size());
        assertEquals(List.of(), clusterServices.events);
    }

    @Test
    void changedNodePortsAndNodesAreFollowed() {
        clusterServices.put(service(Map.of(NLB, "nlb"), new Port("TCP", 80, 30080), new Port("TCP", 8080, 30081)));
        loadBalancers.reconcile(cluster);
        LoadBalancer lb = onlyLoadBalancer();
        String keptGroupArn = targetGroup(listeners(lb).getLast()).getTargetGroupArn();

        clusterServices.put(service(Map.of(NLB, "nlb"), new Port("TCP", 80, 31000), new Port("TCP", 8080, 30081)));
        clusterServices.nodes = List.of("i-node2");
        loadBalancers.reconcile(cluster);

        List<Listener> listeners = listeners(lb);
        assertEquals(List.of(80, 8080), listeners.stream().map(Listener::getPort).toList());
        TargetGroup replaced = targetGroup(listeners.getFirst());
        assertEquals(31000, replaced.getPort());
        assertEquals(List.of("i-node2:31000"), targets(replaced));
        TargetGroup kept = targetGroup(listeners.getLast());
        assertEquals(keptGroupArn, kept.getTargetGroupArn());
        assertEquals(List.of("i-node2:30081"), targets(kept));
        assertEquals(2, elb.describeTargetGroups(REGION, null, null, null).size());
    }

    @Test
    void droppedPortLosesItsListenerAndTargetGroup() {
        clusterServices.put(service(Map.of(NLB, "nlb"), new Port("TCP", 80, 30080), new Port("TCP", 443, 30443)));
        loadBalancers.reconcile(cluster);

        clusterServices.put(service(Map.of(NLB, "nlb"), new Port("TCP", 80, 30080)));
        loadBalancers.reconcile(cluster);

        assertEquals(List.of(80), listeners(onlyLoadBalancer()).stream().map(Listener::getPort).toList());
        assertEquals(1, elb.describeTargetGroups(REGION, null, null, null).size());
    }

    @Test
    void deletedServiceLosesItsLoadBalancerAndThenItsFinalizer() {
        clusterServices.put(service(Map.of(NLB, "nlb"), new Port("TCP", 80, 30080)));
        loadBalancers.reconcile(cluster);
        EksLoadBalancerService live = clusterServices.services.get("ingress/nginx");
        clusterServices.put(new EksLoadBalancerService("ingress", "nginx", UID, "7", live.annotations(),
                live.ports(), live.hostname(), true, true,
                List.of("example.com/keep", EksLoadBalancerService.CLEANUP_FINALIZER)));
        clusterServices.events.clear();

        loadBalancers.reconcile(cluster);

        assertEquals(List.of(), elb.describeLoadBalancers(REGION, null, null, null, null));
        assertEquals(List.of(), elb.describeTargetGroups(REGION, null, null, null));
        assertEquals(List.of("finalizers ingress/nginx [example.com/keep] with 0 load balancers"),
                clusterServices.events);
    }

    @Test
    void serviceThatStopsAskingForAnNlbLosesIt() {
        clusterServices.put(service(Map.of(NLB, "nlb"), new Port("TCP", 80, 30080)));
        loadBalancers.reconcile(cluster);
        EksLoadBalancerService live = clusterServices.services.get("ingress/nginx");
        clusterServices.put(new EksLoadBalancerService("ingress", "nginx", UID, "8", Map.of(), live.ports(),
                live.hostname(), true, false, live.finalizers()));

        loadBalancers.reconcile(cluster);

        assertEquals(List.of(), elb.describeLoadBalancers(REGION, null, null, null, null));
        assertEquals(List.of(), clusterServices.services.get("ingress/nginx").finalizers());
    }

    @Test
    void serviceWithoutTheNlbAnnotationIsLeftAlone() {
        clusterServices.put(service(Map.of(), new Port("TCP", 80, 30080)));

        loadBalancers.reconcile(cluster);

        assertEquals(List.of(), elb.describeLoadBalancers(REGION, null, null, null, null));
        assertEquals(List.of(), clusterServices.events);
    }

    @Test
    void internalServiceUsesTheInternalSubnets() {
        clusterServices.put(service(Map.of(NLB, "nlb", EksLoadBalancerService.INTERNAL_ANNOTATION, "true"),
                new Port("TCP", 80, 30080)));

        loadBalancers.reconcile(cluster);

        LoadBalancer lb = onlyLoadBalancer();
        assertEquals("internal", lb.getScheme());
        assertEquals(List.of("subnet-c1"),
                lb.getAvailabilityZones().stream().map(AvailabilityZone::getSubnetId).toList());
    }

    @Test
    void lostTagsAreAddedBack() {
        clusterServices.put(service(Map.of(NLB, "nlb"), new Port("TCP", 80, 30080)));
        loadBalancers.reconcile(cluster);
        String arn = onlyLoadBalancer().getLoadBalancerArn();
        elb.removeTags(List.of(arn), List.of("kubernetes.io/service-name"));

        loadBalancers.reconcile(cluster);

        assertEquals("ingress/nginx", tagsOf(arn).get("kubernetes.io/service-name"));
    }

    @Test
    void disabledReconcilerQueuesNothing() {
        clusterServices.put(service(Map.of(NLB, "nlb"), new Port("TCP", 80, 30080)));

        new EksServiceLoadBalancers(false, elb, ec2, clusterServices).requestReconcile(cluster);

        assertEquals(List.of(), clusterServices.events);
        assertEquals(List.of(), elb.describeLoadBalancers(REGION, null, null, null, null));
    }

    @Test
    void namesFollowTheInTreeProvider() {
        assertEquals("a5f2c1a7e0b3d4c8e9a617d2e4f8b1c9", EksServiceLoadBalancers.loadBalancerName(UID));
        String name = EksServiceLoadBalancers.targetGroupName("demo",
                new EksLoadBalancerService("cert-manager-app", "ingress-nginx-controller", UID, "1", Map.of(),
                        List.of(), null, true, false, List.of()),
                new Port("TCP", 443, 30443));
        assertTrue(name.matches("k8s-certmana-ingressn-[0-9a-f]{10}"), name);
        assertEquals(32, name.length());
    }

    private EksLoadBalancerService service(Map<String, String> annotations, Port... ports) {
        EksLoadBalancerService current = clusterServices.services.get("ingress/nginx");
        return new EksLoadBalancerService("ingress", "nginx", UID, "1", annotations, List.of(ports),
                current != null ? current.hostname() : null, true, false,
                current != null ? current.finalizers() : List.of());
    }

    private LoadBalancer onlyLoadBalancer() {
        List<LoadBalancer> all = elb.describeLoadBalancers(REGION, null, null, null, null);
        assertEquals(1, all.size());
        return all.getFirst();
    }

    private List<Listener> listeners(LoadBalancer lb) {
        return elb.describeListeners(REGION, lb.getLoadBalancerArn(), null).stream()
                .sorted(Comparator.comparing(Listener::getPort))
                .toList();
    }

    private TargetGroup targetGroup(Listener listener) {
        String arn = listener.getDefaultActions().getFirst().getTargetGroupArn();
        return elb.describeTargetGroups(REGION, null, List.of(arn), null).getFirst();
    }

    private Map<String, String> tagsOf(String arn) {
        return elb.describeTags(List.of(arn)).get(arn);
    }

    private static List<String> targets(TargetGroup group) {
        return group.getTargets().stream().map(t -> t.getId() + ":" + t.getPort()).toList();
    }

    private static Subnet subnet(String id, String vpcId, String zone, String... tagKeys) {
        Subnet subnet = new Subnet();
        subnet.setSubnetId(id);
        subnet.setVpcId(vpcId);
        subnet.setAvailabilityZone(zone);
        subnet.setRegion(REGION);
        List<Tag> tags = new ArrayList<>();
        for (String key : tagKeys) {
            tags.add(new Tag(key, key.startsWith("kubernetes.io/cluster/") ? "shared" : "1"));
        }
        subnet.setTags(tags);
        return subnet;
    }

    private final class FakeClusterServices implements EksClusterServices {

        final Map<String, EksLoadBalancerService> services = new LinkedHashMap<>();
        final List<String> events = new ArrayList<>();
        List<String> nodes = List.of("i-node1");

        void put(EksLoadBalancerService service) {
            services.put(service.namespacedName(), service);
        }

        @Override
        public List<EksLoadBalancerService> loadBalancerServices(Cluster cluster) {
            return List.copyOf(services.values());
        }

        @Override
        public List<String> loadBalancerNodeInstanceIds(Cluster cluster) {
            return nodes;
        }

        @Override
        public void setFinalizers(Cluster cluster, EksLoadBalancerService service, List<String> finalizers) {
            events.add("finalizers " + service.namespacedName() + " " + finalizers + " with "
                    + elb.describeLoadBalancers(REGION, null, null, null, null).size() + " load balancers");
            put(new EksLoadBalancerService(service.namespace(), service.name(), service.uid(),
                    service.resourceVersion(), service.annotations(), service.ports(), service.hostname(),
                    service.loadBalancerType(), service.deleting(), List.copyOf(finalizers)));
        }

        @Override
        public void publishHostname(Cluster cluster, EksLoadBalancerService service, String hostname) {
            events.add("hostname " + service.namespacedName() + " " + hostname);
            EksLoadBalancerService current = services.get(service.namespacedName());
            put(new EksLoadBalancerService(current.namespace(), current.name(), current.uid(),
                    current.resourceVersion(), current.annotations(), current.ports(), hostname,
                    current.loadBalancerType(), current.deleting(), current.finalizers()));
        }
    }
}
