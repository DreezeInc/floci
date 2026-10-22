package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdateClusterConfigRequest(
        ResourcesVpcConfig resourcesVpcConfig,
        Logging logging,
        UpdateAccessConfigRequest accessConfig,
        String clientRequestToken
) {}
