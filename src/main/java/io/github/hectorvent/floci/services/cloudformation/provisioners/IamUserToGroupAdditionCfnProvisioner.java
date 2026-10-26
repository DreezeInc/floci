package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.iam.IamService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::IAM::UserToGroupAddition}: adds the declared
 * {@code Users} to {@code GroupName}. {@code Ref} returns a generated resource name, as on AWS, and
 * the type publishes no attribute. The memberships this resource made are recorded on it, so an
 * update removes the users it no longer declares (from the prior group when {@code GroupName}
 * changed) and a delete removes exactly those memberships, leaving any the user's own
 * {@code Groups} or another addition made.
 */
@ApplicationScoped
public class IamUserToGroupAdditionCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::IAM::UserToGroupAddition";
    private static final String GROUP_NAME_ATTR = "__FlociGroupName";
    private static final String USERS_ATTR = "__FlociUsers";

    private final IamService iamService;

    @Inject
    public IamUserToGroupAdditionCfnProvisioner(IamService iamService) {
        this.iamService = iamService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        String groupName = ctx.resolveRequired(props, "GroupName", TYPE);
        List<String> users = ctx.resolveStringList(props, "Users");
        if (users.isEmpty()) {
            throw new AwsException("ValidationError", TYPE + " requires at least one user in Users.", 400);
        }

        String priorGroupName = r.getAttributes().get(GROUP_NAME_ATTR);
        Set<String> priorUsers = readTrackedSet(r, USERS_ATTR);
        Set<String> membersBefore = new HashSet<>(iamService.getGroup(groupName).getUserNames());

        LinkedHashSet<String> addedByThisAttempt = new LinkedHashSet<>();
        LinkedHashSet<String> removedByThisAttempt = new LinkedHashSet<>();
        try {
            for (String userName : users) {
                iamService.addUserToGroup(groupName, userName);
                if (!membersBefore.contains(userName)) {
                    addedByThisAttempt.add(userName);
                }
            }
            if (priorGroupName != null) {
                for (String stale : priorUsers) {
                    if (!priorGroupName.equals(groupName) || !users.contains(stale)) {
                        iamService.removeUserFromGroup(priorGroupName, stale);
                        removedByThisAttempt.add(stale);
                    }
                }
            }
        } catch (RuntimeException failure) {
            for (String userName : removedByThisAttempt) {
                CfnRollback.attemptIamCleanup(failure,
                        "re-add user " + userName + " to group " + priorGroupName,
                        () -> iamService.addUserToGroup(priorGroupName, userName));
            }
            List<String> rollbackUsers = new ArrayList<>(addedByThisAttempt);
            Collections.reverse(rollbackUsers);
            for (String userName : rollbackUsers) {
                CfnRollback.attemptIamCleanup(failure,
                        "remove user " + userName + " from group " + groupName,
                        () -> iamService.removeUserFromGroup(groupName, userName));
            }
            throw failure;
        }

        r.setPhysicalId(ctx.stablePhysicalName(null, r.getLogicalId(), 128, false));
        r.getAttributes().put(GROUP_NAME_ATTR, groupName);
        writeTrackedSet(r, USERS_ATTR, users);
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
    public void delete(StackResource resource, String region) {
        String groupName = resource.getAttributes().get(GROUP_NAME_ATTR);
        if (groupName == null || groupName.isBlank()) {
            return;
        }
        for (String userName : readTrackedSet(resource, USERS_ATTR)) {
            CfnDeletes.safeDelete("user " + userName + " membership in group " + groupName,
                    resource.getPhysicalId(),
                    () -> iamService.removeUserFromGroup(groupName, userName), "NoSuchEntity");
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        throw new AwsException("ValidationError",
                TYPE + " " + physicalId + " can only be deleted with its stack resource, which records "
                        + "the memberships it made.", 400);
    }
}
