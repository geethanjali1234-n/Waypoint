package com.waypoint.config;

import com.waypoint.storage.BinaryObjectStorage;
import com.waypoint.storage.LocalFileSystemObjectStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * Registers a filesystem-backed {@link BinaryObjectStorage} for local
 * development when Cloudflare R2 is disabled, so the Document Service and the
 * My HR document endpoints that depend on it work without R2 credentials.
 *
 * <p>When {@code waypoint.documents.r2.enabled=true}, {@link R2StorageConfig}
 * provides the bean instead and this configuration backs off. Local storage
 * requires an explicit opt-out from R2.
 */
@Configuration
@ConditionalOnProperty(
        name = "waypoint.documents.r2.enabled",
        havingValue = "false")
public class LocalObjectStorageConfig {

    @Bean
    public BinaryObjectStorage localFileSystemObjectStorage(
            @Value("${waypoint.documents.local.root:./uploads/object-store}") String root) {
        return new LocalFileSystemObjectStorage(Path.of(root));
    }
}
