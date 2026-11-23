package com.waypoint.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.waypoint.entity.KnowledgeDocument;
import com.waypoint.entity.KnowledgeDocumentVersion;
import com.waypoint.enums.ChunkAccessLevel;
import com.waypoint.enums.DocumentType;
import com.waypoint.enums.KnowledgeDocumentStatus;
import com.waypoint.enums.KnowledgeIngestionFailure;
import com.waypoint.enums.KnowledgeIngestionStage;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.exception.UnauthorizedException;
import com.waypoint.security.TestActors;
import com.waypoint.security.VerifiedMyHrActor;
import com.waypoint.service.KnowledgeRetrievalService.RetrievalResult;
import com.waypoint.service.KnowledgeRetrievalService.RetrievalStatus;
import com.waypoint.service.KnowledgeRetrievalService.RetrievedChunk;
import com.waypoint.setup.KnowledgeChunkVectorSearch;
import com.waypoint.setup.KnowledgeChunkVectorSearch.CandidateChunk;
import com.waypoint.setup.KnowledgeChunkVectorSearch.SearchRequest;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Pure Mockito tests: no Spring context, MongoDB, Atlas Vector Search or Voyage call is made. */
@ExtendWith(MockitoExtension.class)
class KnowledgeRetrievalServiceTest {

    private static final String ORG = "org-acme-77";
    private static final String OTHER_ORG = "org-other-12";
    private static final String DOCUMENT = "knowledge-77";
    private static final String SOURCE = "stored-policy-77";
    private static final String PENDING_SOURCE = "stored-policy-78";
    private static final String ACTIVE_INGESTION = "ingestion-active-77";
    private static final String PENDING_INGESTION = "ingestion-pending-78";
    private static final String OLD_INGESTION = "ingestion-old-22";
    private static final String MODEL = "voyage-4-lite";
    private static final String QUESTION = "How many vacation days do new employees get?";
    private static final String POLICY_TEXT = "Confidential: new employees accrue 1.5 vacation days per month.";
    private static final String SHA = "a3f1b2c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 19, 12, 0);
    private static final LocalDateTime ACTIVATED_AT = NOW.minusDays(1);

    @Mock EmbeddingService embeddingService;
    @Mock KnowledgeChunkVectorSearch vectorSearch;
    @Mock MongoOperations mongoOperations;

    private final VerifiedMyHrActor employee = TestActors.actor("employee-71", ORG, UserRole.EMPLOYEE);
    private final VerifiedMyHrActor hr = TestActors.hr("hr-71", ORG);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(KnowledgeRetrievalService.class);

    @BeforeEach
    void setUp() {
        logger.addAppender(logs);
        logs.start();
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    // --- Authorization and input ----------------------------------------------------------------

    @Test
    void missingOrUnsupportedActorsAreRefusedBeforeAnyEmbeddingOrSearch() {
        KnowledgeRetrievalService service = service(true);

        assertThatThrownBy(() -> service.retrieve(null, QUESTION))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage(KnowledgeRetrievalService.AUTHENTICATION_REQUIRED);
        for (UserRole role : List.of(UserRole.JOB_SEEKER, UserRole.EMPLOYER, UserRole.WAYPOINT_ADMIN)) {
            assertThatThrownBy(() -> service.retrieve(TestActors.actor("user-1", ORG, role), QUESTION))
                    .as(role.name())
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage(KnowledgeRetrievalService.RETRIEVAL_NOT_PERMITTED);
        }
        verifyNoInteractions(embeddingService, vectorSearch, mongoOperations);
    }

    @Test
    void everyRoleMapsToAnExplicitAccessLevelSetOrIsRefused() {
        assertThat(KnowledgeRetrievalService.allowedAccessLevels(employee))
                .containsExactly(ChunkAccessLevel.COMPANY_WIDE);
        assertThat(KnowledgeRetrievalService.allowedAccessLevels(hr))
                .containsExactlyInAnyOrder(ChunkAccessLevel.COMPANY_WIDE, ChunkAccessLevel.HR_ONLY);
        assertThat(KnowledgeRetrievalService.allowedAccessLevels(TestActors.companyAdmin("admin-1", ORG)))
                .containsExactlyInAnyOrder(ChunkAccessLevel.COMPANY_WIDE, ChunkAccessLevel.HR_ONLY);
        for (UserRole role : UserRole.values()) {
            VerifiedMyHrActor actor = TestActors.actor("user-1", ORG, role);
            try {
                assertThat(KnowledgeRetrievalService.allowedAccessLevels(actor)).as(role.name())
                        .doesNotContain(ChunkAccessLevel.EMPLOYEE_PRIVATE);
            } catch (ForbiddenException refused) {
                assertThat(role).isNotIn(UserRole.EMPLOYEE, UserRole.HR, UserRole.COMPANY_ADMIN);
            }
        }
    }

    @Test
    void disabledRetrievalReturnsNoKnowledgeWithoutEmbeddingOrSearching() {
        RetrievalResult result = service(false).retrieve(employee, QUESTION);

        assertThat(result.status()).isEqualTo(RetrievalStatus.DISABLED);
        assertThat(result.chunks()).isEmpty();
        assertThat(result.hasKnowledge()).isFalse();
        verifyNoInteractions(embeddingService, vectorSearch, mongoOperations);
    }

    @Test
    void blankOrOverlongQuestionsAreRejectedBeforeEmbedding() {
        KnowledgeRetrievalService service = service(true);

        for (String blank : new String[]{null, "", "   \n\t"}) {
            assertThatThrownBy(() -> service.retrieve(employee, blank))
                    .isInstanceOf(BusinessException.class).hasMessage(KnowledgeRetrievalService.QUESTION_REQUIRED);
        }
        assertThatThrownBy(() -> service.retrieve(employee, "a".repeat(KnowledgeRetrievalService.MAX_QUESTION_CHARS + 1)))
                .isInstanceOf(BusinessException.class).hasMessage(KnowledgeRetrievalService.QUESTION_TOO_LONG);
        verifyNoInteractions(embeddingService, vectorSearch, mongoOperations);
    }

    // --- Search scope ---------------------------------------------------------------------------

    @Test
    void searchScopeComesFromTheActorEmployeesSeeCompanyWideAndManagersAlsoSeeHrOnly() {
        givenEmbedding();
        when(vectorSearch.search(any(SearchRequest.class))).thenReturn(List.of());
        KnowledgeRetrievalService service = service(true);

        Map<VerifiedMyHrActor, Set<ChunkAccessLevel>> expected = Map.of(
                employee, Set.of(ChunkAccessLevel.COMPANY_WIDE),
                hr, Set.of(ChunkAccessLevel.COMPANY_WIDE, ChunkAccessLevel.HR_ONLY),
                TestActors.companyAdmin("admin-1", ORG), Set.of(ChunkAccessLevel.COMPANY_WIDE, ChunkAccessLevel.HR_ONLY));
        for (Map.Entry<VerifiedMyHrActor, Set<ChunkAccessLevel>> scope : expected.entrySet()) {
            RetrievalResult result = service.retrieve(scope.getKey(), "  " + QUESTION + "\n");

            assertThat(result.status()).isEqualTo(RetrievalStatus.NO_RELEVANT_KNOWLEDGE);
            ArgumentCaptor<SearchRequest> request = ArgumentCaptor.forClass(SearchRequest.class);
            verify(vectorSearch, org.mockito.Mockito.atLeastOnce()).search(request.capture());
            SearchRequest sent = request.getValue();
            assertThat(sent.organizationId()).isEqualTo(ORG);
            assertThat(sent.accessLevels()).as(scope.getKey().role().name()).isEqualTo(scope.getValue());
            assertThat(sent.limit()).isEqualTo(5 * KnowledgeRetrievalService.CANDIDATE_OVERSAMPLING);
            assertThat(sent.numCandidates()).isEqualTo(sent.limit() * KnowledgeRetrievalService.NUM_CANDIDATES_PER_RESULT);
            assertThat(sent.queryVector()).hasSize(1024);
        }
        verify(embeddingService, org.mockito.Mockito.times(3)).embed(QUESTION, EmbeddingService.InputType.QUERY);
        verifyNoInteractions(mongoOperations);
    }

    @Test
    void liveEntryLookupIsOrganizationScopedLiveOnlyBoundedAndOmitsHistory() {
        givenEmbedding();
        when(vectorSearch.search(any(SearchRequest.class))).thenReturn(List.of(
                new Row().index(0).build(), new Row().index(1).build(), new Row().document("knowledge-88").build()));
        when(mongoOperations.find(any(Query.class), eq(KnowledgeDocument.class))).thenReturn(List.of());

        service(true).retrieve(employee, QUESTION);

        ArgumentCaptor<Query> captured = ArgumentCaptor.forClass(Query.class);
        verify(mongoOperations).find(captured.capture(), eq(KnowledgeDocument.class));
        Query query = captured.getValue();
        Document criteria = query.getQueryObject();
        assertThat(criteria.get("organizationId")).isEqualTo(ORG);
        assertThat(criteria.get("status")).isEqualTo(KnowledgeDocumentStatus.LIVE);
        List<Object> ids = new ArrayList<>((Collection<?>) ((Document) criteria.get("id")).get("$in"));
        assertThat(ids).containsExactlyInAnyOrder(DOCUMENT, "knowledge-88");
        assertThat(query.getFieldsObject().keySet())
                .containsExactlyInAnyOrder("organizationId", "title", "status", "activeVersion", "sourceDocumentIds");
        assertThat(query.getLimit()).isEqualTo(2);
        assertThat(query.getMeta().getMaxTimeMsec()).isEqualTo(KnowledgeRetrievalService.MAX_LOOKUP_TIME.toMillis());
    }

    // --- Version validation ---------------------------------------------------------------------

    @Test
    void returnsOnlyTheActiveVersionsChunksWithCitationMetadataInRelevanceOrder() {
        givenEmbedding();
        when(vectorSearch.search(any(SearchRequest.class))).thenReturn(List.of(
                new Row().index(1).score(0.80).build(),
                new Row().ingestion(PENDING_INGESTION).source(PENDING_SOURCE).score(0.97).build(),
                new Row().ingestion(OLD_INGESTION).score(0.95).build(),
                new Row().index(0).score(0.90).build()));
        givenLiveEntries(entry(active(), pending()));

        RetrievalResult result = service(true).retrieve(employee, QUESTION);

        assertThat(result.status()).isEqualTo(RetrievalStatus.RETRIEVED);
        assertThat(result.hasKnowledge()).isTrue();
        assertThat(result.candidateCount()).isEqualTo(4);
        assertThat(result.chunks()).extracting(RetrievedChunk::chunkId).containsExactly(
                KnowledgeIngestionService.chunkId(ACTIVE_INGESTION, 0),
                KnowledgeIngestionService.chunkId(ACTIVE_INGESTION, 1));
        RetrievedChunk top = result.chunks().getFirst();
        assertThat(top.knowledgeDocumentId()).isEqualTo(DOCUMENT);
        assertThat(top.title()).isEqualTo("Leave Policy");
        assertThat(top.sourceDocumentId()).isEqualTo(SOURCE);
        assertThat(top.sourceDocumentName()).isEqualTo("leave-policy-2026.pdf");
        assertThat(top.ingestionId()).isEqualTo(ACTIVE_INGESTION);
        assertThat(top.versionActivatedAt()).isEqualTo(ACTIVATED_AT);
        assertThat(top.accessLevel()).isEqualTo(ChunkAccessLevel.COMPANY_WIDE);
        assertThat(top.score()).isEqualTo(0.90);
        assertThat(top.text()).isEqualTo(POLICY_TEXT);
    }

    @Test
    void revokedCrossOrganizationAndHrOnlyKnowledgeNeverReachesAnEmployee() {
        givenEmbedding();
        when(vectorSearch.search(any(SearchRequest.class))).thenReturn(List.of(
                new Row().document("knowledge-revoked").build(),
                new Row().document("knowledge-returned-revoked").build(),
                new Row().organization(OTHER_ORG).build(),
                new Row().document("knowledge-other-org").build(),
                new Row().document("knowledge-hr").level(ChunkAccessLevel.HR_ONLY).build()));
        KnowledgeDocument revokedAnyway = entry(active(), null);
        revokedAnyway.setId("knowledge-returned-revoked");
        revokedAnyway.setStatus(KnowledgeDocumentStatus.REVOKED);
        KnowledgeDocument otherOrganization = entry(active(), null);
        otherOrganization.setId("knowledge-other-org");
        otherOrganization.setOrganizationId(OTHER_ORG);
        KnowledgeDocumentVersion hrOnly = active();
        hrOnly.setAccessLevel(ChunkAccessLevel.HR_ONLY);
        KnowledgeDocument hrEntry = entry(hrOnly, null);
        hrEntry.setId("knowledge-hr");
        // "knowledge-revoked" is absent, as the LIVE-only lookup would return it.
        givenLiveEntries(revokedAnyway, otherOrganization, hrEntry, entry(active(), null));

        RetrievalResult result = service(true).retrieve(employee, QUESTION);

        assertThat(result.status()).isEqualTo(RetrievalStatus.NO_RELEVANT_KNOWLEDGE);
        assertThat(result.chunks()).isEmpty();
        assertThat(result.candidateCount()).isEqualTo(5);
    }

    @Test
    void managersReceiveHrOnlyKnowledgeOnlyFromAnHrOnlyActiveVersion() {
        givenEmbedding();
        when(vectorSearch.search(any(SearchRequest.class))).thenReturn(List.of(
                new Row().level(ChunkAccessLevel.HR_ONLY).build()));
        KnowledgeDocumentVersion hrOnly = active();
        hrOnly.setAccessLevel(ChunkAccessLevel.HR_ONLY);
        givenLiveEntries(entry(hrOnly, null));

        RetrievalResult result = service(true).retrieve(hr, QUESTION);

        assertThat(result.status()).isEqualTo(RetrievalStatus.RETRIEVED);
        assertThat(result.chunks()).singleElement()
                .extracting(RetrievedChunk::accessLevel).isEqualTo(ChunkAccessLevel.HR_ONLY);
    }

    @Test
    void aCandidateServesOnlyWhenItExactlyMatchesItsLiveEntrysActivatedVersion() {
        Set<ChunkAccessLevel> managerLevels = Set.of(ChunkAccessLevel.COMPANY_WIDE, ChunkAccessLevel.HR_ONLY);
        assertThat(KnowledgeRetrievalService.isServable(new Row().build(), entry(active(), null), ORG, managerLevels, MODEL))
                .isTrue();

        List<Row> invalidCandidates = List.of(
                new Row().organization(OTHER_ORG),
                new Row().level(ChunkAccessLevel.HR_ONLY),
                new Row().level(ChunkAccessLevel.EMPLOYEE_PRIVATE),
                new Row().type(DocumentType.REPORT),
                new Row().model("voyage-3"),
                new Row().source("stored-other"),
                new Row().ingestion(PENDING_INGESTION),
                new Row().ingestion(OLD_INGESTION),
                new Row().index(3),
                new Row().id("forged-id"),
                new Row().document("knowledge-other"),
                new Row().text("x".repeat(KnowledgeRetrievalService.MAX_CHUNK_TEXT_CHARS + 1)));
        for (Row row : invalidCandidates) {
            assertThat(KnowledgeRetrievalService.isServable(row.build(), entry(active(), null), ORG, managerLevels, MODEL))
                    .as(row.build().toString())
                    .isFalse();
        }

        List<Consumer<KnowledgeDocumentVersion>> invalidActiveVersions = List.of(
                version -> version.setStage(KnowledgeIngestionStage.INGESTING),
                version -> version.setActivatedAt(null),
                version -> version.setActivatedByUserId(" "),
                version -> version.setDocumentType(DocumentType.REPORT),
                version -> version.setEmbeddingModel("voyage-3"),
                version -> version.setEmbeddingDimensions(768),
                version -> version.setChunkCount(null),
                version -> version.setFailureReason(KnowledgeIngestionFailure.EMBEDDING_UNAVAILABLE),
                version -> version.setFailedAt(NOW));
        for (Consumer<KnowledgeDocumentVersion> mutation : invalidActiveVersions) {
            KnowledgeDocumentVersion version = active();
            mutation.accept(version);
            assertThat(KnowledgeRetrievalService.isServable(new Row().build(), entry(version, null), ORG, managerLevels, MODEL))
                    .isFalse();
        }

        List<Consumer<KnowledgeDocument>> invalidEntries = List.of(
                entry -> entry.setStatus(KnowledgeDocumentStatus.REVOKED),
                entry -> entry.setOrganizationId(OTHER_ORG),
                entry -> entry.setActiveVersion(null),
                entry -> entry.setSourceDocumentIds(List.of("stored-other")),
                entry -> entry.setSourceDocumentIds(null));
        for (Consumer<KnowledgeDocument> mutation : invalidEntries) {
            KnowledgeDocument entry = entry(active(), null);
            mutation.accept(entry);
            assertThat(KnowledgeRetrievalService.isServable(new Row().build(), entry, ORG, managerLevels, MODEL)).isFalse();
        }
        assertThat(KnowledgeRetrievalService.isServable(new Row().build(), null, ORG, managerLevels, MODEL)).isFalse();
    }

    // --- Bounds ---------------------------------------------------------------------------------

    @Test
    void resultsAreBoundedByCountContextBudgetAndMinimumScore() {
        givenEmbedding();
        when(vectorSearch.search(any(SearchRequest.class))).thenReturn(List.of(
                new Row().index(0).score(0.95).text("a".repeat(600)).build(),
                new Row().index(1).score(0.90).text("b".repeat(600)).build(),
                new Row().index(2).score(0.70).text("c".repeat(100)).build()));
        givenLiveEntries(entry(active(), null));

        RetrievalResult countBound = new KnowledgeRetrievalService(embeddingService, vectorSearch, mongoOperations,
                true, 1, 8000, 0.0).retrieve(employee, QUESTION);
        RetrievalResult budgetBound = new KnowledgeRetrievalService(embeddingService, vectorSearch, mongoOperations,
                true, 5, 1000, 0.0).retrieve(employee, QUESTION);
        RetrievalResult scoreBound = new KnowledgeRetrievalService(embeddingService, vectorSearch, mongoOperations,
                true, 5, 8000, 0.8).retrieve(employee, QUESTION);

        assertThat(countBound.chunks()).extracting(RetrievedChunk::chunkIndex).containsExactly(0);
        // The second 600-character chunk would exceed the 1000-character budget; the smaller one fits.
        assertThat(budgetBound.chunks()).extracting(RetrievedChunk::chunkIndex).containsExactly(0, 2);
        assertThat(budgetBound.chunks().stream().mapToInt(chunk -> chunk.text().length()).sum()).isLessThanOrEqualTo(1000);
        assertThat(scoreBound.chunks()).extracting(RetrievedChunk::chunkIndex).containsExactly(0, 1);
    }

    @Test
    void aRelativeScoreMarginDropsCandidatesFarBelowTheBestMatchAndIsOffByDefault() {
        givenEmbedding();
        when(vectorSearch.search(any(SearchRequest.class))).thenReturn(List.of(
                new Row().index(0).score(0.86).text("best match").build(),
                new Row().index(1).score(0.83).text("close match").build(),
                new Row().index(2).score(0.74).text("another policy").build()));
        givenLiveEntries(entry(active(), null));

        RetrievalResult withMargin = new KnowledgeRetrievalService(embeddingService, vectorSearch, mongoOperations,
                true, 5, 8000, 0.0, 0.05).retrieve(employee, QUESTION);
        RetrievalResult withoutMargin = new KnowledgeRetrievalService(embeddingService, vectorSearch, mongoOperations,
                true, 5, 8000, 0.0).retrieve(employee, QUESTION);

        assertThat(withMargin.chunks()).extracting(RetrievedChunk::chunkIndex).containsExactly(0, 1);
        assertThat(withoutMargin.chunks()).extracting(RetrievedChunk::chunkIndex).containsExactly(0, 1, 2);
        assertThatThrownBy(() -> new KnowledgeRetrievalService(embeddingService, vectorSearch, mongoOperations,
                true, 5, 8000, 0.0, -0.1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unboundedConfigurationIsRejectedAtStartup() {
        assertThatThrownBy(() -> service(0, 8000, 0.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service(KnowledgeRetrievalService.MAX_RESULTS_LIMIT + 1, 8000, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service(5, KnowledgeRetrievalService.MIN_CONTEXT_CHARS - 1, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service(5, KnowledgeRetrievalService.MAX_CONTEXT_CHARS_LIMIT + 1, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        for (double score : new double[]{-0.1, 1.1, Double.NaN}) {
            assertThatThrownBy(() -> service(5, 8000, score)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(service(KnowledgeRetrievalService.MAX_RESULTS_LIMIT, KnowledgeRetrievalService.MAX_CONTEXT_CHARS_LIMIT, 1.0))
                .isNotNull();
    }

    // --- Fail closed ----------------------------------------------------------------------------

    @Test
    void embeddingFailuresAndMismatchedConfigurationReturnUnavailableWithoutSearching() {
        when(embeddingService.model()).thenReturn(MODEL);
        when(embeddingService.dimensions()).thenReturn(1024);
        KnowledgeRetrievalService service = service(true);
        List<List<Double>> badVectors = new ArrayList<>(List.of(
                List.of(0.1, 0.2),
                Collections.nCopies(1024, 0.0),
                withValue(Double.NaN),
                withValue(null)));
        badVectors.add(null);

        when(embeddingService.embed(anyString(), eq(EmbeddingService.InputType.QUERY)))
                .thenThrow(new EmbeddingService.EmbeddingUnavailableException());
        assertThat(service.retrieve(employee, QUESTION).status()).isEqualTo(RetrievalStatus.UNAVAILABLE);
        for (List<Double> vector : badVectors) {
            org.mockito.Mockito.reset(embeddingService);
            givenEmbedding(vector);
            assertThat(service.retrieve(employee, QUESTION).status()).isEqualTo(RetrievalStatus.UNAVAILABLE);
        }
        verifyNoInteractions(vectorSearch, mongoOperations);

        for (int dimensions : new int[]{768, 0}) {
            org.mockito.Mockito.reset(embeddingService);
            when(embeddingService.model()).thenReturn(MODEL);
            when(embeddingService.dimensions()).thenReturn(dimensions);
            assertThat(service.retrieve(employee, QUESTION).status()).isEqualTo(RetrievalStatus.UNAVAILABLE);
            verify(embeddingService, never()).embed(anyString(), any());
        }
        org.mockito.Mockito.reset(embeddingService);
        when(embeddingService.model()).thenReturn(" ");
        assertThat(service.retrieve(employee, QUESTION).status()).isEqualTo(RetrievalStatus.UNAVAILABLE);
        verify(embeddingService, never()).embed(anyString(), any());
    }

    @Test
    void aRateLimitedQueryEmbeddingIsUnavailableOnceWithoutRetryOrSearchAndLogsOnlyTheCause() {
        when(embeddingService.model()).thenReturn(MODEL);
        when(embeddingService.dimensions()).thenReturn(1024);
        when(embeddingService.embed(anyString(), eq(EmbeddingService.InputType.QUERY)))
                .thenThrow(new EmbeddingService.EmbeddingRateLimitedException());

        RetrievalResult result = service(true).retrieve(employee, QUESTION);

        assertThat(result.status()).isEqualTo(RetrievalStatus.UNAVAILABLE);
        assertThat(result.chunks()).isEmpty();
        verify(embeddingService, times(1)).embed(anyString(), any());
        verifyNoInteractions(vectorSearch, mongoOperations);
        String logText = logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", String::concat);
        assertThat(logText).contains("rate limited", "HTTP 429").doesNotContain(QUESTION, ORG, "employee-71");
    }

    @Test
    void searchAndLookupFailuresReturnUnavailableWithNoChunksAndNoLeakedDetails() {
        givenEmbedding();
        KnowledgeRetrievalService service = service(true);

        when(vectorSearch.search(any(SearchRequest.class)))
                .thenThrow(new IllegalStateException("mongodb+srv://user:s3cret@cluster.example " + POLICY_TEXT));
        RetrievalResult searchFailed = service.retrieve(employee, QUESTION);
        assertThat(searchFailed.status()).isEqualTo(RetrievalStatus.UNAVAILABLE);
        assertThat(searchFailed.chunks()).isEmpty();
        verifyNoInteractions(mongoOperations);

        org.mockito.Mockito.reset(vectorSearch);
        when(vectorSearch.search(any(SearchRequest.class))).thenReturn(List.of(new Row().build()));
        when(mongoOperations.find(any(Query.class), eq(KnowledgeDocument.class)))
                .thenThrow(new DataAccessResourceFailureException("mongodb+srv://user:s3cret@cluster.example"));
        RetrievalResult lookupFailed = service.retrieve(employee, QUESTION);
        assertThat(lookupFailed.status()).isEqualTo(RetrievalStatus.UNAVAILABLE);
        assertThat(lookupFailed.chunks()).isEmpty();
        assertThat(lookupFailed.hasKnowledge()).isFalse();

        String logText = logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", String::concat);
        assertThat(logText).isNotEmpty().doesNotContain("mongodb", "s3cret", POLICY_TEXT, QUESTION, ORG, "employee-71");
    }

    @Test
    void resultStringFormsKeepPolicyTextOutOfLogs() {
        givenEmbedding();
        when(vectorSearch.search(any(SearchRequest.class))).thenReturn(List.of(new Row().build()));
        givenLiveEntries(entry(active(), null));

        RetrievalResult result = service(true).retrieve(employee, QUESTION);

        assertThat(result.hasKnowledge()).isTrue();
        assertThat(result.toString()).doesNotContain(POLICY_TEXT);
        assertThat(result.chunks().getFirst().toString()).doesNotContain(POLICY_TEXT);
        String logText = logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", String::concat);
        assertThat(logText).doesNotContain(POLICY_TEXT, QUESTION);
    }

    // --- Stage timings --------------------------------------------------------------------------

    @Test
    void eachStageThatRanIsTimedAndAStageThatDidNotRunIsNotMeasured() {
        givenEmbedding();
        when(vectorSearch.search(any(SearchRequest.class))).thenReturn(List.of(new Row().build()));
        givenLiveEntries(entry(active(), null));

        KnowledgeRetrievalService.StageTimings retrieved = service(true).retrieve(employee, QUESTION).timings();

        assertThat(retrieved.embeddingNanos()).isGreaterThanOrEqualTo(0);
        assertThat(retrieved.vectorSearchNanos()).isGreaterThanOrEqualTo(0);
        assertThat(retrieved.validationNanos()).isGreaterThanOrEqualTo(0);

        org.mockito.Mockito.reset(vectorSearch);
        when(vectorSearch.search(any(SearchRequest.class))).thenReturn(List.of());
        KnowledgeRetrievalService.StageTimings noCandidates = service(true).retrieve(employee, QUESTION).timings();
        assertThat(noCandidates.vectorSearchNanos()).isGreaterThanOrEqualTo(0);
        assertThat(noCandidates.validationNanos()).isEqualTo(KnowledgeRetrievalService.StageTimings.NOT_MEASURED);

        assertThat(service(false).retrieve(employee, QUESTION).timings())
                .isEqualTo(KnowledgeRetrievalService.StageTimings.UNMEASURED);
        assertThat(new RetrievalResult(RetrievalStatus.RETRIEVED, List.of(), 0).timings())
                .isEqualTo(KnowledgeRetrievalService.StageTimings.UNMEASURED);
    }

    // --- Helpers --------------------------------------------------------------------------------

    private KnowledgeRetrievalService service(boolean enabled) {
        return new KnowledgeRetrievalService(embeddingService, vectorSearch, mongoOperations, enabled, 5, 8000, 0.0);
    }

    private KnowledgeRetrievalService service(int maxResults, int maxContextChars, double minScore) {
        return new KnowledgeRetrievalService(embeddingService, vectorSearch, mongoOperations, true,
                maxResults, maxContextChars, minScore);
    }

    private void givenEmbedding() {
        givenEmbedding(vector());
    }

    private void givenEmbedding(List<Double> vector) {
        when(embeddingService.model()).thenReturn(MODEL);
        when(embeddingService.dimensions()).thenReturn(1024);
        when(embeddingService.embed(QUESTION, EmbeddingService.InputType.QUERY)).thenReturn(vector);
    }

    private void givenLiveEntries(KnowledgeDocument... entries) {
        when(mongoOperations.find(any(Query.class), eq(KnowledgeDocument.class))).thenReturn(List.of(entries));
    }

    private static List<Double> vector() {
        return Collections.nCopies(1024, 0.031);
    }

    private static List<Double> withValue(Double value) {
        List<Double> vector = new ArrayList<>(vector());
        vector.set(11, value);
        return vector;
    }

    private static KnowledgeDocument entry(KnowledgeDocumentVersion active, KnowledgeDocumentVersion pending) {
        List<String> sourceIds = new ArrayList<>();
        if (active != null) {
            sourceIds.add(active.getSourceDocumentId());
        }
        if (pending != null) {
            sourceIds.add(pending.getSourceDocumentId());
        }
        return KnowledgeDocument.builder()
                .id(DOCUMENT)
                .organizationId(ORG)
                .title("Leave Policy")
                .status(KnowledgeDocumentStatus.LIVE)
                .activeVersion(active)
                .pendingVersion(pending)
                .sourceDocumentIds(sourceIds)
                .build();
    }

    private static KnowledgeDocumentVersion active() {
        return KnowledgeDocumentVersion.builder()
                .sourceDocumentId(SOURCE)
                .sourceDocumentName("leave-policy-2026.pdf")
                .documentType(DocumentType.COMPANY_POLICY)
                .accessLevel(ChunkAccessLevel.COMPANY_WIDE)
                .contentSha256(SHA)
                .approvedByUserId("hr-earlier")
                .approvedAt(NOW.minusDays(2))
                .pageCount(2)
                .pagesWithoutText(0)
                .stage(KnowledgeIngestionStage.INDEXED)
                .ingestionId(ACTIVE_INGESTION)
                .ingestionStartedAt(NOW.minusDays(2))
                .chunkCount(3)
                .embeddingModel(MODEL)
                .embeddingDimensions(1024)
                .indexedAt(NOW.minusDays(2).plusMinutes(5))
                .activatedAt(ACTIVATED_AT)
                .activatedByUserId("hr-earlier")
                .build();
    }

    private static KnowledgeDocumentVersion pending() {
        KnowledgeDocumentVersion pending = active();
        pending.setSourceDocumentId(PENDING_SOURCE);
        pending.setSourceDocumentName("leave-policy-2027-draft.pdf");
        pending.setIngestionId(PENDING_INGESTION);
        pending.setActivatedAt(null);
        pending.setActivatedByUserId(null);
        return pending;
    }

    /** A vector search hit that, by default, is chunk 0 of the active version. */
    private static final class Row {
        private String id;
        private String organizationId = ORG;
        private String knowledgeDocumentId = DOCUMENT;
        private String ingestionId = ACTIVE_INGESTION;
        private String sourceDocumentId = SOURCE;
        private DocumentType documentType = DocumentType.COMPANY_POLICY;
        private ChunkAccessLevel accessLevel = ChunkAccessLevel.COMPANY_WIDE;
        private int chunkIndex;
        private String text = POLICY_TEXT;
        private String model = MODEL;
        private double score = 0.9;

        Row id(String value) { id = value; return this; }
        Row organization(String value) { organizationId = value; return this; }
        Row document(String value) { knowledgeDocumentId = value; return this; }
        Row ingestion(String value) { ingestionId = value; return this; }
        Row source(String value) { sourceDocumentId = value; return this; }
        Row type(DocumentType value) { documentType = value; return this; }
        Row level(ChunkAccessLevel value) { accessLevel = value; return this; }
        Row index(int value) { chunkIndex = value; return this; }
        Row text(String value) { text = value; return this; }
        Row model(String value) { model = value; return this; }
        Row score(double value) { score = value; return this; }

        CandidateChunk build() {
            String chunkId = id != null ? id : KnowledgeIngestionService.chunkId(ingestionId, chunkIndex);
            return new CandidateChunk(chunkId, organizationId, knowledgeDocumentId, ingestionId, sourceDocumentId,
                    documentType, accessLevel, chunkIndex, text, model, score);
        }
    }
}
