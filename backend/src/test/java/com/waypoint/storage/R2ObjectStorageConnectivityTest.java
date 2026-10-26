package com.waypoint.storage;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class R2ObjectStorageConnectivityTest {

    @Test
    void uploadReadAndDeleteOnePrivateR2Object() throws Throwable {
        String accessKey = System.getenv("R2_ACCESS_KEY_ID");
        String secretKey = System.getenv("R2_SECRET_ACCESS_KEY");
        String endpoint = System.getenv("R2_ENDPOINT");
        String bucket = System.getenv("R2_BUCKET_NAME");
        String accountId = System.getenv("R2_ACCOUNT_ID");

        Assumptions.assumeTrue(hasText(accountId)
                        && hasText(accessKey)
                        && hasText(secretKey)
                        && hasText(endpoint)
                        && hasText(bucket),
                "R2 environment variables are not configured");

        assertThat(accessKey)
                .as("R2_ACCESS_KEY_ID must be the 32-character R2 Access Key ID, not the secret")
                .hasSize(32);
        assertThat(secretKey)
                .as("R2_SECRET_ACCESS_KEY must be the 64-character R2 Secret Access Key")
                .hasSize(64);

        S3Configuration s3Configuration = S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .chunkedEncodingEnabled(false)
                .build();

        try (S3Client client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .region(Region.of("auto"))
                .serviceConfiguration(s3Configuration)
                .build()) {

            R2ObjectStorage storage = new R2ObjectStorage(client, bucket);
            String key = "connectivity-tests/" + UUID.randomUUID() + ".txt";
            byte[] expected = "Waypoint R2 connectivity test".getBytes(StandardCharsets.UTF_8);

            Throwable primaryFailure = null;
            try {
                storage.store(key, expected, "text/plain");
                assertThat(storage.exists(key)).isTrue();
                assertThat(storage.get(key).content()).isEqualTo(expected);
            } catch (Throwable failure) {
                primaryFailure = failure;
                throw failure;
            } finally {
                try {
                    storage.delete(key);
                } catch (RuntimeException cleanupFailure) {
                    if (primaryFailure != null) {
                        primaryFailure.addSuppressed(cleanupFailure);
                    } else {
                        throw cleanupFailure;
                    }
                }
            }

            assertThat(storage.exists(key)).isFalse();
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
