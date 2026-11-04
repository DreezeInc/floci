package io.github.hectorvent.floci.services.wafv2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Optional;

/**
 * The managed rule groups the emulator can enforce, written in the WAFv2 statement language.
 * Each approximates its AWS namesake with the documented rule names, labels and default actions;
 * AWS does not publish the exact signatures.
 */
final class WafV2ManagedRuleGroups {

    record ManagedRuleGroup(String labelNamespace, JsonNode rules) {}

    private static final String COMMON_RULE_SET = """
            [
              {"Name": "NoUserAgent_HEADER", "Priority": 0, "Action": {"Block": {}},
               "RuleLabels": [{"Name": "NoUserAgent_Header"}],
               "Statement": {"NotStatement": {"Statement": {"SizeConstraintStatement": {
                 "FieldToMatch": {"SingleHeader": {"Name": "user-agent"}}, "ComparisonOperator": "GT", "Size": 0,
                 "TextTransformations": [{"Priority": 0, "Type": "NONE"}]}}}}},
              {"Name": "SizeRestrictions_QUERYSTRING", "Priority": 1, "Action": {"Block": {}},
               "RuleLabels": [{"Name": "SizeRestrictions_QueryString"}],
               "Statement": {"SizeConstraintStatement": {
                 "FieldToMatch": {"QueryString": {}}, "ComparisonOperator": "GT", "Size": 2048,
                 "TextTransformations": [{"Priority": 0, "Type": "NONE"}]}}},
              {"Name": "SizeRestrictions_Cookie_HEADER", "Priority": 2, "Action": {"Block": {}},
               "RuleLabels": [{"Name": "SizeRestrictions_Cookie_Header"}],
               "Statement": {"SizeConstraintStatement": {
                 "FieldToMatch": {"SingleHeader": {"Name": "cookie"}}, "ComparisonOperator": "GT", "Size": 10240,
                 "TextTransformations": [{"Priority": 0, "Type": "NONE"}]}}},
              {"Name": "SizeRestrictions_BODY", "Priority": 3, "Action": {"Block": {}},
               "RuleLabels": [{"Name": "SizeRestrictions_Body"}],
               "Statement": {"SizeConstraintStatement": {
                 "FieldToMatch": {"Body": {}}, "ComparisonOperator": "GT", "Size": 8192,
                 "TextTransformations": [{"Priority": 0, "Type": "NONE"}]}}},
              {"Name": "SizeRestrictions_URIPATH", "Priority": 4, "Action": {"Block": {}},
               "RuleLabels": [{"Name": "SizeRestrictions_URIPath"}],
               "Statement": {"SizeConstraintStatement": {
                 "FieldToMatch": {"UriPath": {}}, "ComparisonOperator": "GT", "Size": 1024,
                 "TextTransformations": [{"Priority": 0, "Type": "NONE"}]}}},
              {"Name": "EC2MetaDataSSRF_QUERYARGUMENTS", "Priority": 5, "Action": {"Block": {}},
               "RuleLabels": [{"Name": "EC2MetaDataSSRF_QueryArguments"}],
               "Statement": {"RegexMatchStatement": {
                 "FieldToMatch": {"AllQueryArguments": {}}, "RegexString": "169\\\\.254\\\\.169\\\\.254",
                 "TextTransformations": [{"Priority": 0, "Type": "URL_DECODE"}]}}},
              {"Name": "EC2MetaDataSSRF_URIPATH", "Priority": 6, "Action": {"Block": {}},
               "RuleLabels": [{"Name": "EC2MetaDataSSRF_URIPath"}],
               "Statement": {"RegexMatchStatement": {
                 "FieldToMatch": {"UriPath": {}}, "RegexString": "169\\\\.254\\\\.169\\\\.254",
                 "TextTransformations": [{"Priority": 0, "Type": "URL_DECODE"}]}}},
              {"Name": "GenericLFI_QUERYARGUMENTS", "Priority": 7, "Action": {"Block": {}},
               "RuleLabels": [{"Name": "GenericLFI_QueryArguments"}],
               "Statement": {"RegexMatchStatement": {
                 "FieldToMatch": {"AllQueryArguments": {}}, "RegexString": "\\\\.\\\\./",
                 "TextTransformations": [{"Priority": 0, "Type": "URL_DECODE"}]}}},
              {"Name": "GenericLFI_URIPATH", "Priority": 8, "Action": {"Block": {}},
               "RuleLabels": [{"Name": "GenericLFI_URIPath"}],
               "Statement": {"RegexMatchStatement": {
                 "FieldToMatch": {"UriPath": {}}, "RegexString": "\\\\.\\\\./",
                 "TextTransformations": [{"Priority": 0, "Type": "URL_DECODE"}]}}},
              {"Name": "CrossSiteScripting_QUERYARGUMENTS", "Priority": 9, "Action": {"Block": {}},
               "RuleLabels": [{"Name": "CrossSiteScripting_QueryArguments"}],
               "Statement": {"XssMatchStatement": {
                 "FieldToMatch": {"AllQueryArguments": {}},
                 "TextTransformations": [{"Priority": 0, "Type": "URL_DECODE"},
                                         {"Priority": 1, "Type": "HTML_ENTITY_DECODE"}]}}},
              {"Name": "CrossSiteScripting_URIPATH", "Priority": 10, "Action": {"Block": {}},
               "RuleLabels": [{"Name": "CrossSiteScripting_URIPath"}],
               "Statement": {"XssMatchStatement": {
                 "FieldToMatch": {"UriPath": {}},
                 "TextTransformations": [{"Priority": 0, "Type": "URL_DECODE"},
                                         {"Priority": 1, "Type": "HTML_ENTITY_DECODE"}]}}},
              {"Name": "CrossSiteScripting_COOKIE", "Priority": 11, "Action": {"Block": {}},
               "RuleLabels": [{"Name": "CrossSiteScripting_Cookie"}],
               "Statement": {"XssMatchStatement": {
                 "FieldToMatch": {"Cookies": {"MatchPattern": {"All": {}}, "MatchScope": "ALL",
                                              "OversizeHandling": "CONTINUE"}},
                 "TextTransformations": [{"Priority": 0, "Type": "URL_DECODE"},
                                         {"Priority": 1, "Type": "HTML_ENTITY_DECODE"}]}}}
            ]
            """;

    private static final Map<String, ManagedRuleGroup> GROUPS = Map.of(
            "AWS/AWSManagedRulesCommonRuleSet",
            new ManagedRuleGroup("awswaf:managed:aws:core-rule-set:", parse(COMMON_RULE_SET)));

    private WafV2ManagedRuleGroups() {}

    static Optional<ManagedRuleGroup> find(String vendor, String name) {
        return Optional.ofNullable(GROUPS.get(vendor + "/" + name));
    }

    private static JsonNode parse(String rules) {
        try {
            return new ObjectMapper().readTree(rules);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Managed rule group definition is not valid JSON", e);
        }
    }
}
