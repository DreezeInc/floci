package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Set;

/**
 * Provisions {@code AWS::SecretsManager::RotationSchedule}.
 *
 * <p>A schedule is {@code RotateSecret} on the referenced secret, with the rotation function, the
 * rules and {@code RotateImmediatelyOnUpdate} passed through as the API takes them: the default
 * rotates at once, and {@code false} runs only the function's {@code testSecret} step, as on AWS.
 * An update is the same call again, which is how the registry's update handler applies a changed
 * function or rule. {@code Ref} and {@code Fn::GetAtt Id} return the secret ARN, and deleting the
 * schedule turns rotation off and keeps the secret, as the registry's delete handler does.
 *
 * <p>{@code HostedRotationLambda} is refused: it asks Secrets Manager to deploy one of its own
 * rotation functions from a serverless application, which Floci does not carry.
 */
@ApplicationScoped
public class SecretRotationScheduleCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::SecretsManager::RotationSchedule";

    private static final Logger LOG = Logger.getLogger(SecretRotationScheduleCfnProvisioner.class);

    private final SecretsManagerService secretsManagerService;

    @Inject
    public SecretRotationScheduleCfnProvisioner(SecretsManagerService secretsManagerService) {
        this.secretsManagerService = secretsManagerService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        requireSupported(r.getResourceType());
        String secretId = ctx.resolveRequired(props, "SecretId", TYPE);
        if (props.hasNonNull("HostedRotationLambda")) {
            throw new AwsException("ValidationError",
                    TYPE + " HostedRotationLambda is not supported by Floci; attach a rotation "
                            + "function of the stack's own through RotationLambdaARN.", 400);
        }
        String rotationLambdaArn = ctx.resolveOptional(props, "RotationLambdaARN");
        boolean rotateImmediately = !"false".equalsIgnoreCase(
                ctx.resolveOrDefault(props, "RotateImmediatelyOnUpdate", "true"));
        Secret secret = secretsManagerService.rotateSecret(secretId, null, rotationLambdaArn,
                rotationRules(props, ctx), rotateImmediately, ctx.region());
        r.setPhysicalId(secret.getArn());
        r.getAttributes().put("Id", secret.getArn());
    }

    private static Secret.RotationRules rotationRules(JsonNode props, ProvisionContext ctx) {
        if (!props.hasNonNull("RotationRules")) {
            return null;
        }
        JsonNode rules = ctx.engine().resolveNode(props.get("RotationRules"));
        if (rules == null || rules.isNull()) {
            return null;
        }
        String automaticallyAfterDays = ctx.resolveOptional(rules, "AutomaticallyAfterDays");
        return new Secret.RotationRules(
                automaticallyAfterDays == null || automaticallyAfterDays.isBlank()
                        ? null : Integer.valueOf(automaticallyAfterDays.trim()),
                ctx.resolveOptional(rules, "Duration"),
                ctx.resolveOptional(rules, "ScheduleExpression"));
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        requireSupported(resourceType);
        try {
            secretsManagerService.cancelRotateSecret(physicalId, region);
        } catch (AwsException e) {
            if (!"ResourceNotFoundException".equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("Secret already gone, treating its rotation schedule as deleted: {0}", physicalId);
        }
    }

    private static void requireSupported(String resourceType) {
        if (!TYPE.equals(resourceType)) {
            throw new IllegalStateException(
                    "SecretRotationScheduleCfnProvisioner received an unsupported type: " + resourceType);
        }
    }
}
