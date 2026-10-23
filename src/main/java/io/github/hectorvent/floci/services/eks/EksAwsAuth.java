package io.github.hectorvent.floci.services.eks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** The mapRoles and mapUsers of {@code kube-system/aws-auth}, matched as aws-iam-authenticator matches them. */
record EksAwsAuth(List<Mapping> roles, List<Mapping> users) {
    static final EksAwsAuth NONE = new EksAwsAuth(List.of(), List.of());
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final TypeReference<List<Map<String, Object>>> ENTRIES = new TypeReference<>() { };

    record Mapping(String arn, String username, List<String> groups) {}

    static EksAwsAuth parse(String mapRoles, String mapUsers) throws IOException {
        return new EksAwsAuth(mappings(mapRoles, "rolearn"), mappings(mapUsers, "userarn"));
    }

    /** ARNs compare case-insensitively, so a role ARN written with a path never matches a canonical role ARN. */
    Optional<Mapping> find(String canonicalArn) {
        String wanted = canonicalArn.toLowerCase(Locale.ROOT);
        Mapping match = null;
        for (List<Mapping> mappings : List.of(users, roles)) {
            for (Mapping mapping : mappings) {
                if (mapping.arn().toLowerCase(Locale.ROOT).equals(wanted)) {
                    match = mapping;
                }
            }
        }
        return Optional.ofNullable(match);
    }

    private static List<Mapping> mappings(String yaml, String arnField) throws IOException {
        if (yaml == null || yaml.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> entries = YAML.readValue(yaml, ENTRIES);
        List<Mapping> mappings = new ArrayList<>();
        for (Map<String, Object> entry : entries == null ? List.<Map<String, Object>>of() : entries) {
            if (entry != null && entry.get(arnField) instanceof String arn
                    && entry.get("username") instanceof String username) {
                List<String> groups = new ArrayList<>();
                if (entry.get("groups") instanceof List<?> listed) {
                    listed.stream().filter(String.class::isInstance).map(String.class::cast).forEach(groups::add);
                }
                mappings.add(new Mapping(arn, username, List.copyOf(groups)));
            }
        }
        return List.copyOf(mappings);
    }
}
