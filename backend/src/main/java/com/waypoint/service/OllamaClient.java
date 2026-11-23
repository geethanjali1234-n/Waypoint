package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrChatHistoryMessage;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

@Component
public class OllamaClient {
    /** Fits the grounded system prompt, bounded policy context and history, question and answer. */
    static final int GROUNDED_CONTEXT_TOKENS = 8192;
    /** The same prompt Claude receives, so a fallback answer describes the same capabilities. */
    private static final String SYSTEM_PROMPT = MyHrAssistantPrompt.UNGROUNDED_SYSTEM_PROMPT;

    private static final java.util.regex.Pattern IPV4_LOOPBACK =
            java.util.regex.Pattern.compile("127(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}");

    /** Default output limit for a grounded (policy) answer; a policy answer needs far fewer tokens. */
    static final int DEFAULT_GROUNDED_MAX_TOKENS = 1024;

    private final RestClient restClient;
    /** Routing calls use their own client, with a short read timeout; tests share the chat client. */
    private RestClient routerRestClient;
    private final String model;
    private final boolean localEndpoint;
    private final int groundedMaxTokens;
    /** Ollama keep_alive, e.g. "30m"; null leaves Ollama's own default (the model unloads after 5 idle minutes). */
    private final String keepAlive;

    @Autowired
    public OllamaClient(RestClient.Builder builder,
                        @Value("${waypoint.myhr.ai.ollama.base-url:http://localhost:11434}") String baseUrl,
                        @Value("${waypoint.myhr.ai.ollama.model:llama3.1:8b}") String model,
                        @Value("${waypoint.myhr.ai.ollama.timeout:5m}") java.time.Duration timeout,
                        @Value("${waypoint.myhr.ai.ollama.grounded-max-tokens:" + DEFAULT_GROUNDED_MAX_TOKENS + "}")
                        int groundedMaxTokens,
                        @Value("${waypoint.myhr.ai.ollama.keep-alive:}") String keepAlive) {
        this(builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory(timeout))
                .build(), model, baseUrl, groundedMaxTokens, keepAlive);
    }

    /** Without a known base URL the endpoint cannot be verified as local, so it never receives policy text. */
    OllamaClient(RestClient restClient, String model) {
        this(restClient, model, null);
    }

    OllamaClient(RestClient restClient, String model, String baseUrl) {
        this(restClient, model, baseUrl, DEFAULT_GROUNDED_MAX_TOKENS, null);
    }

    OllamaClient(RestClient restClient, String model, String baseUrl, int groundedMaxTokens, String keepAlive) {
        if (groundedMaxTokens < 64 || groundedMaxTokens > 8192) {
            throw new IllegalArgumentException("Ollama grounded-max-tokens must be between 64 and 8192");
        }
        if (keepAlive != null && !keepAlive.isBlank() && !keepAlive.strip().matches("-?\\d{1,6}(?:ms|s|m|h)?")) {
            throw new IllegalArgumentException("Ollama keep-alive must be a duration such as 30m or -1");
        }
        this.restClient = restClient;
        this.routerRestClient = restClient;
        this.model = model;
        this.localEndpoint = isLoopbackUrl(baseUrl);
        this.groundedMaxTokens = groundedMaxTokens;
        this.keepAlive = keepAlive == null || keepAlive.isBlank() ? null : keepAlive.strip();
    }

    /**
     * True only when the configured base URL names this machine by a loopback literal or
     * "localhost". Other host names are not resolved: a name that merely points at this machine
     * today cannot be verified, so it is treated as remote.
     */
    public boolean isLocalEndpoint() {
        return localEndpoint;
    }

    /** Verify the configured model is local before any fallback sends it a prompt. */
    boolean verifiedLocalModel() {
        if (!localEndpoint || model == null || model.isBlank()
                || model.toLowerCase(java.util.Locale.ROOT).contains("cloud")) return false;
        try {
            JsonNode details = restClient.post().uri("/api/show").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("model", model)).retrieve().body(JsonNode.class);
            return details != null && details.path("model_info").isObject()
                    && !details.path("model_info").isEmpty()
                    && details.path("remote_host").asString("").isBlank()
                    && details.path("remote_model").asString("").isBlank();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    static boolean isLoopbackUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        java.net.URI uri;
        try {
            uri = java.net.URI.create(baseUrl.strip());
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || uri.getUserInfo() != null
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            return false;
        }
        String normalized = host.toLowerCase(java.util.Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized.equals("localhost")
                || normalized.equals("::1")
                || normalized.equals("0:0:0:0:0:0:0:1")
                || IPV4_LOOPBACK.matcher(normalized).matches();
    }

    private static JdkClientHttpRequestFactory requestFactory(java.time.Duration timeout) {
        var http = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5))
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(http);
        requestFactory.setReadTimeout(timeout);
        return requestFactory;
    }

    /** The whole-request timeout of the routing client; within the routing deadline by default. */
    private java.time.Duration routerTimeout = java.time.Duration.ofSeconds(3);

    /**
     * Semantic routing on a local Ollama (development only): the same java.net.http client as Claude routing, so the
     * timeout bounds the whole request and a POST is never resent unseen.
     */
    @Autowired
    void configureRouting(RestClient.Builder builder,
                          @Value("${waypoint.myhr.ai.ollama.base-url:http://localhost:11434}") String baseUrl,
                          @Value("${waypoint.myhr.router.ollama.timeout:3s}") java.time.Duration routerTimeout) {
        this.routerRestClient = builder.clone().baseUrl(baseUrl)
                .requestFactory(ClaudeChatClient.routerRequestFactory(routerTimeout)).build();
        this.routerTimeout = routerTimeout;
    }

    /** How long one routing attempt can take at most. */
    java.time.Duration routerTimeout() {
        return routerTimeout;
    }

    /**
     * One semantic routing decision as JSON text constrained by {@code schema} (Ollama's structured "format"),
     * only from a verifiably local endpoint: the question never leaves this machine through Ollama.
     */
    public String routeJson(String systemPrompt, String userMessage, Map<String, Object> schema, int maxOutputTokens) {
        if (!verifiedLocalModel()) {
            throw new PolicyContextNotLocalException();
        }
        List<OllamaMessage> messages = List.of(new OllamaMessage("system", systemPrompt),
                new OllamaMessage("user", userMessage));
        try {
            OllamaResponse response = routerRestClient.post()
                    .uri("/api/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new OllamaRouteRequest(model, false, messages,
                            new OllamaRouteOptions(GROUNDED_CONTEXT_TOKENS, maxOutputTokens, 0.0), keepAlive, schema))
                    .retrieve()
                    .body(OllamaResponse.class);
            if (response == null || response.message() == null || response.message().content() == null
                    || response.message().content().isBlank()) {
                throw new OllamaUnavailableException();
            }
            if ("length".equals(response.done_reason())) {
                throw new OllamaIncompleteResponseException();
            }
            return response.message().content().trim();
        } catch (RestClientException exception) {
            throw new OllamaUnavailableException();
        }
    }

    public String chat(String message) {
        return chat(message, List.of());
    }

    public String chat(String message, List<MyHrChatHistoryMessage> history) {
        try {
            List<OllamaMessage> messages = new java.util.ArrayList<>();
            messages.add(new OllamaMessage("system", SYSTEM_PROMPT));
            if (history != null) {
                history.forEach(item -> messages.add(new OllamaMessage(item.role(), item.content())));
            }
            messages.add(new OllamaMessage("user", message));
            OllamaResponse response = restClient.post()
                    .uri("/api/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    // The same context size as grounded requests: Ollama reloads a loaded model whenever num_ctx
                    // changes, so alternating general and policy questions would otherwise reload it each time.
                    .body(new OllamaRequest(model, false, messages, new OllamaOptions(GROUNDED_CONTEXT_TOKENS, null),
                            keepAlive))
                    .retrieve()
                    .body(OllamaResponse.class);
            if (response == null || response.message() == null || response.message().content() == null
                    || response.message().content().isBlank()) {
                throw new OllamaUnavailableException();
            }
            return response.message().content().trim();
        } catch (RestClientException exception) {
            throw new OllamaUnavailableException();
        }
    }

    /**
     * Sends a knowledge-grounded request with the same system prompt, history and final user
     * message Claude would receive. The context window is set explicitly: Ollama's smaller default
     * truncates the oldest tokens first, which would silently drop the system instructions.
     */
    public String chat(MyHrGroundedPrompt prompt) {
        if (prompt.containsPolicyContext() && !verifiedLocalModel()) {
            // Checked here as well as by the caller: company policy text never leaves this machine via Ollama.
            throw new PolicyContextNotLocalException();
        }
        List<OllamaMessage> messages = new java.util.ArrayList<>();
        messages.add(new OllamaMessage("system", prompt.systemPrompt()));
        prompt.history().forEach(item -> messages.add(new OllamaMessage(item.role(), item.content())));
        messages.add(new OllamaMessage("user", prompt.userMessage()));
        try {
            OllamaResponse response = restClient.post()
                    .uri("/api/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new OllamaRequest(model, false, messages,
                            new OllamaOptions(GROUNDED_CONTEXT_TOKENS, groundedMaxTokens), keepAlive))
                    .retrieve()
                    .body(OllamaResponse.class);
            if (response == null || response.message() == null || response.message().content() == null
                    || response.message().content().isBlank()) {
                throw new OllamaUnavailableException();
            }
            if ("length".equals(response.done_reason())) {
                // Cut off at the output limit: a partial policy answer is never shown.
                throw new OllamaIncompleteResponseException();
            }
            return response.message().content().trim();
        } catch (RestClientException exception) {
            throw new OllamaUnavailableException();
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record OllamaRequest(String model, boolean stream, List<OllamaMessage> messages, OllamaOptions options,
                                 String keep_alive) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record OllamaOptions(int num_ctx, Integer num_predict) { }
    private record OllamaMessage(String role, String content) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record OllamaRouteRequest(String model, boolean stream, List<OllamaMessage> messages,
                                      OllamaRouteOptions options, String keep_alive, Map<String, Object> format) { }
    private record OllamaRouteOptions(int num_ctx, int num_predict, double temperature) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OllamaResponse(OllamaResponseMessage message, String done_reason) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OllamaResponseMessage(String role, String content) { }

    public static class OllamaUnavailableException extends RuntimeException { }

    /** The model stopped at the output-token limit; the partial answer is discarded. */
    public static class OllamaIncompleteResponseException extends OllamaUnavailableException { }

    /** The endpoint is not verifiably local, so a request carrying company policy text was not sent. */
    public static class PolicyContextNotLocalException extends OllamaUnavailableException { }
}
