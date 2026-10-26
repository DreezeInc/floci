package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The rotation schedule provisioner in isolation, mocking only SecretsManagerService. */
class SecretRotationScheduleCfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final String ARN = "arn:aws:secretsmanager:us-east-1:000000000000:secret:app/db-AbCdEf";
    private static final String LAMBDA = "arn:aws:lambda:us-east-1:000000000000:function:rotate-db";

    private final SecretsManagerService secrets = mock(SecretsManagerService.class);
    private final SecretRotationScheduleCfnProvisioner provisioner =
            new SecretRotationScheduleCfnProvisioner(secrets);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx() {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, REGION, "000000000000", "my-stack", null);
    }

    private static StackResource resource() {
        StackResource r = new StackResource();
        r.setLogicalId("DbSecretSchedule");
        r.setResourceType("AWS::SecretsManager::RotationSchedule");
        r.setAttributes(new HashMap<>());
        return r;
    }

    private static Secret secret() {
        Secret s = new Secret();
        s.setName("app/db");
        s.setArn(ARN);
        return s;
    }

    private ObjectNode props() {
        return mapper.createObjectNode().put("SecretId", ARN).put("RotationLambdaARN", LAMBDA);
    }

    @Test
    void createRotatesWithTheFunctionAndRulesWithoutRotatingAtOnce() {
        when(secrets.rotateSecret(eq(ARN), isNull(), eq(LAMBDA), any(), eq(false), eq(REGION)))
                .thenReturn(secret());
        ObjectNode props = props().put("RotateImmediatelyOnUpdate", false);
        props.putObject("RotationRules").put("AutomaticallyAfterDays", 30);

        StackResource r = resource();
        provisioner.provision(r, props, ctx());

        verify(secrets).rotateSecret(ARN, null, LAMBDA, new Secret.RotationRules(30, null, null), false, REGION);
        assertEquals(ARN, r.getPhysicalId());
        assertEquals(ARN, r.getAttributes().get("Id"));
    }

    @Test
    void createRotatesAtOnceWithAScheduleExpressionByDefault() {
        when(secrets.rotateSecret(eq(ARN), isNull(), eq(LAMBDA), any(), eq(true), eq(REGION)))
                .thenReturn(secret());
        ObjectNode props = props();
        props.putObject("RotationRules").put("ScheduleExpression", "rate(10 days)").put("Duration", "2h");

        provisioner.provision(resource(), props, ctx());

        verify(secrets).rotateSecret(ARN, null, LAMBDA,
                new Secret.RotationRules(null, "2h", "rate(10 days)"), true, REGION);
    }

    @Test
    void createWithoutRulesPassesNone() {
        when(secrets.rotateSecret(eq(ARN), isNull(), eq(LAMBDA), isNull(), eq(true), eq(REGION)))
                .thenReturn(secret());

        provisioner.provision(resource(), props(), ctx());

        verify(secrets).rotateSecret(ARN, null, LAMBDA, null, true, REGION);
    }

    @Test
    void hostedRotationLambdaIsRefused() {
        ObjectNode props = props();
        props.remove("RotationLambdaARN");
        props.putObject("HostedRotationLambda").put("RotationType", "PostgreSQLSingleUser");

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(resource(), props, ctx()));

        assertEquals("ValidationError", e.getErrorCode());
        verify(secrets, never()).rotateSecret(anyString(), any(), any(), any(), anyBoolean(), anyString());
    }

    @Test
    void deleteCancelsTheRotationAndToleratesAGoneSecret() {
        provisioner.delete("AWS::SecretsManager::RotationSchedule", ARN, REGION);
        verify(secrets).cancelRotateSecret(ARN, REGION);

        doThrow(new AwsException("ResourceNotFoundException", "gone", 400))
                .when(secrets).cancelRotateSecret(ARN, REGION);
        provisioner.delete("AWS::SecretsManager::RotationSchedule", ARN, REGION);
    }
}
