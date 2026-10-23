package io.github.hectorvent.floci.services.eks;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ClusterIdentity;
import io.github.hectorvent.floci.services.eks.model.ClusterStatus;
import io.github.hectorvent.floci.services.eks.model.CreateAddonRequest;
import io.github.hectorvent.floci.services.eks.model.CreatePodIdentityAssociationRequest;
import io.github.hectorvent.floci.services.eks.model.OidcIdentity;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.testing.TestImages;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs EKS Pod Identity end to end on a real k3s node: the webhook injects an associated pod, the
 * pod fetches credentials from 169.254.170.23 with its projected token, and a pod whose service
 * account has no association is neither injected nor served credentials.
 */
@QuarkusTest
@TestProfile(EksPodIdentityClusterDockerIntegrationTest.Profile.class)
class EksPodIdentityClusterDockerIntegrationTest {

    /**
     * Serves HTTPS with a leaf from the local CA that also covers this machine's addresses, which is
     * where the API server calls the webhook when Floci runs in a container.
     */
    public static final class Profile implements QuarkusTestProfile {

        private static final Path DATA_DIR = Path.of("target/floci-eks-pod-identity-cluster-test").toAbsolutePath();
        private static final Path TLS_DIR = DATA_DIR.resolve("tls");

        static {
            try {
                List<String> sans = new ArrayList<>(List.of("localhost", "host.docker.internal"));
                for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    nic.inetAddresses()
                            .filter(address -> address instanceof Inet4Address && !address.isLoopbackAddress())
                            .map(InetAddress::getHostAddress)
                            .forEach(sans::add);
                }
                Files.createDirectories(TLS_DIR);
                CertificateGenerator.GeneratedCertificate leaf = FlociCertificateAuthority.loadOrCreate(TLS_DIR)
                        .issueServerCertificate("localhost", sans, KeyAlgorithm.RSA_2048, null);
                Files.writeString(TLS_DIR.resolve("floci-server.crt"), leaf.certificatePem());
                Files.writeString(TLS_DIR.resolve("floci-server.key"), leaf.privateKeyPem());
            } catch (IOException e) {
                throw new IllegalStateException("Could not prepare the TLS fixtures in " + TLS_DIR, e);
            }
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.ofEntries(
                    Map.entry("floci.services.eks.mock", "false"),
                    Map.entry("floci.services.eks.pod-identity-webhook", "true"),
                    Map.entry("floci.tls.enabled", "true"),
                    Map.entry("floci.tls.self-signed", "true"),
                    Map.entry("floci.tls.aws-https-port", "0"),
                    Map.entry("floci.storage.persistent-path", DATA_DIR.toString()),
                    Map.entry("quarkus.tls.key-store.pem.0.cert", TLS_DIR.resolve("floci-server.crt").toString()),
                    Map.entry("quarkus.tls.key-store.pem.0.key", TLS_DIR.resolve("floci-server.key").toString()),
                    Map.entry("quarkus.http.insecure-requests", "enabled"),
                    Map.entry("quarkus.http.test-port", "4510"),
                    Map.entry("quarkus.http.test-ssl-port", "4511"),
                    Map.entry("floci.port", "4595"),
                    Map.entry("floci.base-url", "http://localhost:4595"));
        }
    }

    private static final Logger LOG = Logger.getLogger(EksPodIdentityClusterDockerIntegrationTest.class);
    private static final String ACCOUNT = "000000000000";

    @Inject
    DockerClient dockerClient;

    @Inject
    EksService eksService;

    @Inject
    EksClusterManager eksClusterManager;

    @Inject
    EksOidcService oidcService;

    @Inject
    EksAddonService addonService;

    @Inject
    EksPodIdentityAssociationService associationService;

    @Inject
    IamService iamService;

    private Cluster cluster;
    private String roleName;

    @BeforeEach
    void requireDocker() {
        boolean dockerAvailable = isDockerAvailable();
        if (!dockerAvailable) {
            LOG.warn("Docker daemon is not available; skipping EksPodIdentityClusterDockerIntegrationTest");
        }
        Assumptions.assumeTrue(dockerAvailable, "Docker daemon must be available for the EKS Pod Identity cluster test");
    }

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            try {
                eksClusterManager.stopCluster(cluster);
            } catch (Exception e) {
                LOG.warnv("Failed to stop test cluster: {0}", e.getMessage());
            }
            try {
                eksService.deleteClusterForAccount(ACCOUNT, cluster.getName());
            } catch (Exception ignored) {
            }
        }
        if (roleName != null) {
            try {
                iamService.deleteRole(roleName);
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    void associatedPodGetsCredentialsAndUnassociatedPodDoesNot() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String clusterName = "demo-cluster-" + suffix;
        roleName = "demo-workload-role-" + suffix;
        iamService.createRole(roleName, "/", "{\"Version\":\"2012-10-17\",\"Statement\":[]}", null, 3600, null);

        cluster = new Cluster();
        cluster.setName(clusterName);
        cluster.setAccountId(ACCOUNT);
        cluster.setArn("arn:aws:eks:us-east-1:" + ACCOUNT + ":cluster/" + clusterName);
        cluster.setCreatedAt(Instant.now());
        cluster.setStatus(ClusterStatus.ACTIVE);
        cluster.setVersion(EksService.DEFAULT_K8S_VERSION);
        cluster.setRoleArn("arn:aws:iam::" + ACCOUNT + ":role/demo-cluster-role");
        String issuer = oidcService.newIssuerUrl("us-east-1");
        cluster.setIdentity(new ClusterIdentity(new OidcIdentity(issuer)));
        oidcService.ensureKeyForAccount(ACCOUNT, clusterName, issuer);
        eksService.putClusterForAccount(ACCOUNT, cluster);

        addonService.create(cluster, new CreateAddonRequest(EksPodIdentityWebhook.POD_IDENTITY_ADDON,
                null, null, null, null, null, null, null));
        associationService.create(cluster, new CreatePodIdentityAssociationRequest(clusterName, "default",
                "workload-sa", "arn:aws:iam::" + ACCOUNT + ":role/" + roleName, null, null, null, null, null));

        eksClusterManager.startCluster(cluster);
        String containerId = cluster.getContainerId();
        waitFor("k3s API server ready", 90, () -> eksClusterManager.isReady(cluster));
        eksClusterManager.finalizeCluster(cluster);
        waitFor("pod identity webhook registered", 60, () -> exec(containerId,
                "kubectl get mutatingwebhookconfiguration floci-eks-pod-identity").exitCode() == 0);

        String manifest = """
                apiVersion: v1
                kind: ServiceAccount
                metadata:
                  name: workload-sa
                  namespace: default
                ---
                apiVersion: v1
                kind: ServiceAccount
                metadata:
                  name: other-sa
                  namespace: default
                ---
                apiVersion: v1
                kind: Pod
                metadata:
                  name: associated-pod
                  namespace: default
                spec:
                  serviceAccountName: workload-sa
                  containers:
                  - name: app
                    image: %1$s
                    command: ["sleep", "3600"]
                ---
                apiVersion: v1
                kind: Pod
                metadata:
                  name: unassociated-pod
                  namespace: default
                spec:
                  serviceAccountName: other-sa
                  containers:
                  - name: app
                    image: %1$s
                    command: ["sleep", "3600"]
                    volumeMounts:
                    - name: token
                      mountPath: /var/run/secrets/demo
                      readOnly: true
                  volumes:
                  - name: token
                    projected:
                      sources:
                      - serviceAccountToken:
                          path: token
                          audience: pods.eks.amazonaws.com
                          expirationSeconds: 3600
                """.formatted(TestImages.BUSYBOX);
        ContainerExec.Result applied = exec(containerId, "cat << 'EOF' | kubectl apply -f -\n" + manifest + "EOF");
        assertEquals(0, applied.exitCode(), "kubectl apply failed: " + applied.stdout() + applied.stderr());
        waitFor("both pods running", 180, () -> {
            ContainerExec.Result phases = exec(containerId,
                    "kubectl get pod associated-pod unassociated-pod -o jsonpath='{.items[*].status.phase}'");
            return "Running Running".equals(phases.stdout().trim());
        });

        ContainerExec.Result injectedUri = exec(containerId,
                "kubectl exec associated-pod -- printenv AWS_CONTAINER_CREDENTIALS_FULL_URI");
        assertEquals(EksPodIdentityWebhook.CREDENTIALS_FULL_URI, injectedUri.stdout().trim(),
                "the webhook must inject the associated pod: " + injectedUri.stderr());

        ContainerExec.Result credentials = exec(containerId, "kubectl exec associated-pod -- sh -c "
                + "'wget -q -T 5 -O - --header \"Authorization: $(cat \"$AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE\")\" "
                + "\"$AWS_CONTAINER_CREDENTIALS_FULL_URI\"'");
        assertEquals(0, credentials.exitCode(), "credentials request failed: " + credentials.stderr());
        assertTrue(credentials.stdout().matches("(?s).*\"AccessKeyId\"\\s*:\\s*\"ASIA.*"),
                "the associated pod must get session credentials: " + credentials.stdout());

        ContainerExec.Result uninjected = exec(containerId,
                "kubectl exec unassociated-pod -- printenv AWS_CONTAINER_CREDENTIALS_FULL_URI");
        assertNotEquals(0, uninjected.exitCode(), "a pod without an association must not be injected");

        ContainerExec.Result refused = exec(containerId, "kubectl exec unassociated-pod -- sh -c "
                + "'wget -q -T 5 -O - --header \"Authorization: $(cat /var/run/secrets/demo/token)\" "
                + EksPodIdentityWebhook.CREDENTIALS_FULL_URI + " 2>&1'");
        assertNotEquals(0, refused.exitCode(), "a service account without an association must get no credentials");
        assertTrue(refused.stdout().contains("404"), "Floci must answer and refuse the token: " + refused.stdout());
    }

    private static void waitFor(String what, int seconds, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(2000);
        }
        throw new AssertionError("Timed out after " + seconds + "s waiting for " + what);
    }

    private boolean isDockerAvailable() {
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private ContainerExec.Result exec(String containerId, String script) {
        return ContainerExec.run(dockerClient, containerId, new String[]{"sh", "-c", script}, 30)
                .throwIfTimedOut(containerId);
    }
}
