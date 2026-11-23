package com.waypoint.service;

import com.waypoint.controller.MyHrChatController;
import com.waypoint.security.JwtService;
import com.waypoint.security.MyHrActorResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The questions employees reported on 2026-11-07, exactly as typed, sent as the browser sends them: POST
 * /api/myhr/chat with a bearer token and JSON body, through MyHrChatController and its JSON serialization to the real
 * MyHrChatService routing. The token-to-actor resolution is mocked as in MyHrChatControllerTest, and the service runs
 * against WaviEvaluationWorld's synthetic records with mocked providers: no database, Voyage, Claude or Ollama call.
 * The response is checked the way the chat UI reads it ($.data.message, $.data.actions, $.data.knowledgeStatus).
 */
@WebMvcTest(value = MyHrChatController.class, properties = "waypoint.myhr.chat.enabled=true")
class WaviObservedQuestionsHttpTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired MockMvc mockMvc;
    @MockitoBean MyHrChatService chatService;
    @MockitoBean ClaudeChatClient claudeChatClient;
    @MockitoBean JwtService jwtService;
    @MockitoBean MyHrActorResolver actorResolver;

    private WaviEvaluationWorld world;

    private void signIn(WaviEvaluationWorld.Person person) {
        world = new WaviEvaluationWorld(person, new WaviEvaluationWorld.Options());
        // doReturn/doAnswer: re-stubbing for a second user must not invoke the first user's stub.
        doReturn(person.userId()).when(jwtService).extractUserId("token");
        doReturn(person.role().name()).when(jwtService).extractRole("token");
        // MyHrChatControllerTest covers which roles may chat; here the resolver yields the verified synthetic actor.
        doReturn(world.actor).when(actorResolver).resolve(any(), any());
        // The controller's chat service is the real routing for this signed-in user.
        doAnswer(call -> world.service.chat(call.getArgument(0), call.getArgument(1), call.getArgument(2),
                call.getArgument(3))).when(chatService).chat(any(), anyString(), any(), any());
    }

    private ResultActions ask(String message) throws Exception {
        // Client-supplied identity is ignored: only the verified actor decides whose records are read.
        String body = JSON.writeValueAsString(Map.of("message", message, "userId", "emp-a2", "role", "COMPANY_ADMIN"));
        return mockMvc.perform(post("/api/myhr/chat").header("Authorization", "Bearer token")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
    }

    @Test
    void theReportedQuestionsAreAnsweredForTheSignedInEmployeeOverHttp() throws Exception {
        signIn(WaviEvaluationWorld.EMPLOYEE);

        ask("HOW ARE YOU").andExpect(jsonPath("$.data.message")
                .value("I'm doing well, thanks for asking! How can I help you today?"));
        ask("NUVU EVARU").andExpect(jsonPath("$.data.message").value(startsWith("I'm Wavi, Waypoint's assistant.")));
        ask("NENU EVARU").andExpect(jsonPath("$.data.message").value(allOf(containsString("Asha Rao"),
                containsString("WPT-1001"), not(containsString("Ravi Teja")))));
        ask("WHAT IS MY NAME").andExpect(jsonPath("$.data.message").value("Your name in Waypoint is Asha Rao."));
        ask("JOB TITLE").andExpect(jsonPath("$.data.message").value("Your job title is Senior Data Analyst."));
        ask("OCT TIMESHEETS")
                .andExpect(jsonPath("$.data.message").value(allOf(containsString("For October 2026"),
                        containsString("Mon Oct 12, 2026 to Sun Oct 18, 2026 was rejected"))))
                .andExpect(jsonPath("$.data.actions", contains("TIMESHEETS")));
        // A bare "<period> hours" asks for the hours recorded in that period (still the user's own October records).
        ask("OCT HOURS").andExpect(jsonPath("$.data.message").value(startsWith("Hours recorded for October 2026")));
        ask("DID I SUBMIT OCT 3 HOURS").andExpect(jsonPath("$.data.message").value(
                "For Sat Oct 3, 2026: your timesheet for Mon Sep 28, 2026 to Sun Oct 4, 2026 is approved."));
        ask("PAY ROLL MEANS").andExpect(jsonPath("$.data.message").value(startsWith("Payroll is the process")))
                .andExpect(jsonPath("$.data.knowledgeStatus").doesNotExist());
        ask("PAY ROLL READINESS MEANS")
                .andExpect(jsonPath("$.data.message").value(containsString("for HR and Company Admin accounts")))
                .andExpect(jsonPath("$.data.actions", contains("TIMESHEETS")));
        ask("WHAT IS MY PAY DATE")
                .andExpect(jsonPath("$.data.message").value(allOf(startsWith("Waypoint doesn't record pay dates"),
                        containsString("runs monthly"), containsString("ask HR"))))
                .andExpect(jsonPath("$.data.knowledgeStatus").value("NO_MATCHING_POLICY"))
                .andExpect(jsonPath("$.data.citations").doesNotExist());

        // Nothing went to an external provider; every record read was the signed-in employee's own.
        verifyNoInteractions(world.claude);
        verify(world.employees, org.mockito.Mockito.never()).getOwnDetail(WaviEvaluationWorld.COLLEAGUE.userId());
    }

    @Test
    void reviewersGetTheirOwnRecordsAndTheirOwnPages() throws Exception {
        signIn(WaviEvaluationWorld.HR);
        ask("WHAT IS MY NAME").andExpect(jsonPath("$.data.message").value("Your name in Waypoint is Hari Kumar."));
        ask("PAY ROLL READINESS MEANS")
                .andExpect(jsonPath("$.data.message").value(startsWith("Payroll Readiness is the Waypoint page")))
                .andExpect(jsonPath("$.data.actions", contains("PAYROLL_READINESS")));

        signIn(WaviEvaluationWorld.ADMIN);
        ask("OCT TIMESHEETS").andExpect(jsonPath("$.data.message")
                .value(startsWith("Company Admin accounts don't submit timesheets")));
        ask("NENU EVARU").andExpect(jsonPath("$.data.message").value(containsString("Anil Shah")));
        verifyNoInteractions(world.claude);
    }

    @Test
    void theActionsFieldTellsTheChatUiWhenToKeepItsOwnShortcutAndWhenToShowNone() throws Exception {
        signIn(WaviEvaluationWorld.EMPLOYEE);
        // A page the role does not have: an explicit empty list, so the UI shows no button.
        ask("take me to the audit log").andExpect(jsonPath("$.data.actions").isEmpty());
        // A route that leaves shortcuts to the chat UI: no actions field at all.
        ask("who is my manager").andExpect(jsonPath("$.data.message").value("Your reporting manager is Priya Nair."))
                .andExpect(jsonPath("$.data.actions").doesNotExist());
    }

    @Test
    void syntheticPolicyConversationUsesActualControllerResponsesAndRechecksTheCitedSource() throws Exception {
        signIn(WaviEvaluationWorld.EMPLOYEE);
        when(world.ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn(
                "Employees do not earn annual leave. [S1]", "Employees earn annual leave. [S1]");
        List<Map<String, String>> history = new ArrayList<>();
        String question = "What does the annual leave policy say?";
        JsonNode first = askWithHistory(question, history);
        JsonNode second = askWithHistory(question, history);
        assertThat(first.path("knowledgeStatus").asString()).isEqualTo("GROUNDED");
        assertThat(first.path("message").asString()).isEqualTo(second.path("message").asString())
                .contains("1.5 days", "18 days", "[1]").doesNotContain("do not earn");
        assertThat(first.path("citations").get(0).path("title").asString()).isEqualTo(WaviEvaluationWorld.LEAVE.title());
        JsonNode followUp = askWithHistory("Explain source 1", history);
        assertThat(followUp.path("message").asString()).contains("1.5 days", "[1]");
        assertThat(followUp.path("citations").get(0).path("title").asString()).isEqualTo(WaviEvaluationWorld.LEAVE.title());
        verify(world.retrieval, org.mockito.Mockito.times(3)).retrieve(any(), anyString());
        verifyNoInteractions(world.claude);
    }

    @Test
    void syntheticProfileOverviewAndQuantifierFollowUpsStayWithTheAuthenticatedActorOverHttp() throws Exception {
        signIn(WaviEvaluationWorld.EMPLOYEE);
        List<Map<String, String>> history = new ArrayList<>();
        JsonNode first = askWithHistory("What information does Waypoint have about me?", history);
        JsonNode followUp = askWithHistory("everything", history);
        assertThat(first.path("message").asString()).contains(WaviEvaluationWorld.EMPLOYEE.name(),
                WaviEvaluationWorld.EMPLOYEE.employeeId()).doesNotContain(WaviEvaluationWorld.COLLEAGUE.employeeId());
        assertThat(followUp.path("message").asString()).contains(WaviEvaluationWorld.EMPLOYEE.name())
                .doesNotContain("Which one", WaviEvaluationWorld.COLLEAGUE.employeeId());
        verify(world.employees, org.mockito.Mockito.never()).getOwnDetail(WaviEvaluationWorld.COLLEAGUE.userId());
        verifyNoInteractions(world.claude, world.retrieval);
    }

    private JsonNode askWithHistory(String question, List<Map<String, String>> history) throws Exception {
        String body = JSON.writeValueAsString(Map.of("message", question, "history", List.copyOf(history),
                "userId", WaviEvaluationWorld.COLLEAGUE.userId(), "role", "COMPANY_ADMIN"));
        String json = mockMvc.perform(post("/api/myhr/chat").header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode data = JSON.readTree(json).path("data");
        history.add(Map.of("role", "user", "content", question, "provenance",
                data.path("historyProvenance").path("user").asString()));
        history.add(Map.of("role", "assistant", "content", data.path("message").asString(), "provenance",
                data.path("historyProvenance").path("assistant").asString()));
        return data;
    }
}
