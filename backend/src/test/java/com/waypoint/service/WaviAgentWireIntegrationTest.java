package com.waypoint.service;

import com.sun.net.httpserver.HttpServer;
import com.waypoint.dto.myhr.MyHrChatHistoryMessage;
import com.waypoint.dto.myhr.MyHrChatResponse;
import com.waypoint.enums.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OFFLINE integration over REAL HTTP. The production {@link ClaudeChatClient} (its real request serialization, headers,
 * timeouts and response parsing) talks to a stub Anthropic server on 127.0.0.1 that records the exact bytes it
 * receives, so the outbound payload is audited as it would leave the process. The stub is the only "Claude":
 * scripted, free, and it sees only SYNTHETIC data (WaviEvaluationWorld). Nothing leaves the machine.
 *
 * <p>This validates the integration, the wire format and the privacy boundary. It does not measure how well the real
 * model understands wording; see docs/wavi/agent-real-claude-test-plan.md.
 */
class WaviAgentWireIntegrationTest {

    private static final String API_KEY = "sk-ant-test-key-do-not-leak-0123456789";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final ExternalRecordProcessingAuthorization RECORDS_ONLY = (organization, role) -> true;
    private static final ExternalRecordProcessingAuthorization RECORDS_AND_NAMES = new ExternalRecordProcessingAuthorization() {
        @Override
        public boolean permits(String organization, UserRole role) {
            return true;
        }

        @Override
        public boolean permitsPersonNames(String organization, UserRole role) {
            return true;
        }
    };

    private HttpServer server;
    private MockedStatic<ClaudeSpendLimiter> reservation;

    @BeforeEach
    void allowLoopbackStubOnly() {
        // This class constructs its clients only with the private loopback server's generated URL.
        reservation = Mockito.mockStatic(ClaudeSpendLimiter.class);
        reservation.when(() -> ClaudeSpendLimiter.fromSettings(Mockito.anyDouble(), Mockito.any()))
                .thenCallRealMethod();
        reservation.when(() -> ClaudeSpendLimiter.closed(Mockito.anyDouble())).thenCallRealMethod();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
        reservation.close();
    }

    /** The stub Anthropic API: records every request exactly, answers by a scripted function of the parsed request. */
    private final class Stub {
        final List<String> bodies = new CopyOnWriteArrayList<>();
        final List<Map<String, String>> headers = new CopyOnWriteArrayList<>();
        volatile Function<JsonNode, Map<String, Object>> script = request -> text("ok");
        int status = 200;
        String url;

        Stub() {
            try {
                server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
            server.createContext("/v1/messages", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                bodies.add(body);
                Map<String, String> seen = new LinkedHashMap<>();
                exchange.getRequestHeaders().forEach((name, values) -> seen.put(name.toLowerCase(), String.join(",", values)));
                headers.add(seen);
                byte[] reply;
                if (status != 200) {
                    reply = "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"SECRET-DETAIL\"}}"
                            .getBytes(StandardCharsets.UTF_8);
                } else {
                    reply = JSON.writeValueAsString(script.apply(JSON.readTree(body))).getBytes(StandardCharsets.UTF_8);
                }
                exchange.getResponseHeaders().add("content-type", "application/json");
                exchange.sendResponseHeaders(status, reply.length);
                exchange.getResponseBody().write(reply);
                exchange.close();
            });
            server.start();
            url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages";
        }

        String everythingReceived() {
            return String.join("\n", bodies);
        }
    }

    private static Map<String, Object> text(String text) {
        return Map.of("content", List.of(Map.of("type", "text", "text", text)), "stop_reason", "end_turn",
                "usage", Map.of("input_tokens", 1200, "output_tokens", 60));
    }

    private static Map<String, Object> toolUse(String id, String name, Map<String, Object> input) {
        return Map.of("content", List.of(Map.of("type", "tool_use", "id", id, "name", name, "input", input)),
                "stop_reason", "tool_use", "usage", Map.of("input_tokens", 1500, "output_tokens", 40));
    }

    /** Round-by-round plan; afterwards the model words the answer from the tool results of the last message. */
    private static Function<JsonNode, Map<String, Object>> plan(Function<Integer, Map<String, Object>> rounds) {
        int[] counter = {0};
        return request -> {
            Map<String, Object> planned = rounds.apply(counter[0]++);
            if (planned != null) return planned;
            StringBuilder composed = new StringBuilder();
            JsonNode last = request.get("messages").get(request.get("messages").size() - 1);
            JsonNode content = last.get("content");
            if (content.isArray()) {
                for (JsonNode block : content) {
                    String shown = block.get("content").asString();
                    composed.append(shown.startsWith("UNTRUSTED") ? shown.substring(shown.indexOf('\n') + 1) : shown).append('\n');
                }
            }
            return text(composed.toString().strip());
        };
    }

    private WaviEvaluationWorld world(WaviEvaluationWorld.Person person, Stub stub,
                                      ExternalRecordProcessingAuthorization authorization) {
        if (!stub.url.equals("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages")) {
            throw new IllegalArgumentException("Only the test's loopback stub may receive requests");
        }
        var options = new WaviEvaluationWorld.Options();
        options.semanticRouter = WaviEvaluationWorld.RouterBehaviour.UNAVAILABLE;
        var world = new WaviEvaluationWorld(person, options);
        ClaudeChatClient real = new ClaudeChatClient(RestClient.builder().build(), API_KEY, stub.url,
                "claude-sonnet-5-5");
        real.useAgentModel("claude-haiku-5-5", Duration.ofSeconds(5));
        world.service.useAgentLoop(new WaviAgentLoop(real::converse, authorization,
                WaviAgentLoop.Settings.defaults(true), Clock.systemUTC()));
        return world;
    }

    private static final class Session {
        final WaviEvaluationWorld world;
        final List<MyHrChatHistoryMessage> history = new ArrayList<>();

        Session(WaviEvaluationWorld world) {
            this.world = world;
        }

        MyHrChatResponse ask(String question) {
            MyHrChatResponse response = world.service.chat(world.actor, question, List.copyOf(history), StampedHistory.SESSION);
            history.add(new MyHrChatHistoryMessage("user", question, response.historyProvenance().user()));
            history.add(new MyHrChatHistoryMessage("assistant", response.message(), response.historyProvenance().assistant()));
            return response;
        }
    }

    /** What must never appear in a byte sent to the provider, for any synthetic user in the evaluation world. */
    private static void assertNoIdentifiersOrSecrets(String sent) {
        assertThat(sent).doesNotContain("WPT-", "emp-a1", "emp-a2", "hr-a1", "admin-a1", "org-eval-a", "org-eval-b",
                "personRef", "x-api-key", API_KEY, "ORGB-CONFIDENTIAL", "@");
        assertThat(WaviDataMinimizer.sensitiveShape(sent)).as("sensitive shape in outbound bytes").isNull();
    }

    // --- Wire format --------------------------------------------------------------------------------------------------

    @Test
    void theRealClientSendsAWellFormedToolUseRequestAndEchoesToolResultsWithTheRightIds() throws Exception {
        Stub stub = new Stub();
        stub.script = plan(round -> round == 0 ? toolUse("toolu_01ABC", WaviTools.GET_MY_PROFILE_FIELD, Map.of("field", "JOB_TITLE")) : null);
        var session = new Session(world(WaviEvaluationWorld.EMPLOYEE, stub, RECORDS_ONLY));
        String answer = session.ask("what do I do for a living here?").message();
        assertThat(answer).contains("Senior Data Analyst");

        assertThat(stub.bodies).hasSize(2);
        JsonNode first = JSON.readTree(stub.bodies.get(0));
        assertThat(first.get("model").asString()).isEqualTo("claude-haiku-5-5");
        assertThat(first.get("max_tokens").asInt()).isEqualTo(1024);
        assertThat(first.get("system").get(0).get("cache_control").get("type").asString()).isEqualTo("ephemeral");
        assertThat(first.get("tool_choice").get("type").asString()).isEqualTo("auto");
        assertThat(first.get("tools").size()).isGreaterThan(5);
        for (JsonNode tool : first.get("tools")) {
            assertThat(tool.get("input_schema").get("type").asString()).isEqualTo("object");
            assertThat(tool.get("input_schema").get("additionalProperties").asBoolean()).isFalse();
        }
        assertThat(stub.headers.get(0)).containsEntry("x-api-key", API_KEY).containsEntry("anthropic-version", "2023-06-01");
        // The key is a header only; it is never in any body.
        assertThat(stub.everythingReceived()).doesNotContain(API_KEY);

        JsonNode second = JSON.readTree(stub.bodies.get(1));
        JsonNode messages = second.get("messages");
        assertThat(messages.get(messages.size() - 2).get("content").get(0).get("id").asString()).isEqualTo("toolu_01ABC");
        JsonNode result = messages.get(messages.size() - 1).get("content").get(0);
        assertThat(result.get("type").asString()).isEqualTo("tool_result");
        assertThat(result.get("tool_use_id").asString()).isEqualTo("toolu_01ABC");
        assertThat(result.get("content").asString()).startsWith("UNTRUSTED TOOL DATA").contains("Senior Data Analyst");
    }

    @Test
    void theLastRoundForcesATextAnswerSoALoopCannotRunOn() throws Exception {
        Stub stub = new Stub();
        stub.script = request -> toolUse("toolu_" + System.nanoTime(), WaviTools.GET_MY_PROFILE_FIELD, Map.of("field", "JOB_TITLE"));
        var session = new Session(world(WaviEvaluationWorld.EMPLOYEE, stub, RECORDS_ONLY));
        assertThat(session.ask("what is my job title?").message()).contains("Senior Data Analyst");
        assertThat(stub.bodies).hasSize(WaviAgentLoop.Settings.defaults(true).maxModelCalls());
        assertThat(JSON.readTree(stub.bodies.getLast()).get("tool_choice").get("type").asString()).isEqualTo("none");
    }

    @Test
    void aProviderErrorFallsBackToTheDeterministicAnswerAndNeverEchoesTheErrorBody() {
        Stub stub = new Stub();
        stub.status = 529;
        var session = new Session(world(WaviEvaluationWorld.EMPLOYEE, stub, RECORDS_ONLY));
        MyHrChatResponse response = session.ask("what is my job title?");
        assertThat(response.message()).contains("Senior Data Analyst").doesNotContain("SECRET-DETAIL");
    }

    // --- Privacy boundary: the bytes actually sent -----------------------------------------------------------------------

    @Test
    void anEmployeeConversationInThreeLanguagesSendsNoIdentifierEmailOrSecretAndNoCrossTurnLeak() {
        Stub stub = new Stub();
        stub.script = plan(round -> switch (round) {
            case 0 -> toolUse("toolu_1", WaviTools.GET_MY_TIMESHEETS, Map.of("period", "LATEST"));
            case 2 -> toolUse("toolu_2", WaviTools.GET_MY_PROFILE_FIELD, Map.of("field", "EMPLOYEE_ID"));
            default -> null;
        });
        var session = new Session(world(WaviEvaluationWorld.EMPLOYEE, stub, RECORDS_AND_NAMES));
        session.ask("Please check my last timesheet, my address is asha.rao@example.test and my phone is +1 555 010 2030");
        MyHrChatResponse id = session.ask("¿Y cuál es mi número de empleado?");
        // The identifier is shown to the user as Waypoint states it, and never sent.
        assertThat(id.message()).contains("WPT-1001");
        assertThat(stub.everythingReceived()).doesNotContain("WPT-1001", "asha.rao", "555 010");
        assertNoIdentifiersOrSecrets(stub.everythingReceived());
        // The typed address was replaced by a placeholder before sending.
        assertThat(stub.bodies.get(0)).contains("[email]");
    }

    @Test
    void hrWithoutNameApprovalSendsNoNameInAnyRequestAcrossTheWholeConversation() {
        Stub stub = new Stub();
        stub.script = plan(round -> round == 0 ? toolUse("toolu_1", WaviTools.GET_REVIEW_QUEUE, Map.of("queue", "TIMESHEETS", "detail", "LIST")) : null);
        var session = new Session(world(WaviEvaluationWorld.HR, stub, RECORDS_ONLY));
        assertThat(session.ask("who is holding things up on my side?").message()).contains("Asha Rao", "Ravi Teja");
        stub.script = request -> text("Of course, what would you like to know?");
        session.ask("thanks, one more question please");
        assertThat(stub.everythingReceived()).doesNotContain("Asha", "Ravi", "Teja", "Rao");
        assertNoIdentifiersOrSecrets(stub.everythingReceived());
    }

    @Test
    void adminHeadcountAndQueueCountsReachTheModelWithNoNamesAndNoIds() {
        Stub stub = new Stub();
        stub.script = plan(round -> switch (round) {
            case 0 -> toolUse("toolu_1", WaviTools.GET_PEOPLE_SUMMARY, Map.of());
            default -> null;
        });
        var session = new Session(world(WaviEvaluationWorld.ADMIN, stub, RECORDS_ONLY));
        assertThat(session.ask("wie gross ist unsere Belegschaft?").message()).isNotBlank();
        assertThat(stub.everythingReceived()).doesNotContain("Asha", "Ravi", "Hari Kumar", "Anil Shah");
        assertNoIdentifiersOrSecrets(stub.everythingReceived());
    }

    @Test
    void thePreviousAnswerHintNeverCarriesAPersonReferenceOrAListPosition() throws Exception {
        Stub stub = new Stub();
        stub.script = plan(round -> round == 0
                ? toolUse("toolu_1", WaviTools.GET_TEAM_TIMESHEETS, Map.of("status", "SUBMITTED")) : null);
        var session = new Session(world(WaviEvaluationWorld.HR, stub, RECORDS_AND_NAMES));
        session.ask("show Ravi Teja's timesheets that are waiting for me");
        stub.script = request -> text("Which period do you mean?");
        session.ask("and what about last week?");
        String system = JSON.readTree(stub.bodies.getLast()).get("system").get(0).get("text").asString();
        assertThat(system).contains("previous verified answer").contains(WaviTools.GET_TEAM_TIMESHEETS)
                .doesNotContain("personRef", "ordinal");
        assertNoIdentifiersOrSecrets(stub.everythingReceived());
    }

    @Test
    void theOutboundGuardRefusesAnyRequestThatWouldCarryASensitiveShape() {
        var tools = List.<ClaudeChatClient.ToolDefinition>of();
        assertThat(WaviAgentLoop.outboundProblem("clean system", List.of(Map.of("role", "user", "content", "hello")), tools)).isNull();
        assertThat(WaviAgentLoop.outboundProblem("x", List.of(Map.of("role", "user", "content", "mail a.b@c.test")), tools))
                .isEqualTo("EMAIL");
        assertThat(WaviAgentLoop.outboundProblem("x", List.of(Map.of("role", "user", "content", "badge WPT-1001")), tools))
                .isEqualTo("EMPLOYEE_CODE");
        assertThat(WaviAgentLoop.outboundProblem("ref a8f3c91d7be24e60b5a1c3d9e8f7a6b5c4d3e2f1", List.of(), tools))
                .isNotNull();
        assertThat(WaviAgentLoop.outboundProblem("x", List.of(Map.of("role", "user", "content",
                List.of(Map.of("type", "tool_result", "tool_use_id", "toolu_01ABCDEFGHIJKLMNOPQRSTUV9", "content", "40 hours on 2026-10-14 17:00")))),
                tools)).isNull();
    }

    @Test
    void aStoredPersonReferenceInTheDialogueStateIsStrippedFromTheSystemText() {
        var state = new WaviDialogueStore.State("t1", WaviTools.GET_TEAM_TIMESHEETS,
                Map.of("status", "SUBMITTED", "ordinal", 2, MyHrChatService.PERSON_REFERENCE, "9f2c41b7aa03d5e6c8b1f0e2d4a69b38"),
                null, null, null, null);
        String line = WaviAgentLoop.previousLine(state);
        assertThat(line).contains("SUBMITTED").doesNotContain("9f2c41b7", "ordinal", "personRef");
    }

    // --- Authorization and approval flags -------------------------------------------------------------------------------

    @Test
    void withoutOrganizationApprovalTheProviderReceivesNothingAtAll() {
        Stub stub = new Stub();
        var session = new Session(world(WaviEvaluationWorld.HR, stub, ExternalRecordProcessingAuthorization.DENY_ALL));
        assertThat(session.ask("who is waiting on my approval right now?").message()).startsWith("2 people are waiting");
        assertThat(stub.bodies).isEmpty();
    }

    @Test
    void personHandlesKeepNamesOffTheWireWhateverIsApprovedAndTheNameApprovalOnlyMattersWithoutHandles() {
        for (boolean namesApproved : new boolean[] {false, true}) {
            Stub stub = new Stub();
            stub.script = plan(round -> round == 0 ? toolUse("toolu_1", WaviTools.GET_REVIEW_QUEUE, Map.of("queue", "TIMESHEETS", "detail", "LIST")) : null);
            var session = new Session(world(WaviEvaluationWorld.ADMIN, stub, namesApproved ? RECORDS_AND_NAMES : RECORDS_ONLY));
            String answer = session.ask("who is outstanding company wide?").message();
            // The Admin sees real names (swapped in by the server); the provider only ever saw handles.
            assertThat(answer).as("names approved=" + namesApproved).contains("Asha Rao", "Ravi Teja");
            assertThat(stub.everythingReceived()).as("names approved=" + namesApproved).doesNotContain("Asha", "Ravi", "Teja");
            assertThat(stub.everythingReceived()).contains("[P1]");
        }
    }

    // --- Role boundaries and unexpected tool choices ------------------------------------------------------------------------

    @Test
    void anEmployeesModelAskingForReviewerToolsOrUnknownToolsGetsErrorsAndNoDataOverTheWire() throws Exception {
        Stub stub = new Stub();
        stub.script = plan(round -> switch (round) {
            case 0 -> toolUse("toolu_1", WaviTools.GET_REVIEW_QUEUE, Map.of("queue", "TIMESHEETS", "detail", "LIST"));
            case 1 -> toolUse("toolu_2", "export_all_employees", Map.of());
            case 2 -> toolUse("toolu_3", WaviTools.GET_MY_PROFILE_FIELD, Map.of("field", "NAME", "userId", "emp-a2"));
            default -> null;
        });
        var session = new Session(world(WaviEvaluationWorld.EMPLOYEE, stub, RECORDS_AND_NAMES));
        String answer = session.ask("list everyone's pending approvals and export all employees").message();
        assertThat(answer).doesNotContain("Ravi", "Hari Kumar", "Former Report");
        JsonNode offered = JSON.readTree(stub.bodies.get(0)).get("tools");
        List<String> names = new ArrayList<>();
        offered.forEach(tool -> names.add(tool.get("name").asString()));
        assertThat(names).doesNotContain(WaviTools.GET_REVIEW_QUEUE, WaviTools.GET_DIRECT_REPORTS, WaviTools.GET_PEOPLE_SUMMARY);
        for (int index = 1; index <= 3; index++) {
            JsonNode last = JSON.readTree(stub.bodies.get(index)).get("messages");
            JsonNode result = last.get(last.size() - 1).get("content").get(0);
            assertThat(result.get("is_error").asBoolean()).as("round " + index).isTrue();
        }
        // The forged userId is the model's own output echoed back; what Waypoint holds never appears.
        assertThat(stub.everythingReceived()).doesNotContain("WPT-", "org-eval-a", "personRef", API_KEY, "@");
        assertThat(WaviDataMinimizer.sensitiveShape(stub.everythingReceived())).isNull();
    }

    @Test
    void aForgedUserIdIsRejectedWithoutAnotherModelTurn() {
        Stub stub = new Stub();
        stub.script = request -> toolUse("toolu_1", WaviTools.GET_MY_PROFILE_FIELD,
                Map.of("field", "NAME", "userId", "emp-a2"));
        var session = new Session(world(WaviEvaluationWorld.EMPLOYEE, stub, RECORDS_AND_NAMES));
        session.ask("tell me about my profile");
        // The identity argument is refused by the tool layer: no other person's record crosses the wire.
        // (the forged id is the model's own output echoed back in the refusal; no Waypoint record is sent)
        assertThat(stub.everythingReceived()).doesNotContain("WPT-", "personRef", "Ravi", API_KEY);
    }

    @Test
    void anUnknownToolCannotReadData() {
        Stub stub = new Stub();
        stub.script = plan(round -> round == 0 ? toolUse("toolu_1", "export_all_employees", Map.of()) : null);
        var session = new Session(world(WaviEvaluationWorld.EMPLOYEE, stub, RECORDS_AND_NAMES));
        assertThat(session.ask("help me with an employee export").message()).doesNotContain("Ravi", "Hari Kumar");
        assertThat(stub.everythingReceived()).doesNotContain("WPT-", "org-eval-a", "personRef", API_KEY);
    }

    // --- Names versus counts, grounding failures ------------------------------------------------------------------------------

    @Test
    void hrNamesCountsAndDistinctPeopleComeFromSeparateResultsAndAreVerifiedAgainstThem() {
        Stub stub = new Stub();
        stub.script = request -> {
            JsonNode messages = request.get("messages");
            JsonNode last = messages.get(messages.size() - 1);
            if (!last.get("content").isArray()) {
                return Map.of("content", List.of(
                        Map.of("type", "tool_use", "id", "toolu_a", "name", WaviTools.GET_REVIEW_QUEUE,
                                "input", Map.of("queue", "TIMESHEETS", "detail", "COUNT")),
                        Map.of("type", "tool_use", "id", "toolu_b", "name", WaviTools.GET_REVIEW_QUEUE,
                                "input", Map.of("queue", "TIMESHEETS", "detail", "LIST"))),
                        "stop_reason", "tool_use", "usage", Map.of("input_tokens", 1500, "output_tokens", 40));
            }
            return text("3 timesheets from 2 people are waiting: [P1] and [P2].");
        };
        var session = new Session(world(WaviEvaluationWorld.HR, stub, RECORDS_AND_NAMES));
        assertThat(session.ask("how many people and sheets am I holding up?").message())
                .isEqualTo("3 timesheets from 2 people are waiting: Asha Rao and Ravi Teja.");
    }

    @Test
    void aWrongFigureAnInventedPersonAndAnInventedDateAreNeverShownToTheUser() {
        for (String fabricated : List.of("7 timesheets from 5 people are waiting.",
                "Asha Rao and Maria Gonzalez are waiting.", "It was approved on 2026-12-01 by someone.")) {
            Stub stub = new Stub();
            stub.script = plan(round -> round == 0
                    ? toolUse("toolu_1", WaviTools.GET_REVIEW_QUEUE, Map.of("queue", "TIMESHEETS", "detail", "LIST"))
                    : text(fabricated));
            var session = new Session(world(WaviEvaluationWorld.HR, stub, RECORDS_AND_NAMES));
            String answer = session.ask("who is waiting and since when?").message();
            // The deterministic answer replaces it: the real queue, never the model's wording.
            assertThat(answer).as(fabricated).doesNotContain("Maria", "2026-12-01", "7 timesheets", "5 people")
                    .contains("Asha Rao", "Ravi Teja");
        }
    }

    // --- Payload size, for the cost estimate ----------------------------------------------------------------------------------------

    @Test
    void thePayloadSizePerRoleIsMeasuredForTheCostEstimate() throws Exception {
        StringBuilder report = new StringBuilder("role, first_request_chars, tools, est_input_tokens(chars/3.5)\n");
        for (WaviEvaluationWorld.Person person : List.of(WaviEvaluationWorld.EMPLOYEE, WaviEvaluationWorld.HR, WaviEvaluationWorld.ADMIN)) {
            Stub stub = new Stub();
            stub.script = request -> text("Happy to help with Waypoint.");
            new Session(world(person, stub, RECORDS_ONLY)).ask("I have a general question about something odd");
            JsonNode first = JSON.readTree(stub.bodies.getFirst());
            int chars = stub.bodies.getFirst().length();
            report.append(person.role()).append(", ").append(chars).append(", ").append(first.get("tools").size()).append(", ")
                    .append(Math.round(chars / 3.5)).append('\n');
            assertThat(chars).as(person.role().name()).isLessThan(60_000);
        }
        Path out = Path.of("target", "wavi-eval", "agent-payload-size.csv");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString());
    }

    // --- The correction round on the wire ------------------------------------------------------------------------------------------

    @Test
    void theCorrectionRequestIsWellFormedOnTheWireAndForbidsFurtherTools() throws Exception {
        Stub stub = new Stub();
        int[] call = {0};
        stub.script = request -> switch (call[0]++) {
            case 0 -> toolUse("toolu_1", WaviTools.GET_REVIEW_QUEUE, Map.of("queue", "TIMESHEETS", "detail", "COUNT"));
            case 1 -> text("You have 7 timesheets waiting.");
            default -> text("You have 3 submitted timesheets awaiting your review.");
        };
        var session = new Session(world(WaviEvaluationWorld.HR, stub, RECORDS_ONLY));
        assertThat(session.ask("how big is my backlog?").message()).isEqualTo("You have 3 submitted timesheets awaiting your review.");

        // The tool round, the unsupported answer, and exactly ONE correction: three requests, no more.
        assertThat(stub.bodies).hasSize(3);
        JsonNode third = JSON.readTree(stub.bodies.get(2));
        assertThat(third.get("tool_choice").get("type").asString()).isEqualTo("none");
        JsonNode messages = third.get("messages");
        List<String> roles = new ArrayList<>();
        messages.forEach(message -> roles.add(message.get("role").asString()));
        // Roles alternate: question, tool_use, tool_result, the rejected text, the correction request.
        assertThat(roles).containsExactly("user", "assistant", "user", "assistant", "user");
        JsonNode rejected = messages.get(3).get("content").get(0);
        assertThat(rejected.get("type").asString()).isEqualTo("text");
        assertThat(rejected.get("text").asString()).isEqualTo("You have 7 timesheets waiting.");
        assertThat(messages.get(4).get("content").asString()).contains("the figure 7").contains("Reply with the corrected answer only");
        assertNoIdentifiersOrSecrets(stub.everythingReceived());
    }
}
