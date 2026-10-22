package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.eks.EksService;
import io.github.hectorvent.floci.services.eks.model.CertificateAuthority;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ClusterIdentity;
import io.github.hectorvent.floci.services.eks.model.ClusterStatus;
import io.github.hectorvent.floci.services.eks.model.CreateClusterRequest;
import io.github.hectorvent.floci.services.eks.model.CreateNodeGroupRequest;
import io.github.hectorvent.floci.services.eks.model.Nodegroup;
import io.github.hectorvent.floci.services.eks.model.NodegroupStatus;
import io.github.hectorvent.floci.services.eks.model.OidcIdentity;
import io.github.hectorvent.floci.services.eks.model.ResourcesVpcConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The EKS control-plane types in isolation: the physical id (Ref) and Fn::GetAtt keys each
 * publishes, the cluster name flowing into a nodegroup delete, and the already-gone tolerance.
 */
class EksCfnProvisionerTest {

    private final EksService eks = mock(EksService.class);
    private final EksCfnProvisioner provisioner = new EksCfnProvisioner(eks);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void clusterRefIsItsNameAndItPublishesTheResolvableReadOnlyAttributes() throws Exception {
        Cluster created = new Cluster();
        created.setName("prod");
        created.setArn("arn:aws:eks:us-east-1:000000000000:cluster/prod");
        created.setEndpoint("https://prod.eks.amazonaws.com");
        created.setCertificateAuthority(new CertificateAuthority("LS0tLS1CRUdJTg=="));
        ClusterIdentity identity = new ClusterIdentity();
        identity.setOidc(new OidcIdentity("https://oidc.eks.us-east-1.amazonaws.com/id/ABC"));
        created.setIdentity(identity);
        when(eks.createCluster(any(CreateClusterRequest.class))).thenReturn(created);

        StackResource r = resource("AWS::EKS::Cluster", "Control");
        provisioner.provision(r, props("""
                {"Name": "prod", "RoleArn": "arn:aws:iam::000000000000:role/eks", "Version": "1.31"}
                """), ctx());

        assertEquals("prod", r.getPhysicalId());
        assertEquals(Set.of("Arn", "Endpoint", "CertificateAuthorityData", "OpenIdConnectIssuerUrl"),
                r.getAttributes().keySet());
        assertEquals("arn:aws:eks:us-east-1:000000000000:cluster/prod", r.getAttributes().get("Arn"));
        assertEquals("LS0tLS1CRUdJTg==", r.getAttributes().get("CertificateAuthorityData"));
        assertEquals("https://oidc.eks.us-east-1.amazonaws.com/id/ABC",
                r.getAttributes().get("OpenIdConnectIssuerUrl"));
    }

    @Test
    void clusterInCreatingStateIsReportedOnlyOnceActiveWithTheEndpointItThenHas() throws Exception {
        Cluster creating = new Cluster();
        creating.setName("prod");
        creating.setStatus(ClusterStatus.CREATING);
        Cluster active = new Cluster();
        active.setName("prod");
        active.setStatus(ClusterStatus.ACTIVE);
        active.setArn("arn:aws:eks:us-east-1:000000000000:cluster/prod");
        active.setEndpoint("https://floci-service:6500");
        active.setCertificateAuthority(new CertificateAuthority("LS0tLS1CRUdJTg=="));
        when(eks.createCluster(any(CreateClusterRequest.class))).thenReturn(creating);
        when(eks.describeCluster("prod")).thenReturn(creating, creating, active);
        EksCfnProvisioner waiting = new EksCfnProvisioner(eks, Duration.ofSeconds(5), Duration.ofMillis(1));

        StackResource r = resource("AWS::EKS::Cluster", "Control");
        waiting.provision(r, props("{\"Name\": \"prod\"}"), ctx());

        verify(eks, times(3)).describeCluster("prod");
        assertEquals("https://floci-service:6500", r.getAttributes().get("Endpoint"));
        assertEquals("LS0tLS1CRUdJTg==", r.getAttributes().get("CertificateAuthorityData"));
        assertNull(r.getAttributes().get(CfnRollback.ROLLBACK_OWNED_ATTR));
    }

    @Test
    void clusterStillCreatingAtTheDeadlineFailsTheResourceAndLeavesItRollbackOwned() throws Exception {
        Cluster creating = new Cluster();
        creating.setName("prod");
        creating.setStatus(ClusterStatus.CREATING);
        when(eks.createCluster(any(CreateClusterRequest.class))).thenReturn(creating);
        when(eks.describeCluster("prod")).thenReturn(creating);
        EksCfnProvisioner waiting = new EksCfnProvisioner(eks, Duration.ofMillis(20), Duration.ofMillis(1));

        StackResource r = resource("AWS::EKS::Cluster", "Control");
        AwsException failure = assertThrows(AwsException.class,
                () -> waiting.provision(r, props("{\"Name\": \"prod\"}"), ctx()));

        assertEquals("ResourceNotReady", failure.getErrorCode());
        assertEquals("prod", r.getPhysicalId());
        assertEquals("true", r.getAttributes().get(CfnRollback.ROLLBACK_OWNED_ATTR));
    }

    @Test
    void clusterThatFailsWhileCreatingFailsTheResource() throws Exception {
        Cluster creating = new Cluster();
        creating.setName("prod");
        creating.setStatus(ClusterStatus.CREATING);
        Cluster failed = new Cluster();
        failed.setName("prod");
        failed.setStatus(ClusterStatus.FAILED);
        when(eks.createCluster(any(CreateClusterRequest.class))).thenReturn(creating);
        when(eks.describeCluster("prod")).thenReturn(failed);
        EksCfnProvisioner waiting = new EksCfnProvisioner(eks, Duration.ofSeconds(5), Duration.ofMillis(1));

        AwsException failure = assertThrows(AwsException.class, () ->
                waiting.provision(resource("AWS::EKS::Cluster", "Control"), props("{\"Name\": \"prod\"}"), ctx()));

        assertEquals("ResourceNotReady", failure.getErrorCode());
    }

    @Test
    void nodegroupIsReportedOnlyOnceTheClusterItRebootedIsActiveAgain() throws Exception {
        Nodegroup created = new Nodegroup();
        created.setNodegroupName("workers");
        created.setClusterName("prod");
        created.setNodegroupArn("arn:aws:eks:us-east-1:000000000000:nodegroup/prod/workers/1");
        when(eks.createNodeGroup(eq("prod"), any(CreateNodeGroupRequest.class))).thenReturn(created);
        Cluster creating = new Cluster();
        creating.setName("prod");
        creating.setStatus(ClusterStatus.CREATING);
        Cluster active = new Cluster();
        active.setName("prod");
        active.setStatus(ClusterStatus.ACTIVE);
        when(eks.describeCluster("prod")).thenReturn(creating, active);
        EksCfnProvisioner waiting = new EksCfnProvisioner(eks, Duration.ofSeconds(5), Duration.ofMillis(1));

        StackResource r = resource("AWS::EKS::Nodegroup", "Workers");
        waiting.provision(r, props("{\"ClusterName\": \"prod\", \"NodegroupName\": \"workers\"}"), ctx());

        verify(eks, times(2)).describeCluster("prod");
        assertEquals("prod/workers", r.getPhysicalId());
    }

    @Test
    void clusterPassesAccessConfigAndTagsThrough() throws Exception {
        Cluster created = new Cluster();
        created.setName("prod");
        created.setArn("arn:aws:eks:us-east-1:000000000000:cluster/prod");
        when(eks.createCluster(any(CreateClusterRequest.class))).thenReturn(created);

        provisioner.provision(resource("AWS::EKS::Cluster", "Control"), props("""
                {"Name": "prod", "RoleArn": "arn:aws:iam::000000000000:role/eks",
                 "AccessConfig": {"AuthenticationMode": "API_AND_CONFIG_MAP",
                                  "BootstrapClusterCreatorAdminPermissions": false},
                 "Tags": [{"Key": "application", "Value": "demo"}]}
                """), ctx());

        ArgumentCaptor<CreateClusterRequest> request = ArgumentCaptor.forClass(CreateClusterRequest.class);
        verify(eks).createCluster(request.capture());
        assertEquals("API_AND_CONFIG_MAP", request.getValue().getAccessConfig().authenticationMode());
        assertEquals(Boolean.FALSE, request.getValue().getAccessConfig().bootstrapClusterCreatorAdminPermissions());
        assertEquals(Map.of("application", "demo"), request.getValue().getTags());
    }

    @Test
    void clusterPassesResourcesVpcConfigThroughAndPublishesTheClusterSecurityGroup() throws Exception {
        Cluster created = new Cluster();
        created.setName("prod");
        created.setArn("arn:aws:eks:us-east-1:000000000000:cluster/prod");
        ResourcesVpcConfig createdVpcConfig = new ResourcesVpcConfig();
        createdVpcConfig.setClusterSecurityGroupId("sg-cluster");
        created.setResourcesVpcConfig(createdVpcConfig);
        when(eks.createCluster(any(CreateClusterRequest.class))).thenReturn(created);

        StackResource r = resource("AWS::EKS::Cluster", "Control");
        provisioner.provision(r, props("""
                {"Name": "prod",
                 "ResourcesVpcConfig": {"SubnetIds": ["subnet-1", "subnet-2"], "SecurityGroupIds": ["sg-extra"],
                                        "EndpointPublicAccess": true, "EndpointPrivateAccess": true,
                                        "PublicAccessCidrs": ["203.0.113.0/24"]}}
                """), ctx());

        ArgumentCaptor<CreateClusterRequest> request = ArgumentCaptor.forClass(CreateClusterRequest.class);
        verify(eks).createCluster(request.capture());
        ResourcesVpcConfig vpcConfig = request.getValue().getResourcesVpcConfig();
        assertEquals(List.of("subnet-1", "subnet-2"), vpcConfig.getSubnetIds());
        assertEquals(List.of("sg-extra"), vpcConfig.getSecurityGroupIds());
        assertEquals(Boolean.TRUE, vpcConfig.getEndpointPublicAccess());
        assertEquals(Boolean.TRUE, vpcConfig.getEndpointPrivateAccess());
        assertEquals(List.of("203.0.113.0/24"), vpcConfig.getPublicAccessCidrs());
        assertEquals("sg-cluster", r.getAttributes().get("ClusterSecurityGroupId"));
    }

    @Test
    void clusterVpcConfigWithoutOptionalFieldsLeavesThemToTheServiceDefault() throws Exception {
        Cluster created = new Cluster();
        created.setName("prod");
        created.setArn("arn:aws:eks:us-east-1:000000000000:cluster/prod");
        when(eks.createCluster(any(CreateClusterRequest.class))).thenReturn(created);

        provisioner.provision(resource("AWS::EKS::Cluster", "Control"), props("""
                {"Name": "prod", "ResourcesVpcConfig": {"SubnetIds": ["subnet-1"]}}
                """), ctx());

        ArgumentCaptor<CreateClusterRequest> request = ArgumentCaptor.forClass(CreateClusterRequest.class);
        verify(eks).createCluster(request.capture());
        ResourcesVpcConfig vpcConfig = request.getValue().getResourcesVpcConfig();
        assertEquals(List.of("subnet-1"), vpcConfig.getSubnetIds());
        assertNull(vpcConfig.getEndpointPublicAccess());
        assertNull(vpcConfig.getEndpointPrivateAccess());
        assertNull(vpcConfig.getPublicAccessCidrs());
    }

    @Test
    void clusterWithoutAccessConfigLeavesItToTheServiceDefault() throws Exception {
        Cluster created = new Cluster();
        created.setName("prod");
        created.setArn("arn:aws:eks:us-east-1:000000000000:cluster/prod");
        when(eks.createCluster(any(CreateClusterRequest.class))).thenReturn(created);

        provisioner.provision(resource("AWS::EKS::Cluster", "Control"), props("{\"Name\": \"prod\"}"), ctx());

        ArgumentCaptor<CreateClusterRequest> request = ArgumentCaptor.forClass(CreateClusterRequest.class);
        verify(eks).createCluster(request.capture());
        assertNull(request.getValue().getAccessConfig());
        assertNull(request.getValue().getResourcesVpcConfig());
        assertNull(request.getValue().getTags());
    }

    @Test
    void absentClusterNameIsGeneratedFromStackAndLogicalId() throws Exception {
        Cluster created = new Cluster();
        created.setName("my-stack-Control-abc123");
        created.setArn("arn:aws:eks:us-east-1:000000000000:cluster/generated");
        when(eks.createCluster(any(CreateClusterRequest.class))).thenReturn(created);

        StackResource r = resource("AWS::EKS::Cluster", "Control");
        provisioner.provision(r, props("{}"), ctx());

        ArgumentCaptor<CreateClusterRequest> request = ArgumentCaptor.forClass(CreateClusterRequest.class);
        verify(eks).createCluster(request.capture());
        assertTrue(request.getValue().getName().matches("my-stack-Control-[0-9a-f]{12}"),
                request.getValue().getName());
    }

    @Test
    void nodegroupRefIsTheCompositeIdAndItStoresNamesForDelete() throws Exception {
        Nodegroup created = new Nodegroup();
        created.setNodegroupName("workers");
        created.setClusterName("prod");
        created.setNodegroupArn("arn:aws:eks:us-east-1:000000000000:nodegroup/prod/workers/uuid");
        when(eks.createNodeGroup(eq("prod"), any(CreateNodeGroupRequest.class))).thenReturn(created);

        StackResource r = resource("AWS::EKS::Nodegroup", "Workers");
        provisioner.provision(r, props("""
                {"ClusterName": "prod", "NodegroupName": "workers",
                 "NodeRole": "arn:aws:iam::000000000000:role/nodes", "Subnets": ["subnet-1", "subnet-2"]}
                """), ctx());

        // Ref and Id are the composite clusterName/nodegroupName, matching AWS.
        assertEquals("prod/workers", r.getPhysicalId());
        assertEquals("prod/workers", r.getAttributes().get("Id"));
        assertEquals("prod", r.getAttributes().get("ClusterName"));
        assertEquals("workers", r.getAttributes().get("NodegroupName"));
        assertEquals("arn:aws:eks:us-east-1:000000000000:nodegroup/prod/workers/uuid",
                r.getAttributes().get("Arn"));
        assertNull(r.getAttributes().get(CfnRollback.ROLLBACK_OWNED_ATTR));
        ArgumentCaptor<CreateNodeGroupRequest> request = ArgumentCaptor.forClass(CreateNodeGroupRequest.class);
        verify(eks).createNodeGroup(eq("prod"), request.capture());
        assertEquals("workers", request.getValue().getNodegroupName());
        assertEquals("arn:aws:iam::000000000000:role/nodes", request.getValue().getNodeRole());
        assertEquals(List.of("subnet-1", "subnet-2"), request.getValue().getSubnets());
    }

    @Test
    void nodegroupPassesEveryDeclaredPropertyToCreateNodegroup() throws Exception {
        when(eks.createNodeGroup(eq("demo-cluster"), any(CreateNodeGroupRequest.class)))
                .thenReturn(nodegroup("demo-cluster", "workers", NodegroupStatus.ACTIVE));

        provisioner.provision(resource("AWS::EKS::Nodegroup", "Workers"), props("""
                {"ClusterName": "demo-cluster", "NodegroupName": "workers",
                 "NodeRole": "arn:aws:iam::000000000000:role/nodes", "Subnets": ["subnet-1"],
                 "Version": "1.31", "ReleaseVersion": "1.31.0-20250101", "AmiType": "AL2023_x86_64_STANDARD",
                 "CapacityType": "SPOT", "DiskSize": 50, "InstanceTypes": ["t3.medium", "t3.large"],
                 "ScalingConfig": {"MinSize": 1, "MaxSize": 3, "DesiredSize": "2"},
                 "UpdateConfig": {"MaxUnavailablePercentage": 25, "UpdateStrategy": "MINIMAL"},
                 "RemoteAccess": {"Ec2SshKey": "demo-key", "SourceSecurityGroups": ["sg-ssh"]},
                 "Taints": [{"Key": "dedicated", "Value": "gpu", "Effect": "NO_SCHEDULE"},
                            {"Key": "spot", "Effect": "PREFER_NO_SCHEDULE"}],
                 "LaunchTemplate": {"Name": "demo-nodes", "Version": 3},
                 "NodeRepairConfig": {"Enabled": true, "MaxUnhealthyNodeThresholdPercentage": 20,
                                      "NodeRepairConfigOverrides": [{"MinRepairWaitTimeMins": 10,
                                          "NodeMonitoringCondition": "AcceleratedHardwareReady",
                                          "NodeUnhealthyReason": "NvidiaXID13Error", "RepairAction": "Reboot"}]},
                 "WarmPoolConfig": {"Enabled": "true", "MinSize": 2, "PoolState": "STOPPED", "ReuseOnScaleIn": false},
                 "ForceUpdateEnabled": true,
                 "Labels": {"role": "worker", "tier": 1},
                 "Tags": {"team": "platform"}}
                """), ctx());

        ArgumentCaptor<CreateNodeGroupRequest> captured = ArgumentCaptor.forClass(CreateNodeGroupRequest.class);
        verify(eks).createNodeGroup(eq("demo-cluster"), captured.capture());
        CreateNodeGroupRequest request = captured.getValue();
        assertEquals("1.31", request.getVersion());
        assertEquals("1.31.0-20250101", request.getReleaseVersion());
        assertEquals("AL2023_x86_64_STANDARD", request.getAmiType());
        assertEquals("SPOT", request.getCapacityType());
        assertEquals(50, request.getDiskSize());
        assertEquals(List.of("t3.medium", "t3.large"), request.getInstanceTypes());
        assertEquals(1, request.getScalingConfig().getMinSize());
        assertEquals(3, request.getScalingConfig().getMaxSize());
        assertEquals(2, request.getScalingConfig().getDesiredSize());
        assertEquals(Map.of("maxUnavailablePercentage", 25, "updateStrategy", "MINIMAL"), request.getUpdateConfig());
        assertEquals(Map.of("ec2SshKey", "demo-key", "sourceSecurityGroups", List.of("sg-ssh")),
                request.getRemoteAccess());
        assertEquals(List.of(Map.of("key", "dedicated", "value", "gpu", "effect", "NO_SCHEDULE"),
                Map.of("key", "spot", "effect", "PREFER_NO_SCHEDULE")), request.getTaints());
        assertEquals(Map.of("name", "demo-nodes", "version", "3"), request.getLaunchTemplate());
        assertEquals(Map.of("enabled", true, "maxUnhealthyNodeThresholdPercentage", 20,
                "nodeRepairConfigOverrides", List.of(Map.of("minRepairWaitTimeMins", 10,
                        "nodeMonitoringCondition", "AcceleratedHardwareReady",
                        "nodeUnhealthyReason", "NvidiaXID13Error", "repairAction", "Reboot"))),
                request.getNodeRepairConfig());
        assertEquals(Map.of("enabled", true, "minSize", 2, "poolState", "STOPPED", "reuseOnScaleIn", false),
                request.getWarmPoolConfig());
        assertEquals(Map.of("role", "worker", "tier", "1"), request.getLabels());
        assertEquals(Map.of("team", "platform"), request.getTags());
    }

    @Test
    void nodegroupLaunchTemplateFromIntrinsicsIsPassedAsTheResolvedStrings() throws Exception {
        when(eks.createNodeGroup(eq("demo-cluster"), any(CreateNodeGroupRequest.class)))
                .thenReturn(nodegroup("demo-cluster", "workers", NodegroupStatus.ACTIVE));

        provisioner.provision(resource("AWS::EKS::Nodegroup", "Workers"), props("""
                {"ClusterName": "demo-cluster", "NodegroupName": "workers",
                 "NodeRole": "arn:aws:iam::000000000000:role/nodes", "Subnets": ["subnet-1"],
                 "LaunchTemplate": {"Id": {"Ref": "NodeTemplate"},
                                    "Version": {"Fn::GetAtt": ["NodeTemplate", "LatestVersionNumber"]}}}
                """), ctx(Map.of("NodeTemplate", "lt-0123456789abcdef0",
                "NodeTemplate.LatestVersionNumber", "7")));

        ArgumentCaptor<CreateNodeGroupRequest> captured = ArgumentCaptor.forClass(CreateNodeGroupRequest.class);
        verify(eks).createNodeGroup(eq("demo-cluster"), captured.capture());
        assertEquals(Map.of("id", "lt-0123456789abcdef0", "version", "7"), captured.getValue().getLaunchTemplate());
    }

    @Test
    void nodegroupWithOnlyRequiredPropertiesLeavesTheRestToTheServiceDefaults() throws Exception {
        when(eks.createNodeGroup(eq("demo-cluster"), any(CreateNodeGroupRequest.class)))
                .thenReturn(nodegroup("demo-cluster", "workers", NodegroupStatus.ACTIVE));

        provisioner.provision(resource("AWS::EKS::Nodegroup", "Workers"), props("""
                {"ClusterName": "demo-cluster", "NodegroupName": "workers",
                 "NodeRole": "arn:aws:iam::000000000000:role/nodes", "Subnets": ["subnet-1"]}
                """), ctx());

        ArgumentCaptor<CreateNodeGroupRequest> captured = ArgumentCaptor.forClass(CreateNodeGroupRequest.class);
        verify(eks).createNodeGroup(eq("demo-cluster"), captured.capture());
        CreateNodeGroupRequest request = captured.getValue();
        assertNull(request.getVersion());
        assertNull(request.getReleaseVersion());
        assertNull(request.getAmiType());
        assertNull(request.getCapacityType());
        assertNull(request.getDiskSize());
        assertNull(request.getInstanceTypes());
        assertNull(request.getScalingConfig());
        assertNull(request.getUpdateConfig());
        assertNull(request.getRemoteAccess());
        assertNull(request.getTaints());
        assertNull(request.getLaunchTemplate());
        assertNull(request.getNodeRepairConfig());
        assertNull(request.getWarmPoolConfig());
        assertNull(request.getLabels());
        assertEquals(Map.of(), request.getTags());
    }

    @Test
    void nodegroupWithANonIntegerSizeFailsBeforeCreatingAnything() throws Exception {
        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(
                resource("AWS::EKS::Nodegroup", "Workers"), props("""
                        {"ClusterName": "demo-cluster", "NodegroupName": "workers",
                         "NodeRole": "arn:aws:iam::000000000000:role/nodes", "Subnets": ["subnet-1"],
                         "ScalingConfig": {"DesiredSize": "three"}}
                        """), ctx()));

        assertEquals("ValidationError", failure.getErrorCode());
        verify(eks, never()).createNodeGroup(any(), any(CreateNodeGroupRequest.class));
    }

    @Test
    void nodegroupThatFailedToCreateFailsTheResourceAndLeavesItRollbackOwned() throws Exception {
        Nodegroup failed = nodegroup("demo-cluster", "workers", NodegroupStatus.CREATE_FAILED);
        failed.setHealth(Map.of("issues", List.of(Map.of("code", "NodeCreationFailure"))));
        when(eks.createNodeGroup(eq("demo-cluster"), any(CreateNodeGroupRequest.class))).thenReturn(failed);

        StackResource r = resource("AWS::EKS::Nodegroup", "Workers");
        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(r, props("""
                {"ClusterName": "demo-cluster", "NodegroupName": "workers",
                 "NodeRole": "arn:aws:iam::000000000000:role/nodes", "Subnets": ["subnet-1"]}
                """), ctx()));

        assertEquals("ResourceNotReady", failure.getErrorCode());
        assertTrue(failure.getMessage().contains("NodeCreationFailure"), failure.getMessage());
        assertEquals("demo-cluster/workers", r.getPhysicalId());
        assertEquals("true", r.getAttributes().get(CfnRollback.ROLLBACK_OWNED_ATTR));
        assertEquals("demo-cluster", r.getAttributes().get("ClusterName"));
        assertEquals("workers", r.getAttributes().get("NodegroupName"));
    }

    @Test
    void deleteRemovesTheClusterByPhysicalId() {
        StackResource r = resource("AWS::EKS::Cluster", "Control");
        r.setPhysicalId("prod");

        provisioner.delete(r, "us-east-1");

        verify(eks).deleteCluster("prod");
    }

    @Test
    void deleteRemovesTheNodegroupUsingTheStoredNames() {
        StackResource r = resource("AWS::EKS::Nodegroup", "Workers");
        r.setPhysicalId("prod/workers");
        r.getAttributes().put("ClusterName", "prod");
        r.getAttributes().put("NodegroupName", "workers");

        provisioner.delete(r, "us-east-1");

        verify(eks).deleteNodeGroup("prod", "workers");
    }

    @Test
    void deleteToleratesAClusterAlreadyGone() {
        StackResource r = resource("AWS::EKS::Cluster", "Control");
        r.setPhysicalId("prod");
        doThrow(new AwsException("ResourceNotFoundException", "gone", 404))
                .when(eks).deleteCluster("prod");

        assertDoesNotThrow(() -> provisioner.delete(r, "us-east-1"));
    }

    @Test
    void deletePropagatesAnUnexpectedClusterError() {
        StackResource r = resource("AWS::EKS::Cluster", "Control");
        r.setPhysicalId("prod");
        doThrow(new AwsException("ResourceInUseException", "in use", 409))
                .when(eks).deleteCluster("prod");

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.delete(r, "us-east-1"));
        assertEquals("ResourceInUseException", failure.getErrorCode());
    }

    @Test
    void deleteSkipsANodegroupWithNoStoredClusterName() {
        StackResource r = resource("AWS::EKS::Nodegroup", "Workers");
        r.setPhysicalId("workers");

        provisioner.delete(r, "us-east-1");

        verify(eks, never()).deleteNodeGroup(any(), any());
    }

    private ProvisionContext ctx() {
        return ctx(Map.of());
    }

    /** {@code intrinsics} maps a Ref target, or {@code Resource.Attribute} for a GetAtt, to its value. */
    private ProvisionContext ctx(Map<String, String> intrinsics) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            if (node != null && node.has("Ref")) {
                return intrinsics.get(node.get("Ref").asText());
            }
            if (node != null && node.has("Fn::GetAtt")) {
                return intrinsics.get(node.get("Fn::GetAtt").get(0).asText() + "."
                        + node.get("Fn::GetAtt").get(1).asText());
            }
            return node == null ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        when(engine.resolveStringList(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            List<String> out = new ArrayList<>();
            if (node != null && node.isArray()) {
                node.forEach(element -> out.add(element.asText()));
            }
            return out;
        });
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack");
    }

    private static Nodegroup nodegroup(String clusterName, String name, NodegroupStatus status) {
        Nodegroup nodegroup = new Nodegroup();
        nodegroup.setClusterName(clusterName);
        nodegroup.setNodegroupName(name);
        nodegroup.setStatus(status);
        return nodegroup;
    }

    private JsonNode props(String json) throws Exception {
        return mapper.readTree(json);
    }

    private static StackResource resource(String type, String logicalId) {
        StackResource r = new StackResource();
        r.setLogicalId(logicalId);
        r.setResourceType(type);
        r.setAttributes(new HashMap<>());
        return r;
    }
}
