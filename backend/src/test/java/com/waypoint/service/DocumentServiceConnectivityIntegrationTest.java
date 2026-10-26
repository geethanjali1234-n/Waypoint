package com.waypoint.service;

import com.waypoint.config.DatabaseInitializer;
import com.waypoint.entity.StoredDocument;
import com.waypoint.enums.DocumentStatus;
import com.waypoint.enums.DocumentType;
import com.waypoint.enums.StorageProvider;
import com.waypoint.repository.StoredDocumentRepository;
import com.waypoint.storage.BinaryObjectStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "JWT_SECRET=test-only-waypoint-jwt-secret-that-is-long-enough",
        "MAIL_ENABLED=false"
})
@EnabledIfEnvironmentVariable(named = "MONGODB_URI", matches = ".+")
@EnabledIfEnvironmentVariable(named = "MONGODB_DATABASE", matches = ".+")
@EnabledIfEnvironmentVariable(named = "R2_ENABLED", matches = "(?i:true)")
@EnabledIfEnvironmentVariable(named = "R2_ACCOUNT_ID", matches = ".+")
@EnabledIfEnvironmentVariable(named = "R2_ACCESS_KEY_ID", matches = ".+")
@EnabledIfEnvironmentVariable(named = "R2_SECRET_ACCESS_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "R2_BUCKET_NAME", matches = ".+")
@EnabledIfEnvironmentVariable(named = "R2_ENDPOINT", matches = ".+")
class DocumentServiceConnectivityIntegrationTest {

    private static final String ORGANIZATION_ID = "connectivity-tests-document-service";
    private static final String OWNER_USER_ID = "connectivity-test-user";
    private static final String FILE_NAME = "waypoint-document-service-test.txt";
    private static final String CONTENT_TYPE = "text/plain";
    private static final byte[] CONTENT =
            "Waypoint DocumentService end-to-end connectivity test".getBytes(StandardCharsets.UTF_8);

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

    @Autowired
    private MongoTemplate mongoTemplate;

    @Test
    void storesMetadataInMongoAndBinaryInRealR2AndLeavesBothForInspection() {
        String expectedDatabase = System.getenv("MONGODB_DATABASE");
        String effectiveDatabase = mongoTemplate.getDb().getName();
        String bucketName = System.getenv("R2_BUCKET_NAME");
        String endpointHost = URI.create(System.getenv("R2_ENDPOINT")).getHost();

        assertThat(effectiveDatabase)
                .as("Phase 2B must use the explicitly configured MongoDB database")
                .isEqualTo(expectedDatabase);
        assertThat(bucketName)
                .as("Phase 2B must target the private Waypoint My HR documents bucket")
                .isEqualTo("waypoint-myhr-documents");

        System.out.println("DOCUMENT_SERVICE_CONNECTIVITY_MONGODB_DATABASE=" + effectiveDatabase);
        System.out.println("DOCUMENT_SERVICE_CONNECTIVITY_R2_BUCKET=" + bucketName);
        System.out.println("DOCUMENT_SERVICE_CONNECTIVITY_R2_ENDPOINT_HOST=" + endpointHost);

        String relatedEntityId = "connectivity-test-" + UUID.randomUUID();
        DocumentUploadRequest request = new DocumentUploadRequest(
                ORGANIZATION_ID,
                OWNER_USER_ID,
                DocumentType.REPORT,
                "CONNECTIVITY_TEST",
                relatedEntityId,
                FILE_NAME,
                CONTENT_TYPE,
                CONTENT,
                1,
                OWNER_USER_ID);

        StoredDocument saved = documentService.store(request);

        assertThat(saved.getId()).isNotBlank();
        assertThat(saved.getStorageKey())
                .startsWith("organizations/connectivity-tests-document-service/report/")
                .endsWith("-" + FILE_NAME);
        assertThat(binaryObjectStorage.exists(saved.getStorageKey())).isTrue();

        StoredDocument persisted = storedDocumentRepository.findById(saved.getId()).orElseThrow();
        assertThat(persisted.getOrganizationId()).isEqualTo(ORGANIZATION_ID);
        assertThat(persisted.getOwnerUserId()).isEqualTo(OWNER_USER_ID);
        assertThat(persisted.getDocumentType()).isEqualTo(DocumentType.REPORT);
        assertThat(persisted.getRelatedEntityType()).isEqualTo("CONNECTIVITY_TEST");
        assertThat(persisted.getRelatedEntityId()).isEqualTo(relatedEntityId);
        assertThat(persisted.getFileName()).isEqualTo(FILE_NAME);
        assertThat(persisted.getStorageProvider()).isEqualTo(StorageProvider.R2);
        assertThat(persisted.getStorageKey()).isEqualTo(saved.getStorageKey());
        assertThat(persisted.getContentType()).isEqualTo(CONTENT_TYPE);
        assertThat(persisted.getFileSize()).isEqualTo((long) CONTENT.length);
        assertThat(persisted.getStatus()).isEqualTo(DocumentStatus.ACTIVE);

        DocumentService.DocumentContent retrieved = documentService.getContent(saved.getId());
        assertThat(retrieved.metadata().getId()).isEqualTo(saved.getId());
        assertThat(retrieved.metadata().getStorageKey()).isEqualTo(saved.getStorageKey());
        assertThat(retrieved.content()).isEqualTo(CONTENT);
        assertThat(retrieved.contentType()).isEqualTo(CONTENT_TYPE);
        assertThat(retrieved.contentLength()).isEqualTo(CONTENT.length);

        System.out.println("DOCUMENT_SERVICE_CONNECTIVITY_DOCUMENT_ID=" + saved.getId());
        System.out.println("DOCUMENT_SERVICE_CONNECTIVITY_STORAGE_KEY=" + saved.getStorageKey());
        System.out.println("DOCUMENT_SERVICE_CONNECTIVITY_FILE_NAME=" + saved.getFileName());
    }
}
