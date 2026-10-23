package io.github.hectorvent.floci.services.eks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.hectorvent.floci.core.common.Digests;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Finds the RBAC k3s applies from its own manifests, to label it as the API server labels its defaults. */
final class EksK3sPlatformRbac {

    static final String LABEL_KEY = "kubernetes.io/bootstrapping";
    static final String LABEL_VALUE = "rbac-defaults";
    static final String ROLEBINDINGS_MANIFEST = "rolebindings";
    static final Set<String> MANIFESTS = Set.of(ROLEBINDINGS_MANIFEST, "ccm");
    static final String[] LIST_COMMAND = {"kubectl", "get",
            "clusterroles,clusterrolebindings,roles,rolebindings,addons.k3s.cattle.io", "--all-namespaces",
            "--output", "json"};
    static final String[] READ_ROLEBINDINGS_MANIFEST_COMMAND = {"cat",
            "/var/lib/rancher/k3s/server/manifests/" + ROLEBINDINGS_MANIFEST + ".yaml"};

    private static final String OWNER_GVK = "objectset.rio.cattle.io/owner-gvk";
    private static final String OWNER_NAME = "objectset.rio.cattle.io/owner-name";
    private static final String OWNER_NAMESPACE = "objectset.rio.cattle.io/owner-namespace";
    private static final String ADDON_GVK = "k3s.cattle.io/v1, Kind=Addon";
    private static final String ADDON_NAMESPACE = "kube-system";
    private static final String RBAC_API_VERSION = "rbac.authorization.k8s.io/v1";
    private static final String RBAC_GROUP = ".rbac.authorization.k8s.io/";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private EksK3sPlatformRbac() {
    }

    /** {@code applied} turns true once k3s has applied the current {@code rolebindings}, after {@code ccm}. */
    record Listing(boolean applied, List<String[]> labelCommands) {
    }

    static Listing parse(String kubectlJson, String rolebindingsManifest) {
        JsonNode items;
        Set<String> declared;
        try {
            items = JSON.readTree(kubectlJson).path("items");
            declared = declaredObjects(rolebindingsManifest);
        } catch (IOException e) {
            return new Listing(false, List.of());
        }
        String manifestChecksum = Digests.sha256Hex(rolebindingsManifest);
        boolean checksumRecorded = false;
        Set<String> listed = new HashSet<>();
        List<String> clusterScoped = new ArrayList<>();
        Map<String, List<String>> namespaced = new TreeMap<>();
        for (JsonNode item : items) {
            JsonNode metadata = item.path("metadata");
            if ("Addon".equals(item.path("kind").asText())) {
                // k3s records the manifest's checksum on its Addon only after applying every object in it.
                checksumRecorded |= ROLEBINDINGS_MANIFEST.equals(metadata.path("name").asText())
                        && ADDON_NAMESPACE.equals(metadata.path("namespace").asText())
                        && manifestChecksum.equals(item.path("spec").path("checksum").asText());
                continue;
            }
            JsonNode annotations = metadata.path("annotations");
            String owner = annotations.path(OWNER_NAME).asText();
            if (!RBAC_API_VERSION.equals(item.path("apiVersion").asText())
                    || !ADDON_GVK.equals(annotations.path(OWNER_GVK).asText())
                    || !ADDON_NAMESPACE.equals(annotations.path(OWNER_NAMESPACE).asText())
                    || !MANIFESTS.contains(owner)) {
                continue;
            }
            String kind = item.path("kind").asText();
            String name = metadata.path("name").asText();
            String namespace = metadata.path("namespace").asText();
            if (ROLEBINDINGS_MANIFEST.equals(owner)) {
                listed.add(objectKey(kind, namespace, name));
            }
            if (LABEL_VALUE.equals(metadata.path("labels").path(LABEL_KEY).asText())) {
                continue;
            }
            String ref = kind.toLowerCase(Locale.ROOT) + RBAC_GROUP + name;
            if (namespace.isEmpty()) {
                clusterScoped.add(ref);
            } else {
                namespaced.computeIfAbsent(namespace, ns -> new ArrayList<>()).add(ref);
            }
        }
        // kubectl lists each kind in its own request, so k3s can apply the manifest between a kind's and the Addon's.
        boolean applied = checksumRecorded && listed.containsAll(declared);
        List<String[]> commands = new ArrayList<>();
        if (!clusterScoped.isEmpty()) {
            commands.add(labelCommand(List.of(), clusterScoped));
        }
        namespaced.forEach((namespace, refs) -> commands.add(labelCommand(List.of("--namespace", namespace), refs)));
        return new Listing(applied, commands);
    }

    private static Set<String> declaredObjects(String manifest) throws IOException {
        Set<String> declared = new HashSet<>();
        try (MappingIterator<JsonNode> documents = YAML.readerFor(JsonNode.class).readValues(manifest)) {
            while (documents.hasNext()) {
                JsonNode document = documents.next();
                if (document == null) {
                    continue;
                }
                JsonNode metadata = document.path("metadata");
                String kind = document.path("kind").asText();
                String name = metadata.path("name").asText();
                if (!kind.isEmpty() && !name.isEmpty()) {
                    declared.add(objectKey(kind, metadata.path("namespace").asText(), name));
                }
            }
        }
        return declared;
    }

    private static String objectKey(String kind, String namespace, String name) {
        return kind + " " + namespace + "/" + name;
    }

    private static String[] labelCommand(List<String> scope, List<String> refs) {
        List<String> command = new ArrayList<>(List.of("kubectl", "label", "--overwrite"));
        command.addAll(scope);
        command.addAll(refs);
        command.add(LABEL_KEY + "=" + LABEL_VALUE);
        return command.toArray(String[]::new);
    }
}
