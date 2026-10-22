package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.eks.EksIdentityProviderConfigService;
import io.github.hectorvent.floci.services.eks.EksService;
import io.github.hectorvent.floci.services.eks.model.AssociateIdentityProviderConfigRequest;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.IdentityProviderConfig;
import io.github.hectorvent.floci.services.eks.model.OidcIdentityProviderConfig;
import io.github.hectorvent.floci.services.eks.model.OidcIdentityProviderConfigRequest;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code AWS::EKS::IdentityProviderConfig} in isolation: the {@code name|cluster|type} Ref, the
 * IdentityProviderConfigArn attribute, the Oidc request mapping (RequiredClaims folded to a map, a
 * missing name generated), and the update and delete paths.
 */
class EksIdentityProviderConfigCfnProvisionerTest {

    private static final String ARN =
            "arn:aws:eks:us-east-1:000000000000:identityproviderconfig/prod/oidc/google/uuid";
    private static final IdentityProviderConfig GOOGLE = new IdentityProviderConfig("oidc", "google");

    private final EksService eks = mock(EksService.class);
    private final EksIdentityProviderConfigService configs = mock(EksIdentityProviderConfigService.class);
    private final EksIdentityProviderConfigCfnProvisioner provisioner =
            new EksIdentityProviderConfigCfnProvisioner(eks, configs);
    private final ObjectMapper mapper = new ObjectMapper();
    private final Cluster cluster = cluster("prod");

    @Test
    void refIsNamePipeClusterPipeTypeAndTheArnIsTheOnlyAttribute() throws Exception {
        when(eks.describeCluster("prod")).thenReturn(cluster);
        when(configs.describe(cluster, GOOGLE)).thenReturn(config("google"));

        StackResource r = resource("Idp");
        provisioner.provision(r, props("""
                {"ClusterName": "prod", "Type": "oidc", "IdentityProviderConfigName": "google",
                 "Oidc": {"IssuerUrl": "https://accounts.google.com", "ClientId": "example-client",
                          "UsernameClaim": "email", "UsernamePrefix": "google:",
                          "GroupsClaim": "groups", "GroupsPrefix": "gid:",
                          "RequiredClaims": [{"Key": "hd", "Value": "example.com"}]},
                 "Tags": [{"Key": "Name", "Value": "google"}]}
                """), ctx());

        assertEquals("google|prod|oidc", r.getPhysicalId());
        assertEquals(Set.of("IdentityProviderConfigArn"), r.getAttributes().keySet());
        assertEquals(ARN, r.getAttributes().get("IdentityProviderConfigArn"));
        ArgumentCaptor<AssociateIdentityProviderConfigRequest> request =
                ArgumentCaptor.forClass(AssociateIdentityProviderConfigRequest.class);
        verify(configs).associate(eq(cluster), request.capture());
        OidcIdentityProviderConfigRequest oidc = request.getValue().oidc();
        assertEquals("google", oidc.identityProviderConfigName());
        assertEquals("https://accounts.google.com", oidc.issuerUrl());
        assertEquals("example-client", oidc.clientId());
        assertEquals("email", oidc.usernameClaim());
        assertEquals("google:", oidc.usernamePrefix());
        assertEquals("groups", oidc.groupsClaim());
        assertEquals("gid:", oidc.groupsPrefix());
        assertEquals(Map.of("hd", "example.com"), oidc.requiredClaims());
        assertEquals(Map.of("Name", "google"), request.getValue().tags());
        assertNull(request.getValue().clientRequestToken());
    }

    @Test
    void aMissingNameIsGeneratedFromTheStackAndLogicalId() throws Exception {
        when(eks.describeCluster("prod")).thenReturn(cluster);
        when(configs.describe(eq(cluster), any())).thenAnswer(inv -> {
            IdentityProviderConfig handle = inv.getArgument(1);
            return config(handle.name());
        });

        StackResource r = resource("Idp");
        provisioner.provision(r, props("""
                {"ClusterName": "prod", "Type": "oidc",
                 "Oidc": {"IssuerUrl": "https://accounts.google.com", "ClientId": "client"}}
                """), ctx());

        ArgumentCaptor<AssociateIdentityProviderConfigRequest> request =
                ArgumentCaptor.forClass(AssociateIdentityProviderConfigRequest.class);
        verify(configs).associate(eq(cluster), request.capture());
        String name = request.getValue().oidc().identityProviderConfigName();
        assertTrue(name.matches("my-stack-Idp-[0-9a-f]{12}"), name);
        assertEquals(name + "|prod|oidc", r.getPhysicalId());
        assertNull(request.getValue().oidc().requiredClaims());
        assertNull(request.getValue().tags());
    }

    @Test
    void missingOidcIsAValidationError() throws Exception {
        when(eks.describeCluster("prod")).thenReturn(cluster);

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(resource("Idp"),
                props("{\"ClusterName\": \"prod\", \"Type\": \"oidc\", \"IdentityProviderConfigName\": \"google\"}"),
                ctx()));

        assertEquals("ValidationError", failure.getErrorCode());
        verify(configs, never()).associate(any(), any());
    }

    @Test
    void updateWithTheSameIdentifierReusesTheExistingConfig() throws Exception {
        when(eks.describeCluster("prod")).thenReturn(cluster);
        when(configs.describe(cluster, GOOGLE)).thenReturn(config("google"));

        StackResource r = resource("Idp");
        r.setPhysicalId("google|prod|oidc");
        provisioner.provision(r, props("""
                {"ClusterName": "prod", "Type": "oidc", "IdentityProviderConfigName": "google",
                 "Oidc": {"IssuerUrl": "https://accounts.google.com", "ClientId": "client"}}
                """), updateCtx("google|prod|oidc"));

        assertEquals(ARN, r.getAttributes().get("IdentityProviderConfigArn"));
        verify(configs, never()).associate(any(), any());
    }

    @Test
    void updateKeepsAGeneratedNameThroughThePriorPhysicalId() throws Exception {
        when(eks.describeCluster("prod")).thenReturn(cluster);
        when(configs.describe(cluster, new IdentityProviderConfig("oidc", "my-stack-Idp-0123456789ab")))
                .thenReturn(config("my-stack-Idp-0123456789ab"));

        StackResource r = resource("Idp");
        provisioner.provision(r, props("""
                {"ClusterName": "prod", "Type": "oidc",
                 "Oidc": {"IssuerUrl": "https://accounts.google.com", "ClientId": "client"}}
                """), updateCtx("my-stack-Idp-0123456789ab|prod|oidc"));

        assertEquals("my-stack-Idp-0123456789ab|prod|oidc", r.getPhysicalId());
        verify(configs, never()).associate(any(), any());
    }

    @Test
    void updateChangingTheNameIsRefusedAsAReplacement() throws Exception {
        when(eks.describeCluster("prod")).thenReturn(cluster);

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(resource("Idp"),
                props("""
                        {"ClusterName": "prod", "Type": "oidc", "IdentityProviderConfigName": "okta",
                         "Oidc": {"IssuerUrl": "https://okta.example.com", "ClientId": "client"}}
                        """), updateCtx("google|prod|oidc")));

        assertEquals("ValidationError", failure.getErrorCode());
        verify(configs, never()).associate(any(), any());
    }

    @Test
    void updateRecreatesAConfigThatIsGone() throws Exception {
        when(eks.describeCluster("prod")).thenReturn(cluster);
        when(configs.describe(cluster, GOOGLE))
                .thenThrow(new AwsException("ResourceNotFoundException", "gone", 404))
                .thenReturn(config("google"));

        StackResource r = resource("Idp");
        provisioner.provision(r, props("""
                {"ClusterName": "prod", "Type": "oidc", "IdentityProviderConfigName": "google",
                 "Oidc": {"IssuerUrl": "https://accounts.google.com", "ClientId": "client"}}
                """), updateCtx("google|prod|oidc"));

        verify(configs).associate(eq(cluster), any());
        assertEquals(ARN, r.getAttributes().get("IdentityProviderConfigArn"));
    }

    @Test
    void deleteSplitsTheCompositeId() {
        when(eks.describeCluster("prod")).thenReturn(cluster);

        provisioner.delete("AWS::EKS::IdentityProviderConfig", "google|prod|oidc", "us-east-1");

        verify(configs).disassociate(cluster, GOOGLE);
    }

    @Test
    void deleteIgnoresAMalformedIdAndToleratesAConfigAlreadyGone() {
        when(eks.describeCluster("prod")).thenReturn(cluster);
        doThrow(new AwsException("ResourceNotFoundException", "gone", 404)).when(configs).disassociate(cluster, GOOGLE);

        assertDoesNotThrow(() -> provisioner.delete("AWS::EKS::IdentityProviderConfig", "google|prod|oidc", "us-east-1"));
        assertDoesNotThrow(() -> provisioner.delete("AWS::EKS::IdentityProviderConfig", "google|prod", "us-east-1"));
        assertDoesNotThrow(() -> provisioner.delete("AWS::EKS::IdentityProviderConfig", null, "us-east-1"));
        verify(eks).describeCluster("prod");
    }

    @Test
    void deletePropagatesAnUnexpectedError() {
        when(eks.describeCluster("prod")).thenReturn(cluster);
        doThrow(new AwsException("InvalidRequestException", "Cluster must be ACTIVE", 400))
                .when(configs).disassociate(cluster, GOOGLE);

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.delete("AWS::EKS::IdentityProviderConfig", "google|prod|oidc", "us-east-1"));
        assertEquals("InvalidRequestException", failure.getErrorCode());
    }

    private static Cluster cluster(String name) {
        Cluster cluster = new Cluster();
        cluster.setName(name);
        cluster.setArn("arn:aws:eks:us-east-1:000000000000:cluster/" + name);
        return cluster;
    }

    private static OidcIdentityProviderConfig config(String name) {
        return new OidcIdentityProviderConfig(name, ARN, "prod", "https://accounts.google.com", "client",
                null, null, null, null, Map.of(), Map.of(), "ACTIVE");
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
        r.setResourceType("AWS::EKS::IdentityProviderConfig");
        r.setAttributes(new HashMap<>());
        return r;
    }
}
