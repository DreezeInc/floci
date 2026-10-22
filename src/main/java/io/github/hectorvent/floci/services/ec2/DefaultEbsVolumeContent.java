package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.services.ec2.model.Volume;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Snapshots carry forward the content a volume was restored from; a fresh volume holds none.
 */
@DefaultBean
@ApplicationScoped
public class DefaultEbsVolumeContent implements EbsVolumeContent {

    @Override
    public String capture(String accountId, String region, Volume volume, String snapshotId) {
        return volume.getSeedContentRef();
    }
}
