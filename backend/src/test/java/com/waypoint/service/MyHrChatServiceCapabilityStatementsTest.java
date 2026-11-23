package com.waypoint.service;

import com.waypoint.enums.UserRole;
import com.waypoint.security.TestActors;
import com.waypoint.security.VerifiedMyHrActor;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Phase 1 regressions: Wavi denied the own-record lookups it has, and "What is Waypoint?" was
 * answered about the assistant. Configured as the shared-readonly backend runs (grounding off);
 * providers are mocks or mock HTTP servers, so no paid API call is made.
 */
class MyHrChatServiceCapabilityStatementsTest {

    private static final String CLAUDE_URL = "https://claude.test/v1/messages";
    private static final String OLLAMA_URL = "http://localhost:11434";

    private final ClaudeChatClient claude = mock(ClaudeChatClient.class);
    private final OllamaClient ollama = mock(OllamaClient.class);
    private final KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
    private final List<VerifiedMyHrActor> actors = List.of(
            TestActors.actor("employee-9", "org-9", UserRole.EMPLOYEE),
            TestActors.actor("hr-9", "org-9", UserRole.HR),
            TestActors.actor("admin-9", "org-9", UserRole.COMPANY_ADMIN));

    private MyHrChatService service(ClaudeChatClient claudeClient, OllamaClient ollamaClient, String provider,
                                    boolean grounding) {
        return new MyHrChatService(claudeClient, ollamaClient, mock(TimesheetService.class),
                mock(ProfileChangeRequestService.class), mock(EmployeeManagementService.class), provider, retrieval,
                grounding, false);
    }

    @Test
    void greetingsAddressedToWaviGetTheFixedGreetingFromEitherProviderSetup() {
        for (String provider : List.of("claude", "ollama")) {
            MyHrChatService service = service(claude, ollama, provider, false);
            for (VerifiedMyHrActor actor : actors) {
                for (String greeting : List.of("Hello Wavi", "Hello, Wavi!", "hello wavi.")) {
                    assertThat(service.chat(actor, greeting, List.of()).message()).as(greeting)
                            .isEqualTo("Hello! How can I help you today?");
                }
                for (String greeting : List.of("Hi Wavi", "Hey, Wavi", "hey wavi!")) {
                    assertThat(service.chat(actor, greeting, List.of()).message()).as(greeting)
                            .isEqualTo("Hi there! How can I help you today?");
                }
                assertThat(service.chat(actor, "Good morning, Wavi", List.of()).message())
                        .isEqualTo("Good morning! How can I help you today?");
            }
        }
        verifyNoInteractions(claude, ollama, retrieval);
    }

    @Test
    void whatCanYouHelpWithListsTheVerifiedLookupsWorkflowsAndUnsupportedActions() {
        for (String provider : List.of("claude", "ollama")) {
            MyHrChatService service = service(claude, ollama, provider, false);
            for (VerifiedMyHrActor actor : actors) {
                for (String question : List.of("What can you help me with?", "what can you do", "Hey Wavi, what can you do?",
                        "How can you help me?", "Who are you?", "help")) {
                    assertThat(service.chat(actor, question, List.of()).message()).as(question)
                            .startsWith("I'm Wavi, Waypoint's assistant.")
                            .contains("employee ID", "job title", "reporting manager", "profile summary",
                                    "Explain Waypoint workflows for your role",
                                    // RAG is off in shared-readonly, so policy answers are not offered
                                    "Company-policy answers aren't turned on",
                                    "I can't approve, reject, submit, sign or change anything")
                            .doesNotContain("not connected", "no live", "HR-approved policy documents");
                    if (actor.role() != UserRole.COMPANY_ADMIN) {
                        assertThat(service.chat(actor, question, List.of()).message())
                                .contains("latest timesheet status", "latest profile change request status");
                    }
                }
            }
        }
        verifyNoInteractions(claude, ollama, retrieval);
    }

    @Test
    void policyAnswersAreOfferedOnlyWhenGroundingCanReachAnApprovedProvider() {
        when(ollama.isLocalEndpoint()).thenReturn(true);
        org.mockito.Mockito.lenient().when(ollama.verifiedLocalModel()).thenReturn(true);
        assertThat(service(claude, ollama, "ollama", true).chat(actors.get(0), "What can you help me with?", List.of())
                .message()).contains("only from HR-approved policy documents, with sources")
                .doesNotContain("aren't turned on");

        when(ollama.isLocalEndpoint()).thenReturn(false);
        assertThat(service(claude, ollama, "ollama", true).chat(actors.get(0), "What can you help me with?", List.of())
                .message()).contains("Company-policy answers aren't turned on");
        verifyNoInteractions(retrieval, claude);
    }

    @Test
    void whatIsWaypointDescribesTheApplicationNotTheAssistant() {
        MyHrChatService service = service(claude, ollama, "claude", false);
        for (String question : List.of("What is Waypoint?", "what's waypoint", "Tell me about Waypoint",
                "What does Waypoint do?", "Wavi, what is the Waypoint app?")) {
            assertThat(service.chat(actors.get(0), question, List.of()).message()).as(question)
                    .isEqualTo(MyHrChatService.ABOUT_WAYPOINT_REPLY)
                    .startsWith("Waypoint is a workforce and HR application.");
        }
        verifyNoInteractions(claude, ollama, retrieval);
    }

    @Test
    void questionsThatOnlyMentionWaviStillReachTheModel() {
        when(claude.chat(anyString(), org.mockito.ArgumentMatchers.anyList())).thenReturn("model answer");
        MyHrChatService service = service(claude, ollama, "claude", false);

        // A leave balance is not recorded in Waypoint, so it gets a fixed honest reply instead of a model answer.
        assertThat(service.chat(TestActors.actor("emp-pto", "org-pto", UserRole.EMPLOYEE),
                "Hello Wavi, what is my PTO balance?", List.of()).message()).startsWith("I can't see leave or PTO balances");
        for (String question : List.of("Hello Wavi, can you draft a note to my team?", "Can you help me plan my week?")) {
            assertThat(service.chat(actors.get(0), question, List.of()).message()).as(question).isEqualTo("model answer");
        }
        assertThat(service.chat(actors.get(0), "What is the Waypoint holiday policy?", List.of()).message())
                .isEqualTo(MyHrKnowledgeGrounding.POLICY_UNAVAILABLE_ANSWER);
    }

    // --- The prompts both providers receive --------------------------------------------------------

    @Test
    void theSharedPromptDescribesTheVerifiedLookupsInsteadOfDenyingThem() {
        assertThat(MyHrAssistantPrompt.UNGROUNDED_SYSTEM_PROMPT)
                .startsWith(MyHrAssistantPrompt.CAPABILITIES)
                .contains("You are Wavi, the assistant built into Waypoint. Waypoint is a workforce and HR application",
                        // Hours are a supported lookup (2026-11-17), so a model must never say it can't see them.
                        "employee ID, job title, reporting manager, a profile summary, their latest timesheet status "
                                + "and when they last submitted a timesheet, the hours recorded on their own timesheets "
                                + "(for a day, a week, a month, a date range or one timesheet, including the total "
                                + "Waypoint shows in Hours Overview), their latest profile change request status "
                                + "and dates, including a phone number, address, name or personal email change, "
                                + "and their own document requests",
                        "HR and Company Admin users can also ask how many timesheets, profile change requests and "
                                + "document requests are waiting for their approval",
                        "Present these lookups as available", "never state, guess or confirm the values",
                        "it was not recognized as a lookup",
                        "approve, reject, submit, sign, cancel, delete or change anything",
                        "No approved company-policy documents are available")
                .doesNotContain("not connected", "no live employee", "Phase 1", "no company-policy RAG");
        assertThat(MyHrKnowledgeGrounding.GROUNDED_SYSTEM_PROMPT)
                .startsWith(MyHrAssistantPrompt.CAPABILITIES)
                .contains("Company policy rules:", "[GENERAL]")
                .doesNotContain("not connected", "no live employee");
    }

    @Test
    void claudePrimaryRequestCarriesTheAccuratePrompt() {
        RestClient.Builder claudeBuilder = RestClient.builder();
        MockRestServiceServer claudeServer = MockRestServiceServer.bindTo(claudeBuilder).build();
        claudeServer.expect(requestTo(CLAUDE_URL))
                .andExpect(jsonPath("$.system").value(MyHrAssistantPrompt.UNGROUNDED_SYSTEM_PROMPT))
                .andExpect(jsonPath("$.messages[0].content").value("Can you help me plan my week?"))
                .andRespond(withSuccess("{\"content\":[{\"type\":\"text\",\"text\":\"Plan the week.\"}]}",
                        MediaType.APPLICATION_JSON));
        RestClient.Builder ollamaBuilder = RestClient.builder();
        MockRestServiceServer ollamaServer = MockRestServiceServer.bindTo(ollamaBuilder).build();

        MyHrChatService service = service(claudeClient(claudeBuilder), ollamaClient(ollamaBuilder), "claude", false);

        assertThat(service.chat(actors.get(0), "Can you help me plan my week?", List.of()).message())
                .isEqualTo("Plan the week.");
        claudeServer.verify();
        ollamaServer.verify(); // no fallback request
        verifyNoInteractions(retrieval);
    }

    @Test
    void ollamaFallbackRequestCarriesTheSameAccuratePrompt() {
        RestClient.Builder claudeBuilder = RestClient.builder();
        MockRestServiceServer claudeServer = MockRestServiceServer.bindTo(claudeBuilder).build();
        claudeServer.expect(requestTo(CLAUDE_URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        RestClient.Builder ollamaBuilder = RestClient.builder();
        MockRestServiceServer ollamaServer = MockRestServiceServer.bindTo(ollamaBuilder).build();
        // the fallback first confirms the configured model runs locally (no remote/cloud model)
        ollamaServer.expect(requestTo(OLLAMA_URL + "/api/show"))
                .andRespond(withSuccess("{\"model_info\":{\"general.architecture\":\"llama\"}}", MediaType.APPLICATION_JSON));
        ollamaServer.expect(requestTo(OLLAMA_URL + "/api/chat"))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[0].content").value(MyHrAssistantPrompt.UNGROUNDED_SYSTEM_PROMPT))
                .andExpect(jsonPath("$.messages[1].content").value("Can you help me plan my week?"))
                .andRespond(withSuccess("{\"message\":{\"role\":\"assistant\",\"content\":\"Plan the week.\"}}",
                        MediaType.APPLICATION_JSON));

        MyHrChatService service = service(claudeClient(claudeBuilder), ollamaClient(ollamaBuilder), "claude", false);

        assertThat(service.chat(actors.get(0), "Can you help me plan my week?", List.of()).message())
                .isEqualTo("Plan the week.");
        claudeServer.verify();
        ollamaServer.verify();
        verifyNoInteractions(retrieval);
    }

    private static ClaudeChatClient claudeClient(RestClient.Builder builder) {
        return new ClaudeChatClient(builder.build(), "test-key", CLAUDE_URL, "claude-sonnet-5");
    }

    private static OllamaClient ollamaClient(RestClient.Builder builder) {
        return new OllamaClient(builder.baseUrl(OLLAMA_URL).build(), "llama3.1:8b", OLLAMA_URL);
    }
}
