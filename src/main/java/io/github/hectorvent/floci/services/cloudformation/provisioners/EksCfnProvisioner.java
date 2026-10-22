package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.eks.EksService;
import io.github.hectorvent.floci.services.eks.model.AccessConfig;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ClusterStatus;
import io.github.hectorvent.floci.services.eks.model.CreateClusterRequest;
import io.github.hectorvent.floci.services.eks.model.CreateNodeGroupRequest;
import io.github.hectorvent.floci.services.eks.model.Nodegroup;
import io.github.hectorvent.floci.services.eks.model.NodegroupScalingConfig;
import io.github.hectorvent.floci.services.eks.model.NodegroupStatus;
import io.github.hectorvent.floci.services.eks.model.ResourcesVpcConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

    private final EksService eksService;
    private final Duration clusterActiveTimeout;
    private final Duration clusterActivePoll;

    @Inject
    public EksCfnProvisioner(EksService eksService) {
        this(eksService, EksClusterReadiness.ACTIVE_TIMEOUT, EksClusterReadiness.ACTIVE_POLL);
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
        request.setResourcesVpcConfig(resourcesVpcConfig(props, ctx));
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
        if (cluster.getResourcesVpcConfig() != null && cluster.getResourcesVpcConfig().getClusterSecurityGroupId() != null) {
            r.getAttributes().put("ClusterSecurityGroupId", cluster.getResourcesVpcConfig().getClusterSecurityGroupId());
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
        Cluster active = EksClusterReadiness.awaitActive(eksService, name, clusterActiveTimeout, clusterActivePoll);
        r.getAttributes().remove(CfnRollback.ROLLBACK_OWNED_ATTR);
        return active;
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

    /**
     * Without this the cluster has no subnets, so DescribeCluster reports a blank vpcId and no
     * cluster security group, unlike the same template applied to AWS.
     */
    private static ResourcesVpcConfig resourcesVpcConfig(JsonNode props, ProvisionContext ctx) {
        if (props == null || !props.hasNonNull("ResourcesVpcConfig")) {
            return null;
        }
        JsonNode resolved = ctx.engine().resolveNode(props.get("ResourcesVpcConfig"));
        if (resolved == null || !resolved.isObject()) {
            return null;
        }
        ResourcesVpcConfig vpcConfig = new ResourcesVpcConfig();
        vpcConfig.setSubnetIds(ctx.resolveStringList(resolved, "SubnetIds"));
        vpcConfig.setSecurityGroupIds(ctx.resolveStringList(resolved, "SecurityGroupIds"));
        vpcConfig.setEndpointPublicAccess(optionalBoolean(ctx.resolveOptional(resolved, "EndpointPublicAccess")));
        vpcConfig.setEndpointPrivateAccess(optionalBoolean(ctx.resolveOptional(resolved, "EndpointPrivateAccess")));
        if (resolved.has("PublicAccessCidrs")) {
            vpcConfig.setPublicAccessCidrs(ctx.resolveStringList(resolved, "PublicAccessCidrs"));
        }
        return vpcConfig;
    }

    private static Boolean optionalBoolean(String value) {
        return value == null ? null : Boolean.parseBoolean(value);
    }

    private void provisionNodegroup(StackResource r, JsonNode props, ProvisionContext ctx) {
        String clusterName = ctx.resolveOptional(props, "ClusterName");
        CreateNodeGroupRequest request = nodegroupRequest(props, ctx);
        String nodegroupName = ctx.resolveOptional(props, "NodegroupName");
        if (nodegroupName == null || nodegroupName.isBlank()) {
            nodegroupName = ctx.generatePhysicalName(r.getLogicalId(), 100, false);
        }
        request.setNodegroupName(nodegroupName);

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
        r.getAttributes().put(CfnRollback.ROLLBACK_OWNED_ATTR, "true");
        // The first nodegroup may replace the k3s node for its instance type; the cluster is CREATING until it is back.
        EksClusterReadiness.awaitActive(eksService, clusterName, clusterActiveTimeout, clusterActivePoll);
        if (nodegroup.getStatus() == NodegroupStatus.CREATE_FAILED) {
            throw new AwsException("ResourceNotReady", "Nodegroup " + id + " did not become ACTIVE: "
                    + nodegroup.getStatus() + " " + nodegroup.getHealth(), 400);
        }
        r.getAttributes().remove(CfnRollback.ROLLBACK_OWNED_ATTR);
    }

    /** ForceUpdateEnabled is left out: it only steers updates and CreateNodegroup has no counterpart. */
    private static CreateNodeGroupRequest nodegroupRequest(JsonNode props, ProvisionContext ctx) {
        CreateNodeGroupRequest request = new CreateNodeGroupRequest();
        request.setNodeRole(ctx.resolveOptional(props, "NodeRole"));
        request.setSubnets(ctx.resolveStringList(props, "Subnets"));
        request.setVersion(optionalText(props, "Version", ctx));
        request.setReleaseVersion(optionalText(props, "ReleaseVersion", ctx));
        request.setAmiType(optionalText(props, "AmiType", ctx));
        request.setCapacityType(optionalText(props, "CapacityType", ctx));
        request.setDiskSize(optionalInteger(props, "DiskSize", ctx));
        if (props != null && props.hasNonNull("InstanceTypes")) {
            request.setInstanceTypes(ctx.resolveStringList(props, "InstanceTypes"));
        }
        request.setScalingConfig(scalingConfig(resolvedObject(props, "ScalingConfig", ctx), ctx));
        request.setUpdateConfig(updateConfig(resolvedObject(props, "UpdateConfig", ctx), ctx));
        request.setRemoteAccess(remoteAccess(resolvedObject(props, "RemoteAccess", ctx), ctx));
        request.setTaints(taints(props, ctx));
        request.setLaunchTemplate(launchTemplate(resolvedObject(props, "LaunchTemplate", ctx), ctx));
        request.setNodeRepairConfig(nodeRepairConfig(resolvedObject(props, "NodeRepairConfig", ctx), ctx));
        request.setWarmPoolConfig(warmPoolConfig(resolvedObject(props, "WarmPoolConfig", ctx), ctx));
        if (props != null && props.hasNonNull("Labels")) {
            request.setLabels(ctx.resolveTags(props, "Labels"));
        }
        request.setTags(ctx.resolveTags(props, "Tags"));
        return request;
    }

    private static NodegroupScalingConfig scalingConfig(JsonNode node, ProvisionContext ctx) {
        if (node == null) {
            return null;
        }
        NodegroupScalingConfig config = new NodegroupScalingConfig();
        config.setMinSize(optionalInteger(node, "MinSize", ctx));
        config.setMaxSize(optionalInteger(node, "MaxSize", ctx));
        config.setDesiredSize(optionalInteger(node, "DesiredSize", ctx));
        return config;
    }

    private static Map<String, Object> updateConfig(JsonNode node, ProvisionContext ctx) {
        if (node == null) {
            return null;
        }
        Map<String, Object> config = new LinkedHashMap<>();
        putIfPresent(config, "maxUnavailable", optionalInteger(node, "MaxUnavailable", ctx));
        putIfPresent(config, "maxUnavailablePercentage", optionalInteger(node, "MaxUnavailablePercentage", ctx));
        putIfPresent(config, "updateStrategy", optionalText(node, "UpdateStrategy", ctx));
        return config;
    }

    private static Map<String, Object> remoteAccess(JsonNode node, ProvisionContext ctx) {
        if (node == null) {
            return null;
        }
        Map<String, Object> access = new LinkedHashMap<>();
        putIfPresent(access, "ec2SshKey", optionalText(node, "Ec2SshKey", ctx));
        if (node.hasNonNull("SourceSecurityGroups")) {
            access.put("sourceSecurityGroups", ctx.resolveStringList(node, "SourceSecurityGroups"));
        }
        return access;
    }

    private static List<Object> taints(JsonNode props, ProvisionContext ctx) {
        if (props == null || !props.hasNonNull("Taints")) {
            return null;
        }
        JsonNode resolved = ctx.engine().resolveNode(props.get("Taints"));
        if (resolved == null || !resolved.isArray()) {
            return null;
        }
        List<Object> taints = new ArrayList<>();
        for (JsonNode taint : resolved) {
            Map<String, Object> entry = new LinkedHashMap<>();
            putIfPresent(entry, "key", optionalText(taint, "Key", ctx));
            putIfPresent(entry, "value", optionalText(taint, "Value", ctx));
            putIfPresent(entry, "effect", optionalText(taint, "Effect", ctx));
            taints.add(entry);
        }
        return taints;
    }

    private static Map<String, Object> launchTemplate(JsonNode node, ProvisionContext ctx) {
        if (node == null) {
            return null;
        }
        Map<String, Object> spec = new LinkedHashMap<>();
        putIfPresent(spec, "id", optionalText(node, "Id", ctx));
        putIfPresent(spec, "name", optionalText(node, "Name", ctx));
        putIfPresent(spec, "version", optionalText(node, "Version", ctx));
        return spec;
    }

    private static Map<String, Object> nodeRepairConfig(JsonNode node, ProvisionContext ctx) {
        if (node == null) {
            return null;
        }
        Map<String, Object> config = new LinkedHashMap<>();
        putIfPresent(config, "enabled", optionalBoolean(optionalText(node, "Enabled", ctx)));
        putIfPresent(config, "maxParallelNodesRepairedCount",
                optionalInteger(node, "MaxParallelNodesRepairedCount", ctx));
        putIfPresent(config, "maxParallelNodesRepairedPercentage",
                optionalInteger(node, "MaxParallelNodesRepairedPercentage", ctx));
        putIfPresent(config, "maxUnhealthyNodeThresholdCount",
                optionalInteger(node, "MaxUnhealthyNodeThresholdCount", ctx));
        putIfPresent(config, "maxUnhealthyNodeThresholdPercentage",
                optionalInteger(node, "MaxUnhealthyNodeThresholdPercentage", ctx));
        JsonNode overrides = node.get("NodeRepairConfigOverrides");
        if (overrides != null && overrides.isArray()) {
            List<Object> entries = new ArrayList<>();
            for (JsonNode override : overrides) {
                Map<String, Object> entry = new LinkedHashMap<>();
                putIfPresent(entry, "minRepairWaitTimeMins", optionalInteger(override, "MinRepairWaitTimeMins", ctx));
                putIfPresent(entry, "nodeMonitoringCondition", optionalText(override, "NodeMonitoringCondition", ctx));
                putIfPresent(entry, "nodeUnhealthyReason", optionalText(override, "NodeUnhealthyReason", ctx));
                putIfPresent(entry, "repairAction", optionalText(override, "RepairAction", ctx));
                entries.add(entry);
            }
            config.put("nodeRepairConfigOverrides", entries);
        }
        return config;
    }

    private static Map<String, Object> warmPoolConfig(JsonNode node, ProvisionContext ctx) {
        if (node == null) {
            return null;
        }
        Map<String, Object> config = new LinkedHashMap<>();
        putIfPresent(config, "enabled", optionalBoolean(optionalText(node, "Enabled", ctx)));
        putIfPresent(config, "maxGroupPreparedCapacity", optionalInteger(node, "MaxGroupPreparedCapacity", ctx));
        putIfPresent(config, "minSize", optionalInteger(node, "MinSize", ctx));
        putIfPresent(config, "poolState", optionalText(node, "PoolState", ctx));
        putIfPresent(config, "reuseOnScaleIn", optionalBoolean(optionalText(node, "ReuseOnScaleIn", ctx)));
        return config;
    }

    private static JsonNode resolvedObject(JsonNode props, String name, ProvisionContext ctx) {
        if (props == null || !props.hasNonNull(name)) {
            return null;
        }
        JsonNode resolved = ctx.engine().resolveNode(props.get(name));
        return resolved != null && resolved.isObject() ? resolved : null;
    }

    /** Blank is absent: an intrinsic that chose AWS::NoValue resolves to "". */
    private static String optionalText(JsonNode node, String name, ProvisionContext ctx) {
        String value = ctx.resolveOptional(node, name);
        return value == null || value.isBlank() ? null : value;
    }

    private static Integer optionalInteger(JsonNode node, String name, ProvisionContext ctx) {
        String value = optionalText(node, name, ctx);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new AwsException("ValidationError",
                    NODEGROUP + " " + name + " must be an integer, got '" + value + "'", 400);
        }
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
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
