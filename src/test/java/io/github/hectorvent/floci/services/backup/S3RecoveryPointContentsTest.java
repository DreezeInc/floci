package io.github.hectorvent.floci.services.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.backup.model.S3RecoveryPointObject;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.PutObjectOptions;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class S3RecoveryPointContentsTest {

    private static final String OWNER = "111111111111";
    private static final String RECIPIENT = "222222222222";
    private static final String RP = "arn:aws:backup:us-east-1:111111111111:recovery-point:rp-1";
    private static final String COPY = "arn:aws:backup:us-east-1:111111111111:recovery-point:rp-2";

    private S3Service s3;
    private S3RecoveryPointContents contents;

    @BeforeEach
    void setUp() {
        s3 = mock(S3Service.class);
        AccountAwareStorageBackend<List<S3RecoveryPointObject>> store = AccountAwareStorageBackend.inMemory(OWNER);
        contents = new S3RecoveryPointContents(store, s3);
        when(s3.getBucketVersioning("source")).thenReturn("Enabled");
        when(s3.getBucketVersioning("target")).thenReturn("Enabled");
        S3Object report = object("reports/q1.csv", "a,b\n1,2\n", "text/csv");
        report.getMetadata().put("owner", "finance");
        report.setServerSideEncryption("aws:kms");
        report.setSseKmsKeyId("arn:aws:kms:us-east-1:111111111111:key/source");
        S3Object readme = object("readme.txt", "hello", "text/plain");
        when(s3.listObjects("source", null, null, Integer.MAX_VALUE)).thenReturn(List.of(report, readme));
        when(s3.getObject("source", "reports/q1.csv")).thenReturn(report);
        when(s3.getObject("source", "readme.txt")).thenReturn(readme);
        when(s3.getObjectTagging("source", "reports/q1.csv")).thenReturn(Map.of("class", "internal"));
        when(s3.getObjectTagging("source", "readme.txt")).thenReturn(Map.of());
    }

    private static S3Object object(String key, String body, String contentType) {
        return new S3Object("source", key, body.getBytes(StandardCharsets.UTF_8), contentType);
    }

    private static S3RestoreRequest request(Map<String, String> metadata) {
        return S3RestoreRequest.from(metadata, "us-east-1", new ObjectMapper());
    }

    @Test
    void captureSavesEveryObjectAndRestoreWritesThemBack() {
        assertEquals(13, contents.capture(RP, "source"));

        long restored = contents.restore(OWNER, RP, request(Map.of("DestinationBucketName", "target")));

        assertEquals(13, restored);
        ArgumentCaptor<PutObjectOptions> options = ArgumentCaptor.forClass(PutObjectOptions.class);
        verify(s3).putObject(eq("target"), eq("reports/q1.csv"), eq("a,b\n1,2\n".getBytes(StandardCharsets.UTF_8)),
                eq("text/csv"), eq(Map.of("owner", "finance")), options.capture());
        assertEquals(Map.of("class", "internal"), options.getValue().getTagging());
        assertEquals("aws:kms", options.getValue().getServerSideEncryption());
        assertEquals("arn:aws:kms:us-east-1:111111111111:key/source", options.getValue().getSseKmsKeyId());
        verify(s3).putObject(eq("target"), eq("readme.txt"), eq("hello".getBytes(StandardCharsets.UTF_8)),
                eq("text/plain"), isNull(), any(PutObjectOptions.class));
        verify(s3, never()).createBucket(anyString(), anyString());
    }

    @Test
    void restoreEncryptsAsAskedAndOnlyTheSelectedItems() {
        contents.capture(RP, "source");

        contents.restore(OWNER, RP, request(Map.of("DestinationBucketName", "target",
                "EncryptionType", "SSE_S3", "ItemsToRestore", "[\"s3://source/reports/\"]")));

        ArgumentCaptor<PutObjectOptions> options = ArgumentCaptor.forClass(PutObjectOptions.class);
        verify(s3).putObject(eq("target"), eq("reports/q1.csv"), any(), any(), any(), options.capture());
        assertEquals("AES256", options.getValue().getServerSideEncryption());
        verify(s3, never()).putObject(eq("target"), eq("readme.txt"), any(), any(), any(), any(PutObjectOptions.class));
    }

    @Test
    void aNewBucketIsCreatedVersionedAndAnExistingOneIsRefused() {
        contents.capture(RP, "source");
        when(s3.ownsBucket("fresh")).thenReturn(false);
        when(s3.ownsBucket("target")).thenReturn(true);

        contents.restore(OWNER, RP, request(Map.of("DestinationBucketName", "fresh", "NewBucket", "true")));
        verify(s3).createBucket("fresh", "us-east-1");
        verify(s3).putBucketVersioning("fresh", "Enabled");

        AwsException e = assertThrows(AwsException.class, () -> contents.restore(OWNER, RP,
                request(Map.of("DestinationBucketName", "target", "NewBucket", "true"))));
        assertEquals("InvalidRequestException", e.getErrorCode());
    }

    @Test
    void bothBucketsNeedVersioning() {
        when(s3.getBucketVersioning("unversioned")).thenReturn(null);
        assertThrows(AwsException.class, () -> contents.capture(RP, "unversioned"));

        contents.capture(RP, "source");
        AwsException e = assertThrows(AwsException.class, () -> contents.restore(OWNER, RP,
                request(Map.of("DestinationBucketName", "unversioned"))));
        assertTrue(e.getMessage().contains("Versioning"));
        verify(s3, never()).putObject(anyString(), anyString(), any(), any(), any(), any(PutObjectOptions.class));
    }

    @Test
    void aCopyCarriesTheObjectsToAnotherAccountAndADeleteDropsThem() {
        contents.capture(RP, "source");
        contents.copy(OWNER, RP, RECIPIENT, COPY);

        assertEquals(13, contents.restore(RECIPIENT, COPY, request(Map.of("DestinationBucketName", "target"))));

        contents.delete(RP);
        AwsException e = assertThrows(AwsException.class, () -> contents.restore(OWNER, RP,
                request(Map.of("DestinationBucketName", "target"))));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }
}
