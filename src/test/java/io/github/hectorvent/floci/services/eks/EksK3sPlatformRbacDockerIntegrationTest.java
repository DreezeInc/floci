package io.github.hectorvent.floci.services.eks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ClusterStatus;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@QuarkusTest
@TestProfile(EksK3sPlatformRbacDockerIntegrationTest.Profile.class)
@Timeout(value = 8, unit = TimeUnit.MINUTES)
class EksK3sPlatformRbacDockerIntegrationTest {

    private static final Logger LOG = Logger.getLogger(EksK3sPlatformRbacDockerIntegrationTest.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> RESERVED_PREFIXES = List.of("system:", "eks:", "aws:", "amazon:", "iam:");
    private static final String KUBELET_ADMIN = "kube-apiserver-kubelet-admin";

    public static final class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.eks.mock", "false");
        }
    }

    @Inject
    DockerClient dockerClient;

    @Inject
    EksService eksService;

    private String clusterName;

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(isDockerAvailable(dockerClient), "Docker daemon must be available");
    }

    @AfterEach
    void tearDown() {
        deleteQuietly(eksService, clusterName);
    }

    @Test
    void k3sRbacReadsAsPlatformDefaultsAndKeepsItsLabelAcrossARestart() throws Exception {
        clusterName = "rbac-dock-" + UUID.randomUUID().toString().substring(0, 8);
        given().contentType("application/json")
                .body("{\"name\":\"" + clusterName + "\",\"roleArn\":\"arn:aws:iam::000000000000:role/eks-role\"}")
                .when().post("/clusters")
                .then().statusCode(200);
        String containerId = awaitActive(eksService, clusterName).getContainerId();
        awaitK3sRoleBindings(dockerClient, containerId);

        assertNoUnlabelledCustomerSubjects(dockerClient, containerId);
        assertPlatformDefault(dockerClient, containerId, KUBELET_ADMIN);
        assertPlatformDefault(dockerClient, containerId, "k3s-cloud-controller-manager");
        awaitNodesReady(dockerClient, containerId);

        ContainerExec.Result tampered = kubectl(dockerClient, containerId, "patch", "clusterrolebinding",
                KUBELET_ADMIN, "--type=json", "--patch", "[{\"op\":\"add\",\"path\":\"/subjects/-\",\"value\":"
                        + "{\"kind\":\"User\",\"name\":\"tamper-probe\",\"apiGroup\":\"rbac.authorization.k8s.io\"}}]");
        assertEquals(0, tampered.exitCode(), tampered.summary());

        dockerClient.restartContainerCmd(containerId).exec();
        awaitK3sReapplied(dockerClient, containerId);

        assertPlatformDefault(dockerClient, containerId, KUBELET_ADMIN);
        assertPlatformDefault(dockerClient, containerId, "k3s-cloud-controller-manager");
        assertNoUnlabelledCustomerSubjects(dockerClient, containerId);
        awaitNodesReady(dockerClient, containerId);
    }

    private static void awaitK3sReapplied(DockerClient dockerClient, String containerId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 120_000;
        String subjects = "";
        while (System.currentTimeMillis() < deadline) {
            ContainerExec.Result result = kubectl(dockerClient, containerId, "get", "clusterrolebinding",
                    KUBELET_ADMIN, "--output", "jsonpath={.subjects[*].name}");
            subjects = result.stdout().trim();
            if (result.exitCode() == 0 && subjects.equals("kube-apiserver")) {
                return;
            }
            Thread.sleep(2000);
        }
        fail("k3s did not re-apply its rolebindings manifest after the restart; subjects: " + subjects);
    }

    // k3s applies ccm before rolebindings, so the RBAC checks need not race either manifest.
    static void awaitK3sRoleBindings(DockerClient dockerClient, String containerId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            if (kubectl(dockerClient, containerId, "get", "clusterrolebinding", KUBELET_ADMIN).exitCode() == 0) {
                return;
            }
            Thread.sleep(1000);
        }
        fail("k3s did not apply its rolebindings manifest");
    }

    static Cluster awaitActive(EksService eksService, String name) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 180_000;
        while (System.currentTimeMillis() < deadline) {
            Cluster cluster = eksService.describeCluster(name);
            if (cluster.getStatus() == ClusterStatus.ACTIVE) {
                return cluster;
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("Cluster " + name + " did not become ACTIVE");
    }

    static void assertNoUnlabelledCustomerSubjects(DockerClient dockerClient, String containerId) throws Exception {
        ContainerExec.Result listed = kubectl(dockerClient, containerId, "get",
                "clusterroles,clusterrolebindings,roles,rolebindings", "--all-namespaces", "--output", "json");
        assertEquals(0, listed.exitCode(), listed.summary());
        List<String> offenders = new ArrayList<>();
        for (JsonNode item : JSON.readTree(listed.stdout()).path("items")) {
            JsonNode metadata = item.path("metadata");
            if ("rbac-defaults".equals(metadata.path("labels").path("kubernetes.io/bootstrapping").asText())) {
                continue;
            }
            for (JsonNode subject : item.path("subjects")) {
                String kind = subject.path("kind").asText();
                String name = subject.path("name").asText();
                if ((kind.equals("User") || kind.equals("Group"))
                        && RESERVED_PREFIXES.stream().noneMatch(name::startsWith)) {
                    offenders.add(item.path("kind").asText() + " " + metadata.path("name").asText()
                            + " -> " + kind + " " + name);
                }
            }
        }
        assertTrue(offenders.isEmpty(), "unlabelled RBAC with non-reserved subjects: " + offenders);
    }

    static void assertPlatformDefault(DockerClient dockerClient, String containerId, String clusterRoleBinding) {
        ContainerExec.Result label = kubectl(dockerClient, containerId, "get", "clusterrolebinding",
                clusterRoleBinding, "--output", "jsonpath={.metadata.labels.kubernetes\\.io/bootstrapping}");
        assertEquals(0, label.exitCode(), label.summary());
        assertEquals("rbac-defaults", label.stdout().trim(), clusterRoleBinding);
    }

    static JsonNode awaitNodesReady(DockerClient dockerClient, String containerId) throws Exception {
        long deadline = System.currentTimeMillis() + 120_000;
        String last = "";
        while (System.currentTimeMillis() < deadline) {
            ContainerExec.Result result = kubectl(dockerClient, containerId, "get", "nodes", "--output", "json");
            if (result.exitCode() == 0) {
                JsonNode nodes = JSON.readTree(result.stdout()).path("items");
                boolean allReady = nodes.size() > 0;
                for (JsonNode node : nodes) {
                    boolean ready = false;
                    for (JsonNode condition : node.path("status").path("conditions")) {
                        ready |= "Ready".equals(condition.path("type").asText())
                                && "True".equals(condition.path("status").asText());
                    }
                    allReady &= ready;
                }
                if (allReady) {
                    return nodes;
                }
                last = result.stdout();
            } else {
                last = result.summary();
            }
            Thread.sleep(2000);
        }
        throw new AssertionError("cluster nodes did not become Ready: " + last);
    }

    static ContainerExec.Result kubectl(DockerClient dockerClient, String containerId, String... args) {
        String[] command = new String[args.length + 1];
        command[0] = "kubectl";
        System.arraycopy(args, 0, command, 1, args.length);
        return ContainerExec.run(dockerClient, containerId, command, 30);
    }

    static boolean isDockerAvailable(DockerClient dockerClient) {
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (Exception e) {
            LOG.warnv("Docker daemon is not available: {0}", e.getMessage());
            return false;
        }
    }

    static void deleteQuietly(EksService eksService, String clusterName) {
        if (clusterName == null) {
            return;
        }
        try {
            eksService.deleteCluster(clusterName);
        } catch (RuntimeException e) {
            LOG.warnv("Failed to delete test cluster {0}: {1}", clusterName, e.getMessage());
        }
    }
}
