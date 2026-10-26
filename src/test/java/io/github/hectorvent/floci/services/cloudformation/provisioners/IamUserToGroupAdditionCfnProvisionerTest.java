package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamGroup;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.HashMap;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code AWS::IAM::UserToGroupAddition} in isolation: every declared user joins the group, Ref is
 * a generated name and nothing is published for Fn::GetAtt, an update removes the users no longer
 * declared (from the prior group when it changed), a failed addition unwinds the memberships it
 * made and no other, and delete removes exactly the recorded memberships.
 */
class IamUserToGroupAdditionCfnProvisionerTest {

    private static final String TYPE = "AWS::IAM::UserToGroupAddition";

    private final IamService iam = mock(IamService.class);
    private final IamUserToGroupAdditionCfnProvisioner provisioner = new IamUserToGroupAdditionCfnProvisioner(iam);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx() {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        when(engine.resolveStringList(any())).thenCallRealMethod();
        return new ProvisionContext(engine, "us-east-1", "000000000000", "test-stack");
    }

    private ProvisionContext updateCtx(String priorPhysicalId) {
        ProvisionContext base = ctx();
        return new ProvisionContext(base.engine(), base.region(), base.accountId(), base.stackName(), priorPhysicalId);
    }

    private StackResource resource() {
        StackResource r = new StackResource();
        r.setLogicalId("Membership");
        r.setResourceType(TYPE);
        r.setAttributes(new HashMap<>());
        return r;
    }

    private JsonNode props(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private IamGroup stubGroup(String groupName, String... members) {
        IamGroup group = new IamGroup("AGPA" + groupName, groupName, "/",
                "arn:aws:iam::000000000000:group/" + groupName);
        group.getUserNames().addAll(java.util.List.of(members));
        when(iam.getGroup(groupName)).thenReturn(group);
        return group;
    }

    @Test
    void declaresTheAdditionResourceType() {
        assertEquals(Set.of(TYPE), provisioner.resourceTypes());
    }

    @Test
    void provisionAddsEachUserAndRecordsTheMemberships() {
        stubGroup("developers");
        StackResource r = resource();

        provisioner.provision(r, props("""
                {"GroupName": "developers", "Users": ["alice", "bob"]}
                """), ctx());

        verify(iam).addUserToGroup("developers", "alice");
        verify(iam).addUserToGroup("developers", "bob");
        assertNotNull(r.getPhysicalId());
        assertTrue(r.getPhysicalId().contains("test-stack-Membership"));
        // Nothing is published: the type has no Fn::GetAtt attribute on AWS.
        assertEquals(Set.of("__FlociGroupName", "__FlociUsers"), r.getAttributes().keySet());
        assertEquals("developers", r.getAttributes().get("__FlociGroupName"));
        assertEquals("alice\nbob", r.getAttributes().get("__FlociUsers"));
    }

    @Test
    void provisionRequiresGroupNameAndUsers() {
        AwsException noGroup = assertThrows(AwsException.class, () ->
                provisioner.provision(resource(), props("""
                        {"Users": ["alice"]}
                        """), ctx()));
        assertEquals("ValidationError", noGroup.getErrorCode());

        AwsException noUsers = assertThrows(AwsException.class, () ->
                provisioner.provision(resource(), props("""
                        {"GroupName": "developers", "Users": []}
                        """), ctx()));
        assertEquals("ValidationError", noUsers.getErrorCode());
        verify(iam, never()).addUserToGroup(any(), any());
    }

    @Test
    void updateKeepsThePhysicalIdAndRemovesUsersNoLongerDeclared() {
        stubGroup("developers", "alice", "bob");
        StackResource r = resource();
        r.setPhysicalId("test-stack-Membership-abc123");
        r.getAttributes().put("__FlociGroupName", "developers");
        r.getAttributes().put("__FlociUsers", "alice\nbob");

        provisioner.provision(r, props("""
                {"GroupName": "developers", "Users": ["alice", "carol"]}
                """), updateCtx("test-stack-Membership-abc123"));

        assertEquals("test-stack-Membership-abc123", r.getPhysicalId());
        verify(iam).addUserToGroup("developers", "alice");
        verify(iam).addUserToGroup("developers", "carol");
        verify(iam).removeUserFromGroup("developers", "bob");
        verify(iam, never()).removeUserFromGroup("developers", "alice");
        assertEquals("alice\ncarol", r.getAttributes().get("__FlociUsers"));
    }

    @Test
    void updateMovesTheMembershipsToTheNewGroup() {
        stubGroup("admins");
        StackResource r = resource();
        r.setPhysicalId("test-stack-Membership-abc123");
        r.getAttributes().put("__FlociGroupName", "developers");
        r.getAttributes().put("__FlociUsers", "alice");

        provisioner.provision(r, props("""
                {"GroupName": "admins", "Users": ["alice"]}
                """), updateCtx("test-stack-Membership-abc123"));

        verify(iam).addUserToGroup("admins", "alice");
        verify(iam).removeUserFromGroup("developers", "alice");
        assertEquals("admins", r.getAttributes().get("__FlociGroupName"));
    }

    @Test
    void failedAdditionRemovesOnlyTheMembershipsItMade() {
        stubGroup("developers", "alice");
        doThrow(new AwsException("NoSuchEntity", "The user with name carol cannot be found.", 404))
                .when(iam).addUserToGroup("developers", "carol");
        StackResource r = resource();

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(r, props("""
                {"GroupName": "developers", "Users": ["alice", "bob", "carol"]}
                """), ctx()));

        assertEquals("NoSuchEntity", failure.getErrorCode());
        verify(iam).removeUserFromGroup("developers", "bob");
        verify(iam, never()).removeUserFromGroup("developers", "alice");
        assertTrue(r.getAttributes().isEmpty());
    }

    @Test
    void failedUpdateReAddsTheUserItRemoved() {
        stubGroup("developers", "alice", "bob");
        doThrow(new AwsException("ServiceFailure", "boom", 500))
                .when(iam).removeUserFromGroup("developers", "bob");
        StackResource r = resource();
        r.setPhysicalId("test-stack-Membership-abc123");
        r.getAttributes().put("__FlociGroupName", "developers");
        r.getAttributes().put("__FlociUsers", "alice\nbob");

        assertThrows(AwsException.class, () -> provisioner.provision(r, props("""
                {"GroupName": "developers", "Users": ["alice"]}
                """), updateCtx("test-stack-Membership-abc123")));

        verify(iam, never()).addUserToGroup("developers", "bob");
        assertEquals("alice\nbob", r.getAttributes().get("__FlociUsers"));
    }

    @Test
    void deleteRemovesExactlyTheRecordedMemberships() {
        StackResource r = resource();
        r.setPhysicalId("test-stack-Membership-abc123");
        r.getAttributes().put("__FlociGroupName", "developers");
        r.getAttributes().put("__FlociUsers", "alice\nbob");
        doThrow(new AwsException("NoSuchEntity", "The user with name bob cannot be found.", 404))
                .when(iam).removeUserFromGroup("developers", "bob");

        provisioner.delete(r, "us-east-1");

        InOrder order = inOrder(iam);
        order.verify(iam).removeUserFromGroup("developers", "alice");
        order.verify(iam).removeUserFromGroup("developers", "bob");
    }

    @Test
    void deleteWithoutARecordedGroupDoesNothing() {
        StackResource r = resource();
        r.setPhysicalId("test-stack-Membership-abc123");

        provisioner.delete(r, "us-east-1");

        verify(iam, never()).removeUserFromGroup(any(), any());
    }

    @Test
    void deleteByPhysicalIdAloneIsRefused() {
        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.delete(TYPE, "test-stack-Membership-abc123", "us-east-1"));

        assertEquals("ValidationError", failure.getErrorCode());
        verify(iam, never()).removeUserFromGroup(any(), any());
    }
}
