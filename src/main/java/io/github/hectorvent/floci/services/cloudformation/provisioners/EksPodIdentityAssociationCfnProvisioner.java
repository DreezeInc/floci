package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.eks.EksPodIdentityAssociationService;
import io.github.hectorvent.floci.services.eks.EksService;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.CreatePodIdentityAssociationRequest;
import io.github.hectorvent.floci.services.eks.model.PodIdentityAssociation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::EKS::PodIdentityAssociation}, backed by
 * {@link EksPodIdentityAssociationService}. {@code Ref} and {@code Fn::GetAtt AssociationArn} are the
 * association ARN and {@code Fn::GetAtt AssociationId} its id, matching AWS. The cluster name is
 * kept as an attribute because the delete needs it alongside the id.
 *
 * <p>ClusterName, Namespace and ServiceAccount are createOnly: an update that keeps them reuses the
 * association it already has (RoleArn and Tags are not reconciled), and a change to any of them is
 * refused as a replacement.
 */
@ApplicationScoped
public class EksPodIdentityAssociationCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(EksPodIdentityAssociationCfnProvisioner.class);
    private static final String TYPE = "AWS::EKS::PodIdentityAssociation";
    private static final String NOT_FOUND = "ResourceNotFoundException";

    private final EksService eksService;
    private final EksPodIdentityAssociationService associationService;

    @Inject
    public EksPodIdentityAssociationCfnProvisioner(EksService eksService,
                                                   EksPodIdentityAssociationService associationService) {
        this.eksService = eksService;
        this.associationService = associationService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        String clusterName = ctx.resolveRequired(props, "ClusterName", TYPE);
        String namespace = ctx.resolveRequired(props, "Namespace", TYPE);
        String serviceAccount = ctx.resolveRequired(props, "ServiceAccount", TYPE);
        String roleArn = ctx.resolveRequired(props, "RoleArn", TYPE);
        Cluster cluster = EksClusterReadiness.awaitActive(eksService, clusterName);

        if (ctx.isUpdate()) {
            if (!clusterName.equals(r.getAttributes().get("ClusterName"))) {
                throw CfnExceptions.replacementNotSupported("ClusterName");
            }
            PodIdentityAssociation existing = findAssociation(cluster, r.getAttributes().get("AssociationId"));
            if (existing != null) {
                if (!namespace.equals(existing.namespace()) || !serviceAccount.equals(existing.serviceAccount())) {
                    throw CfnExceptions.replacementNotSupported("Namespace or ServiceAccount");
                }
                record(r, existing);
                return;
            }
        }

        Map<String, String> tags = ctx.resolveTags(props, "Tags");
        CreatePodIdentityAssociationRequest request = new CreatePodIdentityAssociationRequest(
                clusterName, namespace, serviceAccount, roleArn, null,
                tags.isEmpty() ? null : tags, null, null, null);
        record(r, associationService.create(cluster, request));
    }

    private PodIdentityAssociation findAssociation(Cluster cluster, String associationId) {
        if (associationId == null || associationId.isBlank()) {
            return null;
        }
        try {
            return associationService.describe(cluster, associationId);
        } catch (AwsException e) {
            if (!NOT_FOUND.equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("EKS pod identity association {0} on cluster {1} from the previous provision is gone, "
                    + "creating it again", associationId, cluster.getName());
            return null;
        }
    }

    private static void record(StackResource r, PodIdentityAssociation association) {
        r.setPhysicalId(association.associationArn());
        r.getAttributes().put("AssociationArn", association.associationArn());
        r.getAttributes().put("AssociationId", association.associationId());
        r.getAttributes().put("ClusterName", association.clusterName());
    }

    @Override
    public void delete(StackResource resource, String region) {
        String clusterName = resource.getAttributes().get("ClusterName");
        String associationId = resource.getAttributes().get("AssociationId");
        if (clusterName == null || clusterName.isBlank() || associationId == null || associationId.isBlank()) {
            return;
        }
        CfnDeletes.safeDelete("EKS pod identity association", resource.getPhysicalId(),
                () -> associationService.delete(eksService.describeCluster(clusterName), associationId), NOT_FOUND);
    }
}
