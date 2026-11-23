package com.waypoint.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class VoyageEmbeddingClientTest {

    private static final String API_URL = "https://voyage-embed.test/v1/embeddings";

    // --- Happy path ---

    @Test
    void embedsDocumentTextAndReturnsDimensionMatchedVector() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API_URL))
                .andRespond(withSuccess(
                        "{\"data\":[{\"embedding\":[0.1,-0.2,0.3],\"index\":0}]}",
                        MediaType.APPLICATION_JSON));

        assertThat(client(builder, "test-key").embed("What is the leave policy?", EmbeddingService.InputType.DOCUMENT))
                .containsExactly(0.1, -0.2, 0.3);
        server.verify();
    }

    @Test
    void embedsBatchAndReturnsVectorsInIndexOrder() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API_URL))
                .andRespond(withSuccess(
                        "{\"data\":[{\"embedding\":[0.4,0.5,0.6],\"index\":1},{\"embedding\":[0.1,-0.2,0.3],\"index\":0}]}",
                        MediaType.APPLICATION_JSON));

        List<List<Double>> result = client(builder, "test-key").embedBatch(
                List.of("chunk one", "chunk two"), EmbeddingService.InputType.DOCUMENT);

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).containsExactly(0.1, -0.2, 0.3);
        assertThat(result.get(1)).containsExactly(0.4, 0.5, 0.6);
        server.verify();
    }

    // --- Request shape ---

    @Test
    void sendsAuthorizationHeaderModelAndInputInRequest() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.model").value("voyage-4-lite"))
                .andExpect(jsonPath("$.input[0]").value("sample text"))
                .andRespond(withSuccess(
                        "{\"data\":[{\"embedding\":[0.1,-0.2,0.3],\"index\":0}]}",
                        MediaType.APPLICATION_JSON));

        client(builder, "test-key").embed("sample text", EmbeddingService.InputType.DOCUMENT);
        server.verify();
    }

    @Test
    void usesDocumentInputTypeForDocumentEmbedding() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API_URL))
                .andExpect(jsonPath("$.input_type").value("document"))
                .andRespond(withSuccess(
                        "{\"data\":[{\"embedding\":[0.1,-0.2,0.3],\"index\":0}]}",
                        MediaType.APPLICATION_JSON));

        client(builder, "test-key").embed("policy text", EmbeddingService.InputType.DOCUMENT);
        server.verify();
    }

    @Test
    void usesQueryInputTypeForQueryEmbedding() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API_URL))
                .andExpect(jsonPath("$.input_type").value("query"))
                .andRespond(withSuccess(
                        "{\"data\":[{\"embedding\":[0.1,-0.2,0.3],\"index\":0}]}",
                        MediaType.APPLICATION_JSON));

        client(builder, "test-key").embed("What is the leave policy?", EmbeddingService.InputType.QUERY);
        server.verify();
    }

    // --- Input validation (no HTTP call) ---

    @Test
    void missingApiKeyThrowsConfigurationExceptionBeforeHttpCall() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        assertThatThrownBy(() -> client(builder, " ").embed("text", EmbeddingService.InputType.QUERY))
                .isInstanceOf(EmbeddingService.EmbeddingConfigurationException.class);
        server.verify();
    }

    @Test
    void rejectsBlankTextWithoutMakingHttpCall() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        assertThatThrownBy(() -> client(builder, "test-key").embed("  ", EmbeddingService.InputType.DOCUMENT))
                .isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }

    @Test
    void rejectsNullInputTypeInEmbedWithoutMakingHttpCall() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        assertThatThrownBy(() -> client(builder, "test-key").embed("text", null))
                .isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }

    @Test
    void rejectsEmptyBatchWithoutMakingHttpCall() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        assertThatThrownBy(() -> client(builder, "test-key").embedBatch(List.of(), EmbeddingService.InputType.DOCUMENT))
                .isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }

    @Test
    void rejectsNullEntryInBatchWithoutMakingHttpCall() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        assertThatThrownBy(() -> client(builder, "test-key").embedBatch(
                Arrays.asList("valid text", null, "another text"), EmbeddingService.InputType.DOCUMENT))
                .isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }

    @Test
    void rejectsBlankEntryInBatchWithoutMakingHttpCall() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        assertThatThrownBy(() -> client(builder, "test-key").embedBatch(
                List.of("valid text", "  ", "another text"), EmbeddingService.InputType.DOCUMENT))
                .isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }

    @Test
    void rejectsNullInputTypeInEmbedBatchWithoutMakingHttpCall() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        assertThatThrownBy(() -> client(builder, "test-key").embedBatch(List.of("text"), null))
                .isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }

    // --- Configuration validation (no HTTP call) ---

    @Test
    void rejectsBlankApiUrlBeforeHttpCall() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        VoyageEmbeddingClient clientWithBlankUrl =
                new VoyageEmbeddingClient(builder.build(), "test-key", " ", "voyage-4-lite", 3);

        assertThatThrownBy(() -> clientWithBlankUrl.embed("text", EmbeddingService.InputType.QUERY))
                .isInstanceOf(EmbeddingService.EmbeddingConfigurationException.class);
        server.verify();
    }

    @Test
    void rejectsBlankModelBeforeHttpCall() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        VoyageEmbeddingClient clientWithBlankModel =
                new VoyageEmbeddingClient(builder.build(), "test-key", API_URL, " ", 3);

        assertThatThrownBy(() -> clientWithBlankModel.embed("text", EmbeddingService.InputType.QUERY))
                .isInstanceOf(EmbeddingService.EmbeddingConfigurationException.class);
        server.verify();
    }

    @Test
    void rejectsNonPositiveDimensionsInConstructor() {
        assertThatThrownBy(() -> new VoyageEmbeddingClient(
                RestClient.builder().build(), "test-key", API_URL, "voyage-4-lite", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publicConstructorRejectsNonHttpsUrl() {
        assertThatThrownBy(() -> new VoyageEmbeddingClient(
                RestClient.builder(),
                "key",
                "http://api.voyageai.com/v1/embeddings",
                "voyage-4-lite",
                Duration.ofSeconds(30),
                1024))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publicConstructorRejectsMalformedUrl() {
        assertThatThrownBy(() -> new VoyageEmbeddingClient(
                RestClient.builder(),
                "key",
                "not a url with spaces",
                "voyage-4-lite",
                Duration.ofSeconds(30),
                1024))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- HTTP error mapping ---

    @Test
    void mapsServerErrorToUnavailableException() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API_URL)).andRespond(withServerError());

        assertThatThrownBy(() -> client(builder, "test-key").embed("text", EmbeddingService.InputType.QUERY))
                .isInstanceOf(EmbeddingService.EmbeddingUnavailableException.class);
        server.verify();
    }

    @Test
    void mapsConnectionFailureToUnavailableException() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API_URL))
                .andRespond(withException(new IOException("simulated connection failure")));

        assertThatThrownBy(() -> client(builder, "test-key").embed("text", EmbeddingService.InputType.QUERY))
                .isInstanceOf(EmbeddingService.EmbeddingUnavailableException.class);
        server.verify();
    }

    @Test
    void mapsUnauthorizedToConfigurationException() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API_URL)).andRespond(withStatus(UNAUTHORIZED));

        assertThatThrownBy(() -> client(builder, "bad-key").embed("text", EmbeddingService.InputType.QUERY))
                .isInstanceOf(EmbeddingService.EmbeddingConfigurationException.class);
        server.verify();
    }

    @Test
    void mapsForbiddenToConfigurationException() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API_URL)).andRespond(withStatus(FORBIDDEN));

        assertThatThrownBy(() -> client(builder, "bad-key").embed("text", EmbeddingService.InputType.QUERY))
                .isInstanceOf(EmbeddingService.EmbeddingConfigurationException.class);
        server.verify();
    }

    @Test
    void mapsRateLimitToARateLimitedUnavailableExceptionWithoutProviderDetails() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API_URL)).andRespond(withStatus(TOO_MANY_REQUESTS)
                .body("{\"detail\":\"rate limit for test-key\"}").contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client(builder, "test-key").embed("text", EmbeddingService.InputType.QUERY))
                .isInstanceOf(EmbeddingService.EmbeddingRateLimitedException.class)
                .isInstanceOf(EmbeddingService.EmbeddingUnavailableException.class)
                .hasMessage(null)
                .hasNoCause();
        server.verify();
    }

    @Test
    void mapsBadRequestToRequestException() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API_URL)).andRespond(withStatus(BAD_REQUEST));

        assertThatThrownBy(() -> client(builder, "test-key").embed("text", EmbeddingService.InputType.DOCUMENT))
                .isInstanceOf(EmbeddingService.EmbeddingRequestException.class);
        server.verify();
    }

    // --- Response validation ---

    @Test
    void rejectsEmptyDataArrayInResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API_URL))
                .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client(builder, "test-key").embed("text", EmbeddingService.InputType.DOCUMENT))
                .isInstanceOf(EmbeddingService.EmbeddingUnavailableException.class);
        server.verify();
    }

    @Test
    void rejectsWrongDimensionCountWithMismatchException() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API_URL))
                .andRespond(withSuccess(
                        "{\"data\":[{\"embedding\":[0.1,0.2],\"index\":0}]}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client(builder, "test-key").embed("text", EmbeddingService.InputType.DOCUMENT))
                .isInstanceOf(EmbeddingService.EmbeddingDimensionMismatchException.class)
                .hasMessageContaining("3")
                .hasMessageContaining("2");
        server.verify();
    }

    @Test
    void batchResultCountMismatchThrowsUnavailableException() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API_URL))
                .andRespond(withSuccess(
                        "{\"data\":[{\"embedding\":[0.1,-0.2,0.3],\"index\":0}]}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client(builder, "test-key").embedBatch(
                List.of("chunk one", "chunk two"), EmbeddingService.InputType.DOCUMENT))
                .isInstanceOf(EmbeddingService.EmbeddingUnavailableException.class);
        server.verify();
    }

    @Test
    void rejectsDuplicateIndexesInResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API_URL))
                .andRespond(withSuccess(
                        "{\"data\":[{\"embedding\":[0.1,-0.2,0.3],\"index\":0},{\"embedding\":[0.4,0.5,0.6],\"index\":0}]}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client(builder, "test-key").embedBatch(
                List.of("text1", "text2"), EmbeddingService.InputType.DOCUMENT))
                .isInstanceOf(EmbeddingService.EmbeddingUnavailableException.class);
        server.verify();
    }

    @Test
    void rejectsOutOfBoundsIndexInResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API_URL))
                .andRespond(withSuccess(
                        "{\"data\":[{\"embedding\":[0.1,-0.2,0.3],\"index\":5}]}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client(builder, "test-key").embed("text", EmbeddingService.InputType.DOCUMENT))
                .isInstanceOf(EmbeddingService.EmbeddingUnavailableException.class);
        server.verify();
    }

    @Test
    void rejectsNullDataEntryInResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API_URL))
                .andRespond(withSuccess("{\"data\":[null]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client(builder, "test-key").embed("text", EmbeddingService.InputType.DOCUMENT))
                .isInstanceOf(EmbeddingService.EmbeddingUnavailableException.class);
        server.verify();
    }

    @Test
    void rejectsNullEmbeddingValueInResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API_URL))
                .andRespond(withSuccess(
                        "{\"data\":[{\"embedding\":[0.1,null,0.3],\"index\":0}]}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client(builder, "test-key").embed("text", EmbeddingService.InputType.DOCUMENT))
                .isInstanceOf(EmbeddingService.EmbeddingUnavailableException.class);
        server.verify();
    }

    // --- Metadata ---

    @Test
    void dimensionsMethodReturnsConfiguredExpectedDimensions() {
        assertThat(client(RestClient.builder(), "test-key").dimensions()).isEqualTo(3);
    }

    @Test
    void modelMethodReturnsConfiguredModelWithoutMakingHttpCall() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        assertThat(client(builder, "test-key").model()).isEqualTo("voyage-4-lite");
        server.verify();
    }

    @Test
    void modelMethodReturnsTheModelSentInRequests() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        VoyageEmbeddingClient client = new VoyageEmbeddingClient(
                builder.baseUrl("https://voyage-embed.test").build(), "test-key", API_URL, "voyage-test-model", 3);
        server.expect(requestTo(API_URL))
                .andExpect(jsonPath("$.model").value(client.model()))
                .andRespond(withSuccess(
                        "{\"data\":[{\"embedding\":[0.1,-0.2,0.3],\"index\":0}]}",
                        MediaType.APPLICATION_JSON));

        client.embed("sample text", EmbeddingService.InputType.DOCUMENT);

        assertThat(client.model()).isEqualTo("voyage-test-model");
        server.verify();
    }

    @Test
    void publicConstructorExposesConfiguredModel() {
        VoyageEmbeddingClient client = new VoyageEmbeddingClient(
                RestClient.builder(), "", API_URL, "voyage-4-lite", Duration.ofSeconds(30), 1024);

        assertThat(client.model()).isEqualTo("voyage-4-lite");
        assertThat(client.dimensions()).isEqualTo(1024);
    }

    private VoyageEmbeddingClient client(RestClient.Builder builder, String apiKey) {
        return new VoyageEmbeddingClient(
                builder.baseUrl("https://voyage-embed.test").build(),
                apiKey,
                API_URL,
                "voyage-4-lite",
                3);
    }
}
