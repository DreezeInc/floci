package io.github.hectorvent.floci.services.backup.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RestoreJob {

    @JsonProperty("AccountId")
    private String accountId;

    @JsonProperty("RestoreJobId")
    private String restoreJobId;

    @JsonProperty("RecoveryPointArn")
    private String recoveryPointArn;

    @JsonProperty("SourceResourceArn")
    private String sourceResourceArn;

    @JsonProperty("BackupVaultArn")
    private String backupVaultArn;

    @JsonProperty("CreationDate")
    private long creationDate;

    @JsonProperty("CompletionDate")
    private Long completionDate;

    @JsonProperty("Status")
    private String status;

    @JsonProperty("StatusMessage")
    private String statusMessage;

    @JsonProperty("PercentDone")
    private String percentDone;

    @JsonProperty("BackupSizeInBytes")
    private Long backupSizeInBytes;

    @JsonProperty("IamRoleArn")
    private String iamRoleArn;

    @JsonProperty("CreatedResourceArn")
    private String createdResourceArn;

    @JsonProperty("ResourceType")
    private String resourceType;

    @JsonProperty("RecoveryPointCreationDate")
    private Long recoveryPointCreationDate;

    @JsonProperty("IsParent")
    private boolean parent;

    public RestoreJob() {}

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getRestoreJobId() { return restoreJobId; }
    public void setRestoreJobId(String restoreJobId) { this.restoreJobId = restoreJobId; }

    public String getRecoveryPointArn() { return recoveryPointArn; }
    public void setRecoveryPointArn(String recoveryPointArn) { this.recoveryPointArn = recoveryPointArn; }

    public String getSourceResourceArn() { return sourceResourceArn; }
    public void setSourceResourceArn(String sourceResourceArn) { this.sourceResourceArn = sourceResourceArn; }

    public String getBackupVaultArn() { return backupVaultArn; }
    public void setBackupVaultArn(String backupVaultArn) { this.backupVaultArn = backupVaultArn; }

    public long getCreationDate() { return creationDate; }
    public void setCreationDate(long creationDate) { this.creationDate = creationDate; }

    public Long getCompletionDate() { return completionDate; }
    public void setCompletionDate(Long completionDate) { this.completionDate = completionDate; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getStatusMessage() { return statusMessage; }
    public void setStatusMessage(String statusMessage) { this.statusMessage = statusMessage; }

    public String getPercentDone() { return percentDone; }
    public void setPercentDone(String percentDone) { this.percentDone = percentDone; }

    public Long getBackupSizeInBytes() { return backupSizeInBytes; }
    public void setBackupSizeInBytes(Long backupSizeInBytes) { this.backupSizeInBytes = backupSizeInBytes; }

    public String getIamRoleArn() { return iamRoleArn; }
    public void setIamRoleArn(String iamRoleArn) { this.iamRoleArn = iamRoleArn; }

    public String getCreatedResourceArn() { return createdResourceArn; }
    public void setCreatedResourceArn(String createdResourceArn) { this.createdResourceArn = createdResourceArn; }

    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }

    public Long getRecoveryPointCreationDate() { return recoveryPointCreationDate; }
    public void setRecoveryPointCreationDate(Long recoveryPointCreationDate) { this.recoveryPointCreationDate = recoveryPointCreationDate; }

    public boolean isParent() { return parent; }
    public void setParent(boolean parent) { this.parent = parent; }
}
