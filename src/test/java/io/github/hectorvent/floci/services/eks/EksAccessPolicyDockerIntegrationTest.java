package io.github.hectorvent.floci.services.eks;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.services.eks.model.AccessConfig;
import io.github.hectorvent.floci.services.eks.model.AccessScope;
import io.github.hectorvent.floci.services.eks.model.AssociateAccessPolicyRequest;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ClusterStatus;
import io.github.hectorvent.floci.services.eks.model.CreateAccessEntryRequest;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.testutil.FreePorts;
import io.github.hectorvent.floci.testutil.SigV4TokenTestHelper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.xml.XmlPath;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(EksAccessPolicyDockerIntegrationTest.Profile.class)
class EksAccessPolicyDockerIntegrationTest {

    public static final class Profile implements QuarkusTestProfile {
        private static final int PORT = freePort();

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.services.eks.mock", "false",
                    "quarkus.http.test-port", String.valueOf(PORT),
                    "quarkus.http.port", String.valueOf(PORT),
                    "floci.port", String.valueOf(PORT),
                    "floci.base-url", "http://localhost:" + PORT);
        }

        private static int freePort() {
            try {
                return FreePorts.anyInterfacePort();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static final Logger LOG = Logger.getLogger(EksAccessPolicyDockerIntegrationTest.class);
    private static final String ACCOUNT = "000000000000";
    private static final String TRUST_POLICY = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Principal\":{\"AWS\":\"*\"},\"Action\":\"sts:AssumeRole\"}]}";
    private static final String STS_AUTHORIZATION =
            "AWS4-HMAC-SHA256 Credential=test/20261001/us-east-1/sts/aws4_request";
    private static final String POLICY_PREFIX = "arn:aws:eks::aws:cluster-access-policy/";

    @Inject
    DockerClient dockerClient;

    @Inject
    EksClusterManager eksClusterManager;

    @Inject
    EksService eksService;

    @Inject
    EksAccessEntryService accessEntries;

    @Inject
    IamService iamService;

    private final List<String> roles = new ArrayList<>();
    private Cluster cluster;

    private record Session(String accessKeyId, String secretAccessKey, String sessionToken) {
    }

    @BeforeEach
    void requireDocker() {
        boolean dockerAvailable = isDockerAvailable();
        if (!dockerAvailable) {
            LOG.warn("Docker daemon is not available; skipping EksAccessPolicyDockerIntegrationTest");
        }
        Assumptions.assumeTrue(dockerAvailable, "Docker daemon must be available for EKS access policy integration test");
    }

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            try {
                eksClusterManager.stopCluster(cluster);
            } catch (Exception e) {
                LOG.warnv("Failed to stop test cluster: {0}", e.getMessage());
            }
            eksService.deleteClusterForAccount(ACCOUNT, cluster.getName());
        }
        for (String role : roles) {
            try {
                iamService.deleteRole(role);
            } catch (Exception e) {
                LOG.warnv("Failed to delete test role {0}: {1}", role, e.getMessage());
            }
        }
    }

    @Test
    void accessEntriesAndAwsAuthDecideWhatEachRoleMayDo() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String clusterName = "demo-cluster-" + suffix;
        cluster = new Cluster();
        cluster.setName(clusterName);
        cluster.setAccountId(ACCOUNT);
        cluster.setArn("arn:aws:eks:us-east-1:" + ACCOUNT + ":cluster/" + clusterName);
        cluster.setCreatedAt(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        cluster.setRoleArn("arn:aws:iam::" + ACCOUNT + ":role/eks-service-role");
        cluster.setStatus(ClusterStatus.ACTIVE);
        cluster.setAccessConfig(new AccessConfig("API_AND_CONFIG_MAP", false));
        eksService.putClusterForAccount(ACCOUNT, cluster);

        String adminRole = role("admin-role-" + suffix, "/");
        String readerRole = role("reader-role-" + suffix, "/");
        String mappedRole = role("mapped-role-" + suffix, "/team/");
        String unknownRole = role("unknown-role-" + suffix, "/");
        entry(adminRole, "AmazonEKSClusterAdminPolicy");
        entry(readerRole, "AmazonEKSViewPolicy");

        eksClusterManager.startCluster(cluster);
        long deadline = System.currentTimeMillis() + 60000;
        boolean ready = false;
        while (System.currentTimeMillis() < deadline) {
            if (eksClusterManager.isReady(cluster)) {
                ready = true;
                break;
            }
            Thread.sleep(2000);
        }
        assertTrue(ready, "k3s API server must become ready within 60 seconds");
        eksClusterManager.finalizeCluster(cluster);

        String mapRoles = "- rolearn: arn:aws:iam::" + ACCOUNT + ":role/mapped-role-" + suffix + "\n"
                + "  username: mapped:{{SessionName}}\n"
                + "  groups:\n"
                + "    - namespace-listers\n";
        admin("kubectl", "create", "clusterrole", "namespace-lister", "--verb=get,list", "--resource=namespaces");
        admin("kubectl", "create", "clusterrolebinding", "namespace-listers", "--clusterrole=namespace-lister",
                "--group=namespace-listers");
        admin("kubectl", "create", "configmap", "aws-auth", "-n", "kube-system",
                "--from-literal=mapRoles=" + mapRoles);

        ContainerExec.Result admin = asRole(adminRole, "get", "namespaces");
        assertEquals(0, admin.exitCode(), "ClusterAdmin entry must list namespaces: " + admin.stderr());

        ContainerExec.Result unknown = asRole(unknownRole, "get", "namespaces");
        assertNotEquals(0, unknown.exitCode());
        assertTrue(unknown.stderr().contains("Unauthorized"), "A role with no entry must get 401: " + unknown.stderr());

        ContainerExec.Result readerPods = asRole(readerRole, "get", "pods", "-n", "kube-system");
        assertEquals(0, readerPods.exitCode(), "View entry must list pods: " + readerPods.stderr());
        ContainerExec.Result readerWrite = asRole(readerRole, "create", "configmap", "reader-write", "-n", "default");
        assertNotEquals(0, readerWrite.exitCode());
        assertTrue(readerWrite.stderr().contains("is forbidden"), "View entry must get 403 creating a ConfigMap: "
                + readerWrite.stderr());

        ContainerExec.Result mapped = asRole(mappedRole, "get", "namespaces");
        assertEquals(0, mapped.exitCode(), "aws-auth role bound by its group must list namespaces: " + mapped.stderr());
        ContainerExec.Result mappedPods = asRole(mappedRole, "get", "pods", "-n", "kube-system");
        assertTrue(mappedPods.stderr().contains("is forbidden"), "The binding grants namespaces only: "
                + mappedPods.stderr());
    }

    private String role(String name, String path) {
        roles.add(name);
        return iamService.createRole(name, path, TRUST_POLICY, null, 3600, null).getArn();
    }

    private void entry(String principalArn, String policy) {
        accessEntries.create(cluster, new CreateAccessEntryRequest(principalArn, null, null, null, null, null));
        accessEntries.associatePolicy(cluster, principalArn, new AssociateAccessPolicyRequest(POLICY_PREFIX + policy,
                new AccessScope("cluster", List.of())));
    }

    private Session assume(String roleArn) {
        XmlPath xml = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "AssumeRole")
                .formParam("RoleArn", roleArn)
                .formParam("RoleSessionName", "alice")
                .header("Authorization", STS_AUTHORIZATION)
                .when().post("/").then().statusCode(200)
                .extract().xmlPath();
        String credentials = "AssumeRoleResponse.AssumeRoleResult.Credentials.";
        return new Session(xml.getString(credentials + "AccessKeyId"), xml.getString(credentials + "SecretAccessKey"),
                xml.getString(credentials + "SessionToken"));
    }

    private ContainerExec.Result asRole(String roleArn, String... kubectlArgs) throws Exception {
        Session session = assume(roleArn);
        String token = SigV4TokenTestHelper.createEksToken(cluster.getName(), session.accessKeyId(),
                session.secretAccessKey(), Instant.now(), 60, session.sessionToken());
        List<String> cmd = new ArrayList<>(List.of("kubectl", "--kubeconfig=/dev/null",
                "--server=https://127.0.0.1:6443", "--insecure-skip-tls-verify", "--token=" + token));
        cmd.addAll(List.of(kubectlArgs));
        return ContainerExec.run(dockerClient, cluster.getContainerId(), cmd.toArray(String[]::new), 30)
                .throwIfTimedOut(cluster.getContainerId());
    }

    private void admin(String... cmd) {
        ContainerExec.Result result = ContainerExec.run(dockerClient, cluster.getContainerId(), cmd, 30)
                .throwIfTimedOut(cluster.getContainerId());
        assertEquals(0, result.exitCode(), String.join(" ", cmd) + " failed: " + result.stderr());
    }

    private boolean isDockerAvailable() {
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
