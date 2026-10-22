package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ec2.Ec2Service;

import static org.mockito.Mockito.mock;

/** Builds an in-memory {@link ElbV2Service} for tests outside this package, with no data plane or health checks. */
public final class ElbV2TestServices {

    private ElbV2TestServices() {}

    public static ElbV2Service inMemory(String region, String accountId, Ec2Service ec2Service) {
        ElbV2Service service = new ElbV2Service();
        service.dataPlane = mock(ElbV2DataPlane.class);
        service.healthChecker = mock(ElbV2HealthChecker.class);
        service.regionResolver = new RegionResolver(region, accountId);
        service.ec2Service = ec2Service;
        return service;
    }
}
