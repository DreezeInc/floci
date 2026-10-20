package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.eks.EksAccessEntryService;
import io.github.hectorvent.floci.services.eks.EksService;
import io.github.hectorvent.floci.services.eks.model.AccessEntry;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.CreateAccessEntryRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::EKS::AccessEntry}, backed by
 * {@link EksAccessEntryService}. {@code Ref} is the registry primary identifier
 * {@code <PrincipalArn>|<ClusterName>} and {@code Fn::GetAtt AccessEntryArn} the entry ARN, matching
 * AWS.
 *
 * <p>PrincipalArn, ClusterName and Type are createOnly: an update that keeps the identifier reuses
 * the entry it already has (its mutable properties are not reconciled), and a change to the
 * identifier is refused as a replacement. {@code AccessPolicies} is accepted and not associated,
 * because the EKS API has no access-policy association yet; the omission is logged once per entry.
 */
@ApplicationScoped
public class EksAccessEntryCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(EksAccessEntryCfnProvisioner.class);
    private static final String TYPE = "AWS::EKS::AccessEntry";
    private static final String ID_SEPARATOR = "|";
    private static final String NOT_FOUND = "ResourceNotFoundException";

    private final EksService eksService;
    private final EksAccessEntryService accessEntryService;

    @Inject
    public EksAccessEntryCfnProvisioner(EksService eksService, EksAccessEntryService accessEntryService) {
        this.eksService = eksService;
        this.accessEntryService = accessEntryService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        String clusterName = ctx.resolveRequired(props, "ClusterName", TYPE);
        String principalArn = ctx.resolveRequired(props, "PrincipalArn", TYPE);
        String id = principalArn + ID_SEPARATOR + clusterName;
        Cluster cluster = EksClusterReadiness.awaitActive(eksService, clusterName);
        warnOnAccessPolicies(r, props, ctx);

        if (ctx.isUpdate()) {
            if (!ctx.reusesPriorEntity(id)) {
                throw CfnExceptions.replacementNotSupported("PrincipalArn or ClusterName");
            }
            AccessEntry existing = findEntry(cluster, principalArn);
            if (existing != null) {
                record(r, id, existing);
                return;
            }
        }

        Map<String, String> tags = ctx.resolveTags(props, "Tags");
        // The service rejects any kubernetesGroups on an EC2_LINUX entry, so an absent property must
        // stay null rather than become an empty list.
        List<String> groups = props != null && props.hasNonNull("KubernetesGroups")
                ? ctx.resolveStringList(props, "KubernetesGroups") : null;
        CreateAccessEntryRequest request = new CreateAccessEntryRequest(
                principalArn,
                ctx.resolveOptional(props, "Type"),
                ctx.resolveOptional(props, "Username"),
                groups,
                tags.isEmpty() ? null : tags,
                null);
        record(r, id, accessEntryService.create(cluster, request));
    }

    private static void warnOnAccessPolicies(StackResource r, JsonNode props, ProvisionContext ctx) {
        if (props == null || !props.hasNonNull("AccessPolicies")) {
            return;
        }
        JsonNode policies = ctx.engine().resolveNode(props.get("AccessPolicies"));
        if (policies != null && policies.isArray() && !policies.isEmpty()) {
            LOG.warnv("{0} {1}: AccessPolicies accepted but not associated; "
                    + "EKS access-policy association is not implemented", TYPE, r.getLogicalId());
        }
    }

    private AccessEntry findEntry(Cluster cluster, String principalArn) {
        try {
            return accessEntryService.describe(cluster, principalArn);
        } catch (AwsException e) {
            if (!NOT_FOUND.equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("EKS access entry {0} on cluster {1} from the previous provision is gone, creating it again",
                    principalArn, cluster.getName());
            return null;
        }
    }

    private static void record(StackResource r, String id, AccessEntry entry) {
        r.setPhysicalId(id);
        r.getAttributes().put("AccessEntryArn", entry.accessEntryArn());
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        int separator = physicalId == null ? -1 : physicalId.lastIndexOf(ID_SEPARATOR);
        if (separator <= 0 || separator == physicalId.length() - 1) {
            return;
        }
        String principalArn = physicalId.substring(0, separator);
        String clusterName = physicalId.substring(separator + 1);
        CfnDeletes.safeDelete("EKS access entry", physicalId,
                () -> accessEntryService.delete(eksService.describeCluster(clusterName), principalArn), NOT_FOUND);
    }
}
