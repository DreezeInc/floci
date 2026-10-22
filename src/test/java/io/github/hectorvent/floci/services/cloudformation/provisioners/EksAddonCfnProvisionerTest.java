package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.eks.EksAddonService;
import io.github.hectorvent.floci.services.eks.EksService;
import io.github.hectorvent.floci.services.eks.model.Addon;
import io.github.hectorvent.floci.services.eks.model.AddonHealth;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.CreateAddonRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code AWS::EKS::Addon} in isolation: the composite Ref, the Arn attribute, the request the
 * template maps onto, the reuse-or-refuse update path and the already-gone tolerance on delete.
 */
class EksAddonCfnProvisionerTest {

    private static final String ARN = "arn:aws:eks:us-east-1:000000000000:addon/prod/vpc-cni/uuid";

    private final EksService eks = mock(EksService.class);
    private final EksAddonService addons = mock(EksAddonService.class);
    private final EksAddonCfnProvisioner provisioner = new EksAddonCfnProvisioner(eks, addons);
    private final ObjectMapper mapper = new ObjectMapper();
    private final Cluster cluster = cluster("prod");

    @Test
    void refIsClusterPipeAddonAndArnIsTheOnlyAttribute() throws Exception {
        when(eks.describeCluster("prod")).thenReturn(cluster);
        when(addons.create(eq(cluster), any(CreateAddonRequest.class))).thenReturn(addon("vpc-cni"));

        StackResource r = resource("Cni");
        provisioner.provision(r, props("""
                {"ClusterName": "prod", "AddonName": "vpc-cni", "AddonVersion": "v1.18.5-eksbuild.1",
                 "ServiceAccountRoleArn": "arn:aws:iam::000000000000:role/cni", "ResolveConflicts": "OVERWRITE",
                 "ConfigurationValues": "{\\"env\\":{\\"ENABLE_PREFIX_DELEGATION\\":\\"true\\"}}",
                 "PodIdentityAssociations": [{"RoleArn": "arn:aws:iam::000000000000:role/pia", "ServiceAccount": "aws-node"}],
                 "Tags": [{"Key": "Name", "Value": "prod-vpc-cni"}]}
                """), ctx());

        assertEquals("prod|vpc-cni", r.getPhysicalId());
        assertEquals(Set.of("Arn"), r.getAttributes().keySet());
        assertEquals(ARN, r.getAttributes().get("Arn"));
        ArgumentCaptor<CreateAddonRequest> request = ArgumentCaptor.forClass(CreateAddonRequest.class);
        verify(addons).create(eq(cluster), request.capture());
        assertEquals("vpc-cni", request.getValue().addonName());
        assertEquals("v1.18.5-eksbuild.1", request.getValue().addonVersion());
        assertEquals("arn:aws:iam::000000000000:role/cni", request.getValue().serviceAccountRoleArn());
        assertEquals("OVERWRITE", request.getValue().resolveConflicts());
        assertEquals("{\"env\":{\"ENABLE_PREFIX_DELEGATION\":\"true\"}}", request.getValue().configurationValues());
        assertEquals(Map.of("Name", "prod-vpc-cni"), request.getValue().tags());
        assertEquals(1, request.getValue().podIdentityAssociations().size());
        assertEquals("aws-node", request.getValue().podIdentityAssociations().get(0).serviceAccount());
        assertEquals("arn:aws:iam::000000000000:role/pia", request.getValue().podIdentityAssociations().get(0).roleArn());
    }

    @Test
    void optionalPropertiesAbsentStayNullOnTheRequest() throws Exception {
        when(eks.describeCluster("prod")).thenReturn(cluster);
        when(addons.create(eq(cluster), any(CreateAddonRequest.class))).thenReturn(addon("coredns"));

        provisioner.provision(resource("Dns"), props("""
                {"ClusterName": "prod", "AddonName": "coredns"}
                """), ctx());

        ArgumentCaptor<CreateAddonRequest> request = ArgumentCaptor.forClass(CreateAddonRequest.class);
        verify(addons).create(eq(cluster), request.capture());
        assertNull(request.getValue().addonVersion());
        assertNull(request.getValue().tags());
        assertNull(request.getValue().podIdentityAssociations());
    }

    @Test
    void missingAddonNameFailsBeforeTouchingEks() {
        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(resource("Cni"), props("{\"ClusterName\": \"prod\"}"), ctx()));

        assertEquals("ValidationError", failure.getErrorCode());
        verify(eks, never()).describeCluster(anyString());
    }

    @Test
    void updateWithTheSameIdentifierReusesTheExistingAddon() throws Exception {
        when(eks.describeCluster("prod")).thenReturn(cluster);
        when(addons.describe(cluster, "vpc-cni")).thenReturn(addon("vpc-cni"));

        StackResource r = resource("Cni");
        r.setPhysicalId("prod|vpc-cni");
        provisioner.provision(r, props("{\"ClusterName\": \"prod\", \"AddonName\": \"vpc-cni\"}"),
                updateCtx("prod|vpc-cni"));

        assertEquals("prod|vpc-cni", r.getPhysicalId());
        assertEquals(ARN, r.getAttributes().get("Arn"));
        verify(addons, never()).create(any(), any());
    }

    @Test
    void updateRecreatesAnAddonThatIsGone() throws Exception {
        when(eks.describeCluster("prod")).thenReturn(cluster);
        when(addons.describe(cluster, "vpc-cni"))
                .thenThrow(new AwsException("ResourceNotFoundException", "gone", 404));
        when(addons.create(eq(cluster), any(CreateAddonRequest.class))).thenReturn(addon("vpc-cni"));

        StackResource r = resource("Cni");
        provisioner.provision(r, props("{\"ClusterName\": \"prod\", \"AddonName\": \"vpc-cni\"}"),
                updateCtx("prod|vpc-cni"));

        assertEquals(ARN, r.getAttributes().get("Arn"));
        verify(addons).create(eq(cluster), any(CreateAddonRequest.class));
    }

    @Test
    void updateChangingTheAddonNameIsRefusedAsAReplacement() throws Exception {
        when(eks.describeCluster("prod")).thenReturn(cluster);

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(resource("Cni"),
                props("{\"ClusterName\": \"prod\", \"AddonName\": \"coredns\"}"), updateCtx("prod|vpc-cni")));

        assertEquals("ValidationError", failure.getErrorCode());
        verify(addons, never()).create(any(), any());
    }

    @Test
    void deleteSplitsTheCompositeIdAndDoesNotPreserve() {
        when(eks.describeCluster("prod")).thenReturn(cluster);

        provisioner.delete("AWS::EKS::Addon", "prod|vpc-cni", "us-east-1");

        verify(addons).delete(cluster, "vpc-cni", false);
    }

    @Test
    void deleteToleratesAnAddonOrClusterAlreadyGone() {
        when(eks.describeCluster("prod")).thenThrow(new AwsException("ResourceNotFoundException", "gone", 404));

        assertDoesNotThrow(() -> provisioner.delete("AWS::EKS::Addon", "prod|vpc-cni", "us-east-1"));
    }

    @Test
    void deletePropagatesAnUnexpectedError() {
        when(eks.describeCluster("prod")).thenReturn(cluster);
        doThrow(new AwsException("InvalidRequestException", "Cluster must be ACTIVE", 400))
                .when(addons).delete(cluster, "vpc-cni", false);

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.delete("AWS::EKS::Addon", "prod|vpc-cni", "us-east-1"));
        assertEquals("InvalidRequestException", failure.getErrorCode());
    }

    private static Cluster cluster(String name) {
        Cluster cluster = new Cluster();
        cluster.setName(name);
        cluster.setArn("arn:aws:eks:us-east-1:000000000000:cluster/" + name);
        return cluster;
    }

    private static Addon addon(String name) {
        return new Addon(ARN, name, "v1.18.5-eksbuild.1", "prod", "ACTIVE", new AddonHealth(List.of()),
                0, 0, null, null, Map.of(), List.of(), "aws", "eks");
    }

    private ProvisionContext ctx() {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
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

    private ProvisionContext updateCtx(String priorPhysicalId) {
        ProvisionContext create = ctx();
        return new ProvisionContext(create.engine(), create.region(), create.accountId(),
                create.stackName(), priorPhysicalId);
    }

    private JsonNode props(String json) throws Exception {
        return mapper.readTree(json);
    }

    private static StackResource resource(String logicalId) {
        StackResource r = new StackResource();
        r.setLogicalId(logicalId);
        r.setResourceType("AWS::EKS::Addon");
        r.setAttributes(new HashMap<>());
        return r;
    }
}
