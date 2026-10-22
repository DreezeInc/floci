package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ec2.model.Volume;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
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
 * {@code AWS::EC2::Volume} in isolation: the {@code vol-} id as both Ref and VolumeId, the scalar
 * mapping onto createVolume, the AvailabilityZone-keyed update path and the delete tolerance.
 */
class Ec2VolumeCfnProvisionerTest {

    private final Ec2Service ec2 = mock(Ec2Service.class);
    private final Ec2VolumeCfnProvisioner provisioner = new Ec2VolumeCfnProvisioner(ec2);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void refAndVolumeIdAreTheVolumeId() throws Exception {
        when(ec2.createVolume(eq("us-east-1"), eq("us-east-1a"), eq("gp3"), eq(10), eq(true), eq(3000), eq(125),
                isNull(), anyList())).thenReturn(volume("us-east-1a"));

        StackResource r = resource("Data");
        provisioner.provision(r, props("""
                {"AvailabilityZone": "us-east-1a", "Size": 10, "VolumeType": "gp3", "Encrypted": true,
                 "Iops": 3000, "Throughput": 125, "KmsKeyId": "arn:aws:kms:us-east-1:000000000000:key/k",
                 "Tags": [{"Key": "Name", "Value": "data"}]}
                """), ctx());

        assertEquals("vol-0123456789abcdef0", r.getPhysicalId());
        assertEquals(Set.of("VolumeId"), r.getAttributes().keySet());
        assertEquals("vol-0123456789abcdef0", r.getAttributes().get("VolumeId"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Tag>> tags = ArgumentCaptor.forClass(List.class);
        verify(ec2).createVolume(eq("us-east-1"), eq("us-east-1a"), eq("gp3"), eq(10), eq(true), eq(3000), eq(125),
                isNull(), tags.capture());
        assertEquals(1, tags.getValue().size());
        assertEquals("Name", tags.getValue().get(0).getKey());
        assertEquals("data", tags.getValue().get(0).getValue());
    }

    @Test
    void absentScalarsFallToTheServiceDefaults() throws Exception {
        when(ec2.createVolume(eq("us-east-1"), eq("us-east-1a"), isNull(), eq(0), eq(false), eq(0), isNull(),
                isNull(), anyList())).thenReturn(volume("us-east-1a"));

        provisioner.provision(resource("Data"), props("{\"AvailabilityZone\": \"us-east-1a\"}"), ctx());

        verify(ec2).createVolume(eq("us-east-1"), eq("us-east-1a"), isNull(), eq(0), eq(false), eq(0), isNull(),
                isNull(), anyList());
    }

    @Test
    void missingAvailabilityZoneFailsBeforeTouchingEc2() throws Exception {
        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(resource("Data"), props("{\"Size\": 10}"), ctx()));

        assertEquals("ValidationError", failure.getErrorCode());
        verify(ec2, never()).createVolume(anyString(), any(), any(), anyInt(), anyBoolean(), anyInt(), any(), any(),
                anyList());
    }

    @Test
    void updateInTheSameZoneReusesTheVolume() throws Exception {
        when(ec2.describeVolumes("us-east-1", List.of("vol-0123456789abcdef0"), null))
                .thenReturn(List.of(volume("us-east-1a")));

        StackResource r = resource("Data");
        r.setPhysicalId("vol-0123456789abcdef0");
        provisioner.provision(r, props("{\"AvailabilityZone\": \"us-east-1a\", \"Size\": 20}"),
                updateCtx("vol-0123456789abcdef0"));

        assertEquals("vol-0123456789abcdef0", r.getAttributes().get("VolumeId"));
        verify(ec2, never()).createVolume(anyString(), any(), any(), anyInt(), anyBoolean(), anyInt(), any(), any(),
                anyList());
    }

    @Test
    void updateMovingTheZoneIsRefusedAsAReplacement() throws Exception {
        when(ec2.describeVolumes("us-east-1", List.of("vol-0123456789abcdef0"), null))
                .thenReturn(List.of(volume("us-east-1a")));

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(resource("Data"),
                props("{\"AvailabilityZone\": \"us-east-1b\"}"), updateCtx("vol-0123456789abcdef0")));

        assertEquals("ValidationError", failure.getErrorCode());
        assertTrue(failure.getMessage().contains("AvailabilityZone"), failure.getMessage());
    }

    @Test
    void updateRecreatesAVolumeThatIsGone() throws Exception {
        when(ec2.describeVolumes("us-east-1", List.of("vol-0123456789abcdef0"), null))
                .thenThrow(new AwsException("InvalidVolume.NotFound", "gone", 400));
        when(ec2.createVolume(eq("us-east-1"), eq("us-east-1a"), any(), anyInt(), anyBoolean(), anyInt(), any(),
                any(), anyList())).thenReturn(volume("us-east-1a"));

        StackResource r = resource("Data");
        provisioner.provision(r, props("{\"AvailabilityZone\": \"us-east-1a\"}"), updateCtx("vol-0123456789abcdef0"));

        assertEquals("vol-0123456789abcdef0", r.getPhysicalId());
    }

    @Test
    void deleteRemovesTheVolumeAndToleratesOneAlreadyGone() {
        provisioner.delete("AWS::EC2::Volume", "vol-0123456789abcdef0", "us-east-1");
        verify(ec2).deleteVolume("us-east-1", "vol-0123456789abcdef0");

        doThrow(new AwsException("InvalidVolume.NotFound", "gone", 400)).when(ec2).deleteVolume("us-east-1", "vol-gone");
        assertDoesNotThrow(() -> provisioner.delete("AWS::EC2::Volume", "vol-gone", "us-east-1"));
    }

    @Test
    void deletePropagatesAVolumeStillInUse() {
        doThrow(new AwsException("VolumeInUse", "attached", 400)).when(ec2).deleteVolume("us-east-1", "vol-busy");

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.delete("AWS::EC2::Volume", "vol-busy", "us-east-1"));
        assertEquals("VolumeInUse", failure.getErrorCode());
    }

    @Test
    void deleteSkipsABlankId() {
        provisioner.delete("AWS::EC2::Volume", null, "us-east-1");

        verify(ec2, never()).deleteVolume(any(), any());
        assertNull(resource("Data").getPhysicalId());
    }

    private static Volume volume(String availabilityZone) {
        Volume volume = new Volume();
        volume.setVolumeId("vol-0123456789abcdef0");
        volume.setAvailabilityZone(availabilityZone);
        return volume;
    }

    private ProvisionContext ctx() {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
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
        r.setResourceType("AWS::EC2::Volume");
        r.setAttributes(new HashMap<>());
        return r;
    }
}
