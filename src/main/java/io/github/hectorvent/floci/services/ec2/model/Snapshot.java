package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Snapshot {

    private String snapshotId;
    private String volumeId;
    private Integer volumeSize;
    private String ownerId;
    private String state = "completed";
    private String description;
    private Instant startTime;
    private String progress = "100%";
    private boolean encrypted;
    private String kmsKeyId;
    private String region;
    private List<Tag> tags = new ArrayList<>();
    private List<String> createVolumePermissionUserIds = new ArrayList<>();
    private List<String> createVolumePermissionGroups = new ArrayList<>();
    private String contentRef;
    private SnapshotLock lock;

    public Snapshot() {}

    public Snapshot(Snapshot other) {
        this.snapshotId = other.snapshotId;
        this.volumeId = other.volumeId;
        this.volumeSize = other.volumeSize;
        this.ownerId = other.ownerId;
        this.state = other.state;
        this.description = other.description;
        this.startTime = other.startTime;
        this.progress = other.progress;
        this.encrypted = other.encrypted;
        this.kmsKeyId = other.kmsKeyId;
        this.region = other.region;
        this.tags = new ArrayList<>(other.tags);
        this.createVolumePermissionUserIds = new ArrayList<>(other.createVolumePermissionUserIds);
        this.createVolumePermissionGroups = new ArrayList<>(other.createVolumePermissionGroups);
        this.contentRef = other.contentRef;
        this.lock = other.lock != null ? new SnapshotLock(other.lock) : null;
    }

    public String getSnapshotId() { return snapshotId; }
    public void setSnapshotId(String snapshotId) { this.snapshotId = snapshotId; }

    public String getVolumeId() { return volumeId; }
    public void setVolumeId(String volumeId) { this.volumeId = volumeId; }

    public Integer getVolumeSize() { return volumeSize; }
    public void setVolumeSize(Integer volumeSize) { this.volumeSize = volumeSize; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Instant getStartTime() { return startTime; }
    public void setStartTime(Instant startTime) { this.startTime = startTime; }

    public String getProgress() { return progress; }
    public void setProgress(String progress) { this.progress = progress; }

    public boolean isEncrypted() { return encrypted; }
    public void setEncrypted(boolean encrypted) { this.encrypted = encrypted; }

    public String getKmsKeyId() { return kmsKeyId; }
    public void setKmsKeyId(String kmsKeyId) { this.kmsKeyId = kmsKeyId; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }

    public List<String> getCreateVolumePermissionUserIds() { return createVolumePermissionUserIds; }
    public void setCreateVolumePermissionUserIds(List<String> createVolumePermissionUserIds) {
        this.createVolumePermissionUserIds = createVolumePermissionUserIds;
    }

    public List<String> getCreateVolumePermissionGroups() { return createVolumePermissionGroups; }
    public void setCreateVolumePermissionGroups(List<String> createVolumePermissionGroups) {
        this.createVolumePermissionGroups = createVolumePermissionGroups;
    }

    public String getContentRef() { return contentRef; }
    public void setContentRef(String contentRef) { this.contentRef = contentRef; }

    public SnapshotLock getLock() { return lock; }
    public void setLock(SnapshotLock lock) { this.lock = lock; }
}
