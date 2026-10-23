package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.services.eks.model.AccessScope;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The Kubernetes permissions of the EKS access policies, from "Review access policy permissions". */
final class EksAccessPolicies {

    /** One SubjectAccessReview request; {@code path} is set only for a non-resource request. */
    record Request(String namespace, String verb, String group, String resource, String subresource, String path) {
        boolean nonResource() {
            return path != null;
        }
    }

    private record Rule(List<String> groups, List<String> resources, List<String> verbs, List<String> urls) {
        boolean matches(Request request) {
            if (verbs.stream().noneMatch(verb -> verb.equals("*") || verb.equals(request.verb()))) {
                return false;
            }
            if (request.nonResource()) {
                return urls.stream().anyMatch(url -> url.equals(request.path())
                        || url.endsWith("*") && request.path().startsWith(url.substring(0, url.length() - 1)));
            }
            boolean subresource = request.subresource() != null && !request.subresource().isEmpty();
            String combined = subresource ? request.resource() + "/" + request.subresource() : request.resource();
            return groups.stream().anyMatch(group -> group.equals("*") || group.equals(request.group()))
                    && resources.stream().anyMatch(resource -> resource.equals("*") || resource.equals(combined)
                    || subresource && resource.equals("*/" + request.subresource()));
        }
    }

    private static final List<String> READ = List.of("get", "list", "watch");
    private static final List<String> WRITE = List.of("create", "delete", "deletecollection", "patch", "update");

    private static final List<Rule> VIEW = List.of(
            rule("apps", READ, "controllerrevisions", "daemonsets", "daemonsets/status", "deployments",
                    "deployments/scale", "deployments/status", "replicasets", "replicasets/scale",
                    "replicasets/status", "statefulsets", "statefulsets/scale", "statefulsets/status"),
            rule("autoscaling", READ, "horizontalpodautoscalers", "horizontalpodautoscalers/status"),
            rule("batch", READ, "cronjobs", "cronjobs/status", "jobs", "jobs/status"),
            rule("discovery.k8s.io", READ, "endpointslices"),
            rule("extensions", READ, "daemonsets", "daemonsets/status", "deployments", "deployments/scale",
                    "deployments/status", "ingresses", "ingresses/status", "networkpolicies", "replicasets",
                    "replicasets/scale", "replicasets/status", "replicationcontrollers/scale"),
            rule("networking.k8s.io", READ, "ingresses", "ingresses/status", "networkpolicies"),
            rule("policy", READ, "poddisruptionbudgets", "poddisruptionbudgets/status"),
            rule("", READ, "configmaps", "endpoints", "persistentvolumeclaims", "persistentvolumeclaims/status",
                    "pods", "replicationcontrollers", "replicationcontrollers/scale", "serviceaccounts", "services",
                    "services/status"),
            rule("", READ, "bindings", "events", "limitranges", "namespaces/status", "pods/log", "pods/status",
                    "replicationcontrollers/status", "resourcequotas", "resourcequotas/status"),
            rule("", READ, "namespaces"));

    private static final List<Rule> EDIT = concat(VIEW, List.of(
            rule("apps", WRITE, "daemonsets", "deployments", "deployments/rollback", "deployments/scale",
                    "replicasets", "replicasets/scale", "statefulsets", "statefulsets/scale"),
            rule("autoscaling", WRITE, "horizontalpodautoscalers"),
            rule("batch", WRITE, "cronjobs", "jobs"),
            rule("extensions", WRITE, "daemonsets", "deployments", "deployments/rollback", "deployments/scale",
                    "ingresses", "networkpolicies", "replicasets", "replicasets/scale",
                    "replicationcontrollers/scale"),
            rule("networking.k8s.io", WRITE, "ingresses", "networkpolicies"),
            rule("policy", WRITE, "poddisruptionbudgets"),
            rule("", WRITE, "pods", "pods/attach", "pods/exec", "pods/portforward", "pods/proxy"),
            rule("", WRITE, "configmaps", "events", "persistentvolumeclaims", "replicationcontrollers",
                    "replicationcontrollers/scale", "secrets", "serviceaccounts", "services", "services/proxy"),
            rule("", READ, "pods/attach", "pods/exec", "pods/portforward", "pods/proxy", "secrets",
                    "services/proxy"),
            rule("", List.of("impersonate"), "serviceaccounts")));

    private static final List<Rule> ADMIN = concat(EDIT, List.of(
            rule("authorization.k8s.io", List.of("create"), "localsubjectaccessreviews"),
            rule("rbac.authorization.k8s.io", concat(WRITE, READ), "rolebindings", "roles")));

    private static final Map<String, List<Rule>> POLICIES = Map.of(
            "AmazonEKSClusterAdminPolicy", List.of(
                    new Rule(List.of("*"), List.of("*"), List.of("*"), List.of()),
                    new Rule(List.of(), List.of(), List.of("*"), List.of("*"))),
            "AmazonEKSAdminViewPolicy", List.of(new Rule(List.of("*"), List.of("*"), READ, List.of())),
            "AmazonEKSAdminPolicy", ADMIN,
            "AmazonEKSEditPolicy", EDIT,
            "AmazonEKSViewPolicy", VIEW);

    private EksAccessPolicies() {
    }

    /** Only the five general-purpose policies have Kubernetes permissions here; any other name grants nothing. */
    static boolean allows(String policyName, AccessScope scope, Request request) {
        List<Rule> rules = policyName == null ? null : POLICIES.get(policyName);
        return rules != null && inScope(scope, request) && rules.stream().anyMatch(rule -> rule.matches(request));
    }

    /** A namespace scope holds only namespaced requests, matched exactly or by a trailing-{@code *} prefix. */
    static boolean inScope(AccessScope scope, Request request) {
        if (scope == null || "cluster".equals(scope.type())) {
            return scope != null;
        }
        if (!"namespace".equals(scope.type()) || request.nonResource() || request.namespace() == null
                || request.namespace().isEmpty() || scope.namespaces() == null) {
            return false;
        }
        return scope.namespaces().stream().anyMatch(namespace -> namespace.endsWith("*")
                ? request.namespace().startsWith(namespace.substring(0, namespace.length() - 1))
                : namespace.equals(request.namespace()));
    }

    private static Rule rule(String group, List<String> verbs, String... resources) {
        return new Rule(List.of(group), List.of(resources), verbs, List.of());
    }

    private static <T> List<T> concat(List<T> first, List<T> second) {
        List<T> all = new ArrayList<>(first);
        all.addAll(second);
        return List.copyOf(all);
    }
}
