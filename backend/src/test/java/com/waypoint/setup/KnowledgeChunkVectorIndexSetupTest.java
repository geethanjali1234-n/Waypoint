package com.waypoint.setup;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.ServerAddress;
import com.mongodb.client.ListCollectionNamesIterable;
import com.mongodb.client.ListSearchIndexesIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.SearchIndexModel;
import com.waypoint.entity.KnowledgeChunk;
import com.waypoint.setup.KnowledgeChunkVectorIndexSetup.Outcome;
import com.waypoint.setup.KnowledgeChunkVectorIndexSetup.SetupFailedException;
import com.waypoint.setup.KnowledgeChunkVectorIndexSetup.SetupReport;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.index.Indexed;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.ignoreStubs;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * All MongoDB interaction is mocked; no connection is made. verifyNoMoreInteractions(ignoreStubs(...))
 * proves that nothing beyond the stubbed reads (and any explicitly verified write) was called, so
 * inserts, updates, drops, and ordinary index creation would all fail these tests.
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeChunkVectorIndexSetupTest {

    @BeforeAll
    static void operatorApproval() {
        KnowledgeChunkVectorIndexSetup.approvedForTests = true; // the explicit switch is tested elsewhere
    }

    @AfterAll
    static void operatorApprovalCleared() {
        KnowledgeChunkVectorIndexSetup.approvedForTests = false;
    }

    private static final String VECTOR_FIELD =
            "{\"type\": \"vector\", \"path\": \"embedding\", \"numDimensions\": 1024, \"similarity\": \"cosine\"}";
    private static final String SERVER_ERROR = "Atlas Search index management is not available on this cluster";

    @Mock MongoDatabase database;
    @Mock MongoCollection<Document> collection;
    @Mock ListCollectionNamesIterable collectionNames;
    @Mock ListSearchIndexesIterable<Document> searchIndexes;
    @Captor ArgumentCaptor<List<SearchIndexModel>> indexModels;

    private KnowledgeChunkVectorIndexSetup setup;

    @BeforeEach
    void setUp() {
        setup = new KnowledgeChunkVectorIndexSetup(database);
    }

    // --- Expected configuration ---------------------------------------------------------------

    @Test
    void vectorIndexDefinitionMatchesTheAgreedSpecification() {
        Document expected = Document.parse("""
                {
                  "fields": [
                    {"type": "vector", "path": "embedding", "numDimensions": 1024, "similarity": "cosine"},
                    {"type": "filter", "path": "organizationId"},
                    {"type": "filter", "path": "accessLevel"},
                    {"type": "filter", "path": "ownerUserId"}
                  ]
                }
                """);

        assertThat(KnowledgeChunkVectorIndexSetup.vectorIndexDefinition()).isEqualTo(expected);
    }

    @Test
    void targetsMatchTheKnowledgeChunkEntity() throws Exception {
        assertThat(KnowledgeChunk.class.getAnnotation(org.springframework.data.mongodb.core.mapping.Document.class)
                .collection()).isEqualTo(KnowledgeChunkVectorIndexSetup.COLLECTION_NAME);
        assertThat(KnowledgeChunk.class.getDeclaredField(KnowledgeChunkVectorIndexSetup.EMBEDDING_PATH)).isNotNull();
        for (String filterPath : KnowledgeChunkVectorIndexSetup.FILTER_PATHS) {
            assertThat(KnowledgeChunk.class.getDeclaredField(filterPath)).isNotNull();
        }
    }

    @Test
    void embeddingFieldIsNeverAnOrdinaryIndex() throws Exception {
        assertThat(KnowledgeChunk.class.getDeclaredField(KnowledgeChunkVectorIndexSetup.EMBEDDING_PATH)
                .getAnnotation(Indexed.class))
                .as("a B-tree index on the embedding array is useless for vector search and expensive to maintain")
                .isNull();
    }

    // --- check(): read-only ---------------------------------------------------------------------

    @Test
    void checkReportsMissingCollectionWithoutWriting() {
        givenCollectionExists(false);

        SetupReport report = setup.check();

        assertThat(report.outcome()).isEqualTo(Outcome.COLLECTION_MISSING);
        assertThat(report.serverVersion()).isEqualTo("8.0.4");
        assertThat(report.successful()).isTrue();
        verify(database, never()).getCollection(anyString());
        verifyNoWritesBeyondVerified();
    }

    @Test
    void checkReportsMissingIndexWithoutWriting() {
        givenCollectionExists(true);
        givenExistingIndex(null);

        SetupReport report = setup.check();

        assertThat(report.outcome()).isEqualTo(Outcome.INDEX_MISSING);
        verifyNoWritesBeyondVerified();
    }

    @Test
    void checkReportsMatchingIndexAndItsBuildStatus() {
        givenCollectionExists(true);
        givenExistingIndex(vectorIndex(withAllFilters(VECTOR_FIELD)));

        SetupReport report = setup.check();

        assertThat(report.outcome()).isEqualTo(Outcome.INDEX_MATCHES);
        assertThat(report.indexStatus()).isEqualTo("READY");
        assertThat(report.indexQueryable()).isTrue();
        assertThat(report.mismatches()).isEmpty();
        verifyNoWritesBeyondVerified();
    }

    @Test
    void checkFailsSafelyWhenSearchIndexesCannotBeListed() {
        givenCollectionExists(true);
        when(database.getCollection("knowledge_chunks")).thenReturn(collection);
        when(collection.listSearchIndexes()).thenReturn(searchIndexes);
        when(searchIndexes.name("knowledge_chunk_vector_index")).thenReturn(searchIndexes);
        when(searchIndexes.first()).thenThrow(atlasRejection());

        assertThatThrownBy(() -> setup.check())
                .isInstanceOf(SetupFailedException.class)
                .hasMessageContaining("knowledge_chunks was not modified")
                .hasMessageNotContaining(SERVER_ERROR)
                .hasCauseInstanceOf(MongoCommandException.class);
        verifyNoWritesBeyondVerified();
    }

    @Test
    void connectionFailureIsWrappedWithoutDriverText() {
        String driverMessage = "Timed out after 30000 ms while waiting to connect to cluster0.abcde.mongodb.net";
        when(database.runCommand(new Document("buildInfo", 1))).thenThrow(new MongoTimeoutException(driverMessage));

        assertThatThrownBy(() -> setup.apply())
                .isInstanceOf(SetupFailedException.class)
                .hasMessage("Could not read the server version (buildInfo). Nothing was changed.")
                .hasCauseInstanceOf(MongoTimeoutException.class);
        verifyNoWritesBeyondVerified();
    }

    // --- apply(): creates missing resources only ------------------------------------------------

    @Test
    void applyCreatesCollectionThenVectorIndexWhenBothAreMissing() {
        givenCollectionExists(false);
        givenExistingIndex(null);

        SetupReport report = setup.apply();

        InOrder order = inOrder(database, collection);
        order.verify(database).createCollection("knowledge_chunks");
        order.verify(collection).createSearchIndexes(indexModels.capture());
        verifyNoWritesBeyondVerified();

        assertThat(indexModels.getValue()).hasSize(1);
        SearchIndexModel model = indexModels.getValue().get(0);
        assertThat(model.getName()).isEqualTo("knowledge_chunk_vector_index");
        assertThat(model.getType().toBsonValue()).isEqualTo(new BsonString("vectorSearch"));
        assertThat(model.getDefinition()).isEqualTo(KnowledgeChunkVectorIndexSetup.vectorIndexDefinition());
        assertThat(report.outcome()).isEqualTo(Outcome.INDEX_CREATED);
        assertThat(report.collectionCreated()).isTrue();
    }

    @Test
    void applyLeavesExistingCollectionAloneAndCreatesOnlyTheIndex() {
        givenCollectionExists(true);
        givenExistingIndex(null);

        SetupReport report = setup.apply();

        verify(database, never()).createCollection(anyString());
        verify(collection).createSearchIndexes(anyList());
        verifyNoWritesBeyondVerified();
        assertThat(report.outcome()).isEqualTo(Outcome.INDEX_CREATED);
        assertThat(report.collectionCreated()).isFalse();
    }

    @Test
    void applyDoesNothingWhenAMatchingIndexAlreadyExists() {
        givenCollectionExists(true);
        givenExistingIndex(vectorIndex(definition(VECTOR_FIELD, filter("ownerUserId"),
                filter("organizationId"), filter("accessLevel"))));

        SetupReport report = setup.apply();

        assertThat(report.outcome()).isEqualTo(Outcome.INDEX_MATCHES);
        verifyNoWritesBeyondVerified();
    }

    @Test
    void applyReportsMismatchAndLeavesTheExistingIndexUntouched() {
        givenCollectionExists(true);
        givenExistingIndex(vectorIndex(withAllFilters(VECTOR_FIELD.replace("1024", "1536"))));

        SetupReport report = setup.apply();

        assertThat(report.outcome()).isEqualTo(Outcome.INDEX_MISMATCH);
        assertThat(report.successful()).isFalse();
        assertThat(report.mismatches()).containsExactly("numDimensions is 1536, expected 1024");
        assertThat(report.describe()).anySatisfy(line -> assertThat(line).contains("left unchanged"));
        verifyNoWritesBeyondVerified();
    }

    @Test
    void applyFailsSafelyWhenAtlasRejectsIndexCreationOnANewCollection() {
        givenCollectionExists(false);
        givenExistingIndex(null);
        when(collection.createSearchIndexes(anyList())).thenThrow(atlasRejection());

        assertThatThrownBy(() -> setup.apply())
                .isInstanceOf(SetupFailedException.class)
                .hasMessageContaining("knowledge_chunks was created in this run and left empty")
                .hasMessageNotContaining(SERVER_ERROR);
        verify(database).createCollection("knowledge_chunks");
        verifyNoWritesBeyondVerified();
    }

    @Test
    void applyFailsSafelyWhenAtlasRejectsIndexCreationOnAnExistingCollection() {
        givenCollectionExists(true);
        givenExistingIndex(null);
        when(collection.createSearchIndexes(anyList())).thenThrow(atlasRejection());

        assertThatThrownBy(() -> setup.apply())
                .isInstanceOf(SetupFailedException.class)
                .hasMessageContaining("knowledge_chunks was not modified");
        verify(database, never()).createCollection(anyString());
        verifyNoWritesBeyondVerified();
    }

    @Test
    void applyStopsBeforeTheIndexWhenTheCollectionCannotBeCreated() {
        givenCollectionExists(false);
        doThrow(atlasRejection()).when(database).createCollection("knowledge_chunks");

        assertThatThrownBy(() -> setup.apply())
                .isInstanceOf(SetupFailedException.class)
                .hasMessage("Could not create collection knowledge_chunks. The vector index was not attempted.");
        verify(database).createCollection("knowledge_chunks");
        verify(database, never()).getCollection(anyString());
        verifyNoWritesBeyondVerified();
    }

    // --- Existing-index validation --------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"type\": \"vector\", \"path\": \"embedding\", \"numDimensions\": 1024.0, \"similarity\": \"cosine\"}",
            "{\"type\": \"vector\", \"path\": \"embedding\", \"numDimensions\": {\"$numberLong\": \"1024\"},"
                    + " \"similarity\": \"cosine\"}",
            "{\"type\": \"vector\", \"path\": \"embedding\", \"numDimensions\": 1024, \"similarity\": \"cosine\","
                    + " \"quantization\": \"none\"}"
    })
    void equivalentDefinitionsAreAccepted(String vectorField) {
        Document index = vectorIndex(definition(filter("accessLevel"), vectorField,
                filter("ownerUserId"), filter("organizationId")));

        assertThat(KnowledgeChunkVectorIndexSetup.definitionMismatches(index)).isEmpty();
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("mismatchedDefinitions")
    void reportsEachConfigurationMismatch(Document definition, String expectedMismatch) {
        List<String> mismatches = KnowledgeChunkVectorIndexSetup.definitionMismatches(vectorIndex(definition));

        assertThat(mismatches).contains(expectedMismatch);
    }

    static Stream<Arguments> mismatchedDefinitions() {
        return Stream.of(
                Arguments.of(withAllFilters(VECTOR_FIELD.replace("1024", "1536")),
                        "numDimensions is 1536, expected 1024"),
                Arguments.of(withAllFilters(VECTOR_FIELD.replace("cosine", "euclidean")),
                        "similarity is 'euclidean', expected 'cosine'"),
                Arguments.of(withAllFilters(VECTOR_FIELD.replace("\"embedding\"", "\"embeddings\"")),
                        "vector field path is 'embeddings', expected 'embedding'"),
                Arguments.of(withAllFilters(VECTOR_FIELD.replace("}", ", \"quantization\": \"scalar\"}")),
                        "quantization is 'scalar', expected none"),
                Arguments.of(withAllFilters(VECTOR_FIELD.replace("}", ", \"indexingMethod\": \"flat\"}")),
                        "vector field has unexpected option 'indexingMethod'"),
                Arguments.of(definition(VECTOR_FIELD, filter("organizationId"), filter("accessLevel")),
                        "missing filter field 'ownerUserId'"),
                Arguments.of(definition(VECTOR_FIELD, filter("organizationId"), filter("accessLevel"),
                                filter("ownerUserId"), filter("documentType")),
                        "unexpected filter field 'documentType'"),
                Arguments.of(definition(filter("organizationId"), filter("accessLevel"), filter("ownerUserId")),
                        "expected exactly one vector field, found 0"),
                Arguments.of(withAllFilters(VECTOR_FIELD + ", " + VECTOR_FIELD.replace("\"embedding\"", "\"other\"")),
                        "expected exactly one vector field, found 2"),
                Arguments.of(withAllFilters(VECTOR_FIELD).append("storedSource", true),
                        "unexpected index option 'storedSource'"));
    }

    @Test
    void searchTypeIndexWithTheSameNameIsAMismatch() {
        Document searchIndex = new Document("name", "knowledge_chunk_vector_index")
                .append("type", "search")
                .append("status", "READY")
                .append("latestDefinition", Document.parse("{\"mappings\": {\"dynamic\": true}}"));

        assertThat(KnowledgeChunkVectorIndexSetup.definitionMismatches(searchIndex)).contains(
                "index type is 'search', expected 'vectorSearch'",
                "unexpected index option 'mappings'",
                "index definition has no fields list");
    }

    @Test
    void missingTypeOrDefinitionIsAMismatch() {
        Document bareIndex = new Document("name", "knowledge_chunk_vector_index").append("status", "PENDING");

        assertThat(KnowledgeChunkVectorIndexSetup.definitionMismatches(bareIndex)).containsExactly(
                "index type is missing, expected 'vectorSearch'",
                "latestDefinition was not reported by the server");
    }

    // --- Helpers --------------------------------------------------------------------------------

    private void givenCollectionExists(boolean exists) {
        when(database.runCommand(new Document("buildInfo", 1))).thenReturn(new Document("version", "8.0.4"));
        when(database.listCollectionNames()).thenReturn(collectionNames);
        when(collectionNames.filter(new Document("name", "knowledge_chunks"))).thenReturn(collectionNames);
        when(collectionNames.first()).thenReturn(exists ? "knowledge_chunks" : null);
    }

    private void givenExistingIndex(Document index) {
        when(database.getCollection("knowledge_chunks")).thenReturn(collection);
        when(collection.listSearchIndexes()).thenReturn(searchIndexes);
        when(searchIndexes.name("knowledge_chunk_vector_index")).thenReturn(searchIndexes);
        when(searchIndexes.first()).thenReturn(index);
    }

    private void verifyNoWritesBeyondVerified() {
        verifyNoMoreInteractions(ignoreStubs(database, collection, collectionNames, searchIndexes));
    }

    private static Document vectorIndex(Document definition) {
        return new Document("name", "knowledge_chunk_vector_index")
                .append("type", "vectorSearch")
                .append("status", "READY")
                .append("queryable", true)
                .append("latestDefinition", definition);
    }

    private static Document definition(String... fields) {
        return Document.parse("{\"fields\": [" + String.join(", ", fields) + "]}");
    }

    private static Document withAllFilters(String vectorField) {
        return definition(vectorField, filter("organizationId"), filter("accessLevel"), filter("ownerUserId"));
    }

    private static String filter(String path) {
        return "{\"type\": \"filter\", \"path\": \"" + path + "\"}";
    }

    private static MongoCommandException atlasRejection() {
        BsonDocument response = BsonDocument.parse(
                "{\"ok\": 0, \"errmsg\": \"" + SERVER_ERROR + "\", \"code\": 8000, \"codeName\": \"AtlasError\"}");
        return new MongoCommandException(response, new ServerAddress("cluster0-shard-00-00.abcde.mongodb.net"));
    }
}
