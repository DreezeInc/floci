package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.eks.EksAddonService;
import io.github.hectorvent.floci.services.eks.EksService;
import io.github.hectorvent.floci.services.eks.model.Addon;
import io.github.hectorvent.floci.services.eks.model.AddonPodIdentityAssociation;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.CreateAddonRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::EKS::Addon}, backed by {@link EksAddonService}.
 * {@code Ref} is the registry primary identifier {@code <ClusterName>|<AddonName>} and
 * {@code Fn::GetAtt Arn} the addon ARN, matching AWS.
 *
 * <p>ClusterName and AddonName are createOnly: an update that keeps both reuses the addon it already
 * has (its mutable properties are not reconciled), and a change to either is refused as a
 * replacement. {@code PreserveOnDelete} is accepted and ignored: a stack delete removes the addon.
 */
@ApplicationScoped
public class EksAddonCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(EksAddonCfnProvisioner.class);
    private static final String TYPE = "AWS::EKS::Addon";
    private static final String ID_SEPARATOR = "|";
    private static final String NOT_FOUND = "ResourceNotFoundException";

    private final EksService eksService;
    private final EksAddonService addonService;

    @Inject
    public EksAddonCfnProvisioner(EksService eksService, EksAddonService addonService) {
        this.eksService = eksService;
        this.addonService = addonService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        String clusterName = ctx.resolveRequired(props, "ClusterName", TYPE);
        String addonName = ctx.resolveRequired(props, "AddonName", TYPE);
        String id = clusterName + ID_SEPARATOR + addonName;
        Cluster cluster = eksService.describeCluster(clusterName);

        if (ctx.isUpdate()) {
            if (!ctx.reusesPriorEntity(id)) {
                throw CfnExceptions.replacementNotSupported("ClusterName or AddonName");
            }
            Addon existing = findAddon(cluster, addonName);
            if (existing != null) {
                record(r, id, existing);
                return;
            }
        }

        Map<String, String> tags = ctx.resolveTags(props, "Tags");
        CreateAddonRequest request = new CreateAddonRequest(
                addonName,
                ctx.resolveOptional(props, "AddonVersion"),
                ctx.resolveOptional(props, "ServiceAccountRoleArn"),
                ctx.resolveOptional(props, "ResolveConflicts"),
                null,
                ctx.resolveOptional(props, "ConfigurationValues"),
                tags.isEmpty() ? null : tags,
                podIdentityAssociations(props, ctx));
        record(r, id, addonService.create(cluster, request));
    }

    private static List<AddonPodIdentityAssociation> podIdentityAssociations(JsonNode props, ProvisionContext ctx) {
        if (props == null || !props.hasNonNull("PodIdentityAssociations")) {
            return null;
        }
        JsonNode resolved = ctx.engine().resolveNode(props.get("PodIdentityAssociations"));
        if (resolved == null || !resolved.isArray()) {
            return null;
        }
        List<AddonPodIdentityAssociation> associations = new ArrayList<>();
        for (JsonNode association : resolved) {
            associations.add(new AddonPodIdentityAssociation(
                    ctx.resolveOptional(association, "RoleArn"),
                    ctx.resolveOptional(association, "ServiceAccount")));
        }
        return associations;
    }

    private Addon findAddon(Cluster cluster, String addonName) {
        try {
            return addonService.describe(cluster, addonName);
        } catch (AwsException e) {
            if (!NOT_FOUND.equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("EKS addon {0} on cluster {1} from the previous provision is gone, creating it again",
                    addonName, cluster.getName());
            return null;
        }
    }

    private static void record(StackResource r, String id, Addon addon) {
        r.setPhysicalId(id);
        r.getAttributes().put("Arn", addon.addonArn());
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        int separator = physicalId == null ? -1 : physicalId.lastIndexOf(ID_SEPARATOR);
        if (separator <= 0 || separator == physicalId.length() - 1) {
            return;
        }
        String clusterName = physicalId.substring(0, separator);
        String addonName = physicalId.substring(separator + 1);
        // A cluster already gone took its addons with it, so its not-found is tolerated too.
        CfnDeletes.safeDelete("EKS addon", physicalId,
                () -> addonService.delete(eksService.describeCluster(clusterName), addonName, false), NOT_FOUND);
    }
}
