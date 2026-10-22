package io.github.hectorvent.floci.services.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class S3RestoreRequestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static S3RestoreRequest parse(Map<String, String> metadata) {
        return S3RestoreRequest.from(metadata, "us-east-1", MAPPER);
    }

    private static void assertRejected(String code, Map<String, String> metadata) {
        AwsException e = assertThrows(AwsException.class, () -> parse(metadata));
        assertEquals(code, e.getErrorCode());
    }

    @Test
    void defaultsToTheOriginalEncryptionIntoAnExistingBucket() {
        S3RestoreRequest request = parse(Map.of("DestinationBucketName", "target"));

        assertEquals("target", request.bucket());
        assertFalse(request.newBucket());
        assertEquals("ORIGINAL", request.encryptionType());
        assertTrue(request.restores("any/key"));
    }

    @Test
    void readsKeysWhateverTheirCase() {
        S3RestoreRequest request = parse(Map.of("destinationbucketname", "target", "NEWBUCKET", "TRUE",
                "encryptionType", "SSE_KMS", "kmsKey", "arn:aws:kms:us-east-1:222222222222:key/k"));

        assertTrue(request.newBucket());
        assertEquals("SSE_KMS", request.encryptionType());
        assertEquals("arn:aws:kms:us-east-1:222222222222:key/k", request.kmsKey());
    }

    @Test
    void encryptedWithoutAnEncryptionTypeFollowsTheKey() {
        assertEquals("SSE_KMS", parse(Map.of("DestinationBucketName", "t", "Encrypted", "true",
                "KMSKey", "arn:aws:kms:us-east-1:222222222222:key/k")).encryptionType());
        assertEquals("SSE_S3", parse(Map.of("DestinationBucketName", "t", "Encrypted", "true")).encryptionType());
    }

    @Test
    void itemsToRestoreSelectKeysAndPrefixes() {
        S3RestoreRequest request = parse(Map.of("DestinationBucketName", "t",
                "ItemsToRestore", "[\"s3://source/reports/\", \"s3://source/a.txt\", \"plain/key\"]"));

        assertEquals(List.of("reports/", "a.txt", "plain/key"), request.itemsToRestore());
        assertTrue(request.restores("reports/2026/q1.csv"));
        assertTrue(request.restores("a.txt"));
        assertTrue(request.restores("plain/key"));
        assertFalse(request.restores("b.txt"));
    }

    @Test
    void rejectsInvalidMetadata() {
        assertRejected("MissingParameterValueException", Map.of("NewBucket", "true"));
        assertRejected("MissingParameterValueException",
                Map.of("DestinationBucketName", "t", "EncryptionType", "SSE_KMS"));
        assertRejected("InvalidParameterValueException",
                Map.of("DestinationBucketName", "t", "EncryptionType", "SSE_C"));
        assertRejected("InvalidParameterValueException", Map.of("DestinationBucketName", "t", "NewBucket", "yes"));
        assertRejected("InvalidParameterValueException",
                Map.of("DestinationBucketName", "t", "ItemsToRestore", "s3://source/a.txt"));
        assertRejected("InvalidParameterValueException", Map.of("DestinationBucketName", "t",
                "ItemsToRestore", "[\"a\", \"b\", \"c\", \"d\", \"e\", \"f\"]"));
    }
}
