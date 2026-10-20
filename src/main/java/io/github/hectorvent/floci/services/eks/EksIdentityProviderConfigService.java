package io.github.hectorvent.floci.services.eks;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.eks.model.AssociateIdentityProviderConfigRequest;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ClusterStatus;
import io.github.hectorvent.floci.services.eks.model.IdentityProviderConfig;
import io.github.hectorvent.floci.services.eks.model.OidcIdentityProviderConfig;
import io.github.hectorvent.floci.services.eks.model.OidcIdentityProviderConfigRequest;
import io.github.hectorvent.floci.services.eks.model.Update;
import io.github.hectorvent.floci.services.eks.model.UpdateParam;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * OIDC identity provider config metadata per cluster. Associating is metadata only: the issuer is
 * never contacted, so a config is ACTIVE at once. Each association and disassociation is an update
 * that the cluster-level DescribeUpdate/ListUpdates can see.
 */
@ApplicationScoped
public class EksIdentityProviderConfigService {

    public static final String OIDC = "oidc";
    public static final String ASSOCIATE_UPDATE = "AssociateIdentityProviderConfig";
    public static final String DISASSOCIATE_UPDATE = "DisassociateIdentityProviderConfig";

    private static final Logger LOG = Logger.getLogger(EksIdentityProviderConfigService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_NAME_LENGTH = 100;

    private final StorageBackend<String, StoredConfig> configs;
    private final StorageBackend<String, Update> updates;

    @Inject
    public EksIdentityProviderConfigService(StorageFactory factory) {
        this(factory.create("eks", "eks-identity-provider-configs.json",
                        new TypeReference<Map<String, StoredConfig>>() {}),
                factory.create("eks", "eks-identity-provider-config-updates.json",
                        new TypeReference<Map<String, Update>>() {}));
    }

    EksIdentityProviderConfigService(StorageBackend<String, StoredConfig> configs,
                                     StorageBackend<String, Update> updates) {
        this.configs = configs;
        this.updates = updates;
    }

    @RegisterForReflection
    public record StoredConfig(OidcIdentityProviderConfig config, String clientRequestToken, Update update) {}

    @RegisterForReflection
    public record Page(List<IdentityProviderConfig> identityProviderConfigs, String nextToken) {}

    public synchronized Update associate(Cluster cluster, AssociateIdentityProviderConfigRequest request) {
        requireActive(cluster);
        if (request == null || request.oidc() == null) {
            throw invalid("oidc is required");
        }
        OidcIdentityProviderConfigRequest oidc = request.oidc();
        String name = oidc.identityProviderConfigName();
        if (isBlank(name) || name.length() > MAX_NAME_LENGTH) {
            throw invalid("oidc.identityProviderConfigName must be 1 to " + MAX_NAME_LENGTH + " characters");
        }
        if (isBlank(oidc.issuerUrl()) || !oidc.issuerUrl().startsWith("https://")) {
            throw invalid("oidc.issuerUrl must be an https URL");
        }
        if (isBlank(oidc.clientId())) {
            throw invalid("oidc.clientId is required");
        }
        for (String prefix : List.of(Objects.toString(oidc.usernamePrefix(), ""),
                Objects.toString(oidc.groupsPrefix(), ""))) {
            if (prefix.contains("system:")) {
                throw invalid("usernamePrefix and groupsPrefix cannot contain system:");
            }
        }
        Map<String, String> requiredClaims = oidc.requiredClaims() == null ? Map.of() : oidc.requiredClaims();
        if (requiredClaims.entrySet().stream().anyMatch(claim -> isBlank(claim.getKey())
                || claim.getKey().length() > 63 || isBlank(claim.getValue()) || claim.getValue().length() > 253)) {
            throw invalid("requiredClaims keys must be 1 to 63 and values 1 to 253 characters");
        }
        Map<String, String> tags = request.tags() == null ? Map.of() : request.tags();
        if (tags.size() > 50 || tags.entrySet().stream().anyMatch(tag -> isBlank(tag.getKey())
                || tag.getKey().length() > 128 || tag.getValue() == null || tag.getValue().length() > 256)) {
            throw invalid("Invalid identity provider config tags");
        }
        String prefix = prefix(cluster);
        String token = request.clientRequestToken();
        Optional<StoredConfig> replay = token == null ? Optional.empty()
                : configs.scan(key -> key.startsWith(prefix)).stream()
                        .filter(stored -> token.equals(stored.clientRequestToken())).findFirst();
        if (replay.isPresent()) {
            OidcIdentityProviderConfig previous = replay.get().config();
            if (name.equals(previous.identityProviderConfigName()) && oidc.issuerUrl().equals(previous.issuerUrl())
                    && oidc.clientId().equals(previous.clientId())
                    && Objects.equals(oidc.usernameClaim(), previous.usernameClaim())
                    && Objects.equals(oidc.usernamePrefix(), previous.usernamePrefix())
                    && Objects.equals(oidc.groupsClaim(), previous.groupsClaim())
                    && Objects.equals(oidc.groupsPrefix(), previous.groupsPrefix())
                    && requiredClaims.equals(previous.requiredClaims()) && tags.equals(previous.tags())) {
                return replay.get().update();
            }
            throw invalid("clientRequestToken was already used with different parameters");
        }
        if (configs.get(prefix + name).isPresent()) {
            throw new AwsException("ResourceInUseException", "Identity provider config already exists: " + name, 409);
        }
        // EKS allows a single OIDC identity provider per cluster.
        if (!configs.scan(key -> key.startsWith(prefix)).isEmpty()) {
            throw new AwsException("ResourceInUseException",
                    "Cluster " + cluster.getName() + " already has an identity provider config associated", 409);
        }
        String[] clusterArn = cluster.getArn().split(":", 6);
        String arn = "arn:" + clusterArn[1] + ":eks:" + clusterArn[3] + ":" + clusterArn[4]
                + ":identityproviderconfig/" + cluster.getName() + "/" + OIDC + "/" + name + "/" + UUID.randomUUID();
        OidcIdentityProviderConfig config = new OidcIdentityProviderConfig(name, arn, cluster.getName(),
                oidc.issuerUrl(), oidc.clientId(), oidc.usernameClaim(), oidc.usernamePrefix(), oidc.groupsClaim(),
                oidc.groupsPrefix(), Map.copyOf(requiredClaims), Map.copyOf(tags), "ACTIVE");
        Update update = successful(ASSOCIATE_UPDATE, name);
        configs.put(prefix + name, new StoredConfig(config, token, update));
        updates.put(prefix + update.id(), update);
        LOG.infov("Associated identity provider config {0} with EKS cluster {1}", name, cluster.getName());
        return update;
    }

    public synchronized Update disassociate(Cluster cluster, IdentityProviderConfig handle) {
        requireActive(cluster);
        String name = requireHandle(handle);
        String key = prefix(cluster) + name;
        if (configs.get(key).isEmpty()) {
            throw notFound(cluster, name);
        }
        configs.delete(key);
        Update update = successful(DISASSOCIATE_UPDATE, name);
        updates.put(prefix(cluster) + update.id(), update);
        LOG.infov("Disassociated identity provider config {0} from EKS cluster {1}", name, cluster.getName());
        return update;
    }

    public synchronized OidcIdentityProviderConfig describe(Cluster cluster, IdentityProviderConfig handle) {
        String name = requireHandle(handle);
        return configs.get(prefix(cluster) + name).map(StoredConfig::config)
                .orElseThrow(() -> notFound(cluster, name));
    }

    public synchronized Page list(Cluster cluster, Integer maxResults, String nextToken) {
        String prefix = prefix(cluster);
        List<IdentityProviderConfig> all = configs.scan(key -> key.startsWith(prefix)).stream()
                .map(stored -> new IdentityProviderConfig(OIDC, stored.config().identityProviderConfigName()))
                .sorted(Comparator.comparing(IdentityProviderConfig::name))
                .toList();
        PaginatedResult<IdentityProviderConfig> page = Pagination.paginate(all, IdentityProviderConfig::name,
                maxResults, nextToken, MAX_PAGE_SIZE, "InvalidParameterException");
        return new Page(page.items(), page.nextToken());
    }

    public Optional<Update> findUpdate(Cluster cluster, String updateId) {
        return updates.get(prefix(cluster) + updateId);
    }

    public List<Update> listUpdates(Cluster cluster) {
        String prefix = prefix(cluster);
        return updates.scan(key -> key.startsWith(prefix));
    }

    public synchronized void deleteClusterConfigs(Cluster cluster) {
        String prefix = prefix(cluster);
        configs.keys().stream().filter(key -> key.startsWith(prefix)).toList().forEach(configs::delete);
        updates.keys().stream().filter(key -> key.startsWith(prefix)).toList().forEach(updates::delete);
    }

    private static String requireHandle(IdentityProviderConfig handle) {
        if (handle == null || isBlank(handle.name())) {
            throw invalid("identityProviderConfig.name is required");
        }
        if (!OIDC.equalsIgnoreCase(handle.type())) {
            throw invalid("identityProviderConfig.type must be oidc");
        }
        return handle.name();
    }

    private static Update successful(String type, String name) {
        String value;
        try {
            value = MAPPER.writeValueAsString(List.of(new IdentityProviderConfig(OIDC, name)));
        } catch (JsonProcessingException e) {
            throw new AwsException("ServerException", "Failed to serialize update parameter", 500);
        }
        return new Update(UUID.randomUUID().toString(), "Successful", type,
                List.of(new UpdateParam("IdentityProviderConfig", value)), Instant.now().toEpochMilli() / 1000.0,
                List.of());
    }

    private static String prefix(Cluster cluster) {
        // A recreated cluster must not inherit configs or updates from its predecessor.
        return cluster.getArn() + "/" + Objects.toString(cluster.getCreatedAt()) + "/";
    }

    private static void requireActive(Cluster cluster) {
        if (cluster.getStatus() != ClusterStatus.ACTIVE) {
            throw new AwsException("InvalidRequestException", "Cluster must be ACTIVE", 400);
        }
    }

    private static AwsException notFound(Cluster cluster, String name) {
        return new AwsException("ResourceNotFoundException",
                "No identity provider config: " + name + " found for cluster: " + cluster.getName(), 404);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParameterException", message, 400);
    }
}
