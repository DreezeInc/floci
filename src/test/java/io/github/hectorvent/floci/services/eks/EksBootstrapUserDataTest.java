package io.github.hectorvent.floci.services.eks;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EksBootstrapUserDataTest {

    @Test
    void extractsClusterNameFromBootstrapScripts() {
        assertEquals("my-cluster",
                EksBootstrapUserData.extractClusterName("#!/bin/bash\n/etc/eks/bootstrap.sh my-cluster\n").orElse(null));
        assertEquals("test-cluster", EksBootstrapUserData.extractClusterName(
                "#!/bin/bash\n/etc/eks/bootstrap.sh --b64-cluster-ca dGVzdA== --apiserver-endpoint https://k8s.example.com test-cluster\n").orElse(null));
        assertEquals("my-prod-cluster", EksBootstrapUserData.extractClusterName(
                "#!/bin/bash\n/etc/eks/bootstrap.sh my-prod-cluster --kubelet-extra-args '--node-labels=workload=compute'\n").orElse(null));
    }

    @Test
    void extractsClusterNameFromNodeadmConfig() {
        String yaml = "---\napiVersion: node.eks.aws/v1alpha1\nkind: NodeConfig\nspec:\n  cluster:\n    name: nodeadm-cluster\n";
        assertEquals("nodeadm-cluster", EksBootstrapUserData.extractClusterName(yaml).orElse(null));
    }

    @Test
    void extractsClusterNameFromMimeMultipartNodeadmConfig() {
        String mime = "MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=\"//\"\r\n\r\n"
                + "--//\r\nContent-Type: application/node.eks.aws\r\n\r\n---\r\n"
                + "apiVersion: node.eks.aws/v1alpha1\r\nkind: NodeConfig\r\nspec:\r\n  cluster:\r\n    name: mime-nodeadm-cluster\r\n--//--\r\n";
        assertEquals("mime-nodeadm-cluster", EksBootstrapUserData.extractClusterName(mime).orElse(null));
    }

    @Test
    void readsNodeLabelsAndTaintsFromBootstrapKubeletExtraArgs() {
        EksBootstrapUserData.NodeJoinRequest request = EksBootstrapUserData.extract(
                "#!/bin/bash\n/etc/eks/bootstrap.sh my-cluster --kubelet-extra-args"
                        + " '--node-labels=workload=compute,team=a --register-with-taints=dedicated=gpu:NoSchedule'\n")
                .orElseThrow();

        assertEquals("my-cluster", request.clusterName());
        assertEquals(Map.of("workload", "compute", "team", "a"), request.labels());
        assertEquals(List.of("dedicated=gpu:NoSchedule"), request.taints());
    }

    @Test
    void readsNodeLabelsAndTaintsFromNodeadmConfig() {
        String yaml = "---\napiVersion: node.eks.aws/v1alpha1\nkind: NodeConfig\nspec:\n  cluster:\n    name: nodeadm-cluster\n"
                + "  kubelet:\n    config:\n      registerWithTaints:\n"
                + "        - key: karpenter.sh/unregistered\n          effect: NoExecute\n"
                + "        - key: dedicated\n          value: gpu\n          effect: NoSchedule\n"
                + "    flags:\n      - --node-labels=karpenter.sh/nodepool=default,karpenter.sh/capacity-type=on-demand\n";

        EksBootstrapUserData.NodeJoinRequest request = EksBootstrapUserData.extract(yaml).orElseThrow();

        assertEquals("nodeadm-cluster", request.clusterName());
        assertEquals(Map.of("karpenter.sh/nodepool", "default", "karpenter.sh/capacity-type", "on-demand"),
                request.labels());
        assertEquals(List.of("karpenter.sh/unregistered:NoExecute", "dedicated=gpu:NoSchedule"), request.taints());
    }

    @Test
    void readsQuotedKubeletFlagValuesFromNodeadmConfig() {
        String yaml = "---\napiVersion: node.eks.aws/v1alpha1\nkind: NodeConfig\nspec:\n  cluster:\n    name: nodeadm-cluster\n"
                + "  kubelet:\n    flags:\n"
                + "      - --node-labels=\"karpenter.sh/nodepool=default,karpenter.sh/capacity-type=on-demand\"\n"
                + "      - --register-with-taints=\"karpenter.sh/unregistered:NoExecute\"\n";

        EksBootstrapUserData.NodeJoinRequest request = EksBootstrapUserData.extract(yaml).orElseThrow();

        assertEquals(Map.of("karpenter.sh/nodepool", "default", "karpenter.sh/capacity-type", "on-demand"),
                request.labels());
        assertEquals(List.of("karpenter.sh/unregistered:NoExecute"), request.taints());
    }

    @Test
    void plainJoinRequestsCarryNoLabelsOrTaints() {
        EksBootstrapUserData.NodeJoinRequest request =
                EksBootstrapUserData.extract("#!/bin/bash\n/etc/eks/bootstrap.sh my-cluster\n").orElseThrow();

        assertTrue(request.labels().isEmpty());
        assertTrue(request.taints().isEmpty());
    }

    @Test
    void returnsEmptyForUnrelatedUserData() {
        assertTrue(EksBootstrapUserData.extractClusterName(null).isEmpty());
        assertTrue(EksBootstrapUserData.extractClusterName("").isEmpty());
        assertTrue(EksBootstrapUserData.extractClusterName("#!/bin/bash\necho hello world").isEmpty());
        assertTrue(EksBootstrapUserData.extractClusterName("export CLUSTER_NAME=\"my-cluster\"").isEmpty());
        assertTrue(EksBootstrapUserData.extractClusterName("--cluster-name my-cluster").isEmpty());
        String appYaml = "app:\n  version: 1.0\ncluster:\n  name: redis-cache-cluster\n  nodes: 3\n";
        assertTrue(EksBootstrapUserData.extractClusterName(appYaml).isEmpty());
    }
}
