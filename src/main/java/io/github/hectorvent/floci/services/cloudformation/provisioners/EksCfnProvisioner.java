package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.eks.EksService;
import io.github.hectorvent.floci.services.eks.model.AccessConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ClusterStatus;
import io.github.hectorvent.floci.services.eks.model.CreateClusterRequest;
import io.github.hectorvent.floci.services.eks.model.Nodegroup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * CloudFormation provisioning for the EKS control-plane types. {@code AWS::EKS::Cluster} and
 * {@code AWS::EKS::Nodegroup} share one provisioner over {@link EksService} because a nodegroup
 * belongs to a cluster and its delete needs the cluster name, which is stored as a create-time
 * attribute. {@code Ref} returns the cluster name for a Cluster and the nodegroup name for a
 * Nodegroup, matching what the legacy switch published.
 */
@ApplicationScoped
public class EksCfnProvisioner implements CfnResourceProvisioner {

    private static final String CLUSTER = "AWS::EKS::Cluster";
    private static final String NODEGROUP = "AWS::EKS::Nodegroup";

    /** Real-mode clusters boot a k3s container; CloudFormation reports the cluster once it is ACTIVE. */
    static final Duration CLUSTER_ACTIVE_TIMEOUT = Duration.ofMinutes(15);
    static final Duration CLUSTER_ACTIVE_POLL = Duration.ofSeconds(3);

    private final EksService eksService;
    private final Duration clusterActiveTimeout;
    private final Duration clusterActivePoll;

    @Inject
    public EksCfnProvisioner(EksService eksService) {
        this(eksService, CLUSTER_ACTIVE_TIMEOUT, CLUSTER_ACTIVE_POLL);
    }

    EksCfnProvisioner(EksService eksService, Duration clusterActiveTimeout, Duration clusterActivePoll) {
        this.eksService = eksService;
        this.clusterActiveTimeout = clusterActiveTimeout;
        this.clusterActivePoll = clusterActivePoll;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(CLUSTER, NODEGROUP);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case CLUSTER -> provisionCluster(r, props, ctx);
            case NODEGROUP -> provisionNodegroup(r, props, ctx);
            default -> throw new IllegalStateException("Unhandled type: " + r.getResourceType());
        }
    }

    private void provisionCluster(StackResource r, JsonNode props, ProvisionContext ctx) {
        String name = ctx.resolveOptional(props, "Name");
        if (name == null || name.isBlank()) {
            name = ctx.generatePhysicalName(r.getLogicalId(), 100, false);
        }
        CreateClusterRequest request = new CreateClusterRequest();
        request.setName(name);
        request.setVersion(ctx.resolveOptional(props, "Version"));
        request.setRoleArn(ctx.resolveOptional(props, "RoleArn"));
        request.setAccessConfig(accessConfig(props, ctx));
        Map<String, String> tags = ctx.resolveTags(props, "Tags");
        if (!tags.isEmpty()) {
            request.setTags(tags);
        }

        Cluster cluster = eksService.createCluster(request);
        r.setPhysicalId(cluster.getName());
        if (cluster.getStatus() == ClusterStatus.CREATING) {
            cluster = awaitClusterActive(r, cluster.getName());
        }
        r.getAttributes().put("Arn", cluster.getArn());
        if (cluster.getEndpoint() != null) {
            r.getAttributes().put("Endpoint", cluster.getEndpoint());
        }
        if (cluster.getCertificateAuthority() != null && cluster.getCertificateAuthority().getData() != null) {
            r.getAttributes().put("CertificateAuthorityData", cluster.getCertificateAuthority().getData());
        }
        if (cluster.getIdentity() != null && cluster.getIdentity().getOidc() != null
                && cluster.getIdentity().getOidc().getIssuer() != null) {
            r.getAttributes().put("OpenIdConnectIssuerUrl", cluster.getIdentity().getOidc().getIssuer());
        }
    }

    /**
     * Waits for a cluster this provision created to become ACTIVE, as CloudFormation does before
     * it reports the resource complete and lets its dependants (access entries, addons, pod
     * identity associations) proceed. The resource is marked as owned meanwhile, so a cluster
     * that never comes up is deleted by the create rollback.
     */
    private Cluster awaitClusterActive(StackResource r, String name) {
        r.getAttributes().put(CfnRollback.ROLLBACK_OWNED_ATTR, "true");
        long deadline = System.nanoTime() + clusterActiveTimeout.toNanos();
        while (true) {
            Cluster current = eksService.describeCluster(name);
            if (current.getStatus() == ClusterStatus.ACTIVE) {
                r.getAttributes().remove(CfnRollback.ROLLBACK_OWNED_ATTR);
                return current;
            }
            if (current.getStatus() != ClusterStatus.CREATING) {
                throw new AwsException("ResourceNotReady", "Cluster " + name + " did not become ACTIVE: "
                        + current.getStatus(), 400);
            }
            if (System.nanoTime() - deadline >= 0) {
                throw new AwsException("ResourceNotReady", "Cluster " + name + " was still CREATING after "
                        + clusterActiveTimeout.toSeconds() + " seconds", 400);
            }
            try {
                Thread.sleep(clusterActivePoll.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AwsException("ResourceNotReady", "Interrupted while waiting for cluster " + name, 400);
            }
        }
    }

    /**
     * Without this every CloudFormation cluster is CONFIG_MAP, and an {@code AWS::EKS::AccessEntry}
     * in the same template fails on it.
     */
    private static AccessConfig accessConfig(JsonNode props, ProvisionContext ctx) {
        if (props == null || !props.hasNonNull("AccessConfig")) {
            return null;
        }
        JsonNode resolved = ctx.engine().resolveNode(props.get("AccessConfig"));
        if (resolved == null || !resolved.isObject()) {
            return null;
        }
        String bootstrap = ctx.resolveOptional(resolved, "BootstrapClusterCreatorAdminPermissions");
        return new AccessConfig(ctx.resolveOptional(resolved, "AuthenticationMode"),
                bootstrap == null ? null : Boolean.parseBoolean(bootstrap));
    }

    private void provisionNodegroup(StackResource r, JsonNode props, ProvisionContext ctx) {
        String clusterName = ctx.resolveOptional(props, "ClusterName");
        Nodegroup request = new Nodegroup();
        String nodegroupName = ctx.resolveOptional(props, "NodegroupName");
        if (nodegroupName == null || nodegroupName.isBlank()) {
            nodegroupName = ctx.generatePhysicalName(r.getLogicalId(), 100, false);
        }
        request.setNodegroupName(nodegroupName);
        request.setNodeRole(ctx.resolveOptional(props, "NodeRole"));
        request.setSubnets(ctx.resolveStringList(props, "Subnets"));

        Nodegroup nodegroup = eksService.createNodeGroup(clusterName, request);
        // Ref (and the readOnly Id) is the composite the registry primary identifier declares,
        // clusterName/nodegroupName, matching AWS. The plain names stay as their own attributes:
        // ClusterName and NodegroupName back delete() and Fn::GetAtt.
        String id = nodegroup.getClusterName() + "/" + nodegroup.getNodegroupName();
        r.setPhysicalId(id);
        r.getAttributes().put("Id", id);
        r.getAttributes().put("ClusterName", nodegroup.getClusterName());
        r.getAttributes().put("NodegroupName", nodegroup.getNodegroupName());
        if (nodegroup.getNodegroupArn() != null) {
            r.getAttributes().put("Arn", nodegroup.getNodegroupArn());
        }
    }

    @Override
    public void delete(StackResource resource, String region) {
        switch (resource.getResourceType()) {
            case CLUSTER -> CfnDeletes.safeDelete("EKS cluster", resource.getPhysicalId(),
                    () -> eksService.deleteCluster(resource.getPhysicalId()), "ResourceNotFoundException");
            case NODEGROUP -> {
                // Deleting a nodegroup needs the cluster name and the nodegroup name, both stored as
                // create-time attributes; the physical id is now the composite clusterName/nodegroupName,
                // so it cannot be passed as the nodegroup name.
                String clusterName = resource.getAttributes().get("ClusterName");
                String nodegroupName = resource.getAttributes().get("NodegroupName");
                if (clusterName != null && !clusterName.isBlank()
                        && nodegroupName != null && !nodegroupName.isBlank()) {
                    CfnDeletes.safeDelete("EKS nodegroup", resource.getPhysicalId(),
                            () -> eksService.deleteNodeGroup(clusterName, nodegroupName),
                            "ResourceNotFoundException");
                }
            }
            default -> {
                // No other type reaches this provisioner's delete.
            }
        }
    }
}
