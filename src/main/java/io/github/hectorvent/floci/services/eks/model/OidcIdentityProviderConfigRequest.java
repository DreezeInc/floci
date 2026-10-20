package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record OidcIdentityProviderConfigRequest(String identityProviderConfigName, String issuerUrl, String clientId,
                                                String usernameClaim, String usernamePrefix, String groupsClaim,
                                                String groupsPrefix, Map<String, String> requiredClaims) {}
