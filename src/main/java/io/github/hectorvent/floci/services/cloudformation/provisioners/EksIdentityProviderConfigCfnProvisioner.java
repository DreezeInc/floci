package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.eks.EksIdentityProviderConfigService;
import io.github.hectorvent.floci.services.eks.EksService;
import io.github.hectorvent.floci.services.eks.model.AssociateIdentityProviderConfigRequest;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.IdentityProviderConfig;
import io.github.hectorvent.floci.services.eks.model.OidcIdentityProviderConfig;
import io.github.hectorvent.floci.services.eks.model.OidcIdentityProviderConfigRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::EKS::IdentityProviderConfig}, backed by
 * {@link EksIdentityProviderConfigService}. {@code Ref} is the registry primary identifier
 * {@code <IdentityProviderConfigName>|<ClusterName>|<Type>} and {@code Fn::GetAtt IdentityProviderConfigArn}
 * the config ARN, matching AWS.
 *
 * <p>Every property but Tags is createOnly: an update that keeps the identifier reuses the config it
 * already has (Oidc and Tags are not reconciled), and a change to the identifier is refused as a
 * replacement. An absent {@code IdentityProviderConfigName} is generated, as on AWS, and kept across
 * updates through the prior physical id.
 */
@ApplicationScoped
public class EksIdentityProviderConfigCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(EksIdentityProviderConfigCfnProvisioner.class);
    private static final String TYPE = "AWS::EKS::IdentityProviderConfig";
    private static final String ID_SEPARATOR = "|";
    private static final String NOT_FOUND = "ResourceNotFoundException";
    private static final int MAX_NAME_LENGTH = 100;

    private final EksService eksService;
    private final EksIdentityProviderConfigService configService;

    @Inject
    public EksIdentityProviderConfigCfnProvisioner(EksService eksService,
                                                   EksIdentityProviderConfigService configService) {
        this.eksService = eksService;
        this.configService = configService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        String clusterName = ctx.resolveRequired(props, "ClusterName", TYPE);
        String type = ctx.resolveRequired(props, "Type", TYPE);
        String name = configName(r, props, ctx);
        String id = name + ID_SEPARATOR + clusterName + ID_SEPARATOR + type;
        Cluster cluster = eksService.describeCluster(clusterName);
        IdentityProviderConfig handle = new IdentityProviderConfig(type, name);

        if (ctx.isUpdate()) {
            if (!ctx.reusesPriorEntity(id)) {
                throw CfnExceptions.replacementNotSupported("ClusterName, Type or IdentityProviderConfigName");
            }
            OidcIdentityProviderConfig existing = findConfig(cluster, handle);
            if (existing != null) {
                record(r, id, existing);
                return;
            }
        }

        JsonNode oidc = props != null && props.hasNonNull("Oidc") ? ctx.engine().resolveNode(props.get("Oidc")) : null;
        if (oidc == null || !oidc.isObject()) {
            throw new AwsException("ValidationError", TYPE + " requires Oidc", 400);
        }
        Map<String, String> tags = ctx.resolveTags(props, "Tags");
        Map<String, String> requiredClaims = requiredClaims(oidc, ctx);
        OidcIdentityProviderConfigRequest oidcRequest = new OidcIdentityProviderConfigRequest(
                name,
                ctx.resolveOptional(oidc, "IssuerUrl"),
                ctx.resolveOptional(oidc, "ClientId"),
                ctx.resolveOptional(oidc, "UsernameClaim"),
                ctx.resolveOptional(oidc, "UsernamePrefix"),
                ctx.resolveOptional(oidc, "GroupsClaim"),
                ctx.resolveOptional(oidc, "GroupsPrefix"),
                requiredClaims.isEmpty() ? null : requiredClaims);
        configService.associate(cluster, new AssociateIdentityProviderConfigRequest(oidcRequest,
                tags.isEmpty() ? null : tags, null));
        record(r, id, configService.describe(cluster, handle));
    }

    private static String configName(StackResource r, JsonNode props, ProvisionContext ctx) {
        String explicit = ctx.resolveOptional(props, "IdentityProviderConfigName");
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        String[] prior = ctx.isUpdate() ? ctx.priorPhysicalId().split("\\" + ID_SEPARATOR, -1) : new String[0];
        if (prior.length == 3 && !prior[0].isBlank()) {
            return prior[0];
        }
        return ctx.generatePhysicalName(r.getLogicalId(), MAX_NAME_LENGTH, false);
    }

    private static Map<String, String> requiredClaims(JsonNode oidc, ProvisionContext ctx) {
        Map<String, String> claims = new LinkedHashMap<>();
        if (!oidc.hasNonNull("RequiredClaims")) {
            return claims;
        }
        JsonNode resolved = ctx.engine().resolveNode(oidc.get("RequiredClaims"));
        if (resolved == null || !resolved.isArray()) {
            return claims;
        }
        for (JsonNode claim : resolved) {
            claims.put(ctx.resolveRequired(claim, "Key", TYPE), ctx.resolveRequired(claim, "Value", TYPE));
        }
        return claims;
    }

    private OidcIdentityProviderConfig findConfig(Cluster cluster, IdentityProviderConfig handle) {
        try {
            return configService.describe(cluster, handle);
        } catch (AwsException e) {
            if (!NOT_FOUND.equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("EKS identity provider config {0} on cluster {1} from the previous provision is gone, "
                    + "creating it again", handle.name(), cluster.getName());
            return null;
        }
    }

    private static void record(StackResource r, String id, OidcIdentityProviderConfig config) {
        r.setPhysicalId(id);
        r.getAttributes().put("IdentityProviderConfigArn", config.identityProviderConfigArn());
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        String[] parts = physicalId == null ? new String[0] : physicalId.split("\\" + ID_SEPARATOR, -1);
        if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
            return;
        }
        IdentityProviderConfig handle = new IdentityProviderConfig(parts[2], parts[0]);
        CfnDeletes.safeDelete("EKS identity provider config", physicalId,
                () -> configService.disassociate(eksService.describeCluster(parts[1]), handle), NOT_FOUND);
    }
}
