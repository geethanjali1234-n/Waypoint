package com.waypoint.entity;

import com.waypoint.enums.ChunkAccessLevel;
import com.waypoint.enums.DocumentType;
import com.waypoint.enums.KnowledgeDocumentStatus;
import com.waypoint.enums.KnowledgeIngestionFailure;
import com.waypoint.enums.KnowledgeIngestionStage;
import com.waypoint.enums.KnowledgeContentReviewDecision;
import com.waypoint.exception.DocumentExtractionException;
import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeDocumentModelTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 17, 9, 0);
    private static final String SHA = "a3f1b2c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2";

    // --- Mapping --------------------------------------------------------------------------------

    @Test
    void usesKnowledgeDocumentsCollection() {
        assertThat(KnowledgeDocument.class.getAnnotation(Document.class).collection())
                .isEqualTo("knowledge_documents");
    }

    @Test
    void mapsIdAndOptimisticLockVersionOnTheRootEntity() throws Exception {
        assertThat(KnowledgeDocument.class.getDeclaredField("id").getAnnotation(Id.class)).isNotNull();
        Field version = KnowledgeDocument.class.getDeclaredField("version");
        assertThat(version.getAnnotation(Version.class)).isNotNull();
        assertThat(version.getType()).isEqualTo(Long.class);
    }

    @Test
    void versionsAndHistoryEntriesAreEmbeddedRatherThanStoredOrVersionedOnTheirOwn() {
        for (Class<?> type : List.of(KnowledgeDocumentVersion.class, KnowledgeVersionHistoryEntry.class)) {
            assertThat(type.getAnnotation(Document.class)).isNull();
            assertThat(fields(type))
                    .noneMatch(field -> field.isAnnotationPresent(Id.class) || field.isAnnotationPresent(Version.class));
        }
    }

    @Test
    void declaresNoIndexAnnotationsSoNoIndexIsEverCreatedAutomatically() {
        for (Class<?> type : List.of(KnowledgeDocument.class, KnowledgeDocumentVersion.class,
                KnowledgeVersionHistoryEntry.class)) {
            assertThat(indexAnnotations(type.getAnnotations())).as(type.getSimpleName()).isEmpty();
            for (Field field : fields(type)) {
                assertThat(indexAnnotations(field.getAnnotations()))
                        .as(type.getSimpleName() + "." + field.getName())
                        .isEmpty();
            }
        }
    }

    // --- Fields: adding one fails these tests until it has been reviewed ------------------------

    @Test
    void entryHoldsExactlyTheApprovedFields() {
        assertThat(fieldNames(KnowledgeDocument.class)).containsExactlyInAnyOrder(
                "id", "organizationId", "title", "status", "activeVersion", "pendingVersion",
                "versionHistory", "sourceDocumentIds", "createdByUserId", "createdAt", "revokedByUserId", "revokedAt", "version");
    }

    @Test
    void versionHoldsMetadataOnlyAndNeverDocumentText() {
        assertThat(fieldNames(KnowledgeDocumentVersion.class)).containsExactlyInAnyOrder(
                "sourceDocumentId", "sourceDocumentName", "documentType", "accessLevel", "contentSha256",
                "approvedByUserId", "approvedAt",
                "pageCount", "pagesWithoutText", "incompleteExtractionAcceptedByUserId", "incompleteExtractionAcceptedAt",
                "inspectionFormatVersion", "hiddenDocxRunsExcluded", "suspiciousTextFragments",
                "invisibleUnicodeCharacters", "unicodeTagCharactersRemoved", "hiddenCommentBlocks",
                "instructionLikeContentMatches", "pagesRequiringOcrReview", "contentReviewRequired",
                "inspectionFindingsSha256", "reviewablePassagesSha256", "indexableTextSha256", "contentReviewDecision", "contentReviewedByUserId",
                "contentReviewedAt", "reviewedSourceDocumentId", "reviewedFindingsSha256", "reviewedIndexableTextSha256",
                "restoredFromSourceDocumentId", "restoredFromActivatedAt",
                "stage", "ingestionId", "ingestionStartedAt", "chunkCount", "embeddingModel", "embeddingDimensions",
                "indexedAt", "activatedAt", "activatedByUserId",
                "failureReason", "extractionFailureReason", "failedAt");
    }

    @Test
    void historyEntryPreservesOnlyVersionMetadataAndSupersessionMetadata() throws Exception {
        assertThat(fieldNames(KnowledgeVersionHistoryEntry.class)).containsExactlyInAnyOrder(
                "version", "supersededAt", "supersededByIngestionId", "supersededByUserId");
        assertThat(type(KnowledgeVersionHistoryEntry.class, "version")).isEqualTo(KnowledgeDocumentVersion.class);
        assertThat(fieldNames(KnowledgeDocumentVersion.class))
                .doesNotContain("content", "bytes", "chunkText", "embedding", "embeddings");
    }

    @Test
    void fieldsUseTheirDomainTypes() throws Exception {
        assertThat(type(KnowledgeDocument.class, "status")).isEqualTo(KnowledgeDocumentStatus.class);
        assertThat(type(KnowledgeDocument.class, "activeVersion")).isEqualTo(KnowledgeDocumentVersion.class);
        assertThat(type(KnowledgeDocument.class, "pendingVersion")).isEqualTo(KnowledgeDocumentVersion.class);
        assertThat(KnowledgeDocument.class.getDeclaredField("versionHistory").getGenericType().getTypeName())
                .isEqualTo("java.util.List<com.waypoint.entity.KnowledgeVersionHistoryEntry>");
        assertThat(KnowledgeDocument.class.getDeclaredField("sourceDocumentIds").getGenericType().getTypeName())
                .isEqualTo("java.util.List<java.lang.String>");

        assertThat(type(KnowledgeDocumentVersion.class, "documentType")).isEqualTo(DocumentType.class);
        assertThat(type(KnowledgeDocumentVersion.class, "accessLevel")).isEqualTo(ChunkAccessLevel.class);
        assertThat(type(KnowledgeDocumentVersion.class, "stage")).isEqualTo(KnowledgeIngestionStage.class);
        assertThat(type(KnowledgeDocumentVersion.class, "failureReason")).isEqualTo(KnowledgeIngestionFailure.class);
        assertThat(type(KnowledgeDocumentVersion.class, "extractionFailureReason"))
                .isEqualTo(DocumentExtractionException.Reason.class);
        assertThat(type(KnowledgeDocumentVersion.class, "contentReviewDecision"))
                .isEqualTo(KnowledgeContentReviewDecision.class);
        assertThat(type(KnowledgeDocumentVersion.class, "restoredFromSourceDocumentId")).isEqualTo(String.class);
        assertThat(type(KnowledgeDocumentVersion.class, "restoredFromActivatedAt")).isEqualTo(LocalDateTime.class);
        for (String count : List.of("pageCount", "pagesWithoutText", "inspectionFormatVersion", "hiddenDocxRunsExcluded",
                "suspiciousTextFragments", "invisibleUnicodeCharacters", "unicodeTagCharactersRemoved", "hiddenCommentBlocks",
                "instructionLikeContentMatches", "pagesRequiringOcrReview", "chunkCount", "embeddingDimensions")) {
            // Boxed so "not known yet" stays distinguishable from zero.
            assertThat(type(KnowledgeDocumentVersion.class, count)).as(count).isEqualTo(Integer.class);
        }
    }

    // --- Builders and defaults ------------------------------------------------------------------

    @Test
    void builderProducesALiveEntryWithActiveAndPendingVersions() {
        KnowledgeDocumentVersion active = KnowledgeDocumentVersion.builder()
                .sourceDocumentId("doc-v1").stage(KnowledgeIngestionStage.INDEXED).ingestionId("ingestion-1").build();
        KnowledgeDocumentVersion pending = KnowledgeDocumentVersion.builder()
                .sourceDocumentId("doc-v2").stage(KnowledgeIngestionStage.APPROVED).build();

        KnowledgeDocument entry = KnowledgeDocument.builder()
                .id("kd-1")
                .organizationId("org-abc")
                .title("Leave Policy")
                .status(KnowledgeDocumentStatus.LIVE)
                .activeVersion(active)
                .pendingVersion(pending)
                .sourceDocumentIds(List.of("doc-v1", "doc-v2"))
                .createdByUserId("hr-1")
                .createdAt(T0)
                .version(3L)
                .build();

        assertThat(entry.getId()).isEqualTo("kd-1");
        assertThat(entry.getOrganizationId()).isEqualTo("org-abc");
        assertThat(entry.getTitle()).isEqualTo("Leave Policy");
        assertThat(entry.getStatus()).isEqualTo(KnowledgeDocumentStatus.LIVE);
        assertThat(entry.getActiveVersion()).isSameAs(active);
        assertThat(entry.getPendingVersion()).isSameAs(pending);
        assertThat(entry.getSourceDocumentIds()).containsExactly("doc-v1", "doc-v2");
        assertThat(entry.getCreatedByUserId()).isEqualTo("hr-1");
        assertThat(entry.getCreatedAt()).isEqualTo(T0);
        assertThat(entry.getRevokedByUserId()).isNull();
        assertThat(entry.getRevokedAt()).isNull();
        assertThat(entry.getVersion()).isEqualTo(3L);
    }

    @Test
    void revokedEntryRecordsWhoRevokedItAndWhen() {
        KnowledgeDocument entry = KnowledgeDocument.builder()
                .status(KnowledgeDocumentStatus.REVOKED)
                .revokedByUserId("admin-1")
                .revokedAt(T0.plusDays(2))
                .build();

        assertThat(entry.getStatus()).isEqualTo(KnowledgeDocumentStatus.REVOKED);
        assertThat(entry.getRevokedByUserId()).isEqualTo("admin-1");
        assertThat(entry.getRevokedAt()).isEqualTo(T0.plusDays(2));
    }

    @Test
    void versionCarriesApprovalExtractionIngestionAndFailureMetadata() {
        KnowledgeDocumentVersion version = KnowledgeDocumentVersion.builder()
                .sourceDocumentId("doc-v2")
                .sourceDocumentName("leave-policy-2026.pdf")
                .documentType(DocumentType.COMPANY_POLICY)
                .accessLevel(ChunkAccessLevel.HR_ONLY)
                .contentSha256(SHA)
                .approvedByUserId("hr-1")
                .approvedAt(T0)
                .pageCount(12)
                .pagesWithoutText(2)
                .incompleteExtractionAcceptedByUserId("hr-2")
                .incompleteExtractionAcceptedAt(T0.plusMinutes(5))
                .inspectionFormatVersion(1)
                .hiddenDocxRunsExcluded(1)
                .suspiciousTextFragments(2)
                .invisibleUnicodeCharacters(3)
                .unicodeTagCharactersRemoved(4)
                .hiddenCommentBlocks(5)
                .instructionLikeContentMatches(6)
                .pagesRequiringOcrReview(2)
                .contentReviewRequired(true)
                .inspectionFindingsSha256(SHA)
                .reviewablePassagesSha256(SHA)
                .indexableTextSha256(SHA)
                .contentReviewDecision(KnowledgeContentReviewDecision.EXCLUDE_FLAGGED_TEXT)
                .contentReviewedByUserId("hr-2")
                .contentReviewedAt(T0.plusMinutes(6))
                .reviewedSourceDocumentId("doc-v2")
                .reviewedFindingsSha256(SHA)
                .reviewedIndexableTextSha256(SHA)
                .restoredFromSourceDocumentId("doc-v1")
                .restoredFromActivatedAt(T0.minusDays(4))
                .stage(KnowledgeIngestionStage.FAILED)
                .ingestionId("ingestion-7")
                .ingestionStartedAt(T0.plusMinutes(10))
                .chunkCount(40)
                .embeddingModel("voyage-4-lite")
                .embeddingDimensions(1024)
                .indexedAt(T0.plusMinutes(11))
                .activatedAt(T0.plusMinutes(12))
                .activatedByUserId("admin-3")
                .failureReason(KnowledgeIngestionFailure.EXTRACTION_FAILED)
                .extractionFailureReason(DocumentExtractionException.Reason.ENCRYPTED)
                .failedAt(T0.plusMinutes(13))
                .build();

        assertThat(version.getSourceDocumentId()).isEqualTo("doc-v2");
        assertThat(version.getSourceDocumentName()).isEqualTo("leave-policy-2026.pdf");
        assertThat(version.getDocumentType()).isEqualTo(DocumentType.COMPANY_POLICY);
        assertThat(version.getAccessLevel()).isEqualTo(ChunkAccessLevel.HR_ONLY);
        assertThat(version.getContentSha256()).isEqualTo(SHA).hasSize(64);
        assertThat(version.getApprovedByUserId()).isEqualTo("hr-1");
        assertThat(version.getApprovedAt()).isEqualTo(T0);
        assertThat(version.getPageCount()).isEqualTo(12);
        assertThat(version.getPagesWithoutText()).isEqualTo(2);
        assertThat(version.getIncompleteExtractionAcceptedByUserId()).isEqualTo("hr-2");
        assertThat(version.getIncompleteExtractionAcceptedAt()).isEqualTo(T0.plusMinutes(5));
        assertThat(version.getInspectionFormatVersion()).isEqualTo(1);
        assertThat(version.getHiddenDocxRunsExcluded()).isEqualTo(1);
        assertThat(version.getReviewablePassagesSha256()).isEqualTo(SHA);
        assertThat(version.getContentReviewDecision()).isEqualTo(KnowledgeContentReviewDecision.EXCLUDE_FLAGGED_TEXT);
        assertThat(version.getReviewedSourceDocumentId()).isEqualTo("doc-v2");
        assertThat(version.getRestoredFromSourceDocumentId()).isEqualTo("doc-v1");
        assertThat(version.getRestoredFromActivatedAt()).isEqualTo(T0.minusDays(4));
        assertThat(version.getStage()).isEqualTo(KnowledgeIngestionStage.FAILED);
        assertThat(version.getIngestionId()).isEqualTo("ingestion-7");
        assertThat(version.getIngestionStartedAt()).isEqualTo(T0.plusMinutes(10));
        assertThat(version.getChunkCount()).isEqualTo(40);
        assertThat(version.getEmbeddingModel()).isEqualTo("voyage-4-lite");
        assertThat(version.getEmbeddingDimensions()).isEqualTo(1024);
        assertThat(version.getIndexedAt()).isEqualTo(T0.plusMinutes(11));
        assertThat(version.getActivatedAt()).isEqualTo(T0.plusMinutes(12));
        assertThat(version.getActivatedByUserId()).isEqualTo("admin-3");
        assertThat(version.getFailureReason()).isEqualTo(KnowledgeIngestionFailure.EXTRACTION_FAILED);
        assertThat(version.getExtractionFailureReason()).isEqualTo(DocumentExtractionException.Reason.ENCRYPTED);
        assertThat(version.getFailedAt()).isEqualTo(T0.plusMinutes(13));
    }

    @Test
    void noArgConstructorsLeaveEveryFieldUnset() throws Exception {
        for (Object empty : List.of(new KnowledgeDocument(), new KnowledgeDocumentVersion(), new KnowledgeVersionHistoryEntry())) {
            for (Field field : fields(empty.getClass())) {
                field.setAccessible(true);
                assertThat(field.get(empty)).as(empty.getClass().getSimpleName() + "." + field.getName()).isNull();
            }
        }
    }

    @Test
    void legacyDocumentWithoutHistoryMapsToAnEmptyHistoryAndKeepsItsOptimisticLock() {
        org.bson.Document legacy = new org.bson.Document("_id", "kd-legacy")
                .append("organizationId", "org-1")
                .append("status", "LIVE")
                .append("version", 7L)
                .append("activeVersion", new org.bson.Document("sourceDocumentId", "doc-1")
                        .append("stage", "INDEXED").append("ingestionId", "ingestion-1"));

        KnowledgeDocument mapped = converter().read(KnowledgeDocument.class, legacy);

        assertThat(mapped.getVersionHistory()).isEmpty();
        assertThat(mapped.hasVersionHistoryCapacity()).isTrue();
        assertThat(mapped.getVersion()).isEqualTo(7L);
        assertThat(mapped.getActiveVersion().getIngestionId()).isEqualTo("ingestion-1");
    }

    @Test
    void historyUsesDefensiveListsAndSignalsTheOneHundredEntryLimit() {
        KnowledgeVersionHistoryEntry entry = KnowledgeVersionHistoryEntry.builder()
                .version(KnowledgeDocumentVersion.builder().ingestionId("ingestion-old").build())
                .supersededAt(T0)
                .supersededByIngestionId("ingestion-new")
                .supersededByUserId("admin-1")
                .build();
        List<KnowledgeVersionHistoryEntry> supplied = new ArrayList<>();
        supplied.add(entry);
        KnowledgeDocument document = new KnowledgeDocument();
        document.setVersionHistory(supplied);
        supplied.clear();

        assertThat(document.getVersionHistory()).containsExactly(entry);
        assertThat(document.hasVersionHistoryCapacity()).isTrue();

        List<KnowledgeVersionHistoryEntry> atLimit = new ArrayList<>();
        for (int index = 0; index < KnowledgeDocument.MAX_VERSION_HISTORY_ENTRIES; index++) {
            atLimit.add(entry);
        }
        document.setVersionHistory(atLimit);

        assertThat(KnowledgeDocument.MAX_VERSION_HISTORY_ENTRIES).isEqualTo(100);
        assertThat(document.getVersionHistory()).hasSize(100);
        assertThat(document.hasVersionHistoryCapacity()).isFalse();
    }

    // --- Enums ----------------------------------------------------------------------------------

    @Test
    void entryStatusIsLiveOrRevoked() {
        assertThat(KnowledgeDocumentStatus.values())
                .containsExactly(KnowledgeDocumentStatus.LIVE, KnowledgeDocumentStatus.REVOKED);
    }

    @Test
    void ingestionStagesIncludeIndexedAsTheSuccessfulStage() {
        assertThat(KnowledgeIngestionStage.values()).containsExactly(
                KnowledgeIngestionStage.APPROVED,
                KnowledgeIngestionStage.NEEDS_REVIEW,
                KnowledgeIngestionStage.INGESTING,
                KnowledgeIngestionStage.INDEXED,
                KnowledgeIngestionStage.FAILED);
    }

    @Test
    void ingestionFailuresAreTheApprovedReasonCodes() {
        assertThat(KnowledgeIngestionFailure.values()).containsExactly(
                KnowledgeIngestionFailure.SOURCE_DOCUMENT_INELIGIBLE,
                KnowledgeIngestionFailure.STORAGE_UNAVAILABLE,
                KnowledgeIngestionFailure.CONTENT_CHANGED,
                KnowledgeIngestionFailure.EXTRACTION_FAILED,
                KnowledgeIngestionFailure.TOO_MANY_CHUNKS,
                KnowledgeIngestionFailure.EMBEDDING_CONFIGURATION,
                KnowledgeIngestionFailure.EMBEDDING_REQUEST_REJECTED,
                KnowledgeIngestionFailure.EMBEDDING_UNAVAILABLE,
                KnowledgeIngestionFailure.INVALID_EMBEDDING,
                KnowledgeIngestionFailure.CHUNK_WRITE_FAILED,
                KnowledgeIngestionFailure.ACTIVATION_CONFLICT,
                KnowledgeIngestionFailure.CONTENT_REVIEW_REJECTED,
                // Recorded when the organization's Voyage approval for the content is missing at embedding time.
                KnowledgeIngestionFailure.EXTERNAL_PROCESSING_NOT_APPROVED);
    }

    @Test
    void companyPolicyIsAnExistingDocumentTypeAndEnumsArePersistedByName() {
        // Production already carries COMPANY_POLICY ("retained for compatibility"); documents are stored by enum NAME,
        // so its position in the declaration does not matter and no existing value was renamed or removed.
        assertThat(DocumentType.values()).contains(DocumentType.TIMESHEET_SUPPORTING_DOCUMENT, DocumentType.REQUESTED_DOCUMENT,
                DocumentType.EMPLOYEE_PROFILE_DOCUMENT, DocumentType.TIMESHEET, DocumentType.PAYSTUB, DocumentType.REPORT,
                DocumentType.RESUME, DocumentType.PROFILE_IMAGE, DocumentType.ORGANIZATION_LOGO, DocumentType.COMPANY_POLICY);
        assertThat(DocumentType.valueOf("COMPANY_POLICY")).isEqualTo(DocumentType.COMPANY_POLICY);
    }

    // --- Helpers --------------------------------------------------------------------------------

    private static List<Field> fields(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()) && !field.isSynthetic())
                .toList();
    }

    private static List<String> fieldNames(Class<?> type) {
        return fields(type).stream().map(Field::getName).toList();
    }

    private static Class<?> type(Class<?> owner, String fieldName) throws NoSuchFieldException {
        return owner.getDeclaredField(fieldName).getType();
    }

    private static List<Annotation> indexAnnotations(Annotation[] annotations) {
        return Arrays.stream(annotations)
                .filter(annotation -> annotation.annotationType().getPackageName()
                        .equals("org.springframework.data.mongodb.core.index"))
                .toList();
    }

    /**
     * Built the way Spring Boot builds the application's converter from its MongoCustomConversions
     * (see MongoConversionConfig): the conversions' simple types, which include java.time values such
     * as LocalDateTime, are registered on the mapping context so they are stored as values rather than
     * mapped as entities.
     */
    private static MappingMongoConverter converter() {
        MongoCustomConversions conversions = MongoCustomConversions.create(adapter -> { });
        MongoMappingContext context = new MongoMappingContext();
        context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        context.afterPropertiesSet();
        MappingMongoConverter converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
        converter.setCustomConversions(conversions);
        converter.afterPropertiesSet();
        return converter;
    }
}
