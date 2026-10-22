package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.eks.EksLoadBalancerService.Port;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ResourcesVpcConfig;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import io.github.hectorvent.floci.services.elbv2.model.Action;
import io.github.hectorvent.floci.services.elbv2.model.Listener;
import io.github.hectorvent.floci.services.elbv2.model.LoadBalancer;
import io.github.hectorvent.floci.services.elbv2.model.TargetDescription;
import io.github.hectorvent.floci.services.elbv2.model.TargetGroup;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Plays the EKS in-tree cloud provider's service controller for network load balancers. Each
 * {@code LoadBalancer} Service annotated {@code service.beta.kubernetes.io/aws-load-balancer-type: nlb}
 * gets an NLB in the cluster's account, named after the Service's UID, with one listener and one
 * {@code instance} target group per port. The target group registers the cluster's Ready nodes on
 * the port's node port, and the NLB's DNS name goes to the Service status. The provider's cleanup
 * finalizer holds the Service until its NLB and target groups are deleted.
 */
@ApplicationScoped
public class EksServiceLoadBalancers {

    static final String SERVICE_NAME_TAG = "kubernetes.io/service-name";
    static final String CLUSTER_TAG_PREFIX = "kubernetes.io/cluster/";
    static final String PUBLIC_SUBNET_ROLE_TAG = "kubernetes.io/role/elb";
    static final String INTERNAL_SUBNET_ROLE_TAG = "kubernetes.io/role/internal-elb";
    static final long RECONCILE_INTERVAL_MILLIS = 10_000;

    private static final Logger LOG = Logger.getLogger(EksServiceLoadBalancers.class);
    private static final int MAX_LOAD_BALANCER_NAME_LENGTH = 32;
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^A-Za-z0-9]");
    private static final String HEALTH_CHECK_PROTOCOL = "TCP";
    private static final String HEALTH_CHECK_PORT = "traffic-port";
    private static final int HEALTH_CHECK_INTERVAL_SECONDS = 30;
    private static final int HEALTH_CHECK_TIMEOUT_SECONDS = 10;
    private static final int HEALTH_CHECK_THRESHOLD = 3;

    private final boolean enabled;
    private final ElbV2Service elb;
    private final Ec2Service ec2;
    private final EksClusterServices clusterServices;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "eks-service-load-balancers");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Long> lastReconciled = new ConcurrentHashMap<>();
    private final Set<String> queued = ConcurrentHashMap.newKeySet();

    @Inject
    public EksServiceLoadBalancers(EmulatorConfig config, ElbV2Service elb, Ec2Service ec2,
                                   EksClusterServices clusterServices) {
        this(config.services().eks().loadBalancerServices(), elb, ec2, clusterServices);
    }

    EksServiceLoadBalancers(boolean enabled, ElbV2Service elb, Ec2Service ec2, EksClusterServices clusterServices) {
        this.enabled = enabled;
        this.elb = elb;
        this.ec2 = ec2;
        this.clusterServices = clusterServices;
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }

    /**
     * Queues a reconcile of a running cluster on the worker thread, unless one is already queued or
     * one finished less than {@link #RECONCILE_INTERVAL_MILLIS} ago.
     */
    void requestReconcile(Cluster cluster) {
        if (!enabled || cluster.getContainerId() == null || cluster.getArn() == null) {
            return;
        }
        String key = cluster.getArn();
        Long last = lastReconciled.get(key);
        if ((last != null && System.currentTimeMillis() - last < RECONCILE_INTERVAL_MILLIS) || !queued.add(key)) {
            return;
        }
        worker.execute(() -> {
            try {
                reconcile(cluster);
            } catch (RuntimeException e) {
                LOG.warnv("Could not read the Services of EKS cluster {0}: {1}", cluster.getName(), e.getMessage());
            } finally {
                lastReconciled.put(key, System.currentTimeMillis());
                queued.remove(key);
            }
        });
    }

    /** Brings every Service's network load balancer in line with the Service, as the cluster's account. */
    void reconcile(Cluster cluster) {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(cluster.getArn());
        RequestScopes.runAs(arn.accountId(), arn.region(), () -> reconcileServices(arn.region(), cluster));
    }

    private void reconcileServices(String region, Cluster cluster) {
        List<String> nodes = null;
        for (EksLoadBalancerService service : clusterServices.loadBalancerServices(cluster)) {
            try {
                if (service.wantsNetworkLoadBalancer()) {
                    if (nodes == null) {
                        nodes = clusterServices.loadBalancerNodeInstanceIds(cluster);
                    }
                    ensureLoadBalancer(region, cluster, service, nodes);
                } else if (service.holdsCleanupFinalizer()) {
                    deleteLoadBalancer(region, cluster, service);
                }
            } catch (RuntimeException e) {
                LOG.warnv("Could not reconcile the load balancer of Service {0} in EKS cluster {1}: {2}",
                        service.namespacedName(), cluster.getName(), e.getMessage());
            }
        }
    }

    private void ensureLoadBalancer(String region, Cluster cluster, EksLoadBalancerService service,
                                    List<String> nodes) {
        if (!service.holdsCleanupFinalizer()) {
            List<String> finalizers = new ArrayList<>(service.finalizers());
            finalizers.add(EksLoadBalancerService.CLEANUP_FINALIZER);
            clusterServices.setFinalizers(cluster, service, finalizers);
        }
        Map<String, String> tags = tags(cluster, service);
        String name = loadBalancerName(service.uid());
        LoadBalancer loadBalancer = findLoadBalancer(region, name).orElse(null);
        if (loadBalancer == null) {
            loadBalancer = elb.createLoadBalancer(region, name, service.internal() ? "internal" : "internet-facing",
                    "network", "ipv4", subnets(region, cluster, service.internal()), null, tags);
            LOG.infov("Created network load balancer {0} for Service {1} of EKS cluster {2}",
                    name, service.namespacedName(), cluster.getName());
        } else {
            ensureTags(loadBalancer.getLoadBalancerArn(), tags);
        }

        String loadBalancerArn = loadBalancer.getLoadBalancerArn();
        String vpcId = loadBalancer.getVpcId() != null ? loadBalancer.getVpcId() : clusterVpcId(cluster);
        List<TargetGroup> attached = elb.describeTargetGroups(region, loadBalancerArn, null, null);
        Map<Integer, Listener> listenersByPort = new HashMap<>();
        elb.describeListeners(region, loadBalancerArn, null).forEach(l -> listenersByPort.put(l.getPort(), l));
        Set<String> wantedGroups = new HashSet<>();
        for (Port port : service.ports()) {
            if (port.nodePort() == 0) {
                continue;
            }
            TargetGroup group = ensureTargetGroup(region, cluster, service, port, vpcId, tags, nodes);
            wantedGroups.add(group.getTargetGroupArn());
            Listener listener = listenersByPort.remove(port.port());
            if (listener != null && !forwardsTo(listener, port, group)) {
                elb.deleteListener(region, listener.getListenerArn());
                listener = null;
            }
            if (listener == null) {
                elb.createListener(region, loadBalancerArn, port.protocol(), port.port(), null, null,
                        List.of(forwardTo(group)), null, Map.of());
            }
        }
        listenersByPort.values().forEach(listener -> elb.deleteListener(region, listener.getListenerArn()));
        attached.stream()
                .filter(group -> !wantedGroups.contains(group.getTargetGroupArn()))
                .forEach(group -> elb.deleteTargetGroup(region, group.getTargetGroupArn()));

        if (!loadBalancer.getDnsName().equals(service.hostname())) {
            clusterServices.publishHostname(cluster, service, loadBalancer.getDnsName());
        }
    }

    private TargetGroup ensureTargetGroup(String region, Cluster cluster, EksLoadBalancerService service, Port port,
                                          String vpcId, Map<String, String> tags, List<String> nodes) {
        String name = targetGroupName(cluster.getName(), service, port);
        TargetGroup group = findTargetGroup(region, name).orElse(null);
        if (group == null) {
            group = elb.createTargetGroup(region, name, port.protocol(), null, port.nodePort(), vpcId, "instance",
                    HEALTH_CHECK_PROTOCOL, HEALTH_CHECK_PORT, true, null, HEALTH_CHECK_INTERVAL_SECONDS,
                    HEALTH_CHECK_TIMEOUT_SECONDS, HEALTH_CHECK_THRESHOLD, HEALTH_CHECK_THRESHOLD, null, "ipv4", tags);
        } else {
            ensureTags(group.getTargetGroupArn(), tags);
        }
        List<TargetDescription> stale = group.getTargets().stream()
                .filter(target -> !nodes.contains(target.getId()))
                .toList();
        Set<String> registered = group.getTargets().stream()
                .map(TargetDescription::getId)
                .collect(Collectors.toSet());
        List<TargetDescription> missing = nodes.stream()
                .filter(instanceId -> !registered.contains(instanceId))
                .map(instanceId -> target(instanceId, port.nodePort()))
                .toList();
        if (!stale.isEmpty()) {
            elb.deregisterTargets(region, group.getTargetGroupArn(), stale);
        }
        if (!missing.isEmpty()) {
            elb.registerTargets(region, group.getTargetGroupArn(), missing);
        }
        return group;
    }

    private void deleteLoadBalancer(String region, Cluster cluster, EksLoadBalancerService service) {
        Optional<LoadBalancer> loadBalancer = findLoadBalancer(region, loadBalancerName(service.uid()));
        if (loadBalancer.isPresent()) {
            String loadBalancerArn = loadBalancer.get().getLoadBalancerArn();
            List<TargetGroup> attached = elb.describeTargetGroups(region, loadBalancerArn, null, null);
            elb.deleteLoadBalancer(region, loadBalancerArn);
            attached.forEach(group -> elb.deleteTargetGroup(region, group.getTargetGroupArn()));
            LOG.infov("Deleted network load balancer {0} of Service {1} of EKS cluster {2}",
                    loadBalancer.get().getLoadBalancerName(), service.namespacedName(), cluster.getName());
        }
        clusterServices.setFinalizers(cluster, service, service.finalizers().stream()
                .filter(finalizer -> !EksLoadBalancerService.CLEANUP_FINALIZER.equals(finalizer))
                .toList());
    }

    /**
     * The subnets of the cluster's VPC carrying the role tag for the scheme, or the cluster's own
     * subnets when none does: one per Availability Zone, preferring a subnet tagged for this cluster
     * and then the lowest subnet id, as the in-tree provider breaks ties.
     */
    private List<String> subnets(String region, Cluster cluster, boolean internal) {
        String vpcId = clusterVpcId(cluster);
        if (vpcId == null) {
            return List.of();
        }
        String clusterTag = CLUSTER_TAG_PREFIX + cluster.getName();
        List<Subnet> inVpc = ec2.describeSubnets(region, List.of(), Map.of()).stream()
                .filter(subnet -> vpcId.equals(subnet.getVpcId()))
                .filter(subnet -> hasTag(subnet, clusterTag) || tagsOf(subnet).stream()
                        .noneMatch(tag -> tag.getKey().startsWith(CLUSTER_TAG_PREFIX)))
                .toList();
        String roleTag = internal ? INTERNAL_SUBNET_ROLE_TAG : PUBLIC_SUBNET_ROLE_TAG;
        List<Subnet> candidates = inVpc.stream().filter(subnet -> hasTag(subnet, roleTag)).toList();
        if (candidates.isEmpty()) {
            List<String> clusterSubnets = cluster.getResourcesVpcConfig().getSubnetIds() != null
                    ? cluster.getResourcesVpcConfig().getSubnetIds()
                    : List.of();
            candidates = inVpc.stream().filter(subnet -> clusterSubnets.contains(subnet.getSubnetId())).toList();
        }
        Map<String, String> subnetByZone = new TreeMap<>();
        candidates.stream()
                .sorted(Comparator.comparing((Subnet subnet) -> !hasTag(subnet, clusterTag))
                        .thenComparing(Subnet::getSubnetId))
                .forEach(subnet -> subnetByZone.putIfAbsent(subnet.getAvailabilityZone(), subnet.getSubnetId()));
        return List.copyOf(subnetByZone.values());
    }

    private void ensureTags(String arn, Map<String, String> tags) {
        Map<String, String> current = elb.describeTags(List.of(arn)).getOrDefault(arn, Map.of());
        if (!current.entrySet().containsAll(tags.entrySet())) {
            elb.addTags(List.of(arn), tags);
        }
    }

    private Optional<LoadBalancer> findLoadBalancer(String region, String name) {
        return elb.describeLoadBalancers(region, null, null, null, null).stream()
                .filter(loadBalancer -> name.equals(loadBalancer.getLoadBalancerName()))
                .findFirst();
    }

    private Optional<TargetGroup> findTargetGroup(String region, String name) {
        return elb.describeTargetGroups(region, null, null, null).stream()
                .filter(group -> name.equals(group.getTargetGroupName()))
                .findFirst();
    }

    private static String clusterVpcId(Cluster cluster) {
        ResourcesVpcConfig vpcConfig = cluster.getResourcesVpcConfig();
        return vpcConfig != null ? vpcConfig.getVpcId() : null;
    }

    private static boolean hasTag(Subnet subnet, String key) {
        return tagsOf(subnet).stream().anyMatch(tag -> key.equals(tag.getKey()));
    }

    private static List<Tag> tagsOf(Subnet subnet) {
        return subnet.getTags() != null ? subnet.getTags() : List.of();
    }

    private static boolean forwardsTo(Listener listener, Port port, TargetGroup group) {
        List<Action> actions = listener.getDefaultActions();
        return port.protocol().equals(listener.getProtocol()) && actions.size() == 1
                && "forward".equals(actions.getFirst().getType())
                && group.getTargetGroupArn().equals(actions.getFirst().getTargetGroupArn());
    }

    private static Action forwardTo(TargetGroup group) {
        Action action = new Action();
        action.setType("forward");
        action.setTargetGroupArn(group.getTargetGroupArn());
        return action;
    }

    private static TargetDescription target(String instanceId, int port) {
        TargetDescription target = new TargetDescription();
        target.setId(instanceId);
        target.setPort(port);
        return target;
    }

    /** The additional tags the Service asks for, then the cluster ownership and Service name tags. */
    static Map<String, String> tags(Cluster cluster, EksLoadBalancerService service) {
        Map<String, String> tags = new LinkedHashMap<>(service.additionalTags());
        tags.put(CLUSTER_TAG_PREFIX + cluster.getName(), "owned");
        tags.put(SERVICE_NAME_TAG, service.namespacedName());
        return tags;
    }

    /** {@code a} and the Service UID without dashes, cut to 32 characters, as the in-tree provider names it. */
    static String loadBalancerName(String uid) {
        String name = "a" + uid.replace("-", "");
        return name.length() > MAX_LOAD_BALANCER_NAME_LENGTH ? name.substring(0, MAX_LOAD_BALANCER_NAME_LENGTH) : name;
    }

    /**
     * {@code k8s-<namespace>-<name>-<hash>} with the namespace and name cut to 8 alphanumerics and
     * the hash to 10 hex digits, hashing what the in-tree provider hashes: the cluster, the Service,
     * the ports, the protocol, the target type and the health check.
     */
    static String targetGroupName(String clusterName, EksLoadBalancerService service, Port port) {
        MessageDigest sha1;
        try {
            sha1 = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        for (String part : List.of(clusterName, service.namespace(), service.name(), String.valueOf(port.port()),
                String.valueOf(port.nodePort()), port.protocol(), "instance", HEALTH_CHECK_PROTOCOL, "",
                HEALTH_CHECK_PORT)) {
            sha1.update(part.getBytes(StandardCharsets.UTF_8));
        }
        String hash = HexFormat.of().formatHex(sha1.digest());
        return "k8s-" + cut(NON_ALPHANUMERIC.matcher(service.namespace()).replaceAll(""), 8)
                + "-" + cut(NON_ALPHANUMERIC.matcher(service.name()).replaceAll(""), 8)
                + "-" + hash.substring(0, 10);
    }

    private static String cut(String value, int length) {
        return value.length() > length ? value.substring(0, length) : value;
    }
}
