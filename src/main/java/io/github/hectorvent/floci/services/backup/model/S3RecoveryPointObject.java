package io.github.hectorvent.floci.services.backup.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

/** One object of an S3 bucket as a backup job saw it. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record S3RecoveryPointObject(
        String key,
        byte[] data,
        String contentType,
        Map<String, String> metadata,
        String contentEncoding,
        String cacheControl,
        String contentDisposition,
        Map<String, String> tags,
        String serverSideEncryption,
        String sseKmsKeyId) {
}
