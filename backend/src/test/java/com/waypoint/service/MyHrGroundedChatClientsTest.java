package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrChatHistoryMessage;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Request-shape tests against MockRestServiceServer; no real Claude or Ollama endpoint is contacted. */
class MyHrGroundedChatClientsTest {

    private static final String CLAUDE_URL = "https://claude.test/v1/messages";
    private static final String OLLAMA_BASE = "http://localhost:11434";
    private static final String OLLAMA_URL = OLLAMA_BASE + "/api/chat";
    private static final MyHrGroundedPrompt PROMPT = new MyHrGroundedPrompt(
            "GROUNDED SYSTEM RULES",
            List.of(new MyHrChatHistoryMessage("user", "Hello"), new MyHrChatHistoryMessage("assistant", "Hi there.")),
            "<policy_context>\n<source id=\"1\" title=\"Leave Policy\">\n<excerpt>\nPolicy text.\n</excerpt>\n</source>\n"
                    + "</policy_context>\n\n<employee_question>\nHow much leave?\n</employee_question>");

    @Test
    void claudeGroundedRequestSendsThePromptsSystemTextHistoryAndContextMessage() {
      try (var reservation = org.mockito.Mockito.mockStatic(ClaudeSpendLimiter.class)) {
        reservation.when(() -> ClaudeSpendLimiter.fromSettings(org.mockito.Mockito.anyDouble(), org.mockito.Mockito.any()))
                .thenCallRealMethod();
        reservation.when(() -> ClaudeSpendLimiter.closed(org.mockito.Mockito.anyDouble())).thenCallRealMethod();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(CLAUDE_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.system").value("GROUNDED SYSTEM RULES"))
                .andExpect(jsonPath("$.max_tokens").value(ClaudeChatClient.DEFAULT_MAX_TOKENS))
                .andExpect(jsonPath("$.messages.length()").value(3))
                .andExpect(jsonPath("$.messages[0].content").value("Hello"))
                .andExpect(jsonPath("$.messages[1].role").value("assistant"))
                .andExpect(jsonPath("$.messages[2].role").value("user"))
                .andExpect(jsonPath("$.messages[2].content").value(PROMPT.userMessage()))
                .andRespond(withSuccess("{\"content\":[{\"type\":\"text\",\"text\":\"You get 20 days [1].\"}]}",
                        MediaType.APPLICATION_JSON));

        assertThat(claude(builder, "test-key").chat(PROMPT)).isEqualTo("You get 20 days [1].");
        server.verify();
      }
    }

    @Test
    void claudeGroundedRequestWithoutAKeyFailsBeforeSendingPolicyText() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        assertThatThrownBy(() -> claude(builder, " ").chat(PROMPT))
                .isInstanceOf(ClaudeChatClient.ClaudeConfigurationException.class);
        server.verify();
    }

    @Test
    void ollamaGroundedRequestSendsTheSamePromptWithAnExplicitContextWindow() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(OLLAMA_BASE + "/api/show"))
                .andRespond(withSuccess("{\"model_info\":{\"general.architecture\":\"llama\"}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(OLLAMA_URL))
                .andExpect(jsonPath("$.stream").value(false))
                .andExpect(jsonPath("$.messages.length()").value(4))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[0].content").value("GROUNDED SYSTEM RULES"))
                .andExpect(jsonPath("$.messages[1].content").value("Hello"))
                .andExpect(jsonPath("$.messages[3].role").value("user"))
                .andExpect(jsonPath("$.messages[3].content").value(PROMPT.userMessage()))
                .andExpect(jsonPath("$.options.num_ctx").value(OllamaClient.GROUNDED_CONTEXT_TOKENS))
                .andRespond(withSuccess("{\"message\":{\"role\":\"assistant\",\"content\":\" You get 20 days [1]. \"}}",
                        MediaType.APPLICATION_JSON));

        assertThat(ollama(builder).chat(PROMPT)).isEqualTo("You get 20 days [1].");
        server.verify();
    }

    @Test
    void ungroundedOllamaRequestKeepsItsPromptAndSharesOnlyTheContextSize() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(OLLAMA_URL))
                // Same num_ctx as grounded requests, so the model is not reloaded between them; no output limit.
                .andExpect(jsonPath("$.options.num_ctx").value(OllamaClient.GROUNDED_CONTEXT_TOKENS))
                .andExpect(jsonPath("$.options.num_predict").doesNotExist())
                .andExpect(jsonPath("$.messages[0].content").value(org.hamcrest.Matchers.containsString("No approved company-policy documents are available")))
                .andRespond(withSuccess("{\"message\":{\"role\":\"assistant\",\"content\":\"Hi\"}}", MediaType.APPLICATION_JSON));

        assertThat(ollama(builder).chat("hello", List.of())).isEqualTo("Hi");
        server.verify();
    }

    @Test
    void ollamaGroundedFailureIsReportedAsUnavailable() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(OLLAMA_BASE + "/api/show"))
                .andRespond(withSuccess("{\"model_info\":{\"general.architecture\":\"llama\"}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(OLLAMA_URL)).andRespond(withServerError());

        assertThatThrownBy(() -> ollama(builder).chat(PROMPT)).isInstanceOf(OllamaClient.OllamaUnavailableException.class);
        server.verify();
    }

    @Test
    void aRemoteOrUnverifiedOllamaNeverReceivesPolicyTextButStillServesPromptsWithoutIt() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OllamaClient remote = new OllamaClient(builder.baseUrl("http://gpu-box.example.com:11434").build(),
                "llama3.1:8b", "http://gpu-box.example.com:11434");

        assertThat(remote.isLocalEndpoint()).isFalse();
        assertThatThrownBy(() -> remote.chat(PROMPT)).isInstanceOf(OllamaClient.PolicyContextNotLocalException.class);
        server.verify();

        server.expect(requestTo("http://gpu-box.example.com:11434/api/chat"))
                .andRespond(withSuccess("{\"message\":{\"role\":\"assistant\",\"content\":\"Hi\"}}", MediaType.APPLICATION_JSON));
        MyHrGroundedPrompt withoutPolicy = new MyHrGroundedPrompt("RULES", List.of(),
                "<employee_question>\nhello\n</employee_question>", false);
        assertThat(remote.chat(withoutPolicy)).isEqualTo("Hi");
        server.verify();

        RestClient.Builder unknown = RestClient.builder();
        MockRestServiceServer unknownServer = MockRestServiceServer.bindTo(unknown).build();
        OllamaClient unverified = new OllamaClient(unknown.baseUrl(OLLAMA_BASE).build(), "llama3.1:8b");
        assertThat(unverified.isLocalEndpoint()).isFalse();
        assertThatThrownBy(() -> unverified.chat(PROMPT)).isInstanceOf(OllamaClient.PolicyContextNotLocalException.class);
        unknownServer.verify();
    }

    @Test
    void onlyLoopbackLiteralsAndLocalhostCountAsLocal() {
        for (String local : new String[]{"http://localhost:11434", "http://LOCALHOST", "https://127.0.0.1:11434",
                "http://127.10.20.30", "http://[::1]:11434", " http://localhost:11434/ "}) {
            assertThat(OllamaClient.isLoopbackUrl(local)).as(local).isTrue();
        }
        for (String remote : new String[]{null, "", "localhost:11434", "ftp://localhost", "http://ollama.internal:11434",
                "http://host.docker.internal:11434", "http://10.0.0.5:11434", "http://128.0.0.1",
                "http://127.0.0.1.attacker.example", "http://user@localhost:11434", "http://localhost.example.com",
                "not a url"}) {
            assertThat(OllamaClient.isLoopbackUrl(remote)).as(String.valueOf(remote)).isFalse();
        }
    }

    private static ClaudeChatClient claude(RestClient.Builder builder, String key) {
        return new ClaudeChatClient(builder.baseUrl(CLAUDE_URL.substring(0, CLAUDE_URL.indexOf("/v1"))).build(),
                key, CLAUDE_URL, "claude-sonnet-5");
    }

    private static OllamaClient ollama(RestClient.Builder builder) {
        return new OllamaClient(builder.baseUrl(OLLAMA_BASE).build(), "llama3.1:8b", OLLAMA_BASE);
    }
}
