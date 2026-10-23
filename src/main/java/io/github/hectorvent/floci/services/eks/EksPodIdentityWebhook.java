package io.github.hectorvent.floci.services.eks;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.ContainerCaBundle;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Admission logic behind the EKS pod identity mutating webhook.
 *
 * <p>Real EKS mutates a pod at admission in two cases. When the pod's service account carries the
 * IRSA {@code eks.amazonaws.com/role-arn} annotation, the pod identity webhook that ships with
 * every EKS control plane projects a service account token for STS and sets the web identity
 * environment variables, so the SDK credential chain assumes the role with
 * {@code sts:AssumeRoleWithWebIdentity}. When the service account has a pod identity association
 * and the {@code eks-pod-identity-agent} addon is installed, the mutation projects a token with the
 * pod identity audience and sets the two container credentials environment variables instead.
 * Floci reproduces both mutations; a pod that qualifies for both gets both.
 *
 * <p>With {@code floci.services.eks.inject-endpoint-url} on, every mutated container also gets
 * {@code AWS_ENDPOINT_URL} pointing at Floci, so the SDKs inside the cluster call the emulator
 * instead of AWS without each chart being told where Floci is.
 *
 * <p>Each node routes {@link #CREDENTIALS_FULL_URI} to {@link EksPodIdentityCredentialsController},
 * which tells pods apart by the token they present (see {@link EksPodNetworkRouting}).
 *
 * <p>Every path here admits the pod. A cluster that is gone, an addon that is not installed, a
 * malformed review and an internal error all return {@code allowed: true} with no patch, which is
 * what {@code failurePolicy: Ignore} promises on the k3s side.
 */
@ApplicationScoped
public class EksPodIdentityWebhook {

    private static final Logger LOG = Logger.getLogger(EksPodIdentityWebhook.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Real EKS only injects when the pod identity agent is installed on the cluster. */
    static final String POD_IDENTITY_ADDON = "eks-pod-identity-agent";

    static final String TOKEN_VOLUME_NAME = "eks-pod-identity-token";
    static final String TOKEN_AUDIENCE = "pods.eks.amazonaws.com"; // partition-literal: pod-identity token audience; no source outside the commercial partition (P9)
    static final String TOKEN_MOUNT_PATH = "/var/run/secrets/pods.eks.amazonaws.com/serviceaccount"; // partition-literal: the projected-token mount path the EKS agent hardcodes
    static final String TOKEN_FILE_NAME = "eks-pod-identity-token";
    static final String TOKEN_FILE_PATH = TOKEN_MOUNT_PATH + "/" + TOKEN_FILE_NAME;
    static final int TOKEN_EXPIRATION_SECONDS = 86400;
    static final int TOKEN_DEFAULT_MODE = 420;

    static final String ENV_CREDENTIALS_FULL_URI = "AWS_CONTAINER_CREDENTIALS_FULL_URI";
    static final String ENV_AUTHORIZATION_TOKEN_FILE = "AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE";
    static final String CREDENTIALS_FULL_URI = "http://169.254.170.23/v1/credentials";

    static final String IRSA_ROLE_ARN_ANNOTATION = "eks.amazonaws.com/role-arn"; // partition-literal: the IRSA annotation key is the same in every partition
    static final String IRSA_AUDIENCE_ANNOTATION = "eks.amazonaws.com/audience"; // partition-literal: the IRSA annotation key is the same in every partition
    static final String IRSA_TOKEN_EXPIRATION_ANNOTATION = "eks.amazonaws.com/token-expiration"; // partition-literal: the IRSA annotation key is the same in every partition
    static final String IRSA_DEFAULT_AUDIENCE = "sts.amazonaws.com"; // partition-literal: the IRSA default audience is sts.amazonaws.com in every partition
    static final String IRSA_TOKEN_VOLUME_NAME = "aws-iam-token";
    static final String IRSA_TOKEN_MOUNT_PATH = "/var/run/secrets/eks.amazonaws.com/serviceaccount"; // partition-literal: the projected-token mount path the EKS webhook hardcodes
    static final String IRSA_TOKEN_FILE_NAME = "token";
    static final String IRSA_TOKEN_FILE_PATH = IRSA_TOKEN_MOUNT_PATH + "/" + IRSA_TOKEN_FILE_NAME;
    /** The real webhook refuses a shorter expiration and falls back to its default. */
    static final int IRSA_MIN_TOKEN_EXPIRATION_SECONDS = 600;

    static final String ENV_STS_REGIONAL_ENDPOINTS = "AWS_STS_REGIONAL_ENDPOINTS";
    static final String ENV_DEFAULT_REGION = "AWS_DEFAULT_REGION";
    static final String ENV_REGION = "AWS_REGION";
    static final String ENV_ROLE_ARN = "AWS_ROLE_ARN";
    static final String ENV_WEB_IDENTITY_TOKEN_FILE = "AWS_WEB_IDENTITY_TOKEN_FILE";
    static final String ENV_ENDPOINT_URL = "AWS_ENDPOINT_URL";
    static final String ENV_CA_BUNDLE = "AWS_CA_BUNDLE";
    static final String CA_BUNDLE_VOLUME_NAME = "floci-ca-bundle";

    private static final String DEFAULT_API_VERSION = "admission.k8s.io/v1";
    private static final String DEFAULT_SERVICE_ACCOUNT = "default";

    /** One credential mechanism's share of the patch: its token volume, the mount, and the env. */
    record Injection(Map<String, Object> volume, Map<String, Object> mount, List<Map<String, Object>> env) {}

    private final EksService eks;
    private final EksAddonService addons;
    private final EksPodIdentityAssociationService associations;
    private final EksClusterManager clusters;
    private final DockerHostResolver dockerHostResolver;
    private final EmulatorConfig config;
    private final Supplier<Optional<Path>> caBundle;

    @Inject
    public EksPodIdentityWebhook(EksService eks, EksAddonService addons,
                                 EksPodIdentityAssociationService associations,
                                 EksClusterManager clusters, DockerHostResolver dockerHostResolver,
                                 EmulatorConfig config) {
        this(eks, addons, associations, clusters, dockerHostResolver, config,
                () -> ContainerCaBundle.hostPath(config));
    }

    EksPodIdentityWebhook(EksService eks, EksAddonService addons,
                          EksPodIdentityAssociationService associations,
                          EksClusterManager clusters, DockerHostResolver dockerHostResolver,
                          EmulatorConfig config, Supplier<Optional<Path>> caBundle) {
        this.eks = eks;
        this.addons = addons;
        this.associations = associations;
        this.clusters = clusters;
        this.dockerHostResolver = dockerHostResolver;
        this.config = config;
        this.caBundle = caBundle;
    }

    /**
     * Reviews one pod CREATE and returns the {@code AdmissionReview} to hand back to the API server.
     * The account comes from the URL because the k3s API server calls this anonymously: without it
     * the cluster would only ever be looked up in the default account.
     */
    public Map<String, Object> review(String clusterName, String accountId, Map<String, Object> admissionReview) {
        String apiVersion = admissionReview != null && admissionReview.get("apiVersion") instanceof String version
                ? version : DEFAULT_API_VERSION;
        Map<String, Object> request = admissionReview == null ? null : asMap(admissionReview.get("request"));
        String uid = request != null && request.get("uid") instanceof String value ? value : "";
        try {
            return admissionReview(apiVersion, uid, decide(clusterName, accountId, request));
        } catch (RuntimeException e) {
            LOG.warnv("EKS pod-identity-webhook: admitting pod unchanged for cluster {0} after an "
                    + "internal error: {1}", clusterName, e.toString());
            return admissionReview(apiVersion, uid, List.of());
        }
    }

    private List<Map<String, Object>> decide(String clusterName, String accountId,
                                             Map<String, Object> request) {
        if (request == null) {
            return List.of();
        }
        Map<String, Object> pod = asMap(request.get("object"));
        if (pod == null) {
            return List.of();
        }
        String namespace = namespaceOf(request, pod);
        if (namespace == null) {
            return List.of();
        }
        Optional<Cluster> cluster = eks.findAuthenticationCluster(accountId, clusterName);
        if (cluster.isEmpty()) {
            return List.of();
        }
        String serviceAccount = serviceAccountOf(pod);
        List<Injection> injections = new ArrayList<>();
        irsaInjection(cluster.get(), namespace, serviceAccount).ifPresent(injections::add);
        if (podIdentityAgentInstalled(cluster.get()) && hasAssociation(cluster.get(), namespace, serviceAccount)) {
            injections.add(podIdentityInjection());
        }
        if (injections.isEmpty()) {
            return List.of();
        }
        if (!config.services().eks().injectEndpointUrl()) {
            return buildPatch(pod, injections, List.of());
        }
        // The SDKs' web-identity STS call ignores AWS_ENDPOINT_URL, so it reaches Floci only through
        // spoofed AWS names over HTTPS, and the pod has to trust the CA the k3s node already carries.
        if (caBundle.get().isPresent()) {
            injections.add(caBundleInjection());
        }
        return buildPatch(pod, injections,
                List.of(env(ENV_ENDPOINT_URL, "http://" + dockerHostResolver.resolve() + ":" + config.port())));
    }

    private Optional<Injection> irsaInjection(Cluster cluster, String namespace, String serviceAccount) {
        Map<String, String> annotations = clusters.serviceAccountAnnotations(cluster, namespace, serviceAccount);
        String roleArn = annotations.get(IRSA_ROLE_ARN_ANNOTATION);
        if (roleArn == null || roleArn.isBlank()) {
            return Optional.empty();
        }
        String audience = annotations.getOrDefault(IRSA_AUDIENCE_ANNOTATION, IRSA_DEFAULT_AUDIENCE);
        int expiration = irsaTokenExpiration(annotations.get(IRSA_TOKEN_EXPIRATION_ANNOTATION));
        List<Map<String, Object>> env = new ArrayList<>();
        env.add(env(ENV_STS_REGIONAL_ENDPOINTS, "regional"));
        String region = clusters.clusterRegion(cluster);
        if (region != null && !region.isBlank()) {
            env.add(env(ENV_DEFAULT_REGION, region));
            env.add(env(ENV_REGION, region));
        }
        env.add(env(ENV_ROLE_ARN, roleArn));
        env.add(env(ENV_WEB_IDENTITY_TOKEN_FILE, IRSA_TOKEN_FILE_PATH));
        return Optional.of(new Injection(
                projectedTokenVolume(IRSA_TOKEN_VOLUME_NAME, audience, expiration, IRSA_TOKEN_FILE_NAME),
                mount(IRSA_TOKEN_VOLUME_NAME, IRSA_TOKEN_MOUNT_PATH),
                List.copyOf(env)));
    }

    static int irsaTokenExpiration(String annotation) {
        if (annotation == null || annotation.isBlank()) {
            return TOKEN_EXPIRATION_SECONDS;
        }
        try {
            int seconds = Integer.parseInt(annotation.strip());
            return seconds < IRSA_MIN_TOKEN_EXPIRATION_SECONDS ? TOKEN_EXPIRATION_SECONDS : seconds;
        } catch (NumberFormatException e) {
            return TOKEN_EXPIRATION_SECONDS;
        }
    }

    /** Mounts the node's copy of Floci's CA bundle and points the AWS SDKs at it. */
    static Injection caBundleInjection() {
        Map<String, Object> volume = new LinkedHashMap<>();
        volume.put("name", CA_BUNDLE_VOLUME_NAME);
        Map<String, Object> hostPath = new LinkedHashMap<>();
        hostPath.put("path", ContainerCaBundle.CONTAINER_PATH);
        hostPath.put("type", "File");
        volume.put("hostPath", hostPath);
        return new Injection(volume, mount(CA_BUNDLE_VOLUME_NAME, ContainerCaBundle.CONTAINER_PATH),
                List.of(env(ENV_CA_BUNDLE, ContainerCaBundle.CONTAINER_PATH)));
    }

    static Injection podIdentityInjection() {
        return new Injection(tokenVolume(), tokenMount(), credentialsEnv());
    }

    private boolean podIdentityAgentInstalled(Cluster cluster) {
        try {
            addons.describe(cluster, POD_IDENTITY_ADDON);
            return true;
        } catch (AwsException e) {
            LOG.debugv("EKS pod-identity-webhook: cluster {0} has no {1} addon, admitting pods unchanged ({2})",
                    cluster.getName(), POD_IDENTITY_ADDON, e.getMessage());
            return false;
        }
    }

    private boolean hasAssociation(Cluster cluster, String namespace, String serviceAccount) {
        try {
            return !associations.list(cluster, namespace, serviceAccount, 1, null).associations().isEmpty();
        } catch (AwsException e) {
            LOG.debugv("EKS pod-identity-webhook: could not list associations for {0}/{1} on cluster {2} ({3})",
                    namespace, serviceAccount, cluster.getName(), e.getMessage());
            return false;
        }
    }

    /**
     * The JSONPatch that turns a pod into an injected pod: one projected token volume per
     * mechanism, then the mounts and the environment variables on every init container and
     * container. An entry the pod already carries under the same name is left alone, matching the
     * real webhook, which never overrides a value the workload set itself.
     */
    static List<Map<String, Object>> buildPatch(Map<String, Object> pod, List<Injection> injections,
                                                List<Map<String, Object>> sharedEnv) {
        Map<String, Object> spec = asMap(pod.get("spec"));
        if (spec == null) {
            return List.of();
        }
        List<Map<String, Object>> patch = new ArrayList<>();
        List<Object> volumes = asList(spec.get("volumes"));
        List<Map<String, Object>> missingVolumes = new ArrayList<>();
        for (Injection injection : injections) {
            if (volumes == null || !containsNamed(volumes, String.valueOf(injection.volume().get("name")))) {
                missingVolumes.add(injection.volume());
            }
        }
        if (volumes == null) {
            patch.add(operation("/spec/volumes", missingVolumes));
        } else {
            for (Map<String, Object> volume : missingVolumes) {
                patch.add(operation("/spec/volumes/-", volume));
            }
        }
        List<Map<String, Object>> mounts = injections.stream().map(Injection::mount).toList();
        List<Map<String, Object>> env = new ArrayList<>();
        injections.forEach(injection -> env.addAll(injection.env()));
        env.addAll(sharedEnv);
        patchContainers(patch, spec, "initContainers", mounts, env);
        patchContainers(patch, spec, "containers", mounts, env);
        return patch;
    }

    private static void patchContainers(List<Map<String, Object>> patch, Map<String, Object> spec, String field,
                                        List<Map<String, Object>> mounts, List<Map<String, Object>> env) {
        List<Object> containers = asList(spec.get(field));
        if (containers == null) {
            return;
        }
        for (int index = 0; index < containers.size(); index++) {
            Map<String, Object> container = asMap(containers.get(index));
            if (container == null) {
                continue;
            }
            String path = "/spec/" + field + "/" + index;
            appendMissing(patch, path + "/volumeMounts", asList(container.get("volumeMounts")), mounts);
            appendMissing(patch, path + "/env", asList(container.get("env")), env);
        }
    }

    private static void appendMissing(List<Map<String, Object>> patch, String path, List<Object> existing,
                                      List<Map<String, Object>> wanted) {
        List<Map<String, Object>> missing = new ArrayList<>();
        for (Map<String, Object> entry : wanted) {
            if (existing == null || !containsNamed(existing, String.valueOf(entry.get("name")))) {
                missing.add(entry);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        if (existing == null) {
            patch.add(operation(path, missing));
        } else {
            for (Map<String, Object> entry : missing) {
                patch.add(operation(path + "/-", entry));
            }
        }
    }

    static Map<String, Object> tokenVolume() {
        return projectedTokenVolume(TOKEN_VOLUME_NAME, TOKEN_AUDIENCE, TOKEN_EXPIRATION_SECONDS, TOKEN_FILE_NAME);
    }

    static Map<String, Object> projectedTokenVolume(String name, String audience, int expirationSeconds, String path) {
        return Map.of(
                "name", name,
                "projected", Map.of(
                        "defaultMode", TOKEN_DEFAULT_MODE,
                        "sources", List.of(Map.of("serviceAccountToken", Map.of(
                                "audience", audience,
                                "expirationSeconds", expirationSeconds,
                                "path", path)))));
    }

    static Map<String, Object> tokenMount() {
        return mount(TOKEN_VOLUME_NAME, TOKEN_MOUNT_PATH);
    }

    static Map<String, Object> mount(String volumeName, String mountPath) {
        return Map.of("name", volumeName, "mountPath", mountPath, "readOnly", true);
    }

    static List<Map<String, Object>> credentialsEnv() {
        return List.of(
                env(ENV_CREDENTIALS_FULL_URI, CREDENTIALS_FULL_URI),
                env(ENV_AUTHORIZATION_TOKEN_FILE, TOKEN_FILE_PATH));
    }

    static Map<String, Object> env(String name, String value) {
        return Map.of("name", name, "value", value);
    }

    private static Map<String, Object> operation(String path, Object value) {
        return Map.of("op", "add", "path", path, "value", value);
    }

    private static Map<String, Object> admissionReview(String apiVersion, String uid,
                                                       List<Map<String, Object>> patch) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("uid", uid);
        response.put("allowed", true);
        if (!patch.isEmpty()) {
            response.put("patchType", "JSONPatch");
            response.put("patch", encodePatch(patch));
        }
        return Map.of("apiVersion", apiVersion, "kind", "AdmissionReview", "response", response);
    }

    private static String encodePatch(List<Map<String, Object>> patch) {
        try {
            return Base64.getEncoder().encodeToString(MAPPER.writeValueAsBytes(patch));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize the pod identity JSONPatch", e);
        }
    }

    /**
     * The namespace the admission decision is made against: the API server always sets it on the
     * request, and the pod's own metadata is the fallback for a hand-made review.
     */
    private static String namespaceOf(Map<String, Object> request, Map<String, Object> pod) {
        if (request.get("namespace") instanceof String namespace && !namespace.isBlank()) {
            return namespace;
        }
        Map<String, Object> metadata = asMap(pod.get("metadata"));
        if (metadata != null && metadata.get("namespace") instanceof String namespace && !namespace.isBlank()) {
            return namespace;
        }
        return null;
    }

    /** Kubernetes defaults an omitted service account to {@code default} before webhooks run. */
    private static String serviceAccountOf(Map<String, Object> pod) {
        Map<String, Object> spec = asMap(pod.get("spec"));
        if (spec != null && spec.get("serviceAccountName") instanceof String name && !name.isBlank()) {
            return name;
        }
        return DEFAULT_SERVICE_ACCOUNT;
    }

    private static boolean containsNamed(List<Object> entries, String name) {
        for (Object entry : entries) {
            Map<String, Object> map = asMap(entry);
            if (map != null && name.equals(map.get("name"))) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return value instanceof List<?> list ? (List<Object>) list : null;
    }
}
