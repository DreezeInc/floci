package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.eks.model.AccessConfig;
import io.github.hectorvent.floci.services.eks.model.AccessScope;
import io.github.hectorvent.floci.services.eks.model.AssociateAccessPolicyRequest;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ClusterStatus;
import io.github.hectorvent.floci.services.eks.model.CreateAccessEntryRequest;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EksAuthorizationWebhookControllerTest {
    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private static final String CREATED = "2026-09-17T00:00:00Z";
    private static final String ROLE = "arn:aws:iam::" + ACCOUNT + ":role/team/reader-role";
    private static final Map<String, Object> READER_EXTRA = Map.of(
            "arn", List.of("arn:aws:sts::" + ACCOUNT + ":assumed-role/reader-role/alice"),
            "canonicalArn", List.of("arn:aws:iam::" + ACCOUNT + ":role/reader-role"),
            "principalId", List.of("AROAREADER:alice"),
            "sessionName", List.of("alice"),
            "accessKeyId", List.of("ASIAREADERKEY"));

    private final IamService iam = mock(IamService.class);
    private final EksService eks = mock(EksService.class);
    private final EksAccessEntryService entries = new EksAccessEntryService(new InMemoryStorage<>(), iam);
    private final EksAuthorizationWebhookController controller = new EksAuthorizationWebhookController(eks, entries);
    private final Cluster cluster = new Cluster();

    @BeforeEach
    void setup() {
        cluster.setName("demo-cluster");
        cluster.setArn("arn:aws:eks:" + REGION + ":" + ACCOUNT + ":cluster/demo-cluster");
        cluster.setCreatedAt(Instant.parse(CREATED));
        cluster.setStatus(ClusterStatus.ACTIVE);
        cluster.setAccessConfig(new AccessConfig("API", false));
        when(eks.findWebhookCluster("demo-cluster", ACCOUNT, REGION, CREATED)).thenReturn(Optional.of(cluster));
        when(iam.findRole(ACCOUNT, "reader-role")).thenReturn(Optional.of(
                new IamRole("AROAREADER", "reader-role", "/team/", ROLE, "{}")));
        entries.create(cluster, new CreateAccessEntryRequest(ROLE, null, null, null, null, null));
    }

    @Test
    void viewListsPodsButCannotReadSecretsOrCreate() {
        associate("AmazonEKSViewPolicy", cluster());

        assertTrue(allowed("list", "", "pods", "", "dev"));
        assertTrue(allowed("get", "apps", "deployments", "scale", "dev"));
        assertTrue(allowed("get", "", "pods", "log", "dev"));
        assertFalse(allowed("get", "", "secrets", "", "dev"));
        assertFalse(allowed("create", "", "configmaps", "", "dev"));
        assertFalse(allowed("delete", "", "pods", "", "dev"));
        assertFalse(allowed("create", "", "pods", "exec", "dev"));
    }

    @Test
    void adminViewReadsSecretsButCannotWrite() {
        associate("AmazonEKSAdminViewPolicy", cluster());

        assertTrue(allowed("get", "", "secrets", "", "dev"));
        assertTrue(allowed("list", "rbac.authorization.k8s.io", "clusterroles", "", ""));
        assertFalse(allowed("update", "", "secrets", "", "dev"));
        assertFalse(allowed("create", "", "configmaps", "", "dev"));
    }

    @Test
    void editCreatesDeploymentsAndSecretsButNotRbac() {
        associate("AmazonEKSEditPolicy", cluster());

        assertTrue(allowed("create", "apps", "deployments", "", "dev"));
        assertTrue(allowed("patch", "apps", "deployments", "scale", "dev"));
        assertTrue(allowed("create", "", "secrets", "", "dev"));
        assertTrue(allowed("create", "", "pods", "exec", "dev"));
        assertTrue(allowed("impersonate", "", "serviceaccounts", "", "dev"));
        assertFalse(allowed("create", "rbac.authorization.k8s.io", "rolebindings", "", "dev"));
        assertFalse(allowed("create", "", "namespaces", "", ""));
    }

    @Test
    void adminAddsNamespacedRbacButNotClusterWideResources() {
        associate("AmazonEKSAdminPolicy", cluster());

        assertTrue(allowed("create", "rbac.authorization.k8s.io", "rolebindings", "", "dev"));
        assertTrue(allowed("create", "authorization.k8s.io", "localsubjectaccessreviews", "", "dev"));
        assertTrue(allowed("create", "apps", "deployments", "", "dev"));
        assertFalse(allowed("create", "rbac.authorization.k8s.io", "clusterrolebindings", "", ""));
        assertFalse(allowed("delete", "", "nodes", "", ""));
    }

    @Test
    void clusterAdminAllowsEverythingIncludingNonResourceUrls() {
        associate("AmazonEKSClusterAdminPolicy", cluster());

        assertTrue(allowed("delete", "", "nodes", "", ""));
        assertTrue(allowed("escalate", "rbac.authorization.k8s.io", "clusterroles", "", ""));
        assertEquals(true, status(nonResource("get", "/metrics")).get("allowed"));
    }

    @Test
    void namespaceScopeGrantsOnlyInsideListedNamespacesWithTrailingWildcards() {
        associate("AmazonEKSEditPolicy", new AccessScope("namespace", List.of("dev", "team-*")));

        assertTrue(allowed("create", "", "configmaps", "", "dev"));
        assertTrue(allowed("create", "", "configmaps", "", "team-a"));
        assertTrue(allowed("get", "", "namespaces", "", "team-b"));
        assertFalse(allowed("create", "", "configmaps", "", "devel"));
        assertFalse(allowed("create", "", "configmaps", "", "prod"));
        assertFalse(allowed("list", "", "pods", "", ""));
        assertFalse(allowed("list", "", "namespaces", "", ""));
        assertFalse((Boolean) status(nonResource("get", "/healthz")).get("allowed"));
    }

    @Test
    void unknownOrNonGeneralPurposePolicyGrantsNothing() {
        associate("AmazonEKSSecretReaderPolicy", cluster());
        associate("AmazonEKSBlockStoragePolicy", cluster());

        assertFalse(allowed("get", "", "secrets", "", "dev"));
        assertFalse(allowed("list", "", "pods", "", "dev"));
    }

    @Test
    void noOpinionLeavesTheDecisionToTheOtherAuthorizersAndEchoesTheApiVersion() {
        Map<String, Object> review = sar("create", "", "configmaps", "", "dev", READER_EXTRA);
        review.put("apiVersion", "authorization.k8s.io/v1beta1");

        Map<?, ?> body = (Map<?, ?>) controller.review("demo-cluster", ACCOUNT, REGION, CREATED, review).getEntity();

        assertEquals("authorization.k8s.io/v1beta1", body.get("apiVersion"));
        assertEquals("SubjectAccessReview", body.get("kind"));
        assertEquals(Map.of("allowed", false, "denied", false), body.get("status"));
    }

    @Test
    void reviewsWithoutThePrincipalOrForAnotherPrincipalOrClusterGetNoOpinion() {
        associate("AmazonEKSClusterAdminPolicy", cluster());

        assertEquals(EksAuthorizationWebhookController.NO_OPINION,
                status(sar("get", "", "pods", "", "dev", Map.of())));
        Map<String, Object> otherPrincipal = new HashMap<>(READER_EXTRA);
        otherPrincipal.put("canonicalArn", List.of("arn:aws:iam::" + ACCOUNT + ":role/other-role"));
        assertEquals(EksAuthorizationWebhookController.NO_OPINION,
                status(sar("get", "", "pods", "", "dev", otherPrincipal)));
        Map<String, Object> recreated = new HashMap<>(READER_EXTRA);
        recreated.put("principalId", List.of("AROARECREATED:alice"));
        assertEquals(EksAuthorizationWebhookController.NO_OPINION,
                status(sar("get", "", "pods", "", "dev", recreated)));
        Map<?, ?> otherIncarnation = (Map<?, ?>) controller.review("demo-cluster", ACCOUNT, REGION, "0",
                sar("get", "", "pods", "", "dev", READER_EXTRA)).getEntity();
        assertEquals(EksAuthorizationWebhookController.NO_OPINION, otherIncarnation.get("status"));
    }

    @Test
    void configMapModeClusterGetsNoOpinion() {
        associate("AmazonEKSClusterAdminPolicy", cluster());
        cluster.setAccessConfig(new AccessConfig("CONFIG_MAP", false));

        assertFalse(allowed("get", "", "pods", "", "dev"));
    }

    @Test
    void rootEntryIsFoundByItsCanonicalArn() {
        String root = "arn:aws:iam::" + ACCOUNT + ":root";
        entries.bootstrapCreator(cluster, root);
        Map<String, Object> rootExtra = Map.of("canonicalArn", List.of(root), "principalId", List.of(ACCOUNT));

        assertEquals(true, status(sar("delete", "", "nodes", "", "", rootExtra)).get("allowed"));
    }

    private boolean allowed(String verb, String group, String resource, String subresource, String namespace) {
        return Boolean.TRUE.equals(status(sar(verb, group, resource, subresource, namespace, READER_EXTRA))
                .get("allowed"));
    }

    private void associate(String policy, AccessScope scope) {
        entries.associatePolicy(cluster, ROLE, new AssociateAccessPolicyRequest(
                "arn:aws:eks::aws:cluster-access-policy/" + policy, scope));
    }

    private static AccessScope cluster() {
        return new AccessScope("cluster", List.of());
    }

    private Map<?, ?> status(Map<String, Object> review) {
        Map<?, ?> body = (Map<?, ?>) controller.review("demo-cluster", ACCOUNT, REGION, CREATED, review).getEntity();
        return (Map<?, ?>) body.get("status");
    }

    private static Map<String, Object> sar(String verb, String group, String resource, String subresource,
                                           String namespace, Map<String, Object> extra) {
        Map<String, Object> attributes = Map.of("verb", verb, "group", group, "resource", resource,
                "subresource", subresource, "namespace", namespace, "version", "v1");
        Map<String, Object> review = new HashMap<>();
        review.put("apiVersion", "authorization.k8s.io/v1");
        review.put("kind", "SubjectAccessReview");
        review.put("spec", Map.of("resourceAttributes", attributes, "user", "reader", "groups",
                List.of("system:authenticated"), "extra", extra));
        return review;
    }

    private static Map<String, Object> nonResource(String verb, String path) {
        return Map.of("apiVersion", "authorization.k8s.io/v1", "kind", "SubjectAccessReview",
                "spec", Map.of("nonResourceAttributes", Map.of("verb", verb, "path", path),
                        "user", "reader", "extra", READER_EXTRA));
    }
}
