package com.waypoint.service;

import org.junit.jupiter.api.Test;
import com.waypoint.dto.myhr.MyHrChatHistoryMessage;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import org.springframework.http.HttpMethod;

class OllamaClientTest {
    @Test
    void mapsSuccessfulOllamaChatResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://ollama.test/api/chat"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.model").value("llama3.1:8b"))
                .andExpect(jsonPath("$.stream").value(false))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andRespond(withSuccess("{\"message\":{\"role\":\"assistant\",\"content\":\"Hello from model\"}}", APPLICATION_JSON));
        assertThat(client(builder).chat("hello"))
                .isEqualTo("Hello from model");
        server.verify();
    }

    @Test
    void sendsSystemPromptThenHistoryThenCurrentMessageExactlyOnce() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://ollama.test/api/chat"))
                .andExpect(jsonPath("$.messages.length()").value(4))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                // The same prompt Claude receives, so the fallback describes the same capabilities.
                .andExpect(jsonPath("$.messages[0].content").value(MyHrAssistantPrompt.UNGROUNDED_SYSTEM_PROMPT))
                .andExpect(jsonPath("$.messages[0].content").value(org.hamcrest.Matchers.containsString("No approved company-policy documents")))
                .andExpect(jsonPath("$.messages[0].content").value(org.hamcrest.Matchers.containsString("Waypoint's Profile Change Requests workflow")))
                .andExpect(jsonPath("$.messages[0].content").value(org.hamcrest.Matchers.containsString("do not recommend external HR systems")))
                .andExpect(jsonPath("$.messages[0].content").value(org.hamcrest.Matchers.containsString("must not claim a request was submitted")))
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andExpect(jsonPath("$.messages[1].content").value("My favorite color is blue."))
                .andExpect(jsonPath("$.messages[2].role").value("assistant"))
                .andExpect(jsonPath("$.messages[2].content").value("Got it."))
                .andExpect(jsonPath("$.messages[3].role").value("user"))
                .andExpect(jsonPath("$.messages[3].content").value("What is my favorite color?"))
                .andRespond(withSuccess("{\"message\":{\"role\":\"assistant\",\"content\":\"Blue\"}}", APPLICATION_JSON));
        assertThat(client(builder).chat("What is my favorite color?", java.util.List.of(
                new MyHrChatHistoryMessage("user", "My favorite color is blue."),
                new MyHrChatHistoryMessage("assistant", "Got it."))))
                .isEqualTo("Blue");
        server.verify();
    }

    @Test
    void mapsOllamaHttpErrorToSafeException() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://ollama.test/api/chat"))
                .andRespond(withServerError());
        assertThatThrownBy(() -> client(builder).chat("hello"))
                .isInstanceOf(OllamaClient.OllamaUnavailableException.class)
                .hasNoCause();
        server.verify();
    }

    @Test
    void rejectsEmptyModelResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://ollama.test/api/chat"))
                .andRespond(withSuccess("{\"message\":{\"role\":\"assistant\",\"content\":\" \"}}", APPLICATION_JSON));
        assertThatThrownBy(() -> client(builder).chat("hello"))
                .isInstanceOf(OllamaClient.OllamaUnavailableException.class);
        server.verify();
    }

    // --- Latency-related request options ---------------------------------------------------------------------

    @Test
    void generalAndGroundedRequestsUseTheSameContextSizeSoTheModelIsNotReloadedBetweenThem() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://localhost:11434/api/chat"))
                .andExpect(jsonPath("$.options.num_ctx").value(OllamaClient.GROUNDED_CONTEXT_TOKENS))
                .andExpect(jsonPath("$.options.num_predict").doesNotExist())
                .andExpect(jsonPath("$.keep_alive").doesNotExist())
                .andRespond(withSuccess("{\"message\":{\"role\":\"assistant\",\"content\":\"General\"}}", APPLICATION_JSON));
        expectLocalModel(server);
        server.expect(requestTo("http://localhost:11434/api/chat"))
                .andExpect(jsonPath("$.options.num_ctx").value(OllamaClient.GROUNDED_CONTEXT_TOKENS))
                .andExpect(jsonPath("$.options.num_predict").value(OllamaClient.DEFAULT_GROUNDED_MAX_TOKENS))
                .andExpect(jsonPath("$.keep_alive").doesNotExist())
                .andRespond(withSuccess("{\"message\":{\"role\":\"assistant\",\"content\":\"Grounded [S1]\"},"
                        + "\"done_reason\":\"stop\"}", APPLICATION_JSON));
        OllamaClient local = new OllamaClient(builder.baseUrl("http://localhost:11434").build(), "llama3.1:8b",
                "http://localhost:11434");

        assertThat(local.chat("hello")).isEqualTo("General");
        assertThat(local.chat(groundedPrompt())).isEqualTo("Grounded [S1]");
        server.verify();
    }

    @Test
    void aGroundedAnswerCutOffAtTheOutputLimitIsNeverReturned() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        expectLocalModel(server);
        server.expect(requestTo("http://localhost:11434/api/chat"))
                .andRespond(withSuccess("{\"message\":{\"role\":\"assistant\",\"content\":\"Employees may carry\"},"
                        + "\"done_reason\":\"length\"}", APPLICATION_JSON));
        OllamaClient local = new OllamaClient(builder.baseUrl("http://localhost:11434").build(), "llama3.1:8b",
                "http://localhost:11434");

        assertThatThrownBy(() -> local.chat(groundedPrompt()))
                .isInstanceOf(OllamaClient.OllamaIncompleteResponseException.class)
                .isInstanceOf(OllamaClient.OllamaUnavailableException.class);
        server.verify();
    }

    @Test
    void aConfiguredKeepAliveAndOutputLimitAreSentAndInvalidValuesAreRejectedAtStartup() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        expectLocalModel(server);
        server.expect(requestTo("http://localhost:11434/api/chat"))
                .andExpect(jsonPath("$.keep_alive").value("30m"))
                .andExpect(jsonPath("$.options.num_predict").value(512))
                .andRespond(withSuccess("{\"message\":{\"role\":\"assistant\",\"content\":\"Ok [S1]\"}}", APPLICATION_JSON));
        OllamaClient tuned = new OllamaClient(builder.baseUrl("http://localhost:11434").build(), "llama3.1:8b",
                "http://localhost:11434", 512, "30m");

        assertThat(tuned.chat(groundedPrompt())).isEqualTo("Ok [S1]");
        server.verify();
        RestClient plain = RestClient.builder().build();
        assertThatThrownBy(() -> new OllamaClient(plain, "m", "http://localhost:11434", 10, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OllamaClient(plain, "m", "http://localhost:11434", 512, "forever"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static MyHrGroundedPrompt groundedPrompt() {
        return new MyHrGroundedPrompt("system", java.util.List.of(), "<policy_context>x</policy_context>", true);
    }

    private static void expectLocalModel(MockRestServiceServer server) {
        server.expect(requestTo("http://localhost:11434/api/show"))
                .andRespond(withSuccess("{\"model_info\":{\"general.architecture\":\"llama\"}}", APPLICATION_JSON));
    }

    private OllamaClient client(RestClient.Builder builder) {
        return new OllamaClient(builder.baseUrl("http://ollama.test").build(), "llama3.1:8b");
    }
}
