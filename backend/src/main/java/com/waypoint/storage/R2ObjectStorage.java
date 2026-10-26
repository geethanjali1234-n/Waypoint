package com.waypoint.storage;

import com.waypoint.exception.ObjectStorageException;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

public class R2ObjectStorage implements BinaryObjectStorage {

    private final S3Client s3Client;
    private final String bucketName;

    public R2ObjectStorage(S3Client s3Client, String bucketName) {
        this.s3Client = s3Client;
        this.bucketName = bucketName;
    }

    @Override
    public void store(String storageKey, byte[] content, String contentType) {
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(storageKey)
                    .contentType(contentType)
                    .contentLength((long) content.length)
                    .build();
            s3Client.putObject(request, RequestBody.fromBytes(content));
        } catch (RuntimeException exception) {
            throw storageFailure("store", storageKey, exception);
        }
    }

    @Override
    public StoredObject get(String storageKey) {
        try {
            ResponseBytes<GetObjectResponse> response = s3Client.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucketName).key(storageKey).build());
            String contentType = response.response().contentType();
            byte[] content = response.asByteArray();
            return new StoredObject(content, contentType, content.length);
        } catch (RuntimeException exception) {
            throw storageFailure("read", storageKey, exception);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(storageKey)
                    .build());
        } catch (RuntimeException exception) {
            throw storageFailure("delete", storageKey, exception);
        }
    }

    @Override
    public boolean exists(String storageKey) {
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucketName)
                    .key(storageKey)
                    .build());
            return true;
        } catch (NoSuchKeyException exception) {
            return false;
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                return false;
            }
            throw storageFailure("check", storageKey, exception);
        } catch (RuntimeException exception) {
            throw storageFailure("check", storageKey, exception);
        }
    }

    private ObjectStorageException storageFailure(String operation, String storageKey, RuntimeException cause) {
        String details = s3FailureDetails(cause);
        return new ObjectStorageException(
                "Failed to " + operation + " object in configured storage: " + storageKey + details, cause);
    }

    private String s3FailureDetails(RuntimeException cause) {
        if (!(cause instanceof S3Exception s3Exception)) {
            return "";
        }

        String errorCode = "unknown";
        String errorMessage = s3Exception.getMessage();
        if (s3Exception.awsErrorDetails() != null) {
            if (s3Exception.awsErrorDetails().errorCode() != null
                    && !s3Exception.awsErrorDetails().errorCode().isBlank()) {
                errorCode = s3Exception.awsErrorDetails().errorCode();
            }
            if (s3Exception.awsErrorDetails().errorMessage() != null
                    && !s3Exception.awsErrorDetails().errorMessage().isBlank()) {
                errorMessage = s3Exception.awsErrorDetails().errorMessage();
            }
        }

        return " [HTTP status=" + s3Exception.statusCode()
                + ", errorCode=" + errorCode
                + ", message=" + errorMessage + "]";
    }
}
