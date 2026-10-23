package io.github.hectorvent.floci.services.eks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
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

import static io.github.hectorvent.floci.services.eks.EksK3sPlatformRbacDockerIntegrationTest.awaitActive;
import static io.github.hectorvent.floci.services.eks.EksK3sPlatformRbacDockerIntegrationTest.deleteQuietly;
import static io.github.hectorvent.floci.services.eks.EksK3sPlatformRbacDockerIntegrationTest.isDockerAvailable;
import static io.github.hectorvent.floci.services.eks.EksK3sPlatformRbacDockerIntegrationTest.kubectl;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(EksK3sManagedObjectsDockerIntegrationTest.Profile.class)
@Timeout(value = 8, unit = TimeUnit.MINUTES)
class EksK3sManagedObjectsDockerIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

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
    void k3sObjectsReadAsEksManagedAndEksKubeSystemObjectsExist() throws Exception {
        clusterName = "managed-dock-" + UUID.randomUUID().toString().substring(0, 8);
        given().contentType("application/json")
                .body("{\"name\":\"" + clusterName + "\",\"roleArn\":\"arn:aws:iam::000000000000:role/eks-role\"}")
                .when().post("/clusters")
                .then().statusCode(200);
        String containerId = awaitActive(eksService, clusterName).getContainerId();

        JsonNode items = list(containerId);
        List<String> unmarked = new ArrayList<>();
        List<String> marked = new ArrayList<>();
        for (JsonNode item : items) {
            String id = item.path("kind").asText() + " " + item.path("metadata").path("name").asText();
            boolean eksManaged = false;
            for (JsonNode entry : item.path("metadata").path("managedFields")) {
                eksManaged |= "eks".equals(entry.path("manager").asText());
            }
            if (expectedEksManaged(item) && !eksManaged) {
                unmarked.add(id);
            }
            if (eksManaged) {
                marked.add(id);
            }
        }
        assertTrue(unmarked.isEmpty(), "k3s objects without the eks field manager: " + unmarked);
        assertTrue(marked.containsAll(List.of("Deployment coredns", "Service kube-dns", "ConfigMap cluster-dns",
                "Secret k3s-serving", "ServiceAccount token-cleaner", "Addon coredns", "Addon floci-eks-control-plane",
                "CustomResourceDefinition addons.k3s.cattle.io")), marked.toString());
        assertTrue(marked.stream().noneMatch(id -> id.contains("eks:") || id.endsWith("tagging-controller")
                || id.endsWith("eks-extension-metrics-api")), marked.toString());

        for (String object : List.of("role/eks:addon-manager", "rolebinding/eks:service-operations",
                "serviceaccount/aws-cloud-provider", "service/eks-extension-metrics-api",
                "clusterrolebinding/eks:tagging-controller")) {
            ContainerExec.Result found = kubectl(dockerClient, containerId, "get", "--namespace", "kube-system",
                    object, "--output", "name");
            assertEquals(0, found.exitCode(), found.summary());
        }
        ContainerExec.Result metricsServer = kubectl(dockerClient, containerId, "get", "--namespace", "kube-system",
                "deployment/metrics-server");
        assertTrue(metricsServer.exitCode() != 0, metricsServer.summary());
    }

    private static boolean expectedEksManaged(JsonNode item) {
        String kind = item.path("kind").asText();
        String name = item.path("metadata").path("name").asText();
        return kind.equals("Addon")
                || (kind.equals("CustomResourceDefinition") && name.endsWith(".cattle.io"))
                || "coredns".equals(item.path("metadata").path("annotations")
                        .path("objectset.rio.cattle.io/owner-name").asText())
                || (kind.equals("Secret") && (name.equals("k3s-serving") || name.endsWith(".node-password.k3s")))
                || (kind.equals("ConfigMap") && name.equals("cluster-dns"))
                || (kind.equals("ServiceAccount") && name.equals("token-cleaner"));
    }

    private JsonNode list(String containerId) throws Exception {
        ContainerExec.Result listed = kubectl(dockerClient, containerId, "get", EksK3sManagedObjects.LIST_COMMAND[2],
                "--namespace", "kube-system", "--output", "json", "--show-managed-fields");
        assertEquals(0, listed.exitCode(), listed.summary());
        return JSON.readTree(listed.stdout()).path("items");
    }
}
