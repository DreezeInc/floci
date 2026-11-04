package io.github.hectorvent.floci.services.wafv2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.wafv2.model.IpSet;
import io.github.hectorvent.floci.services.wafv2.model.RegexPatternSet;
import io.github.hectorvent.floci.services.wafv2.model.RuleGroup;
import io.github.hectorvent.floci.services.wafv2.model.WebAcl;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

/**
 * Evaluates the Web ACL associated with a resource against one request, the way WAF fronts an
 * Application Load Balancer. Rules run in priority order; labels a matching rule adds are visible
 * to later rules, and rule groups (custom or managed) run with their own label namespace.
 *
 * <p>Emulation limits: the body and JSON body inspect as empty, because the load balancer streams
 * bodies to targets unbuffered; rate-based, geo-match and ASN statements never match; text
 * transformations other than the ones {@link #transform} names leave the value unchanged; and
 * managed rule groups other than {@link WafV2ManagedRuleGroups} hold no rules.
 */
@ApplicationScoped
public class WafV2RequestInspector {

    public record InspectedRequest(String method, String uriPath, String query,
                                   Map<String, List<String>> headers, String clientIp) {}

    public record BlockResponse(int status, String contentType, String body, Map<String, String> headers) {}

    static final String DEFAULT_BLOCK_BODY = "<html>\r\n<head><title>403 Forbidden</title></head>\r\n"
            + "<body>\r\n<center><h1>403 Forbidden</h1></center>\r\n</body>\r\n</html>\r\n";

    private static final Pattern XSS = Pattern.compile(
            "<\\s*/?\\s*(script|iframe|object|embed|svg|img|body|style|link|meta|frame|applet)\\b"
                    + "|<[^>]*\\bon[a-z]+\\s*="
                    + "|(java|vb)script\\s*:"
                    + "|document\\s*\\.\\s*(cookie|write|domain)"
                    + "|expression\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SQLI = Pattern.compile(
            "['\"`]\\s*(or|and)\\s+['\"`\\d]"
                    + "|\\bunion(\\s+all)?\\s+select\\b"
                    + "|;\\s*(drop|delete|insert|update|shutdown|exec)\\b"
                    + "|\\b(sleep|benchmark|pg_sleep|waitfor\\s+delay)\\b\\s*[(']"
                    + "|['\"`]\\s*(--|#|/\\*)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern HTML_ENTITY = Pattern.compile("&(#[xX]?[0-9a-fA-F]+|[a-zA-Z]+);?");

    private static final Map<String, String> NAMED_ENTITIES = Map.of(
            "lt", "<", "gt", ">", "amp", "&", "quot", "\"", "apos", "'", "nbsp", " ");

    private final WafV2Service wafV2Service;
    private final ObjectMapper objectMapper;

    @Inject
    public WafV2RequestInspector(WafV2Service wafV2Service, ObjectMapper objectMapper) {
        this.wafV2Service = wafV2Service;
        this.objectMapper = objectMapper;
    }

    /** The response WAF answers in place of the target, or empty when the request may pass. */
    public Optional<BlockResponse> inspect(String resourceArn, Supplier<InspectedRequest> request) {
        WebAcl acl = wafV2Service.getWebAclForResource(resourceArn);
        if (acl == null) {
            return Optional.empty();
        }
        JsonNode bodies = json(acl.getCustomResponseBodies());
        Verdict verdict = new Evaluation(request.get())
                .rules(json(acl.getRules()), acl.getLabelNamespace(), bodies, Map.of());
        if (verdict == null) {
            verdict = new Verdict(json(acl.getDefaultAction()), bodies);
        }
        return verdict.response();
    }

    private JsonNode json(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored WAFv2 JSON is not parseable: " + raw, e);
        }
    }

    private record Verdict(JsonNode action, JsonNode customResponseBodies) {

        Optional<BlockResponse> response() {
            if (action == null || action.has("Allow")) {
                return Optional.empty();
            }
            if (action.has("Captcha")) {
                return Optional.of(new BlockResponse(405, "text/html", "", Map.of()));
            }
            if (action.has("Challenge")) {
                return Optional.of(new BlockResponse(202, "text/html", "", Map.of()));
            }
            JsonNode custom = action.path("Block").path("CustomResponse");
            if (custom.isMissingNode()) {
                return Optional.of(new BlockResponse(403, "text/html", DEFAULT_BLOCK_BODY, Map.of()));
            }
            Map<String, String> headers = new LinkedHashMap<>();
            custom.path("ResponseHeaders").forEach(h -> headers.put(h.path("Name").asText(), h.path("Value").asText()));
            String bodyKey = custom.path("CustomResponseBodyKey").asText(null);
            JsonNode body = bodyKey == null || customResponseBodies == null ? null : customResponseBodies.get(bodyKey);
            if (body == null) {
                return Optional.of(new BlockResponse(custom.path("ResponseCode").asInt(403), "text/plain", "", headers));
            }
            String contentType = switch (body.path("ContentType").asText("TEXT_PLAIN")) {
                case "TEXT_HTML" -> "text/html";
                case "APPLICATION_JSON" -> "application/json";
                default -> "text/plain";
            };
            return Optional.of(new BlockResponse(custom.path("ResponseCode").asInt(403), contentType,
                    body.path("Content").asText(""), headers));
        }
    }

    private final class Evaluation {

        private final InspectedRequest request;
        private final Set<String> labels = new HashSet<>();

        private Evaluation(InspectedRequest request) {
            this.request = request;
        }

        Verdict rules(JsonNode rules, String labelNamespace, JsonNode bodies, Map<String, JsonNode> overrides) {
            if (rules == null || !rules.isArray()) {
                return null;
            }
            List<JsonNode> ordered = StreamSupport.stream(rules.spliterator(), false)
                    .sorted(Comparator.comparingInt(rule -> rule.path("Priority").asInt()))
                    .toList();
            for (JsonNode rule : ordered) {
                JsonNode statement = rule.path("Statement");
                if (statement.has("RuleGroupReferenceStatement") || statement.has("ManagedRuleGroupStatement")) {
                    Verdict group = ruleGroup(statement);
                    if (group != null && !rule.path("OverrideAction").has("Count")) {
                        return group;
                    }
                    continue;
                }
                if (!matches(statement)) {
                    continue;
                }
                rule.path("RuleLabels").forEach(label -> labels.add(labelNamespace + label.path("Name").asText()));
                JsonNode action = overrides.getOrDefault(rule.path("Name").asText(), rule.path("Action"));
                if (!action.has("Count")) {
                    return new Verdict(action, bodies);
                }
            }
            return null;
        }

        private Verdict ruleGroup(JsonNode statement) {
            JsonNode reference = statement.get("RuleGroupReferenceStatement");
            if (reference != null) {
                RuleGroup group = wafV2Service.findRuleGroupByArn(reference.path("ARN").asText());
                if (group == null) {
                    return null;
                }
                return rules(json(group.getRules()), group.getLabelNamespace(),
                        json(group.getCustomResponseBodies()), overrides(reference));
            }
            JsonNode managed = statement.get("ManagedRuleGroupStatement");
            JsonNode scopeDown = managed.get("ScopeDownStatement");
            if (scopeDown != null && !matches(scopeDown)) {
                return null;
            }
            return WafV2ManagedRuleGroups.find(managed.path("VendorName").asText(), managed.path("Name").asText())
                    .map(group -> rules(group.rules(), group.labelNamespace(), null, overrides(managed)))
                    .orElse(null);
        }

        private Map<String, JsonNode> overrides(JsonNode groupStatement) {
            Map<String, JsonNode> overrides = new HashMap<>();
            groupStatement.path("ExcludedRules").forEach(rule ->
                    overrides.put(rule.path("Name").asText(), objectMapper.createObjectNode().set("Count",
                            objectMapper.createObjectNode())));
            groupStatement.path("RuleActionOverrides").forEach(override ->
                    overrides.put(override.path("Name").asText(), override.path("ActionToUse")));
            return overrides;
        }

        private boolean matches(JsonNode statement) {
            if (!statement.isObject() || statement.isEmpty()) {
                return false;
            }
            String kind = statement.fieldNames().next();
            JsonNode body = statement.get(kind);
            return switch (kind) {
                case "AndStatement" -> StreamSupport.stream(body.path("Statements").spliterator(), false)
                        .allMatch(this::matches);
                case "OrStatement" -> StreamSupport.stream(body.path("Statements").spliterator(), false)
                        .anyMatch(this::matches);
                case "NotStatement" -> !matches(body.path("Statement"));
                case "ByteMatchStatement" -> byteMatch(body);
                case "RegexMatchStatement" -> regexMatch(body, List.of(body.path("RegexString").asText()));
                case "RegexPatternSetReferenceStatement" -> {
                    RegexPatternSet set = wafV2Service.findRegexPatternSetByArn(body.path("ARN").asText());
                    yield set != null && regexMatch(body, set.getRegularExpressionList());
                }
                case "SizeConstraintStatement" -> sizeConstraint(body);
                case "XssMatchStatement" -> transformed(body).stream().anyMatch(v -> XSS.matcher(v).find());
                case "SqliMatchStatement" -> transformed(body).stream().anyMatch(v -> SQLI.matcher(v).find());
                case "IPSetReferenceStatement" -> ipSet(body);
                case "LabelMatchStatement" -> labelMatch(body);
                default -> false;
            };
        }

        private boolean byteMatch(JsonNode statement) {
            String search = searchString(statement.path("SearchString").asText());
            String position = statement.path("PositionalConstraint").asText("CONTAINS");
            return transformed(statement).stream().anyMatch(value -> switch (position) {
                case "EXACTLY" -> value.equals(search);
                case "STARTS_WITH" -> value.startsWith(search);
                case "ENDS_WITH" -> value.endsWith(search);
                case "CONTAINS_WORD" -> Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(search)
                        + "(?![A-Za-z0-9_])").matcher(value).find();
                default -> value.contains(search);
            });
        }

        private boolean regexMatch(JsonNode statement, List<String> expressions) {
            List<Pattern> patterns = expressions.stream().map(Pattern::compile).toList();
            return transformed(statement).stream()
                    .anyMatch(value -> patterns.stream().anyMatch(p -> p.matcher(value).find()));
        }

        private boolean sizeConstraint(JsonNode statement) {
            long limit = statement.path("Size").asLong();
            String operator = statement.path("ComparisonOperator").asText();
            return transformed(statement).stream().anyMatch(value -> {
                long size = value.getBytes(StandardCharsets.UTF_8).length;
                return switch (operator) {
                    case "EQ" -> size == limit;
                    case "NE" -> size != limit;
                    case "LE" -> size <= limit;
                    case "LT" -> size < limit;
                    case "GE" -> size >= limit;
                    case "GT" -> size > limit;
                    default -> false;
                };
            });
        }

        private boolean ipSet(JsonNode statement) {
            IpSet set = wafV2Service.findIpSetByArn(statement.path("ARN").asText());
            if (set == null) {
                return false;
            }
            JsonNode forwarded = statement.get("IPSetForwardedIPConfig");
            List<String> candidates;
            if (forwarded == null) {
                candidates = request.clientIp() == null ? List.of() : List.of(request.clientIp());
            } else {
                List<String> header = header(forwarded.path("HeaderName").asText());
                if (header.isEmpty()) {
                    return "MATCH".equals(forwarded.path("FallbackBehavior").asText());
                }
                List<String> chain = List.of(String.join(",", header).split(",")).stream()
                        .map(String::trim).filter(ip -> !ip.isEmpty()).toList();
                candidates = switch (forwarded.path("Position").asText("FIRST")) {
                    case "LAST" -> chain.isEmpty() ? List.of() : List.of(chain.getLast());
                    case "ANY" -> chain;
                    default -> chain.isEmpty() ? List.of() : List.of(chain.getFirst());
                };
            }
            return candidates.stream().anyMatch(ip -> set.getAddresses().stream().anyMatch(cidr -> inCidr(ip, cidr)));
        }

        private boolean labelMatch(JsonNode statement) {
            String key = statement.path("Key").asText();
            if ("NAMESPACE".equals(statement.path("Scope").asText())) {
                return labels.stream().anyMatch(label -> label.startsWith(key));
            }
            return labels.stream().anyMatch(label -> label.equals(key) || label.endsWith(":" + key));
        }

        private List<String> transformed(JsonNode statement) {
            JsonNode transformations = statement.path("TextTransformations");
            return values(statement.path("FieldToMatch")).stream()
                    .map(value -> transform(value, transformations))
                    .toList();
        }

        private List<String> values(JsonNode field) {
            if (!field.isObject() || field.isEmpty()) {
                return List.of();
            }
            String kind = field.fieldNames().next();
            JsonNode spec = field.get(kind);
            return switch (kind) {
                case "SingleHeader" -> header(spec.path("Name").asText());
                case "Method" -> List.of(request.method());
                case "UriPath" -> List.of(request.uriPath());
                case "QueryString" -> List.of(request.query() == null ? "" : request.query());
                case "SingleQueryArgument" -> queryArguments().entrySet().stream()
                        .filter(e -> e.getKey().equalsIgnoreCase(spec.path("Name").asText()))
                        .flatMap(e -> e.getValue().stream()).toList();
                case "AllQueryArguments" -> queryArguments().values().stream().flatMap(List::stream).toList();
                case "Body" -> List.of("");
                case "Headers" -> pairs(lowercasedKeys(request.headers()), spec, "IncludedHeaders", "ExcludedHeaders");
                case "Cookies" -> pairs(cookies(), spec, "IncludedCookies", "ExcludedCookies");
                default -> List.of();
            };
        }

        private List<String> header(String name) {
            return request.headers().entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(name))
                    .flatMap(e -> e.getValue().stream()).toList();
        }

        private Map<String, List<String>> queryArguments() {
            Map<String, List<String>> arguments = new LinkedHashMap<>();
            if (request.query() == null || request.query().isEmpty()) {
                return arguments;
            }
            for (String pair : request.query().split("&")) {
                int eq = pair.indexOf('=');
                String name = eq < 0 ? pair : pair.substring(0, eq);
                String value = eq < 0 ? "" : pair.substring(eq + 1);
                arguments.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
            }
            return arguments;
        }

        private Map<String, List<String>> cookies() {
            Map<String, List<String>> cookies = new LinkedHashMap<>();
            for (String headerValue : header("cookie")) {
                for (String pair : headerValue.split(";")) {
                    int eq = pair.indexOf('=');
                    String name = (eq < 0 ? pair : pair.substring(0, eq)).trim();
                    if (!name.isEmpty()) {
                        cookies.computeIfAbsent(name, k -> new ArrayList<>()).add(eq < 0 ? "" : pair.substring(eq + 1).trim());
                    }
                }
            }
            return cookies;
        }

        private static Map<String, List<String>> lowercasedKeys(Map<String, List<String>> headers) {
            Map<String, List<String>> lowered = new LinkedHashMap<>();
            headers.forEach((name, values) ->
                    lowered.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).addAll(values));
            return lowered;
        }

        private static List<String> pairs(Map<String, List<String>> source, JsonNode spec,
                                          String included, String excluded) {
            JsonNode pattern = spec.path("MatchPattern");
            Set<String> include = names(pattern.get(included));
            Set<String> exclude = names(pattern.get(excluded));
            String scope = spec.path("MatchScope").asText("ALL");
            List<String> values = new ArrayList<>();
            source.forEach((name, entries) -> {
                String key = name.toLowerCase(Locale.ROOT);
                if ((include != null && !include.contains(key)) || (exclude != null && exclude.contains(key))) {
                    return;
                }
                if (!"VALUE".equals(scope)) {
                    values.add(name);
                }
                if (!"KEY".equals(scope)) {
                    values.addAll(entries);
                }
            });
            return values;
        }

        private static Set<String> names(JsonNode list) {
            if (list == null) {
                return null;
            }
            Set<String> names = new HashSet<>();
            list.forEach(name -> names.add(name.asText().toLowerCase(Locale.ROOT)));
            return names;
        }
    }

    /** API clients send SearchString as a base64 blob; a JSON client may send the text itself. */
    private static String searchString(String stored) {
        try {
            return new String(Base64.getDecoder().decode(stored), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return stored;
        }
    }

    static String transform(String value, JsonNode transformations) {
        List<JsonNode> ordered = StreamSupport.stream(transformations.spliterator(), false)
                .sorted(Comparator.comparingInt(t -> t.path("Priority").asInt()))
                .toList();
        String result = value;
        for (JsonNode transformation : ordered) {
            result = switch (transformation.path("Type").asText("NONE")) {
                case "LOWERCASE" -> result.toLowerCase(Locale.ROOT);
                case "URL_DECODE", "URL_DECODE_UNI" -> urlDecode(result);
                case "HTML_ENTITY_DECODE" -> htmlEntityDecode(result);
                case "COMPRESS_WHITE_SPACE" -> result.replaceAll("[\\s\\x0B]+", " ");
                case "CMD_LINE" -> result.replaceAll("[\\\\\"'^]", "")
                        .replaceAll("\\s+(?=[/(])", "")
                        .replaceAll("[,;]", " ")
                        .replaceAll("\\s+", " ")
                        .toLowerCase(Locale.ROOT);
                case "BASE64_DECODE" -> base64Decode(result);
                case "TRIM" -> result.strip();
                case "REMOVE_NULLS" -> result.replace("\0", "");
                case "REPLACE_NULLS" -> result.replace('\0', ' ');
                default -> result;
            };
        }
        return result;
    }

    private static String urlDecode(String value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '+') {
                out.write(' ');
            } else if (bytes[i] == '%' && i + 2 < bytes.length
                    && Character.digit(bytes[i + 1], 16) >= 0 && Character.digit(bytes[i + 2], 16) >= 0) {
                out.write(Character.digit(bytes[i + 1], 16) * 16 + Character.digit(bytes[i + 2], 16));
                i += 2;
            } else {
                out.write(bytes[i]);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String htmlEntityDecode(String value) {
        Matcher matcher = HTML_ENTITY.matcher(value);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String entity = matcher.group(1);
            String replacement;
            if (entity.startsWith("#x") || entity.startsWith("#X")) {
                replacement = codePoint(entity.substring(2), 16, matcher.group());
            } else if (entity.startsWith("#")) {
                replacement = codePoint(entity.substring(1), 10, matcher.group());
            } else {
                replacement = NAMED_ENTITIES.getOrDefault(entity.toLowerCase(Locale.ROOT), matcher.group());
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String codePoint(String digits, int radix, String original) {
        try {
            return new String(Character.toChars(Integer.parseInt(digits, radix)));
        } catch (IllegalArgumentException e) {
            return original;
        }
    }

    private static String base64Decode(String value) {
        try {
            return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    static boolean inCidr(String ip, String cidr) {
        int slash = cidr.indexOf('/');
        try {
            byte[] address = InetAddress.ofLiteral(ip).getAddress();
            byte[] network = InetAddress.ofLiteral(cidr.substring(0, slash)).getAddress();
            int prefix = Integer.parseInt(cidr.substring(slash + 1));
            if (address.length != network.length) {
                return false;
            }
            for (int bit = 0; bit < prefix; bit++) {
                int mask = 0x80 >> (bit % 8);
                if ((address[bit / 8] & mask) != (network[bit / 8] & mask)) {
                    return false;
                }
            }
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
