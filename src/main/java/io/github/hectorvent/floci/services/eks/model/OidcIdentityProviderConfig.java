package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OidcIdentityProviderConfig(String identityProviderConfigName, String identityProviderConfigArn,
                                         String clusterName, String issuerUrl, String clientId,
                                         String usernameClaim, String usernamePrefix, String groupsClaim,
                                         String groupsPrefix, Map<String, String> requiredClaims,
                                         Map<String, String> tags, String status) {}
