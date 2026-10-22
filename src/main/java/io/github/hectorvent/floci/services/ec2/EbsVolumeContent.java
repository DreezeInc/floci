package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.services.ec2.model.Volume;

/**
 * Captures what an EBS volume holds when a snapshot of it is taken.
 */
public interface EbsVolumeContent {

    /** Opaque reference to the data a new snapshot of this volume holds, or null when it holds none. */
    String capture(String accountId, String region, Volume volume, String snapshotId);
}
