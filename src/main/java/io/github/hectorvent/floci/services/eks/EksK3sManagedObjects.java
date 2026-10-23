package io.github.hectorvent.floci.services.eks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Finds the k3s objects that stand in for EKS add-ons or have no EKS counterpart, to give them EKS's field manager. */
final class EksK3sManagedObjects {

    static final String FIELD_MANAGER = "eks";
    static final String COMPONENT_LABEL = "eks.amazonaws.com/component";
    static final String K3S_COMPONENT = "k3s";
    // An update leaves objects created without managed fields untracked; a server-side apply records the manager.
    static final String APPLY_STDIN_SCRIPT = "printf '%s' \"$1\" | kubectl apply --server-side --field-manager="
            + FIELD_MANAGER + " --validate=false --output name -f -";
    static final String[] READ_CHECKSUMS_COMMAND = {"sh", "-c",
            "cd /var/lib/rancher/k3s/server/manifests && sha256sum *.yaml"};
    // StorageClasses are not listed: EKS creates gp2 itself, outside any add-on.
    static final String[] LIST_COMMAND = {"kubectl", "get",
            "customresourcedefinitions,addons.k3s.cattle.io,serviceaccounts,configmaps,secrets,services,"
                    + "deployments.apps,daemonsets.apps,clusterroles,clusterrolebindings,csidrivers.storage.k8s.io,"
                    + "mutatingwebhookconfigurations.admissionregistration.k8s.io",
            "--namespace", "kube-system", "--output", "json"};

    static final Map<String, String> MANIFEST_COMPONENTS = Map.of(
            "coredns", "coredns",
            "floci-eks-ebs-storage", "aws-ebs-csi-driver",
            "floci-eks-pod-identity", "pod-identity-webhook",
            "local-storage", K3S_COMPONENT);

    private static final Set<String> K3S_CRD_GROUPS = Set.of("k3s.cattle.io", "helm.cattle.io");
    private static final String NAMESPACE = "kube-system";
    private static final String K3S_SERVING_SECRET = "k3s-serving";
    private static final String NODE_PASSWORD_SUFFIX = ".node-password.k3s";
    private static final String CLUSTER_DNS_CONFIG_MAP = "cluster-dns";
    private static final String TOKEN_CLEANER = "token-cleaner";
    private static final String OWNER_GVK = "objectset.rio.cattle.io/owner-gvk";
    private static final String OWNER_NAME = "objectset.rio.cattle.io/owner-name";
    private static final String OWNER_NAMESPACE = "objectset.rio.cattle.io/owner-namespace";
    private static final String ADDON_GVK = "k3s.cattle.io/v1, Kind=Addon";
    private static final ObjectMapper JSON = new ObjectMapper();

    private EksK3sManagedObjects() {
    }

    /** {@code missing} names what k3s has not created or applied yet; the listing is complete once it is empty. */
    record Listing(List<String> missing, Optional<String[]> markCommand) {

        boolean complete() {
            return missing.isEmpty();
        }
    }

    static Listing parse(String kubectlJson, String manifestChecksums) {
        Map<String, String> pendingManifests = checksumsByManifest(manifestChecksums);
        if (pendingManifests.isEmpty()) {
            return new Listing(List.of("the manifest checksums"), Optional.empty());
        }
        JsonNode items;
        try {
            items = JSON.readTree(kubectlJson).path("items");
        } catch (IOException e) {
            return new Listing(List.of("the kube-system listing"), Optional.empty());
        }
        boolean servingSecret = false;
        boolean nodePassword = false;
        boolean clusterDns = false;
        boolean tokenCleaner = false;
        ArrayNode marked = JSON.createArrayNode();
        for (JsonNode item : items) {
            String kind = item.path("kind").asText();
            JsonNode metadata = item.path("metadata");
            String name = metadata.path("name").asText();
            String namespace = metadata.path("namespace").asText();
            String component;
            if ("Addon".equals(kind) && NAMESPACE.equals(namespace)) {
                // k3s records a manifest's checksum on its Addon only after applying every object in it.
                pendingManifests.remove(name, item.path("spec").path("checksum").asText());
                component = K3S_COMPONENT;
            } else if ("CustomResourceDefinition".equals(kind)
                    && K3S_CRD_GROUPS.contains(item.path("spec").path("group").asText())) {
                component = K3S_COMPONENT;
            } else if (NAMESPACE.equals(namespace) && "Secret".equals(kind)
                    && (K3S_SERVING_SECRET.equals(name) || name.endsWith(NODE_PASSWORD_SUFFIX))) {
                servingSecret |= K3S_SERVING_SECRET.equals(name);
                nodePassword |= name.endsWith(NODE_PASSWORD_SUFFIX);
                component = K3S_COMPONENT;
            } else if (NAMESPACE.equals(namespace) && "ConfigMap".equals(kind) && CLUSTER_DNS_CONFIG_MAP.equals(name)) {
                clusterDns = true;
                component = K3S_COMPONENT;
            } else if (NAMESPACE.equals(namespace) && "ServiceAccount".equals(kind) && TOKEN_CLEANER.equals(name)) {
                tokenCleaner = true;
                component = K3S_COMPONENT;
            } else {
                component = manifestComponent(metadata.path("annotations"));
            }
            if (component == null || component.equals(metadata.path("labels").path(COMPONENT_LABEL).asText())) {
                continue;
            }
            ObjectNode object = marked.addObject().put("apiVersion", item.path("apiVersion").asText())
                    .put("kind", kind);
            ObjectNode objectMetadata = object.putObject("metadata").put("name", name);
            if (!namespace.isEmpty()) {
                objectMetadata.put("namespace", namespace);
            }
            objectMetadata.putObject("labels").put(COMPONENT_LABEL, component);
        }
        List<String> missing = new ArrayList<>(pendingManifests.keySet().stream()
                .map(manifest -> "the " + manifest + " manifest").sorted().toList());
        if (!servingSecret) {
            missing.add("Secret " + K3S_SERVING_SECRET);
        }
        if (!nodePassword) {
            missing.add("a node password Secret");
        }
        if (!clusterDns) {
            missing.add("ConfigMap " + CLUSTER_DNS_CONFIG_MAP);
        }
        if (!tokenCleaner) {
            missing.add("ServiceAccount " + TOKEN_CLEANER);
        }
        if (marked.isEmpty()) {
            return new Listing(missing, Optional.empty());
        }
        ObjectNode list = JSON.createObjectNode().put("apiVersion", "v1").put("kind", "List");
        list.set("items", marked);
        return new Listing(missing, Optional.of(new String[]{"sh", "-c", APPLY_STDIN_SCRIPT, "sh", list.toString()}));
    }

    private static Map<String, String> checksumsByManifest(String sha256sumOutput) {
        Map<String, String> checksums = new HashMap<>();
        for (String line : sha256sumOutput == null ? new String[0] : sha256sumOutput.split("\\R")) {
            String[] fields = line.trim().split("\\s+", 2);
            if (fields.length == 2 && fields[1].endsWith(".yaml")) {
                checksums.put(fields[1].substring(0, fields[1].length() - ".yaml".length()), fields[0]);
            }
        }
        return checksums;
    }

    private static String manifestComponent(JsonNode annotations) {
        if (!ADDON_GVK.equals(annotations.path(OWNER_GVK).asText())
                || !NAMESPACE.equals(annotations.path(OWNER_NAMESPACE).asText())) {
            return null;
        }
        return MANIFEST_COMPONENTS.get(annotations.path(OWNER_NAME).asText());
    }
}
