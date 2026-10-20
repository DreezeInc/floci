package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** The {@code {type, name}} handle EKS uses to list, describe and disassociate an identity provider config. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record IdentityProviderConfig(String type, String name) {}
