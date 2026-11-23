package com.waypoint.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.waypoint.entity.KnowledgeDocument;
import com.waypoint.entity.KnowledgeDocumentVersion;
import com.waypoint.enums.ChunkAccessLevel;
import com.waypoint.enums.DocumentType;
import com.waypoint.enums.KnowledgeDocumentStatus;
import com.waypoint.enums.KnowledgeIngestionStage;
import com.waypoint.repository.ExternalProcessingApprovalRepository;
import com.waypoint.security.TestActors;
import com.waypoint.security.VerifiedMyHrActor;
import com.waypoint.setup.KnowledgeChunkVectorSearch;
import com.waypoint.setup.KnowledgeChunkVectorSearch.CandidateChunk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Offline cost checks for one grounded policy question, using the real retrieval service over mocked Voyage, Atlas and
 * MongoDB: every RAG stage runs exactly once, nothing is retrieved or embedded twice, the approval store is not
 * queried while external processing is switched off, and a second local generation (the HR-only citation retry) is
 * visible in the latency line. These are call counts, not timings; live latency needs a live benchmark.
 */
class MyHrRagStageEfficiencyTest {

    private static final String ORG = "org-perf-91";
    private static final String MODEL = "voyage-4-lite";
    private static final String QUESTION = "How many unused vacation days can employees carry forward?";

    private final EmbeddingService embedding = mock(EmbeddingService.class);
    private final KnowledgeChunkVectorSearch vectorSearch = mock(KnowledgeChunkVectorSearch.class);
    private final MongoOperations mongo = mock(MongoOperations.class);
    private final OllamaClient ollama = mock(OllamaClient.class);
    private final ClaudeChatClient claude = mock(ClaudeChatClient.class);
    private final ExternalProcessingApprovalRepository approvals = mock(ExternalProcessingApprovalRepository.class);
    private final Logger logger = (Logger) LoggerFactory.getLogger(MyHrChatService.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void setUp() {
        logs.start();
        logger.addAppender(logs);
        when(ollama.isLocalEndpoint()).thenReturn(true);
        org.mockito.Mockito.lenient().when(ollama.verifiedLocalModel()).thenReturn(true);
        when(embedding.model()).thenReturn(MODEL);
        when(embedding.dimensions()).thenReturn(1024);
        when(embedding.embed(anyString(), eq(EmbeddingService.InputType.QUERY)))
                .thenReturn(new ArrayList<>(Collections.nCopies(1024, 0.031)));
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        logs.stop();
    }

    @Test
    void eachStageRunsOnceForOneQuestionAndTheApprovalStoreIsNotQueriedWhileSwitchedOff() {
        givenPolicy(ChunkAccessLevel.COMPANY_WIDE, "Employees may carry forward up to 5 unused vacation days.");
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn("Up to 5 days [S1].");

        service().chat(TestActors.actor("employee-91", ORG, com.waypoint.enums.UserRole.EMPLOYEE), QUESTION, List.of());

        verify(embedding, times(1)).embed(anyString(), eq(EmbeddingService.InputType.QUERY));
        verify(vectorSearch, times(1)).search(any());
        verify(mongo, times(1)).find(any(Query.class), eq(KnowledgeDocument.class));
        verify(ollama, times(1)).chat(any(MyHrGroundedPrompt.class));
        verifyNoInteractions(approvals, claude);
        assertThat(latencyLine()).contains("citation-retry=false");
    }

    @Test
    void theHrOnlyCitationRetryIsTheOnlySecondGenerationAndIsReportedAsSuch() {
        givenPolicy(ChunkAccessLevel.HR_ONLY, "HR may approve up to 8 extra carry-forward days.");
        when(ollama.chat(any(MyHrGroundedPrompt.class)))
                .thenReturn("HR may approve up to 8 extra days.", "HR may approve up to 8 extra days [S1].");
        VerifiedMyHrActor hr = TestActors.hr("hr-91", ORG);

        service().chat(hr, QUESTION, List.of());

        verify(embedding, times(1)).embed(anyString(), any());
        verify(vectorSearch, times(1)).search(any());
        verify(ollama, times(2)).chat(any(MyHrGroundedPrompt.class));
        assertThat(latencyLine()).contains("citation-retry=true", "status=GROUNDED");
    }

    private MyHrChatService service() {
        KnowledgeRetrievalService retrieval = new KnowledgeRetrievalService(embedding, vectorSearch, mongo, true, 5,
                8000, 0.70);
        return new MyHrChatService(claude, ollama, mock(TimesheetService.class), mock(ProfileChangeRequestService.class),
                mock(EmployeeManagementService.class), mock(DocumentRequestService.class), "claude", retrieval, true,
                false, false, new RecordedExternalPolicyProcessingAuthorization(approvals));
    }

    private void givenPolicy(ChunkAccessLevel level, String text) {
        when(vectorSearch.search(any())).thenReturn(List.of(new CandidateChunk(
                KnowledgeIngestionService.chunkId("ing-1", 0), ORG, "kd-1", "ing-1", "source-1",
                DocumentType.COMPANY_POLICY, level, 0, text, MODEL, 0.91)));
        KnowledgeDocumentVersion active = KnowledgeDocumentVersion.builder().sourceDocumentId("source-1")
                .sourceDocumentName("policy.pdf").documentType(DocumentType.COMPANY_POLICY).accessLevel(level)
                .stage(KnowledgeIngestionStage.INDEXED).ingestionId("ing-1").chunkCount(1).embeddingModel(MODEL)
                .embeddingDimensions(1024).activatedAt(LocalDateTime.of(2026, 10, 1, 9, 0))
                .activatedByUserId("hr-earlier").build();
        when(mongo.find(any(Query.class), eq(KnowledgeDocument.class))).thenReturn(List.of(KnowledgeDocument.builder()
                .id("kd-1").organizationId(ORG).title("Policy").status(KnowledgeDocumentStatus.LIVE)
                .activeVersion(active).sourceDocumentIds(List.of("source-1")).build()));
    }

    private String latencyLine() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(line -> line.startsWith(MyHrChatService.RAG_LATENCY_MARKER))
                .collect(Collectors.joining("\n"));
    }
}
