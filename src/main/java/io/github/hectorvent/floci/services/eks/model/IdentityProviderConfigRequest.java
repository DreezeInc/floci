package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** Body of DescribeIdentityProviderConfig and DisassociateIdentityProviderConfig. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record IdentityProviderConfigRequest(IdentityProviderConfig identityProviderConfig, String clientRequestToken) {}
