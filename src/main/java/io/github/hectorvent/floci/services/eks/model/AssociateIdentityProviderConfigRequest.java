package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record AssociateIdentityProviderConfigRequest(OidcIdentityProviderConfigRequest oidc, Map<String, String> tags,
                                                     String clientRequestToken) {}
