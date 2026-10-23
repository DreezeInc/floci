package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.eks.model.AccessConfig;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ClusterStatus;
import io.github.hectorvent.floci.services.eks.model.CreateAccessEntryRequest;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.testutil.IamServiceTestHelper;
import io.github.hectorvent.floci.testutil.SigV4TokenTestHelper;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EksTokenWebhookControllerTest {

    private static final String CLUSTER_NAME = "demo";
    private static final String ACCESS_KEY_ID = "AKIDEKSTEST";
    private static final String SECRET_ACCESS_KEY = "eks-secret-key";
    private static final String DEFAULT_ACCOUNT = "000000000000";
    private static final String OTHER_ACCOUNT = "111122223333";
    private static final Instant CREATED = Instant.parse("2026-09-17T00:00:00Z");
    private final IamService iamService = IamServiceTestHelper.iamServiceWithUserKeyInAccount(
            ACCESS_KEY_ID, SECRET_ACCESS_KEY, DEFAULT_ACCOUNT, DEFAULT_ACCOUNT);
    private final EmulatorConfig config = mock(EmulatorConfig.class);
    private final EksService eks = mock(EksService.class);
    private final EksClusterManager clusters = mock(EksClusterManager.class);
    private final Cluster defaultCluster = cluster(DEFAULT_ACCOUNT);
    private final Cluster otherCluster = cluster(OTHER_ACCOUNT);
    private final EksTokenWebhookController controller = controller(iamService);

    @BeforeEach
    void clusters() {
        when(config.defaultAccountId()).thenReturn(DEFAULT_ACCOUNT);
        when(eks.findAuthenticationCluster(DEFAULT_ACCOUNT, CLUSTER_NAME)).thenReturn(Optional.of(defaultCluster));
        when(eks.findAuthenticationCluster(OTHER_ACCOUNT, CLUSTER_NAME)).thenReturn(Optional.of(otherCluster));
        when(eks.findWebhookCluster(any(), any(), any(), any())).thenCallRealMethod();
        when(clusters.readAwsAuth(any())).thenReturn(EksAwsAuth.NONE);
    }

    private EksTokenWebhookController controller(IamService iam) {
        EksAccessEntryService entries = new EksAccessEntryService(new InMemoryStorage<>(), iamService);
        entries.create(defaultCluster, new CreateAccessEntryRequest(
                "arn:aws:iam::" + DEFAULT_ACCOUNT + ":user/test-user", "STANDARD", "tester", List.of("testers"),
                null, null));
        return new EksTokenWebhookController(new EksTokenValidator(iam), new EksIamAuthentication(iam, eks, entries,
                clusters, new EksWorkerAuthentication(iam, eks, null, entries), config));
    }

    private static Cluster cluster(String account) {
        Cluster cluster = new Cluster();
        cluster.setName(CLUSTER_NAME);
        cluster.setArn("arn:aws:eks:us-east-1:" + account + ":cluster/" + CLUSTER_NAME);
        cluster.setCreatedAt(CREATED);
        cluster.setStatus(ClusterStatus.ACTIVE);
        cluster.setAccessConfig(new AccessConfig("API", false));
        return cluster;
    }

    @Test
    void scopedWebhookAuthenticatesAKeyOnlyOnTheClusterHoldingItsEntry() throws Exception {
        Response own = controller.reviewScoped(CLUSTER_NAME, DEFAULT_ACCOUNT, "us-east-1", CREATED.toString(),
                tokenReview(validToken()));
        Response otherAccount = controller.reviewScoped(CLUSTER_NAME, OTHER_ACCOUNT, "us-east-1",
                CREATED.toString(), tokenReview(validToken()));
        Response otherIncarnation = controller.reviewScoped(CLUSTER_NAME, DEFAULT_ACCOUNT, "us-east-1", "0",
                tokenReview(validToken()));

        assertTrue((Boolean) status(own).get("authenticated"));
        assertFalse((Boolean) status(otherAccount).get("authenticated"));
        assertFalse((Boolean) status(otherIncarnation).get("authenticated"));
    }

    @Test
    void anotherAccountsKeyIsRejectedOnTheDefaultAccountsCluster() throws Exception {
        IamService otherAccountKeys = IamServiceTestHelper.iamServiceWithUserKeyInAccount(
                ACCESS_KEY_ID, SECRET_ACCESS_KEY, OTHER_ACCOUNT, DEFAULT_ACCOUNT);
        EksTokenWebhookController scoped = controller(otherAccountKeys);

        Response response = scoped.reviewScoped(CLUSTER_NAME, DEFAULT_ACCOUNT, "us-east-1", CREATED.toString(),
                tokenReview(validToken()));

        assertFalse((Boolean) status(response).get("authenticated"));
    }

    @Test
    void webhookRejectsAnInactiveKey() throws Exception {
        IamService inactive = IamServiceTestHelper.iamServiceWithUserKeyInAccount(
                ACCESS_KEY_ID, SECRET_ACCESS_KEY, DEFAULT_ACCOUNT, DEFAULT_ACCOUNT);
        EksTokenWebhookController controllerWithInactiveKey = controller(inactive);
        inactive.updateAccessKey("test-user", ACCESS_KEY_ID, "Inactive");

        Response response = controllerWithInactiveKey.review(CLUSTER_NAME, null, null, null, tokenReview(validToken()));

        assertFalse((Boolean) status(response).get("authenticated"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> status(Response response) {
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        return (Map<String, Object>) body.get("status");
    }

    private Map<String, Object> tokenReview(String token) {
        return Map.of(
                "apiVersion", "authentication.k8s.io/v1",
                "kind", "TokenReview",
                "spec", Map.of("token", token));
    }

    @Test
    @SuppressWarnings("unchecked")
    void awsIamTokenAuthenticatesAsItsAccessEntryNotAsClusterAdmin() throws Exception {
        Response response = controller.review(CLUSTER_NAME, null, null, null, tokenReview(validToken()));

        Map<String, Object> status = status(response);
        assertEquals(Boolean.TRUE, status.get("authenticated"));

        Map<String, Object> user = (Map<String, Object>) status.get("user");
        assertEquals("tester", user.get("username"));
        assertEquals(List.of("testers"), user.get("groups"));
    }

    @Test
    void ownAccountKeyWithoutAnAccessEntryIsRejected() throws Exception {
        EksAccessEntryService empty = new EksAccessEntryService(new InMemoryStorage<>(), iamService);
        EksTokenWebhookController withoutEntry = new EksTokenWebhookController(new EksTokenValidator(iamService),
                new EksIamAuthentication(iamService, eks, empty, clusters,
                        new EksWorkerAuthentication(iamService, eks, null, empty), config));

        assertEquals(Boolean.FALSE, status(withoutEntry.review(CLUSTER_NAME, null, null, null,
                tokenReview(validToken()))).get("authenticated"));
        assertEquals(Boolean.FALSE, status(withoutEntry.reviewScoped(CLUSTER_NAME, DEFAULT_ACCOUNT, "us-east-1",
                CREATED.toString(), tokenReview(validToken()))).get("authenticated"));
    }

    @Test
    void unrecognisedTokenIsRejected() {
        Response response = controller.review(CLUSTER_NAME, null, null, null, tokenReview("some-random-bearer-token"));
        assertEquals(Boolean.FALSE, status(response).get("authenticated"));
    }

    @Test
    void emptyOrMalformedReviewIsRejected() {
        assertFalse((Boolean) status(controller.review(CLUSTER_NAME, null, null, null, Map.of())).get("authenticated"));
        assertFalse((Boolean) status(controller.review(CLUSTER_NAME, null, null, null,
                Map.of("spec", Map.of()))).get("authenticated"));
    }

    @Test
    void responseIsAlwaysAWellFormedTokenReview() throws Exception {
        Response response = controller.review(CLUSTER_NAME, null, null, null, tokenReview(validToken()));
        Map<?, ?> body = (Map<?, ?>) response.getEntity();
        assertEquals("authentication.k8s.io/v1", body.get("apiVersion"));
        assertEquals("TokenReview", body.get("kind"));
        assertTrue(body.containsKey("status"));
    }

    @Test
    void responseEchoesRequestApiVersion() throws Exception {
        // The kube-apiserver defaults to the v1beta1 webhook API and cannot convert a v1
        // response back to v1beta1, so the response apiVersion MUST match the request's.
        Map<String, Object> v1beta1Review = Map.of(
                "apiVersion", "authentication.k8s.io/v1beta1",
                "kind", "TokenReview",
                "spec", Map.of("token", validToken()));

        Response response = controller.review(CLUSTER_NAME, null, null, null, v1beta1Review);
        Map<?, ?> body = (Map<?, ?>) response.getEntity();
        assertEquals("authentication.k8s.io/v1beta1", body.get("apiVersion"));
        assertEquals(Boolean.TRUE, status(response).get("authenticated"));
    }

    @Test
    void tokenBoundToAnotherClusterIsRejected() throws Exception {
        String token = SigV4TokenTestHelper.createEksToken(
                "other-cluster", ACCESS_KEY_ID, SECRET_ACCESS_KEY, Instant.now(), 60);

        Response response = controller.review(CLUSTER_NAME, null, null, null, tokenReview(token));

        assertEquals(Boolean.FALSE, status(response).get("authenticated"));
    }

    private String validToken() throws Exception {
        return SigV4TokenTestHelper.createEksToken(
                CLUSTER_NAME, ACCESS_KEY_ID, SECRET_ACCESS_KEY, Instant.now(), 60);
    }
}
