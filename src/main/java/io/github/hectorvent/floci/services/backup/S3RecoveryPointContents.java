package io.github.hectorvent.floci.services.backup;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.backup.model.S3RecoveryPointObject;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.PutObjectOptions;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The objects an S3 recovery point holds, captured when its backup job completes. */
@ApplicationScoped
public class S3RecoveryPointContents {

    private final AccountAwareStorageBackend<List<S3RecoveryPointObject>> store;
    private final S3Service s3Service;

    @Inject
    public S3RecoveryPointContents(StorageFactory storageFactory, S3Service s3Service) {
        this(storageFactory.create("backup", "backup-recovery-point-contents.json", new TypeReference<>() {}),
                s3Service);
    }

    S3RecoveryPointContents(AccountAwareStorageBackend<List<S3RecoveryPointObject>> store, S3Service s3Service) {
        this.store = store;
        this.s3Service = s3Service;
    }

    /** Saves every current object of the caller's bucket under the recovery point; returns its size. */
    public long capture(String recoveryPointArn, String bucket) {
        if (bucket == null) {
            throw new AwsException("InvalidParameterValueException", "S3 backups take a bucket ARN", 400);
        }
        requireVersioningEnabled(bucket);
        List<S3RecoveryPointObject> objects = new ArrayList<>();
        for (S3Object listed : s3Service.listObjects(bucket, null, null, Integer.MAX_VALUE)) {
            S3Object object = s3Service.getObject(bucket, listed.getKey());
            objects.add(new S3RecoveryPointObject(object.getKey(), object.getData(), object.getContentType(),
                    copyOf(object.getMetadata()), object.getContentEncoding(), object.getCacheControl(),
                    object.getContentDisposition(), copyOf(s3Service.getObjectTagging(bucket, object.getKey())),
                    object.getServerSideEncryption(), object.getSseKmsKeyId()));
        }
        store.put(recoveryPointArn, objects);
        return sizeOf(objects);
    }

    public void copy(String fromAccountId, String fromRecoveryPointArn, String toAccountId, String toRecoveryPointArn) {
        store.getForAccount(fromAccountId, fromRecoveryPointArn)
                .ifPresent(objects -> store.putForAccount(toAccountId, toRecoveryPointArn, objects));
    }

    public void delete(String recoveryPointArn) {
        store.delete(recoveryPointArn);
    }

    /** Writes the recovery point's objects into a bucket of the caller's; returns the bytes written. */
    public long restore(String ownerAccountId, String recoveryPointArn, S3RestoreRequest request) {
        List<S3RecoveryPointObject> objects = store.getForAccount(ownerAccountId, recoveryPointArn)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Recovery point holds no S3 objects: " + recoveryPointArn, 404));
        String bucket = request.bucket();
        if (request.newBucket()) {
            if (s3Service.ownsBucket(bucket)) {
                throw new AwsException("InvalidRequestException",
                        "NewBucket is true but bucket " + bucket + " already exists", 400);
            }
            s3Service.createBucket(bucket, request.region());
            s3Service.putBucketVersioning(bucket, "Enabled");
        } else {
            requireVersioningEnabled(bucket);
        }
        long bytes = 0;
        for (S3RecoveryPointObject object : objects) {
            if (!request.restores(object.key())) {
                continue;
            }
            byte[] data = object.data() == null ? new byte[0] : object.data();
            s3Service.putObject(bucket, object.key(), data, object.contentType(), object.metadata(),
                    putOptions(object, request));
            bytes += data.length;
        }
        return bytes;
    }

    private static PutObjectOptions putOptions(S3RecoveryPointObject object, S3RestoreRequest request) {
        PutObjectOptions options = new PutObjectOptions()
                .withContentEncoding(object.contentEncoding())
                .withCacheControl(object.cacheControl())
                .withContentDisposition(object.contentDisposition())
                .withTagging(object.tags());
        return switch (request.encryptionType()) {
            case S3RestoreRequest.SSE_S3 -> options.withServerSideEncryption("AES256");
            case S3RestoreRequest.SSE_KMS -> options.withServerSideEncryption("aws:kms")
                    .withSseKmsKeyId(request.kmsKey());
            default -> options.withServerSideEncryption(object.serverSideEncryption())
                    .withSseKmsKeyId(object.sseKmsKeyId());
        };
    }

    private void requireVersioningEnabled(String bucket) {
        if (!"Enabled".equals(s3Service.getBucketVersioning(bucket))) {
            throw new AwsException("InvalidRequestException",
                    "Versioning must be enabled on bucket " + bucket, 400);
        }
    }

    private static Map<String, String> copyOf(Map<String, String> values) {
        return values == null || values.isEmpty() ? null : new HashMap<>(values);
    }

    private static long sizeOf(List<S3RecoveryPointObject> objects) {
        return objects.stream().mapToLong(object -> object.data() == null ? 0 : object.data().length).sum();
    }
}
