package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.OpenIDConnectProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code AWS::IAM::OIDCProvider} in isolation: the ARN as both Ref and Arn, the lists and tags the
 * template maps onto, the Url-keyed update path and the already-gone tolerance on delete.
 */
class IamOidcProviderCfnProvisionerTest {

    private static final String URL = "https://oidc.eks.us-east-1.amazonaws.com/id/ABC";
    private static final String ARN = "arn:aws:iam::000000000000:oidc-provider/oidc.eks.us-east-1.amazonaws.com/id/ABC";

    private final IamService iam = mock(IamService.class);
    private final IamOidcProviderCfnProvisioner provisioner = new IamOidcProviderCfnProvisioner(iam);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void refAndArnAreTheProviderArn() throws Exception {
        when(iam.createOpenIDConnectProvider(eq(URL), eq(List.of("sts.amazonaws.com")),
                eq(List.of("9e99a48a9960b14926bb7f3b02e22da2b0ab7280")), eq(Map.of("Name", "prod-oidc"))))
                .thenReturn(provider());

        StackResource r = resource("Oidc");
        provisioner.provision(r, props("""
                {"Url": "%s", "ClientIdList": ["sts.amazonaws.com"],
                 "ThumbprintList": ["9e99a48a9960b14926bb7f3b02e22da2b0ab7280"],
                 "Tags": [{"Key": "Name", "Value": "prod-oidc"}]}
                """.formatted(URL)), ctx());

        assertEquals(ARN, r.getPhysicalId());
        assertEquals(Set.of("Arn"), r.getAttributes().keySet());
        assertEquals(ARN, r.getAttributes().get("Arn"));
    }

    @Test
    void absentListsAndTagsBecomeEmptyListsAndNoTags() throws Exception {
        when(iam.createOpenIDConnectProvider(eq(URL), eq(List.of()), eq(List.of()), isNull())).thenReturn(provider());

        provisioner.provision(resource("Oidc"), props("{\"Url\": \"%s\"}".formatted(URL)), ctx());

        verify(iam).createOpenIDConnectProvider(eq(URL), eq(List.of()), eq(List.of()), isNull());
    }

    @Test
    void missingUrlFailsBeforeTouchingIam() throws Exception {
        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(resource("Oidc"), props("{}"), ctx()));

        assertEquals("ValidationError", failure.getErrorCode());
        verify(iam, never()).createOpenIDConnectProvider(anyString(), anyList(), anyList(), any());
    }

    @Test
    void updateWithTheSameUrlReusesTheProvider() throws Exception {
        when(iam.getOpenIDConnectProvider(ARN)).thenReturn(provider());

        StackResource r = resource("Oidc");
        r.setPhysicalId(ARN);
        provisioner.provision(r, props("{\"Url\": \"%s\"}".formatted(URL)), updateCtx(ARN));

        assertEquals(ARN, r.getAttributes().get("Arn"));
        verify(iam, never()).createOpenIDConnectProvider(anyString(), anyList(), anyList(), any());
    }

    @Test
    void updateChangingTheUrlIsRefusedAsAReplacement() throws Exception {
        when(iam.getOpenIDConnectProvider(ARN)).thenReturn(provider());

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(resource("Oidc"),
                props("{\"Url\": \"https://oidc.eks.us-east-1.amazonaws.com/id/OTHER\"}"), updateCtx(ARN)));

        assertEquals("ValidationError", failure.getErrorCode());
    }

    @Test
    void updateRecreatesAProviderThatIsGone() throws Exception {
        when(iam.getOpenIDConnectProvider(ARN)).thenThrow(new AwsException("NoSuchEntity", "gone", 404));
        when(iam.createOpenIDConnectProvider(eq(URL), anyList(), anyList(), isNull())).thenReturn(provider());

        StackResource r = resource("Oidc");
        provisioner.provision(r, props("{\"Url\": \"%s\"}".formatted(URL)), updateCtx(ARN));

        assertEquals(ARN, r.getPhysicalId());
    }

    @Test
    void deleteRemovesTheProviderByArn() {
        provisioner.delete("AWS::IAM::OIDCProvider", ARN, "us-east-1");

        verify(iam).deleteOpenIDConnectProvider(ARN);
    }

    @Test
    void deleteToleratesAProviderAlreadyGone() {
        doThrow(new AwsException("NoSuchEntity", "gone", 404)).when(iam).deleteOpenIDConnectProvider(ARN);

        assertDoesNotThrow(() -> provisioner.delete("AWS::IAM::OIDCProvider", ARN, "us-east-1"));
    }

    private static OpenIDConnectProvider provider() {
        OpenIDConnectProvider provider = new OpenIDConnectProvider();
        provider.setArn(ARN);
        provider.setUrl("oidc.eks.us-east-1.amazonaws.com/id/ABC");
        return provider;
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
        r.setResourceType("AWS::IAM::OIDCProvider");
        r.setAttributes(new HashMap<>());
        return r;
    }
}
