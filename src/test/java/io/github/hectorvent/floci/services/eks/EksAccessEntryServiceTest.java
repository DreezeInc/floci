package io.github.hectorvent.floci.services.eks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.eks.model.AccessConfig;
import io.github.hectorvent.floci.services.eks.model.AccessEntry;
import io.github.hectorvent.floci.services.eks.model.AccessPolicy;
import io.github.hectorvent.floci.services.eks.model.AccessScope;
import io.github.hectorvent.floci.services.eks.model.AssociateAccessPolicyRequest;
import io.github.hectorvent.floci.services.eks.model.AssociatedAccessPolicy;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ClusterStatus;
import io.github.hectorvent.floci.services.eks.model.CreateAccessEntryRequest;
import io.github.hectorvent.floci.services.eks.model.UpdateAccessEntryRequest;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.iam.model.IamUser;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EksAccessEntryServiceTest {
    private static final String PRINCIPAL = "arn:aws:iam::123456789012:role/path/worker";
    private static final String NODE = "arn:aws:iam::123456789012:role/node";
    private static final String VIEW = "arn:aws:eks::aws:cluster-access-policy/AmazonEKSViewPolicy";
    private static final String ADMIN = "arn:aws:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy";

    @Test
    void nodeEntryUsesGeneratedIdentityAndCapturesStablePrincipalId() throws Exception {
        Fixture fixture = fixture();
        AccessEntry entry = fixture.service.create(fixture.cluster, request(PRINCIPAL, "EC2_LINUX", "retry"));
        assertEquals("system:node:{{EC2PrivateDNSName}}", entry.username());
        assertEquals(List.of("system:nodes"), entry.kubernetesGroups());
        assertEquals(Map.of("team", "platform"), entry.tags());
        assertTrue(entry.createdAt() > 0);
        EksAccessEntryService.StoredEntry stored = fixture.storage.scan(key -> true).getFirst();
        assertEquals("AROA-worker", stored.principalId());
        ObjectMapper mapper = new ObjectMapper();
        EksAccessEntryService.StoredEntry restored = mapper.readValue(mapper.writeValueAsBytes(stored),
                EksAccessEntryService.StoredEntry.class);
        assertEquals(stored, restored);
        assertFalse(mapper.writeValueAsString(entry).contains("principalId"));
        fixture.role.setRoleId("AROA-recreated");
        assertEquals("AROA-worker", fixture.storage.scan(key -> true).getFirst().principalId());
        assertEquals(entry, fixture.service.describe(fixture.cluster, PRINCIPAL));
    }

    @Test
    void retriesAreIdempotentButConflictingRequestsAndDuplicatesFail() {
        Fixture fixture = fixture();
        CreateAccessEntryRequest request = request(PRINCIPAL, "EC2_LINUX", "retry");
        AccessEntry first = fixture.service.create(fixture.cluster, request);
        assertEquals(first, fixture.service.create(fixture.cluster, request));
        assertEquals("InvalidParameterException", assertThrows(AwsException.class, () -> fixture.service.create(
                fixture.cluster, request(PRINCIPAL, "STANDARD", "retry"))).getErrorCode());
        assertEquals(409, assertThrows(AwsException.class, () -> fixture.service.create(
                fixture.cluster, request(PRINCIPAL, "EC2_LINUX", "other"))).getHttpStatus());
    }

    @Test
    void standardRoleDefaultsStripTheIamPath() {
        Fixture fixture = fixture();
        AccessEntry entry = fixture.service.create(fixture.cluster, request(PRINCIPAL, null, null));
        assertEquals("STANDARD", entry.type());
        assertEquals("arn:aws:sts::123456789012:assumed-role/worker/{{SessionName}}", entry.username());
    }

    @Test
    void standardUserCanBelongToAnotherAccount() {
        Fixture fixture = fixture();
        String principal = "arn:aws:iam::999999999999:user/team/reader";
        when(fixture.iam.findUser("999999999999", "reader")).thenReturn(Optional.of(
                new IamUser("AIDA-reader", "reader", "/team/", principal)));
        AccessEntry entry = fixture.service.create(fixture.cluster,
                new CreateAccessEntryRequest(principal, null, null, List.of("readers"), null, null));
        assertEquals(principal, entry.username());
        assertEquals(List.of("readers"), entry.kubernetesGroups());
        assertEquals("AIDA-reader", fixture.storage.scan(key -> true).getFirst().principalId());
    }

    @Test
    void nodeOverridesForeignAccountsAndMissingPrincipalsAreRejected() {
        Fixture fixture = fixture();
        assertThrows(AwsException.class, () -> fixture.service.create(fixture.cluster,
                new CreateAccessEntryRequest(PRINCIPAL, "EC2_LINUX", "custom", null, null, null)));
        assertThrows(AwsException.class, () -> fixture.service.create(fixture.cluster,
                new CreateAccessEntryRequest(PRINCIPAL, "EC2_LINUX", null, List.of(), null, null)));
        assertThrows(AwsException.class, () -> fixture.service.create(fixture.cluster,
                request(PRINCIPAL.replace("123456789012", "999999999999"), "EC2_LINUX", null)));
        when(fixture.iam.findRole("123456789012", "worker")).thenReturn(Optional.empty());
        assertThrows(AwsException.class, () -> fixture.service.create(fixture.cluster,
                request(PRINCIPAL, "STANDARD", null)));
    }

    @Test
    void paginationTokensCannotCrossClusterOrAccountBoundaries() {
        Fixture fixture = fixture();
        fixture.service.create(fixture.cluster, request(PRINCIPAL, "STANDARD", null));
        String second = PRINCIPAL.replace("worker", "worker2");
        when(fixture.iam.findRole("123456789012", "worker2")).thenReturn(Optional.of(
                new IamRole("AROA-second", "worker2", "/path/", second, "{}")));
        fixture.service.create(fixture.cluster, request(second, "STANDARD", null));
        EksAccessEntryService.Page first = fixture.service.list(fixture.cluster, 1, null);
        assertEquals(List.of(PRINCIPAL), first.accessEntries());
        assertNotNull(first.nextToken());
        EksAccessEntryService.Page next = fixture.service.list(fixture.cluster, 1, first.nextToken());
        assertEquals(List.of(second), next.accessEntries());
        assertNull(next.nextToken());
        assertThrows(AwsException.class, () -> fixture.service.list(fixture.cluster, 0, null));
        assertThrows(AwsException.class, () -> fixture.service.list(fixture.cluster, 101, null));
        assertThrows(AwsException.class, () -> fixture.service.list(fixture.cluster, 1, "invalid"));
        fixture.cluster.setArn(fixture.cluster.getArn().replace("123456789012", "999999999999"));
        assertTrue(fixture.service.list(fixture.cluster, 100, null).accessEntries().isEmpty());
        assertThrows(AwsException.class, () -> fixture.service.list(fixture.cluster, 1, first.nextToken()));
    }

    @Test
    void clusterDeletionCleansEntriesAndRecreationCannotInheritThem() {
        Fixture fixture = fixture();
        fixture.service.create(fixture.cluster, request(PRINCIPAL, "EC2_LINUX", null));
        fixture.service.deleteClusterEntries(fixture.cluster);
        assertTrue(fixture.storage.scan(key -> true).isEmpty());
        fixture.service.create(fixture.cluster, request(PRINCIPAL, "EC2_LINUX", null));
        fixture.cluster.setCreatedAt(fixture.cluster.getCreatedAt().plusSeconds(1));
        assertTrue(fixture.service.list(fixture.cluster, null, null).accessEntries().isEmpty());
        assertEquals(404, assertThrows(AwsException.class,
                () -> fixture.service.describe(fixture.cluster, PRINCIPAL)).getHttpStatus());
    }

    @Test
    void configMapAndInactiveClustersRejectAccessEntryOperations() {
        Fixture fixture = fixture();
        fixture.cluster.setAccessConfig(new AccessConfig("CONFIG_MAP", true));
        assertEquals("InvalidRequestException", assertThrows(AwsException.class,
                () -> fixture.service.list(fixture.cluster, null, null)).getErrorCode());
        fixture.cluster.setAccessConfig(new AccessConfig("API", false));
        fixture.cluster.setStatus(ClusterStatus.CREATING);
        assertThrows(AwsException.class, () -> fixture.service.create(fixture.cluster,
                request(PRINCIPAL, "EC2_LINUX", null)));
    }

    @Test
    void reassociatingReplacesTheScopeAndKeepsTheAssociationTime() {
        Fixture fixture = fixture();
        fixture.service.create(fixture.cluster, request(PRINCIPAL, null, null));
        AssociatedAccessPolicy first = fixture.service.associatePolicy(fixture.cluster, PRINCIPAL,
                association(VIEW, "namespace", List.of("dev-*", "team")));
        assertEquals(new AccessScope("namespace", List.of("dev-*", "team")), first.accessScope());
        assertTrue(first.associatedAt() > 0);
        assertEquals(first.associatedAt(), first.modifiedAt());
        AssociatedAccessPolicy second = fixture.service.associatePolicy(fixture.cluster, PRINCIPAL,
                association(VIEW, "cluster", null));
        assertEquals(new AccessScope("cluster", List.of()), second.accessScope());
        assertEquals(first.associatedAt(), second.associatedAt());
        assertTrue(second.modifiedAt() >= first.modifiedAt());
        assertEquals(List.of(second),
                fixture.service.listAssociatedPolicies(fixture.cluster, PRINCIPAL, null, null).items());
    }

    @Test
    void associationsPageByPolicyArnAndDisassociateRemovesOnlyTheNamedPolicy() {
        Fixture fixture = fixture();
        fixture.service.create(fixture.cluster, request(PRINCIPAL, null, null));
        fixture.service.associatePolicy(fixture.cluster, PRINCIPAL, association(VIEW, "cluster", List.of()));
        fixture.service.associatePolicy(fixture.cluster, PRINCIPAL, association(ADMIN, "cluster", List.of()));
        PaginatedResult<AssociatedAccessPolicy> first =
                fixture.service.listAssociatedPolicies(fixture.cluster, PRINCIPAL, 1, null);
        assertEquals(List.of(ADMIN), first.items().stream().map(AssociatedAccessPolicy::policyArn).toList());
        PaginatedResult<AssociatedAccessPolicy> second =
                fixture.service.listAssociatedPolicies(fixture.cluster, PRINCIPAL, 1, first.nextToken());
        assertEquals(List.of(VIEW), second.items().stream().map(AssociatedAccessPolicy::policyArn).toList());
        assertNull(second.nextToken());
        assertEquals("InvalidParameterException", assertThrows(AwsException.class,
                () -> fixture.service.listAssociatedPolicies(fixture.cluster, PRINCIPAL, 101, null)).getErrorCode());

        fixture.service.disassociatePolicy(fixture.cluster, PRINCIPAL, ADMIN);
        assertEquals(List.of(VIEW), fixture.service.listAssociatedPolicies(fixture.cluster, PRINCIPAL, null, null)
                .items().stream().map(AssociatedAccessPolicy::policyArn).toList());
        assertEquals(404, assertThrows(AwsException.class,
                () -> fixture.service.disassociatePolicy(fixture.cluster, PRINCIPAL, ADMIN)).getHttpStatus());
    }

    @Test
    void associationRejectsUnknownPoliciesInvalidScopesAndNodeEntries() {
        Fixture fixture = fixture();
        fixture.service.create(fixture.cluster, request(PRINCIPAL, null, null));
        for (AssociateAccessPolicyRequest invalid : List.of(
                association("arn:aws:eks::aws:cluster-access-policy/AmazonEKSUnknownPolicy", "cluster", null),
                association(VIEW.replace("arn:aws:", "arn:aws-cn:"), "cluster", null),
                association(null, "cluster", null),
                new AssociateAccessPolicyRequest(VIEW, null),
                association(VIEW, null, List.of("default")),
                association(VIEW, "account", null),
                association(VIEW, "namespace", null),
                association(VIEW, "namespace", List.of(" ")),
                association(VIEW, "cluster", List.of("default")))) {
            assertEquals("InvalidParameterException", assertThrows(AwsException.class,
                    () -> fixture.service.associatePolicy(fixture.cluster, PRINCIPAL, invalid)).getErrorCode(),
                    invalid.toString());
        }
        assertTrue(fixture.service.listAssociatedPolicies(fixture.cluster, PRINCIPAL, null, null).items().isEmpty());

        AwsException missing = assertThrows(AwsException.class, () -> fixture.service.associatePolicy(
                fixture.cluster, PRINCIPAL + "2", association(VIEW, "cluster", null)));
        assertEquals(404, missing.getHttpStatus());
        assertEquals("The specified principalArn could not be found.", missing.getMessage());
        assertEquals(404, assertThrows(AwsException.class, () -> fixture.service.listAssociatedPolicies(
                fixture.cluster, PRINCIPAL + "2", null, null)).getHttpStatus());

        when(fixture.iam.findRole("123456789012", "node")).thenReturn(Optional.of(
                new IamRole("AROA-node", "node", "/", NODE, "{}")));
        fixture.service.create(fixture.cluster, request(NODE, "EC2_LINUX", null));
        assertEquals("InvalidRequestException", assertThrows(AwsException.class, () -> fixture.service
                .associatePolicy(fixture.cluster, NODE, association(VIEW, "cluster", null))).getErrorCode());
    }

    @Test
    void deletingTheEntryOrTheClusterDropsItsAssociations() {
        Fixture fixture = fixture();
        fixture.service.create(fixture.cluster, request(PRINCIPAL, null, null));
        fixture.service.associatePolicy(fixture.cluster, PRINCIPAL, association(VIEW, "cluster", null));
        fixture.service.delete(fixture.cluster, PRINCIPAL);
        fixture.service.create(fixture.cluster, request(PRINCIPAL, null, null));
        assertTrue(fixture.service.listAssociatedPolicies(fixture.cluster, PRINCIPAL, null, null).items().isEmpty());

        fixture.service.associatePolicy(fixture.cluster, PRINCIPAL, association(VIEW, "cluster", null));
        fixture.service.deleteClusterEntries(fixture.cluster);
        fixture.service.create(fixture.cluster, request(PRINCIPAL, null, null));
        assertTrue(fixture.service.listAssociatedPolicies(fixture.cluster, PRINCIPAL, null, null).items().isEmpty());
    }

    @Test
    void entriesStoredWithoutAssociationsReadAsHavingNone() throws Exception {
        Fixture fixture = fixture();
        fixture.service.create(fixture.cluster, request(PRINCIPAL, null, null));
        String key = fixture.storage.keys().iterator().next();
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode legacy = mapper.valueToTree(fixture.storage.get(key).orElseThrow());
        legacy.remove("accessPolicies");
        fixture.storage.put(key, mapper.treeToValue(legacy, EksAccessEntryService.StoredEntry.class));
        assertTrue(fixture.service.listAssociatedPolicies(fixture.cluster, PRINCIPAL, null, null).items().isEmpty());
        fixture.service.associatePolicy(fixture.cluster, PRINCIPAL, association(VIEW, "cluster", null));
        assertEquals(1, fixture.service.listAssociatedPolicies(fixture.cluster, PRINCIPAL, null, null).items().size());
    }

    @Test
    void updateReplacesOnlyTheGivenFieldsAndKeepsAssociations() {
        Fixture fixture = fixture();
        AccessEntry created = fixture.service.create(fixture.cluster,
                new CreateAccessEntryRequest(PRINCIPAL, null, null, List.of("readers"), null, null));
        fixture.service.associatePolicy(fixture.cluster, PRINCIPAL, association(VIEW, "cluster", null));

        AccessEntry renamed = fixture.service.update(fixture.cluster, PRINCIPAL,
                new UpdateAccessEntryRequest(null, "token", "ops:{{SessionName}}"));
        assertEquals("ops:{{SessionName}}", renamed.username());
        assertEquals(List.of("readers"), renamed.kubernetesGroups());
        assertEquals(created.accessEntryArn(), renamed.accessEntryArn());
        assertEquals(created.createdAt(), renamed.createdAt());
        assertTrue(renamed.modifiedAt() >= created.modifiedAt());

        AccessEntry cleared = fixture.service.update(fixture.cluster, PRINCIPAL,
                new UpdateAccessEntryRequest(List.of(), null, null));
        assertEquals(List.of(), cleared.kubernetesGroups());
        assertEquals("ops:{{SessionName}}", cleared.username());
        assertEquals(cleared, fixture.service.describe(fixture.cluster, PRINCIPAL));
        assertEquals(1, fixture.service.listAssociatedPolicies(fixture.cluster, PRINCIPAL, null, null).items().size());

        for (UpdateAccessEntryRequest invalid : List.of(new UpdateAccessEntryRequest(null, null, "system:admin"),
                new UpdateAccessEntryRequest(null, null, "{{SessionName}}"),
                new UpdateAccessEntryRequest(List.of(""), null, null))) {
            assertEquals("InvalidParameterException", assertThrows(AwsException.class,
                    () -> fixture.service.update(fixture.cluster, PRINCIPAL, invalid)).getErrorCode());
        }
        assertEquals(404, assertThrows(AwsException.class, () -> fixture.service.update(fixture.cluster,
                PRINCIPAL + "2", new UpdateAccessEntryRequest(List.of("readers"), null, null))).getHttpStatus());
    }

    @Test
    void nodeEntriesRejectUsernameAndGroupUpdates() {
        Fixture fixture = fixture();
        fixture.service.create(fixture.cluster, request(PRINCIPAL, "EC2_LINUX", null));
        assertThrows(AwsException.class, () -> fixture.service.update(fixture.cluster, PRINCIPAL,
                new UpdateAccessEntryRequest(List.of("admins"), null, null)));
        assertThrows(AwsException.class, () -> fixture.service.update(fixture.cluster, PRINCIPAL,
                new UpdateAccessEntryRequest(null, null, "custom")));
        AccessEntry unchanged = fixture.service.update(fixture.cluster, PRINCIPAL, null);
        assertEquals("system:node:{{EC2PrivateDNSName}}", unchanged.username());
        assertEquals(List.of("system:nodes"), unchanged.kubernetesGroups());
    }

    @Test
    void accessPolicyCatalogUsesTheRequestedPartitionAndPages() {
        Fixture fixture = fixture();
        PaginatedResult<AccessPolicy> all = fixture.service.listAccessPolicies("aws", null, null);
        assertNull(all.nextToken());
        for (String name : List.of("AmazonEKSClusterAdminPolicy", "AmazonEKSAdminPolicy", "AmazonEKSEditPolicy",
                "AmazonEKSViewPolicy", "AmazonEKSAdminViewPolicy")) {
            assertTrue(all.items().contains(
                    new AccessPolicy(name, "arn:aws:eks::aws:cluster-access-policy/" + name)), name);
        }
        List<String> paged = new ArrayList<>();
        String token = null;
        do {
            PaginatedResult<AccessPolicy> page = fixture.service.listAccessPolicies("aws-cn", 5, token);
            assertTrue(page.items().size() <= 5);
            for (AccessPolicy policy : page.items()) {
                assertEquals("arn:aws-cn:eks::aws:cluster-access-policy/" + policy.name(), policy.arn());
                paged.add(policy.name());
            }
            token = page.nextToken();
        } while (token != null);
        assertEquals(all.items().stream().map(AccessPolicy::name).toList(), paged);
        assertThrows(AwsException.class, () -> fixture.service.listAccessPolicies("aws", 0, null));
    }

    private static AssociateAccessPolicyRequest association(String policyArn, String type, List<String> namespaces) {
        return new AssociateAccessPolicyRequest(policyArn, new AccessScope(type, namespaces));
    }

    private static CreateAccessEntryRequest request(String principal, String type, String token) {
        return new CreateAccessEntryRequest(principal, type, null, null, Map.of("team", "platform"), token);
    }

    private static Fixture fixture() {
        Cluster cluster = new Cluster();
        cluster.setName("nodes");
        cluster.setArn("arn:aws:eks:us-east-1:123456789012:cluster/nodes");
        cluster.setCreatedAt(Instant.parse("2026-09-16T10:00:00Z"));
        cluster.setStatus(ClusterStatus.ACTIVE);
        cluster.setAccessConfig(new AccessConfig("API", false));
        IamService iam = mock(IamService.class);
        IamRole role = new IamRole("AROA-worker", "worker", "/path/", PRINCIPAL, "{}");
        when(iam.findRole("123456789012", "worker")).thenReturn(Optional.of(role));
        InMemoryStorage<String, EksAccessEntryService.StoredEntry> storage = new InMemoryStorage<>();
        return new Fixture(cluster, iam, role, storage, new EksAccessEntryService(storage, iam));
    }

    private record Fixture(Cluster cluster, IamService iam, IamRole role,
                           InMemoryStorage<String, EksAccessEntryService.StoredEntry> storage,
                           EksAccessEntryService service) {}
}
