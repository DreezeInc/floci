package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.eks.model.AccessConfig;
import io.github.hectorvent.floci.services.eks.model.AccessScope;
import io.github.hectorvent.floci.services.eks.model.AssociateAccessPolicyRequest;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ClusterStatus;
import io.github.hectorvent.floci.services.eks.model.CreateAccessEntryRequest;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.IamService.EksPrincipal;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.iam.model.IamUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EksIamAuthenticationTest {
    private static final String ACCOUNT = "111111111111";
    private static final String OTHER_ACCOUNT = "222222222222";
    private static final String ROLE = "arn:aws:iam::" + ACCOUNT + ":role/team/reader-role";
    private static final String CANONICAL_ROLE = "arn:aws:iam::" + ACCOUNT + ":role/reader-role";
    private static final String USER = "arn:aws:iam::" + ACCOUNT + ":user/writer";
    private static final String KEY = "ASIAREADERKEY";
    private static final EksPrincipal READER = rolePrincipal(ACCOUNT, "AROAREADER", "alice@corp");
    private static final EksPrincipal WRITER = new EksPrincipal(USER, USER, "AIDAWRITER", "AIDAWRITER", null, ACCOUNT);

    private final IamService iam = mock(IamService.class);
    private final EksClusterManager clusters = mock(EksClusterManager.class);
    private final EksAccessEntryService entries = new EksAccessEntryService(new InMemoryStorage<>(), iam);
    private final EksIamAuthentication auth = new EksIamAuthentication(iam, mock(EksService.class), entries,
            clusters, mock(EksWorkerAuthentication.class), mock(EmulatorConfig.class));
    private final Cluster cluster = new Cluster();

    @BeforeEach
    void setup() {
        cluster.setName("demo-cluster");
        cluster.setArn("arn:aws:eks:us-east-1:" + ACCOUNT + ":cluster/demo-cluster");
        cluster.setCreatedAt(Instant.parse("2026-09-17T00:00:00Z"));
        cluster.setStatus(ClusterStatus.ACTIVE);
        mode("API");
        when(iam.findRole(ACCOUNT, "reader-role")).thenReturn(Optional.of(
                new IamRole("AROAREADER", "reader-role", "/team/", ROLE, "{}")));
        when(iam.findUser(ACCOUNT, "writer")).thenReturn(Optional.of(new IamUser("AIDAWRITER", "writer", "/", USER)));
        awsAuth("", "");
    }

    @Test
    void apiModeEntryAuthenticatesWithItsUsernameGroupsAndPrincipalExtras() {
        entry(ROLE, "reader:{{SessionName}}", List.of("readers"));

        Map<String, Object> user = authenticate(READER).orElseThrow();

        assertEquals("reader:alice-corp", user.get("username"));
        assertEquals(List.of("readers"), user.get("groups"));
        assertEquals("aws-iam-authenticator:" + ACCOUNT + ":AROAREADER:alice@corp", user.get("uid"));
        assertEquals(Map.of(
                "accessKeyId", List.of(KEY),
                "arn", List.of("arn:aws:sts::" + ACCOUNT + ":assumed-role/reader-role/alice@corp"),
                "canonicalArn", List.of(CANONICAL_ROLE),
                "sessionName", List.of("alice@corp"),
                "principalId", List.of("AROAREADER:alice@corp")), user.get("extra"));
    }

    @Test
    void entryWithoutUsernameGetsTheEksDefaultForItsPrincipalType() {
        entry(ROLE, null, null);
        entry(USER, null, null);

        assertEquals("arn:aws:sts::" + ACCOUNT + ":assumed-role/reader-role/alice-corp",
                authenticate(READER).orElseThrow().get("username"));
        assertEquals(USER, authenticate(WRITER).orElseThrow().get("username"));
        assertEquals(List.of(), authenticate(WRITER).orElseThrow().get("groups"));
    }

    @Test
    void principalWithoutAnEntryIsRejectedInApiModeWithoutReadingAwsAuth() {
        awsAuth(mapRole(CANONICAL_ROLE, "from-config-map"), "");

        assertTrue(authenticate(READER).isEmpty());
        verify(clusters, never()).readAwsAuth(any());
    }

    @Test
    void principalOfAnotherAccountNeverMatchesAnEntryOrAMapping() {
        entry(ROLE, "reader", List.of());
        awsAuth(mapRole(CANONICAL_ROLE, "reader"), "");
        EksPrincipal sameNameElsewhere = rolePrincipal(OTHER_ACCOUNT, "AROAREADER", "alice");

        for (String mode : List.of("API", "API_AND_CONFIG_MAP")) {
            mode(mode);
            assertTrue(authenticate(sameNameElsewhere).isEmpty(), mode);
        }
        mode("CONFIG_MAP");
        assertTrue(authenticate(sameNameElsewhere).isEmpty());
    }

    @Test
    void entryOfARecreatedPrincipalNoLongerMatches() {
        entry(ROLE, "reader", List.of());
        EksPrincipal recreated = rolePrincipal(ACCOUNT, "AROARECREATED", "alice");

        assertTrue(authenticate(recreated).isEmpty());
    }

    @Test
    void awsAuthRoleMappingMatchesTheRoleWithoutItsPath() {
        mode("CONFIG_MAP");
        awsAuth("""
                - rolearn: %s
                  username: reader:{{SessionName}}
                  groups:
                    - readers
                    - team-{{AccountID}}
                """.formatted(CANONICAL_ROLE.replace("reader-role", "Reader-Role")), "");

        Map<String, Object> user = authenticate(READER).orElseThrow();

        assertEquals("reader:alice-corp", user.get("username"));
        assertEquals(List.of("readers", "team-" + ACCOUNT), user.get("groups"));
    }

    @Test
    void awsAuthRoleArnWrittenWithAPathNeverMatches() {
        mode("CONFIG_MAP");
        awsAuth(mapRole(ROLE, "reader"), "");

        assertTrue(authenticate(READER).isEmpty());
    }

    @Test
    void awsAuthUserMappingAuthenticatesTheUser() {
        mode("API_AND_CONFIG_MAP");
        awsAuth("", """
                - userarn: %s
                  username: writer
                  groups: [writers]
                """.formatted(USER));

        Map<String, Object> user = authenticate(WRITER).orElseThrow();

        assertEquals("writer", user.get("username"));
        assertEquals(List.of("writers"), user.get("groups"));
        assertTrue(authenticate(READER).isEmpty());
    }

    @Test
    void accessEntryTakesPrecedenceOverAwsAuthForTheSamePrincipal() {
        mode("API_AND_CONFIG_MAP");
        entry(ROLE, "from-entry", List.of("entry-group"));
        awsAuth(mapRole(CANONICAL_ROLE, "from-config-map"), "");

        assertEquals("from-entry", authenticate(READER).orElseThrow().get("username"));
        verify(clusters, never()).readAwsAuth(any());

        entries.delete(cluster, ROLE);
        assertEquals("from-config-map", authenticate(READER).orElseThrow().get("username"));
    }

    @Test
    void nodeEntryOfTheSamePrincipalStillTakesPrecedenceOverAwsAuth() {
        mode("API_AND_CONFIG_MAP");
        entries.create(cluster, new CreateAccessEntryRequest(ROLE, "EC2_LINUX", null, null, null, null));
        awsAuth(mapRole(CANONICAL_ROLE, "from-config-map"), "");

        assertTrue(authenticate(READER).isEmpty());
    }

    @Test
    void eachAuthenticationModeConsultsOnlyItsSources() {
        entry(ROLE, "from-entry", List.of());
        awsAuth("", """
                - userarn: %s
                  username: from-config-map
                """.formatted(USER));
        Map<String, List<Optional<String>>> expected = Map.of(
                "API", List.of(Optional.of("from-entry"), Optional.empty()),
                "API_AND_CONFIG_MAP", List.of(Optional.of("from-entry"), Optional.of("from-config-map")),
                "CONFIG_MAP", List.of(Optional.empty(), Optional.of("from-config-map")));

        expected.forEach((mode, outcomes) -> {
            mode(mode);
            assertEquals(outcomes.get(0), authenticate(READER).map(user -> (String) user.get("username")), mode);
            assertEquals(outcomes.get(1), authenticate(WRITER).map(user -> (String) user.get("username")), mode);
        });
    }

    @Test
    void creatorInConfigMapModeIsAnInvisibleAdministrator() {
        mode("CONFIG_MAP");
        cluster.setCreatorPrincipalArn(ROLE);

        Map<String, Object> user = authenticate(READER).orElseThrow();

        assertEquals("kubernetes-admin", user.get("username"));
        assertEquals(List.of("system:masters"), user.get("groups"));
        assertTrue(authenticate(WRITER).isEmpty());
    }

    @Test
    void creatorWithoutBootstrapPermissionsGetsNothing() {
        mode("CONFIG_MAP");
        cluster.setAccessConfig(new AccessConfig("CONFIG_MAP", false));

        assertTrue(authenticate(READER).isEmpty());
    }

    @Test
    void creatorEntryInApiModesCarriesClusterAdminAsSystemMasters() {
        for (String mode : List.of("API", "API_AND_CONFIG_MAP")) {
            mode(mode);
            entries.bootstrapCreator(cluster, ROLE);

            Map<String, Object> user = authenticate(READER).orElseThrow();

            assertEquals("arn:aws:sts::" + ACCOUNT + ":assumed-role/reader-role/alice-corp", user.get("username"));
            assertEquals(List.of("system:masters"), user.get("groups"));
            assertEquals(List.of(ROLE), entries.list(cluster, null, null).accessEntries());
            entries.delete(cluster, ROLE);
            assertTrue(authenticate(READER).isEmpty(), mode);
        }
    }

    @Test
    void namespaceScopedClusterAdminIsNotSystemMasters() {
        entry(ROLE, "reader", List.of());
        entries.associatePolicy(cluster, ROLE, new AssociateAccessPolicyRequest(
                "arn:aws:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy",
                new AccessScope("namespace", List.of("dev"))));

        assertEquals(List.of(), authenticate(READER).orElseThrow().get("groups"));
    }

    @Test
    void usernameTemplatesResolveFromThePrincipal() {
        entry(ROLE, "a:{{AccountID}}:{{SessionName}}:{{SessionNameRaw}}:{{AccessKeyID}}", List.of());

        assertEquals("a:" + ACCOUNT + ":alice-corp:alice@corp:" + KEY, authenticate(READER).orElseThrow().get("username"));

        mode("CONFIG_MAP");
        awsAuth(mapRole(CANONICAL_ROLE, "system:node:{{EC2PrivateDNSName}}"), "");
        assertTrue(authenticate(READER).isEmpty());
        awsAuth("", """
                - userarn: %s
                  username: writer-{{SessionName}}
                """.formatted(USER));
        assertEquals("writer-", authenticate(WRITER).orElseThrow().get("username"));
    }

    private Optional<Map<String, Object>> authenticate(EksPrincipal principal) {
        return auth.authenticate(cluster, principal, KEY);
    }

    private void mode(String mode) {
        cluster.setAccessConfig(new AccessConfig(mode, true));
    }

    private void entry(String principal, String username, List<String> groups) {
        entries.create(cluster, new CreateAccessEntryRequest(principal, null, username, groups, null, null));
    }

    private void awsAuth(String mapRoles, String mapUsers) {
        try {
            when(clusters.readAwsAuth(cluster)).thenReturn(EksAwsAuth.parse(mapRoles, mapUsers));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String mapRole(String arn, String username) {
        return """
                - rolearn: %s
                  username: %s
                """.formatted(arn, username);
    }

    private static EksPrincipal rolePrincipal(String account, String roleId, String session) {
        return new EksPrincipal("arn:aws:iam::" + account + ":role/team/reader-role",
                "arn:aws:sts::" + account + ":assumed-role/reader-role/" + session, roleId,
                roleId + ":" + session, session, account);
    }
}
