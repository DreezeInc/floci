package io.github.hectorvent.floci.services.eks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Reads and patches a cluster's Services and Nodes with the cluster container's own {@code kubectl}. */
@ApplicationScoped
public class KubectlEksClusterServices implements EksClusterServices {

    static final String EXCLUDE_FROM_LOAD_BALANCERS_LABEL = "node.kubernetes.io/exclude-from-external-load-balancers";

    private static final int KUBECTL_TIMEOUT_SECONDS = 15;
    private static final String AWS_PROVIDER_ID_PREFIX = "aws://";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ContainerLifecycleManager lifecycleManager;

    @Inject
    public KubectlEksClusterServices(ContainerLifecycleManager lifecycleManager) {
        this.lifecycleManager = lifecycleManager;
    }

    @Override
    public List<EksLoadBalancerService> loadBalancerServices(Cluster cluster) {
        return EksLoadBalancerService.parseList(
                kubectl(cluster, "get", "services", "--all-namespaces", "--output", "json").stdout());
    }

    @Override
    public List<String> loadBalancerNodeInstanceIds(Cluster cluster) {
        return parseNodeInstanceIds(kubectl(cluster, "get", "nodes", "--output", "json").stdout());
    }

    @Override
    public void setFinalizers(Cluster cluster, EksLoadBalancerService service, List<String> finalizers) {
        kubectl(cluster, "patch", "service", service.name(), "--namespace", service.namespace(),
                "--type=merge", "--patch", finalizersPatch(service.resourceVersion(), finalizers));
    }

    @Override
    public void publishHostname(Cluster cluster, EksLoadBalancerService service, String hostname) {
        kubectl(cluster, "patch", "service", service.name(), "--namespace", service.namespace(),
                "--subresource=status", "--type=merge", "--patch", statusPatch(hostname));
    }

    /**
     * The instance ids, from {@code aws://} provider ids, of the Ready nodes not labelled out of
     * external load balancers in a {@code kubectl get nodes -o json} list. Nodes with no kubelet
     * never report Ready, so a load balancer only gets nodes that serve their node ports.
     */
    static List<String> parseNodeInstanceIds(String nodeListJson) {
        JsonNode items;
        try {
            items = JSON.readTree(nodeListJson).path("items");
        } catch (IOException e) {
            throw new IllegalStateException("the Node list is not JSON: " + e.getMessage(), e);
        }
        List<String> instanceIds = new ArrayList<>();
        for (JsonNode node : items) {
            String providerId = node.path("spec").path("providerID").asText("");
            if (!providerId.startsWith(AWS_PROVIDER_ID_PREFIX)
                    || node.path("metadata").path("labels").has(EXCLUDE_FROM_LOAD_BALANCERS_LABEL)
                    || !isReady(node)) {
                continue;
            }
            String instanceId = providerId.substring(providerId.lastIndexOf('/') + 1);
            if (instanceId.startsWith("i-")) {
                instanceIds.add(instanceId);
            }
        }
        return instanceIds;
    }

    private static boolean isReady(JsonNode node) {
        for (JsonNode condition : node.path("status").path("conditions")) {
            if ("Ready".equals(condition.path("type").asText())) {
                return "True".equals(condition.path("status").asText());
            }
        }
        return false;
    }

    static String finalizersPatch(String resourceVersion, List<String> finalizers) {
        ObjectNode patch = JSON.createObjectNode();
        ObjectNode metadata = patch.putObject("metadata");
        metadata.put("resourceVersion", resourceVersion);
        ArrayNode values = metadata.putArray("finalizers");
        finalizers.forEach(values::add);
        return patch.toString();
    }

    static String statusPatch(String hostname) {
        ObjectNode patch = JSON.createObjectNode();
        patch.putObject("status").putObject("loadBalancer").putArray("ingress").addObject().put("hostname", hostname);
        return patch.toString();
    }

    private ContainerExec.Result kubectl(Cluster cluster, String... args) {
        String[] command = new String[args.length + 1];
        command[0] = "kubectl";
        System.arraycopy(args, 0, command, 1, args.length);
        ContainerExec.Result result = ContainerExec.run(lifecycleManager.getDockerClient(), cluster.getContainerId(),
                command, KUBECTL_TIMEOUT_SECONDS).throwIfTimedOut(cluster.getContainerId());
        if (result.exitCode() != 0) {
            throw new IllegalStateException("kubectl " + args[0] + " " + args[1] + " in cluster " + cluster.getName()
                    + " failed: " + result.summary());
        }
        return result;
    }
}
