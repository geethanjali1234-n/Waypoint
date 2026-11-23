package com.waypoint.repository;

import com.waypoint.entity.KnowledgeChunk;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.repository.query.parser.Part;
import org.springframework.data.repository.query.parser.PartTree;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Signature contract tests only — no database connection is made.
 * BSON serialization of KnowledgeChunk (including List<Double> embedding) requires a live
 * MongoDB instance and is deferred to a future integration test against an Atlas dev cluster.
 */
class KnowledgeChunkRepositoryTest {

    @Test
    void extendsMongoRepositoryWithKnowledgeChunk() {
        boolean extendsMongoRepository = Arrays.stream(KnowledgeChunkRepository.class.getInterfaces())
                .anyMatch(i -> i.equals(MongoRepository.class));
        assertThat(extendsMongoRepository).isTrue();
    }

    @Test
    void findByOrganizationIdAndSourceDocumentIdReturnsListAndRequiresBothKeys() throws NoSuchMethodException {
        Method method = KnowledgeChunkRepository.class.getDeclaredMethod(
                "findByOrganizationIdAndSourceDocumentId", String.class, String.class);
        assertThat(method.getReturnType()).isEqualTo(List.class);
        assertThat(method.getParameterCount()).isEqualTo(2);
    }

    @Test
    void findByOrganizationIdReturnsListAndRequiresSingleOrgKey() throws NoSuchMethodException {
        Method method = KnowledgeChunkRepository.class.getDeclaredMethod(
                "findByOrganizationId", String.class);
        assertThat(method.getReturnType()).isEqualTo(List.class);
        assertThat(method.getParameterCount()).isEqualTo(1);
    }

    @Test
    void ingestionIdDiscoveryIsOrganizationScopedAndProjectsOnlyTheId() throws NoSuchMethodException {
        Method method = KnowledgeChunkRepository.class.getDeclaredMethod(
                "findByOrganizationIdAndKnowledgeDocumentId", String.class, String.class);

        Query query = method.getAnnotation(Query.class);
        assertThat(method.getReturnType()).isEqualTo(List.class);
        assertThat(query.value()).contains("organizationId", "knowledgeDocumentId", "ingestionId");
        assertThat(query.fields()).contains("ingestionId").doesNotContain("chunkText", "embedding");
    }

    @Test
    void deleteByOrganizationIdAndSourceDocumentIdReturnsLongAndRequiresBothKeys() throws NoSuchMethodException {
        Method method = KnowledgeChunkRepository.class.getDeclaredMethod(
                "deleteByOrganizationIdAndSourceDocumentId", String.class, String.class);
        assertThat(method.getReturnType()).isEqualTo(long.class);
        assertThat(method.getParameterCount()).isEqualTo(2);
    }

    @Test
    void deleteMethodNameEncodesOrganizationScopeBeforeSourceDocumentId() {
        boolean hasOrgScopedDelete = Arrays.stream(KnowledgeChunkRepository.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().startsWith("deleteByOrganizationIdAnd") && m.getParameterCount() == 2);
        assertThat(hasOrgScopedDelete)
                .as("delete method must scope by organizationId before sourceDocumentId to prevent cross-tenant deletion")
                .isTrue();
    }

    @Test
    void noUnscopedDeleteBySourceDocumentIdExists() {
        boolean hasUnscopedDelete = Arrays.stream(KnowledgeChunkRepository.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals("deleteBySourceDocumentId"));
        assertThat(hasUnscopedDelete)
                .as("unscoped deleteBySourceDocumentId must not exist — a bug would silently delete another tenant's chunks")
                .isFalse();
    }

    @Test
    void versionCleanupMethodsAreOrganizationScopedAndReturnCounts() throws NoSuchMethodException {
        for (String name : List.of(
                "deleteByOrganizationIdAndKnowledgeDocumentIdAndIngestionIdNot",
                "deleteByOrganizationIdAndKnowledgeDocumentIdAndIngestionId",
                "countByOrganizationIdAndKnowledgeDocumentIdAndIngestionId")) {
            Method method = KnowledgeChunkRepository.class.getDeclaredMethod(name, String.class, String.class, String.class);
            assertThat(method.getReturnType()).as(name).isEqualTo(long.class);
        }
        Method revocation = KnowledgeChunkRepository.class.getDeclaredMethod(
                "deleteByOrganizationIdAndKnowledgeDocumentId", String.class, String.class);
        assertThat(revocation.getReturnType()).isEqualTo(long.class);
    }

    @Test
    void everyDeclaredQueryResolvesAgainstKnowledgeChunkAndFiltersByOrganizationFirst() {
        for (Method method : KnowledgeChunkRepository.class.getDeclaredMethods()) {
            PartTree tree = new PartTree(method.getName(), KnowledgeChunk.class);
            assertThat(properties(tree)).as(method.getName()).startsWith("organizationId");
        }
    }

    @Test
    void everyDeleteIsOrganizationScoped() {
        assertThat(Arrays.stream(KnowledgeChunkRepository.class.getDeclaredMethods())
                .map(Method::getName)
                .filter(name -> name.startsWith("delete")))
                .isNotEmpty()
                .allMatch(name -> name.startsWith("deleteByOrganizationIdAnd"));
    }

    @Test
    void activeVersionCleanupDeletesEveryIngestionExceptTheActiveOne() {
        PartTree tree = new PartTree(
                "deleteByOrganizationIdAndKnowledgeDocumentIdAndIngestionIdNot", KnowledgeChunk.class);

        assertThat(tree.isDelete()).isTrue();
        assertThat(properties(tree)).containsExactly("organizationId", "knowledgeDocumentId", "ingestionId");
        assertThat(types(tree)).containsExactly(
                Part.Type.SIMPLE_PROPERTY, Part.Type.SIMPLE_PROPERTY, Part.Type.NEGATING_SIMPLE_PROPERTY);
    }

    @Test
    void attemptCleanupAndCountMatchOneIngestionExactly() {
        PartTree delete = new PartTree(
                "deleteByOrganizationIdAndKnowledgeDocumentIdAndIngestionId", KnowledgeChunk.class);
        PartTree count = new PartTree(
                "countByOrganizationIdAndKnowledgeDocumentIdAndIngestionId", KnowledgeChunk.class);

        assertThat(delete.isDelete()).isTrue();
        assertThat(count.isCountProjection()).isTrue();
        for (PartTree tree : List.of(delete, count)) {
            assertThat(properties(tree)).containsExactly("organizationId", "knowledgeDocumentId", "ingestionId");
            assertThat(types(tree)).containsOnly(Part.Type.SIMPLE_PROPERTY);
        }
    }

    @Test
    void revocationCleanupRemovesTheWholeKnowledgeDocumentWithinTheOrganization() {
        PartTree tree = new PartTree("deleteByOrganizationIdAndKnowledgeDocumentId", KnowledgeChunk.class);

        assertThat(tree.isDelete()).isTrue();
        assertThat(properties(tree)).containsExactly("organizationId", "knowledgeDocumentId");
        assertThat(types(tree)).containsOnly(Part.Type.SIMPLE_PROPERTY);
    }

    private static List<String> properties(PartTree tree) {
        return tree.getParts().stream().map(part -> part.getProperty().toDotPath()).toList();
    }

    private static List<Part.Type> types(PartTree tree) {
        return tree.getParts().stream().map(Part::getType).toList();
    }
}
