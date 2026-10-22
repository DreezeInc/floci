package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/** The lock state is not stored: it follows from the clock, so it lapses on its own. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SnapshotLock {

    public static final String GOVERNANCE = "governance";
    public static final String COMPLIANCE = "compliance";
    public static final String COMPLIANCE_COOLOFF = "compliance-cooloff";
    public static final String EXPIRED = "expired";

    private String lockMode;
    private Integer lockDuration;
    private Integer coolOffPeriod;
    private Instant coolOffPeriodExpiresOn;
    private Instant lockCreatedOn;
    private Instant lockDurationStartTime;
    private Instant lockExpiresOn;

    public SnapshotLock() {}

    public SnapshotLock(SnapshotLock other) {
        this.lockMode = other.lockMode;
        this.lockDuration = other.lockDuration;
        this.coolOffPeriod = other.coolOffPeriod;
        this.coolOffPeriodExpiresOn = other.coolOffPeriodExpiresOn;
        this.lockCreatedOn = other.lockCreatedOn;
        this.lockDurationStartTime = other.lockDurationStartTime;
        this.lockExpiresOn = other.lockExpiresOn;
    }

    public String stateAt(Instant now) {
        if (!now.isBefore(lockExpiresOn)) {
            return EXPIRED;
        }
        if (GOVERNANCE.equals(lockMode)) {
            return GOVERNANCE;
        }
        boolean coolingOff = coolOffPeriodExpiresOn != null && now.isBefore(coolOffPeriodExpiresOn);
        return coolingOff ? COMPLIANCE_COOLOFF : COMPLIANCE;
    }

    public String getLockMode() { return lockMode; }
    public void setLockMode(String lockMode) { this.lockMode = lockMode; }

    public Integer getLockDuration() { return lockDuration; }
    public void setLockDuration(Integer lockDuration) { this.lockDuration = lockDuration; }

    public Integer getCoolOffPeriod() { return coolOffPeriod; }
    public void setCoolOffPeriod(Integer coolOffPeriod) { this.coolOffPeriod = coolOffPeriod; }

    public Instant getCoolOffPeriodExpiresOn() { return coolOffPeriodExpiresOn; }
    public void setCoolOffPeriodExpiresOn(Instant coolOffPeriodExpiresOn) {
        this.coolOffPeriodExpiresOn = coolOffPeriodExpiresOn;
    }

    public Instant getLockCreatedOn() { return lockCreatedOn; }
    public void setLockCreatedOn(Instant lockCreatedOn) { this.lockCreatedOn = lockCreatedOn; }

    public Instant getLockDurationStartTime() { return lockDurationStartTime; }
    public void setLockDurationStartTime(Instant lockDurationStartTime) {
        this.lockDurationStartTime = lockDurationStartTime;
    }

    public Instant getLockExpiresOn() { return lockExpiresOn; }
    public void setLockExpiresOn(Instant lockExpiresOn) { this.lockExpiresOn = lockExpiresOn; }
}
