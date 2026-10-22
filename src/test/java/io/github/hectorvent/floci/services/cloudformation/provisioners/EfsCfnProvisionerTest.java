package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.efs.EfsService;
import io.github.hectorvent.floci.services.efs.model.AccessPointDescription;
import io.github.hectorvent.floci.services.efs.model.BackupPolicy;
import io.github.hectorvent.floci.services.efs.model.CreateAccessPointRequest;
import io.github.hectorvent.floci.services.efs.model.CreateFileSystemRequest;
import io.github.hectorvent.floci.services.efs.model.CreateMountTargetRequest;
import io.github.hectorvent.floci.services.efs.model.DescribeMountTargetsRequest;
import io.github.hectorvent.floci.services.efs.model.DescribeMountTargetsResponse;
import io.github.hectorvent.floci.services.efs.model.FileSystem;
import io.github.hectorvent.floci.services.efs.model.LifecyclePolicy;
import io.github.hectorvent.floci.services.efs.model.ModifyMountTargetSecurityGroupsRequest;
import io.github.hectorvent.floci.services.efs.model.MountTarget;
import io.github.hectorvent.floci.services.efs.model.PerformanceMode;
import io.github.hectorvent.floci.services.efs.model.ThroughputMode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The three EFS types in isolation: the id each publishes as Ref and as its read-only attributes,
 * the request each template maps onto (policies included), the createOnly checks on update, and
 * the per-type not-found tolerance on delete.
 */
class EfsCfnProvisionerTest {

    private static final String FS_ARN = "arn:aws:elasticfilesystem:us-east-1:000000000000:file-system/fs-0123456789abcdef0";
    private static final String AP_ARN = "arn:aws:elasticfilesystem:us-east-1:000000000000:access-point/fsap-0123456789abcdef0";

    private final EfsService efs = mock(EfsService.class);
    private final EfsCfnProvisioner provisioner = new EfsCfnProvisioner(efs);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void fileSystemPublishesIdAndArnAndAppliesItsPolicies() throws Exception {
        when(efs.createFileSystem(any(CreateFileSystemRequest.class), eq("us-east-1"))).thenReturn(fileSystem());

        StackResource r = resource("AWS::EFS::FileSystem", "Fs");
        provisioner.provision(r, props("""
                {"Encrypted": true, "KmsKeyId": "arn:aws:kms:us-east-1:000000000000:key/k",
                 "PerformanceMode": "generalPurpose", "ThroughputMode": "bursting",
                 "FileSystemTags": [{"Key": "Name", "Value": "data"}],
                 "BackupPolicy": {"Status": "DISABLED"},
                 "LifecyclePolicies": [{"TransitionToIA": "AFTER_30_DAYS"}, {"TransitionToPrimaryStorageClass": "AFTER_1_ACCESS"}],
                 "FileSystemPolicy": {"Version": "2012-10-17", "Statement": []}}
                """), ctx());

        assertEquals("fs-0123456789abcdef0", r.getPhysicalId());
        assertEquals(Set.of("FileSystemId", "Arn"), r.getAttributes().keySet());
        assertEquals(FS_ARN, r.getAttributes().get("Arn"));
        ArgumentCaptor<CreateFileSystemRequest> request = ArgumentCaptor.forClass(CreateFileSystemRequest.class);
        verify(efs).createFileSystem(request.capture(), eq("us-east-1"));
        assertEquals(Boolean.TRUE, request.getValue().getEncrypted());
        assertEquals("arn:aws:kms:us-east-1:000000000000:key/k", request.getValue().getKmsKeyId());
        assertEquals(PerformanceMode.generalPurpose, request.getValue().getPerformanceMode());
        assertEquals(ThroughputMode.bursting, request.getValue().getThroughputMode());
        assertEquals(Boolean.FALSE, request.getValue().getBackup());
        assertEquals("Name", request.getValue().getTags().get(0).getKey());
        verify(efs).putFileSystemPolicy("us-east-1", "fs-0123456789abcdef0", "{\"Version\":\"2012-10-17\",\"Statement\":[]}");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LifecyclePolicy>> lifecycle = ArgumentCaptor.forClass(List.class);
        verify(efs).putLifecycleConfiguration(eq("us-east-1"), eq("fs-0123456789abcdef0"), lifecycle.capture());
        assertEquals("AFTER_30_DAYS", lifecycle.getValue().get(0).getTransitionToIA());
        assertEquals("AFTER_1_ACCESS", lifecycle.getValue().get(1).getTransitionToPrimaryStorageClass());
    }

    @Test
    void fileSystemWithNoPropertiesLeavesEveryRequestFieldToTheService() throws Exception {
        when(efs.createFileSystem(any(CreateFileSystemRequest.class), eq("us-east-1"))).thenReturn(fileSystem());

        provisioner.provision(resource("AWS::EFS::FileSystem", "Fs"), props("{}"), ctx());

        ArgumentCaptor<CreateFileSystemRequest> request = ArgumentCaptor.forClass(CreateFileSystemRequest.class);
        verify(efs).createFileSystem(request.capture(), eq("us-east-1"));
        assertNull(request.getValue().getPerformanceMode());
        assertNull(request.getValue().getThroughputMode());
        assertNull(request.getValue().getBackup());
        assertTrue(request.getValue().getTags().isEmpty());
        verify(efs, never()).putFileSystemPolicy(any(), any(), any());
        verify(efs, never()).putLifecycleConfiguration(any(), any(), anyList());
    }

    @Test
    void fileSystemRejectsAnUnknownPerformanceMode() throws Exception {
        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(
                resource("AWS::EFS::FileSystem", "Fs"), props("{\"PerformanceMode\": \"turbo\"}"), ctx()));

        assertEquals("ValidationError", failure.getErrorCode());
        verify(efs, never()).createFileSystem(any(), any());
    }

    @Test
    void fileSystemUpdateReusesTheExistingOneAndReappliesPolicies() throws Exception {
        when(efs.getFileSystem("us-east-1", "fs-0123456789abcdef0")).thenReturn(fileSystem());

        StackResource r = resource("AWS::EFS::FileSystem", "Fs");
        r.setPhysicalId("fs-0123456789abcdef0");
        provisioner.provision(r, props("""
                {"Encrypted": true, "PerformanceMode": "generalPurpose", "BackupPolicy": {"Status": "ENABLED"},
                 "FileSystemPolicy": {"Version": "2012-10-17", "Statement": []}}
                """), updateCtx("fs-0123456789abcdef0"));

        assertEquals(FS_ARN, r.getAttributes().get("Arn"));
        verify(efs, never()).createFileSystem(any(), any());
        ArgumentCaptor<BackupPolicy> backup = ArgumentCaptor.forClass(BackupPolicy.class);
        verify(efs).putBackupPolicy(eq("us-east-1"), eq("fs-0123456789abcdef0"), backup.capture());
        assertEquals("ENABLED", backup.getValue().getStatus());
        verify(efs).putFileSystemPolicy("us-east-1", "fs-0123456789abcdef0", "{\"Version\":\"2012-10-17\",\"Statement\":[]}");
    }

    @Test
    void fileSystemUpdateChangingEncryptionIsRefusedAsAReplacement() throws Exception {
        when(efs.getFileSystem("us-east-1", "fs-0123456789abcdef0")).thenReturn(fileSystem());

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(
                resource("AWS::EFS::FileSystem", "Fs"), props("{\"Encrypted\": false}"), updateCtx("fs-0123456789abcdef0")));

        assertEquals("ValidationError", failure.getErrorCode());
        assertTrue(failure.getMessage().contains("Encrypted"), failure.getMessage());
    }

    @Test
    void fileSystemUpdateRecreatesOneThatIsGone() throws Exception {
        when(efs.getFileSystem("us-east-1", "fs-0123456789abcdef0"))
                .thenThrow(new AwsException("FileSystemNotFound", "gone", 404));
        when(efs.createFileSystem(any(CreateFileSystemRequest.class), eq("us-east-1"))).thenReturn(fileSystem());

        StackResource r = resource("AWS::EFS::FileSystem", "Fs");
        provisioner.provision(r, props("{\"Encrypted\": true}"), updateCtx("fs-0123456789abcdef0"));

        assertEquals("fs-0123456789abcdef0", r.getPhysicalId());
    }

    @Test
    void mountTargetPublishesIdAndIpAddress() throws Exception {
        when(efs.createMountTarget(any(CreateMountTargetRequest.class), eq("us-east-1"))).thenReturn(mountTarget());

        StackResource r = resource("AWS::EFS::MountTarget", "Mt");
        provisioner.provision(r, props("""
                {"FileSystemId": "fs-0123456789abcdef0", "SubnetId": "subnet-1", "SecurityGroups": ["sg-1", "sg-2"],
                 "IpAddress": "10.0.1.10"}
                """), ctx());

        assertEquals("fsmt-0123456789abcdef0", r.getPhysicalId());
        assertEquals(Set.of("Id", "IpAddress"), r.getAttributes().keySet());
        assertEquals("10.0.1.10", r.getAttributes().get("IpAddress"));
        ArgumentCaptor<CreateMountTargetRequest> request = ArgumentCaptor.forClass(CreateMountTargetRequest.class);
        verify(efs).createMountTarget(request.capture(), eq("us-east-1"));
        assertEquals("fs-0123456789abcdef0", request.getValue().getFileSystemId());
        assertEquals("subnet-1", request.getValue().getSubnetId());
        assertEquals(List.of("sg-1", "sg-2"), request.getValue().getSecurityGroups());
        assertEquals("10.0.1.10", request.getValue().getIpAddress());
    }

    @Test
    void mountTargetRequiresFileSystemAndSubnet() throws Exception {
        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(
                resource("AWS::EFS::MountTarget", "Mt"), props("{\"FileSystemId\": \"fs-0123456789abcdef0\"}"), ctx()));

        assertEquals("ValidationError", failure.getErrorCode());
        verify(efs, never()).createMountTarget(any(), any());
    }

    @Test
    void mountTargetUpdateInTheSameSubnetReusesItAndReconcilesSecurityGroups() throws Exception {
        DescribeMountTargetsResponse response = new DescribeMountTargetsResponse();
        response.setMountTargets(List.of(mountTarget()));
        when(efs.describeMountTargets(eq("us-east-1"), any(DescribeMountTargetsRequest.class))).thenReturn(response);

        StackResource r = resource("AWS::EFS::MountTarget", "Mt");
        r.setPhysicalId("fsmt-0123456789abcdef0");
        provisioner.provision(r, props("""
                {"FileSystemId": "fs-0123456789abcdef0", "SubnetId": "subnet-1", "SecurityGroups": ["sg-3"]}
                """), updateCtx("fsmt-0123456789abcdef0"));

        assertEquals("fsmt-0123456789abcdef0", r.getAttributes().get("Id"));
        verify(efs, never()).createMountTarget(any(), any());
        ArgumentCaptor<ModifyMountTargetSecurityGroupsRequest> modify =
                ArgumentCaptor.forClass(ModifyMountTargetSecurityGroupsRequest.class);
        verify(efs).modifyMountTargetSecurityGroups(eq("us-east-1"), eq("fsmt-0123456789abcdef0"), modify.capture());
        assertEquals(List.of("sg-3"), modify.getValue().getSecurityGroups());
    }

    @Test
    void mountTargetUpdateMovingSubnetIsRefusedAsAReplacement() throws Exception {
        DescribeMountTargetsResponse response = new DescribeMountTargetsResponse();
        response.setMountTargets(List.of(mountTarget()));
        when(efs.describeMountTargets(eq("us-east-1"), any(DescribeMountTargetsRequest.class))).thenReturn(response);

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(
                resource("AWS::EFS::MountTarget", "Mt"),
                props("{\"FileSystemId\": \"fs-0123456789abcdef0\", \"SubnetId\": \"subnet-2\"}"),
                updateCtx("fsmt-0123456789abcdef0")));

        assertEquals("ValidationError", failure.getErrorCode());
        assertTrue(failure.getMessage().contains("SubnetId"), failure.getMessage());
    }

    @Test
    void accessPointPublishesIdAndArnAndMapsPosixUserAndRootDirectory() throws Exception {
        when(efs.createAccessPoint(eq("us-east-1"), any(CreateAccessPointRequest.class))).thenReturn(accessPoint());

        StackResource r = resource("AWS::EFS::AccessPoint", "Ap");
        provisioner.provision(r, props("""
                {"FileSystemId": "fs-0123456789abcdef0",
                 "PosixUser": {"Uid": "1000", "Gid": "1000", "SecondaryGids": ["2000"]},
                 "RootDirectory": {"Path": "/eks-data",
                                   "CreationInfo": {"OwnerUid": "1000", "OwnerGid": "1000", "Permissions": "755"}},
                 "AccessPointTags": [{"Key": "Name", "Value": "ap"}]}
                """), ctx());

        assertEquals("fsap-0123456789abcdef0", r.getPhysicalId());
        assertEquals(Set.of("AccessPointId", "Arn"), r.getAttributes().keySet());
        assertEquals(AP_ARN, r.getAttributes().get("Arn"));
        ArgumentCaptor<CreateAccessPointRequest> request = ArgumentCaptor.forClass(CreateAccessPointRequest.class);
        verify(efs).createAccessPoint(eq("us-east-1"), request.capture());
        assertEquals("fs-0123456789abcdef0", request.getValue().getFileSystemId());
        assertEquals(1000L, request.getValue().getPosixUser().getUid());
        assertEquals(List.of(2000L), request.getValue().getPosixUser().getSecondaryGids());
        assertEquals("/eks-data", request.getValue().getRootDirectory().getPath());
        assertEquals(1000L, request.getValue().getRootDirectory().getCreationInfo().getOwnerGid());
        assertEquals("755", request.getValue().getRootDirectory().getCreationInfo().getPermissions());
        assertEquals("ap", request.getValue().getTags().get(0).getValue());
    }

    @Test
    void accessPointUpdateOnTheSameFileSystemReusesIt() throws Exception {
        when(efs.describeAccessPoints("us-east-1", null, "fsap-0123456789abcdef0")).thenReturn(List.of(accessPoint()));

        StackResource r = resource("AWS::EFS::AccessPoint", "Ap");
        r.setPhysicalId("fsap-0123456789abcdef0");
        provisioner.provision(r, props("{\"FileSystemId\": \"fs-0123456789abcdef0\"}"), updateCtx("fsap-0123456789abcdef0"));

        assertEquals(AP_ARN, r.getAttributes().get("Arn"));
        verify(efs, never()).createAccessPoint(any(), any());
    }

    @Test
    void accessPointUpdateMovingFileSystemIsRefusedAsAReplacement() throws Exception {
        when(efs.describeAccessPoints("us-east-1", null, "fsap-0123456789abcdef0")).thenReturn(List.of(accessPoint()));

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(
                resource("AWS::EFS::AccessPoint", "Ap"), props("{\"FileSystemId\": \"fs-other\"}"),
                updateCtx("fsap-0123456789abcdef0")));

        assertEquals("ValidationError", failure.getErrorCode());
    }

    @Test
    void deleteDispatchesPerTypeAndToleratesEachNotFound() {
        provisioner.delete("AWS::EFS::FileSystem", "fs-1", "us-east-1");
        provisioner.delete("AWS::EFS::MountTarget", "fsmt-1", "us-east-1");
        provisioner.delete("AWS::EFS::AccessPoint", "fsap-1", "us-east-1");
        verify(efs).deleteFileSystem("us-east-1", "fs-1");
        verify(efs).deleteMountTarget("us-east-1", "fsmt-1");
        verify(efs).deleteAccessPoint("us-east-1", "fsap-1");

        doThrow(new AwsException("FileSystemNotFound", "gone", 404)).when(efs).deleteFileSystem("us-east-1", "fs-gone");
        doThrow(new AwsException("MountTargetNotFound", "gone", 404)).when(efs).deleteMountTarget("us-east-1", "fsmt-gone");
        doThrow(new AwsException("AccessPointNotFound", "gone", 404)).when(efs).deleteAccessPoint("us-east-1", "fsap-gone");
        assertDoesNotThrow(() -> provisioner.delete("AWS::EFS::FileSystem", "fs-gone", "us-east-1"));
        assertDoesNotThrow(() -> provisioner.delete("AWS::EFS::MountTarget", "fsmt-gone", "us-east-1"));
        assertDoesNotThrow(() -> provisioner.delete("AWS::EFS::AccessPoint", "fsap-gone", "us-east-1"));
    }

    @Test
    void deletePropagatesAFileSystemStillInUse() {
        doThrow(new AwsException("FileSystemInUse", "mount targets", 409)).when(efs).deleteFileSystem("us-east-1", "fs-busy");

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.delete("AWS::EFS::FileSystem", "fs-busy", "us-east-1"));
        assertEquals("FileSystemInUse", failure.getErrorCode());
    }

    private static FileSystem fileSystem() {
        FileSystem fs = new FileSystem();
        fs.setFileSystemId("fs-0123456789abcdef0");
        fs.setFileSystemArn(FS_ARN);
        fs.setEncrypted(true);
        fs.setPerformanceMode("generalPurpose");
        return fs;
    }

    private static MountTarget mountTarget() {
        MountTarget mt = new MountTarget();
        mt.setMountTargetId("fsmt-0123456789abcdef0");
        mt.setFileSystemId("fs-0123456789abcdef0");
        mt.setSubnetId("subnet-1");
        mt.setIpAddress("10.0.1.10");
        mt.setSecurityGroups(new ArrayList<>(List.of("sg-1", "sg-2")));
        return mt;
    }

    private static AccessPointDescription accessPoint() {
        AccessPointDescription ap = new AccessPointDescription();
        ap.setAccessPointId("fsap-0123456789abcdef0");
        ap.setAccessPointArn(AP_ARN);
        ap.setFileSystemId("fs-0123456789abcdef0");
        return ap;
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
        when(engine.resolveJsonAttributeStrict(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.toString();
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

    private static StackResource resource(String type, String logicalId) {
        StackResource r = new StackResource();
        r.setLogicalId(logicalId);
        r.setResourceType(type);
        r.setAttributes(new HashMap<>());
        return r;
    }
}
