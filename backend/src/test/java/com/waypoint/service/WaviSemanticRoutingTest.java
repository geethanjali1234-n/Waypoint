package com.waypoint.service;

import com.waypoint.enums.UserRole;
import com.waypoint.security.TestActors;
import com.waypoint.security.VerifiedMyHrActor;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Phase P2: the semantic routing orchestrator with scripted providers (no network): provider order, bounded
 * attempts and time budget, refusals, invalid output, decision cache, per-user and daily limits, circuit breaker,
 * and which providers may be configured at all. Also the exact request the existing Claude and Ollama clients send.
 */
class WaviSemanticRoutingTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-11-05");
    private static final VerifiedMyHrActor ASHA = TestActors.actor("emp-a1", WaviEvaluationWorld.ORG, UserRole.EMPLOYEE);
    private static final String GOOD = "{\"decision\":{\"tool\":\"get_my_profile_field\",\"arguments\":{\"field\":"
            + "\"REPORTING_MANAGER\"}},\"confidence\":\"HIGH\",\"language\":\"telugu\"}";

    /** A provider that answers from a script: text to return, or an exception to throw, per call. */
    static final class Scripted implements WaviSemanticRouting.Provider {
        final String name;
        final Deque<Supplier<String>> script = new ArrayDeque<>();
        final List<String> userMessages = new ArrayList<>();
        boolean usable = true;
        int calls;

        Scripted(String name) {
            this.name = name;
        }

        Scripted then(Supplier<String> step) {
            script.add(step);
            return this;
        }

        public String name() {
            return name;
        }

        public WaviRouterDecision.Source source() {
            return WaviRouterDecision.Source.CLAUDE;
        }

        public boolean usable() {
            return usable;
        }

        public String route(WaviRouterPrompt.Request request, WaviSemanticRouting.Settings settings) {
            calls++;
            userMessages.add(request.user());
            Supplier<String> step = script.size() > 1 ? script.poll() : script.peek();
            return step.get();
        }
    }

    /** A clock tests can move. */
    static final class MovableClock extends Clock {
        Instant now = Instant.parse("2026-11-05T15:00:00Z");

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return now;
        }
    }

    private static List<WaviTool> catalog() {
        WaviEvaluationWorld.Options options = new WaviEvaluationWorld.Options();
        options.toolLayer = true;
        return new WaviEvaluationWorld(WaviEvaluationWorld.EMPLOYEE, options).service.toolLayer().registry().all();
    }

    private static WaviSemanticRouting routing(WaviSemanticRouting.Settings settings, Clock clock,
                                               WaviSemanticRouting.Provider... providers) {
        return new WaviSemanticRouting(true, List.of(providers), settings, clock);
    }

    private static WaviSemanticRouting.Settings settings(int maxAttempts, Duration budget, int userLimit, int dailyLimit,
                                                         int cacheSize) {
        return new WaviSemanticRouting.Settings(500, 400, maxAttempts, budget, "low", userLimit, Duration.ofMinutes(10),
                dailyLimit, Duration.ofMinutes(10), cacheSize, 3, Duration.ofSeconds(60));
    }

    private static WaviSemanticRouting.Result route(WaviSemanticRouting routing, String message) {
        return routing.route(ASHA, message, null, TODAY, catalog());
    }

    @Test
    void aGoodDecisionIsReturnedWithItsProviderAndSource() {
        Scripted claude = new Scripted("claude").then(() -> GOOD);
        WaviSemanticRouting.Result result = route(routing(WaviSemanticRouting.Settings.defaults(), Clock.systemUTC(), claude),
                "నా మేనేజర్ ఎవరు?");

        assertThat(result.outcome()).isEqualTo("decision");
        assertThat(result.provider()).isEqualTo("claude");
        assertThat(result.decision().tool()).isEqualTo(WaviTools.GET_MY_PROFILE_FIELD);
        assertThat(result.decision().arguments()).containsEntry("field", "REPORTING_MANAGER");
        assertThat(claude.userMessages.getFirst()).contains("\"నా మేనేజర్ ఎవరు?\"");
    }

    @Test
    void attemptsAreBoundedAndTheNextProviderIsTriedAfterAFailure() {
        // Only clearly transient failures (here 529 overloaded) are retried.
        Scripted claude = new Scripted("claude").then(() -> {
            throw new ClaudeChatClient.ClaudeUnavailableException().withDetails(529, "overloaded_error", null, 0);
        });
        Scripted local = new Scripted("ollama-local").then(() -> GOOD);
        WaviSemanticRouting.Result result = route(routing(settings(2, Duration.ofSeconds(4), 30, 100, 0),
                Clock.systemUTC(), claude, local), "who is my boss");

        assertThat(claude.calls).isEqualTo(2);
        assertThat(local.calls).isEqualTo(1);
        assertThat(result.outcome()).isEqualTo("decision");
        assertThat(result.provider()).isEqualTo("ollama-local");
        assertThat(result.attempts()).isEqualTo(3);
    }

    @Test
    void permanentFailuresAreNotRetried() {
        for (Supplier<String> failure : List.<Supplier<String>>of(
                () -> { throw new ClaudeChatClient.ClaudeConfigurationException(); },
                () -> { throw new ClaudeChatClient.ClaudeRequestRejectedException(); },
                () -> { throw new ClaudeChatClient.ClaudeIncompleteResponseException(
                        ClaudeChatClient.IncompleteReason.TRUNCATED); },
                () -> "not json")) {
            Scripted claude = new Scripted("claude").then(failure);
            WaviSemanticRouting.Result result = route(routing(settings(3, Duration.ofSeconds(4), 30, 100, 0),
                    Clock.systemUTC(), claude), "who is my boss");
            assertThat(claude.calls).as(result.outcome()).isEqualTo(1);
            assertThat(result.decision()).isNull();
        }
    }

    @Test
    void aRefusalIsFinalAndNeverSentToAnotherProvider() {
        Scripted claude = new Scripted("claude").then(() -> {
            throw new ClaudeChatClient.ClaudeSafetyRefusalException();
        });
        Scripted local = new Scripted("ollama-local").then(() -> GOOD);
        WaviSemanticRouting.Result result = route(routing(WaviSemanticRouting.Settings.defaults(), Clock.systemUTC(),
                claude, local), "something refused");

        assertThat(result.outcome()).isEqualTo("refusal");
        assertThat(result.decision()).isNull();
        assertThat(local.calls).isZero();
    }

    @Test
    void safetyRefusalsDoNotRetryOrOpenTheProviderCircuit() {
        Scripted claude = new Scripted("claude").then(() -> {
            throw new ClaudeChatClient.ClaudeSafetyRefusalException();
        });
        WaviSemanticRouting routing = routing(settings(3, Duration.ofSeconds(4), 100, 100, 0),
                Clock.systemUTC(), claude);

        for (int index = 0; index < 4; index++) {
            WaviSemanticRouting.Result result = route(routing, "refusal " + index);
            assertThat(result.outcome()).isEqualTo("refusal");
            assertThat(result.attempts()).isOne();
            assertThat(result.retries()).isZero();
        }
        // The circuit opens after three actual provider failures. Every refusal still reaches the provider.
        assertThat(claude.calls).isEqualTo(4);
    }

    @Test
    void theTimeBudgetStopsFurtherAttempts() {
        Scripted slow = new Scripted("claude").then(() -> {
            try {
                Thread.sleep(80);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            throw new ClaudeChatClient.ClaudeUnavailableException().withDetails(529, "overloaded_error", null, 0);
        });
        WaviSemanticRouting.Result result = route(routing(settings(3, Duration.ofMillis(50), 30, 100, 0),
                Clock.systemUTC(), slow), "who is my boss");

        assertThat(slow.calls).isEqualTo(1);
        assertThat(result.outcome()).isEqualTo("budget");
    }

    @Test
    void decisionsAreCachedButGuessesAreNot() {
        Scripted claude = new Scripted("claude").then(() -> GOOD);
        WaviSemanticRouting routing = routing(settings(1, Duration.ofSeconds(4), 30, 100, 10), Clock.systemUTC(), claude);
        assertThat(route(routing, "naa manager evaru").outcome()).isEqualTo("decision");
        WaviSemanticRouting.Result again = route(routing, "naa manager evaru");
        assertThat(again.outcome()).isEqualTo("cached");
        assertThat(again.decision().tool()).isEqualTo(WaviTools.GET_MY_PROFILE_FIELD);
        assertThat(claude.calls).isEqualTo(1);

        Scripted unsure = new Scripted("claude").then(() -> "{\"decision\":{\"tool\":\"clarify\",\"arguments\":{}},"
                + "\"confidence\":\"LOW\",\"language\":\"english\"}");
        WaviSemanticRouting guessing = routing(settings(1, Duration.ofSeconds(4), 30, 100, 10), Clock.systemUTC(), unsure);
        route(guessing, "hmm");
        route(guessing, "hmm");
        assertThat(unsure.calls).isEqualTo(2);
    }

    @Test
    void perUserAndDailyLimitsCapProviderCalls() {
        MovableClock clock = new MovableClock();
        Scripted claude = new Scripted("claude").then(() -> GOOD);
        WaviSemanticRouting routing = routing(settings(1, Duration.ofSeconds(4), 2, 3, 0), clock, claude);
        assertThat(route(routing, "a one").outcome()).isEqualTo("decision");
        assertThat(route(routing, "a two").outcome()).isEqualTo("decision");
        assertThat(route(routing, "a three").outcome()).isEqualTo("user-limit");
        clock.now = clock.now.plus(Duration.ofMinutes(11));
        assertThat(route(routing, "a four").outcome()).isEqualTo("decision");
        assertThat(routing.route(TestActors.actor("emp-a2", WaviEvaluationWorld.ORG, UserRole.EMPLOYEE), "a five", null,
                TODAY, catalog()).outcome()).isEqualTo("daily-limit");
        clock.now = clock.now.plus(Duration.ofDays(1));
        assertThat(route(routing, "a six").outcome()).isEqualTo("decision");
        assertThat(claude.calls).isEqualTo(4);
    }

    @Test
    void repeatedFailuresOpenTheCircuitForAWhile() {
        MovableClock clock = new MovableClock();
        Scripted claude = new Scripted("claude").then(() -> {
            throw new ClaudeChatClient.ClaudeConfigurationException();
        });
        WaviSemanticRouting routing = routing(settings(1, Duration.ofSeconds(4), 100, 100, 0), clock, claude);
        for (int index = 0; index < 3; index++) route(routing, "question " + index);
        assertThat(claude.calls).isEqualTo(3);
        assertThat(route(routing, "question 4").outcome()).isEqualTo("no-provider");
        assertThat(claude.calls).isEqualTo(3);
        clock.now = clock.now.plus(Duration.ofSeconds(61));
        route(routing, "question 5");
        assertThat(claude.calls).isEqualTo(4);
    }

    @Test
    void onlyApprovedProvidersCanBeConfiguredAndOllamaOnlyWhenLocal() {
        ClaudeChatClient claude = mock(ClaudeChatClient.class);
        OllamaClient localOllama = mock(OllamaClient.class);
        when(localOllama.isLocalEndpoint()).thenReturn(true);
        org.mockito.Mockito.lenient().when(localOllama.verifiedLocalModel()).thenReturn(true);
        OllamaClient remoteOllama = mock(OllamaClient.class);

        assertThat(WaviSemanticRouting.providers(List.of("claude", "ollama-local"), claude, localOllama, false))
                .extracting(WaviSemanticRouting.Provider::name).containsExactly("claude", "ollama-local");
        // A hosted fallback is not implemented and needs separate approval: routing stays off entirely.
        assertThat(WaviSemanticRouting.providers(List.of("claude", "bedrock"), claude, localOllama, false)).isEmpty();
        assertThat(WaviSemanticRouting.providers(List.of("hosted"), claude, localOllama, false)).isEmpty();
        // Claude-only mode never uses Ollama.
        assertThat(WaviSemanticRouting.providers(List.of("claude", "ollama-local"), claude, localOllama, true))
                .extracting(WaviSemanticRouting.Provider::name).containsExactly("claude");
        assertThat(new WaviSemanticRouting.OllamaProvider(remoteOllama).usable()).isFalse();
        assertThat(new WaviSemanticRouting.OllamaProvider(localOllama).usable()).isTrue();

        assertThat(new WaviSemanticRouting(false, List.of(new Scripted("claude")), WaviSemanticRouting.Settings.defaults(),
                Clock.systemUTC()).enabled()).as("flag off").isFalse();
        assertThat(new WaviSemanticRouting(true, List.of(), WaviSemanticRouting.Settings.defaults(),
                Clock.systemUTC()).enabled()).as("no approved provider").isFalse();
        WaviSemanticRouting spring = new WaviSemanticRouting(claude, localOllama, false, List.of("claude", "ollama-local"),
                false, 500, 400, 2, Duration.ofSeconds(4), "low", 30, Duration.ofMinutes(10), 2000, Duration.ofMinutes(10),
                false, 0.1);
        assertThat(spring.enabled()).as("Spring defaults: off").isFalse();
    }

    @Test
    void anUnusableOrDisabledRouterNeverCallsAProvider() {
        Scripted claude = new Scripted("claude").then(() -> GOOD);
        claude.usable = false;
        assertThat(route(routing(WaviSemanticRouting.Settings.defaults(), Clock.systemUTC(), claude), "x y z").outcome())
                .isEqualTo("no-provider");
        Scripted off = new Scripted("claude").then(() -> GOOD);
        WaviSemanticRouting disabled = new WaviSemanticRouting(false, List.of(off), WaviSemanticRouting.Settings.defaults(),
                Clock.systemUTC());
        assertThat(route(disabled, "x").outcome()).isEqualTo("disabled");
        assertThat(route(routing(WaviSemanticRouting.Settings.defaults(), Clock.systemUTC(), off), "   ").outcome())
                .isEqualTo("empty");
        assertThat(claude.calls + off.calls).isZero();
    }

    // --- The existing provider clients ---------------------------------------------------------------------------------

    @Test
    void claudeRoutingSendsStructuredOutputsWithNoHistoryAndAShortTimeoutClient() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://claude.test/v1/messages"))
                .andExpect(header("x-api-key", "test-key"))
                .andExpect(header("anthropic-version", "2023-06-01"))
                .andExpect(jsonPath("$.model").value("claude-haiku-5-5"))
                .andExpect(jsonPath("$.max_tokens").value(400))
                .andExpect(jsonPath("$.system[0].type").value("text"))
                .andExpect(jsonPath("$.system[0].text").value("ROUTER SYSTEM"))
                .andExpect(jsonPath("$.system[0].cache_control.type").value("ephemeral"))
                .andExpect(jsonPath("$.messages.length()").value(1))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[0].content").value("ROUTER USER"))
                .andExpect(jsonPath("$.thinking.type").value("disabled"))
                .andExpect(jsonPath("$.output_config.effort").value("low"))
                .andExpect(jsonPath("$.output_config.format.type").value("json_schema"))
                .andExpect(jsonPath("$.output_config.format.schema.additionalProperties").value(false))
                .andExpect(jsonPath("$.tools").doesNotExist())
                .andRespond(withSuccess("{\"content\":[{\"type\":\"text\",\"text\":" + jsonString(GOOD) + "}],"
                        + "\"stop_reason\":\"end_turn\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://claude.test/v1/messages"))
                .andRespond(withSuccess("{\"content\":[{\"type\":\"text\",\"text\":\"\"}],\"stop_reason\":\"refusal\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://claude.test/v1/messages"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST));
        ClaudeChatClient client = new ClaudeChatClient(builder.baseUrl("https://claude.test").build(), "test-key",
                "https://claude.test/v1/messages", "claude-sonnet-5");
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of(), "additionalProperties", false);

        assertThat(client.routerModel()).isEqualTo("claude-haiku-5-5");
        assertThat(client.routeJson("ROUTER SYSTEM", "ROUTER USER", schema, 400, "low")).isEqualTo(GOOD);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.routeJson("S", "U", schema, 400, "low"))
                .isInstanceOf(ClaudeChatClient.ClaudeSafetyRefusalException.class);
        // An API that does not accept the request shape is a rejection, never a silent fallback to chat.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.routeJson("S", "U", schema, 400, "low"))
                .isInstanceOf(ClaudeChatClient.ClaudeRequestRejectedException.class);
        server.verify();
    }

    @Test
    void ollamaRoutingIsLocalOnlyAndConstrainedByTheSchema() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // the model is first verified to be local (no remote/cloud model), then asked to route
        server.expect(requestTo("http://localhost:11434/api/show"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{\"model_info\":{\"general.architecture\":\"llama\"}}", org.springframework.http.MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:11434/api/chat"))
                .andExpect(jsonPath("$.format.type").value("object"))
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.options.temperature").value(0.0))
                .andExpect(jsonPath("$.options.num_predict").value(400))
                .andExpect(jsonPath("$.stream").value(false))
                .andRespond(withSuccess("{\"message\":{\"role\":\"assistant\",\"content\":" + jsonString(GOOD) + "},"
                        + "\"done_reason\":\"stop\"}", MediaType.APPLICATION_JSON));
        OllamaClient local = new OllamaClient(builder.baseUrl("http://localhost:11434").build(), "llama3.1:8b",
                "http://localhost:11434");
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of(), "additionalProperties", false);
        assertThat(local.routeJson("S", "U", schema, 400)).isEqualTo(GOOD);
        server.verify();

        OllamaClient remote = new OllamaClient(RestClient.builder().baseUrl("http://gpu.example.com:11434").build(),
                "llama3.1:8b", "http://gpu.example.com:11434");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> remote.routeJson("S", "U", schema, 400))
                .isInstanceOf(OllamaClient.OllamaUnavailableException.class);
    }

    private static String jsonString(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
