package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.services.eks.EksAccessEntryService.StoredEntry;
import io.github.hectorvent.floci.services.eks.model.AssociatedAccessPolicy;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Floci-internal SubjectAccessReview webhook that k3s consults after Node and RBAC to apply EKS access policies. */
@ApplicationScoped
@Path("_floci/eks/clusters/{clusterName}/authorization-webhook")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EksAuthorizationWebhookController {

    static final Map<String, Object> NO_OPINION = Map.of("allowed", false, "denied", false);
    private final EksService eks;
    private final EksAccessEntryService entries;

    @Inject
    public EksAuthorizationWebhookController(EksService eks, EksAccessEntryService entries) {
        this.eks = eks;
        this.entries = entries;
    }

    @POST
    @Path("scope/{accountId}/{region}/{createdAt}")
    public Response review(@PathParam("clusterName") String clusterName,
                           @PathParam("accountId") String accountId,
                           @PathParam("region") String region,
                           @PathParam("createdAt") String createdAt,
                           Map<String, Object> review) {
        String apiVersion = review != null && review.get("apiVersion") instanceof String v
                ? v : "authorization.k8s.io/v1";
        Map<String, Object> status = review != null && review.get("spec") instanceof Map<?, ?> spec
                ? decide(clusterName, accountId, region, createdAt, spec) : NO_OPINION;
        return Response.ok(Map.of("apiVersion", apiVersion, "kind", "SubjectAccessReview", "status", status))
                .build();
    }

    Map<String, Object> decide(String clusterName, String accountId, String region, String createdAt,
                               Map<?, ?> spec) {
        String principalId = extra(spec, "principalId");
        String canonicalArn = extra(spec, "canonicalArn");
        Optional<EksAccessPolicies.Request> request = request(spec);
        if (canonicalArn == null || request.isEmpty()) {
            return NO_OPINION;
        }
        Optional<Cluster> cluster = eks.findWebhookCluster(clusterName, accountId, region, createdAt)
                .filter(found -> !"CONFIG_MAP".equals(EksService.authenticationMode(found)));
        if (cluster.isEmpty()) {
            return NO_OPINION;
        }
        Optional<StoredEntry> entry = canonicalArn.endsWith(":root")
                ? entries.principalEntry(cluster.get(), canonicalArn, null)
                : principalId == null ? Optional.empty()
                : entries.standardEntryById(cluster.get(), principalId.split(":", 2)[0]);
        Optional<StoredEntry> principalsEntry = entry
                .filter(stored -> "STANDARD".equals(stored.entry().type()))
                .filter(stored -> EksIamAuthentication.canonicalArn(stored.entry().principalArn())
                        .equalsIgnoreCase(canonicalArn));
        if (principalsEntry.isEmpty()) {
            return NO_OPINION;
        }
        for (AssociatedAccessPolicy association : principalsEntry.get().associations()) {
            String policy = EksAccessEntryService.accessPolicyName(association.policyArn());
            if (EksAccessPolicies.allows(policy, association.accessScope(), request.get())) {
                return Map.of("allowed", true, "reason", "allowed by EKS access policy " + policy);
            }
        }
        return NO_OPINION;
    }

    private static Optional<EksAccessPolicies.Request> request(Map<?, ?> spec) {
        if (spec.get("resourceAttributes") instanceof Map<?, ?> resource) {
            return Optional.of(new EksAccessPolicies.Request(text(resource, "namespace"), text(resource, "verb"),
                    text(resource, "group"), text(resource, "resource"), text(resource, "subresource"), null));
        }
        if (spec.get("nonResourceAttributes") instanceof Map<?, ?> nonResource
                && nonResource.get("path") instanceof String path) {
            return Optional.of(new EksAccessPolicies.Request("", text(nonResource, "verb"), "", "", "", path));
        }
        return Optional.empty();
    }

    private static String text(Map<?, ?> attributes, String key) {
        return attributes.get(key) instanceof String value ? value : "";
    }

    private static String extra(Map<?, ?> spec, String key) {
        if (!(spec.get("extra") instanceof Map<?, ?> extra)) {
            return null;
        }
        for (Map.Entry<?, ?> value : extra.entrySet()) {
            if (value.getKey() instanceof String name && name.equalsIgnoreCase(key)
                    && value.getValue() instanceof List<?> values && !values.isEmpty()
                    && values.getFirst() instanceof String first && !first.isEmpty()) {
                return first;
            }
        }
        return null;
    }
}
