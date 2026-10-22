package io.github.hectorvent.floci.services.eks;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.eks.model.AccessConfig;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ClusterStatus;
import io.github.hectorvent.floci.services.eks.model.LogSetup;
import io.github.hectorvent.floci.services.eks.model.Logging;
import io.github.hectorvent.floci.services.eks.model.ResourcesVpcConfig;
import io.github.hectorvent.floci.services.eks.model.Update;
import io.github.hectorvent.floci.services.eks.model.UpdateClusterConfigRequest;
import io.github.hectorvent.floci.services.eks.model.UpdateClusterVersionRequest;
import io.github.hectorvent.floci.services.eks.model.UpdateParam;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Cluster-level updates (UpdateClusterConfig, UpdateClusterVersion) and the cluster-wide update index. */
@ApplicationScoped
public class EksClusterUpdateService {

    private static final Logger LOG = Logger.getLogger(EksClusterUpdateService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_PAGE_SIZE = 100;
    private static final Pattern CIDR_PATTERN = Pattern.compile("^(\\d{1,3}\\.){3}\\d{1,3}/\\d{1,2}$");
    private static final List<String> AUTHENTICATION_MODES = List.of("CONFIG_MAP", "API_AND_CONFIG_MAP", "API");

    private final StorageBackend<String, Update> updates;
    private final EksAddonService addons;

    @Inject
    public EksClusterUpdateService(StorageFactory factory, EksAddonService addons) {
        this(factory.create("eks", "eks-cluster-updates.json", new TypeReference<Map<String, Update>>() {}), addons);
    }

    EksClusterUpdateService(StorageBackend<String, Update> updates, EksAddonService addons) {
        this.updates = updates;
        this.addons = addons;
    }

    @RegisterForReflection
    public record Page(List<String> updateIds, String nextToken) {}

    public synchronized Update updateClusterConfig(Cluster cluster, UpdateClusterConfigRequest request) {
        requireActive(cluster);
        if (request == null) {
            throw invalid("Request body is required");
        }
        boolean vpc = request.resourcesVpcConfig() != null;
        boolean logging = request.logging() != null;
        boolean access = request.accessConfig() != null && request.accessConfig().authenticationMode() != null;
        int requested = (vpc ? 1 : 0) + (logging ? 1 : 0) + (access ? 1 : 0);
        if (requested == 0) {
            throw invalid("No update specified: provide resourcesVpcConfig, logging, or accessConfig");
        }
        if (requested > 1) {
            throw invalid("Only one type of update can be allowed.");
        }
        Update update;
        if (vpc) {
            update = applyVpcConfig(cluster, request.resourcesVpcConfig());
        } else if (logging) {
            update = applyLogging(cluster, request.logging());
        } else {
            update = applyAuthenticationMode(cluster, request.accessConfig().authenticationMode());
        }
        updates.put(prefix(cluster) + update.id(), update);
        LOG.infov("Applied {0} {1} to EKS cluster {2}", update.type(), update.id(), cluster.getName());
        return update;
    }

    public synchronized Update updateClusterVersion(Cluster cluster, UpdateClusterVersionRequest request) {
        requireActive(cluster);
        if (request == null || request.version() == null || request.version().isBlank()) {
            throw invalid("version is required");
        }
        String requested = request.version().trim();
        Matcher matcher = EksService.K8S_VERSION_PATTERN.matcher(requested);
        if (!matcher.matches()) {
            throw invalid("The specified parameter version is not valid: " + requested);
        }
        int minor = Integer.parseInt(matcher.group(1));
        if (minor < EksService.MIN_SUPPORTED_K8S_MINOR) {
            throw invalid("Unsupported Kubernetes version '" + requested + "'. Supported versions are 1."
                    + EksService.MIN_SUPPORTED_K8S_MINOR + " and above.");
        }
        Matcher current = EksService.K8S_VERSION_PATTERN.matcher(Objects.toString(cluster.getVersion(), ""));
        int currentMinor = current.matches() ? Integer.parseInt(current.group(1)) : minor - 1;
        if (minor == currentMinor) {
            throw invalid("Cluster is already at version " + requested);
        }
        if (minor != currentMinor + 1) {
            throw invalid("Unsupported Kubernetes minor version update from " + cluster.getVersion() + " to " + requested);
        }
        cluster.setVersion(requested);
        cluster.setExplicitVersion(true);
        Update update = successful("VersionUpdate", List.of(new UpdateParam("Version", requested)));
        updates.put(prefix(cluster) + update.id(), update);
        LOG.infov("Applied VersionUpdate {0} to EKS cluster {1}: version {2}", update.id(), cluster.getName(), requested);
        return update;
    }

    public Update describeUpdate(Cluster cluster, String updateId, String addonName, String nodegroupName) {
        if (updateId == null || updateId.isBlank()) {
            throw invalid("updateId is required");
        }
        boolean scopedToSubResource = !isBlank(addonName) || !isBlank(nodegroupName);
        Optional<Update> clusterUpdate = scopedToSubResource ? Optional.empty() : updates.get(prefix(cluster) + updateId);
        Optional<Update> addonUpdate = isBlank(nodegroupName) ? addons.findUpdate(cluster, updateId, addonName)
                : Optional.empty();
        return clusterUpdate.or(() -> addonUpdate).orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "No update: " + updateId + " found for cluster: " + cluster.getName(), 404));
    }

    public Page listUpdates(Cluster cluster, String addonName, String nodegroupName,
                            Integer maxResults, String nextToken) {
        List<String> ids = new ArrayList<>();
        if (isBlank(addonName) && isBlank(nodegroupName)) {
            String prefix = prefix(cluster);
            updates.scan(key -> key.startsWith(prefix)).forEach(update -> ids.add(update.id()));
        }
        if (isBlank(nodegroupName)) {
            addons.listUpdates(cluster, addonName).forEach(update -> ids.add(update.id()));
        }
        PaginatedResult<String> page = Pagination.paginate(ids, Function.identity(), maxResults, nextToken,
                MAX_PAGE_SIZE, "InvalidParameterException");
        return new Page(page.items(), page.nextToken());
    }

    public synchronized void deleteClusterUpdates(Cluster cluster) {
        String prefix = prefix(cluster);
        updates.keys().stream().filter(key -> key.startsWith(prefix)).toList().forEach(updates::delete);
    }

    private static Update applyVpcConfig(Cluster cluster, ResourcesVpcConfig requested) {
        if (requested.getSubnetIds() != null || requested.getSecurityGroupIds() != null) {
            throw invalid("Floci does not support updating subnetIds or securityGroupIds");
        }
        if (requested.getEndpointPublicAccess() == null && requested.getEndpointPrivateAccess() == null
                && requested.getPublicAccessCidrs() == null) {
            throw invalid("resourcesVpcConfig has no updatable fields");
        }
        ResourcesVpcConfig current = cluster.getResourcesVpcConfig() == null
                ? new ResourcesVpcConfig() : cluster.getResourcesVpcConfig();
        boolean publicAccess = requested.getEndpointPublicAccess() != null ? requested.getEndpointPublicAccess()
                : !Boolean.FALSE.equals(current.getEndpointPublicAccess());
        boolean privateAccess = requested.getEndpointPrivateAccess() != null ? requested.getEndpointPrivateAccess()
                : Boolean.TRUE.equals(current.getEndpointPrivateAccess());
        if (!publicAccess && !privateAccess) {
            throw invalid("Cluster must have at least one of endpointPublicAccess or endpointPrivateAccess enabled");
        }
        List<String> cidrs = requested.getPublicAccessCidrs();
        if (cidrs != null && (cidrs.isEmpty()
                || cidrs.stream().anyMatch(cidr -> cidr == null || !CIDR_PATTERN.matcher(cidr).matches()))) {
            throw invalid("publicAccessCidrs must contain valid IPv4 CIDR blocks");
        }
        List<UpdateParam> params = new ArrayList<>();
        if (requested.getEndpointPublicAccess() != null) {
            params.add(new UpdateParam("EndpointPublicAccess", String.valueOf(publicAccess)));
        }
        if (requested.getEndpointPrivateAccess() != null) {
            params.add(new UpdateParam("EndpointPrivateAccess", String.valueOf(privateAccess)));
        }
        if (cidrs != null) {
            params.add(new UpdateParam("PublicAccessCidrs", toJson(cidrs)));
        }
        current.setEndpointPublicAccess(publicAccess);
        current.setEndpointPrivateAccess(privateAccess);
        if (cidrs != null) {
            current.setPublicAccessCidrs(List.copyOf(cidrs));
        }
        cluster.setResourcesVpcConfig(current);
        return successful("VpcConfigUpdate", params);
    }

    private static Update applyLogging(Cluster cluster, Logging requested) {
        if (requested.getClusterLogging() == null || requested.getClusterLogging().isEmpty()) {
            throw invalid("logging.clusterLogging must not be empty");
        }
        Set<String> enabled = new LinkedHashSet<>();
        if (cluster.getLogging() != null && cluster.getLogging().getClusterLogging() != null) {
            for (LogSetup setup : cluster.getLogging().getClusterLogging()) {
                if (Boolean.TRUE.equals(setup.getEnabled()) && setup.getTypes() != null) {
                    enabled.addAll(setup.getTypes());
                }
            }
        }
        for (LogSetup setup : requested.getClusterLogging()) {
            if (setup == null || setup.getTypes() == null || setup.getTypes().isEmpty()) {
                throw invalid("Each clusterLogging entry must list at least one log type");
            }
            for (String type : setup.getTypes()) {
                if (!EksService.ALL_LOG_TYPES.contains(type)) {
                    throw invalid("'" + type + "' is not a valid log type");
                }
                if (Boolean.TRUE.equals(setup.getEnabled())) {
                    enabled.add(type);
                } else {
                    enabled.remove(type);
                }
            }
        }
        List<String> enabledTypes = EksService.ALL_LOG_TYPES.stream().filter(enabled::contains).toList();
        List<String> disabledTypes = EksService.ALL_LOG_TYPES.stream().filter(type -> !enabled.contains(type)).toList();
        List<LogSetup> entries = new ArrayList<>();
        if (!enabledTypes.isEmpty()) {
            entries.add(new LogSetup(enabledTypes, true));
        }
        if (!disabledTypes.isEmpty()) {
            entries.add(new LogSetup(disabledTypes, false));
        }
        cluster.setLogging(new Logging(entries));
        return successful("LoggingUpdate", List.of(new UpdateParam("ClusterLogging", toJson(requested))));
    }

    private static Update applyAuthenticationMode(Cluster cluster, String requested) {
        int target = AUTHENTICATION_MODES.indexOf(requested);
        if (target < 0) {
            throw invalid("Invalid authenticationMode");
        }
        AccessConfig current = cluster.getAccessConfig() == null
                ? new AccessConfig("CONFIG_MAP", null) : cluster.getAccessConfig();
        int from = Math.max(0, AUTHENTICATION_MODES.indexOf(current.authenticationMode()));
        if (target == from) {
            throw invalid("No changes needed for the authentication mode");
        }
        if (target < from) {
            throw invalid("Unsupported authentication mode update from " + current.authenticationMode()
                    + " to " + requested);
        }
        cluster.setAccessConfig(new AccessConfig(requested, current.bootstrapClusterCreatorAdminPermissions()));
        return successful("AccessConfigUpdate", List.of(new UpdateParam("AuthenticationMode", requested)));
    }

    private static Update successful(String type, List<UpdateParam> params) {
        return new Update(UUID.randomUUID().toString(), "Successful", type, List.copyOf(params),
                Instant.now().toEpochMilli() / 1000.0, List.of());
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new AwsException("ServerException", "Failed to serialize update parameter", 500);
        }
    }

    private static String prefix(Cluster cluster) {
        return cluster.getArn() + "/" + Objects.toString(cluster.getCreatedAt()) + "/";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void requireActive(Cluster cluster) {
        if (cluster.getStatus() != ClusterStatus.ACTIVE) {
            throw new AwsException("InvalidRequestException", "Cluster must be ACTIVE", 400);
        }
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParameterException", message, 400);
    }
}
