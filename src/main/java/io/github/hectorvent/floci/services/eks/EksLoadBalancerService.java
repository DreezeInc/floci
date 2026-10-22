package io.github.hectorvent.floci.services.eks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A Service the EKS in-tree cloud provider looks after: a {@code LoadBalancer} Service with no load
 * balancer class, or any Service that still holds the provider's cleanup finalizer.
 */
record EksLoadBalancerService(String namespace, String name, String uid, String resourceVersion,
                              Map<String, String> annotations, List<Port> ports, String hostname,
                              boolean loadBalancerType, boolean deleting, List<String> finalizers) {

    static final String CLEANUP_FINALIZER = "service.kubernetes.io/load-balancer-cleanup";
    static final String TYPE_ANNOTATION = "service.beta.kubernetes.io/aws-load-balancer-type";
    static final String INTERNAL_ANNOTATION = "service.beta.kubernetes.io/aws-load-balancer-internal";
    static final String ADDITIONAL_TAGS_ANNOTATION =
            "service.beta.kubernetes.io/aws-load-balancer-additional-resource-tags";

    private static final ObjectMapper JSON = new ObjectMapper();

    record Port(String protocol, int port, int nodePort) {}

    /** A live {@code LoadBalancer} Service that asks for a network load balancer. */
    boolean wantsNetworkLoadBalancer() {
        return loadBalancerType && !deleting
                && "nlb".equalsIgnoreCase(annotations.getOrDefault(TYPE_ANNOTATION, "").trim());
    }

    boolean holdsCleanupFinalizer() {
        return finalizers.contains(CLEANUP_FINALIZER);
    }

    /** As the in-tree provider reads it: any value other than {@code false} makes the load balancer internal. */
    boolean internal() {
        String value = annotations.getOrDefault(INTERNAL_ANNOTATION, "");
        return !value.isEmpty() && !"false".equals(value);
    }

    /** As the in-tree provider reads it: comma-separated {@code key=value} pairs, a bare key has an empty value. */
    Map<String, String> additionalTags() {
        Map<String, String> tags = new LinkedHashMap<>();
        for (String pair : annotations.getOrDefault(ADDITIONAL_TAGS_ANNOTATION, "").split(",")) {
            String trimmed = pair.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] keyValue = trimmed.split("=", 2);
            tags.put(keyValue[0].trim(), keyValue.length == 2 ? keyValue[1].trim() : "");
        }
        return tags;
    }

    String namespacedName() {
        return namespace + "/" + name;
    }

    /** The Services the provider looks after in a {@code kubectl get services -o json} list. */
    static List<EksLoadBalancerService> parseList(String serviceListJson) {
        JsonNode items;
        try {
            items = JSON.readTree(serviceListJson).path("items");
        } catch (IOException e) {
            throw new IllegalStateException("the Service list is not JSON: " + e.getMessage(), e);
        }
        List<EksLoadBalancerService> services = new ArrayList<>();
        for (JsonNode item : items) {
            JsonNode metadata = item.path("metadata");
            JsonNode spec = item.path("spec");
            List<String> finalizers = new ArrayList<>();
            metadata.path("finalizers").forEach(finalizer -> finalizers.add(finalizer.asText()));
            boolean loadBalancerType = "LoadBalancer".equals(spec.path("type").asText())
                    && !spec.hasNonNull("loadBalancerClass");
            if (!loadBalancerType && !finalizers.contains(CLEANUP_FINALIZER)) {
                continue;
            }
            Map<String, String> annotations = new LinkedHashMap<>();
            metadata.path("annotations").properties()
                    .forEach(entry -> annotations.put(entry.getKey(), entry.getValue().asText()));
            List<Port> ports = new ArrayList<>();
            for (JsonNode port : spec.path("ports")) {
                ports.add(new Port(port.path("protocol").asText("TCP"), port.path("port").asInt(),
                        port.path("nodePort").asInt()));
            }
            JsonNode ingress = item.path("status").path("loadBalancer").path("ingress");
            String hostname = ingress.isArray() && !ingress.isEmpty() && ingress.get(0).hasNonNull("hostname")
                    ? ingress.get(0).get("hostname").asText()
                    : null;
            services.add(new EksLoadBalancerService(metadata.path("namespace").asText(),
                    metadata.path("name").asText(), metadata.path("uid").asText(),
                    metadata.path("resourceVersion").asText(), Map.copyOf(annotations), List.copyOf(ports),
                    hostname, loadBalancerType, metadata.hasNonNull("deletionTimestamp"),
                    List.copyOf(finalizers)));
        }
        return services;
    }
}
