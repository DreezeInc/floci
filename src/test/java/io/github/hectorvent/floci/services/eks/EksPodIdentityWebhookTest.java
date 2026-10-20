package io.github.hectorvent.floci.services.eks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.services.eks.model.Addon;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.PodIdentityAssociationSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.github.hectorvent.floci.testing.InlineMocks.inlineMock;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class EksPodIdentityWebhookTest {

    private static final String CLUSTER_NAME = "demo";
    private static final String ACCOUNT_ID = "123456789012";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EksService eks;
    private EksAddonService addons;
    private EksPodIdentityAssociationService associations;
    private EksClusterManager clusters;
    private EmulatorConfig.EksServiceConfig eksConfig;
    private Cluster cluster;
    private Optional<Path> caBundle;
    private EksPodIdentityWebhook webhook;

    @BeforeEach
    void setUp() {
        eks = Mockito.mock(EksService.class);
        addons = Mockito.mock(EksAddonService.class);
        associations = Mockito.mock(EksPodIdentityAssociationService.class);
        clusters = Mockito.mock(EksClusterManager.class);
        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        eksConfig = Mockito.mock(EmulatorConfig.EksServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.eks()).thenReturn(eksConfig);
        when(config.port()).thenReturn(4566);
        DockerHostResolver dockerHostResolver = Mockito.mock(DockerHostResolver.class);
        when(dockerHostResolver.resolve()).thenReturn("10.0.0.7");
        caBundle = Optional.empty();
        webhook = new EksPodIdentityWebhook(eks, addons, associations, clusters, dockerHostResolver, config,
                () -> caBundle);

        cluster = new Cluster();
        cluster.setName(CLUSTER_NAME);
        when(eks.findAuthenticationCluster(ACCOUNT_ID, CLUSTER_NAME)).thenReturn(Optional.of(cluster));
        when(clusters.serviceAccountAnnotations(any(), anyString(), anyString())).thenReturn(Map.of());
        when(clusters.clusterRegion(any())).thenReturn("us-east-1");
        when(addons.describe(any(), eq(EksPodIdentityWebhook.POD_IDENTITY_ADDON)))
                .thenReturn(inlineMock(Addon.class));
        when(associations.list(any(), anyString(), anyString(), any(), any()))
                .thenReturn(new EksPodIdentityAssociationService.Page(
                        List.of(inlineMock(PodIdentityAssociationSummary.class)), null));
    }

    private Map<String, Object> admissionReview(Map<String, Object> pod) {
        return Map.of(
                "apiVersion", "admission.k8s.io/v1",
                "kind", "AdmissionReview",
                "request", Map.of("uid", "req-1", "namespace", "payments", "object", pod));
    }

    private Map<String, Object> pod(String serviceAccount, List<Object> containers) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("serviceAccountName", serviceAccount);
        spec.put("containers", containers);
        return Map.of("kind", "Pod", "metadata", Map.of("name", "api"), "spec", spec);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> response(Map<String, Object> review) {
        return (Map<String, Object>) review.get("response");
    }

    private JsonNode patchOf(Map<String, Object> review) throws Exception {
        Object patch = response(review).get("patch");
        return patch == null ? null
                : MAPPER.readTree(Base64.getDecoder().decode((String) patch));
    }

    @Test
    void matchingServiceAccountIsPatched() throws Exception {
        Map<String, Object> review = webhook.review(CLUSTER_NAME, ACCOUNT_ID,
                admissionReview(pod("checkout", List.of(Map.of("name", "app")))));

        assertEquals(Boolean.TRUE, response(review).get("allowed"));
        assertEquals("req-1", response(review).get("uid"));
        assertEquals("JSONPatch", response(review).get("patchType"));
        assertEquals(3, patchOf(review).size());
        Mockito.verify(associations).list(any(), eq("payments"), eq("checkout"), any(), any());
    }

    /**
     * The exact shape an AWS SDK matches on: the environment variable names and values, the mount
     * path and the token audience. A near-miss silently does nothing, so this asserts the whole
     * patch rather than probing it. Init containers get the same treatment as containers.
     */
    @Test
    void patchMatchesTheAwsPodIdentityInjectionExactly() throws Exception {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("serviceAccountName", "checkout");
        spec.put("initContainers", List.of(Map.of("name", "migrate")));
        spec.put("containers", List.of(Map.of("name", "app")));
        Map<String, Object> pod = Map.of("kind", "Pod", "spec", spec);

        JsonNode patch = patchOf(webhook.review(CLUSTER_NAME, ACCOUNT_ID, admissionReview(pod)));

        assertEquals(MAPPER.readTree("""
                [
                  {"op":"add","path":"/spec/volumes","value":[
                    {"name":"eks-pod-identity-token","projected":{"defaultMode":420,"sources":[
                      {"serviceAccountToken":{"audience":"pods.eks.amazonaws.com",
                       "expirationSeconds":86400,"path":"eks-pod-identity-token"}}]}}]},
                  {"op":"add","path":"/spec/initContainers/0/volumeMounts","value":[
                    {"name":"eks-pod-identity-token",
                     "mountPath":"/var/run/secrets/pods.eks.amazonaws.com/serviceaccount",
                     "readOnly":true}]},
                  {"op":"add","path":"/spec/initContainers/0/env","value":[
                    {"name":"AWS_CONTAINER_CREDENTIALS_FULL_URI",
                     "value":"http://169.254.170.23/v1/credentials"},
                    {"name":"AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE",
                     "value":"/var/run/secrets/pods.eks.amazonaws.com/serviceaccount/eks-pod-identity-token"}]},
                  {"op":"add","path":"/spec/containers/0/volumeMounts","value":[
                    {"name":"eks-pod-identity-token",
                     "mountPath":"/var/run/secrets/pods.eks.amazonaws.com/serviceaccount",
                     "readOnly":true}]},
                  {"op":"add","path":"/spec/containers/0/env","value":[
                    {"name":"AWS_CONTAINER_CREDENTIALS_FULL_URI",
                     "value":"http://169.254.170.23/v1/credentials"},
                    {"name":"AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE",
                     "value":"/var/run/secrets/pods.eks.amazonaws.com/serviceaccount/eks-pod-identity-token"}]}
                ]"""), patch);
    }

    @Test
    void podMatchingNoAssociationIsAdmittedUnchanged() throws Exception {
        when(associations.list(any(), anyString(), anyString(), any(), any()))
                .thenReturn(new EksPodIdentityAssociationService.Page(List.of(), null));

        Map<String, Object> review = webhook.review(CLUSTER_NAME, ACCOUNT_ID,
                admissionReview(pod("unassociated", List.of(Map.of("name", "app")))));

        assertEquals(Boolean.TRUE, response(review).get("allowed"));
        assertNull(patchOf(review));
        assertFalse(response(review).containsKey("patchType"));
    }

    @Test
    void clusterWithoutThePodIdentityAgentAddonIsNotPatched() throws Exception {
        when(addons.describe(any(), eq(EksPodIdentityWebhook.POD_IDENTITY_ADDON)))
                .thenThrow(new AwsException("ResourceNotFoundException", "No addon", 404));

        Map<String, Object> review = webhook.review(CLUSTER_NAME, ACCOUNT_ID,
                admissionReview(pod("checkout", List.of(Map.of("name", "app")))));

        assertEquals(Boolean.TRUE, response(review).get("allowed"));
        assertNull(patchOf(review));
        Mockito.verifyNoInteractions(associations);
    }

    /** The account is in the webhook URL because the API server calls Floci with no credentials. */
    @Test
    void clusterOwnedByAnotherAccountIsNotPatched() throws Exception {
        Map<String, Object> review = webhook.review(CLUSTER_NAME, "000000000000",
                admissionReview(pod("checkout", List.of(Map.of("name", "app")))));

        assertEquals(Boolean.TRUE, response(review).get("allowed"));
        assertNull(patchOf(review));
        Mockito.verifyNoInteractions(addons, associations);
    }

    @Test
    void malformedReviewIsAdmittedRatherThanRejected() throws Exception {
        for (Map<String, Object> malformed : malformedReviews()) {
            Map<String, Object> review = webhook.review(CLUSTER_NAME, ACCOUNT_ID, malformed);
            assertEquals(Boolean.TRUE, response(review).get("allowed"), () -> "rejected " + malformed);
            assertEquals("AdmissionReview", review.get("kind"));
            assertNull(patchOf(review));
        }
        assertEquals(Boolean.TRUE, response(webhook.review(CLUSTER_NAME, ACCOUNT_ID, null)).get("allowed"));
    }

    private static List<Map<String, Object>> malformedReviews() {
        List<Map<String, Object>> reviews = new ArrayList<>();
        reviews.add(Map.of());
        reviews.add(Map.of("request", "not-an-object"));
        reviews.add(Map.of("request", Map.of("uid", "req-1")));
        reviews.add(Map.of("request", Map.of("uid", "req-1", "namespace", "payments",
                "object", Map.of("kind", "Pod"))));
        return reviews;
    }

    @Test
    void internalErrorStillAdmitsThePod() throws Exception {
        when(eks.findAuthenticationCluster(ACCOUNT_ID, CLUSTER_NAME))
                .thenThrow(new IllegalStateException("storage is gone"));

        Map<String, Object> review = webhook.review(CLUSTER_NAME, ACCOUNT_ID,
                admissionReview(pod("checkout", List.of(Map.of("name", "app")))));

        assertEquals(Boolean.TRUE, response(review).get("allowed"));
        assertNull(patchOf(review));
    }

    /** The real webhook never overrides what the workload set itself. */
    @Test
    void entriesThePodAlreadyCarriesAreLeftAlone() throws Exception {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("serviceAccountName", "checkout");
        spec.put("volumes", List.of(Map.of("name", "eks-pod-identity-token")));
        spec.put("containers", List.of(Map.of(
                "name", "app",
                "volumeMounts", List.of(Map.of("name", "eks-pod-identity-token", "mountPath", "/elsewhere")),
                "env", List.of(Map.of("name", "AWS_CONTAINER_CREDENTIALS_FULL_URI", "value", "http://127.0.0.1/x")))));

        JsonNode patch = patchOf(webhook.review(CLUSTER_NAME, ACCOUNT_ID,
                admissionReview(Map.of("kind", "Pod", "spec", spec))));

        assertEquals(1, patch.size());
        assertEquals("/spec/containers/0/env/-", patch.get(0).get("path").asText());
        assertEquals("AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE", patch.get(0).get("value").get("name").asText());
    }

    @Test
    void responseEchoesTheRequestApiVersion() {
        Map<String, Object> review = webhook.review(CLUSTER_NAME, ACCOUNT_ID, Map.of(
                "apiVersion", "admission.k8s.io/v1beta1",
                "request", Map.of("uid", "req-9")));

        assertEquals("admission.k8s.io/v1beta1", review.get("apiVersion"));
        assertTrue(response(review).containsKey("uid"));
    }

    private void irsaAnnotated(Map<String, String> annotations) {
        when(clusters.serviceAccountAnnotations(any(), anyString(), anyString())).thenReturn(annotations);
        when(addons.describe(any(), eq(EksPodIdentityWebhook.POD_IDENTITY_ADDON)))
                .thenThrow(new AwsException("ResourceNotFoundException", "no addon", 404));
    }

    /**
     * The exact shape the AWS SDK web identity provider matches on: the five environment variables
     * in the order the EKS webhook sets them, the token path, and the STS audience.
     */
    @Test
    void irsaAnnotatedServiceAccountIsPatchedLikeTheEksWebhook() throws Exception {
        irsaAnnotated(Map.of("eks.amazonaws.com/role-arn", "arn:aws:iam::123456789012:role/checkout"));

        JsonNode patch = patchOf(webhook.review(CLUSTER_NAME, ACCOUNT_ID,
                admissionReview(pod("checkout", List.of(Map.of("name", "app"))))));

        assertEquals(MAPPER.readTree("""
                [
                  {"op":"add","path":"/spec/volumes","value":[
                    {"name":"aws-iam-token","projected":{"defaultMode":420,"sources":[
                      {"serviceAccountToken":{"audience":"sts.amazonaws.com",
                       "expirationSeconds":86400,"path":"token"}}]}}]},
                  {"op":"add","path":"/spec/containers/0/volumeMounts","value":[
                    {"name":"aws-iam-token",
                     "mountPath":"/var/run/secrets/eks.amazonaws.com/serviceaccount",
                     "readOnly":true}]},
                  {"op":"add","path":"/spec/containers/0/env","value":[
                    {"name":"AWS_STS_REGIONAL_ENDPOINTS","value":"regional"},
                    {"name":"AWS_DEFAULT_REGION","value":"us-east-1"},
                    {"name":"AWS_REGION","value":"us-east-1"},
                    {"name":"AWS_ROLE_ARN","value":"arn:aws:iam::123456789012:role/checkout"},
                    {"name":"AWS_WEB_IDENTITY_TOKEN_FILE",
                     "value":"/var/run/secrets/eks.amazonaws.com/serviceaccount/token"}]}
                ]"""), patch);
        Mockito.verify(clusters).serviceAccountAnnotations(cluster, "payments", "checkout");
    }

    @Test
    void irsaAnnotationsSetTheTokenAudienceAndExpiration() throws Exception {
        irsaAnnotated(Map.of(
                "eks.amazonaws.com/role-arn", "arn:aws:iam::123456789012:role/checkout",
                "eks.amazonaws.com/audience", "custom-audience",
                "eks.amazonaws.com/token-expiration", "3600"));

        JsonNode patch = patchOf(webhook.review(CLUSTER_NAME, ACCOUNT_ID,
                admissionReview(pod("checkout", List.of(Map.of("name", "app"))))));

        JsonNode token = patch.get(0).get("value").get(0).get("projected").get("sources").get(0).get("serviceAccountToken");
        assertEquals("custom-audience", token.get("audience").asText());
        assertEquals(3600, token.get("expirationSeconds").asInt());
    }

    @Test
    void irsaTokenExpirationBelowTheEksMinimumFallsBackToTheDefault() {
        assertEquals(86400, EksPodIdentityWebhook.irsaTokenExpiration("300"));
        assertEquals(86400, EksPodIdentityWebhook.irsaTokenExpiration("not-a-number"));
        assertEquals(86400, EksPodIdentityWebhook.irsaTokenExpiration(null));
        assertEquals(7200, EksPodIdentityWebhook.irsaTokenExpiration("7200"));
    }

    @Test
    void serviceAccountWithoutAnIrsaRoleAndNoAssociationIsAdmittedUnchanged() throws Exception {
        when(associations.list(any(), anyString(), anyString(), any(), any()))
                .thenReturn(new EksPodIdentityAssociationService.Page(List.of(), null));

        Map<String, Object> review = webhook.review(CLUSTER_NAME, ACCOUNT_ID,
                admissionReview(pod("checkout", List.of(Map.of("name", "app")))));

        assertNull(patchOf(review));
    }

    @Test
    void endpointUrlIsInjectedWhenConfigured() throws Exception {
        when(eksConfig.injectEndpointUrl()).thenReturn(true);
        irsaAnnotated(Map.of("eks.amazonaws.com/role-arn", "arn:aws:iam::123456789012:role/checkout"));

        JsonNode patch = patchOf(webhook.review(CLUSTER_NAME, ACCOUNT_ID,
                admissionReview(pod("checkout", List.of(Map.of("name", "app"))))));

        JsonNode env = patch.get(2).get("value");
        assertEquals(6, env.size());
        assertEquals("AWS_ENDPOINT_URL", env.get(5).get("name").asText());
        assertEquals("http://10.0.0.7:4566", env.get(5).get("value").asText());
    }

    @Test
    void caBundleIsMountedFromTheNodeWhenFlociServesTls() throws Exception {
        when(eksConfig.injectEndpointUrl()).thenReturn(true);
        caBundle = Optional.of(Path.of("/app/data/tls/floci-ca-bundle.pem"));
        irsaAnnotated(Map.of("eks.amazonaws.com/role-arn", "arn:aws:iam::123456789012:role/checkout"));

        JsonNode patch = patchOf(webhook.review(CLUSTER_NAME, ACCOUNT_ID,
                admissionReview(pod("checkout", List.of(Map.of("name", "app"))))));

        assertEquals(MAPPER.readTree("""
                {"name":"floci-ca-bundle","hostPath":{"path":"/etc/floci-ca-bundle.pem","type":"File"}}"""),
                patch.get(0).get("value").get(1));
        assertEquals(MAPPER.readTree("""
                {"name":"floci-ca-bundle","mountPath":"/etc/floci-ca-bundle.pem","readOnly":true}"""),
                patch.get(1).get("value").get(1));
        JsonNode env = patch.get(2).get("value");
        assertEquals(7, env.size());
        assertEquals("AWS_CA_BUNDLE", env.get(5).get("name").asText());
        assertEquals("/etc/floci-ca-bundle.pem", env.get(5).get("value").asText());
        assertEquals("AWS_ENDPOINT_URL", env.get(6).get("name").asText());
    }

    @Test
    void caBundleIsLeftOutWithoutEndpointInjection() throws Exception {
        caBundle = Optional.of(Path.of("/app/data/tls/floci-ca-bundle.pem"));
        irsaAnnotated(Map.of("eks.amazonaws.com/role-arn", "arn:aws:iam::123456789012:role/checkout"));

        JsonNode patch = patchOf(webhook.review(CLUSTER_NAME, ACCOUNT_ID,
                admissionReview(pod("checkout", List.of(Map.of("name", "app"))))));

        assertEquals(1, patch.get(0).get("value").size());
        assertEquals(5, patch.get(2).get("value").size());
    }

    @Test
    void endpointUrlIsNotInjectedWithoutAnyCredentialInjection() throws Exception {
        when(eksConfig.injectEndpointUrl()).thenReturn(true);
        when(associations.list(any(), anyString(), anyString(), any(), any()))
                .thenReturn(new EksPodIdentityAssociationService.Page(List.of(), null));

        assertNull(patchOf(webhook.review(CLUSTER_NAME, ACCOUNT_ID,
                admissionReview(pod("checkout", List.of(Map.of("name", "app")))))));
    }

    /** A pod that qualifies for both mechanisms gets both volumes in one add, never a second replace. */
    @Test
    void irsaAndPodIdentityShareTheVolumesOperation() throws Exception {
        when(clusters.serviceAccountAnnotations(any(), anyString(), anyString()))
                .thenReturn(Map.of("eks.amazonaws.com/role-arn", "arn:aws:iam::123456789012:role/checkout"));

        JsonNode patch = patchOf(webhook.review(CLUSTER_NAME, ACCOUNT_ID,
                admissionReview(pod("checkout", List.of(Map.of("name", "app"))))));

        assertEquals(3, patch.size());
        assertEquals("/spec/volumes", patch.get(0).get("path").asText());
        assertEquals(List.of("aws-iam-token", "eks-pod-identity-token"),
                List.of(patch.get(0).get("value").get(0).get("name").asText(),
                        patch.get(0).get("value").get(1).get("name").asText()));
        assertEquals(2, patch.get(1).get("value").size());
        assertEquals(7, patch.get(2).get("value").size());
    }
}
