package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrChatHistoryMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ClaudeChatClientTest {
    private static final String API_URL = "https://claude.test/v1/messages";
    private MockedStatic<ClaudeSpendLimiter> reservation;

    @BeforeEach
    void allowOnlyMockTransportTestsToExerciseSerialization() {
        // Every request in this class is intercepted by MockRestServiceServer and uses the reserved .test domain.
        // The override exists solely in test sources and never changes the production gate.
        reservation = Mockito.mockStatic(ClaudeSpendLimiter.class);
        reservation.when(() -> ClaudeSpendLimiter.fromSettings(Mockito.anyDouble(), Mockito.any()))
                .thenCallRealMethod();
        reservation.when(() -> ClaudeSpendLimiter.closed(Mockito.anyDouble())).thenCallRealMethod();
    }

    @AfterEach
    void restoreReservationGate() {
        reservation.close();
    }

    @Test
    void sendsSystemPromptThenHistoryThenCurrentMessageAndParsesText() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-api-key", "test-key"))
                .andExpect(header("anthropic-version", "2023-06-01"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.model").value("claude-sonnet-5"))
                .andExpect(jsonPath("$.system").value(MyHrAssistantPrompt.UNGROUNDED_SYSTEM_PROMPT))
                .andExpect(jsonPath("$.system").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("not connected"))))
                .andExpect(jsonPath("$.system").value(org.hamcrest.Matchers.containsString("Waypoint's Profile Change Requests workflow")))
                .andExpect(jsonPath("$.system").value(org.hamcrest.Matchers.containsString("do not recommend external HR systems")))
                .andExpect(jsonPath("$.system").value(org.hamcrest.Matchers.containsString("must not claim a request was submitted")))
                .andExpect(jsonPath("$.messages.length()").value(3))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[0].content").value("My favorite color is blue."))
                .andExpect(jsonPath("$.messages[1].role").value("assistant"))
                .andExpect(jsonPath("$.messages[1].content").value("Got it."))
                .andExpect(jsonPath("$.messages[2].role").value("user"))
                .andExpect(jsonPath("$.messages[2].content").value("What is my favorite color?"))
                .andRespond(withSuccess("{\"content\":[{\"type\":\"text\",\"text\":\"Your favorite color is blue.\"}]}", MediaType.APPLICATION_JSON));

        assertThat(client(builder, "test-key").chat("What is my favorite color?", List.of(
                new MyHrChatHistoryMessage("user", "My favorite color is blue."),
                new MyHrChatHistoryMessage("assistant", "Got it."))))
                .isEqualTo("Your favorite color is blue.");
        server.verify();
    }

    @Test
    void missingKeyFailsBeforeMakingRequest() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        assertThatThrownBy(() -> client(builder, " ").chat("hello", List.of()))
                .isInstanceOf(ClaudeChatClient.ClaudeConfigurationException.class);
        server.verify();
    }

    @Test
    void unauthorizedKeyIsConfigurationFailureAndNotFallbackEligible() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API_URL)).andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client(builder, "bad-test-key").chat("hello", List.of()))
                .isInstanceOf(ClaudeChatClient.ClaudeConfigurationException.class);
        server.verify();
    }

    @Test
    void timeoutIsTemporaryAndEligibleForFallback() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API_URL)).andRespond(withException(new IOException("simulated timeout")));

        assertThatThrownBy(() -> client(builder, "test-key").chat("hello", List.of()))
                .isInstanceOf(ClaudeChatClient.ClaudeUnavailableException.class);
        server.verify();
    }

    @Test
    void socketTimeoutIsClassifiedSeparatelyWhileRemainingFallbackEligible() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API_URL)).andRespond(withException(new java.net.SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> client(builder, "test-key").chat("hello", List.of()))
                .isInstanceOf(ClaudeChatClient.ClaudeTimeoutException.class)
                .isInstanceOf(ClaudeChatClient.ClaudeUnavailableException.class);
        server.verify();
    }

    @Test
    void requestTimeoutStatusIsClassifiedSeparatelyWhileRemainingFallbackEligible() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API_URL)).andRespond(withStatus(org.springframework.http.HttpStatus.REQUEST_TIMEOUT));

        assertThatThrownBy(() -> client(builder, "test-key").chat("hello", List.of()))
                .isInstanceOf(ClaudeChatClient.ClaudeTimeoutException.class)
                .isInstanceOf(ClaudeChatClient.ClaudeUnavailableException.class);
        server.verify();
    }

    @Test
    void temporaryServerErrorAndRateLimitAreEligibleForFallback() {
        RestClient.Builder serverBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(serverBuilder).build();
        server.expect(requestTo(API_URL)).andRespond(withServerError());
        assertThatThrownBy(() -> client(serverBuilder, "test-key").chat("hello", List.of()))
                .isInstanceOf(ClaudeChatClient.ClaudeUnavailableException.class);
        server.verify();

        RestClient.Builder limitedBuilder = RestClient.builder();
        MockRestServiceServer limitedServer = MockRestServiceServer.bindTo(limitedBuilder).build();
        limitedServer.expect(requestTo(API_URL)).andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS));
        assertThatThrownBy(() -> client(limitedBuilder, "test-key").chat("hello", List.of()))
                .isInstanceOf(ClaudeChatClient.ClaudeUnavailableException.class);
        limitedServer.verify();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {408, 429, 500, 502, 503, 504, 529})
    void everyTemporaryStatusIsEligibleForFallback(int status) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API_URL)).andRespond(withStatus(org.springframework.http.HttpStatusCode.valueOf(status)));

        assertThatThrownBy(() -> client(builder, "test-key").chat("hello", List.of()))
                .isInstanceOf(ClaudeChatClient.ClaudeUnavailableException.class);
        server.verify();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {401, 403})
    void authenticationAndPermissionFailuresAreConfigurationErrorsNotFallbackTriggers(int status) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API_URL)).andRespond(withStatus(org.springframework.http.HttpStatusCode.valueOf(status)));

        assertThatThrownBy(() -> client(builder, "test-key").chat("hello", List.of()))
                .isInstanceOf(ClaudeChatClient.ClaudeConfigurationException.class);
        server.verify();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {400, 404, 413, 422})
    void requestProblemsAreRejectedNotFallbackTriggers(int status) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API_URL)).andRespond(withStatus(org.springframework.http.HttpStatusCode.valueOf(status)));

        assertThatThrownBy(() -> client(builder, "test-key").chat("hello", List.of()))
                .isInstanceOf(ClaudeChatClient.ClaudeRequestRejectedException.class);
        server.verify();
    }

    @Test
    void routerRequestUsesTheCurrentMessagesStructuredOutputContractWithoutToolsOrHistory() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-api-key", "test-key"))
                .andExpect(header("anthropic-version", "2023-06-01"))
                .andExpect(jsonPath("$.model").value(ClaudeChatClient.DEFAULT_ROUTER_MODEL))
                .andExpect(jsonPath("$.max_tokens").value(400))
                .andExpect(jsonPath("$.system.length()").value(1))
                .andExpect(jsonPath("$.system[0].type").value("text"))
                .andExpect(jsonPath("$.system[0].text").value("Router rules"))
                .andExpect(jsonPath("$.system[0].cache_control.type").value("ephemeral"))
                .andExpect(jsonPath("$.messages.length()").value(1))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[0].content").value("Sanitized question"))
                .andExpect(jsonPath("$.thinking.type").value("disabled"))
                .andExpect(jsonPath("$.output_config.effort").value("low"))
                .andExpect(jsonPath("$.output_config.format.type").value("json_schema"))
                .andExpect(jsonPath("$.output_config.format.schema.properties.decision.type").value("object"))
                .andExpect(jsonPath("$.tools").doesNotExist())
                .andExpect(jsonPath("$.messages[1]").doesNotExist())
                .andRespond(withSuccess("{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\","
                        + "\"text\":\"{\\\"decision\\\":{\\\"tool\\\":\\\"clarify\\\",\\\"arguments\\\":\\\"{}\\\"},"
                        + "\\\"confidence\\\":\\\"HIGH\\\",\\\"language\\\":\\\"english\\\"}\"}]}", MediaType.APPLICATION_JSON));

        ClaudeChatClient.RouteResult result = client(builder, "test-key").routeJsonWithUsage("Router rules",
                "Sanitized question", Map.of("type", "object", "properties", Map.of("decision", Map.of("type", "object")),
                        "required", List.of("decision"), "additionalProperties", false), 400, "low");

        assertThat(result.model()).isEqualTo(ClaudeChatClient.DEFAULT_ROUTER_MODEL);
        assertThat(result.json()).contains("\"tool\":\"clarify\"");
        server.verify();
    }

    @Test
    void routerHttp400RetainsOnlyTheSafeSchemaFailureCategory() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API_URL)).andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
                .body("{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"Schema is too complex for compilation.\"}}")
                .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client(builder, "test-key").routeJson("Router rules", "question", Map.of("type", "object"),
                400, "low"))
                .isInstanceOfSatisfying(ClaudeChatClient.ClaudeRequestRejectedException.class,
                        rejected -> assertThat(rejected.failureClass()).isEqualTo("rejected_400:schema_too_complex"));
        server.verify();
    }

    @Test
    void refusalIsReportedAndIsNeverAFallbackTrigger() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API_URL)).andRespond(withSuccess(
                "{\"stop_reason\":\"refusal\",\"content\":[{\"type\":\"text\",\"text\":\"partial\"}]}",
                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client(builder, "test-key").chat("restricted request", List.of()))
                .isInstanceOf(ClaudeChatClient.ClaudeSafetyRefusalException.class)
                .isNotInstanceOf(ClaudeChatClient.ClaudeUnavailableException.class)
                .hasMessage(null);
        server.verify();
    }

    // --- Output-token limit -----------------------------------------------------------------------------------

    @Test
    void sendsTheDefaultOutputTokenLimitThatLeavesRoomForThinking() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API_URL))
                .andExpect(jsonPath("$.max_tokens").value(ClaudeChatClient.DEFAULT_MAX_TOKENS))
                .andExpect(jsonPath("$.temperature").doesNotExist())
                .andRespond(withSuccess("{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"Hi.\"}]}",
                        MediaType.APPLICATION_JSON));

        assertThat(client(builder, "test-key").chat("hello", List.of())).isEqualTo("Hi.");
        assertThat(ClaudeChatClient.DEFAULT_MAX_TOKENS).isEqualTo(4096);
        server.verify();
    }

    @Test
    void sendsTheConfiguredOutputTokenLimitAndRejectsANonPositiveOne() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API_URL)).andExpect(jsonPath("$.max_tokens").value(2500))
                .andRespond(withSuccess("{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"Hi.\"}]}",
                        MediaType.APPLICATION_JSON));

        RestClient restClient = builder.baseUrl(API_URL.substring(0, API_URL.indexOf("/v1"))).build();
        assertThat(new ClaudeChatClient(restClient, "test-key", API_URL, "claude-sonnet-5", 2500).chat("hello", List.of()))
                .isEqualTo("Hi.");
        server.verify();
        for (int invalid : new int[]{0, -1}) {
            assertThatThrownBy(() -> new ClaudeChatClient(restClient, "test-key", API_URL, "claude-sonnet-5", invalid))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("waypoint.myhr.ai.claude.max-tokens must be positive");
        }
    }

    // --- Cited documents (Claude citations) -------------------------------------------------------------------

    @Test
    void sendsEachAuthorizedSourceAsACitableDocumentFollowedByTheQuestion() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API_URL))
                .andExpect(jsonPath("$.system").value("Cited rules."))
                .andExpect(jsonPath("$.max_tokens").value(ClaudeChatClient.DEFAULT_MAX_TOKENS))
                .andExpect(jsonPath("$.messages.length()").value(3))
                .andExpect(jsonPath("$.messages[0].content").value("Earlier question"))
                .andExpect(jsonPath("$.messages[1].content").value("Earlier answer [1]."))
                .andExpect(jsonPath("$.messages[2].role").value("user"))
                .andExpect(jsonPath("$.messages[2].content.length()").value(3))
                .andExpect(jsonPath("$.messages[2].content[0].type").value("document"))
                .andExpect(jsonPath("$.messages[2].content[0].source.type").value("text"))
                .andExpect(jsonPath("$.messages[2].content[0].source.media_type").value("text/plain"))
                .andExpect(jsonPath("$.messages[2].content[0].source.data").value("Synthetic leave text."))
                .andExpect(jsonPath("$.messages[2].content[0].title").value("Synthetic Leave Policy"))
                .andExpect(jsonPath("$.messages[2].content[0].context").doesNotExist())
                .andExpect(jsonPath("$.messages[2].content[0].citations.enabled").value(true))
                .andExpect(jsonPath("$.messages[2].content[1].title").value("Synthetic HR Guidance"))
                .andExpect(jsonPath("$.messages[2].content[1].context").value("HR-only note"))
                .andExpect(jsonPath("$.messages[2].content[1].citations.enabled").value(true))
                .andExpect(jsonPath("$.messages[2].content[2].type").value("text"))
                .andExpect(jsonPath("$.messages[2].content[2].text").value("<employee_question>\nQ\n</employee_question>"))
                .andRespond(withSuccess("{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"A.\"}]}",
                        MediaType.APPLICATION_JSON));

        List<MyHrCitedSegment> segments = client(builder, "test-key").chatWithDocuments(documentPrompt());

        assertThat(segments).extracting(MyHrCitedSegment::text).containsExactly("A.");
        server.verify();
    }

    @Test
    void returnsEveryTextBlockInOrderWithTheCitationsClaudeReported() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API_URL)).andRespond(withSuccess("{\"stop_reason\":\"end_turn\",\"content\":["
                + "{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"sig\"},"
                + "{\"type\":\"text\",\"text\":\"According to the policy, \"},"
                + "{\"type\":\"text\",\"text\":\"you can carry forward 5 days.\",\"citations\":[{\"type\":\"char_location\","
                + "\"cited_text\":\"Synthetic leave text.\",\"document_index\":0,\"document_title\":\"Synthetic Leave Policy\","
                + "\"start_char_index\":0,\"end_char_index\":21,\"file_id\":null}]},"
                + "{\"type\":\"text\",\"text\":\" Ask HR for exceptions.\",\"citations\":null}]}", MediaType.APPLICATION_JSON));

        List<MyHrCitedSegment> segments = client(builder, "test-key").chatWithDocuments(documentPrompt());

        assertThat(segments).extracting(MyHrCitedSegment::text)
                .containsExactly("According to the policy, ", "you can carry forward 5 days.", " Ask HR for exceptions.");
        assertThat(segments.get(0).citations()).isEmpty();
        assertThat(segments.get(1).citations()).containsExactly(
                new MyHrCitedSegment.Citation("char_location", "Synthetic leave text.", 0, 0, 21));
        assertThat(segments.get(2).citations()).isEmpty();
        server.verify();
    }

    @Test
    void citedRequestsUseTheSameCompletenessRefusalAndFailureRules() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API_URL)).andRespond(withSuccess("{\"stop_reason\":\"max_tokens\",\"content\":["
                + "{\"type\":\"text\",\"text\":\"You can\",\"citations\":[{\"type\":\"char_location\",\"cited_text\":\"x\","
                + "\"document_index\":0,\"start_char_index\":0,\"end_char_index\":1}]}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(API_URL)).andRespond(withSuccess(
                "{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(API_URL)).andRespond(withSuccess(
                "{\"stop_reason\":\"refusal\",\"content\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(API_URL)).andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(API_URL)).andRespond(withException(new java.net.SocketTimeoutException("Read timed out")));
        server.expect(requestTo(API_URL)).andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED));
        ClaudeChatClient client = client(builder, "test-key");

        assertIncomplete(() -> client.chatWithDocuments(documentPrompt()), ClaudeChatClient.IncompleteReason.TRUNCATED);
        assertIncomplete(() -> client.chatWithDocuments(documentPrompt()), ClaudeChatClient.IncompleteReason.NO_TEXT);
        assertThatThrownBy(() -> client.chatWithDocuments(documentPrompt()))
                .isInstanceOf(ClaudeChatClient.ClaudeSafetyRefusalException.class);
        assertThatThrownBy(() -> client.chatWithDocuments(documentPrompt()))
                .isInstanceOf(ClaudeChatClient.ClaudeUnavailableException.class);
        assertThatThrownBy(() -> client.chatWithDocuments(documentPrompt()))
                .isInstanceOf(ClaudeChatClient.ClaudeUnavailableException.class);
        assertThatThrownBy(() -> client.chatWithDocuments(documentPrompt()))
                .isInstanceOf(ClaudeChatClient.ClaudeConfigurationException.class);
        server.verify();
    }

    private static MyHrGroundedDocumentPrompt documentPrompt() {
        return new MyHrGroundedDocumentPrompt("Cited rules.", List.of(
                new MyHrChatHistoryMessage("user", "Earlier question"),
                new MyHrChatHistoryMessage("assistant", "Earlier answer [1].")),
                List.of(new MyHrGroundedDocumentPrompt.Document("Synthetic Leave Policy", null, "Synthetic leave text."),
                        new MyHrGroundedDocumentPrompt.Document("Synthetic HR Guidance", "HR-only note", "Synthetic HR text.")),
                "<employee_question>\nQ\n</employee_question>");
    }

    @Test
    void invalidRequestDoesNotBecomeTemporaryFailure() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API_URL)).andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> client(builder, "test-key").chat("hello", List.of()))
                .isInstanceOf(ClaudeChatClient.ClaudeRequestRejectedException.class);
        server.verify();
    }

    // --- Complete answers only -------------------------------------------------------------------------------

    @Test
    void joinsEveryTextBlockInOrderAndSkipsThinkingAndOtherBlocks() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API_URL)).andRespond(withSuccess("{\"stop_reason\":\"end_turn\",\"content\":["
                + "{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"sig\"},"
                + "{\"type\":\"text\",\"text\":\"Open Timesheets, \"},"
                + "{\"type\":\"redacted_thinking\",\"data\":\"opaque\"},"
                + "{\"type\":\"text\",\"text\":\"then choose Enter Timesheet.\\n\\n\"},"
                + "{\"type\":\"text\",\"text\":\"Submit it when the week is complete.\"}]}", MediaType.APPLICATION_JSON));

        assertThat(client(builder, "test-key").chat("How do I submit my timesheet?", List.of()))
                .isEqualTo("Open Timesheets, then choose Enter Timesheet.\n\nSubmit it when the week is complete.");
        server.verify();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"end_turn", "stop_sequence"})
    void aFinishedAnswerIsReturned(String stopReason) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API_URL)).andRespond(withSuccess("{\"stop_reason\":\"" + stopReason
                + "\",\"content\":[{\"type\":\"text\",\"text\":\"  Complete answer.  \"}]}", MediaType.APPLICATION_JSON));

        assertThat(client(builder, "test-key").chat("hello", List.of())).isEqualTo("Complete answer.");
        server.verify();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"max_tokens", "model_context_window_exceeded"})
    void aTruncatedAnswerIsNeverReturnedEvenWithPartialText(String stopReason) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API_URL)).andRespond(withSuccess("{\"stop_reason\":\"" + stopReason
                + "\",\"content\":[{\"type\":\"text\",\"text\":\"Employees may carry forward up to\"}]}",
                MediaType.APPLICATION_JSON));

        assertIncomplete(() -> client(builder, "test-key").chat("hello", List.of()),
                ClaudeChatClient.IncompleteReason.TRUNCATED);
        server.verify();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"pause_turn", "tool_use", "a_future_stop_reason"})
    void anAnswerStoppedForAnyOtherReasonIsNeverReturned(String stopReason) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API_URL)).andRespond(withSuccess("{\"stop_reason\":\"" + stopReason
                + "\",\"content\":[{\"type\":\"text\",\"text\":\"Let me check that.\"}]}", MediaType.APPLICATION_JSON));

        assertIncomplete(() -> client(builder, "test-key").chat("hello", List.of()),
                ClaudeChatClient.IncompleteReason.UNEXPECTED_STOP);
        server.verify();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "{\"stop_reason\":\"end_turn\",\"content\":[]}",
            "{\"stop_reason\":\"end_turn\"}",
            "{\"stop_reason\":\"end_turn\",\"content\":null}",
            "{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"\"}]}",
            "{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"   \"},{\"type\":\"text\"}]}",
            "{\"stop_reason\":\"end_turn\",\"content\":[null]}",
            "{}"})
    void aResponseWithoutAnswerTextIsNeverReturned(String body) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API_URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertIncomplete(() -> client(builder, "test-key").chat("hello", List.of()),
                ClaudeChatClient.IncompleteReason.NO_TEXT);
        server.verify();
    }

    @Test
    void anEmptyResponseBodyIsUnavailable() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API_URL)).andRespond(withSuccess());

        assertThatThrownBy(() -> client(builder, "test-key").chat("hello", List.of()))
                .isInstanceOf(ClaudeChatClient.ClaudeUnavailableException.class)
                .hasMessage(null);
        server.verify();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "{\"content\":[{\"type\":\"text\",\"text\":\"cut off",
            "[]",
            "{\"content\":\"not a list\"}",
            "not json at all"})
    void aMalformedResponseIsUnavailableAndCarriesNoProviderText(String body) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API_URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client(builder, "test-key").chat("hello", List.of()))
                .isInstanceOf(ClaudeChatClient.ClaudeUnavailableException.class)
                .hasMessage(null)
                .hasNoCause();
        server.verify();
    }

    @Test
    void aNonJsonContentTypeIsUnavailable() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API_URL)).andRespond(withSuccess("<html>gateway page</html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> client(builder, "test-key").chat("hello", List.of()))
                .isInstanceOf(ClaudeChatClient.ClaudeUnavailableException.class);
        server.verify();
    }

    @Test
    void aSocketReadTimeoutIsUnavailable() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API_URL)).andRespond(withException(new java.net.SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> client(builder, "test-key").chat("hello", List.of()))
                .isInstanceOf(ClaudeChatClient.ClaudeUnavailableException.class)
                .hasMessage(null);
        server.verify();
    }

    @Test
    void groundedRequestsUseTheSameCompleteAnswerRules() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API_URL))
                .andExpect(jsonPath("$.system").value("Grounded system prompt."))
                .andRespond(withSuccess("{\"stop_reason\":\"max_tokens\",\"content\":[{\"type\":\"text\","
                        + "\"text\":\"Employees may\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(API_URL)).andRespond(withSuccess("{\"stop_reason\":\"end_turn\",\"content\":["
                + "{\"type\":\"text\",\"text\":\"Employees may carry forward \"},{\"type\":\"text\",\"text\":\"5 days [S1].\"}]}",
                MediaType.APPLICATION_JSON));
        ClaudeChatClient client = client(builder, "test-key");
        MyHrGroundedPrompt prompt = new MyHrGroundedPrompt("Grounded system prompt.", List.of(),
                "<employee_question>\nSynthetic question\n</employee_question>", false);

        assertIncomplete(() -> client.chat(prompt), ClaudeChatClient.IncompleteReason.TRUNCATED);
        assertThat(client.chat(prompt)).isEqualTo("Employees may carry forward 5 days [S1].");
        server.verify();
    }

    private static void assertIncomplete(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                                         ClaudeChatClient.IncompleteReason reason) {
        assertThatThrownBy(call)
                // Still a temporary unavailability, so existing callers handle it exactly as before.
                .isInstanceOf(ClaudeChatClient.ClaudeUnavailableException.class)
                .isInstanceOfSatisfying(ClaudeChatClient.ClaudeIncompleteResponseException.class,
                        incomplete -> assertThat(incomplete.reason()).isEqualTo(reason))
                .hasMessage(null);
    }

    private ClaudeChatClient client(RestClient.Builder builder, String apiKey) {
        return new ClaudeChatClient(builder.baseUrl(API_URL.substring(0, API_URL.indexOf("/v1"))).build(),
                apiKey, API_URL, "claude-sonnet-5");
    }
}
