package io.github.hectorvent.floci.services.backup.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CopyJob {

    @JsonProperty("AccountId")
    private String accountId;

    @JsonProperty("CopyJobId")
    private String copyJobId;

    @JsonProperty("SourceBackupVaultArn")
    private String sourceBackupVaultArn;

    @JsonProperty("SourceRecoveryPointArn")
    private String sourceRecoveryPointArn;

    @JsonProperty("DestinationBackupVaultArn")
    private String destinationBackupVaultArn;

    @JsonProperty("DestinationVaultType")
    private String destinationVaultType;

    @JsonProperty("DestinationRecoveryPointArn")
    private String destinationRecoveryPointArn;

    @JsonProperty("DestinationEncryptionKeyArn")
    private String destinationEncryptionKeyArn;

    @JsonProperty("DestinationRecoveryPointLifecycle")
    private Lifecycle destinationRecoveryPointLifecycle;

    @JsonProperty("ResourceArn")
    private String resourceArn;

    @JsonProperty("ResourceType")
    private String resourceType;

    @JsonProperty("ResourceName")
    private String resourceName;

    @JsonProperty("CreationDate")
    private long creationDate;

    @JsonProperty("CompletionDate")
    private Long completionDate;

    @JsonProperty("State")
    private String state;

    @JsonProperty("StatusMessage")
    private String statusMessage;

    @JsonProperty("BackupSizeInBytes")
    private Long backupSizeInBytes;

    @JsonProperty("IamRoleArn")
    private String iamRoleArn;

    @JsonProperty("IsParent")
    private boolean parent;

    public CopyJob() {}

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getCopyJobId() { return copyJobId; }
    public void setCopyJobId(String copyJobId) { this.copyJobId = copyJobId; }

    public String getSourceBackupVaultArn() { return sourceBackupVaultArn; }
    public void setSourceBackupVaultArn(String sourceBackupVaultArn) { this.sourceBackupVaultArn = sourceBackupVaultArn; }

    public String getSourceRecoveryPointArn() { return sourceRecoveryPointArn; }
    public void setSourceRecoveryPointArn(String sourceRecoveryPointArn) { this.sourceRecoveryPointArn = sourceRecoveryPointArn; }

    public String getDestinationBackupVaultArn() { return destinationBackupVaultArn; }
    public void setDestinationBackupVaultArn(String destinationBackupVaultArn) { this.destinationBackupVaultArn = destinationBackupVaultArn; }

    public String getDestinationVaultType() { return destinationVaultType; }
    public void setDestinationVaultType(String destinationVaultType) { this.destinationVaultType = destinationVaultType; }

    public String getDestinationRecoveryPointArn() { return destinationRecoveryPointArn; }
    public void setDestinationRecoveryPointArn(String destinationRecoveryPointArn) { this.destinationRecoveryPointArn = destinationRecoveryPointArn; }

    public String getDestinationEncryptionKeyArn() { return destinationEncryptionKeyArn; }
    public void setDestinationEncryptionKeyArn(String destinationEncryptionKeyArn) { this.destinationEncryptionKeyArn = destinationEncryptionKeyArn; }

    public Lifecycle getDestinationRecoveryPointLifecycle() { return destinationRecoveryPointLifecycle; }
    public void setDestinationRecoveryPointLifecycle(Lifecycle destinationRecoveryPointLifecycle) { this.destinationRecoveryPointLifecycle = destinationRecoveryPointLifecycle; }

    public String getResourceArn() { return resourceArn; }
    public void setResourceArn(String resourceArn) { this.resourceArn = resourceArn; }

    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }

    public String getResourceName() { return resourceName; }
    public void setResourceName(String resourceName) { this.resourceName = resourceName; }

    public long getCreationDate() { return creationDate; }
    public void setCreationDate(long creationDate) { this.creationDate = creationDate; }

    public Long getCompletionDate() { return completionDate; }
    public void setCompletionDate(Long completionDate) { this.completionDate = completionDate; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getStatusMessage() { return statusMessage; }
    public void setStatusMessage(String statusMessage) { this.statusMessage = statusMessage; }

    public Long getBackupSizeInBytes() { return backupSizeInBytes; }
    public void setBackupSizeInBytes(Long backupSizeInBytes) { this.backupSizeInBytes = backupSizeInBytes; }

    public String getIamRoleArn() { return iamRoleArn; }
    public void setIamRoleArn(String iamRoleArn) { this.iamRoleArn = iamRoleArn; }

    public boolean isParent() { return parent; }
    public void setParent(boolean parent) { this.parent = parent; }
}
