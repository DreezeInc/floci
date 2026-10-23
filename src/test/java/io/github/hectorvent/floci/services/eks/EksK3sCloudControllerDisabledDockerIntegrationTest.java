package io.github.hectorvent.floci.services.eks;

import com.fasterxml.jackson.databind.JsonNode;
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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.github.hectorvent.floci.services.eks.EksK3sPlatformRbacDockerIntegrationTest.assertNoUnlabelledCustomerSubjects;
import static io.github.hectorvent.floci.services.eks.EksK3sPlatformRbacDockerIntegrationTest.assertPlatformDefault;
import static io.github.hectorvent.floci.services.eks.EksK3sPlatformRbacDockerIntegrationTest.awaitActive;
import static io.github.hectorvent.floci.services.eks.EksK3sPlatformRbacDockerIntegrationTest.awaitK3sRoleBindings;
import static io.github.hectorvent.floci.services.eks.EksK3sPlatformRbacDockerIntegrationTest.awaitNodesReady;
import static io.github.hectorvent.floci.services.eks.EksK3sPlatformRbacDockerIntegrationTest.deleteQuietly;
import static io.github.hectorvent.floci.services.eks.EksK3sPlatformRbacDockerIntegrationTest.isDockerAvailable;
import static io.github.hectorvent.floci.services.eks.EksK3sPlatformRbacDockerIntegrationTest.kubectl;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@QuarkusTest
@TestProfile(EksK3sCloudControllerDisabledDockerIntegrationTest.Profile.class)
@Timeout(value = 8, unit = TimeUnit.MINUTES)
class EksK3sCloudControllerDisabledDockerIntegrationTest {

    private static final String ECHO = """
            apiVersion: v1
            kind: Pod
            metadata:
              name: echo
              labels:
                app: echo
            spec:
              containers:
              - name: echo
                image: rancher/mirrored-pause:3.6
            ---
            apiVersion: v1
            kind: Service
            metadata:
              name: echo
              annotations:
                service.beta.kubernetes.io/aws-load-balancer-type: nlb
            spec:
              type: LoadBalancer
              selector:
                app: echo
              ports:
              - port: 80
                targetPort: 8080
            """;

    public static final class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.eks.mock", "false",
                    "floci.services.eks.load-balancer-services", "true");
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
    void clusterWithoutK3sCloudControllerSchedulesPodsAndGetsNetworkLoadBalancers() throws Exception {
        String vpcId = given().formParam("Action", "CreateVpc").formParam("CidrBlock", "10.0.0.0/16")
                .when().post("/")
                .then().statusCode(200)
                .extract().path("CreateVpcResponse.vpc.vpcId");
        String subnetA = subnet(vpcId, "10.0.1.0/24", "us-east-1a");
        String subnetB = subnet(vpcId, "10.0.2.0/24", "us-east-1b");
        clusterName = "ccm-off-dock-" + UUID.randomUUID().toString().substring(0, 8);
        given().contentType("application/json")
                .body("{\"name\":\"" + clusterName + "\",\"roleArn\":\"arn:aws:iam::000000000000:role/eks-role\","
                        + "\"resourcesVpcConfig\":{\"subnetIds\":[\"" + subnetA + "\",\"" + subnetB + "\"]}}")
                .when().post("/clusters")
                .then().statusCode(200);
        String containerId = awaitActive(eksService, clusterName).getContainerId();
        awaitK3sRoleBindings(dockerClient, containerId);

        ContainerExec.Result ccmRole = kubectl(dockerClient, containerId, "get", "clusterrole",
                "k3s-cloud-controller-manager");
        assertNotEquals(0, ccmRole.exitCode(), "k3s's cloud controller manager RBAC must be gone");
        assertTrue(ccmRole.summary().contains("NotFound"), ccmRole.summary());
        assertPlatformDefault(dockerClient, containerId, "kube-apiserver-kubelet-admin");
        assertNoUnlabelledCustomerSubjects(dockerClient, containerId);

        JsonNode nodes = awaitNodesReady(dockerClient, containerId);
        for (JsonNode node : nodes) {
            assertTrue(node.path("spec").path("providerID").asText().startsWith("aws:///"), node.toString());
            for (JsonNode taint : node.path("spec").path("taints")) {
                assertNotEquals("node.cloudprovider.kubernetes.io/uninitialized", taint.path("key").asText());
            }
        }

        ContainerExec.Result applied = ContainerExec.run(dockerClient, containerId, new String[]{"sh", "-c",
                "cat <<'EOF' | kubectl apply -f -\n" + ECHO + "EOF"}, 30);
        assertEquals(0, applied.exitCode(), applied.summary());
        awaitJsonPath(containerId, "pod", "echo", "{.status.phase}", "Running");
        String hostname = awaitJsonPath(containerId, "service", "echo",
                "{.status.loadBalancer.ingress[0].hostname}", null);
        assertTrue(hostname.contains(".elb."), hostname);
    }

    private String subnet(String vpcId, String cidr, String zone) {
        return given().formParam("Action", "CreateSubnet").formParam("VpcId", vpcId)
                .formParam("CidrBlock", cidr).formParam("AvailabilityZone", zone)
                .when().post("/")
                .then().statusCode(200)
                .extract().path("CreateSubnetResponse.subnet.subnetId");
    }

    private String awaitJsonPath(String containerId, String kind, String name, String jsonPath, String expected)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 120_000;
        String value = "";
        while (System.currentTimeMillis() < deadline) {
            ContainerExec.Result result = kubectl(dockerClient, containerId, "get", kind, name,
                    "--output", "jsonpath=" + jsonPath);
            value = result.stdout().trim();
            if (result.exitCode() == 0 && (expected == null ? !value.isEmpty() : expected.equals(value))) {
                return value;
            }
            Thread.sleep(2000);
        }
        return fail(kind + " " + name + " " + jsonPath + " stayed at '" + value + "'");
    }
}
