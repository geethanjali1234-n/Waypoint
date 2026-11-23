package com.waypoint.service;

import com.waypoint.enums.ChunkAccessLevel;
import com.waypoint.enums.UserRole;
import com.waypoint.security.TestActors;
import com.waypoint.security.VerifiedMyHrActor;
import com.waypoint.service.KnowledgeRetrievalService.RetrievalResult;
import com.waypoint.service.KnowledgeRetrievalService.RetrievalStatus;
import com.waypoint.service.KnowledgeRetrievalService.RetrievedChunk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OFFLINE and free. The development-only answer-source header tells a developer which provider answered, so a Claude
 * answer can never be mistaken for a deterministic one or for a local Ollama fallback. Providers are mocks.
 */
class WaviAnswerSourceDiagnosticTest {

    private static final String HEADER = "X-Wavi-Answer-Source";
    private static final String POLICY_QUESTION = "How many unused vacation days can employees carry forward?";
    private static final String LEAVE_TEXT = "2. Carry forward\nEmployees may carry forward up to 5 unused days.";
    private static final String HR_TEXT = "HR may approve up to 8 extra carry-forward days.";

    private final ClaudeChatClient claude = mock(ClaudeChatClient.class);
    private final OllamaClient ollama = mock(OllamaClient.class);
    private final KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
    private final VerifiedMyHrActor employee = TestActors.actor("employee-9", "org-9", UserRole.EMPLOYEE);
    private final VerifiedMyHrActor hr = TestActors.hr("hr-9", "org-9");
    private MockHttpServletResponse response;

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    private MyHrChatService service(boolean grounding, boolean externalApproved, boolean header) {
        when(ollama.isLocalEndpoint()).thenReturn(true);
        org.mockito.Mockito.lenient().when(ollama.verifiedLocalModel()).thenReturn(true);
        MyHrChatService service = new MyHrChatService(claude, ollama, mock(TimesheetService.class),
                mock(ProfileChangeRequestService.class), mock(EmployeeManagementService.class),
                mock(DocumentRequestService.class), "claude", retrieval, grounding, externalApproved, false,
                externalApproved ? (organizationId, chunks) -> true : ExternalPolicyProcessingAuthorization.DENY_ALL);
        service.answerSourceHeader(header);
        response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest(), response));
        return service;
    }

    private String source() {
        return response.getHeader(HEADER);
    }

    private static RetrievalResult retrieved(RetrievedChunk... chunks) {
        return new RetrievalResult(RetrievalStatus.RETRIEVED, List.of(chunks), chunks.length);
    }

    private static RetrievedChunk chunk(String document, String title, ChunkAccessLevel level, String text) {
        return new RetrievedChunk(document + "-ingestion-00000", document, title, "stored-" + document,
                document + ".pdf", document + "-ingestion", 0, level, LocalDateTime.of(2026, 10, 1, 9, 0), 0.9, text);
    }

    @Test
    void theHeaderIsOffByDefaultAndNothingIsAddedToTheResponse() {
        when(claude.chat(anyString(), anyList())).thenReturn("Here is a draft.");
        service(false, false, false).chat(employee, "Write a polite email to my team", List.of());
        assertThat(source()).isNull();
    }

    @Test
    void aClaudeAnswerIsLabelledClaude() {
        when(claude.chat(anyString(), anyList())).thenReturn("Here is a draft.");
        service(false, false, true).chat(employee, "Write a polite email to my team", List.of());
        assertThat(source()).startsWith("provider=claude; served=handoff");
    }

    @Test
    void anOllamaFallbackIsNeverPassedOffAsClaude() {
        when(claude.chat(anyString(), anyList())).thenThrow(new ClaudeChatClient.ClaudeUnavailableException());
        when(ollama.chat(anyString(), anyList())).thenReturn("Local draft.");
        service(false, false, true).chat(employee, "Write a polite email to my team", List.of());
        assertThat(source()).startsWith("provider=ollama;fallback=true").doesNotContain("provider=claude");
    }

    @Test
    void anApprovedCompanyWidePolicyAnswerFromClaudeIsLabelledWithNativeCitations() {
        when(retrieval.retrieve(eq(employee), anyString())).thenReturn(retrieved(
                chunk("leave", "Synthetic Leave Policy", ChunkAccessLevel.COMPANY_WIDE, LEAVE_TEXT)));
        when(claude.chatWithDocuments(any(MyHrGroundedDocumentPrompt.class))).thenReturn(List.of(new MyHrCitedSegment(
                "You can carry forward up to 5 unused days.", List.of(new MyHrCitedSegment.Citation("char_location",
                        LEAVE_TEXT, 0, 0, LEAVE_TEXT.length())))));
        service(true, true, true).chat(employee, POLICY_QUESTION, List.of());
        assertThat(source()).startsWith("provider=claude;citations=native");
        verify(ollama, never()).chat(any(MyHrGroundedPrompt.class));
    }

    @Test
    void hrOnlyPolicyIsLabelledLocalAndNeverClaude() {
        when(retrieval.retrieve(eq(hr), anyString())).thenReturn(retrieved(
                chunk("leave", "Synthetic Leave Policy", ChunkAccessLevel.COMPANY_WIDE, LEAVE_TEXT),
                chunk("hr", "Synthetic HR Guidance", ChunkAccessLevel.HR_ONLY, HR_TEXT)));
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn("HR may approve up to 8 extra days [S2].");
        service(true, true, true).chat(hr, POLICY_QUESTION, List.of());
        assertThat(source()).startsWith("provider=ollama;local-policy=true");
        verify(claude, never()).chatWithDocuments(any(MyHrGroundedDocumentPrompt.class));
    }

    @Test
    void anUnauthorizedOrganizationsPolicyIsLabelledLocalAndNeverClaude() {
        when(retrieval.retrieve(eq(employee), anyString())).thenReturn(retrieved(
                chunk("leave", "Synthetic Leave Policy", ChunkAccessLevel.COMPANY_WIDE, LEAVE_TEXT)));
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn("You can carry forward up to 5 unused days [S1].");
        service(true, false, true).chat(employee, POLICY_QUESTION, List.of());
        assertThat(source()).startsWith("provider=ollama;local-policy=true");
        verify(claude, never()).chatWithDocuments(any(MyHrGroundedDocumentPrompt.class));
    }

    @Test
    void aDeterministicAnswerNamesNoProvider() {
        service(false, false, true).chat(employee, "hi", List.of());
        assertThat(source()).startsWith("provider=none");
    }

    @Test
    void theHeaderCarriesCodesOnlyNeverTheQuestionTheAnswerOrAData() {
        when(claude.chat(anyString(), anyList())).thenReturn("A confidential answer about Ravi Teja.");
        service(false, false, true).chat(employee, "Tell me about Ravi Teja salary", List.of());
        assertThat(source()).doesNotContain("Ravi", "Teja", "salary", "confidential", "org-9", "employee-9");
    }

    @Test
    void theAgentAnswerIsLabelledClaudeAgent() {
        int[] calls = {0};
        ClaudeChatClient.ToolConversation model = (system, messages, tools, allow, max) -> {
            if (calls[0]++ == 0) {
                var call = new ClaudeChatClient.ToolCall("toolu_1", WaviTools.GET_MY_PROFILE_FIELD, Map.of("field", "JOB_TITLE"));
                return new ClaudeChatClient.ToolTurn(null, List.of(call), List.of(Map.of("type", "tool_use", "id", "toolu_1",
                        "name", call.name(), "input", call.input())), ClaudeChatClient.RouteUsage.NONE, "scripted");
            }
            return new ClaudeChatClient.ToolTurn("Your job title is Senior Data Analyst.", List.of(),
                    List.of(Map.of("type", "text", "text", "Your job title is Senior Data Analyst.")),
                    ClaudeChatClient.RouteUsage.NONE, "scripted");
        };
        var options = new WaviEvaluationWorld.Options();
        options.semanticRouter = WaviEvaluationWorld.RouterBehaviour.UNAVAILABLE;
        var world = new WaviEvaluationWorld(WaviEvaluationWorld.EMPLOYEE, options);
        world.service.useAgentLoop(new WaviAgentLoop(model, (organization, role) -> true,
                WaviAgentLoop.Settings.defaults(true), Clock.systemUTC()));
        world.service.answerSourceHeader(true);
        response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest(), response));
        world.service.chat(world.actor, "what is my job title", List.of(), StampedHistory.SESSION);
        assertThat(source()).startsWith("provider=claude-agent;agent");
    }
}
