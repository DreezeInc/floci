package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.services.eks.EksAccessEntryService.StoredEntry;
import io.github.hectorvent.floci.services.eks.model.AssociatedAccessPolicy;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.IamService.EksPrincipal;
import io.github.hectorvent.floci.services.iam.IamService.EksSessionIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Maps a verified token to a Kubernetes identity by access entry or aws-auth, as the cluster's mode allows. */
@ApplicationScoped
class EksIamAuthentication {
    static final String ADMIN_GROUP = "system:masters";
    static final String CONFIG_MAP_CREATOR_USERNAME = "kubernetes-admin";
    private static final String EC2_PRIVATE_DNS_NAME = "{{EC2PrivateDNSName}}";

    private final IamService iam;
    private final EksService eks;
    private final EksAccessEntryService entries;
    private final EksClusterManager clusters;
    private final EksWorkerAuthentication workers;
    private final EmulatorConfig config;

    @Inject
    EksIamAuthentication(IamService iam, EksService eks, EksAccessEntryService entries, EksClusterManager clusters,
                         EksWorkerAuthentication workers, EmulatorConfig config) {
        this.iam = iam;
        this.eks = eks;
        this.entries = entries;
        this.clusters = clusters;
        this.workers = workers;
        this.config = config;
    }

    Optional<Map<String, Object>> authenticate(EksTokenValidator.VerifiedToken token, String name,
                                               String account, String region, String createdAt) {
        Optional<EksSessionIdentity> session = iam.findEksSessionIdentity(token.accessKeyId());
        if (session.isPresent() && session.get().instanceId() != null) {
            return workers.authenticate(token, session.get(), name, account, region, createdAt);
        }
        // Unscoped webhook paths predate per-account clusters, so they serve the default account.
        Optional<Cluster> cluster = account == null && region == null && createdAt == null
                ? eks.findAuthenticationCluster(config.defaultAccountId(), name)
                : eks.findWebhookCluster(name, account, region, createdAt);
        Optional<EksPrincipal> principal = iam.findEksPrincipal(token.accessKeyId(), token.keyAccountId());
        if (cluster.isEmpty() || principal.isEmpty()) {
            return Optional.empty();
        }
        return authenticate(cluster.get(), principal.get(), token.accessKeyId());
    }

    Optional<Map<String, Object>> authenticate(Cluster cluster, EksPrincipal principal, String accessKeyId) {
        String mode = EksService.authenticationMode(cluster);
        if (!"CONFIG_MAP".equals(mode)) {
            Optional<StoredEntry> entry = entries.principalEntry(cluster, principal.principalArn(),
                    principal.principalId());
            if (entry.isPresent()) {
                return entryIdentity(cluster, entry.get(), principal, accessKeyId);
            }
        } else if (principal.principalArn().equals(cluster.getCreatorPrincipalArn())) {
            return Optional.of(identity(CONFIG_MAP_CREATOR_USERNAME, List.of(ADMIN_GROUP), principal, accessKeyId));
        }
        if ("API".equals(mode)) {
            return Optional.empty();
        }
        return clusters.readAwsAuth(cluster).find(canonicalArn(principal)).flatMap(mapping -> {
            Optional<String> username = render(mapping.username(), principal, accessKeyId);
            List<String> groups = new ArrayList<>();
            for (String group : mapping.groups()) {
                Optional<String> rendered = render(group, principal, accessKeyId);
                if (rendered.isEmpty()) {
                    return Optional.empty();
                }
                groups.add(rendered.get());
            }
            return username.map(value -> identity(value, groups, principal, accessKeyId));
        });
    }

    private Optional<Map<String, Object>> entryIdentity(Cluster cluster, StoredEntry stored, EksPrincipal principal,
                                                        String accessKeyId) {
        if (!"STANDARD".equals(stored.entry().type())) {
            return Optional.empty();
        }
        List<String> groups = new ArrayList<>(stored.entry().kubernetesGroups());
        if (stored.associations().stream().anyMatch(EksIamAuthentication::isClusterWideAdmin)
                && !groups.contains(ADMIN_GROUP)) {
            groups.add(ADMIN_GROUP);
        }
        return render(stored.entry().username(), principal, accessKeyId)
                .map(username -> identity(username, groups, principal, accessKeyId));
    }

    private static boolean isClusterWideAdmin(AssociatedAccessPolicy association) {
        return EksAccessEntryService.CLUSTER_ADMIN_POLICY.equals(
                EksAccessEntryService.accessPolicyName(association.policyArn()))
                && association.accessScope() != null && "cluster".equals(association.accessScope().type());
    }

    /** The aws-iam-authenticator placeholders; an EC2 DNS name exists only for an instance session. */
    static Optional<String> render(String template, EksPrincipal principal, String accessKeyId) {
        if (template == null || template.contains(EC2_PRIVATE_DNS_NAME)) {
            return Optional.empty();
        }
        String sessionName = principal.sessionName() == null ? "" : principal.sessionName();
        String rendered = template.replace("{{AccountID}}", principal.accountId())
                .replace("{{SessionName}}", sessionName.replace('@', '-'))
                .replace("{{SessionNameRaw}}", sessionName)
                .replace("{{AccessKeyID}}", accessKeyId);
        return rendered.isBlank() ? Optional.empty() : Optional.of(rendered);
    }

    static String canonicalArn(EksPrincipal principal) {
        return canonicalArn(principal.principalArn());
    }

    /** A role's ARN without its path, the form aws-auth and the authenticator compare. */
    static String canonicalArn(String principalArn) {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(principalArn);
        if (!"iam".equals(arn.service()) || !arn.resource().startsWith("role/")) {
            return principalArn;
        }
        String name = arn.resource().substring(arn.resource().lastIndexOf('/') + 1);
        return AwsArnUtils.Arn.global(arn.partition(), "iam", arn.accountId(), "role/" + name).toString();
    }

    private static Map<String, Object> identity(String username, List<String> groups, EksPrincipal principal,
                                                String accessKeyId) {
        Map<String, List<String>> extra = new LinkedHashMap<>();
        extra.put("accessKeyId", List.of(accessKeyId));
        extra.put("arn", List.of(principal.callerArn() == null ? principal.principalArn() : principal.callerArn()));
        extra.put("canonicalArn", List.of(canonicalArn(principal)));
        extra.put("sessionName", List.of(principal.sessionName() == null ? "" : principal.sessionName()));
        if (principal.userId() != null) {
            extra.put("principalId", List.of(principal.userId()));
        }
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("username", username);
        user.put("uid", "aws-iam-authenticator:" + principal.accountId() + ":"
                + (principal.userId() == null ? principal.principalArn() : principal.userId()));
        user.put("groups", List.copyOf(groups));
        user.put("extra", extra);
        return user;
    }
}
