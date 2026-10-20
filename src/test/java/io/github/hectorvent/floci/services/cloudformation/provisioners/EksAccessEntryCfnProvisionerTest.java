package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.eks.EksAccessEntryService;
import io.github.hectorvent.floci.services.eks.EksService;
import io.github.hectorvent.floci.services.eks.model.AccessEntry;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.CreateAccessEntryRequest;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code AWS::EKS::AccessEntry} in isolation: the {@code principal|cluster} Ref, the AccessEntryArn
 * attribute, the request mapping (an absent KubernetesGroups stays null so EC2_LINUX entries pass),
 * AccessPolicies being tolerated, and the update and delete paths.
 */
class EksAccessEntryCfnProvisionerTest {

    private static final String PRINCIPAL = "arn:aws:iam::000000000000:role/ops";
    private static final String ARN = "arn:aws:eks:us-east-1:000000000000:access-entry/prod/role/000000000000/ops/uuid";

    private final EksService eks = mock(EksService.class);
    private final EksAccessEntryService entries = mock(EksAccessEntryService.class);
    private final EksAccessEntryCfnProvisioner provisioner = new EksAccessEntryCfnProvisioner(eks, entries);
    private final ObjectMapper mapper = new ObjectMapper();
    private final Cluster cluster = cluster("prod");

    @Test
    void refIsPrincipalPipeClusterAndAccessEntryArnIsTheOnlyAttribute() throws Exception {
        when(eks.describeCluster("prod")).thenReturn(cluster);
        when(entries.create(eq(cluster), any(CreateAccessEntryRequest.class))).thenReturn(entry());

        StackResource r = resource("Ops");
        provisioner.provision(r, props("""
                {"ClusterName": "prod", "PrincipalArn": "%s", "Type": "STANDARD", "Username": "ops-user",
                 "KubernetesGroups": ["viewers"],
                 "AccessPolicies": [{"AccessScope": {"Type": "cluster"},
                                     "PolicyArn": "arn:aws:eks::aws:cluster-access-policy/AmazonEKSViewPolicy"}],
                 "Tags": [{"Key": "Name", "Value": "prod-ops"}]}
                """.formatted(PRINCIPAL)), ctx());

        assertEquals(PRINCIPAL + "|prod", r.getPhysicalId());
        assertEquals(Set.of("AccessEntryArn"), r.getAttributes().keySet());
        assertEquals(ARN, r.getAttributes().get("AccessEntryArn"));
        ArgumentCaptor<CreateAccessEntryRequest> request = ArgumentCaptor.forClass(CreateAccessEntryRequest.class);
        verify(entries).create(eq(cluster), request.capture());
        assertEquals(PRINCIPAL, request.getValue().principalArn());
        assertEquals("STANDARD", request.getValue().type());
        assertEquals("ops-user", request.getValue().username());
        assertEquals(List.of("viewers"), request.getValue().kubernetesGroups());
        assertEquals(Map.of("Name", "prod-ops"), request.getValue().tags());
    }

    @Test
    void nodeEntryWithoutGroupsOrUsernameSendsNulls() throws Exception {
        when(eks.describeCluster("prod")).thenReturn(cluster);
        when(entries.create(eq(cluster), any(CreateAccessEntryRequest.class))).thenReturn(entry());

        provisioner.provision(resource("Nodes"), props("""
                {"ClusterName": "prod", "PrincipalArn": "%s", "Type": "EC2_LINUX"}
                """.formatted(PRINCIPAL)), ctx());

        ArgumentCaptor<CreateAccessEntryRequest> request = ArgumentCaptor.forClass(CreateAccessEntryRequest.class);
        verify(entries).create(eq(cluster), request.capture());
        assertEquals("EC2_LINUX", request.getValue().type());
        assertNull(request.getValue().username());
        assertNull(request.getValue().kubernetesGroups());
        assertNull(request.getValue().tags());
    }

    @Test
    void updateWithTheSameIdentifierReusesTheExistingEntry() throws Exception {
        when(eks.describeCluster("prod")).thenReturn(cluster);
        when(entries.describe(cluster, PRINCIPAL)).thenReturn(entry());

        StackResource r = resource("Ops");
        r.setPhysicalId(PRINCIPAL + "|prod");
        provisioner.provision(r, props("{\"ClusterName\": \"prod\", \"PrincipalArn\": \"%s\"}".formatted(PRINCIPAL)),
                updateCtx(PRINCIPAL + "|prod"));

        assertEquals(ARN, r.getAttributes().get("AccessEntryArn"));
        verify(entries, never()).create(any(), any());
    }

    @Test
    void updateChangingThePrincipalIsRefusedAsAReplacement() throws Exception {
        when(eks.describeCluster("prod")).thenReturn(cluster);

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(resource("Ops"),
                props("{\"ClusterName\": \"prod\", \"PrincipalArn\": \"arn:aws:iam::000000000000:role/other\"}"),
                updateCtx(PRINCIPAL + "|prod")));

        assertEquals("ValidationError", failure.getErrorCode());
        verify(entries, never()).create(any(), any());
    }

    @Test
    void deleteSplitsTheCompositeIdAtItsLastPipe() {
        when(eks.describeCluster("prod")).thenReturn(cluster);

        provisioner.delete("AWS::EKS::AccessEntry", PRINCIPAL + "|prod", "us-east-1");

        verify(entries).delete(cluster, PRINCIPAL);
    }

    @Test
    void deleteToleratesAnEntryAlreadyGone() {
        when(eks.describeCluster("prod")).thenReturn(cluster);
        doThrow(new AwsException("ResourceNotFoundException", "gone", 404)).when(entries).delete(cluster, PRINCIPAL);

        assertDoesNotThrow(() -> provisioner.delete("AWS::EKS::AccessEntry", PRINCIPAL + "|prod", "us-east-1"));
    }

    @Test
    void deletePropagatesAnUnexpectedError() {
        when(eks.describeCluster("prod")).thenReturn(cluster);
        doThrow(new AwsException("InvalidRequestException", "Cluster must be ACTIVE", 400))
                .when(entries).delete(cluster, PRINCIPAL);

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.delete("AWS::EKS::AccessEntry", PRINCIPAL + "|prod", "us-east-1"));
        assertEquals("InvalidRequestException", failure.getErrorCode());
    }

    private static Cluster cluster(String name) {
        Cluster cluster = new Cluster();
        cluster.setName(name);
        cluster.setArn("arn:aws:eks:us-east-1:000000000000:cluster/" + name);
        return cluster;
    }

    private static AccessEntry entry() {
        return new AccessEntry(ARN, "prod", PRINCIPAL, "STANDARD", "ops-user", List.of("viewers"), Map.of(), 0, 0);
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
        r.setResourceType("AWS::EKS::AccessEntry");
        r.setAttributes(new HashMap<>());
        return r;
    }
}
