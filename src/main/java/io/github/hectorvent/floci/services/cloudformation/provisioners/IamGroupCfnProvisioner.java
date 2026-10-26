package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.IamService.InlinePolicyChange;
import io.github.hectorvent.floci.services.iam.model.IamGroup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::IAM::Group}. {@code Ref} returns the group name and
 * {@code Fn::GetAtt Arn} its arn. {@code ManagedPolicyArns} and inline {@code Policies} are
 * reconciled on update the way {@link IamUserCfnProvisioner} reconciles a user's; {@code Path} is
 * updated in place; a {@code GroupName} change is the createOnly replacement it is on AWS, and is
 * refused. Delete detaches and removes every policy and member first, because IAM refuses to
 * delete a group that still holds any.
 */
@ApplicationScoped
public class IamGroupCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(IamGroupCfnProvisioner.class);

    private static final String TYPE = "AWS::IAM::Group";
    private static final String INLINE_NAMES_ATTR = "__FlociInlinePolicyNames";
    private static final String MANAGED_ARNS_ATTR = "__FlociManagedPolicyArns";
    private static final String GROUP_ID_ATTR = "__FlociGroupId";

    private final IamService iamService;

    @Inject
    public IamGroupCfnProvisioner(IamService iamService) {
        this.iamService = iamService;
    }

    private record InlinePolicyUndo(String name, InlinePolicyChange change) {}

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        final String groupName = ctx.stablePhysicalName(ctx.resolveOptional(props, "GroupName"),
                r.getLogicalId(), 128, false);
        if (ctx.isUpdate() && !ctx.priorPhysicalId().equals(groupName)) {
            throw new AwsException("ValidationError",
                    "Updating GroupName requires resource replacement, which is not supported.", 400);
        }

        String path = ctx.resolveOptional(props, "Path");
        if (path == null || path.isBlank()) {
            path = "/";
        }
        final String resolvedPath = path;

        List<String> managedPolicyArns = ctx.resolveStringList(props, "ManagedPolicyArns");

        IamGroup group;
        boolean createdGroup = false;
        String priorPath = null;
        try {
            group = iamService.createGroup(groupName, resolvedPath);
            createdGroup = true;
            r.getAttributes().put(CfnRollback.ROLLBACK_OWNED_ATTR, "true");
        } catch (AwsException e) {
            if (!ctx.reusesPriorEntity(groupName) || !"EntityAlreadyExists".equals(e.getErrorCode())) {
                throw e;
            }
            group = iamService.getGroup(groupName);
            String existingGroupId = r.getAttributes().get(GROUP_ID_ATTR);
            if (existingGroupId == null || existingGroupId.isBlank()
                    || !existingGroupId.equals(group.getGroupId())) {
                r.getAttributes().remove(CfnRollback.ROLLBACK_OWNED_ATTR);
                throw e;
            }
            priorPath = group.getPath();
            if (!resolvedPath.equals(group.getPath())) {
                iamService.updateGroup(groupName, null, resolvedPath);
                group = iamService.getGroup(groupName);
            }
        }

        r.setPhysicalId(groupName);
        r.getAttributes().put("Arn", group.getArn());
        r.getAttributes().put(GROUP_ID_ATTR, group.getGroupId());

        Set<String> previousInlineNames = readTrackedSet(r, INLINE_NAMES_ATTR);
        Set<String> previousManagedArns = readTrackedSet(r, MANAGED_ARNS_ATTR);
        Set<String> originalPolicyArns = new HashSet<>(group.getAttachedPolicyArns());
        Map<String, String> originalInlinePolicies = new HashMap<>(group.getInlinePolicies());

        LinkedHashSet<String> attachedByThisAttempt = new LinkedHashSet<>();
        LinkedHashSet<String> detachedByThisAttempt = new LinkedHashSet<>();
        LinkedHashSet<String> inlineWrittenByThisAttempt = new LinkedHashSet<>();
        List<InlinePolicyUndo> inlineChanges = new ArrayList<>();
        final String pathToRestore = priorPath;

        try {
            for (String policyArn : managedPolicyArns) {
                iamService.attachGroupPolicy(groupName, policyArn);
                if (!originalPolicyArns.contains(policyArn)) {
                    attachedByThisAttempt.add(policyArn);
                }
            }

            Map<String, String> desiredInlinePolicies = new LinkedHashMap<>();
            if (props != null && props.has("Policies")) {
                for (JsonNode policy : props.get("Policies")) {
                    String declaredName = ctx.resolveOptional(policy, "PolicyName");
                    if (declaredName == null || declaredName.isBlank()) {
                        throw new AwsException("ValidationError",
                                "An inline policy on group " + groupName + " has no PolicyName.", 400);
                    }
                    JsonNode document = policy.get("PolicyDocument");
                    if (document == null || document.isNull()) {
                        throw new AwsException("ValidationError",
                                "Inline policy '" + declaredName + "' on group " + groupName
                                        + " has no PolicyDocument.", 400);
                    }
                    desiredInlinePolicies.put(declaredName, ctx.engine().resolveJsonAttributeStrict(document));
                }
            }

            for (String stale : previousInlineNames) {
                if (originalInlinePolicies.containsKey(stale)
                        && !originalInlinePolicies.get(stale).equals(desiredInlinePolicies.get(stale))) {
                    inlineChanges.add(new InlinePolicyUndo(stale,
                            iamService.deleteGroupPolicyWithRollback(groupName, stale)));
                }
            }
            for (Map.Entry<String, String> policy : desiredInlinePolicies.entrySet()) {
                inlineChanges.add(new InlinePolicyUndo(policy.getKey(), iamService.putGroupPolicyWithRollback(
                        groupName, policy.getKey(), policy.getValue())));
                inlineWrittenByThisAttempt.add(policy.getKey());
            }
            for (String stale : previousManagedArns) {
                if (!managedPolicyArns.contains(stale) && originalPolicyArns.contains(stale)) {
                    iamService.detachGroupPolicy(groupName, stale);
                    detachedByThisAttempt.add(stale);
                }
            }
        } catch (RuntimeException failure) {
            boolean cleanupSucceeded = true;

            for (String policyArn : detachedByThisAttempt) {
                if (!CfnRollback.attemptIamCleanup(failure,
                        "reattach policy " + policyArn + " to group " + groupName,
                        () -> iamService.attachGroupPolicy(groupName, policyArn))) {
                    cleanupSucceeded = false;
                }
            }

            for (InlinePolicyUndo undo : inlineChanges.reversed()) {
                String operation = undo.change().previousDocument() == null ? "remove" : "restore";
                if (!CfnRollback.attemptIamCleanup(failure,
                        operation + " inline policy " + undo.name() + " on group " + groupName,
                        undo.change().rollback())) {
                    cleanupSucceeded = false;
                }
            }

            List<String> rollbackArns = new ArrayList<>(attachedByThisAttempt);
            Collections.reverse(rollbackArns);
            for (String policyArn : rollbackArns) {
                if (!CfnRollback.attemptIamCleanup(failure,
                        "detach policy " + policyArn + " from group " + groupName,
                        () -> iamService.detachGroupPolicy(groupName, policyArn))) {
                    cleanupSucceeded = false;
                }
            }

            if (pathToRestore != null && !pathToRestore.equals(resolvedPath)) {
                if (!CfnRollback.attemptIamCleanup(failure, "restore prior path on group " + groupName,
                        () -> iamService.updateGroup(groupName, null, pathToRestore))) {
                    cleanupSucceeded = false;
                }
            }

            if (createdGroup) {
                if (!CfnRollback.attemptIamCleanup(failure, "delete group " + groupName,
                        () -> iamService.deleteGroup(groupName))) {
                    cleanupSucceeded = false;
                }
                if (cleanupSucceeded) {
                    r.getAttributes().remove(CfnRollback.ROLLBACK_OWNED_ATTR);
                }
            }
            if (!createdGroup && !cleanupSucceeded) {
                r.getAttributes().remove(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR);
                r.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR,
                        "Could not restore group after failed update: " + failure.getMessage());
            }
            throw failure;
        }

        writeTrackedSet(r, INLINE_NAMES_ATTR, inlineWrittenByThisAttempt);
        writeTrackedSet(r, MANAGED_ARNS_ATTR, managedPolicyArns);
    }

    private static Set<String> readTrackedSet(StackResource r, String attribute) {
        String raw = r.getAttributes().get(attribute);
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(List.of(raw.split("\n")));
    }

    private static void writeTrackedSet(StackResource r, String attribute, Collection<String> values) {
        if (values.isEmpty()) {
            r.getAttributes().remove(attribute);
            return;
        }
        r.getAttributes().put(attribute, String.join("\n", values));
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        IamGroup group;
        try {
            group = iamService.getGroup(physicalId);
        } catch (AwsException e) {
            if (!"NoSuchEntity".equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("IAM group already gone, treating as deleted: {0}", physicalId);
            return;
        }

        for (String policyArn : new ArrayList<>(group.getAttachedPolicyArns())) {
            CfnDeletes.safeDelete("attached policy " + policyArn + " on group", physicalId,
                    () -> iamService.detachGroupPolicy(physicalId, policyArn), "NoSuchEntity");
        }

        for (String policyName : new ArrayList<>(group.getInlinePolicies().keySet())) {
            CfnDeletes.safeDelete("inline policy " + policyName + " on group", physicalId,
                    () -> iamService.deleteGroupPolicy(physicalId, policyName), "NoSuchEntity");
        }

        for (String userName : new ArrayList<>(group.getUserNames())) {
            CfnDeletes.safeDelete("user " + userName + " membership in group", physicalId,
                    () -> iamService.removeUserFromGroup(physicalId, userName), "NoSuchEntity");
        }

        CfnDeletes.safeDelete("IAM group", physicalId,
                () -> iamService.deleteGroup(physicalId), "NoSuchEntity");
    }
}
