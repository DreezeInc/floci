package io.github.hectorvent.floci.services.eks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.Digests;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EksK3sPlatformRbacTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ADDON = "k3s.cattle.io/v1, Kind=Addon";
    static final String MANIFEST = "apiVersion: rbac.authorization.k8s.io/v1\nkind: ClusterRoleBinding\n";

    @Test
    void labelsEveryObjectOfTheRoleBindingsAndCloudControllerManifests() {
        EksK3sPlatformRbac.Listing listing = EksK3sPlatformRbac.parse(listing(
                item("ClusterRoleBinding", "kube-apiserver-kubelet-admin", null, "rolebindings", ADDON, null),
                item("ClusterRole", "system:k3s-controller", null, "rolebindings", ADDON, null),
                item("ClusterRole", "k3s-cloud-controller-manager", null, "ccm", ADDON, null),
                item("ClusterRoleBinding", "k3s-cloud-controller-manager", null, "ccm", ADDON, null),
                item("RoleBinding", "k3s-cloud-controller-manager-authentication-reader", "kube-system", "ccm",
                        ADDON, null),
                rolebindingsAddon(Digests.sha256Hex(MANIFEST))), MANIFEST);

        assertTrue(listing.applied());
        assertEquals(List.of(
                List.of("kubectl", "label", "--overwrite",
                        "clusterrolebinding.rbac.authorization.k8s.io/kube-apiserver-kubelet-admin",
                        "clusterrole.rbac.authorization.k8s.io/system:k3s-controller",
                        "clusterrole.rbac.authorization.k8s.io/k3s-cloud-controller-manager",
                        "clusterrolebinding.rbac.authorization.k8s.io/k3s-cloud-controller-manager",
                        "kubernetes.io/bootstrapping=rbac-defaults"),
                List.of("kubectl", "label", "--overwrite", "--namespace", "kube-system",
                        "rolebinding.rbac.authorization.k8s.io/k3s-cloud-controller-manager-authentication-reader",
                        "kubernetes.io/bootstrapping=rbac-defaults")),
                commands(listing));
    }

    @Test
    void leavesAddOnCustomerAndAlreadyLabelledObjectsAlone() {
        EksK3sPlatformRbac.Listing listing = EksK3sPlatformRbac.parse(listing(
                item("ClusterRoleBinding", "kube-apiserver-kubelet-admin", null, "rolebindings", ADDON,
                        "rbac-defaults"),
                item("ClusterRoleBinding", "system:metrics-server", null, "resource-reader", ADDON, null),
                item("ClusterRoleBinding", "system:coredns", null, "coredns", ADDON, "rbac-defaults"),
                item("ClusterRole", "floci-ebs-csi-provisioner", null, "floci-eks-ebs-storage", ADDON, null),
                item("ClusterRoleBinding", "demo-admin", null, null, null, null),
                item("ClusterRoleBinding", "rolebindings", null, "rolebindings", "helm.cattle.io/v1, Kind=HelmChart",
                        null),
                rolebindingsAddon(Digests.sha256Hex(MANIFEST))), MANIFEST);

        assertTrue(listing.applied());
        assertTrue(listing.labelCommands().isEmpty(), () -> commands(listing).toString());
    }

    @Test
    void isNotAppliedUntilK3sRecordsTheCurrentRoleBindingsChecksum() {
        ObjectNode ccmRole = item("ClusterRole", "k3s-cloud-controller-manager", null, "ccm", ADDON, null);
        ObjectNode ccmAddon = rolebindingsAddon(Digests.sha256Hex(MANIFEST));
        ((ObjectNode) ccmAddon.get("metadata")).put("name", "ccm");

        assertFalse(EksK3sPlatformRbac.parse(listing(ccmRole, rolebindingsAddon("")), MANIFEST).applied());
        assertFalse(EksK3sPlatformRbac.parse(listing(ccmRole, rolebindingsAddon(Digests.sha256Hex("old"))), MANIFEST)
                .applied());
        assertFalse(EksK3sPlatformRbac.parse(listing(ccmRole, ccmAddon), MANIFEST).applied());
        assertEquals(1, EksK3sPlatformRbac.parse(listing(ccmRole), MANIFEST).labelCommands().size());
    }

    @Test
    void isNotAppliedUntilEveryObjectTheRoleBindingsManifestDeclaresIsListed() {
        String manifest = "apiVersion: rbac.authorization.k8s.io/v1\nkind: ClusterRole\nmetadata:\n"
                + "  name: system:k3s-controller\n---\napiVersion: rbac.authorization.k8s.io/v1\n"
                + "kind: ClusterRoleBinding\nmetadata:\n  name: kube-apiserver-kubelet-admin\n";
        ObjectNode addon = rolebindingsAddon(Digests.sha256Hex(manifest));
        ObjectNode labelledRole = item("ClusterRole", "system:k3s-controller", null, "rolebindings", ADDON,
                "rbac-defaults");
        ObjectNode binding = item("ClusterRoleBinding", "kube-apiserver-kubelet-admin", null, "rolebindings", ADDON,
                null);
        ObjectNode namesake = item("ClusterRoleBinding", "kube-apiserver-kubelet-admin", null, "ccm", ADDON, null);

        assertFalse(EksK3sPlatformRbac.parse(listing(labelledRole, addon), manifest).applied());
        assertFalse(EksK3sPlatformRbac.parse(listing(labelledRole, namesake, addon), manifest).applied());
        assertTrue(EksK3sPlatformRbac.parse(listing(labelledRole, binding, addon), manifest).applied());
    }

    @Test
    void ignoresAnOwnerOutsideKubeSystem() {
        ObjectNode foreign = item("ClusterRoleBinding", "kube-apiserver-kubelet-admin", null, "rolebindings", ADDON,
                null);
        ((ObjectNode) foreign.path("metadata").path("annotations"))
                .put("objectset.rio.cattle.io/owner-namespace", "default");

        EksK3sPlatformRbac.Listing listing = EksK3sPlatformRbac.parse(listing(foreign), MANIFEST);

        assertFalse(listing.applied());
        assertTrue(listing.labelCommands().isEmpty());
    }

    @Test
    void treatsUnreadableOutputAsNotApplied() {
        EksK3sPlatformRbac.Listing listing = EksK3sPlatformRbac.parse("The connection to the server was refused",
                MANIFEST);

        assertFalse(listing.applied());
        assertTrue(listing.labelCommands().isEmpty());
    }

    static String listing(ObjectNode... items) {
        ObjectNode list = JSON.createObjectNode().put("kind", "List");
        ArrayNode array = list.putArray("items");
        Arrays.stream(items).forEach(array::add);
        return list.toString();
    }

    static ObjectNode item(String kind, String name, String namespace, String owner, String ownerGvk,
            String bootstrapping) {
        ObjectNode item = JSON.createObjectNode().put("apiVersion", "rbac.authorization.k8s.io/v1").put("kind", kind);
        ObjectNode metadata = item.putObject("metadata").put("name", name);
        if (namespace != null) {
            metadata.put("namespace", namespace);
        }
        if (owner != null) {
            metadata.putObject("annotations")
                    .put("objectset.rio.cattle.io/owner-gvk", ownerGvk)
                    .put("objectset.rio.cattle.io/owner-name", owner)
                    .put("objectset.rio.cattle.io/owner-namespace", "kube-system");
        }
        ObjectNode labels = metadata.putObject("labels").put("objectset.rio.cattle.io/hash", "0123abcd");
        if (bootstrapping != null) {
            labels.put("kubernetes.io/bootstrapping", bootstrapping);
        }
        return item;
    }

    static ObjectNode rolebindingsAddon(String checksum) {
        ObjectNode addon = JSON.createObjectNode().put("apiVersion", "k3s.cattle.io/v1").put("kind", "Addon");
        addon.putObject("metadata").put("name", "rolebindings").put("namespace", "kube-system");
        addon.putObject("spec").put("checksum", checksum)
                .put("source", "/var/lib/rancher/k3s/server/manifests/rolebindings.yaml");
        return addon;
    }

    private static List<List<String>> commands(EksK3sPlatformRbac.Listing listing) {
        return listing.labelCommands().stream().map(Arrays::asList).toList();
    }
}
