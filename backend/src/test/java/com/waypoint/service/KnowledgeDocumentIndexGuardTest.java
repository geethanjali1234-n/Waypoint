package com.waypoint.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.waypoint.entity.KnowledgeDocument;
import com.waypoint.exception.BusinessException;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.UncategorizedMongoDbException;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Index definitions are given as listIndexes documents and parsed with Spring Data's own
 * IndexInfo.indexInfoOf, as DefaultIndexOperations does for real server responses. No database is
 * contacted.
 */
class KnowledgeDocumentIndexGuardTest {

    private static final Document ID_INDEX = new Document("v", 2).append("key", new Document("_id", 1)).append("name", "_id_");

    private final MongoOperations mongoOperations = mock(MongoOperations.class);
    private final IndexOperations indexOperations = mock(IndexOperations.class);
    private final KnowledgeDocumentIndexGuard guard = new KnowledgeDocumentIndexGuard(mongoOperations);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(KnowledgeDocumentIndexGuard.class);

    @BeforeEach
    void setUp() {
        when(mongoOperations.indexOps(KnowledgeDocument.class)).thenReturn(indexOperations);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    // --- Accepted definitions -------------------------------------------------------------------

    @Test
    void acceptsTheRequiredIndexAlongsideTheIdIndex() {
        serverReports(ID_INDEX, required());

        assertThatCode(guard::requireUniqueLiveSourceIndex).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @MethodSource("equivalentDefinitions")
    void acceptsEquivalentDefinitions(String description, Document index) {
        serverReports(ID_INDEX, index);

        assertThatCode(guard::requireUniqueLiveSourceIndex).as(description).doesNotThrowAnyException();
    }

    static Stream<Arguments> equivalentDefinitions() {
        return Stream.of(
                Arguments.of("any index name", required().append("name", "organizationId_1_sourceDocumentIds_1")),
                Arguments.of("$eq form of the filter", required()
                        .append("partialFilterExpression", new Document("status", new Document("$eq", "LIVE")))),
                Arguments.of("double key values, as mongosh writes them", required()
                        .append("key", new Document("organizationId", 1.0).append("sourceDocumentIds", 1.0))),
                Arguments.of("hidden index, which still enforces uniqueness", required().append("hidden", true)));
    }

    @Test
    void acceptsTheRequiredIndexAmongUnrelatedOnes() {
        serverReports(ID_INDEX,
                new Document("v", 2).append("key", new Document("organizationId", 1).append("status", 1)).append("name", "org_status"),
                required(),
                new Document("v", 2).append("key", new Document("createdAt", -1)).append("name", "createdAt_-1"));

        assertThatCode(guard::requireUniqueLiveSourceIndex).doesNotThrowAnyException();
    }

    // --- Rejected definitions -------------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("incorrectDefinitions")
    void rejectsIncorrectDefinitions(String description, Document index) {
        serverReports(ID_INDEX, index);

        assertThatThrownBy(guard::requireUniqueLiveSourceIndex)
                .as(description)
                .isInstanceOf(BusinessException.class)
                .hasMessage(KnowledgeDocumentIndexGuard.UNAVAILABLE);
    }

    static Stream<Arguments> incorrectDefinitions() {
        return Stream.of(
                Arguments.of("not unique", without(required(), "unique")),
                Arguments.of("unique: false", required().append("unique", false)),
                Arguments.of("keys reversed", required()
                        .append("key", new Document("sourceDocumentIds", 1).append("organizationId", 1))),
                Arguments.of("organizationId descending", required()
                        .append("key", new Document("organizationId", -1).append("sourceDocumentIds", 1))),
                Arguments.of("sourceDocumentIds descending", required()
                        .append("key", new Document("organizationId", 1).append("sourceDocumentIds", -1))),
                Arguments.of("extra key", required().append("key", new Document("organizationId", 1)
                        .append("sourceDocumentIds", 1).append("status", 1))),
                Arguments.of("organizationId only", required().append("key", new Document("organizationId", 1))),
                Arguments.of("sourceDocumentIds only", required().append("key", new Document("sourceDocumentIds", 1))),
                Arguments.of("hashed sourceDocumentIds", required()
                        .append("key", new Document("organizationId", 1).append("sourceDocumentIds", "hashed"))),
                Arguments.of("wrong field name", required()
                        .append("key", new Document("organizationId", 1).append("sourceDocumentId", 1))),
                Arguments.of("no partial filter", without(required(), "partialFilterExpression")),
                Arguments.of("empty partial filter", required().append("partialFilterExpression", new Document())),
                Arguments.of("filter on REVOKED", required().append("partialFilterExpression", new Document("status", "REVOKED"))),
                Arguments.of("filter on lowercase live", required().append("partialFilterExpression", new Document("status", "live"))),
                Arguments.of("filter on another field", required().append("partialFilterExpression", new Document("state", "LIVE"))),
                Arguments.of("filter broadened with $in", required().append("partialFilterExpression",
                        new Document("status", new Document("$in", List.of("LIVE", "REVOKED"))))),
                Arguments.of("filter on status existing", required().append("partialFilterExpression",
                        new Document("status", new Document("$exists", true)))),
                Arguments.of("filter narrowed by an extra condition", required().append("partialFilterExpression",
                        new Document("status", "LIVE").append("title", new Document("$exists", true)))),
                Arguments.of("$eq combined with another operator", required().append("partialFilterExpression",
                        new Document("status", new Document("$eq", "LIVE").append("$type", "string")))),
                Arguments.of("sparse", required().append("sparse", true)));
    }

    @Test
    void rejectsWhenOnlyTheIdIndexExists() {
        serverReports(ID_INDEX);

        assertThatThrownBy(guard::requireUniqueLiveSourceIndex).isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsWhenTheCollectionHasNoIndexesOrDoesNotExist() {
        // listIndexes on a missing collection yields no indexes.
        when(indexOperations.getIndexInfo()).thenReturn(List.of());

        assertThatThrownBy(guard::requireUniqueLiveSourceIndex).isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsWhenNoIndexListIsReturned() {
        when(indexOperations.getIndexInfo()).thenReturn(null);

        assertThatThrownBy(guard::requireUniqueLiveSourceIndex).isInstanceOf(BusinessException.class);
    }

    // --- Cannot be checked ----------------------------------------------------------------------

    @Test
    void failsClosedWhenIndexesCannotBeReadAndLogsNoDriverDetails() {
        when(indexOperations.getIndexInfo()).thenThrow(new DataAccessResourceFailureException(
                "Timed out connecting to mongodb+srv://waypoint:s3cret@cluster.example.net"));

        assertThatThrownBy(guard::requireUniqueLiveSourceIndex)
                .isInstanceOf(BusinessException.class)
                .hasMessage(KnowledgeDocumentIndexGuard.UNAVAILABLE)
                .hasNoCause();
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage())
                    .contains("DataAccessResourceFailureException")
                    .doesNotContain("s3cret")
                    .doesNotContain("mongodb+srv");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test
    void failsClosedWhenIndexOperationsCannotBeObtained() {
        when(mongoOperations.indexOps(KnowledgeDocument.class))
                .thenThrow(new UncategorizedMongoDbException("not authorized on waypoint", null));

        assertThatThrownBy(guard::requireUniqueLiveSourceIndex).isInstanceOf(BusinessException.class);
    }

    // --- Read-only and re-checked ---------------------------------------------------------------

    @Test
    void onlyListsIndexesAndNeverCreatesChangesOrDropsThem() {
        serverReports(ID_INDEX, required());
        guard.requireUniqueLiveSourceIndex();
        when(indexOperations.getIndexInfo()).thenReturn(infos(ID_INDEX));
        assertThatThrownBy(guard::requireUniqueLiveSourceIndex).isInstanceOf(BusinessException.class);

        verify(mongoOperations, times(2)).indexOps(KnowledgeDocument.class);
        verify(indexOperations, times(2)).getIndexInfo();
        verifyNoMoreInteractions(mongoOperations, indexOperations);
    }

    @Test
    void recognisesADroppedIndexOnTheNextCheck() {
        serverReports(ID_INDEX, required());
        assertThatCode(guard::requireUniqueLiveSourceIndex).doesNotThrowAnyException();

        when(indexOperations.getIndexInfo()).thenReturn(infos(ID_INDEX));

        assertThatThrownBy(guard::requireUniqueLiveSourceIndex).isInstanceOf(BusinessException.class);
    }

    @Test
    void exposesTheDefinitionForTheFutureSetupStepWithoutSharingMutableState() {
        assertThat(KnowledgeDocumentIndexGuard.INDEX_NAME).isEqualTo("uniq_live_source_document");
        assertThat(KnowledgeDocumentIndexGuard.INDEX_KEYS).containsExactly("organizationId", "sourceDocumentIds");

        Document filter = KnowledgeDocumentIndexGuard.partialFilterExpression();
        assertThat(filter).isEqualTo(new Document("status", "LIVE"));
        filter.put("status", "REVOKED");
        assertThat(KnowledgeDocumentIndexGuard.partialFilterExpression()).isEqualTo(new Document("status", "LIVE"));
    }

    // --- Helpers --------------------------------------------------------------------------------

    /** The index as the approved setup step will create it, in listIndexes form. */
    private static Document required() {
        return new Document("v", 2)
                .append("key", new Document("organizationId", 1).append("sourceDocumentIds", 1))
                .append("name", KnowledgeDocumentIndexGuard.INDEX_NAME)
                .append("unique", true)
                .append("partialFilterExpression", new Document("status", "LIVE"));
    }

    private static Document without(Document index, String key) {
        index.remove(key);
        return index;
    }

    private void serverReports(Document... indexes) {
        when(indexOperations.getIndexInfo()).thenReturn(infos(indexes));
    }

    private static List<IndexInfo> infos(Document... indexes) {
        return Arrays.stream(indexes).map(IndexInfo::indexInfoOf).toList();
    }
}
