package com.waypoint.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.net.URI;
import java.util.Locale;
import java.util.Map;

/** Dedicated opt-in credentials: never reuses the document-extraction key. */
@Service
public class WavyModelClient {
    private final RestClient client;
    private final boolean enabled;
    private final String apiKey;
    private final String model;
    private final String provider;
    private volatile long checkedAt;
    private volatile boolean localReady;

    @Autowired
    public WavyModelClient(RestClient.Builder builder,
                           @Value("${waypoint.wavy.enabled:true}") boolean enabled,
                           @Value("${waypoint.wavy.api-key:}") String apiKey,
                           @Value("${waypoint.wavy.model:qwen3:4b-instruct-2507-q4_K_M}") String model,
                           @Value("${waypoint.wavy.provider:ollama}") String provider,
                           @Value("${waypoint.wavy.ollama-url:http://127.0.0.1:11434}") String ollamaUrl) {
        this.provider = provider.toLowerCase(Locale.ROOT);
        if (!this.provider.equals("ollama") && !this.provider.equals("anthropic")) throw new IllegalArgumentException("Unsupported Wavy provider");
        if (this.provider.equals("ollama")) validateLocalUrl(ollamaUrl);
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(this.provider.equals("ollama") ? 30 : 15));
        this.client = builder.clone().requestFactory(factory).baseUrl(this.provider.equals("ollama") ? ollamaUrl : "https://api.anthropic.com").build();
        this.enabled = enabled;
        this.apiKey = apiKey;
        this.model = model;
    }

    WavyModelClient(RestClient client, boolean enabled, String apiKey, String model) {
        this(client, enabled, apiKey, model, "anthropic");
    }

    WavyModelClient(RestClient client, boolean enabled, String apiKey, String model, String provider) {
        this.client = client;
        this.enabled = enabled;
        this.apiKey = apiKey;
        this.model = model;
        this.provider = provider;
    }

    public boolean isConfigured() {
        if (!enabled || !StringUtils.hasText(model)) return false;
        if (provider.equals("anthropic")) return StringUtils.hasText(apiKey);
        if (!provider.equals("ollama") || model.toLowerCase(Locale.ROOT).contains("cloud")) return false;
        return localModelReady();
    }

    private synchronized boolean localModelReady() {
        long now = System.currentTimeMillis();
        if (now - checkedAt < 15000) return localReady;
        try {
            JsonNode details = client.post().uri("/api/show").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("model", model)).retrieve().body(JsonNode.class);
            boolean tools = false;
            boolean completion = false;
            if (details != null) for (JsonNode capability : details.path("capabilities")) {
                tools |= "tools".equals(capability.asString());
                completion |= "completion".equals(capability.asString());
            }
            // Reject cloud-backed aliases as well as names containing 'cloud'. No paid fallback.
            localReady = details != null && tools && completion
                    && !StringUtils.hasText(details.path("remote_host").asString(""))
                    && !StringUtils.hasText(details.path("remote_model").asString(""));
        } catch (RuntimeException unavailable) { localReady = false; }
        checkedAt = now;
        return localReady;
    }

    static void validateLocalUrl(String value) {
        URI uri = URI.create(value);
        String host = uri.getHost();
        boolean privateHost = host != null && (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]")
                || host.equals("ollama") || host.equals("host.docker.internal")
                || host.matches("10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")
                || host.matches("192\\.168\\.\\d{1,3}\\.\\d{1,3}")
                || host.matches("172\\.(1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3}"));
        if (!privateHost || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) {
            throw new IllegalArgumentException("Wavy Ollama URL must point to a local or private Ollama server");
        }
    }

    public JsonNode complete(ObjectNode request) {
        if (!isConfigured()) throw new IllegalStateException("Wavy AI is not configured");
        if (provider.equals("ollama")) {
            JsonNode response = client.post().uri("/api/chat").contentType(MediaType.APPLICATION_JSON)
                    .body(WavyOllamaProtocol.encode(request, model)).retrieve().body(JsonNode.class);
            return WavyOllamaProtocol.decode(response);
        }
        ObjectNode body = request.deepCopy();
        body.put("model", model);
        body.put("max_tokens", 700);
        body.put("stream", false);
        return client.post().uri("/v1/messages").headers(headers -> {
                    if (provider.equals("anthropic")) headers.set("x-api-key", apiKey);
                })
                .header("anthropic-version", "2023-06-01").contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(JsonNode.class);
    }
}
