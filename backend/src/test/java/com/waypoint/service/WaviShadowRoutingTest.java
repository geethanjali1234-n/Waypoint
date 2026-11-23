package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrChatResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Phase P3A: shadow mode (the router proposes, nothing it proposes is served or executed) and the routing call's
 * model-specific request (thinking setting) and token usage.
 */
class WaviShadowRoutingTest {

    private static String decision(String tool, String arguments, String confidence) {
        return "{\"decision\":{\"tool\":\"" + tool + "\",\"arguments\":" + arguments + "},\"confidence\":\"" + confidence
                + "\",\"language\":\"english\"}";
    }

    /** A world with shadow mode on (every message sampled, compared synchronously) and the router answering {@code output}. */
    private static WaviEvaluationWorld shadowed(WaviEvaluationWorld.Person person, String output) {
        WaviEvaluationWorld world = new WaviEvaluationWorld(person, new WaviEvaluationWorld.Options());
        doReturn(WaviEvaluationWorld.routed(output)).when(world.claude)
                .routeJsonWithUsage(anyString(), anyString(), anyMap(), anyInt(), anyString());
        WaviSemanticRouting routing = new WaviSemanticRouting(false, true, 1.0,
                List.of(new WaviSemanticRouting.ClaudeProvider(world.claude)), WaviSemanticRouting.Settings.defaults(),
                Clock.fixed(WaviEvaluationWorld.NOW, ZoneOffset.UTC));
        world.service.useShadowRouting(routing, Runnable::run);
        return world;
    }

    private static MyHrChatResponse ask(WaviEvaluationWorld world, String message) {
        return world.service.chat(world.actor, message, List.of(), StampedHistory.SESSION);
    }

    private static List<String> lookups(WaviEvaluationWorld world) {
        List<String> calls = new ArrayList<>();
        for (Object mock : world.dataMocks()) {
            for (Invocation invocation : Mockito.mockingDetails(mock).getInvocations()) {
                calls.add(invocation.getMethod().getName() + List.of(invocation.getArguments()));
            }
        }
        return calls;
    }

    @Test
    void shadowModeNeverChangesAnAnswerOrRunsAProposedTool() {
        List<String> questions = List.of("who is my manager", "నా మేనేజర్ ఎవరు?", "how many vacation days do I get a year",
                "write me a poem about cats", "my timesheets for october", "status?");
        for (String question : questions) {
            WaviEvaluationWorld off = new WaviEvaluationWorld(WaviEvaluationWorld.EMPLOYEE, new WaviEvaluationWorld.Options());
            // A proposal that would read records if it were executed: it must never be.
            WaviEvaluationWorld shadow = shadowed(WaviEvaluationWorld.EMPLOYEE,
                    decision("get_my_requests", "{\"kind\":\"DOCUMENT_REQUEST\"}", "HIGH"));
            MyHrChatResponse a = ask(off, question);
            MyHrChatResponse b = ask(shadow, question);
            assertThat(b.message()).as(question).isEqualTo(a.message());
            assertThat(b.actions()).as(question).isEqualTo(a.actions());
            assertThat(b.knowledgeStatus()).as(question).isEqualTo(a.knowledgeStatus());
            assertThat(lookups(shadow)).as(question).isEqualTo(lookups(off));
        }
    }

    @Test
    void comparisonsRecordAgreementWithWhatAnsweredAndFlagSureDisagreementsForReview() {
        WaviEvaluationWorld agreeing = shadowed(WaviEvaluationWorld.EMPLOYEE,
                decision("get_my_profile_field", "{\"field\":\"REPORTING_MANAGER\"}", "HIGH"));
        ask(agreeing, "who is my manager");
        assertThat(agreeing.service.shadowRouting().snapshot()).containsEntry("tool/agree", 1L);

        WaviEvaluationWorld disagreeing = shadowed(WaviEvaluationWorld.EMPLOYEE,
                decision("policy_question", "{}", "HIGH"));
        ask(disagreeing, "who is my manager");
        assertThat(disagreeing.service.shadowRouting().snapshot()).containsEntry("tool/disagree", 1L)
                .containsEntry("review", 1L);

        WaviEvaluationWorld refused = shadowed(WaviEvaluationWorld.EMPLOYEE, decision("out_of_scope", "{}", "HIGH"));
        ask(refused, "write me a poem about cats");
        assertThat(refused.service.shadowRouting().snapshot()).containsEntry("out_of_scope/agree", 1L);

        WaviEvaluationWorld policy = shadowed(WaviEvaluationWorld.EMPLOYEE, decision("policy_question", "{}", "HIGH"));
        ask(policy, "how many vacation days do I get a year");
        assertThat(policy.service.shadowRouting().snapshot()).containsEntry("handoff/agree", 1L);
    }

    @Test
    void theComparisonDryRunsTheGateWithoutAnyLookup() {
        WaviEvaluationWorld world = shadowed(WaviEvaluationWorld.EMPLOYEE, "unused");
        WaviShadowRouting shadow = world.service.shadowRouting();
        WaviToolLayer layer = world.service.toolLayer();
        doReturn(WaviEvaluationWorld.routed(decision("get_my_profile_field",
                "{\"field\":\"NAME\",\"userId\":\"emp-a2\"}", "HIGH"))).when(world.claude)
                .routeJsonWithUsage(anyString(), anyString(), anyMap(), anyInt(), anyString());
        Mockito.clearInvocations(world.employees, world.timesheets, world.profileChanges, world.documents);

        WaviShadowRouting.Comparison identity = shadow.compare(world.actor, "x y", null, java.time.LocalDate.parse("2026-11-05"),
                layer, new WaviShadowRouting.Served("fixed", null, null));
        assertThat(identity.gate()).isEqualTo("rejected:IDENTITY_ARGUMENT");
        assertThat(identity.review()).isFalse();

        doReturn(WaviEvaluationWorld.routed(decision("get_review_queue", "{\"queue\":\"TIMESHEETS\"}", "HIGH")))
                .when(world.claude).routeJsonWithUsage(anyString(), anyString(), anyMap(), anyInt(), anyString());
        WaviShadowRouting.Comparison outside = shadow.compare(world.actor, "a b", null, java.time.LocalDate.parse("2026-11-05"),
                layer, new WaviShadowRouting.Served("fixed", null, null));
        assertThat(outside.gate()).isEqualTo("outside-role");
        assertThat(lookups(world)).isEmpty();
    }

    @Test
    void aFailingRouterOrAFullQueueNeverReachesTheUser() {
        WaviEvaluationWorld failing = shadowed(WaviEvaluationWorld.EMPLOYEE, "unused");
        doThrow(new ClaudeChatClient.ClaudeUnavailableException()).when(failing.claude)
                .routeJsonWithUsage(anyString(), anyString(), anyMap(), anyInt(), anyString());
        WaviEvaluationWorld off = new WaviEvaluationWorld(WaviEvaluationWorld.EMPLOYEE, new WaviEvaluationWorld.Options());
        assertThat(ask(failing, "who is my manager").message()).isEqualTo(ask(off, "who is my manager").message());
        assertThat(failing.service.shadowRouting().snapshot()).containsEntry("tool/no-decision", 1L);

        WaviEvaluationWorld full = new WaviEvaluationWorld(WaviEvaluationWorld.EMPLOYEE, new WaviEvaluationWorld.Options());
        WaviSemanticRouting routing = new WaviSemanticRouting(false, true, 1.0,
                List.of(new WaviSemanticRouting.ClaudeProvider(full.claude)), WaviSemanticRouting.Settings.defaults(),
                Clock.fixed(WaviEvaluationWorld.NOW, ZoneOffset.UTC));
        full.service.useShadowRouting(routing, runnable -> {
            throw new RejectedExecutionException("queue full");
        });
        assertThat(ask(full, "who is my manager").message()).isEqualTo("Your reporting manager is Priya Nair.");
        assertThat(full.service.shadowRouting().snapshot()).containsEntry("dropped", 1L);
    }

    @Test
    void samplingAndFlagsControlWhetherTheRouterIsAskedAtAll() {
        WaviEvaluationWorld world = new WaviEvaluationWorld(WaviEvaluationWorld.EMPLOYEE, new WaviEvaluationWorld.Options());
        WaviSemanticRouting never = new WaviSemanticRouting(false, true, 0.0,
                List.of(new WaviSemanticRouting.ClaudeProvider(world.claude)), WaviSemanticRouting.Settings.defaults(),
                Clock.fixed(WaviEvaluationWorld.NOW, ZoneOffset.UTC));
        world.service.useShadowRouting(never, Runnable::run);
        ask(world, "who is my manager");
        assertThat(Mockito.mockingDetails(world.claude).getInvocations())
                .noneMatch(invocation -> invocation.getMethod().getName().startsWith("routeJson"));

        // Serving and shadowing are exclusive: serving wins, and shadow mode is off.
        WaviSemanticRouting both = new WaviSemanticRouting(true, true, 1.0,
                List.of(new WaviSemanticRouting.ClaudeProvider(world.claude)), WaviSemanticRouting.Settings.defaults(),
                Clock.systemUTC());
        assertThat(both.enabled()).isTrue();
        assertThat(both.shadowEnabled()).isFalse();

        // Spring defaults: everything off, nothing created.
        WaviSemanticRouting defaults = new WaviSemanticRouting(world.claude, world.ollama, false,
                List.of("claude", "ollama-local"), false, 500, 400, 2, Duration.ofSeconds(4), "low", 30,
                Duration.ofMinutes(10), 2000, Duration.ofMinutes(10), false, 0.1);
        assertThat(defaults.enabled()).isFalse();
        assertThat(defaults.shadowEnabled()).isFalse();
        WaviEvaluationWorld plain = new WaviEvaluationWorld(WaviEvaluationWorld.EMPLOYEE, new WaviEvaluationWorld.Options());
        plain.service.configureSemanticRouting(defaults);
        assertThat(plain.service.shadowRouting()).isNull();
        assertThat(plain.service.toolLayer()).isNull();
        // Kept only for deterministic follow-up continuation (default on); off, nothing is created.
        plain.service.useContinuation(false);
        assertThat(plain.service.dialogueStore()).isNull();
    }

    // --- The Claude routing request per model, and its token usage ---------------------------------------------------

    @Test
    void thinkingIsChosenPerModelSoNoRequestIsRejectedForIt() {
        assertThat(ClaudeChatClient.thinkingFor("claude-haiku-5-5", "auto")).isEqualTo("disabled");
        // Sonnet 5.5 and Opus 5.5 reject thinking "disabled" with a 400 (platform.claude.com, 2026-11-09).
        assertThat(ClaudeChatClient.thinkingFor("claude-sonnet-5-5", "auto")).isEqualTo("between_tools");
        assertThat(ClaudeChatClient.thinkingFor("claude-opus-5-5", "auto")).isEqualTo("between_tools");
        assertThat(ClaudeChatClient.thinkingFor("claude-sonnet-5", "auto")).isNull();
        assertThat(ClaudeChatClient.thinkingFor("claude-haiku-4-5-20251001", "auto")).isNull();
        assertThat(ClaudeChatClient.thinkingFor("claude-sonnet-5-5", "omit")).isNull();
        assertThat(ClaudeChatClient.thinkingFor("claude-sonnet-5-5", "adaptive")).isEqualTo("adaptive");
    }

    @Test
    void eachModelGetsItsOwnThinkingFieldAndTheResponseUsageIsReported() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String body = "{\"content\":[{\"type\":\"text\",\"text\":\"{}\"}],\"stop_reason\":\"end_turn\","
                + "\"usage\":{\"input_tokens\":120,\"output_tokens\":40,\"cache_creation_input_tokens\":2100,"
                + "\"cache_read_input_tokens\":0}}";
        server.expect(requestTo("https://claude.test/v1/messages"))
                .andExpect(jsonPath("$.model").value("claude-sonnet-5-5"))
                .andExpect(jsonPath("$.thinking.type").value("between_tools"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://claude.test/v1/messages"))
                .andExpect(jsonPath("$.model").value("claude-sonnet-5"))
                .andExpect(jsonPath("$.thinking").doesNotExist())
                .andRespond(withSuccess("{\"content\":[{\"type\":\"text\",\"text\":\"{}\"}],\"stop_reason\":\"end_turn\"}",
                        MediaType.APPLICATION_JSON));
        ClaudeChatClient client = new ClaudeChatClient(builder.baseUrl("https://claude.test").build(), "test-key",
                "https://claude.test/v1/messages", "claude-sonnet-5");
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of(), "additionalProperties", false);

        client.useRouterModel("claude-sonnet-5-5", "auto");
        ClaudeChatClient.RouteResult sonnet = client.routeJsonWithUsage("S", "U", schema, 400, "low");
        assertThat(sonnet.usage()).isEqualTo(new ClaudeChatClient.RouteUsage(120, 40, 2100, 0));
        assertThat(sonnet.model()).isEqualTo("claude-sonnet-5-5");
        client.useRouterModel("claude-sonnet-5", "auto");
        assertThat(client.routeJsonWithUsage("S", "U", schema, 400, "low").usage())
                .isEqualTo(ClaudeChatClient.RouteUsage.NONE);
        server.verify();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.useRouterModel("claude-haiku-5-5", "enabled"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void routingResultsCarryTokenUsageForCostTracking() {
        ClaudeChatClient claude = mock(ClaudeChatClient.class);
        doReturn(new ClaudeChatClient.RouteResult(decision("clarify", "{}", "MEDIUM"),
                new ClaudeChatClient.RouteUsage(150, 30, 0, 2100), "claude-haiku-5-5")).when(claude)
                .routeJsonWithUsage(anyString(), anyString(), anyMap(), anyInt(), anyString());
        WaviSemanticRouting routing = new WaviSemanticRouting(true, List.of(new WaviSemanticRouting.ClaudeProvider(claude)),
                WaviSemanticRouting.Settings.defaults(), Clock.systemUTC());
        WaviEvaluationWorld.Options options = new WaviEvaluationWorld.Options();
        options.toolLayer = true;
        List<WaviTool> catalog = new WaviEvaluationWorld(WaviEvaluationWorld.EMPLOYEE, options).service.toolLayer()
                .registry().all();
        WaviSemanticRouting.Result result = routing.route(new WaviEvaluationWorld(WaviEvaluationWorld.EMPLOYEE,
                new WaviEvaluationWorld.Options()).actor, "is it done", null, java.time.LocalDate.parse("2026-11-05"), catalog);
        assertThat(result.usage()).isEqualTo(new ClaudeChatClient.RouteUsage(150, 30, 0, 2100));
    }
}
