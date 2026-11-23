package com.waypoint.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class VoyageEmbeddingClient implements EmbeddingService {

    private final RestClient restClient;
    private final String apiKey;
    private final String apiUrl;
    private final String model;
    private final int expectedDimensions;

    @Autowired
    public VoyageEmbeddingClient(
            RestClient.Builder builder,
            @Value("${waypoint.myhr.ai.voyage.api-key:}") String apiKey,
            @Value("${waypoint.myhr.ai.voyage.api-url:https://api.voyageai.com/v1/embeddings}") String apiUrl,
            @Value("${waypoint.myhr.ai.voyage.model:voyage-4-lite}") String model,
            @Value("${waypoint.myhr.ai.voyage.timeout:30s}") Duration timeout,
            @Value("${waypoint.myhr.ai.voyage.dimensions:1024}") int expectedDimensions) {
        validateApiUrl(apiUrl);
        if (!StringUtils.hasText(model)) {
            throw new IllegalArgumentException("Voyage model name must not be blank");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Voyage timeout must be a positive duration");
        }
        if (expectedDimensions <= 0) {
            throw new IllegalArgumentException("Expected embedding dimensions must be a positive integer");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(timeout);
        this.restClient = builder.clone().requestFactory(factory).build();
        this.apiKey = apiKey;
        this.apiUrl = apiUrl;
        this.model = model;
        this.expectedDimensions = expectedDimensions;
    }

    VoyageEmbeddingClient(RestClient restClient, String apiKey, String apiUrl, String model, int expectedDimensions) {
        if (expectedDimensions <= 0) {
            throw new IllegalArgumentException("Expected embedding dimensions must be a positive integer");
        }
        this.restClient = restClient;
        this.apiKey = apiKey;
        this.apiUrl = apiUrl;
        this.model = model;
        this.expectedDimensions = expectedDimensions;
    }

    @Override
    public List<Double> embed(String text, InputType inputType) {
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException("Embedding input must not be blank");
        }
        if (inputType == null) {
            throw new IllegalArgumentException("InputType must not be null");
        }
        return doEmbed(List.of(text), inputType).get(0);
    }

    @Override
    public List<List<Double>> embedBatch(List<String> texts, InputType inputType) {
        if (texts == null || texts.isEmpty()) {
            throw new IllegalArgumentException("Embedding batch input must not be null or empty");
        }
        for (int i = 0; i < texts.size(); i++) {
            if (!StringUtils.hasText(texts.get(i))) {
                throw new IllegalArgumentException("Embedding batch entry at index " + i + " must not be null or blank");
            }
        }
        if (inputType == null) {
            throw new IllegalArgumentException("InputType must not be null");
        }
        return doEmbed(texts, inputType);
    }

    @Override
    public int dimensions() {
        return expectedDimensions;
    }

    @Override
    public String model() {
        return model;
    }

    private List<List<Double>> doEmbed(List<String> inputs, InputType inputType) {
        if (!StringUtils.hasText(apiKey)) {
            throw new EmbeddingService.EmbeddingConfigurationException();
        }
        if (!StringUtils.hasText(apiUrl)) {
            throw new EmbeddingService.EmbeddingConfigurationException();
        }
        if (!StringUtils.hasText(model)) {
            throw new EmbeddingService.EmbeddingConfigurationException();
        }
        try {
            VoyageEmbedResponse response = restClient.post()
                    .uri(apiUrl)
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new VoyageEmbedRequest(model, inputs, inputType.name().toLowerCase()))
                    .retrieve()
                    .body(VoyageEmbedResponse.class);

            if (response == null || response.data() == null || response.data().isEmpty()) {
                throw new EmbeddingService.EmbeddingUnavailableException();
            }
            if (response.data().size() != inputs.size()) {
                throw new EmbeddingService.EmbeddingUnavailableException();
            }

            Set<Integer> seenIndexes = new HashSet<>();
            for (VoyageEmbeddingData item : response.data()) {
                if (item == null) {
                    throw new EmbeddingService.EmbeddingUnavailableException();
                }
                int idx = item.index();
                if (idx < 0 || idx >= inputs.size() || !seenIndexes.add(idx)) {
                    throw new EmbeddingService.EmbeddingUnavailableException();
                }
            }

            List<List<Double>> vectors = response.data().stream()
                    .sorted(Comparator.comparingInt(VoyageEmbeddingData::index))
                    .map(VoyageEmbeddingData::embedding)
                    .collect(Collectors.toList());

            for (List<Double> vector : vectors) {
                if (vector == null || vector.isEmpty()) {
                    throw new EmbeddingService.EmbeddingUnavailableException();
                }
                if (vector.size() != expectedDimensions) {
                    throw new EmbeddingService.EmbeddingDimensionMismatchException(expectedDimensions, vector.size());
                }
                for (Double value : vector) {
                    if (value == null || !Double.isFinite(value)) {
                        throw new EmbeddingService.EmbeddingUnavailableException();
                    }
                }
            }
            return vectors;

        } catch (EmbeddingService.EmbeddingException e) {
            throw e;
        } catch (RestClientResponseException e) {
            // Response body is not included in the re-thrown exception to prevent leaking provider content.
            HttpStatusCode status = e.getStatusCode();
            if (status.value() == 401 || status.value() == 403) {
                throw new EmbeddingService.EmbeddingConfigurationException();
            }
            if (status.value() == 400) {
                throw new EmbeddingService.EmbeddingRequestException();
            }
            if (status.value() == 429) {
                throw new EmbeddingService.EmbeddingRateLimitedException();
            }
            throw new EmbeddingService.EmbeddingUnavailableException();
        } catch (ResourceAccessException e) {
            throw new EmbeddingService.EmbeddingUnavailableException();
        } catch (RestClientException e) {
            throw new EmbeddingService.EmbeddingUnavailableException();
        }
    }

    private static void validateApiUrl(String apiUrl) {
        if (!StringUtils.hasText(apiUrl)) {
            throw new IllegalArgumentException("Voyage API URL must not be blank");
        }
        URI parsed;
        try {
            parsed = URI.create(apiUrl.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Voyage API URL is not a valid URI");
        }
        if (!"https".equalsIgnoreCase(parsed.getScheme())) {
            throw new IllegalArgumentException("Voyage API URL must use HTTPS");
        }
        if (parsed.getHost() == null || parsed.getHost().isBlank()) {
            throw new IllegalArgumentException("Voyage API URL must include a valid host");
        }
    }

    private record VoyageEmbedRequest(String model, List<String> input, String input_type) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record VoyageEmbedResponse(List<VoyageEmbeddingData> data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record VoyageEmbeddingData(List<Double> embedding, int index) {}
}
