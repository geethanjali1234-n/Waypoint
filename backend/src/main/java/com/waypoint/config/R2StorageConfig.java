package com.waypoint.config;

import com.waypoint.storage.BinaryObjectStorage;
import com.waypoint.storage.R2ObjectStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

@Configuration
@ConditionalOnProperty(name = "waypoint.documents.r2.enabled", havingValue = "true", matchIfMissing = true)
public class R2StorageConfig {

    @Bean
    public S3Client r2S3Client(
            @Value("${waypoint.documents.r2.account-id}") String accountId,
            @Value("${waypoint.documents.r2.access-key-id}") String accessKeyId,
            @Value("${waypoint.documents.r2.secret-access-key}") String secretAccessKey,
            @Value("${waypoint.documents.r2.endpoint}") String endpoint) {

        requireConfigured("R2_ACCOUNT_ID", accountId);
        requireConfigured("R2_ACCESS_KEY_ID", accessKeyId);
        requireConfigured("R2_SECRET_ACCESS_KEY", secretAccessKey);
        requireConfigured("R2_ENDPOINT", endpoint);

        S3Configuration serviceConfiguration = S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .chunkedEncodingEnabled(false)
                .build();

        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
                .region(Region.of("auto"))
                .serviceConfiguration(serviceConfiguration)
                .build();
    }

    @Bean
    public BinaryObjectStorage r2ObjectStorage(
            S3Client r2S3Client,
            @Value("${waypoint.documents.r2.bucket-name}") String bucketName) {
        requireConfigured("R2_BUCKET_NAME", bucketName);
        return new R2ObjectStorage(r2S3Client, bucketName);
    }

    private void requireConfigured(String environmentVariable, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(environmentVariable + " must be configured when R2 is enabled");
        }
    }
}
