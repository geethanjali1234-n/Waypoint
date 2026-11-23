package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrChatResponse;
import com.waypoint.enums.UserRole;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;

/**
 * P3B hardening: a work question no tool answers exactly gets the honest unsupported_request reply instead of the
 * nearest-looking tool (the HO-14 class), with no record read; plus the router guidance and tool limits that steer
 * a model there. Offline: decisions are scripted, no model is called.
 */
class WaviUnsupportedRequestTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-11-05");

    private static WaviEvaluationWorld world(WaviEvaluationWorld.Person person) {
        WaviEvaluationWorld.Options options = new WaviEvaluationWorld.Options();
        options.toolLayer = true;
        return new WaviEvaluationWorld(person, options);
    }

    private static List<Invocation> lookups(WaviEvaluationWorld world) {
        List<Invocation> calls = new ArrayList<>();
        for (Object mock : world.dataMocks()) calls.addAll(Mockito.mockingDetails(mock).getInvocations());
        return calls;
    }

    @Test
    void everyReasonGetsAFixedHonestReplyWithoutReadingAnything() {
        Map<WaviTools.UnsupportedReason, String> replies = Map.of(
                WaviTools.UnsupportedReason.HISTORY_OR_DATE_RANGE, WaviTools.HISTORY_REPLY,
                WaviTools.UnsupportedReason.OTHER_PERSON, MyHrChatService.OTHER_PERSON_REPLY,
                WaviTools.UnsupportedReason.WRITE_ACTION, MyHrChatService.ACTION_REQUEST_REPLY,
                WaviTools.UnsupportedReason.NOT_AVAILABLE_IN_WAYPOINT, WaviTools.NOT_AVAILABLE_REPLY);
        for (WaviEvaluationWorld.Person person : WaviEvaluationWorld.PEOPLE) {
            WaviEvaluationWorld world = world(person);
            for (Map.Entry<WaviTools.UnsupportedReason, String> entry : replies.entrySet()) {
                Mockito.clearInvocations(world.timesheets, world.profileChanges, world.employees, world.documents);
                WaviToolLayer.Outcome outcome = world.service.toolLayer().execute(WaviRouterDecision.fastPath(
                        WaviRouterDecision.Intent.UNSUPPORTED, WaviTools.UNSUPPORTED_REQUEST,
                        Map.of("reason", entry.getKey().name())), world.actor, TODAY);
                assertThat(outcome.result().kind()).isEqualTo(WaviToolResult.Kind.NOT_AVAILABLE);
                assertThat(outcome.result().text()).as(person.role() + " " + entry.getKey()).isEqualTo(entry.getValue());
                assertThat(outcome.externallyShareable()).isFalse();
                assertThat(lookups(world)).as(person.role() + " " + entry.getKey()).isEmpty();
            }
        }
    }

    @Test
    void aTeamHistoryQuestionRoutedAsUnsupportedIsAnsweredHonestlyWithNoLookup() {
        WaviEvaluationWorld hr = world(WaviEvaluationWorld.HR);
        doReturn(WaviEvaluationWorld.routed("{\"decision\":{\"tool\":\"unsupported_request\",\"arguments\":"
                + "{\"reason\":\"HISTORY_OR_DATE_RANGE\"}},\"confidence\":\"HIGH\",\"language\":\"english\"}"))
                .when(hr.claude).routeJsonWithUsage(anyString(), anyString(), anyMap(), anyInt(), anyString());
        hr.service.useSemanticRouting(new WaviSemanticRouting(true,
                List.of(new WaviSemanticRouting.ClaudeProvider(hr.claude)), WaviSemanticRouting.Settings.defaults(),
                Clock.fixed(WaviEvaluationWorld.NOW, ZoneOffset.UTC)));
        Mockito.clearInvocations(hr.timesheets, hr.profileChanges, hr.employees, hr.documents);

        // A wording no deterministic route claims, so the (mocked) router decides.
        MyHrChatResponse response = hr.service.chat(hr.actor, "cuántas hojas de horas envió mi equipo el mes pasado",
                List.of(), StampedHistory.SESSION);

        assertThat(response.message()).isEqualTo(WaviTools.HISTORY_REPLY);
        assertThat(lookups(hr)).as("no queue or record read for an unsupported question").isEmpty();
    }

    @Test
    void toolDescriptionsStateTheirLimitsAndTheRouterIsToldToPreferUnsupportedRequest() {
        WaviToolRegistry registry = world(WaviEvaluationWorld.EMPLOYEE).service.toolLayer().registry();
        String queue = registry.find(WaviTools.GET_REVIEW_QUEUE).orElseThrow().description();
        assertThat(queue).contains("RIGHT NOW").contains("Not history").contains("past date range");
        assertThat(registry.find(WaviTools.GET_MY_TIMESHEETS).orElseThrow().description()).contains("OWN")
                .contains("not a team's");
        assertThat(registry.find(WaviTools.OUT_OF_SCOPE).orElseThrow().description()).contains("unsupported_request");
        assertThat(registry.find(WaviTools.UNSUPPORTED_REQUEST).orElseThrow().allowedRoles())
                .containsExactlyInAnyOrder(UserRole.EMPLOYEE, UserRole.HR, UserRole.COMPANY_ADMIN);

        WaviRouterPrompt.Request prompt = WaviRouterPrompt.build(registry.all(), UserRole.HR, TODAY, null, "x");
        assertThat(prompt.system()).contains("Choose a tool ONLY if it answers the user's actual question exactly")
                .contains("unsupported_request").contains("HISTORY_OR_DATE_RANGE")
                .contains("out_of_scope: only for messages unrelated to work");
        assertThat(prompt.schema().toString()).contains("unsupported_request");
        assertThat(WaviRouterDecisionParser.intentOf(WaviTools.UNSUPPORTED_REQUEST))
                .isEqualTo(WaviRouterDecision.Intent.UNSUPPORTED);
    }

    @Test
    void theLiveGoldModeIsCappedAtOneAttemptPerSealedCaseAndPassesTheAttemptTimeout() throws Exception {
        assertThat(WaviLiveRouterEvaluation.Mode.parse("gold")).isEqualTo(WaviLiveRouterEvaluation.Mode.GOLD);
        assertThat(WaviLiveRouterEvaluation.GOLD_MAX_HTTP_ATTEMPTS).isEqualTo(WaviRouterGold.load().size());
        assertThat(WaviLiveRouterEvaluation.maxHttpAttempts(WaviLiveRouterEvaluation.Mode.GOLD, "45")).isEqualTo(45);
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException().isThrownBy(
                () -> WaviLiveRouterEvaluation.maxHttpAttempts(WaviLiveRouterEvaluation.Mode.GOLD, "46"));
        WaviSemanticRouting.Provider timed = new WaviSemanticRouting.Provider() {
            public String name() { return "claude"; }
            public WaviRouterDecision.Source source() { return WaviRouterDecision.Source.CLAUDE; }
            public boolean usable() { return true; }
            public String route(WaviRouterPrompt.Request request, WaviSemanticRouting.Settings settings) { return ""; }
            public Duration attemptTimeout() { return Duration.ofMillis(2500); }
        };
        assertThat(new WaviLiveRouterEvaluation.MeteredProvider(timed, 1).attemptTimeout())
                .isEqualTo(Duration.ofMillis(2500));

        WaviSemanticRouting.Result result = new WaviSemanticRouting.Result(new WaviRouterDecision(
                WaviRouterDecision.Intent.QUEUE, WaviTools.GET_REVIEW_QUEUE, Map.of("queue", "TIMESHEETS"),
                WaviRouterDecision.Confidence.MEDIUM, List.of(), "english", WaviRouterDecision.Source.CLAUDE),
                "decision", "claude", 2, 1300, new ClaudeChatClient.RouteUsage(72, 54, 0, 7575), 1, List.of("overloaded"));
        String row = WaviLiveRouterEvaluation.observation("claude-haiku-5-5", result, UserRole.HR,
                world(WaviEvaluationWorld.HR).service.toolLayer().registry());
        assertThat(row).contains("get_review_queue").contains("{queue=TIMESHEETS}").contains("MEDIUM")
                .contains("clarify").contains("| 1 |").contains("overloaded").contains("1300");
    }
}
