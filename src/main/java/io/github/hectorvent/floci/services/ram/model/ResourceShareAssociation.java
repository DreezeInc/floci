package io.github.hectorvent.floci.services.ram.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/** One row of a {@code GetResourceShareAssociations} response: a principal or resource on a share. */
@RegisterForReflection
public record ResourceShareAssociation(
        String resourceShareArn,
        String resourceShareName,
        String associatedEntity,
        String associationType,
        String status,
        Instant creationTime,
        Instant lastUpdatedTime,
        boolean external) {
}
