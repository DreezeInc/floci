package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.services.eks.EksLoadBalancerService.Port;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KubectlEksClusterServicesTest {

    @Test
    void serviceListKeepsLoadBalancerServicesAndServicesHoldingTheCleanupFinalizer() {
        String json = """
                {"items": [
                  {"metadata": {"namespace": "edge", "name": "nginx", "uid": "u-1", "resourceVersion": "41",
                                "annotations": {"service.beta.kubernetes.io/aws-load-balancer-type": "nlb"}},
                   "spec": {"type": "LoadBalancer", "ports": [
                     {"protocol": "TCP", "port": 80, "nodePort": 30080},
                     {"protocol": "UDP", "port": 53, "nodePort": 30053}]},
                   "status": {"loadBalancer": {"ingress": [{"hostname": "a1.elb.example"}]}}},
                  {"metadata": {"namespace": "edge", "name": "classed", "uid": "u-2"},
                   "spec": {"type": "LoadBalancer", "loadBalancerClass": "service.k8s.aws/nlb", "ports": []}},
                  {"metadata": {"namespace": "edge", "name": "cluster-ip", "uid": "u-3"},
                   "spec": {"type": "ClusterIP", "ports": []}},
                  {"metadata": {"namespace": "edge", "name": "going", "uid": "u-4", "resourceVersion": "9",
                                "deletionTimestamp": "2026-10-22T10:00:00Z",
                                "finalizers": ["service.kubernetes.io/load-balancer-cleanup"]},
                   "spec": {"type": "ClusterIP", "ports": []}}
                ]}
                """;

        List<EksLoadBalancerService> services = EksLoadBalancerService.parseList(json);

        assertEquals(List.of("edge/nginx", "edge/going"),
                services.stream().map(EksLoadBalancerService::namespacedName).toList());
        EksLoadBalancerService nginx = services.getFirst();
        assertEquals("u-1", nginx.uid());
        assertEquals("41", nginx.resourceVersion());
        assertEquals(List.of(new Port("TCP", 80, 30080), new Port("UDP", 53, 30053)), nginx.ports());
        assertEquals("a1.elb.example", nginx.hostname());
        assertTrue(nginx.wantsNetworkLoadBalancer());
        assertFalse(nginx.holdsCleanupFinalizer());
        EksLoadBalancerService going = services.getLast();
        assertTrue(going.deleting());
        assertFalse(going.loadBalancerType());
        assertFalse(going.wantsNetworkLoadBalancer());
        assertTrue(going.holdsCleanupFinalizer());
        assertNull(going.hostname());
    }

    @Test
    void annotationsAreReadAsTheInTreeProviderReadsThem() {
        EksLoadBalancerService service = new EksLoadBalancerService("ns", "svc", "u", "1", Map.of(
                EksLoadBalancerService.TYPE_ANNOTATION, " NLB ",
                EksLoadBalancerService.INTERNAL_ANNOTATION, "0.0.0.0/0",
                EksLoadBalancerService.ADDITIONAL_TAGS_ANNOTATION, " a=1 ,b = two=2,, c "),
                List.of(), null, true, false, List.of());

        assertTrue(service.wantsNetworkLoadBalancer());
        assertTrue(service.internal());
        assertEquals(Map.of("a", "1", "b", "two=2", "c", ""), service.additionalTags());
        assertFalse(new EksLoadBalancerService("ns", "svc", "u", "1",
                Map.of(EksLoadBalancerService.INTERNAL_ANNOTATION, "false"), List.of(), null, true, false, List.of())
                .internal());
    }

    @Test
    void nodeListGivesTheInstancesOfReadyAwsNodesNotExcludedFromLoadBalancers() {
        String json = """
                {"items": [
                  {"metadata": {"labels": {}}, "spec": {"providerID": "aws:///us-west-2a/i-0aaa"},
                   "status": {"conditions": [{"type": "MemoryPressure", "status": "False"},
                                             {"type": "Ready", "status": "True"}]}},
                  {"metadata": {"labels": {}}, "spec": {"providerID": "aws:///us-west-2a/i-0bbb"},
                   "status": {"conditions": [{"type": "Ready", "status": "Unknown"}]}},
                  {"metadata": {"labels": {}}, "spec": {"providerID": "aws:///us-west-2a/i-0ccc"}},
                  {"metadata": {"labels": {"node.kubernetes.io/exclude-from-external-load-balancers": ""}},
                   "spec": {"providerID": "aws:///us-west-2b/i-0ddd"},
                   "status": {"conditions": [{"type": "Ready", "status": "True"}]}},
                  {"metadata": {"labels": {}}, "spec": {"providerID": "k3s://node-1"},
                   "status": {"conditions": [{"type": "Ready", "status": "True"}]}}
                ]}
                """;

        assertEquals(List.of("i-0aaa"), KubectlEksClusterServices.parseNodeInstanceIds(json));
    }

    @Test
    void patchesCarryTheReadVersionAndTheHostname() {
        assertEquals("{\"metadata\":{\"resourceVersion\":\"41\",\"finalizers\":"
                        + "[\"example.com/keep\",\"service.kubernetes.io/load-balancer-cleanup\"]}}",
                KubectlEksClusterServices.finalizersPatch("41",
                        List.of("example.com/keep", EksLoadBalancerService.CLEANUP_FINALIZER)));
        assertEquals("{\"metadata\":{\"resourceVersion\":\"9\",\"finalizers\":[]}}",
                KubectlEksClusterServices.finalizersPatch("9", List.of()));
        assertEquals("{\"status\":{\"loadBalancer\":{\"ingress\":[{\"hostname\":\"a1.elb.example\"}]}}}",
                KubectlEksClusterServices.statusPatch("a1.elb.example"));
    }
}
