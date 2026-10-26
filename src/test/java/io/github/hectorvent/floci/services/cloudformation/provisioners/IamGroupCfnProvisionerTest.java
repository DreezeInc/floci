package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamGroup;
import io.github.hectorvent.floci.testutil.IamInlinePolicyMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code AWS::IAM::Group} in isolation: Ref is the name, Fn::GetAtt Arn is the arn, Path and the
 * policies are applied, an update reconciles the policies it tracked and the path in place, a
 * GroupName change is refused, a failed provision unwinds what it did, and delete empties the
 * group before removing it.
 */
class IamGroupCfnProvisionerTest {

    private static final String ACCOUNT_ID = "000000000000";

    private final IamService iam = mock(IamService.class);
    private final IamGroupCfnProvisioner provisioner = new IamGroupCfnProvisioner(iam);
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void configureInlinePolicyChanges() {
        IamInlinePolicyMock.configure(iam);
    }

    private ProvisionContext ctx() {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        when(engine.resolveStringList(any())).thenCallRealMethod();
        when(engine.resolveJsonAttributeStrict(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node != null && node.isTextual() ? node.asText() : node.toString();
        });
        return new ProvisionContext(engine, "us-east-1", ACCOUNT_ID, "test-stack");
    }

    private ProvisionContext updateCtx(String priorPhysicalId) {
        ProvisionContext base = ctx();
        return new ProvisionContext(base.engine(), base.region(), base.accountId(), base.stackName(), priorPhysicalId);
    }

    private StackResource resource() {
        StackResource r = new StackResource();
        r.setLogicalId("AppGroup");
        r.setResourceType("AWS::IAM::Group");
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

    private static IamGroup group(String groupName, String path) {
        return new IamGroup("AGPA" + groupName, groupName, path,
                "arn:aws:iam::" + ACCOUNT_ID + ":group" + path + groupName);
    }

    private IamGroup stubCreate(String groupName, String path) {
        IamGroup group = group(groupName, path);
        when(iam.createGroup(groupName, path)).thenReturn(group);
        return group;
    }

    @Test
    void declaresIamGroupResourceType() {
        assertEquals(Set.of("AWS::IAM::Group"), provisioner.resourceTypes());
    }

    @Test
    void provisionCreatesGroupWithExplicitNameAndDefaultPath() {
        stubCreate("developers", "/");
        StackResource r = resource();

        provisioner.provision(r, props("""
                {"GroupName": "developers"}
                """), ctx());

        assertEquals("developers", r.getPhysicalId());
        assertEquals("arn:aws:iam::" + ACCOUNT_ID + ":group/developers", r.getAttributes().get("Arn"));
        assertEquals("AGPAdevelopers", r.getAttributes().get("__FlociGroupId"));
        verify(iam).createGroup("developers", "/");
    }

    @Test
    void provisionAppliesTheDeclaredPath() {
        stubCreate("developers", "/engineering/");
        StackResource r = resource();

        provisioner.provision(r, props("""
                {"GroupName": "developers", "Path": "/engineering/"}
                """), ctx());

        assertEquals("arn:aws:iam::" + ACCOUNT_ID + ":group/engineering/developers", r.getAttributes().get("Arn"));
        verify(iam).createGroup("developers", "/engineering/");
    }

    @Test
    void provisionGeneratesPhysicalNameWhenGroupNameIsOmitted() {
        when(iam.createGroup(any(), eq("/"))).thenAnswer(inv -> group(inv.getArgument(0), "/"));
        StackResource r = resource();

        provisioner.provision(r, mapper.createObjectNode(), ctx());

        assertNotNull(r.getPhysicalId());
        assertTrue(r.getPhysicalId().contains("test-stack"));
        assertEquals("arn:aws:iam::" + ACCOUNT_ID + ":group/" + r.getPhysicalId(), r.getAttributes().get("Arn"));
    }

    @Test
    void provisionAttachesManagedPoliciesAndInlinePolicies() {
        stubCreate("developers", "/");
        StackResource r = resource();

        provisioner.provision(r, props("""
                {
                  "GroupName": "developers",
                  "ManagedPolicyArns": [
                    "arn:aws:iam::aws:policy/ReadOnlyAccess",
                    "arn:aws:iam::000000000000:policy/team-policy"
                  ],
                  "Policies": [
                    {
                      "PolicyName": "s3-access",
                      "PolicyDocument": {"Version": "2012-10-17", "Statement": []}
                    }
                  ]
                }
                """), ctx());

        verify(iam).attachGroupPolicy("developers", "arn:aws:iam::aws:policy/ReadOnlyAccess");
        verify(iam).attachGroupPolicy("developers", "arn:aws:iam::000000000000:policy/team-policy");
        verify(iam).putGroupPolicy(eq("developers"), eq("s3-access"), any());
        assertEquals("s3-access", r.getAttributes().get("__FlociInlinePolicyNames"));
        assertEquals("arn:aws:iam::aws:policy/ReadOnlyAccess\narn:aws:iam::000000000000:policy/team-policy",
                r.getAttributes().get("__FlociManagedPolicyArns"));
    }

    @Test
    void inlinePolicyWithoutNameIsRejectedAndTheCreatedGroupRemoved() {
        stubCreate("developers", "/");
        StackResource r = resource();

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(r, props("""
                {
                  "GroupName": "developers",
                  "Policies": [{"PolicyDocument": {"Version": "2012-10-17", "Statement": []}}]
                }
                """), ctx()));

        assertEquals("ValidationError", failure.getErrorCode());
        verify(iam).deleteGroup("developers");
    }

    @Test
    void updatingGroupNameRequiresReplacement() {
        StackResource r = resource();
        r.setPhysicalId("old-group");

        AwsException failure = assertThrows(AwsException.class, () ->
                provisioner.provision(r, props("""
                        {"GroupName": "new-group"}
                        """), updateCtx("old-group")));

        assertEquals("ValidationError", failure.getErrorCode());
        assertTrue(failure.getMessage().contains("resource replacement"));
        verify(iam, never()).createGroup(any(), any());
    }

    @Test
    void updateReconcilesRemovedManagedAndInlinePolicies() {
        IamGroup existing = group("developers", "/");
        existing.getAttachedPolicyArns().add("arn:aws:iam::aws:policy/Keep");
        existing.getAttachedPolicyArns().add("arn:aws:iam::aws:policy/Drop");
        existing.getInlinePolicies().put("keep-inline", "{}");
        existing.getInlinePolicies().put("drop-inline", "{}");
        when(iam.createGroup("developers", "/")).thenThrow(new AwsException("EntityAlreadyExists", "exists", 409));
        when(iam.getGroup("developers")).thenReturn(existing);

        StackResource r = resource();
        r.setPhysicalId("developers");
        r.getAttributes().put("__FlociGroupId", "AGPAdevelopers");
        r.getAttributes().put("__FlociManagedPolicyArns", "arn:aws:iam::aws:policy/Keep\narn:aws:iam::aws:policy/Drop");
        r.getAttributes().put("__FlociInlinePolicyNames", "keep-inline\ndrop-inline");

        provisioner.provision(r, props("""
                {
                  "GroupName": "developers",
                  "ManagedPolicyArns": ["arn:aws:iam::aws:policy/Keep"],
                  "Policies": [
                    {
                      "PolicyName": "keep-inline",
                      "PolicyDocument": {"Version": "2012-10-17", "Statement": []}
                    }
                  ]
                }
                """), updateCtx("developers"));

        verify(iam).detachGroupPolicy("developers", "arn:aws:iam::aws:policy/Drop");
        verify(iam).deleteGroupPolicy("developers", "drop-inline");
        verify(iam, never()).detachGroupPolicy("developers", "arn:aws:iam::aws:policy/Keep");
        InOrder policyOrder = inOrder(iam);
        policyOrder.verify(iam).deleteGroupPolicy("developers", "keep-inline");
        policyOrder.verify(iam).putGroupPolicy(eq("developers"), eq("keep-inline"), any());
        verify(iam, never()).updateGroup(any(), any(), any());
        assertEquals("arn:aws:iam::aws:policy/Keep", r.getAttributes().get("__FlociManagedPolicyArns"));
        assertEquals("keep-inline", r.getAttributes().get("__FlociInlinePolicyNames"));
    }

    @Test
    void updateReconcilesPathChange() {
        IamGroup existing = group("developers", "/");
        when(iam.createGroup("developers", "/new-path/"))
                .thenThrow(new AwsException("EntityAlreadyExists", "exists", 409));
        when(iam.getGroup("developers")).thenReturn(existing);

        StackResource r = resource();
        r.setPhysicalId("developers");
        r.getAttributes().put("__FlociGroupId", "AGPAdevelopers");

        provisioner.provision(r, props("""
                {"GroupName": "developers", "Path": "/new-path/"}
                """), updateCtx("developers"));

        verify(iam).updateGroup("developers", null, "/new-path/");
    }

    @Test
    void updateRefusesToAdoptReplacementGroupWithDifferentGroupId() {
        when(iam.createGroup("developers", "/")).thenThrow(new AwsException("EntityAlreadyExists", "exists", 409));
        when(iam.getGroup("developers")).thenReturn(group("developers", "/"));

        StackResource r = resource();
        r.setPhysicalId("developers");
        r.getAttributes().put("__FlociGroupId", "AGPAsomeone-else");

        AwsException failure = assertThrows(AwsException.class, () ->
                provisioner.provision(r, props("""
                        {"GroupName": "developers"}
                        """), updateCtx("developers")));

        assertEquals("EntityAlreadyExists", failure.getErrorCode());
        verify(iam, never()).attachGroupPolicy(any(), any());
    }

    @Test
    void failedInlinePolicyDetachesNewManagedPolicyAndDeletesCreatedGroup() {
        stubCreate("developers", "/");
        doThrow(new AwsException("MalformedPolicyDocument", "bad policy", 400))
                .when(iam).putGroupPolicy(eq("developers"), eq("broken"), any());
        StackResource r = resource();

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(r, props("""
                {
                  "GroupName": "developers",
                  "ManagedPolicyArns": ["arn:aws:iam::aws:policy/ReadOnlyAccess"],
                  "Policies": [
                    {"PolicyName": "broken", "PolicyDocument": {"Version": "2012-10-17", "Statement": []}}
                  ]
                }
                """), ctx()));

        assertEquals("MalformedPolicyDocument", failure.getErrorCode());
        InOrder order = inOrder(iam);
        order.verify(iam).attachGroupPolicy("developers", "arn:aws:iam::aws:policy/ReadOnlyAccess");
        order.verify(iam).detachGroupPolicy("developers", "arn:aws:iam::aws:policy/ReadOnlyAccess");
        order.verify(iam).deleteGroup("developers");
        assertFalse(r.getAttributes().containsKey(CfnRollback.ROLLBACK_OWNED_ATTR));
    }

    @Test
    void failedUpdateRestoresPriorInlinePolicyAndDetachesNewManagedPolicy() {
        IamGroup existing = group("developers", "/");
        String priorDocument = "{\"Version\":\"2012-10-17\",\"Statement\":[\"prior\"]}";
        existing.getInlinePolicies().put("first", priorDocument);
        when(iam.createGroup("developers", "/")).thenThrow(new AwsException("EntityAlreadyExists", "exists", 409));
        when(iam.getGroup("developers")).thenReturn(existing);
        when(iam.getGroupPolicy(eq("developers"), any())).thenAnswer(inv -> {
            String document = existing.getInlinePolicies().get(inv.<String>getArgument(1));
            if (document == null) {
                throw new AwsException("NoSuchEntity", "no such inline policy", 404);
            }
            return document;
        });
        doAnswer(inv -> existing.getInlinePolicies().put(inv.getArgument(1), inv.getArgument(2)))
                .when(iam).putGroupPolicy(eq("developers"), eq("first"), any());
        doAnswer(inv -> existing.getInlinePolicies().remove(inv.<String>getArgument(1)))
                .when(iam).deleteGroupPolicy(eq("developers"), any());
        doThrow(new AwsException("MalformedPolicyDocument", "bad policy", 400))
                .when(iam).putGroupPolicy(eq("developers"), eq("second"), any());

        StackResource r = resource();
        r.setPhysicalId("developers");
        r.getAttributes().put("__FlociGroupId", "AGPAdevelopers");
        r.getAttributes().put("__FlociInlinePolicyNames", "first");

        assertThrows(AwsException.class, () -> provisioner.provision(r, props("""
                {
                  "GroupName": "developers",
                  "ManagedPolicyArns": ["arn:aws:iam::aws:policy/New"],
                  "Policies": [
                    {"PolicyName": "first", "PolicyDocument": {"Version": "2012-10-17", "Statement": ["new"]}},
                    {"PolicyName": "second", "PolicyDocument": {"Version": "2012-10-17", "Statement": []}}
                  ]
                }
                """), updateCtx("developers")));

        verify(iam).detachGroupPolicy("developers", "arn:aws:iam::aws:policy/New");
        verify(iam).putGroupPolicy("developers", "first", priorDocument);
        assertEquals(Map.of("first", priorDocument), existing.getInlinePolicies());
        verify(iam, never()).deleteGroup(any());
    }

    @Test
    void deleteDetachesPoliciesRemovesMembersAndDeletesGroup() {
        IamGroup group = group("developers", "/");
        group.getAttachedPolicyArns().add("arn:aws:iam::aws:policy/ReadOnlyAccess");
        group.getInlinePolicies().put("inline-1", "{}");
        group.getUserNames().add("alice");
        when(iam.getGroup("developers")).thenReturn(group);

        provisioner.delete("AWS::IAM::Group", "developers", "us-east-1");

        InOrder order = inOrder(iam);
        order.verify(iam).detachGroupPolicy("developers", "arn:aws:iam::aws:policy/ReadOnlyAccess");
        order.verify(iam).deleteGroupPolicy("developers", "inline-1");
        order.verify(iam).removeUserFromGroup("developers", "alice");
        order.verify(iam).deleteGroup("developers");
    }

    @Test
    void deleteToleratesAlreadyDeletedGroup() {
        when(iam.getGroup("already-gone"))
                .thenThrow(new AwsException("NoSuchEntity", "Group does not exist", 404));

        provisioner.delete("AWS::IAM::Group", "already-gone", "us-east-1");

        verify(iam, never()).deleteGroup(any());
    }

    @Test
    void deletePropagatesAConflictThatIsNotAlreadyGone() {
        when(iam.getGroup("developers")).thenReturn(group("developers", "/"));
        doThrow(new AwsException("DeleteConflict", "still has members", 409)).when(iam).deleteGroup("developers");

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.delete("AWS::IAM::Group", "developers", "us-east-1"));

        assertEquals("DeleteConflict", failure.getErrorCode());
    }
}
