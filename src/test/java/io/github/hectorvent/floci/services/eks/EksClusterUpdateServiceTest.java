package io.github.hectorvent.floci.services.eks;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.eks.model.AccessConfig;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ClusterStatus;
import io.github.hectorvent.floci.services.eks.model.CreateAddonRequest;
import io.github.hectorvent.floci.services.eks.model.LogSetup;
import io.github.hectorvent.floci.services.eks.model.Logging;
import io.github.hectorvent.floci.services.eks.model.ResourcesVpcConfig;
import io.github.hectorvent.floci.services.eks.model.Update;
import io.github.hectorvent.floci.services.eks.model.UpdateAccessConfigRequest;
import io.github.hectorvent.floci.services.eks.model.UpdateAddonRequest;
import io.github.hectorvent.floci.services.eks.model.UpdateClusterConfigRequest;
import io.github.hectorvent.floci.services.eks.model.UpdateClusterVersionRequest;
import io.github.hectorvent.floci.services.eks.model.UpdateParam;
import io.github.hectorvent.floci.services.iam.IamService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EksClusterUpdateServiceTest {

    @Test
    void vpcConfigUpdateAppliesEndpointAccessAndCidrs() throws Exception {
        Fixture fixture = fixture();
        ResourcesVpcConfig requested = new ResourcesVpcConfig();
        requested.setEndpointPublicAccess(false);
        requested.setEndpointPrivateAccess(true);
        requested.setPublicAccessCidrs(List.of("203.0.113.0/24"));

        Update update = fixture.service.updateClusterConfig(fixture.cluster, vpcRequest(requested));

        assertEquals("VpcConfigUpdate", update.type());
        assertEquals("Successful", update.status());
        assertTrue(update.errors().isEmpty());
        assertTrue(update.createdAt() > 0);
        assertEquals(Map.of("EndpointPublicAccess", "false", "EndpointPrivateAccess", "true",
                "PublicAccessCidrs", "[\"203.0.113.0/24\"]"), params(update));
        ResourcesVpcConfig applied = fixture.cluster.getResourcesVpcConfig();
        assertFalse(applied.getEndpointPublicAccess());
        assertTrue(applied.getEndpointPrivateAccess());
        assertEquals(List.of("203.0.113.0/24"), applied.getPublicAccessCidrs());
        assertEquals(List.of("subnet-a"), applied.getSubnetIds());

        Update stored = fixture.storage.scan(key -> true).getFirst();
        ObjectMapper mapper = new ObjectMapper();
        assertEquals(stored, mapper.readValue(mapper.writeValueAsBytes(stored), Update.class));
        assertEquals(update, fixture.service.describeUpdate(fixture.cluster, update.id(), null, null));
    }

    @Test
    void vpcConfigUpdateKeepsUnspecifiedFieldsAndRejectsDisablingBothEndpoints() {
        Fixture fixture = fixture();
        ResourcesVpcConfig privateOnly = new ResourcesVpcConfig();
        privateOnly.setEndpointPrivateAccess(true);
        Update update = fixture.service.updateClusterConfig(fixture.cluster, vpcRequest(privateOnly));
        assertEquals(Map.of("EndpointPrivateAccess", "true"), params(update));
        assertTrue(fixture.cluster.getResourcesVpcConfig().getEndpointPublicAccess());
        assertEquals(List.of("0.0.0.0/0"), fixture.cluster.getResourcesVpcConfig().getPublicAccessCidrs());

        ResourcesVpcConfig publicOff = new ResourcesVpcConfig();
        publicOff.setEndpointPublicAccess(false);
        fixture.cluster.getResourcesVpcConfig().setEndpointPrivateAccess(false);
        assertInvalid(() -> fixture.service.updateClusterConfig(fixture.cluster, vpcRequest(publicOff)));
        assertTrue(fixture.cluster.getResourcesVpcConfig().getEndpointPublicAccess());

        ResourcesVpcConfig badCidr = new ResourcesVpcConfig();
        badCidr.setPublicAccessCidrs(List.of("not-a-cidr"));
        assertInvalid(() -> fixture.service.updateClusterConfig(fixture.cluster, vpcRequest(badCidr)));

        ResourcesVpcConfig subnets = new ResourcesVpcConfig();
        subnets.setSubnetIds(List.of("subnet-b"));
        assertInvalid(() -> fixture.service.updateClusterConfig(fixture.cluster, vpcRequest(subnets)));

        assertInvalid(() -> fixture.service.updateClusterConfig(fixture.cluster, vpcRequest(new ResourcesVpcConfig())));
        assertEquals(1, fixture.storage.keys().size());
    }

    @Test
    void loggingUpdateMergesIntoTheCurrentLogSetup() {
        Fixture fixture = fixture();
        fixture.cluster.setLogging(new Logging(List.of(
                new LogSetup(List.of("api"), true),
                new LogSetup(List.of("audit", "authenticator", "controllerManager", "scheduler"), false))));
        Logging requested = new Logging(List.of(new LogSetup(List.of("audit", "scheduler"), true)));

        Update update = fixture.service.updateClusterConfig(fixture.cluster,
                new UpdateClusterConfigRequest(null, requested, null, null));

        assertEquals("LoggingUpdate", update.type());
        assertEquals(Map.of("ClusterLogging",
                "{\"clusterLogging\":[{\"types\":[\"audit\",\"scheduler\"],\"enabled\":true}]}"), params(update));
        assertEquals(new Logging(List.of(
                new LogSetup(List.of("api", "audit", "scheduler"), true),
                new LogSetup(List.of("authenticator", "controllerManager"), false))), fixture.cluster.getLogging());

        Logging disableAll = new Logging(List.of(new LogSetup(List.of("api", "audit", "scheduler"), false)));
        fixture.service.updateClusterConfig(fixture.cluster, new UpdateClusterConfigRequest(null, disableAll, null, null));
        assertEquals(new Logging(List.of(new LogSetup(
                List.of("api", "audit", "authenticator", "controllerManager", "scheduler"), false))),
                fixture.cluster.getLogging());

        Logging unknownType = new Logging(List.of(new LogSetup(List.of("kubelet"), true)));
        assertInvalid(() -> fixture.service.updateClusterConfig(fixture.cluster,
                new UpdateClusterConfigRequest(null, unknownType, null, null)));
        assertInvalid(() -> fixture.service.updateClusterConfig(fixture.cluster,
                new UpdateClusterConfigRequest(null, new Logging(List.of()), null, null)));
    }

    @Test
    void authenticationModeOnlyMovesForward() {
        Fixture fixture = fixture();
        fixture.cluster.setAccessConfig(new AccessConfig("CONFIG_MAP", true));

        Update update = fixture.service.updateClusterConfig(fixture.cluster, accessRequest("API_AND_CONFIG_MAP"));

        assertEquals("AccessConfigUpdate", update.type());
        assertEquals(Map.of("AuthenticationMode", "API_AND_CONFIG_MAP"), params(update));
        assertEquals(new AccessConfig("API_AND_CONFIG_MAP", true), fixture.cluster.getAccessConfig());
        assertInvalid(() -> fixture.service.updateClusterConfig(fixture.cluster, accessRequest("API_AND_CONFIG_MAP")));
        assertInvalid(() -> fixture.service.updateClusterConfig(fixture.cluster, accessRequest("CONFIG_MAP")));
        assertInvalid(() -> fixture.service.updateClusterConfig(fixture.cluster, accessRequest("SAML")));
        fixture.service.updateClusterConfig(fixture.cluster, accessRequest("API"));
        assertEquals("API", fixture.cluster.getAccessConfig().authenticationMode());
    }

    @Test
    void updateConfigRequiresExactlyOneUpdateTypeOnAnActiveCluster() {
        Fixture fixture = fixture();
        ResourcesVpcConfig vpc = new ResourcesVpcConfig();
        vpc.setEndpointPrivateAccess(true);
        Logging logging = new Logging(List.of(new LogSetup(List.of("api"), true)));

        assertInvalid(() -> fixture.service.updateClusterConfig(fixture.cluster, null));
        assertInvalid(() -> fixture.service.updateClusterConfig(fixture.cluster,
                new UpdateClusterConfigRequest(null, null, null, "token")));
        assertInvalid(() -> fixture.service.updateClusterConfig(fixture.cluster,
                new UpdateClusterConfigRequest(null, null, new UpdateAccessConfigRequest(null), null)));
        assertInvalid(() -> fixture.service.updateClusterConfig(fixture.cluster,
                new UpdateClusterConfigRequest(vpc, logging, null, null)));
        assertInvalid(() -> fixture.service.updateClusterConfig(fixture.cluster,
                new UpdateClusterConfigRequest(vpc, null, new UpdateAccessConfigRequest("API"), null)));
        assertTrue(fixture.storage.keys().isEmpty());

        fixture.cluster.setStatus(ClusterStatus.CREATING);
        assertEquals("InvalidRequestException", assertThrows(AwsException.class,
                () -> fixture.service.updateClusterConfig(fixture.cluster, vpcRequest(vpc))).getErrorCode());
        assertEquals("InvalidRequestException", assertThrows(AwsException.class,
                () -> fixture.service.updateClusterVersion(fixture.cluster,
                        new UpdateClusterVersionRequest("1.30", null, null))).getErrorCode());
    }

    @Test
    void versionUpdateStepsOneMinorAtATime() {
        Fixture fixture = fixture();
        fixture.cluster.setVersion("1.29");
        fixture.cluster.setExplicitVersion(false);

        Update update = fixture.service.updateClusterVersion(fixture.cluster,
                new UpdateClusterVersionRequest("1.30", "token", false));

        assertEquals("VersionUpdate", update.type());
        assertEquals("Successful", update.status());
        assertEquals(Map.of("Version", "1.30"), params(update));
        assertEquals("1.30", fixture.cluster.getVersion());
        assertTrue(fixture.cluster.isExplicitVersion());

        assertInvalid(() -> fixture.service.updateClusterVersion(fixture.cluster, null));
        assertInvalid(() -> fixture.service.updateClusterVersion(fixture.cluster,
                new UpdateClusterVersionRequest(" ", null, null)));
        assertInvalid(() -> fixture.service.updateClusterVersion(fixture.cluster,
                new UpdateClusterVersionRequest("v1.31", null, null)));
        assertInvalid(() -> fixture.service.updateClusterVersion(fixture.cluster,
                new UpdateClusterVersionRequest("1.30", null, null)));
        assertInvalid(() -> fixture.service.updateClusterVersion(fixture.cluster,
                new UpdateClusterVersionRequest("1.29", null, null)));
        assertInvalid(() -> fixture.service.updateClusterVersion(fixture.cluster,
                new UpdateClusterVersionRequest("1.32", null, null)));
        assertInvalid(() -> fixture.service.updateClusterVersion(fixture.cluster,
                new UpdateClusterVersionRequest("1.27", null, null)));
        assertEquals("1.30", fixture.cluster.getVersion());
    }

    @Test
    void describeAndListSpanClusterAndAddonUpdates() {
        Fixture fixture = fixture();
        fixture.cluster.setVersion("1.29");
        Update version = fixture.service.updateClusterVersion(fixture.cluster,
                new UpdateClusterVersionRequest("1.30", null, null));
        fixture.addons.create(fixture.cluster, new CreateAddonRequest("vpc-cni", null, null, null, null, null, null, null));
        Update addon = fixture.addons.update(fixture.cluster, "vpc-cni",
                new UpdateAddonRequest("v1.18.5-eksbuild.1", null, null, null, null, null));

        assertEquals(version, fixture.service.describeUpdate(fixture.cluster, version.id(), null, null));
        assertEquals(addon, fixture.service.describeUpdate(fixture.cluster, addon.id(), null, null));
        assertEquals(addon, fixture.service.describeUpdate(fixture.cluster, addon.id(), "vpc-cni", null));
        assertNotFound(() -> fixture.service.describeUpdate(fixture.cluster, addon.id(), "coredns", null));
        assertNotFound(() -> fixture.service.describeUpdate(fixture.cluster, version.id(), "vpc-cni", null));
        assertNotFound(() -> fixture.service.describeUpdate(fixture.cluster, version.id(), null, "workers"));
        assertNotFound(() -> fixture.service.describeUpdate(fixture.cluster, "missing", null, null));
        assertInvalid(() -> fixture.service.describeUpdate(fixture.cluster, " ", null, null));

        EksClusterUpdateService.Page all = fixture.service.listUpdates(fixture.cluster, null, null, null, null);
        assertEquals(2, all.updateIds().size());
        assertTrue(all.updateIds().containsAll(List.of(version.id(), addon.id())));
        assertNull(all.nextToken());
        assertEquals(List.of(addon.id()),
                fixture.service.listUpdates(fixture.cluster, "vpc-cni", null, null, null).updateIds());
        assertTrue(fixture.service.listUpdates(fixture.cluster, null, "workers", null, null).updateIds().isEmpty());

        EksClusterUpdateService.Page first = fixture.service.listUpdates(fixture.cluster, null, null, 1, null);
        assertEquals(1, first.updateIds().size());
        assertNotNull(first.nextToken());
        EksClusterUpdateService.Page second = fixture.service.listUpdates(fixture.cluster, null, null, 1, first.nextToken());
        assertEquals(1, second.updateIds().size());
        assertNull(second.nextToken());
        assertNotEquals(first.updateIds(), second.updateIds());
        assertInvalid(() -> fixture.service.listUpdates(fixture.cluster, null, null, 0, null));
        assertInvalid(() -> fixture.service.listUpdates(fixture.cluster, null, null, 101, null));
    }

    @Test
    void clusterDeletionDropsUpdatesAndRecreationCannotInheritThem() {
        Fixture fixture = fixture();
        fixture.cluster.setVersion("1.29");
        Update update = fixture.service.updateClusterVersion(fixture.cluster,
                new UpdateClusterVersionRequest("1.30", null, null));
        fixture.service.deleteClusterUpdates(fixture.cluster);
        assertTrue(fixture.storage.keys().isEmpty());
        assertNotFound(() -> fixture.service.describeUpdate(fixture.cluster, update.id(), null, null));

        Update again = fixture.service.updateClusterVersion(fixture.cluster,
                new UpdateClusterVersionRequest("1.31", null, null));
        fixture.cluster.setCreatedAt(fixture.cluster.getCreatedAt().plusSeconds(1));
        assertTrue(fixture.service.listUpdates(fixture.cluster, null, null, null, null).updateIds().isEmpty());
        assertNotFound(() -> fixture.service.describeUpdate(fixture.cluster, again.id(), null, null));
    }

    private static Map<String, String> params(Update update) {
        return update.params().stream().collect(Collectors.toMap(UpdateParam::type, UpdateParam::value));
    }

    private static UpdateClusterConfigRequest vpcRequest(ResourcesVpcConfig vpc) {
        return new UpdateClusterConfigRequest(vpc, null, null, null);
    }

    private static UpdateClusterConfigRequest accessRequest(String mode) {
        return new UpdateClusterConfigRequest(null, null, new UpdateAccessConfigRequest(mode), null);
    }

    private static void assertInvalid(Executable call) {
        AwsException exception = assertThrows(AwsException.class, call);
        assertEquals("InvalidParameterException", exception.getErrorCode());
        assertEquals(400, exception.getHttpStatus());
    }

    private static void assertNotFound(Executable call) {
        AwsException exception = assertThrows(AwsException.class, call);
        assertEquals("ResourceNotFoundException", exception.getErrorCode());
        assertEquals(404, exception.getHttpStatus());
    }

    private static Fixture fixture() {
        Cluster cluster = new Cluster();
        cluster.setName("updates");
        cluster.setArn("arn:aws:eks:us-east-1:123456789012:cluster/updates");
        cluster.setCreatedAt(Instant.parse("2026-10-20T10:00:00Z"));
        cluster.setStatus(ClusterStatus.ACTIVE);
        cluster.setVersion("1.29");
        cluster.setAccessConfig(new AccessConfig("API", false));
        ResourcesVpcConfig vpc = new ResourcesVpcConfig();
        vpc.setSubnetIds(List.of("subnet-a"));
        vpc.setSecurityGroupIds(List.of());
        vpc.setVpcId("vpc-1");
        vpc.setEndpointPublicAccess(true);
        vpc.setEndpointPrivateAccess(false);
        vpc.setPublicAccessCidrs(List.of("0.0.0.0/0"));
        cluster.setResourcesVpcConfig(vpc);

        IamService iam = mock(IamService.class);
        EksPodIdentityAssociationService podIdentityAssociations = new EksPodIdentityAssociationService(
                new InMemoryStorage<>(), iam, new RegionResolver("us-east-1", "123456789012"));
        EksAddonService addons = new EksAddonService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new EksAddonCatalog(), iam, podIdentityAssociations);
        InMemoryStorage<String, Update> storage = new InMemoryStorage<>();
        return new Fixture(cluster, addons, storage, new EksClusterUpdateService(storage, addons));
    }

    private record Fixture(Cluster cluster, EksAddonService addons, InMemoryStorage<String, Update> storage,
                           EksClusterUpdateService service) {}
}
