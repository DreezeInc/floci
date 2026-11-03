package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.wafv2.WafV2Service;
import io.github.hectorvent.floci.services.wafv2.model.IpSet;
import io.github.hectorvent.floci.services.wafv2.model.RegexPatternSet;
import io.github.hectorvent.floci.services.wafv2.model.RuleGroup;
import io.github.hectorvent.floci.services.wafv2.model.WebAcl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WafV2CfnProvisionerTest {

    private static final String ASSOCIATION = "AWS::WAFv2::WebACLAssociation";
    private static final String IP_SET_TYPE = "AWS::WAFv2::IPSet";
    private static final String LOGGING_CONFIGURATION = "AWS::WAFv2::LoggingConfiguration";
    private static final String REGEX_SET_TYPE = "AWS::WAFv2::RegexPatternSet";
    private static final String RULE_GROUP_TYPE = "AWS::WAFv2::RuleGroup";
    private static final String POOL = "arn:aws:cognito-idp:us-east-1:111122223333:userpool/us-east-1_pool";
    private static final String POOL_2 = "arn:aws:cognito-idp:us-east-1:111122223333:userpool/us-east-1_pool2";
    private static final String ACL_A = "arn:aws:wafv2:us-east-1:111122223333:regional/webacl/a/acl-a";
    private static final String ACL_B = "arn:aws:wafv2:us-east-1:111122223333:regional/webacl/b/acl-b";

    private final WafV2Service wafV2 = mock(WafV2Service.class);
    private final WafV2CfnProvisioner provisioner = new WafV2CfnProvisioner(wafV2);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void webAclCreatesBackingResourceAndSetsAwsReferences() {
        ObjectNode props = requiredProps("portal").put("Description", "edge policy");
        props.set("Rules", mapper.createArrayNode().add(mapper.createObjectNode().put("Name", "managed")));
        props.set("Tags", mapper.createArrayNode()
                .add(mapper.createObjectNode().put("Key", "Owner").put("Value", "Platform")));
        WebAcl created = acl("portal", "acl-123", "REGIONAL", "lock-1");
        created.setArn("arn:aws:wafv2:us-east-1:111122223333:regional/webacl/portal/acl-123");
        created.setCapacity(2);
        created.setLabelNamespace("awswaf:111122223333:webacl:portal:");
        when(wafV2.createWebAcl(any(), eq("REGIONAL"), eq("portal"), eq("us-east-1")))
                .thenReturn(created);
        StackResource resource = resource();

        provisioner.provision(resource, props, context());

        ArgumentCaptor<WebAcl> desired = ArgumentCaptor.forClass(WebAcl.class);
        verify(wafV2).createWebAcl(desired.capture(), eq("REGIONAL"), eq("portal"), eq("us-east-1"));
        assertEquals("{\"Allow\":{}}", desired.getValue().getDefaultAction());
        assertEquals("Platform", desired.getValue().getTags().get("Owner"));
        assertEquals("portal|acl-123|REGIONAL", resource.getPhysicalId());
        assertEquals(created.getArn(), resource.getAttributes().get("Arn"));
        assertEquals("acl-123", resource.getAttributes().get("Id"));
        assertEquals("2", resource.getAttributes().get("Capacity"));
        assertEquals(created.getLabelNamespace(), resource.getAttributes().get("LabelNamespace"));
    }

    @Test
    void sameNamedWebAclUpdatesInPlace() {
        WebAcl existing = acl("portal", "acl-123", "REGIONAL", "lock-1");
        WebAcl updated = acl("portal", "acl-123", "REGIONAL", "lock-2");
        updated.setArn("arn:updated");
        when(wafV2.listWebAcls("REGIONAL")).thenReturn(List.of(existing));
        when(wafV2.getWebAcl("REGIONAL", "acl-123", "portal")).thenReturn(updated);
        StackResource resource = resource();
        resource.setPhysicalId("portal|acl-123|REGIONAL");

        provisioner.provision(resource, requiredProps("portal"), context());

        verify(wafV2).updateWebAcl(any(), eq("REGIONAL"), eq("acl-123"), eq("portal"), eq("lock-1"));
        assertEquals("portal|acl-123|REGIONAL", resource.getPhysicalId());
    }

    @Test
    void inPlaceUpdateReconcilesTagsReadFromListTagsForResource() {
        WebAcl existing = acl("portal", "acl-123", "REGIONAL", "lock-1");
        WebAcl fetched = acl("portal", "acl-123", "REGIONAL", "lock-2");
        fetched.setArn("arn:portal");
        when(wafV2.listWebAcls("REGIONAL")).thenReturn(List.of(existing));
        when(wafV2.getWebAcl("REGIONAL", "acl-123", "portal")).thenReturn(fetched);
        when(wafV2.listTagsForResource("arn:portal"))
                .thenReturn(Map.of("Owner", "Security", "Stale", "yes"));
        StackResource resource = resource();
        resource.setPhysicalId("portal|acl-123|REGIONAL");
        ObjectNode props = requiredProps("portal");
        props.set("Tags", mapper.createArrayNode()
                .add(mapper.createObjectNode().put("Key", "Owner").put("Value", "Platform")));

        provisioner.provision(resource, props, context());

        verify(wafV2).untagResource("arn:portal", List.of("Stale"));
        verify(wafV2).tagResource("arn:portal", Map.of("Owner", "Platform"));
    }

    @Test
    void replacementTracksNewAclEvenWhenOldAclDeleteFails() {
        WebAcl existing = acl("portal", "acl-old", "REGIONAL", "lock-1");
        WebAcl created = acl("portal-renamed", "acl-new", "REGIONAL", "lock-2");
        created.setArn("arn:new");
        when(wafV2.listWebAcls("REGIONAL")).thenReturn(List.of(existing));
        when(wafV2.createWebAcl(any(), eq("REGIONAL"), eq("portal-renamed"), eq("us-east-1")))
                .thenReturn(created);
        doThrow(new RuntimeException("WAFAssociatedItemException"))
                .when(wafV2).deleteWebAcl("REGIONAL", "acl-old", "portal", "lock-1");
        StackResource resource = resource();
        resource.setPhysicalId("portal|acl-old|REGIONAL");

        provisioner.provision(resource, requiredProps("portal-renamed"), context());

        assertEquals("portal-renamed|acl-new|REGIONAL", resource.getPhysicalId());
        assertEquals("arn:new", resource.getAttributes().get("Arn"));
    }

    @Test
    void intrinsicResolvedRuleFieldIsNotDoubleEncoded() {
        ObjectNode props = requiredProps("portal");
        props.set("DefaultAction", mapper.createObjectNode());
        ObjectNode resolved = requiredProps("portal");
        resolved.set("DefaultAction",
                TextNode.valueOf("{\"Allow\":{}}"));
        WebAcl created = acl("portal", "acl-1", "REGIONAL", "lock-1");
        when(wafV2.createWebAcl(any(), eq("REGIONAL"), eq("portal"), eq("us-east-1"))).thenReturn(created);
        StackResource resource = resource();
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolveNode(props)).thenReturn(resolved);
        ProvisionContext ctx = new ProvisionContext(engine, "us-east-1", "111122223333", "stack");

        provisioner.provision(resource, props, ctx);

        ArgumentCaptor<WebAcl> desired = ArgumentCaptor.forClass(WebAcl.class);
        verify(wafV2).createWebAcl(desired.capture(), eq("REGIONAL"), eq("portal"), eq("us-east-1"));
        assertEquals("{\"Allow\":{}}", desired.getValue().getDefaultAction());
    }

    @Test
    void webAclWithoutScopeFailsBeforeCallingWafV2() {
        assertMissingRequiredPropertyFails("Scope");
    }

    @Test
    void webAclWithoutDefaultActionFailsBeforeCallingWafV2() {
        assertMissingRequiredPropertyFails("DefaultAction");
    }

    @Test
    void webAclWithoutVisibilityConfigFailsBeforeCallingWafV2() {
        assertMissingRequiredPropertyFails("VisibilityConfig");
    }

    @Test
    void updateWithoutScopeFailsInsteadOfReplacingTheWebAcl() {
        WebAcl existing = acl("portal", "acl-123", "REGIONAL", "lock-1");
        when(wafV2.listWebAcls("REGIONAL")).thenReturn(List.of(existing));
        StackResource resource = resource();
        resource.setPhysicalId("portal|acl-123|REGIONAL");
        ObjectNode props = requiredProps("portal");
        props.remove("Scope");

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(resource, props, context()));

        assertEquals("AWS::WAFv2::WebACL requires Scope", failure.getMessage());
        verify(wafV2, never()).createWebAcl(any(), any(), anyString(), anyString());
        verify(wafV2, never()).deleteWebAcl(any(), anyString(), anyString(), anyString());
        assertEquals("portal|acl-123|REGIONAL", resource.getPhysicalId());
    }

    @Test
    void associationCreateAssociatesAndUsesResourceAndAclArnsAsPhysicalId() {
        StackResource resource = associationResource();

        provisioner.provision(resource, associationProps(ACL_A), context());

        verify(wafV2).associateWebAcl(ACL_A, POOL);
        assertEquals(POOL + "|" + ACL_A, resource.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(resource));
    }

    @Test
    void unchangedUpdateKeepsIdAndDoesNotCallAssociateWebAcl() {
        StackResource resource = associationResource();
        resource.setPhysicalId(POOL + "|" + ACL_A);

        provisioner.provision(resource, associationProps(ACL_A), updateContext(POOL + "|" + ACL_A));

        assertEquals(POOL + "|" + ACL_A, resource.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(resource));
        verifyNoInteractions(wafV2);
    }

    @Test
    void associationWithoutResourceArnFailsBeforeCallingWafV2() {
        ObjectNode props = associationProps(ACL_A);
        props.remove("ResourceArn");

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(associationResource(), props, context()));

        assertEquals("ValidationError", failure.getErrorCode());
        assertEquals("AWS::WAFv2::WebACLAssociation requires ResourceArn", failure.getMessage());
        verifyNoInteractions(wafV2);
    }

    @Test
    void associationDeleteDisassociatesWhenResourceStillUsesThisAcl() {
        when(wafV2.getWebAclForResource(POOL)).thenReturn(aclWithArn(ACL_A));

        provisioner.delete(ASSOCIATION, POOL + "|" + ACL_A, "us-east-1");

        verify(wafV2).disassociateWebAcl(POOL);
    }

    @Test
    void associationDeleteKeepsAnAssociationWithAnotherAcl() {
        when(wafV2.getWebAclForResource(POOL)).thenReturn(aclWithArn(ACL_B));

        provisioner.delete(ASSOCIATION, POOL + "|" + ACL_A, "us-east-1");

        verify(wafV2, never()).disassociateWebAcl(anyString());
    }

    @Test
    void associationDeleteIsNoOpWhenResourceHasNoAcl() {
        provisioner.delete(ASSOCIATION, POOL + "|" + ACL_A, "us-east-1");

        verify(wafV2).getWebAclForResource(POOL);
        verify(wafV2, never()).disassociateWebAcl(anyString());
    }

    @Test
    void replacingAssociationCleanupLeavesTheNewAssociationOnTheSameResource() {
        StackResource resource = associationResource();
        resource.setPhysicalId(POOL + "|" + ACL_A);

        provisioner.provision(resource, associationProps(ACL_B), updateContext(POOL + "|" + ACL_A));

        assertEquals(POOL + "|" + ACL_B, resource.getPhysicalId());
        assertTrue(provisioner.hasReplacementUpdate(resource));
        assertEquals(POOL + "|" + ACL_A, provisioner.updateCleanupPhysicalId(resource));
        when(wafV2.getWebAclForResource(POOL)).thenReturn(aclWithArn(ACL_B));

        UpdateCleanupResult result = provisioner.completeUpdate(resource);

        assertTrue(result.complete());
        verify(wafV2).getWebAclForResource(POOL);
        verify(wafV2, never()).disassociateWebAcl(anyString());
    }

    @Test
    void rollbackUpdateRestoresThePriorAssociation() {
        StackResource resource = associationResource();
        resource.setPhysicalId(POOL + "|" + ACL_A);
        provisioner.provision(resource, associationProps(ACL_B), updateContext(POOL + "|" + ACL_A));
        when(wafV2.getWebAclForResource(POOL)).thenReturn(aclWithArn(ACL_B));

        assertTrue(provisioner.rollbackUpdate(resource));

        assertEquals(POOL + "|" + ACL_A, resource.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(resource));
        InOrder order = inOrder(wafV2);
        order.verify(wafV2).associateWebAcl(ACL_B, POOL);
        order.verify(wafV2).disassociateWebAcl(POOL);
        order.verify(wafV2).associateWebAcl(ACL_A, POOL);
    }

    @Test
    void replacingResourceArnCleanupDisassociatesTheOldResource() {
        StackResource resource = associationResource();
        resource.setPhysicalId(POOL + "|" + ACL_A);

        provisioner.provision(resource, associationProps(POOL_2, ACL_A), updateContext(POOL + "|" + ACL_A));

        assertEquals(POOL_2 + "|" + ACL_A, resource.getPhysicalId());
        verify(wafV2).associateWebAcl(ACL_A, POOL_2);
        assertTrue(provisioner.hasReplacementUpdate(resource));
        assertEquals(POOL + "|" + ACL_A, provisioner.updateCleanupPhysicalId(resource));
        when(wafV2.getWebAclForResource(POOL)).thenReturn(aclWithArn(ACL_A));

        UpdateCleanupResult result = provisioner.completeUpdate(resource);

        assertTrue(result.complete());
        verify(wafV2).disassociateWebAcl(POOL);
        verify(wafV2, never()).disassociateWebAcl(POOL_2);
    }

    @Test
    void rollbackOfResourceArnReplacementRestoresTheOldResource() {
        StackResource resource = associationResource();
        resource.setPhysicalId(POOL + "|" + ACL_A);
        provisioner.provision(resource, associationProps(POOL_2, ACL_A), updateContext(POOL + "|" + ACL_A));
        when(wafV2.getWebAclForResource(POOL_2)).thenReturn(aclWithArn(ACL_A));

        assertTrue(provisioner.rollbackUpdate(resource));

        assertEquals(POOL + "|" + ACL_A, resource.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(resource));
        InOrder order = inOrder(wafV2);
        order.verify(wafV2).associateWebAcl(ACL_A, POOL_2);
        order.verify(wafV2).disassociateWebAcl(POOL_2);
        order.verify(wafV2).associateWebAcl(ACL_A, POOL);
        verify(wafV2, never()).disassociateWebAcl(POOL);
    }

    @Test
    void rollbackUpdateWithoutReplacementSucceedsWithoutTouchingWafV2() {
        StackResource resource = associationResource();
        resource.setPhysicalId(POOL + "|" + ACL_A);

        assertTrue(provisioner.rollbackUpdate(resource));

        assertEquals(POOL + "|" + ACL_A, resource.getPhysicalId());
        verifyNoInteractions(wafV2);
    }

    @Test
    void webAclRollbackUpdateKeepsTheDefault() {
        StackResource resource = resource();
        resource.setPhysicalId("portal|acl-123|REGIONAL");

        assertFalse(provisioner.rollbackUpdate(resource));
    }

    @Test
    void ipSetCreatesBackingResourceAndSetsAwsReferences() {
        ObjectNode props = ipSetProps("blocklist");
        props.put("Description", "deny list");
        props.set("Tags", mapper.createArrayNode()
                .add(mapper.createObjectNode().put("Key", "Owner").put("Value", "Security")));
        IpSet created = ipSet("blocklist", "ip-123", "REGIONAL", "lock-1");
        created.setArn("arn:aws:wafv2:us-east-1:111122223333:regional/ipset/blocklist/ip-123");
        when(wafV2.createIpSet(any(), eq("REGIONAL"), eq("blocklist"), eq("us-east-1")))
                .thenReturn(created);
        StackResource resource = ipSetResource();

        provisioner.provision(resource, props, context());

        ArgumentCaptor<IpSet> desired = ArgumentCaptor.forClass(IpSet.class);
        verify(wafV2).createIpSet(desired.capture(), eq("REGIONAL"), eq("blocklist"), eq("us-east-1"));
        assertEquals("deny list", desired.getValue().getDescription());
        assertEquals("IPV4", desired.getValue().getIpAddressVersion());
        assertEquals(List.of("192.0.2.1/32"), desired.getValue().getAddresses());
        assertEquals("Security", desired.getValue().getTags().get("Owner"));
        assertEquals("blocklist|ip-123|REGIONAL", resource.getPhysicalId());
        assertEquals(created.getArn(), resource.getAttributes().get("Arn"));
        assertEquals("ip-123", resource.getAttributes().get("Id"));
    }

    @Test
    void sameNamedIpSetUpdatesInPlaceWithoutPassingTheIpAddressVersion() {
        IpSet existing = ipSet("blocklist", "ip-123", "REGIONAL", "lock-1");
        IpSet updated = ipSet("blocklist", "ip-123", "REGIONAL", "lock-2");
        updated.setArn("arn:updated");
        when(wafV2.listIpSets("REGIONAL")).thenReturn(List.of(existing));
        when(wafV2.getIpSet("REGIONAL", "ip-123", "blocklist")).thenReturn(updated);
        StackResource resource = ipSetResource();
        resource.setPhysicalId("blocklist|ip-123|REGIONAL");
        ObjectNode props = ipSetProps("blocklist");
        props.put("Description", "rotated");
        props.set("Addresses", mapper.createArrayNode().add("198.51.100.7/32"));

        provisioner.provision(resource, props, context());

        verify(wafV2).updateIpSet(eq("REGIONAL"), eq("ip-123"), eq("rotated"),
                eq(List.of("198.51.100.7/32")), eq("blocklist"), eq("lock-1"));
        verify(wafV2, never()).createIpSet(any(), any(), anyString(), anyString());
        assertEquals("blocklist|ip-123|REGIONAL", resource.getPhysicalId());
        assertEquals("arn:updated", resource.getAttributes().get("Arn"));
        assertEquals("ip-123", resource.getAttributes().get("Id"));
    }

    @Test
    void ipSetInPlaceUpdateReconcilesTags() {
        IpSet existing = ipSet("blocklist", "ip-123", "REGIONAL", "lock-1");
        IpSet fetched = ipSet("blocklist", "ip-123", "REGIONAL", "lock-2");
        fetched.setArn("arn:blocklist");
        when(wafV2.listIpSets("REGIONAL")).thenReturn(List.of(existing));
        when(wafV2.getIpSet("REGIONAL", "ip-123", "blocklist")).thenReturn(fetched);
        when(wafV2.listTagsForResource("arn:blocklist"))
                .thenReturn(Map.of("Owner", "Security", "Stale", "yes"));
        StackResource resource = ipSetResource();
        resource.setPhysicalId("blocklist|ip-123|REGIONAL");
        ObjectNode props = ipSetProps("blocklist");
        props.set("Tags", mapper.createArrayNode()
                .add(mapper.createObjectNode().put("Key", "Owner").put("Value", "Platform")));

        provisioner.provision(resource, props, context());

        verify(wafV2).untagResource("arn:blocklist", List.of("Stale"));
        verify(wafV2).tagResource("arn:blocklist", Map.of("Owner", "Platform"));
    }

    @Test
    void renamedIpSetRecordsThePriorSetAndCommittedCleanupDeletesIt() {
        IpSet existing = ipSet("blocklist", "ip-old", "REGIONAL", "lock-1");
        IpSet created = ipSet("blocklist-v2", "ip-new", "REGIONAL", "lock-2");
        created.setArn("arn:new");
        when(wafV2.listIpSets("REGIONAL")).thenReturn(List.of(existing));
        when(wafV2.createIpSet(any(), eq("REGIONAL"), eq("blocklist-v2"), eq("us-east-1")))
                .thenReturn(created);
        StackResource resource = ipSetResource();
        resource.setPhysicalId("blocklist|ip-old|REGIONAL");

        provisioner.provision(resource, ipSetProps("blocklist-v2"),
                updateContext("blocklist|ip-old|REGIONAL"));

        assertEquals("blocklist-v2|ip-new|REGIONAL", resource.getPhysicalId());
        assertEquals("arn:new", resource.getAttributes().get("Arn"));
        assertTrue(provisioner.hasReplacementUpdate(resource));
        assertEquals("blocklist|ip-old|REGIONAL", provisioner.updateCleanupPhysicalId(resource));
        verify(wafV2, never()).deleteIpSet(any(), anyString(), anyString(), anyString());

        UpdateCleanupResult result = provisioner.completeUpdate(resource);

        assertTrue(result.complete());
        verify(wafV2).deleteIpSet("REGIONAL", "ip-old", "blocklist", "lock-1");
        assertFalse(provisioner.hasReplacementUpdate(resource));
    }

    @Test
    void committedCleanupOfAnIpSetStillReportsThePriorSetWhenItsDeleteFails() {
        IpSet existing = ipSet("blocklist", "ip-old", "REGIONAL", "lock-1");
        IpSet created = ipSet("blocklist-v2", "ip-new", "REGIONAL", "lock-2");
        created.setArn("arn:new");
        when(wafV2.listIpSets("REGIONAL")).thenReturn(List.of(existing));
        when(wafV2.createIpSet(any(), eq("REGIONAL"), eq("blocklist-v2"), eq("us-east-1")))
                .thenReturn(created);
        doThrow(new RuntimeException("WAFAssociatedItemException"))
                .when(wafV2).deleteIpSet("REGIONAL", "ip-old", "blocklist", "lock-1");
        StackResource resource = ipSetResource();
        resource.setPhysicalId("blocklist|ip-old|REGIONAL");
        provisioner.provision(resource, ipSetProps("blocklist-v2"),
                updateContext("blocklist|ip-old|REGIONAL"));

        UpdateCleanupResult result = provisioner.completeUpdate(resource);

        assertTrue(result.applicable());
        assertFalse(result.complete());
        assertEquals("blocklist|ip-old|REGIONAL", result.previousPhysicalId());
        assertEquals(1, result.attempts());
        assertEquals("WAFAssociatedItemException", result.failureReason());
        assertEquals("blocklist-v2|ip-new|REGIONAL", resource.getPhysicalId());
        assertTrue(provisioner.hasReplacementUpdate(resource));
    }

    @Test
    void rollbackOfAnIpSetReplacementRestoresThePriorSetAndDeletesTheReplacement() {
        IpSet existing = ipSet("blocklist", "ip-old", "REGIONAL", "lock-1");
        IpSet created = ipSet("blocklist-v2", "ip-new", "REGIONAL", "lock-2");
        created.setArn("arn:new");
        when(wafV2.listIpSets("REGIONAL")).thenReturn(List.of(existing, created));
        when(wafV2.createIpSet(any(), eq("REGIONAL"), eq("blocklist-v2"), eq("us-east-1")))
                .thenReturn(created);
        StackResource resource = ipSetResource();
        resource.setPhysicalId("blocklist|ip-old|REGIONAL");
        provisioner.provision(resource, ipSetProps("blocklist-v2"),
                updateContext("blocklist|ip-old|REGIONAL"));

        assertTrue(provisioner.rollbackUpdate(resource));

        assertEquals("blocklist|ip-old|REGIONAL", resource.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(resource));
        verify(wafV2).deleteIpSet("REGIONAL", "ip-new", "blocklist-v2", "lock-2");
    }

    @Test
    void ipSetWithoutNameGeneratesOneOnCreate() {
        ObjectNode props = ipSetProps("blocklist");
        props.remove("Name");
        when(wafV2.createIpSet(any(), eq("REGIONAL"), anyString(), eq("us-east-1")))
                .thenAnswer(invocation -> {
                    IpSet created = ipSet(invocation.getArgument(2), "ip-123", "REGIONAL", "lock-1");
                    created.setArn("arn:generated");
                    return created;
                });
        StackResource resource = ipSetResource();

        provisioner.provision(resource, props, context());

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(wafV2).createIpSet(any(), eq("REGIONAL"), name.capture(), eq("us-east-1"));
        assertFalse(name.getValue().isBlank());
        assertEquals(name.getValue() + "|ip-123|REGIONAL", resource.getPhysicalId());
        assertEquals("arn:generated", resource.getAttributes().get("Arn"));
    }

    @Test
    void ipSetWithoutNameReusesTheExistingNameOnUpdate() {
        IpSet existing = ipSet("blocklist", "ip-123", "REGIONAL", "lock-1");
        IpSet updated = ipSet("blocklist", "ip-123", "REGIONAL", "lock-2");
        updated.setArn("arn:updated");
        when(wafV2.listIpSets("REGIONAL")).thenReturn(List.of(existing));
        when(wafV2.getIpSet("REGIONAL", "ip-123", "blocklist")).thenReturn(updated);
        StackResource resource = ipSetResource();
        resource.setPhysicalId("blocklist|ip-123|REGIONAL");
        ObjectNode props = ipSetProps("blocklist");
        props.remove("Name");

        provisioner.provision(resource, props, context());

        verify(wafV2).updateIpSet(eq("REGIONAL"), eq("ip-123"), any(), anyList(),
                eq("blocklist"), eq("lock-1"));
        verify(wafV2, never()).createIpSet(any(), any(), anyString(), anyString());
    }

    @Test
    void ipSetWithUnknownIpAddressVersionFailsBeforeCallingWafV2() {
        ObjectNode props = ipSetProps("blocklist");
        props.put("IPAddressVersion", "IPV5");

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(ipSetResource(), props, context()));

        assertEquals("WAFInvalidParameterException", failure.getErrorCode());
        verifyNoInteractions(wafV2);
    }

    @Test
    void ipSetWithoutScopeFailsBeforeCallingWafV2() {
        assertMissingIpSetPropertyFails("Scope");
    }

    @Test
    void ipSetWithoutIpAddressVersionFailsBeforeCallingWafV2() {
        assertMissingIpSetPropertyFails("IPAddressVersion");
    }

    @Test
    void ipSetWithoutAddressesFailsBeforeCallingWafV2() {
        assertMissingIpSetPropertyFails("Addresses");
    }

    @Test
    void ipSetDeleteRemovesTheBackingResource() {
        IpSet existing = ipSet("blocklist", "ip-123", "REGIONAL", "lock-1");
        when(wafV2.listIpSets("REGIONAL")).thenReturn(List.of(existing));

        provisioner.delete(IP_SET_TYPE, "blocklist|ip-123|REGIONAL", "us-east-1");

        verify(wafV2).deleteIpSet("REGIONAL", "ip-123", "blocklist", "lock-1");
    }

    @Test
    void ipSetDeleteIsNoOpWhenAlreadyGone() {
        when(wafV2.listIpSets("REGIONAL")).thenReturn(List.of());

        provisioner.delete(IP_SET_TYPE, "blocklist|ip-123|REGIONAL", "us-east-1");

        verify(wafV2, never()).deleteIpSet(any(), anyString(), anyString(), anyString());
    }

    @Test
    void ipSetRollbackUpdateKeepsTheDefault() {
        StackResource resource = ipSetResource();
        resource.setPhysicalId("blocklist|ip-123|REGIONAL");

        assertFalse(provisioner.rollbackUpdate(resource));
    }

    @Test
    void regexPatternSetCreatesBackingResourceAndSetsAwsReferences() {
        ObjectNode props = regexSetProps("paths");
        props.put("Description", "blocked paths");
        props.set("Tags", mapper.createArrayNode()
                .add(mapper.createObjectNode().put("Key", "Owner").put("Value", "Security")));
        RegexPatternSet created = regexSet("paths", "rx-123", "REGIONAL", "lock-1");
        created.setArn("arn:aws:wafv2:us-east-1:111122223333:regional/regexpatternset/paths/rx-123");
        when(wafV2.createRegexPatternSet(any(), eq("REGIONAL"), eq("paths"), eq("us-east-1")))
                .thenReturn(created);
        StackResource resource = regexSetResource();

        provisioner.provision(resource, props, context());

        ArgumentCaptor<RegexPatternSet> desired = ArgumentCaptor.forClass(RegexPatternSet.class);
        verify(wafV2).createRegexPatternSet(desired.capture(), eq("REGIONAL"), eq("paths"), eq("us-east-1"));
        assertEquals("blocked paths", desired.getValue().getDescription());
        assertEquals(List.of("^/admin"), desired.getValue().getRegularExpressionList());
        assertEquals("Security", desired.getValue().getTags().get("Owner"));
        assertEquals("paths|rx-123|REGIONAL", resource.getPhysicalId());
        assertEquals(created.getArn(), resource.getAttributes().get("Arn"));
        assertEquals("rx-123", resource.getAttributes().get("Id"));
    }

    @Test
    void sameNamedRegexPatternSetUpdatesInPlace() {
        RegexPatternSet existing = regexSet("paths", "rx-123", "REGIONAL", "lock-1");
        RegexPatternSet updated = regexSet("paths", "rx-123", "REGIONAL", "lock-2");
        updated.setArn("arn:updated");
        when(wafV2.listRegexPatternSets("REGIONAL")).thenReturn(List.of(existing));
        when(wafV2.getRegexPatternSet("REGIONAL", "rx-123", "paths")).thenReturn(updated);
        when(wafV2.listTagsForResource("arn:updated")).thenReturn(Map.of("Stale", "yes"));
        StackResource resource = regexSetResource();
        resource.setPhysicalId("paths|rx-123|REGIONAL");
        ObjectNode props = regexSetProps("paths");
        props.set("RegularExpressionList", mapper.createArrayNode().add("^/internal"));

        provisioner.provision(resource, props, context());

        verify(wafV2).updateRegexPatternSet(eq("REGIONAL"), eq("rx-123"), any(),
                eq(List.of("^/internal")), eq("paths"), eq("lock-1"));
        verify(wafV2).untagResource("arn:updated", List.of("Stale"));
        verify(wafV2, never()).createRegexPatternSet(any(), any(), anyString(), anyString());
        assertEquals("paths|rx-123|REGIONAL", resource.getPhysicalId());
        assertEquals("arn:updated", resource.getAttributes().get("Arn"));
    }

    @Test
    void renamedRegexPatternSetRecordsThePriorSetAndCommittedCleanupDeletesIt() {
        RegexPatternSet existing = regexSet("paths", "rx-old", "REGIONAL", "lock-1");
        RegexPatternSet created = regexSet("paths-v2", "rx-new", "REGIONAL", "lock-2");
        created.setArn("arn:new");
        when(wafV2.listRegexPatternSets("REGIONAL")).thenReturn(List.of(existing));
        when(wafV2.createRegexPatternSet(any(), eq("REGIONAL"), eq("paths-v2"), eq("us-east-1")))
                .thenReturn(created);
        StackResource resource = regexSetResource();
        resource.setPhysicalId("paths|rx-old|REGIONAL");

        provisioner.provision(resource, regexSetProps("paths-v2"), updateContext("paths|rx-old|REGIONAL"));

        assertEquals("paths-v2|rx-new|REGIONAL", resource.getPhysicalId());
        assertEquals("paths|rx-old|REGIONAL", provisioner.updateCleanupPhysicalId(resource));
        verify(wafV2, never()).deleteRegexPatternSet(any(), anyString(), anyString(), anyString());

        assertTrue(provisioner.completeUpdate(resource).complete());
        verify(wafV2).deleteRegexPatternSet("REGIONAL", "rx-old", "paths", "lock-1");
    }

    @Test
    void rollbackOfARegexPatternSetReplacementRestoresThePriorSet() {
        RegexPatternSet existing = regexSet("paths", "rx-old", "REGIONAL", "lock-1");
        RegexPatternSet created = regexSet("paths-v2", "rx-new", "REGIONAL", "lock-2");
        when(wafV2.listRegexPatternSets("REGIONAL")).thenReturn(List.of(existing, created));
        when(wafV2.createRegexPatternSet(any(), eq("REGIONAL"), eq("paths-v2"), eq("us-east-1")))
                .thenReturn(created);
        StackResource resource = regexSetResource();
        resource.setPhysicalId("paths|rx-old|REGIONAL");
        provisioner.provision(resource, regexSetProps("paths-v2"), updateContext("paths|rx-old|REGIONAL"));

        assertTrue(provisioner.rollbackUpdate(resource));

        assertEquals("paths|rx-old|REGIONAL", resource.getPhysicalId());
        verify(wafV2).deleteRegexPatternSet("REGIONAL", "rx-new", "paths-v2", "lock-2");
    }

    @Test
    void regexPatternSetWithoutNameGeneratesOneOnCreate() {
        ObjectNode props = regexSetProps("paths");
        props.remove("Name");
        when(wafV2.createRegexPatternSet(any(), eq("REGIONAL"), anyString(), eq("us-east-1")))
                .thenAnswer(invocation -> regexSet(invocation.getArgument(2), "rx-123", "REGIONAL", "lock-1"));
        StackResource resource = regexSetResource();

        provisioner.provision(resource, props, context());

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(wafV2).createRegexPatternSet(any(), eq("REGIONAL"), name.capture(), eq("us-east-1"));
        assertFalse(name.getValue().isBlank());
        assertEquals(name.getValue() + "|rx-123|REGIONAL", resource.getPhysicalId());
    }

    @Test
    void regexPatternSetWithoutScopeFailsBeforeCallingWafV2() {
        assertMissingPropertyFails(REGEX_SET_TYPE, regexSetProps("paths"), "Scope");
    }

    @Test
    void regexPatternSetWithoutRegularExpressionListFailsBeforeCallingWafV2() {
        assertMissingPropertyFails(REGEX_SET_TYPE, regexSetProps("paths"), "RegularExpressionList");
    }

    @Test
    void regexPatternSetDeleteRemovesTheBackingResourceAndToleratesOneAlreadyGone() {
        when(wafV2.listRegexPatternSets("REGIONAL"))
                .thenReturn(List.of(regexSet("paths", "rx-123", "REGIONAL", "lock-1")))
                .thenReturn(List.of());

        provisioner.delete(REGEX_SET_TYPE, "paths|rx-123|REGIONAL", "us-east-1");
        provisioner.delete(REGEX_SET_TYPE, "paths|rx-123|REGIONAL", "us-east-1");

        verify(wafV2).deleteRegexPatternSet("REGIONAL", "rx-123", "paths", "lock-1");
    }

    @Test
    void ruleGroupCreatesBackingResourceAndSetsAwsReferences() {
        ObjectNode props = ruleGroupProps("probes");
        props.put("Description", "probe rules");
        RuleGroup created = ruleGroup("probes", "rg-123", "REGIONAL", "lock-1");
        created.setArn("arn:aws:wafv2:us-east-1:111122223333:regional/rulegroup/probes/rg-123");
        created.setLabelNamespace("awswaf:111122223333:rulegroup:probes:");
        when(wafV2.createRuleGroup(any(), eq("REGIONAL"), eq("probes"), eq("us-east-1"))).thenReturn(created);
        StackResource resource = ruleGroupResource();

        provisioner.provision(resource, props, context());

        ArgumentCaptor<RuleGroup> desired = ArgumentCaptor.forClass(RuleGroup.class);
        verify(wafV2).createRuleGroup(desired.capture(), eq("REGIONAL"), eq("probes"), eq("us-east-1"));
        assertEquals(10L, desired.getValue().getCapacity());
        assertEquals("probe rules", desired.getValue().getDescription());
        assertEquals("[{\"Name\":\"probe\",\"Priority\":0}]", desired.getValue().getRules());
        assertEquals("{\"MetricName\":\"probes\"}", desired.getValue().getVisibilityConfig());
        assertEquals("probes|rg-123|REGIONAL", resource.getPhysicalId());
        assertEquals(created.getArn(), resource.getAttributes().get("Arn"));
        assertEquals("rg-123", resource.getAttributes().get("Id"));
        assertEquals("awswaf:111122223333:rulegroup:probes:", resource.getAttributes().get("LabelNamespace"));
    }

    @Test
    void ruleGroupCapacityGivenAsTextIsParsed() {
        ObjectNode props = ruleGroupProps("probes");
        props.put("Capacity", "25");
        when(wafV2.createRuleGroup(any(), eq("REGIONAL"), eq("probes"), eq("us-east-1")))
                .thenReturn(ruleGroup("probes", "rg-123", "REGIONAL", "lock-1"));

        provisioner.provision(ruleGroupResource(), props, context());

        ArgumentCaptor<RuleGroup> desired = ArgumentCaptor.forClass(RuleGroup.class);
        verify(wafV2).createRuleGroup(desired.capture(), any(), anyString(), anyString());
        assertEquals(25L, desired.getValue().getCapacity());
    }

    @Test
    void sameNamedRuleGroupUpdatesInPlace() {
        RuleGroup existing = ruleGroup("probes", "rg-123", "REGIONAL", "lock-1");
        RuleGroup updated = ruleGroup("probes", "rg-123", "REGIONAL", "lock-2");
        updated.setArn("arn:updated");
        updated.setLabelNamespace("awswaf:111122223333:rulegroup:probes:");
        when(wafV2.listRuleGroups("REGIONAL")).thenReturn(List.of(existing));
        when(wafV2.getRuleGroup("REGIONAL", "rg-123", "probes")).thenReturn(updated);
        StackResource resource = ruleGroupResource();
        resource.setPhysicalId("probes|rg-123|REGIONAL");
        ObjectNode props = ruleGroupProps("probes");
        props.put("Description", "rotated");

        provisioner.provision(resource, props, context());

        ArgumentCaptor<RuleGroup> changes = ArgumentCaptor.forClass(RuleGroup.class);
        verify(wafV2).updateRuleGroup(changes.capture(), eq("REGIONAL"), eq("rg-123"), eq("probes"), eq("lock-1"));
        assertEquals("rotated", changes.getValue().getDescription());
        verify(wafV2, never()).createRuleGroup(any(), any(), anyString(), anyString());
        assertEquals("probes|rg-123|REGIONAL", resource.getPhysicalId());
        assertEquals("arn:updated", resource.getAttributes().get("Arn"));
        assertEquals("awswaf:111122223333:rulegroup:probes:", resource.getAttributes().get("LabelNamespace"));
    }

    @Test
    void renamedRuleGroupRecordsThePriorGroupAndCommittedCleanupDeletesIt() {
        RuleGroup existing = ruleGroup("probes", "rg-old", "REGIONAL", "lock-1");
        RuleGroup created = ruleGroup("probes-v2", "rg-new", "REGIONAL", "lock-2");
        when(wafV2.listRuleGroups("REGIONAL")).thenReturn(List.of(existing));
        when(wafV2.createRuleGroup(any(), eq("REGIONAL"), eq("probes-v2"), eq("us-east-1"))).thenReturn(created);
        StackResource resource = ruleGroupResource();
        resource.setPhysicalId("probes|rg-old|REGIONAL");

        provisioner.provision(resource, ruleGroupProps("probes-v2"), updateContext("probes|rg-old|REGIONAL"));

        assertEquals("probes-v2|rg-new|REGIONAL", resource.getPhysicalId());
        assertEquals("probes|rg-old|REGIONAL", provisioner.updateCleanupPhysicalId(resource));
        verify(wafV2, never()).deleteRuleGroup(any(), anyString(), anyString(), anyString());

        assertTrue(provisioner.completeUpdate(resource).complete());
        verify(wafV2).deleteRuleGroup("REGIONAL", "rg-old", "probes", "lock-1");
    }

    @Test
    void rollbackOfARuleGroupReplacementRestoresThePriorGroup() {
        RuleGroup existing = ruleGroup("probes", "rg-old", "REGIONAL", "lock-1");
        RuleGroup created = ruleGroup("probes-v2", "rg-new", "REGIONAL", "lock-2");
        when(wafV2.listRuleGroups("REGIONAL")).thenReturn(List.of(existing, created));
        when(wafV2.createRuleGroup(any(), eq("REGIONAL"), eq("probes-v2"), eq("us-east-1"))).thenReturn(created);
        StackResource resource = ruleGroupResource();
        resource.setPhysicalId("probes|rg-old|REGIONAL");
        provisioner.provision(resource, ruleGroupProps("probes-v2"), updateContext("probes|rg-old|REGIONAL"));

        assertTrue(provisioner.rollbackUpdate(resource));

        assertEquals("probes|rg-old|REGIONAL", resource.getPhysicalId());
        verify(wafV2).deleteRuleGroup("REGIONAL", "rg-new", "probes-v2", "lock-2");
    }

    @Test
    void ruleGroupWithoutScopeFailsBeforeCallingWafV2() {
        assertMissingPropertyFails(RULE_GROUP_TYPE, ruleGroupProps("probes"), "Scope");
    }

    @Test
    void ruleGroupWithoutCapacityFailsBeforeCallingWafV2() {
        assertMissingPropertyFails(RULE_GROUP_TYPE, ruleGroupProps("probes"), "Capacity");
    }

    @Test
    void ruleGroupWithoutVisibilityConfigFailsBeforeCallingWafV2() {
        assertMissingPropertyFails(RULE_GROUP_TYPE, ruleGroupProps("probes"), "VisibilityConfig");
    }

    @Test
    void ruleGroupWithNegativeCapacityFailsBeforeCallingWafV2() {
        ObjectNode props = ruleGroupProps("probes");
        props.put("Capacity", -1);

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(ruleGroupResource(), props, context()));

        assertEquals("ValidationError", failure.getErrorCode());
        verifyNoInteractions(wafV2);
    }

    @Test
    void ruleGroupDeleteRemovesTheBackingResourceAndToleratesOneAlreadyGone() {
        when(wafV2.listRuleGroups("REGIONAL"))
                .thenReturn(List.of(ruleGroup("probes", "rg-123", "REGIONAL", "lock-1")))
                .thenReturn(List.of());

        provisioner.delete(RULE_GROUP_TYPE, "probes|rg-123|REGIONAL", "us-east-1");
        provisioner.delete(RULE_GROUP_TYPE, "probes|rg-123|REGIONAL", "us-east-1");

        verify(wafV2).deleteRuleGroup("REGIONAL", "rg-123", "probes", "lock-1");
    }

    @Test
    void loggingConfigurationCreatesWithResourceArnAsRef() {
        StackResource resource = loggingResource();
        ObjectNode props = loggingProps(ACL_A, "arn:logs:a");

        provisioner.provision(resource, props, context());

        verify(wafV2).putLoggingConfiguration(ACL_A, props.toString());
        assertEquals(ACL_A, resource.getPhysicalId());
        assertEquals("false", resource.getAttributes().get("ManagedByFirewallManager"));
    }

    @Test
    void loggingConfigurationRequiresResourceArnAndDestinations() {
        ObjectNode withoutArn = loggingProps(ACL_A, "arn:logs:a");
        withoutArn.remove("ResourceArn");
        AwsException missingArn = assertThrows(AwsException.class,
                () -> provisioner.provision(loggingResource(), withoutArn, context()));
        assertEquals("AWS::WAFv2::LoggingConfiguration requires ResourceArn", missingArn.getMessage());

        ObjectNode withoutDestinations = loggingProps(ACL_A, "arn:logs:a");
        withoutDestinations.remove("LogDestinationConfigs");
        AwsException missingDestinations = assertThrows(AwsException.class,
                () -> provisioner.provision(loggingResource(), withoutDestinations, context()));
        assertEquals("AWS::WAFv2::LoggingConfiguration requires LogDestinationConfigs",
                missingDestinations.getMessage());
        verifyNoInteractions(wafV2);
    }

    @Test
    void loggingConfigurationCreateDoesNotOverwriteExistingConfiguration() {
        String existing = loggingProps(ACL_A, "arn:logs:external").toString();
        when(wafV2.getLoggingConfiguration(ACL_A)).thenReturn(existing);
        StackResource resource = loggingResource();

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(resource, loggingProps(ACL_A, "arn:logs:new"), context()));

        assertEquals("WAFDuplicateItemException", failure.getErrorCode());
        assertNull(resource.getPhysicalId());
        verify(wafV2, never()).putLoggingConfiguration(anyString(), anyString());
        verify(wafV2, never()).deleteLoggingConfiguration(anyString());
    }

    @Test
    void loggingConfigurationSameArnUpdateRestoresPreviousSettingsOnRollback() {
        String previous = loggingProps(ACL_A, "arn:logs:old").toString();
        when(wafV2.getLoggingConfiguration(ACL_A)).thenReturn(previous);
        StackResource resource = loggingResource();
        resource.setPhysicalId(ACL_A);
        ObjectNode updated = loggingProps(ACL_A, "arn:logs:new");

        provisioner.provision(resource, updated, updateContext(ACL_A));

        assertEquals(ACL_A, resource.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(resource));
        assertEquals(previous, resource.getAttributes().get(CfnRollback.WAF_LOGGING_UPDATE_SNAPSHOT_ATTR));
        assertTrue(provisioner.rollbackUpdate(resource));
        InOrder order = inOrder(wafV2);
        order.verify(wafV2).putLoggingConfiguration(ACL_A, updated.toString());
        order.verify(wafV2).putLoggingConfiguration(ACL_A, previous);
        assertFalse(resource.getAttributes().containsKey(CfnRollback.WAF_LOGGING_UPDATE_SNAPSHOT_ATTR));
    }

    @Test
    void loggingConfigurationSameArnUpdateAfterExternalDeletionRemovesRecreatedConfigOnRollback() {
        when(wafV2.getLoggingConfiguration(ACL_A)).thenThrow(
                new AwsException("WAFNonexistentItemException", "No logging configuration", 400));
        StackResource resource = loggingResource();
        resource.setPhysicalId(ACL_A);
        ObjectNode updated = loggingProps(ACL_A, "arn:logs:new");

        provisioner.provision(resource, updated, updateContext(ACL_A));

        assertEquals(ACL_A, resource.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(resource));
        assertEquals("true", resource.getAttributes().get(CfnRollback.WAF_LOGGING_UPDATE_ABSENT_ATTR));
        assertTrue(provisioner.rollbackUpdate(resource));
        verify(wafV2).putLoggingConfiguration(ACL_A, updated.toString());
        verify(wafV2).deleteLoggingConfiguration(ACL_A);
        assertFalse(resource.getAttributes().containsKey(CfnRollback.WAF_LOGGING_UPDATE_ABSENT_ATTR));
    }

    @Test
    void loggingConfigurationSameArnUpdateAfterExternalDeletionClearsMarkerOnCommit() {
        when(wafV2.getLoggingConfiguration(ACL_A)).thenThrow(
                new AwsException("WAFNonexistentItemException", "No logging configuration", 400));
        StackResource resource = loggingResource();
        resource.setPhysicalId(ACL_A);

        provisioner.provision(resource, loggingProps(ACL_A, "arn:logs:new"), updateContext(ACL_A));

        assertTrue(provisioner.completeUpdate(resource).applicable());
        provisioner.clearUpdate(resource);
        assertFalse(resource.getAttributes().containsKey(CfnRollback.WAF_LOGGING_UPDATE_ABSENT_ATTR));
        verify(wafV2, never()).deleteLoggingConfiguration(anyString());
    }

    @Test
    void loggingConfigurationSameArnUpdateClearsSnapshotAfterCommit() {
        String previous = loggingProps(ACL_A, "arn:logs:old").toString();
        when(wafV2.getLoggingConfiguration(ACL_A)).thenReturn(previous);
        StackResource resource = loggingResource();
        resource.setPhysicalId(ACL_A);

        provisioner.provision(resource, loggingProps(ACL_A, "arn:logs:new"), updateContext(ACL_A));

        assertTrue(provisioner.completeUpdate(resource).applicable());
        provisioner.clearUpdate(resource);
        assertFalse(resource.getAttributes().containsKey(CfnRollback.WAF_LOGGING_UPDATE_SNAPSHOT_ATTR));
    }

    @Test
    void loggingConfigurationReplacementDoesNotOverwriteExistingTarget() {
        String existing = loggingProps(ACL_B, "arn:logs:external").toString();
        when(wafV2.getLoggingConfiguration(ACL_B)).thenReturn(existing);
        StackResource resource = loggingResource();
        resource.setPhysicalId(ACL_A);

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(resource, loggingProps(ACL_B, "arn:logs:new"), updateContext(ACL_A)));

        assertEquals("WAFDuplicateItemException", failure.getErrorCode());
        assertEquals(ACL_A, resource.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(resource));
        verify(wafV2, never()).putLoggingConfiguration(anyString(), anyString());
        verify(wafV2, never()).deleteLoggingConfiguration(anyString());
    }

    @Test
    void loggingConfigurationReplacementDeletesOldArnOnlyAfterCommit() {
        StackResource resource = loggingResource();
        resource.setPhysicalId(ACL_A);

        provisioner.provision(resource, loggingProps(ACL_B, "arn:logs:b"), updateContext(ACL_A));

        assertEquals(ACL_B, resource.getPhysicalId());
        assertTrue(provisioner.hasReplacementUpdate(resource));
        assertEquals(ACL_A, provisioner.updateCleanupPhysicalId(resource));
        verify(wafV2, never()).deleteLoggingConfiguration(anyString());

        assertTrue(provisioner.completeUpdate(resource).complete());
        verify(wafV2).deleteLoggingConfiguration(ACL_A);
        verify(wafV2, never()).deleteLoggingConfiguration(ACL_B);
    }

    @Test
    void loggingConfigurationReplacementRollbackKeepsOldArn() {
        StackResource resource = loggingResource();
        resource.setPhysicalId(ACL_A);
        resource.getAttributes().put("ManagedByFirewallManager", "false");

        provisioner.provision(resource, loggingProps(ACL_B, "arn:logs:b"), updateContext(ACL_A));
        assertTrue(provisioner.rollbackUpdate(resource));

        assertEquals(ACL_A, resource.getPhysicalId());
        assertEquals("false", resource.getAttributes().get("ManagedByFirewallManager"));
        assertFalse(provisioner.hasReplacementUpdate(resource));
        verify(wafV2).deleteLoggingConfiguration(ACL_B);
        verify(wafV2, never()).deleteLoggingConfiguration(ACL_A);
    }

    @Test
    void loggingConfigurationDeleteRemovesStoredConfiguration() {
        provisioner.delete(LOGGING_CONFIGURATION, ACL_A, "us-east-1");

        verify(wafV2).deleteLoggingConfiguration(ACL_A);
    }

    private ObjectNode loggingProps(String resourceArn, String destinationArn) {
        ObjectNode props = mapper.createObjectNode().put("ResourceArn", resourceArn);
        props.set("LogDestinationConfigs", mapper.createArrayNode().add(destinationArn));
        return props;
    }

    private StackResource loggingResource() {
        StackResource resource = new StackResource();
        resource.setLogicalId("Logging");
        resource.setResourceType(LOGGING_CONFIGURATION);
        resource.setAttributes(new HashMap<>());
        return resource;
    }

    private void assertMissingIpSetPropertyFails(String property) {
        ObjectNode props = ipSetProps("blocklist");
        props.remove(property);

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(ipSetResource(), props, context()));

        assertEquals("ValidationError", failure.getErrorCode());
        assertEquals("AWS::WAFv2::IPSet requires " + property, failure.getMessage());
        verifyNoInteractions(wafV2);
    }

    private ObjectNode ipSetProps(String name) {
        ObjectNode props = mapper.createObjectNode()
                .put("Name", name)
                .put("Scope", "REGIONAL")
                .put("IPAddressVersion", "IPV4");
        props.set("Addresses", mapper.createArrayNode().add("192.0.2.1/32"));
        return props;
    }

    private StackResource ipSetResource() {
        StackResource resource = new StackResource();
        resource.setLogicalId("Blocklist");
        resource.setResourceType(IP_SET_TYPE);
        resource.setAttributes(new HashMap<>());
        return resource;
    }

    private IpSet ipSet(String name, String id, String scope, String lockToken) {
        IpSet ipSet = new IpSet();
        ipSet.setName(name);
        ipSet.setId(id);
        ipSet.setScope(scope);
        ipSet.setLockToken(lockToken);
        return ipSet;
    }

    private void assertMissingPropertyFails(String resourceType, ObjectNode props, String property) {
        props.remove(property);
        StackResource resource = new StackResource();
        resource.setLogicalId("Entity");
        resource.setResourceType(resourceType);
        resource.setAttributes(new HashMap<>());

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(resource, props, context()));

        assertEquals("ValidationError", failure.getErrorCode());
        assertEquals(resourceType + " requires " + property, failure.getMessage());
        verifyNoInteractions(wafV2);
    }

    private ObjectNode regexSetProps(String name) {
        ObjectNode props = mapper.createObjectNode().put("Name", name).put("Scope", "REGIONAL");
        props.set("RegularExpressionList", mapper.createArrayNode().add("^/admin"));
        return props;
    }

    private StackResource regexSetResource() {
        StackResource resource = new StackResource();
        resource.setLogicalId("Paths");
        resource.setResourceType(REGEX_SET_TYPE);
        resource.setAttributes(new HashMap<>());
        return resource;
    }

    private RegexPatternSet regexSet(String name, String id, String scope, String lockToken) {
        RegexPatternSet set = new RegexPatternSet();
        set.setName(name);
        set.setId(id);
        set.setScope(scope);
        set.setLockToken(lockToken);
        return set;
    }

    private ObjectNode ruleGroupProps(String name) {
        ObjectNode props = mapper.createObjectNode().put("Name", name).put("Scope", "REGIONAL").put("Capacity", 10);
        props.set("VisibilityConfig", mapper.createObjectNode().put("MetricName", name));
        props.set("Rules", mapper.createArrayNode()
                .add(mapper.createObjectNode().put("Name", "probe").put("Priority", 0)));
        return props;
    }

    private StackResource ruleGroupResource() {
        StackResource resource = new StackResource();
        resource.setLogicalId("Probes");
        resource.setResourceType(RULE_GROUP_TYPE);
        resource.setAttributes(new HashMap<>());
        return resource;
    }

    private RuleGroup ruleGroup(String name, String id, String scope, String lockToken) {
        RuleGroup group = new RuleGroup();
        group.setName(name);
        group.setId(id);
        group.setScope(scope);
        group.setLockToken(lockToken);
        return group;
    }

    private ObjectNode associationProps(String webAclArn) {
        return associationProps(POOL, webAclArn);
    }

    private ObjectNode associationProps(String resourceArn, String webAclArn) {
        return mapper.createObjectNode().put("ResourceArn", resourceArn).put("WebACLArn", webAclArn);
    }

    private ProvisionContext updateContext(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolveNode(any(JsonNode.class))).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, "us-east-1", "111122223333", "stack", priorPhysicalId);
    }

    private StackResource associationResource() {
        StackResource resource = new StackResource();
        resource.setLogicalId("Assoc");
        resource.setResourceType(ASSOCIATION);
        resource.setAttributes(new HashMap<>());
        return resource;
    }

    private WebAcl aclWithArn(String arn) {
        WebAcl acl = new WebAcl();
        acl.setArn(arn);
        return acl;
    }

    private void assertMissingRequiredPropertyFails(String property) {
        ObjectNode props = requiredProps("portal");
        props.remove(property);

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(resource(), props, context()));

        assertEquals("ValidationError", failure.getErrorCode());
        assertEquals("AWS::WAFv2::WebACL requires " + property, failure.getMessage());
        verifyNoInteractions(wafV2);
    }

    private ObjectNode requiredProps(String name) {
        ObjectNode props = mapper.createObjectNode().put("Name", name).put("Scope", "REGIONAL");
        props.set("DefaultAction", mapper.createObjectNode().set("Allow", mapper.createObjectNode()));
        props.set("VisibilityConfig", mapper.createObjectNode().put("MetricName", name));
        return props;
    }

    private ProvisionContext context() {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolveNode(any(JsonNode.class))).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, "us-east-1", "111122223333", "stack");
    }

    private StackResource resource() {
        StackResource resource = new StackResource();
        resource.setLogicalId("PortalWebAcl");
        resource.setResourceType("AWS::WAFv2::WebACL");
        resource.setAttributes(new HashMap<>());
        return resource;
    }

    private WebAcl acl(String name, String id, String scope, String lockToken) {
        WebAcl acl = new WebAcl();
        acl.setName(name);
        acl.setId(id);
        acl.setScope(scope);
        acl.setLockToken(lockToken);
        return acl;
    }
}
