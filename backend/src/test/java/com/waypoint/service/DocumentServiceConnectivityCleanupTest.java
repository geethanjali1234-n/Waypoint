package com.waypoint.service;

import com.waypoint.config.DatabaseInitializer;
import com.waypoint.entity.StoredDocument;
import com.waypoint.repository.StoredDocumentRepository;
import com.waypoint.storage.BinaryObjectStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "JWT_SECRET=test-only-waypoint-jwt-secret-that-is-long-enough",
        "MAIL_ENABLED=false"
})
@EnabledIfEnvironmentVariable(named = "R2_ENABLED", matches = "(?i:true)")
@EnabledIfEnvironmentVariable(named = "R2_ACCOUNT_ID", matches = ".+")
@EnabledIfEnvironmentVariable(named = "R2_ACCESS_KEY_ID", matches = ".+")
@EnabledIfEnvironmentVariable(named = "R2_SECRET_ACCESS_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "R2_BUCKET_NAME", matches = ".+")
@EnabledIfEnvironmentVariable(named = "R2_ENDPOINT", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DOCUMENT_SERVICE_TEST_DOCUMENT_ID", matches = ".+")
class DocumentServiceConnectivityCleanupTest {

    @MockitoBean
    private DatabaseInitializer databaseInitializer;

    @MockitoBean
    private ResumeStorageMigrationService resumeStorageMigrationService;

    @MockitoBean
    private EventLocationMigrationService eventLocationMigrationService;

    @MockitoBean
    private JobLocationMigrationService jobLocationMigrationService;

    @MockitoBean
    private FileStorageMigrationService fileStorageMigrationService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private StoredDocumentRepository storedDocumentRepository;

    @Autowired
    private BinaryObjectStorage binaryObjectStorage;

    @Test
    void deletesOnlyTheExactConnectivityDocumentRequested() {
        String documentId = System.getenv("DOCUMENT_SERVICE_TEST_DOCUMENT_ID");
        StoredDocument metadata = storedDocumentRepository.findById(documentId).orElseThrow();

        assertThat(metadata.getOrganizationId()).isEqualTo("connectivity-tests-document-service");
        assertThat(metadata.getRelatedEntityType()).isEqualTo("CONNECTIVITY_TEST");
        String storageKey = metadata.getStorageKey();

        documentService.delete(documentId);

        assertThat(storedDocumentRepository.existsById(documentId)).isFalse();
        assertThat(binaryObjectStorage.exists(storageKey)).isFalse();

        System.out.println("DOCUMENT_SERVICE_CONNECTIVITY_CLEANED_DOCUMENT_ID=" + documentId);
        System.out.println("DOCUMENT_SERVICE_CONNECTIVITY_CLEANED_STORAGE_KEY=" + storageKey);
    }
}
