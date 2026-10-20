package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.eks.EksPodIdentityAssociationService;
import io.github.hectorvent.floci.services.eks.EksService;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.CreatePodIdentityAssociationRequest;
import io.github.hectorvent.floci.services.eks.model.PodIdentityAssociation;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code AWS::EKS::PodIdentityAssociation} in isolation: the ARN Ref, the AssociationArn and
 * AssociationId attributes (plus the cluster name kept for delete), the update path and the delete.
 */
class EksPodIdentityAssociationCfnProvisionerTest {

    private static final String ROLE = "arn:aws:iam::000000000000:role/pod";
    private static final String ARN = "arn:aws:eks:us-east-1:000000000000:podidentityassociation/prod/a-1234567890abcdef0";

    private final EksService eks = mock(EksService.class);
    private final EksPodIdentityAssociationService associations = mock(EksPodIdentityAssociationService.class);
    private final EksPodIdentityAssociationCfnProvisioner provisioner =
            new EksPodIdentityAssociationCfnProvisioner(eks, associations);
    private final ObjectMapper mapper = new ObjectMapper();
    private final Cluster cluster = cluster("prod");

    @Test
    void refIsTheArnAndItPublishesTheArnIdAndClusterName() throws Exception {
        when(eks.describeCluster("prod")).thenReturn(cluster);
        when(associations.create(eq(cluster), any(CreatePodIdentityAssociationRequest.class)))
                .thenReturn(association("default", "app-sa"));

        StackResource r = resource("Pia");
        provisioner.provision(r, props("""
                {"ClusterName": "prod", "Namespace": "default", "ServiceAccount": "app-sa", "RoleArn": "%s",
                 "Tags": [{"Key": "Name", "Value": "prod-pia"}]}
                """.formatted(ROLE)), ctx());

        assertEquals(ARN, r.getPhysicalId());
        assertEquals(Set.of("AssociationArn", "AssociationId", "ClusterName"), r.getAttributes().keySet());
        assertEquals(ARN, r.getAttributes().get("AssociationArn"));
        assertEquals("a-1234567890abcdef0", r.getAttributes().get("AssociationId"));
        assertEquals("prod", r.getAttributes().get("ClusterName"));
        ArgumentCaptor<CreatePodIdentityAssociationRequest> request =
                ArgumentCaptor.forClass(CreatePodIdentityAssociationRequest.class);
        verify(associations).create(eq(cluster), request.capture());
        assertEquals("prod", request.getValue().clusterName());
        assertEquals("default", request.getValue().namespace());
        assertEquals("app-sa", request.getValue().serviceAccount());
        assertEquals(ROLE, request.getValue().roleArn());
        assertEquals(Map.of("Name", "prod-pia"), request.getValue().tags());
    }

    @Test
    void missingRoleArnFailsBeforeTouchingEks() throws Exception {
        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(resource("Pia"),
                props("{\"ClusterName\": \"prod\", \"Namespace\": \"default\", \"ServiceAccount\": \"app-sa\"}"), ctx()));

        assertEquals("ValidationError", failure.getErrorCode());
        verify(eks, never()).describeCluster(any());
    }

    @Test
    void updateKeepingNamespaceAndServiceAccountReusesTheAssociation() throws Exception {
        when(eks.describeCluster("prod")).thenReturn(cluster);
        when(associations.describe(cluster, "a-1234567890abcdef0")).thenReturn(association("default", "app-sa"));

        StackResource r = provisioned();
        provisioner.provision(r, props("""
                {"ClusterName": "prod", "Namespace": "default", "ServiceAccount": "app-sa", "RoleArn": "%s"}
                """.formatted(ROLE)), updateCtx(ARN));

        assertEquals(ARN, r.getPhysicalId());
        verify(associations, never()).create(any(), any());
    }

    @Test
    void updateChangingTheServiceAccountIsRefusedAsAReplacement() throws Exception {
        when(eks.describeCluster("prod")).thenReturn(cluster);
        when(associations.describe(cluster, "a-1234567890abcdef0")).thenReturn(association("default", "app-sa"));

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(provisioned(), props("""
                {"ClusterName": "prod", "Namespace": "default", "ServiceAccount": "other-sa", "RoleArn": "%s"}
                """.formatted(ROLE)), updateCtx(ARN)));

        assertEquals("ValidationError", failure.getErrorCode());
        verify(associations, never()).create(any(), any());
    }

    @Test
    void updateRecreatesAnAssociationThatIsGone() throws Exception {
        when(eks.describeCluster("prod")).thenReturn(cluster);
        when(associations.describe(cluster, "a-1234567890abcdef0"))
                .thenThrow(new AwsException("ResourceNotFoundException", "gone", 404));
        when(associations.create(eq(cluster), any(CreatePodIdentityAssociationRequest.class)))
                .thenReturn(association("default", "app-sa"));

        provisioner.provision(provisioned(), props("""
                {"ClusterName": "prod", "Namespace": "default", "ServiceAccount": "app-sa", "RoleArn": "%s"}
                """.formatted(ROLE)), updateCtx(ARN));

        verify(associations).create(eq(cluster), any(CreatePodIdentityAssociationRequest.class));
    }

    @Test
    void deleteUsesTheStoredClusterNameAndId() {
        when(eks.describeCluster("prod")).thenReturn(cluster);

        provisioner.delete(provisioned(), "us-east-1");

        verify(associations).delete(cluster, "a-1234567890abcdef0");
    }

    @Test
    void deleteToleratesAnAssociationAlreadyGone() {
        when(eks.describeCluster("prod")).thenReturn(cluster);
        doThrow(new AwsException("ResourceNotFoundException", "gone", 404))
                .when(associations).delete(cluster, "a-1234567890abcdef0");

        assertDoesNotThrow(() -> provisioner.delete(provisioned(), "us-east-1"));
    }

    @Test
    void deleteSkipsAResourceWithNoStoredId() {
        StackResource r = resource("Pia");
        r.setPhysicalId(ARN);

        provisioner.delete(r, "us-east-1");

        verify(associations, never()).delete(any(), any());
    }

    private static StackResource provisioned() {
        StackResource r = resource("Pia");
        r.setPhysicalId(ARN);
        r.getAttributes().put("AssociationArn", ARN);
        r.getAttributes().put("AssociationId", "a-1234567890abcdef0");
        r.getAttributes().put("ClusterName", "prod");
        return r;
    }

    private static Cluster cluster(String name) {
        Cluster cluster = new Cluster();
        cluster.setName(name);
        cluster.setArn("arn:aws:eks:us-east-1:000000000000:cluster/" + name);
        return cluster;
    }

    private static PodIdentityAssociation association(String namespace, String serviceAccount) {
        return new PodIdentityAssociation("prod", namespace, serviceAccount, ROLE, ARN, "a-1234567890abcdef0",
                Map.of(), 0, 0, null, null, null, "external", null);
    }

    private ProvisionContext ctx() {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
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
        r.setResourceType("AWS::EKS::PodIdentityAssociation");
        r.setAttributes(new HashMap<>());
        return r;
    }
}
