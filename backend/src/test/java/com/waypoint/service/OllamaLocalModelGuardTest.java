package com.waypoint.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OllamaLocalModelGuardTest {
    @Test void acceptsOnlyVerifiedLocalModelMetadata() {
        var builder = RestClient.builder().baseUrl("http://127.0.0.1:11434");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://127.0.0.1:11434/api/show"))
                .andRespond(withSuccess("{\"model_info\":{\"general.architecture\":\"llama\"}}", APPLICATION_JSON));
        var client = new OllamaClient(builder.build(), "llama3", "http://127.0.0.1:11434");
        assertThat(client.verifiedLocalModel()).isTrue();
        server.verify();
    }

    @Test void rejectsRemoteBackedModelAndMissingMetadata() {
        var builder = RestClient.builder().baseUrl("http://127.0.0.1:11434");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://127.0.0.1:11434/api/show"))
                .andRespond(withSuccess("{\"model_info\":{\"general.architecture\":\"x\"},\"remote_host\":\"remote.example\"}", APPLICATION_JSON));
        server.expect(requestTo("http://127.0.0.1:11434/api/show"))
                .andRespond(withSuccess("{}", APPLICATION_JSON));
        var client = new OllamaClient(builder.build(), "llama3", "http://127.0.0.1:11434");
        assertThat(client.verifiedLocalModel()).isFalse();
        assertThat(client.verifiedLocalModel()).isFalse();
        server.verify();
    }

    @Test void neverQueriesPrivateOrCloudEndpointsForFallback() {
        var builder = RestClient.builder().baseUrl("http://10.0.0.1:11434");
        var server = MockRestServiceServer.bindTo(builder).build();
        assertThat(new OllamaClient(builder.build(), "llama3", "http://10.0.0.1:11434").verifiedLocalModel()).isFalse();
        assertThat(new OllamaClient(builder.build(), "model-cloud", "http://127.0.0.1:11434").verifiedLocalModel()).isFalse();
        server.verify();
    }

    @Test void routingDoesNotSendQuestionsToRemoteBackedModels() {
        var builder = RestClient.builder().baseUrl("http://127.0.0.1:11434");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://127.0.0.1:11434/api/show"))
                .andRespond(withSuccess("{\"model_info\":{\"general.architecture\":\"x\"},\"remote_host\":\"remote.example\"}", APPLICATION_JSON));
        var client = new OllamaClient(builder.build(), "llama3", "http://127.0.0.1:11434");
        assertThatThrownBy(() -> client.routeJson("system", "private question", java.util.Map.of(), 64))
                .isInstanceOf(OllamaClient.PolicyContextNotLocalException.class);
        server.verify();
    }
}
