package io.github.hectorvent.floci.services.eks;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.eks.model.AssociateIdentityProviderConfigRequest;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ClusterStatus;
import io.github.hectorvent.floci.services.eks.model.IdentityProviderConfig;
import io.github.hectorvent.floci.services.eks.model.OidcIdentityProviderConfig;
import io.github.hectorvent.floci.services.eks.model.OidcIdentityProviderConfigRequest;
import io.github.hectorvent.floci.services.eks.model.Update;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EksIdentityProviderConfigServiceTest {

    private static final IdentityProviderConfig GOOGLE = new IdentityProviderConfig("oidc", "google");

    private final InMemoryStorage<String, EksIdentityProviderConfigService.StoredConfig> configs = new InMemoryStorage<>();
    private final InMemoryStorage<String, Update> updates = new InMemoryStorage<>();
    private final EksIdentityProviderConfigService service = new EksIdentityProviderConfigService(configs, updates);
    private final Cluster cluster = cluster("prod");

    @Test
    void associateStoresAnActiveConfigAndASuccessfulUpdate() throws Exception {
        Update update = service.associate(cluster, request("google", Map.of("team", "platform"), null));

        assertEquals("AssociateIdentityProviderConfig", update.type());
        assertEquals("Successful", update.status());
        assertTrue(update.errors().isEmpty());
        assertEquals(1, update.params().size());
        assertEquals("IdentityProviderConfig", update.params().getFirst().type());
        assertEquals("[{\"type\":\"oidc\",\"name\":\"google\"}]", update.params().getFirst().value());

        OidcIdentityProviderConfig config = service.describe(cluster, GOOGLE);
        assertEquals("google", config.identityProviderConfigName());
        assertEquals("prod", config.clusterName());
        assertEquals("ACTIVE", config.status());
        assertEquals("https://accounts.google.com", config.issuerUrl());
        assertEquals("example-client", config.clientId());
        assertEquals("email", config.usernameClaim());
        assertEquals("google:", config.usernamePrefix());
        assertEquals("groups", config.groupsClaim());
        assertEquals("gid:", config.groupsPrefix());
        assertEquals(Map.of("hd", "example.com"), config.requiredClaims());
        assertEquals(Map.of("team", "platform"), config.tags());
        assertTrue(config.identityProviderConfigArn().startsWith(
                "arn:aws:eks:us-east-1:123456789012:identityproviderconfig/prod/oidc/google/"));

        assertEquals(List.of(update), service.listUpdates(cluster));
        assertEquals(update, service.findUpdate(cluster, update.id()).orElseThrow());
        ObjectMapper mapper = new ObjectMapper();
        EksIdentityProviderConfigService.StoredConfig stored = configs.scan(key -> true).getFirst();
        assertEquals(stored, mapper.readValue(mapper.writeValueAsBytes(stored),
                EksIdentityProviderConfigService.StoredConfig.class));
    }

    @Test
    void listReturnsOidcHandlesWithPagination() {
        service.associate(cluster, request("google", null, null));

        EksIdentityProviderConfigService.Page page = service.list(cluster, null, null);
        assertEquals(List.of(GOOGLE), page.identityProviderConfigs());
        assertNull(page.nextToken());

        assertInvalid(() -> service.list(cluster, 0, null));
        assertInvalid(() -> service.list(cluster, 101, null));
        assertTrue(service.list(cluster("other"), null, null).identityProviderConfigs().isEmpty());
    }

    @Test
    void disassociateRemovesTheConfigAndKeepsBothUpdates() {
        Update associated = service.associate(cluster, request("google", null, null));

        Update disassociated = service.disassociate(cluster, GOOGLE);

        assertEquals("DisassociateIdentityProviderConfig", disassociated.type());
        assertEquals("Successful", disassociated.status());
        assertNotEquals(associated.id(), disassociated.id());
        assertNotFound(() -> service.describe(cluster, GOOGLE));
        assertNotFound(() -> service.disassociate(cluster, GOOGLE));
        assertTrue(service.list(cluster, null, null).identityProviderConfigs().isEmpty());
        assertEquals(2, service.listUpdates(cluster).size());
        assertEquals(disassociated, service.findUpdate(cluster, disassociated.id()).orElseThrow());

        Update again = service.associate(cluster, request("google", null, null));
        assertNotEquals(associated.id(), again.id());
    }

    @Test
    void aClusterHoldsOneOidcConfigAndRejectsDuplicates() {
        service.associate(cluster, request("google", null, null));

        AwsException duplicate = assertThrows(AwsException.class,
                () -> service.associate(cluster, request("google", null, null)));
        assertEquals("ResourceInUseException", duplicate.getErrorCode());
        assertEquals(409, duplicate.getHttpStatus());

        AwsException second = assertThrows(AwsException.class,
                () -> service.associate(cluster, request("okta", null, null)));
        assertEquals("ResourceInUseException", second.getErrorCode());
        assertEquals(1, service.listUpdates(cluster).size());
    }

    @Test
    void clientRequestTokenReplaysTheSameUpdateAndRefusesDifferentParameters() {
        Update first = service.associate(cluster, request("google", null, "token-1"));

        assertEquals(first, service.associate(cluster, request("google", null, "token-1")));
        assertInvalid(() -> service.associate(cluster, request("google", Map.of("a", "b"), "token-1")));
        assertEquals(1, service.listUpdates(cluster).size());
    }

    @Test
    void associateValidatesTheRequest() {
        assertInvalid(() -> service.associate(cluster, null));
        assertInvalid(() -> service.associate(cluster, new AssociateIdentityProviderConfigRequest(null, null, null)));
        assertInvalid(() -> service.associate(cluster, oidc(null, "https://issuer", "client", null, null)));
        assertInvalid(() -> service.associate(cluster, oidc("x".repeat(101), "https://issuer", "client", null, null)));
        assertInvalid(() -> service.associate(cluster, oidc("google", "http://issuer", "client", null, null)));
        assertInvalid(() -> service.associate(cluster, oidc("google", null, "client", null, null)));
        assertInvalid(() -> service.associate(cluster, oidc("google", "https://issuer", " ", null, null)));
        assertInvalid(() -> service.associate(cluster, oidc("google", "https://issuer", "client", "system:", null)));
        assertInvalid(() -> service.associate(cluster, oidc("google", "https://issuer", "client", null, "system:x")));
        assertInvalid(() -> service.associate(cluster, new AssociateIdentityProviderConfigRequest(
                new OidcIdentityProviderConfigRequest("google", "https://issuer", "client", null, null, null, null,
                        Map.of("", "value")), null, null)));
        assertInvalid(() -> service.associate(cluster, new AssociateIdentityProviderConfigRequest(
                new OidcIdentityProviderConfigRequest("google", "https://issuer", "client", null, null, null, null, null),
                Map.of("k".repeat(129), "v"), null)));
        assertInvalid(() -> service.describe(cluster, new IdentityProviderConfig("saml", "google")));
        assertInvalid(() -> service.describe(cluster, new IdentityProviderConfig("oidc", " ")));
        assertInvalid(() -> service.disassociate(cluster, null));

        Cluster creating = cluster("creating");
        creating.setStatus(ClusterStatus.CREATING);
        AwsException inactive = assertThrows(AwsException.class,
                () -> service.associate(creating, request("google", null, null)));
        assertEquals("InvalidRequestException", inactive.getErrorCode());
        assertTrue(configs.keys().isEmpty());
        assertTrue(updates.keys().isEmpty());
    }

    @Test
    void deletingTheClusterDropsConfigsAndUpdatesAndARecreatedClusterStartsEmpty() {
        service.associate(cluster, request("google", null, null));
        Cluster recreated = cluster("prod");
        recreated.setCreatedAt(cluster.getCreatedAt().plusSeconds(60));
        assertTrue(service.list(recreated, null, null).identityProviderConfigs().isEmpty());
        assertTrue(service.listUpdates(recreated).isEmpty());

        service.deleteClusterConfigs(cluster);

        assertTrue(configs.keys().isEmpty());
        assertTrue(updates.keys().isEmpty());
        assertNotFound(() -> service.describe(cluster, GOOGLE));
    }

    private static AssociateIdentityProviderConfigRequest request(String name, Map<String, String> tags, String token) {
        return new AssociateIdentityProviderConfigRequest(new OidcIdentityProviderConfigRequest(name,
                "https://accounts.google.com", "example-client", "email", "google:", "groups", "gid:",
                Map.of("hd", "example.com")), tags, token);
    }

    private static AssociateIdentityProviderConfigRequest oidc(String name, String issuer, String clientId,
                                                               String usernamePrefix, String groupsPrefix) {
        return new AssociateIdentityProviderConfigRequest(new OidcIdentityProviderConfigRequest(name, issuer, clientId,
                null, usernamePrefix, null, groupsPrefix, null), null, null);
    }

    private static Cluster cluster(String name) {
        Cluster cluster = new Cluster();
        cluster.setName(name);
        cluster.setArn("arn:aws:eks:us-east-1:123456789012:cluster/" + name);
        cluster.setCreatedAt(Instant.parse("2026-10-20T10:00:00Z"));
        cluster.setStatus(ClusterStatus.ACTIVE);
        return cluster;
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
}
