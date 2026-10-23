package io.github.hectorvent.floci.services.eks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EksK3sManagedObjectsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    static final String CHECKSUMS = """
            1111  coredns.yaml
            2222  floci-eks-control-plane.yaml
            """;

    @Test
    void marksAddOnStandInsAndK3sBookkeepingWithTheEksFieldManager() {
        EksK3sManagedObjects.Listing listing = EksK3sManagedObjects.parse(EksK3sPlatformRbacTest.listing(
                addon("coredns", "1111"),
                addon("floci-eks-control-plane", "2222"),
                crd("addons.k3s.cattle.io", "k3s.cattle.io"),
                crd("helmcharts.helm.cattle.io", "helm.cattle.io"),
                owned("apps/v1", "Deployment", "coredns", "kube-system", "coredns"),
                owned("v1", "Service", "kube-dns", "kube-system", "coredns"),
                owned("rbac.authorization.k8s.io/v1", "ClusterRoleBinding", "system:coredns", null, "coredns"),
                owned("apps/v1", "DaemonSet", "floci-ebs-csi-node", "kube-system", "floci-eks-ebs-storage"),
                owned("storage.k8s.io/v1", "CSIDriver", "ebs.csi.aws.com", null, "floci-eks-ebs-storage"),
                owned("admissionregistration.k8s.io/v1", "MutatingWebhookConfiguration", "floci-eks-pod-identity",
                        null, "floci-eks-pod-identity"),
                owned("apps/v1", "Deployment", "local-path-provisioner", "kube-system", "local-storage"),
                plain("v1", "Secret", "k3s-serving"),
                plain("v1", "Secret", "ip-10-0-0-1.node-password.k3s"),
                plain("v1", "ConfigMap", "cluster-dns"),
                plain("v1", "ServiceAccount", "token-cleaner")), CHECKSUMS);

        assertTrue(listing.complete(), () -> listing.missing().toString());
        assertEquals(List.of(
                "Addon kube-system/coredns=k3s",
                "Addon kube-system/floci-eks-control-plane=k3s",
                "CustomResourceDefinition /addons.k3s.cattle.io=k3s",
                "CustomResourceDefinition /helmcharts.helm.cattle.io=k3s",
                "Deployment kube-system/coredns=coredns",
                "Service kube-system/kube-dns=coredns",
                "ClusterRoleBinding /system:coredns=coredns",
                "DaemonSet kube-system/floci-ebs-csi-node=aws-ebs-csi-driver",
                "CSIDriver /ebs.csi.aws.com=aws-ebs-csi-driver",
                "MutatingWebhookConfiguration /floci-eks-pod-identity=pod-identity-webhook",
                "Deployment kube-system/local-path-provisioner=k3s",
                "Secret kube-system/k3s-serving=k3s",
                "Secret kube-system/ip-10-0-0-1.node-password.k3s=k3s",
                "ConfigMap kube-system/cluster-dns=k3s",
                "ServiceAccount kube-system/token-cleaner=k3s"), marked(listing));
    }

    @Test
    void appliesTheLabelsServerSideAsTheEksFieldManager() throws Exception {
        String[] command = EksK3sManagedObjects.parse(EksK3sPlatformRbacTest.listing(
                plain("v1", "ServiceAccount", "token-cleaner")), CHECKSUMS).markCommand().orElseThrow();

        assertEquals(List.of("sh", "-c", "printf '%s' \"$1\" | kubectl apply --server-side --field-manager=eks "
                + "--validate=false --output name -f -", "sh"), Arrays.asList(command).subList(0, 4));
        assertEquals("{\"apiVersion\":\"v1\",\"kind\":\"List\",\"items\":[{\"apiVersion\":\"v1\","
                + "\"kind\":\"ServiceAccount\",\"metadata\":{\"name\":\"token-cleaner\",\"namespace\":"
                + "\"kube-system\",\"labels\":{\"eks.amazonaws.com/component\":\"k3s\"}}}]}", command[4]);
    }

    @Test
    void marksAJoinedNodesPasswordSecretOnlyOnceK3sHasCreatedIt() {
        String[] command = EksK3sManagedObjects.nodePasswordMarkCommand("ip-10-0-4-10.us-east-2.compute.internal");

        assertEquals(List.of("sh", "-c", "kubectl get secret --namespace kube-system \"$2\" --output name "
                + ">/dev/null 2>&1 && printf '%s' \"$1\" | kubectl apply --server-side --field-manager=eks "
                + "--validate=false --output name -f -", "sh"), Arrays.asList(command).subList(0, 4));
        assertEquals("{\"apiVersion\":\"v1\",\"kind\":\"Secret\",\"metadata\":{\"name\":"
                + "\"ip-10-0-4-10.us-east-2.compute.internal.node-password.k3s\",\"namespace\":\"kube-system\","
                + "\"labels\":{\"eks.amazonaws.com/component\":\"k3s\"}}}", command[4]);
        assertEquals("ip-10-0-4-10.us-east-2.compute.internal.node-password.k3s", command[5]);
    }

    @Test
    void leavesEksControlPlaneCustomerAndAlreadyMarkedObjectsAlone() {
        ObjectNode markedService = owned("v1", "Service", "kube-dns", "kube-system", "coredns");
        ((ObjectNode) markedService.path("metadata").path("labels")).put("eks.amazonaws.com/component", "coredns");

        EksK3sManagedObjects.Listing listing = EksK3sManagedObjects.parse(EksK3sPlatformRbacTest.listing(
                markedService,
                owned("rbac.authorization.k8s.io/v1", "Role", "eks:addon-manager", "kube-system",
                        "floci-eks-control-plane"),
                owned("v1", "ServiceAccount", "tagging-controller", "kube-system", "floci-eks-control-plane"),
                owned("v1", "ServiceAccount", "metrics-server", "kube-system", "metrics-server-deployment"),
                crd("certificates.cert-manager.io", "cert-manager.io"),
                plain("v1", "Secret", "aws-auth-token"),
                plain("v1", "ConfigMap", "coredns-custom"),
                plain("v1", "ServiceAccount", "deployment-controller")), CHECKSUMS);

        assertTrue(listing.markCommand().isEmpty(), () -> marked(listing).toString());
    }

    @Test
    void isIncompleteUntilEveryManifestIsAppliedAndK3sBookkeepingExists() {
        EksK3sManagedObjects.Listing listing = EksK3sManagedObjects.parse(EksK3sPlatformRbacTest.listing(
                addon("coredns", ""),
                addon("floci-eks-control-plane", "stale"),
                plain("v1", "Secret", "k3s-serving")), CHECKSUMS);

        assertFalse(listing.complete());
        assertEquals(List.of("the coredns manifest", "the floci-eks-control-plane manifest",
                "a node password Secret", "ConfigMap cluster-dns", "ServiceAccount token-cleaner"), listing.missing());
        assertEquals(List.of("Addon kube-system/coredns=k3s", "Addon kube-system/floci-eks-control-plane=k3s",
                "Secret kube-system/k3s-serving=k3s"), marked(listing));
    }

    @Test
    void treatsUnreadableOutputAsIncomplete() {
        assertFalse(EksK3sManagedObjects.parse("The connection to the server was refused", CHECKSUMS).complete());
        EksK3sManagedObjects.Listing noChecksums = EksK3sManagedObjects.parse(
                EksK3sPlatformRbacTest.listing(addon("coredns", "1111")), "sh: sha256sum: not found");
        assertFalse(noChecksums.complete());
        assertTrue(noChecksums.markCommand().isEmpty());
    }

    static ObjectNode addon(String name, String checksum) {
        ObjectNode addon = JSON.createObjectNode().put("apiVersion", "k3s.cattle.io/v1").put("kind", "Addon");
        addon.putObject("metadata").put("name", name).put("namespace", "kube-system").putObject("labels");
        addon.putObject("spec").put("checksum", checksum);
        return addon;
    }

    static ObjectNode crd(String name, String group) {
        ObjectNode crd = JSON.createObjectNode().put("apiVersion", "apiextensions.k8s.io/v1")
                .put("kind", "CustomResourceDefinition");
        crd.putObject("metadata").put("name", name).putObject("labels");
        crd.putObject("spec").put("group", group);
        return crd;
    }

    static ObjectNode owned(String apiVersion, String kind, String name, String namespace, String addon) {
        ObjectNode item = plain(apiVersion, kind, name);
        ObjectNode metadata = (ObjectNode) item.get("metadata");
        if (namespace == null) {
            metadata.remove("namespace");
        }
        metadata.putObject("annotations")
                .put("objectset.rio.cattle.io/owner-gvk", "k3s.cattle.io/v1, Kind=Addon")
                .put("objectset.rio.cattle.io/owner-name", addon)
                .put("objectset.rio.cattle.io/owner-namespace", "kube-system");
        return item;
    }

    static ObjectNode plain(String apiVersion, String kind, String name) {
        ObjectNode item = JSON.createObjectNode().put("apiVersion", apiVersion).put("kind", kind);
        item.putObject("metadata").put("name", name).put("namespace", "kube-system").putObject("labels");
        return item;
    }

    static List<String> marked(EksK3sManagedObjects.Listing listing) {
        if (listing.markCommand().isEmpty()) {
            return List.of();
        }
        List<String> marked = new ArrayList<>();
        try {
            for (JsonNode object : JSON.readTree(listing.markCommand().get()[4]).path("items")) {
                JsonNode metadata = object.path("metadata");
                marked.add(object.path("kind").asText() + " " + metadata.path("namespace").asText("") + "/"
                        + metadata.path("name").asText() + "="
                        + metadata.path("labels").path("eks.amazonaws.com/component").asText());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return marked;
    }
}
