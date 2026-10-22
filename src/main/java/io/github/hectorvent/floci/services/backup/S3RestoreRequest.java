package io.github.hectorvent.floci.services.backup;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** The S3 restore that StartRestoreJob's Metadata asks for. */
record S3RestoreRequest(String bucket, boolean newBucket, String region, String encryptionType,
                        String kmsKey, List<String> itemsToRestore) {

    static final String SSE_S3 = "SSE_S3";
    static final String SSE_KMS = "SSE_KMS";
    static final String ORIGINAL = "ORIGINAL";
    private static final Set<String> ENCRYPTION_TYPES = Set.of(SSE_S3, SSE_KMS, ORIGINAL);
    private static final int MAX_ITEMS_TO_RESTORE = 5;

    static S3RestoreRequest from(Map<String, String> metadata, String region, ObjectMapper objectMapper) {
        Map<String, String> values = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        values.putAll(metadata);
        String bucket = values.get("DestinationBucketName");
        if (bucket == null || bucket.isBlank()) {
            throw new AwsException("MissingParameterValueException", "Metadata DestinationBucketName is required", 400);
        }
        String kmsKey = blankToNull(values.get("KMSKey"));
        String encryptionType = blankToNull(values.get("EncryptionType"));
        if (encryptionType == null) {
            encryptionType = !flag(values, "Encrypted") ? ORIGINAL : kmsKey != null ? SSE_KMS : SSE_S3;
        }
        if (!ENCRYPTION_TYPES.contains(encryptionType)) {
            throw new AwsException("InvalidParameterValueException",
                    "Metadata EncryptionType must be one of " + ENCRYPTION_TYPES + ": " + encryptionType, 400);
        }
        if (SSE_KMS.equals(encryptionType) && kmsKey == null) {
            throw new AwsException("MissingParameterValueException",
                    "Metadata KMSKey is required when EncryptionType is SSE_KMS", 400);
        }
        return new S3RestoreRequest(bucket, flag(values, "NewBucket"), region, encryptionType, kmsKey,
                itemsToRestore(values.get("ItemsToRestore"), objectMapper));
    }

    boolean restores(String key) {
        if (itemsToRestore.isEmpty()) {
            return true;
        }
        for (String item : itemsToRestore) {
            if (key.startsWith(item)) {
                return true;
            }
        }
        return false;
    }

    private static boolean flag(Map<String, String> values, String name) {
        String value = blankToNull(values.get(name));
        if (value == null) {
            return false;
        }
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new AwsException("InvalidParameterValueException",
                    "Metadata " + name + " must be true or false: " + value, 400);
        }
        return Boolean.parseBoolean(value);
    }

    private static List<String> itemsToRestore(String value, ObjectMapper objectMapper) {
        if (blankToNull(value) == null) {
            return List.of();
        }
        JsonNode items;
        try {
            items = objectMapper.readTree(value);
        } catch (JsonProcessingException e) {
            throw invalidItemsToRestore(value);
        }
        if (!items.isArray() || items.size() > MAX_ITEMS_TO_RESTORE) {
            throw invalidItemsToRestore(value);
        }
        List<String> prefixes = new ArrayList<>();
        for (JsonNode item : items) {
            if (!item.isTextual()) {
                throw invalidItemsToRestore(value);
            }
            prefixes.add(keyPrefix(item.asText()));
        }
        return prefixes;
    }

    private static AwsException invalidItemsToRestore(String value) {
        return new AwsException("InvalidParameterValueException", "Metadata ItemsToRestore must be a JSON array of"
                + " at most " + MAX_ITEMS_TO_RESTORE + " S3 paths: " + value, 400);
    }

    private static String keyPrefix(String item) {
        if (!item.startsWith("s3://")) {
            return item;
        }
        int slash = item.indexOf('/', "s3://".length());
        return slash < 0 ? "" : item.substring(slash + 1);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
