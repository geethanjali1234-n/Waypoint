package com.waypoint.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class WavyModelClientTest {
    @Test void localModelUsesExistingToolContractWithoutSendingAnyPaidCredentials() {
        var builder = RestClient.builder().baseUrl("http://127.0.0.1:11434");
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new WavyModelClient(builder.build(), true, "must-not-leak", "qwen3:4b", "ollama");
        server.expect(requestTo("http://127.0.0.1:11434/api/show"))
                .andExpect(jsonPath("$.model").value("qwen3:4b"))
                .andRespond(withSuccess("{\"capabilities\":[\"completion\",\"tools\"]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://127.0.0.1:11434/api/chat"))
                .andExpect(headerDoesNotExist("x-api-key"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andExpect(jsonPath("$.think").value(false))
                .andExpect(jsonPath("$.stream").value(false))
                .andExpect(jsonPath("$.tools[0].function.name").value("my_team"))
                .andRespond(withSuccess("{\"done\":true,\"done_reason\":\"stop\",\"message\":{\"content\":\"\",\"tool_calls\":[{\"function\":{\"name\":\"my_team\",\"arguments\":{}}}]}}", MediaType.APPLICATION_JSON));
        var request = new ObjectMapper().createObjectNode();
        request.putArray("tools").addObject().put("name", "my_team");
        assertThat(client.isConfigured()).isTrue();
        assertThat(client.complete(request).path("stop_reason").asString()).isEqualTo("tool_use");
        assertThat(request.has("model")).isFalse();
        server.verify();
    }

    @Test void missingLocalModelFailsClosedWithoutAnyPaidFallback() {
        var builder = RestClient.builder().baseUrl("http://127.0.0.1:11434");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://127.0.0.1:11434/api/show")).andRespond(withResourceNotFound());
        var client = new WavyModelClient(builder.build(), true, "paid-key-present", "qwen3:4b", "ollama");
        assertThat(client.isConfigured()).isFalse();
        assertThatThrownBy(() -> client.complete(new ObjectMapper().createObjectNode())).isInstanceOf(IllegalStateException.class);
        server.verify();
    }

    @Test void rejectsCloudAliasesAndModelsWithoutTools() {
        for (String metadata : new String[]{"{\"capabilities\":[\"completion\"]}",
                "{\"capabilities\":[\"completion\",\"tools\"],\"remote_host\":\"https://ollama.com\"}",
                "{\"capabilities\":[\"completion\",\"tools\"],\"remote_model\":\"remote\"}"}) {
            var builder = RestClient.builder().baseUrl("http://127.0.0.1:11434");
            var server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo("http://127.0.0.1:11434/api/show")).andRespond(withSuccess(metadata, MediaType.APPLICATION_JSON));
            assertThat(new WavyModelClient(builder.build(), true, "", "alias", "ollama").isConfigured()).isFalse();
            server.verify();
        }
        assertThat(new WavyModelClient(RestClient.create(), true, "", "model:cloud", "ollama").isConfigured()).isFalse();
    }

    @Test void localProviderRejectsPublicEndpointsAndUrlCredentials() {
        for (String url : new String[]{"https://ollama.com", "https://api.anthropic.com", "http://user:secret@localhost:11434", "http://localhost:11434/proxy"}) {
            assertThatThrownBy(() -> WavyModelClient.validateLocalUrl(url)).isInstanceOf(IllegalArgumentException.class);
        }
        for (String url : new String[]{"http://127.0.0.1:11434", "http://ollama:11434", "http://host.docker.internal:11434", "http://192.168.1.10:11434"}) {
            assertThatCode(() -> WavyModelClient.validateLocalUrl(url)).doesNotThrowAnyException();
        }
    }

    @Test void sendsTheDocumentedMessagesApiContractWithServerSideCredentials() {
        var builder = RestClient.builder().baseUrl("https://api.anthropic.com");
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new WavyModelClient(builder.build(), true, "test-only-key", "test-model");
        server.expect(requestTo("https://api.anthropic.com/v1/messages")).andExpect(method(HttpMethod.POST))
                .andExpect(header("x-api-key", "test-only-key")).andExpect(header("anthropic-version", "2023-06-01"))
                .andExpect(jsonPath("$.model").value("test-model")).andExpect(jsonPath("$.max_tokens").value(700))
                .andRespond(withSuccess("{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"Hello\"}]}", MediaType.APPLICATION_JSON));
        var request = new ObjectMapper().createObjectNode();
        request.putArray("messages").addObject().put("role", "user").put("content", "Hello");
        assertThat(client.complete(request).path("stop_reason").asString()).isEqualTo("end_turn");
        server.verify();
    }

    @Test void incompleteConfigurationCannotCallAnExternalProvider() {
        var client = new WavyModelClient(RestClient.builder().build(), true, "", "");
        assertThat(client.isConfigured()).isFalse();
        assertThatThrownBy(() -> client.complete(new ObjectMapper().createObjectNode())).isInstanceOf(IllegalStateException.class);
    }
}
