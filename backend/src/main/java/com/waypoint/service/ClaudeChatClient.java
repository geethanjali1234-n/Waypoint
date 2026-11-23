package com.waypoint.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.waypoint.dto.myhr.MyHrChatHistoryMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClientException;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ClaudeChatClient {
    private static final String SYSTEM_PROMPT = MyHrAssistantPrompt.UNGROUNDED_SYSTEM_PROMPT;
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    /**
     * Output-token limit when none is configured. Model thinking counts toward it, so it leaves room for
     * thinking plus a complete chat answer; an answer that still reaches it is cut off and never returned.
     */
    static final int DEFAULT_MAX_TOKENS = 4096;
    /** The model finished its answer: naturally, or at a stop sequence. */
    private static final Set<String> COMPLETE_STOP_REASONS = Set.of("end_turn", "stop_sequence");
    /** The answer text was cut off before the model finished it. */
    private static final Set<String> TRUNCATED_STOP_REASONS = Set.of("max_tokens", "model_context_window_exceeded");

    /** The model a semantic routing decision is asked of, unless configured otherwise. */
    static final String DEFAULT_ROUTER_MODEL = "claude-haiku-5-5";

    private final RestClient restClient;
    /** Routing calls use their own client, with a short total timeout; tests share the chat client. */
    private RestClient routerRestClient;
    /** The whole-request timeout of the routing client: one routing attempt never takes longer. */
    private Duration routerTimeout = Duration.ofMillis(3500);
    private String routerModel = DEFAULT_ROUTER_MODEL;
    /** auto (by model), disabled, between_tools, adaptive or omit; see {@link #thinkingFor}. */
    private String routerThinking = "auto";
    private final String apiKey;
    private final String apiUrl;
    private final String model;
    private final int maxTokens;

    @Autowired
    public ClaudeChatClient(RestClient.Builder builder,
                            @Value("${waypoint.myhr.ai.claude.api-key:}") String apiKey,
                            @Value("${waypoint.myhr.ai.claude.api-url:https://api.anthropic.com/v1/messages}") String apiUrl,
                            @Value("${waypoint.myhr.ai.claude.model:claude-sonnet-5}") String model,
                            @Value("${waypoint.myhr.ai.claude.timeout:60s}") Duration timeout,
                            @Value("${waypoint.myhr.ai.claude.max-tokens:" + DEFAULT_MAX_TOKENS + "}") int maxTokens) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(timeout);
        this.restClient = builder.clone().requestFactory(factory).build();
        this.routerRestClient = this.restClient;
        this.apiKey = apiKey;
        this.apiUrl = apiUrl;
        this.model = model;
        this.maxTokens = requirePositive(maxTokens);
    }

    ClaudeChatClient(RestClient restClient, String apiKey, String apiUrl, String model) {
        this(restClient, apiKey, apiUrl, model, DEFAULT_MAX_TOKENS);
    }

    ClaudeChatClient(RestClient restClient, String apiKey, String apiUrl, String model, int maxTokens) {
        this.restClient = restClient;
        this.routerRestClient = restClient;
        this.apiKey = apiKey;
        this.apiUrl = apiUrl;
        this.model = model;
        this.maxTokens = requirePositive(maxTokens);
    }

    /**
     * Semantic routing settings, separate from chat: a fast model and a short timeout, so a slow routing call
     * never holds up the answer for long. Used only when semantic routing is switched on.
     */
    @Autowired
    void configureRouting(RestClient.Builder builder,
                          @Value("${waypoint.myhr.router.claude.model:" + DEFAULT_ROUTER_MODEL + "}") String routerModel,
                          @Value("${waypoint.myhr.router.claude.timeout:3500ms}") Duration routerTimeout,
                          @Value("${waypoint.myhr.router.claude.thinking:auto}") String routerThinking) {
        this.routerRestClient = builder.clone().requestFactory(routerRequestFactory(routerTimeout)).build();
        this.routerTimeout = routerTimeout;
        this.routerModel = StringUtils.hasText(routerModel) ? routerModel.strip() : DEFAULT_ROUTER_MODEL;
        useRouterThinking(routerThinking);
    }

    /**
     * The routing HTTP client: Java's java.net.http client, whose request timeout bounds the whole exchange (unlike
     * a socket read timeout, which only bounds each silence between bytes) and which never resends a POST on its own
     * (java.net.HttpURLConnection may, by default). Redirects are not followed. Every attempt is therefore exactly
     * one request the caller counted, and it ends within {@code totalTimeout}.
     */
    static ClientHttpRequestFactory routerRequestFactory(Duration totalTimeout) {
        if (totalTimeout == null || totalTimeout.isNegative() || totalTimeout.isZero()) {
            throw new IllegalArgumentException("waypoint.myhr.router.claude.timeout must be positive");
        }
        java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.min(2000, Math.max(200, totalTimeout.toMillis()))))
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(totalTimeout);
        return factory;
    }

    /** Tests and the evaluation harness: route through a real routing client with this total timeout. */
    void useRouterHttp(Duration totalTimeout) {
        this.routerRestClient = RestClient.builder().requestFactory(routerRequestFactory(totalTimeout)).build();
        this.routerTimeout = totalTimeout;
    }

    /** How long one routing attempt can take at most. */
    Duration routerTimeout() {
        return routerTimeout;
    }

    /** Tests and the evaluation harness: route with another model (and its thinking setting). */
    void useRouterModel(String model, String thinking) {
        this.routerModel = StringUtils.hasText(model) ? model.strip() : DEFAULT_ROUTER_MODEL;
        useRouterThinking(thinking);
    }

    private void useRouterThinking(String thinking) {
        String value = thinking == null ? "auto" : thinking.strip().toLowerCase(java.util.Locale.ROOT);
        if (!Set.of("auto", "disabled", "between_tools", "adaptive", "omit").contains(value)) {
            throw new IllegalArgumentException("waypoint.myhr.router.claude.thinking must be auto, disabled, "
                    + "between_tools, adaptive or omit");
        }
        this.routerThinking = value;
    }

    /**
     * The thinking setting a routing call sends, or null to send none (the model's default). A routing decision is
     * a short classification, so thinking is kept off where the model allows it:
     * <ul>
     *   <li>Claude Haiku 5.5 accepts {@code disabled} (at effort high or below).</li>
     *   <li>Claude Sonnet 5.5 and Opus 5.5 reject {@code disabled} with a 400; {@code between_tools} (thinking
     *   only between tool calls, and a routing call has none) is their closest setting.</li>
     *   <li>Any other model gets no thinking field, so the request is never rejected for it; its default thinking
     *   then counts toward the output limit.</li>
     * </ul>
     * Source: platform.claude.com docs (extended thinking, models overview), checked 2026-11-09.
     */
    static String thinkingFor(String model, String setting) {
        String value = setting == null ? "auto" : setting;
        if (!value.equals("auto")) return value.equals("omit") ? null : value;
        String id = model == null ? "" : model.toLowerCase(java.util.Locale.ROOT);
        if (id.startsWith("claude-haiku-5")) return "disabled";
        if (id.startsWith("claude-sonnet-5-5") || id.startsWith("claude-opus-5-5")) return "between_tools";
        return null;
    }

    String routerThinking() {
        return thinkingFor(routerModel, routerThinking);
    }

    String routerModel() {
        return routerModel;
    }

    private static int requirePositive(int maxTokens) {
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("waypoint.myhr.ai.claude.max-tokens must be positive");
        }
        return maxTokens;
    }

    public String chat(String message, List<MyHrChatHistoryMessage> history) {
        List<ClaudeMessage> messages = historyMessages(history);
        messages.add(new ClaudeMessage("user", message));
        return completeAnswer(send(SYSTEM_PROMPT, messages));
    }

    /**
     * Sends a knowledge-grounded request: its system prompt replaces the default one, and its
     * already-escaped history and final user message are sent unchanged.
     */
    public String chat(MyHrGroundedPrompt prompt) {
        List<ClaudeMessage> messages = historyMessages(prompt.history());
        messages.add(new ClaudeMessage("user", prompt.userMessage()));
        return completeAnswer(send(prompt.systemPrompt(), messages));
    }

    /**
     * Sends a grounded request whose authorized sources are plain-text documents with Claude citations
     * enabled (all of them, as the API requires), followed by the escaped question. Returns the answer's
     * text blocks in order with the citations Claude attached to each: provider claims, which
     * MyHrKnowledgeGrounding checks against the supplied documents before anything is shown.
     */
    public List<MyHrCitedSegment> chatWithDocuments(MyHrGroundedDocumentPrompt prompt) {
        List<Object> content = new ArrayList<>();
        for (MyHrGroundedDocumentPrompt.Document document : prompt.documents()) {
            content.add(new ClaudeDocumentBlock("document", new ClaudeTextSource("text", "text/plain", document.text()),
                    document.title(), document.context(), new ClaudeCitationsSetting(true)));
        }
        content.add(new ClaudeTextBlock("text", prompt.question()));
        List<ClaudeMessage> messages = historyMessages(prompt.history());
        messages.add(new ClaudeMessage("user", content));
        return citedSegments(send(prompt.systemPrompt(), messages));
    }

    /**
     * One semantic routing decision, as JSON text matching {@code schema} (Structured Outputs:
     * output_config.format json_schema). No conversation history is sent: only the fixed system text, which is
     * marked cacheable, and one user message. Thinking is disabled and effort kept low, because a routing
     * decision is a short classification. Errors map exactly as for chat; a refusal or a cut-off answer is
     * never returned as a decision.
     */
    public String routeJson(String systemPrompt, String userMessage, Map<String, Object> schema, int maxOutputTokens,
                            String effort) {
        return routeJsonWithUsage(systemPrompt, userMessage, schema, maxOutputTokens, effort).json();
    }

    /** Token counts of one routing call, from the response's usage; zero when the response has none. */
    public record RouteUsage(int inputTokens, int outputTokens, int cacheWriteTokens, int cacheReadTokens) {
        static final RouteUsage NONE = new RouteUsage(0, 0, 0, 0);
    }

    /** A routing decision's JSON text and what the call cost in tokens. */
    public record RouteResult(String json, RouteUsage usage, String model) {
    }

    public RouteResult routeJsonWithUsage(String systemPrompt, String userMessage, Map<String, Object> schema,
                                          int maxOutputTokens, String effort) {
        String thinking = routerThinking();
        ClaudeRouteRequest body = new ClaudeRouteRequest(routerModel, requirePositive(maxOutputTokens),
                List.of(new ClaudeCachedTextBlock("text", systemPrompt, new ClaudeCacheControl("ephemeral"))),
                List.of(new ClaudeMessage("user", userMessage)), thinking == null ? null : new ClaudeThinking(thinking),
                new ClaudeOutputConfig(effort, new ClaudeOutputFormat("json_schema", schema)));
        ClaudeResponse response = exchange(routerRestClient, body, routerModel, maxOutputTokens);
        ClaudeUsage usage = response.usage();
        RouteUsage tokens = usage == null ? RouteUsage.NONE : new RouteUsage(zero(usage.input_tokens()),
                zero(usage.output_tokens()), zero(usage.cache_creation_input_tokens()),
                zero(usage.cache_read_input_tokens()));
        return new RouteResult(completeAnswer(response), tokens, routerModel);
    }

    private static int zero(Integer value) {
        return value == null ? 0 : value;
    }

    private static List<ClaudeMessage> historyMessages(List<MyHrChatHistoryMessage> history) {
        List<ClaudeMessage> messages = new ArrayList<>();
        if (history != null) {
            history.forEach(item -> messages.add(new ClaudeMessage(item.role(), item.content())));
        }
        return messages;
    }

    /**
     * One Messages API call. A safety refusal is reported as ClaudeSafetyRefusalException, which is never a
     * reason to ask another provider; only temporary unavailability (ClaudeUnavailableException) is.
     */
    private ClaudeResponse send(String systemPrompt, List<ClaudeMessage> messages) {
        return exchange(restClient, new ClaudeRequest(model, maxTokens, systemPrompt, messages), model, maxTokens);
    }

    private ClaudeResponse exchange(RestClient client, Object body, String modelName, int maxOutputTokens) {
        ClaudeResponse response = post(client, body, ClaudeResponse.class, modelName, maxOutputTokens);
        if (response != null && "refusal".equals(response.stop_reason())) {
            throw new ClaudeSafetyRefusalException();
        }
        requireFinished(response);
        return response;
    }

    /** One POST with the shared failure mapping: only safe codes escape, never a body or message. */
    private <T> T post(RestClient client, Object body, Class<T> responseType, String modelName, int maxOutputTokens) {
        if (!StringUtils.hasText(apiKey)) {
            throw new ClaudeConfigurationException();
        }
        // The one choke point of every paid Claude call (agent, chat, grounded chat, cited documents, routing): the
        // worst case is reserved BEFORE sending, against one shared ledger, and settled from the response's usage.
        ClaudeSpendLimiter.Prices rates = ClaudeSpendLimiter.Prices.forModel(modelName);
        double reserved = spendLimiter.enabled()
                ? spendLimiter.reserve(rates.worstCase(estimateInputTokens(body), maxOutputTokens)) : 0;
        T response;
        try {
            response = client.post()
                    .uri(apiUrl)
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(responseType);
        } catch (RestClientResponseException exception) {
            HttpStatusCode status = exception.getStatusCode();
            int code = status.value();
            String errorType = errorType(exception);
            String requestId = exception.getResponseHeaders() == null ? null
                    : safeToken(exception.getResponseHeaders().getFirst("request-id"), 80);
            long retryAfterMillis = retryAfterMillis(exception);
            if (code == 401 || code == 403) {
                spendLimiter.release(reserved); // not processed: nothing was spent
                throw new ClaudeConfigurationException().withDetails(code, errorType, requestId, 0);
            }
            // Temporary: request timeout, rate limit, overload (529) and other server errors. Every other
            // 4xx (bad request, unknown model, too large) is a request problem, never a fallback trigger.
            if (code == 408) {
                throw new ClaudeTimeoutException().withDetails(code, errorType, requestId, 0);
            }
            if (code == 429 || status.is5xxServerError()) {
                throw new ClaudeUnavailableException().withDetails(code, errorType, requestId, retryAfterMillis);
            }
            spendLimiter.release(reserved); // a rejected request is not processed: nothing was spent
            throw new ClaudeRequestRejectedException().withDetails(code, errorType, requestId, 0);
        } catch (ResourceAccessException exception) {
            if (isTimeout(exception)) {
                throw new ClaudeTimeoutException();
            }
            throw new ClaudeUnavailableException().withDetails(0, "network", null, 0);
        } catch (RestClientException exception) {
            throw new ClaudeUnavailableException().withDetails(0, "unreadable_response", null, 0);
        }
        // A response without usage leaves the worst-case reservation in place: unknown cost counts as the maximum.
        RouteUsage actual = usageOf(response);
        if (actual != null) spendLimiter.settle(reserved, actual, rates);
        return response;
    }

    private static final tools.jackson.databind.ObjectMapper ERROR_JSON = new tools.jackson.databind.ObjectMapper();

    /** Only the API's error type ("overloaded_error"), never its message: the message may quote the request. */
    private static String errorType(RestClientResponseException exception) {
        try {
            String body = exception.getResponseBodyAsString();
            if (body == null || body.isBlank() || body.length() > 20_000) return null;
            tools.jackson.databind.JsonNode error = ERROR_JSON.readTree(body).path("error");
            tools.jackson.databind.JsonNode type = error.path("type");
            if (!type.isString()) return null;
            String safeType = safeToken(type.asString(), 40);
            // A bad request is classified into one fixed code so its cause is visible in logs; the message itself is
            // read only for that and never stored or logged (it may quote the request).
            if ("invalid_request_error".equals(safeType) && error.path("message").isString()) {
                return safeType + "/" + invalidRequestCategory(error.path("message").asString());
            }
            return safeType;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /** A fixed code for an invalid request's message: which part of the request the API rejected. */
    static String invalidRequestCategory(String message) {
        String text = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
        if (text.contains("too complex")) return "schema_too_complex";
        if (text.contains("optional param")) return "schema_optional_limit";
        if (text.contains("union")) return "schema_union_limit";
        if (text.contains("schema") || text.contains("output_config") || text.contains("format")) return "schema_invalid";
        if (text.contains("thinking") || text.contains("effort")) return "thinking_or_effort";
        if (text.contains("model")) return "model";
        if (text.contains("max_tokens")) return "max_tokens";
        if (text.contains("too long") || text.contains("too large")) return "too_large";
        return "other";
    }

    /** Retry-After in whole seconds, as milliseconds; 0 when absent, unparsable or unreasonable. */
    private static long retryAfterMillis(RestClientResponseException exception) {
        if (exception.getResponseHeaders() == null) return 0;
        String value = exception.getResponseHeaders().getFirst("retry-after");
        if (value == null) return 0;
        try {
            long seconds = Long.parseLong(value.strip());
            return seconds < 0 || seconds > 600 ? 0 : seconds * 1000;
        } catch (NumberFormatException notSeconds) {
            return 0;
        }
    }

    /** Lowercase letters, digits, '_' and '-' only, bounded; anything else is dropped as unsafe for logs. */
    static String safeToken(String value, int maxLength) {
        if (value == null || value.isEmpty() || value.length() > maxLength) return null;
        for (char c : value.toCharArray()) {
            if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z') && !(c >= '0' && c <= '9') && c != '_' && c != '-') {
                return null;
            }
        }
        return value;
    }

    /** A timeout remains fallback-eligible, while allowing routing diagnostics to classify it accurately. */
    private static boolean isTimeout(Throwable failure) {
        for (Throwable current = failure; current != null && current.getCause() != current;
             current = current.getCause()) {
            if (current instanceof SocketTimeoutException || current instanceof java.net.http.HttpTimeoutException
                    || current instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
        }
        return false;
    }

    /**
     * A response the model did not finish (output limit, context window, a pause or an unexpected stop
     * reason) is never returned as an answer. A response without a stop reason is treated as finished, as
     * before.
     */
    private static void requireFinished(ClaudeResponse response) {
        if (response == null) {
            throw new ClaudeIncompleteResponseException(IncompleteReason.NO_TEXT);
        }
        String stopReason = response.stop_reason();
        if (stopReason != null && TRUNCATED_STOP_REASONS.contains(stopReason)) {
            throw new ClaudeIncompleteResponseException(IncompleteReason.TRUNCATED);
        }
        if (stopReason != null && !COMPLETE_STOP_REASONS.contains(stopReason)) {
            throw new ClaudeIncompleteResponseException(IncompleteReason.UNEXPECTED_STOP);
        }
    }

    /**
     * The whole answer: every text block in response order, joined as the model wrote them, so an answer
     * split across blocks is never cut down to its first part. Thinking and other non-text blocks are not
     * answer text, and a finished response without answer text is never returned as an answer.
     */
    private static String completeAnswer(ClaudeResponse response) {
        StringBuilder answer = new StringBuilder();
        for (MyHrCitedSegment segment : citedSegments(response)) {
            answer.append(segment.text());
        }
        return answer.toString().trim();
    }

    /** The answer's text blocks in order, each with the citations Claude attached to it. */
    private static List<MyHrCitedSegment> citedSegments(ClaudeResponse response) {
        List<MyHrCitedSegment> segments = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        if (response.content() != null) {
            for (ClaudeContentBlock block : response.content()) {
                if (block != null && "text".equals(block.type()) && block.text() != null) {
                    List<MyHrCitedSegment.Citation> citations = new ArrayList<>();
                    if (block.citations() != null) {
                        for (ClaudeCitation citation : block.citations()) {
                            if (citation != null) {
                                citations.add(new MyHrCitedSegment.Citation(citation.type(), citation.cited_text(),
                                        citation.document_index(), citation.start_char_index(), citation.end_char_index()));
                            }
                        }
                    }
                    segments.add(new MyHrCitedSegment(block.text(), citations));
                    text.append(block.text());
                }
            }
        }
        if (!StringUtils.hasText(text)) {
            throw new ClaudeIncompleteResponseException(IncompleteReason.NO_TEXT);
        }
        return segments;
    }

    // --- Tool-use conversation (Wavi agent) ---------------------------------------------------------------------

    /** One tool Claude may call: the registry's name, description and strict input schema. */
    public record ToolDefinition(String name, String description, Map<String, Object> inputSchema) {
    }

    /** One tool call Claude asked for. The id is echoed back with the result. */
    public record ToolCall(String id, String name, Map<String, Object> input) {
    }

    /**
     * One model turn: either tool calls to run ({@code calls} non-empty, {@code assistantContent} to echo back as
     * the assistant message) or a finished answer ({@code text}).
     */
    public record ToolTurn(String text, List<ToolCall> calls, List<Map<String, Object>> assistantContent,
                           RouteUsage usage, String model) {
        public ToolTurn {
            calls = calls == null ? List.of() : List.copyOf(calls);
            assistantContent = assistantContent == null ? List.of() : List.copyOf(assistantContent);
        }

        public boolean wantsTools() {
            return !calls.isEmpty();
        }
    }

    /** One bounded call of the tool-use loop; the agent and its tests depend on this, not on HTTP. */
    @FunctionalInterface
    public interface ToolConversation {
        ToolTurn next(String systemPrompt, List<Map<String, Object>> messages, List<ToolDefinition> tools,
                      boolean allowTools, int maxOutputTokens);
    }

    private RestClient agentRestClient;
    private String agentModel = DEFAULT_ROUTER_MODEL;
    private Duration agentTimeout = Duration.ofSeconds(8);

    /** The agent's own HTTP client (a hard total timeout per model call) and model; used only when it is enabled. */
    @Autowired
    void configureAgent(RestClient.Builder builder,
                        @Value("${waypoint.myhr.agent.claude.model:" + DEFAULT_ROUTER_MODEL + "}") String agentModel,
                        @Value("${waypoint.myhr.agent.claude.timeout:8s}") Duration agentTimeout) {
        this.agentRestClient = builder.clone().requestFactory(routerRequestFactory(agentTimeout)).build();
        this.agentTimeout = agentTimeout;
        this.agentModel = StringUtils.hasText(agentModel) ? agentModel.strip() : DEFAULT_ROUTER_MODEL;
    }

    /** Tests and the evaluation harness. */
    void useAgentModel(String model, Duration timeout) {
        this.agentRestClient = RestClient.builder().requestFactory(routerRequestFactory(timeout)).build();
        this.agentTimeout = timeout;
        this.agentModel = StringUtils.hasText(model) ? model.strip() : DEFAULT_ROUTER_MODEL;
    }

    public Duration agentTimeout() {
        return agentTimeout;
    }

    private ClaudeSpendLimiter spendLimiter = new ClaudeSpendLimiter(0, null, null);

    /** Spring: the one shared cap and ledger of every paid Claude call (see {@link ClaudeSpendConfiguration}). */
    @Autowired(required = false)
    void shareSpendLimiter(ClaudeSpendLimiter shared) {
        if (shared != null) this.spendLimiter = shared;
    }

    /** Tests: the same settings the shared bean is built from. */
    void configureSpend(double capUsd, String ledgerFile) {
        this.spendLimiter = ClaudeSpendLimiter.fromSettings(capUsd, ledgerFile);
    }

    void useSpendLimiter(ClaudeSpendLimiter limiter) {
        this.spendLimiter = limiter;
    }

    /** Thrown BEFORE a request is sent when its worst-case cost would pass the configured spending cap. */
    public static class ClaudeSpendLimitException extends ClaudeChatException { }

    /** Two characters per token of the serialized request: deliberately pessimistic. */
    private static int estimateInputTokens(Object body) {
        try {
            return (int) Math.min(Integer.MAX_VALUE, ERROR_JSON.writeValueAsString(body).length() / 2L + 200);
        } catch (RuntimeException unserializable) {
            throw new ClaudeSpendLimitException(); // the size, and so the cost, cannot be bounded: fail closed
        }
    }

    private static RouteUsage usageOf(Object response) {
        ClaudeUsage usage = response instanceof ClaudeResponse plain ? plain.usage()
                : response instanceof ClaudeToolResponse tool ? tool.usage() : null;
        return usage == null ? null : new RouteUsage(zero(usage.input_tokens()), zero(usage.output_tokens()),
                zero(usage.cache_creation_input_tokens()), zero(usage.cache_read_input_tokens()));
    }

    /**
     * One tool-use model call. The fixed system text is cacheable; with {@code allowTools} false the model must
     * answer in text (tool_choice none), which is how the last round of a bounded loop ends. A refusal, a cut-off
     * answer and every API failure map exactly as for chat; nothing but safe codes is kept.
     */
    public ToolTurn converse(String systemPrompt, List<Map<String, Object>> messages, List<ToolDefinition> tools,
                             boolean allowTools, int maxOutputTokens) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", agentModel);
        body.put("max_tokens", requirePositive(maxOutputTokens));
        body.put("system", List.of(new ClaudeCachedTextBlock("text", systemPrompt, new ClaudeCacheControl("ephemeral"))));
        body.put("messages", messages);
        if (tools != null && !tools.isEmpty()) {
            List<Map<String, Object>> definitions = new ArrayList<>();
            for (ToolDefinition tool : tools) {
                Map<String, Object> definition = new java.util.LinkedHashMap<>();
                definition.put("name", tool.name());
                definition.put("description", tool.description());
                definition.put("input_schema", tool.inputSchema());
                definitions.add(definition);
            }
            body.put("tools", definitions);
            body.put("tool_choice", Map.of("type", allowTools ? "auto" : "none"));
        }
        String thinking = thinkingFor(agentModel, "auto");
        if (thinking != null) body.put("thinking", new ClaudeThinking(thinking));
        ClaudeToolResponse response = post(agentRestClient == null ? routerRestClient : agentRestClient, body,
                ClaudeToolResponse.class, agentModel, maxOutputTokens);
        if (response == null) throw new ClaudeIncompleteResponseException(IncompleteReason.NO_TEXT);
        if ("refusal".equals(response.stop_reason())) throw new ClaudeSafetyRefusalException();
        ClaudeUsage usage = response.usage();
        RouteUsage tokens = usage == null ? RouteUsage.NONE : new RouteUsage(zero(usage.input_tokens()),
                zero(usage.output_tokens()), zero(usage.cache_creation_input_tokens()),
                zero(usage.cache_read_input_tokens()));
        List<ToolCall> calls = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        List<Map<String, Object>> echoed = new ArrayList<>();
        if (response.content() != null) {
            for (Map<String, Object> block : response.content()) {
                if (block == null) continue;
                Object type = block.get("type");
                if ("text".equals(type) && block.get("text") instanceof String value) {
                    text.append(value);
                    echoed.add(Map.of("type", "text", "text", value));
                } else if ("tool_use".equals(type) && block.get("id") instanceof String id
                        && block.get("name") instanceof String name) {
                    Map<String, Object> input = block.get("input") instanceof Map<?, ?> raw ? copyInput(raw) : Map.of();
                    calls.add(new ToolCall(id, name, input));
                    echoed.add(Map.of("type", "tool_use", "id", id, "name", name, "input", input));
                }
            }
        }
        String stopReason = response.stop_reason();
        if (stopReason != null && TRUNCATED_STOP_REASONS.contains(stopReason)) {
            throw new ClaudeIncompleteResponseException(IncompleteReason.TRUNCATED);
        }
        if (!calls.isEmpty() && "tool_use".equals(stopReason)) {
            return new ToolTurn(null, calls, echoed, tokens, agentModel);
        }
        if (stopReason != null && !COMPLETE_STOP_REASONS.contains(stopReason)) {
            throw new ClaudeIncompleteResponseException(IncompleteReason.UNEXPECTED_STOP);
        }
        if (!StringUtils.hasText(text)) throw new ClaudeIncompleteResponseException(IncompleteReason.NO_TEXT);
        return new ToolTurn(text.toString().trim(), List.of(), echoed, tokens, agentModel);
    }

    private static Map<String, Object> copyInput(Map<?, ?> raw) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (key instanceof String name) copy.put(name, value);
        });
        return copy;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ClaudeToolResponse(List<Map<String, Object>> content, String stop_reason, ClaudeUsage usage) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ClaudeRequest(String model, int max_tokens, String system, List<ClaudeMessage> messages) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ClaudeRouteRequest(String model, int max_tokens, List<ClaudeCachedTextBlock> system,
                                      List<ClaudeMessage> messages, ClaudeThinking thinking,
                                      ClaudeOutputConfig output_config) { }
    private record ClaudeCachedTextBlock(String type, String text, ClaudeCacheControl cache_control) { }
    private record ClaudeCacheControl(String type) { }
    private record ClaudeThinking(String type) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ClaudeOutputConfig(String effort, ClaudeOutputFormat format) { }
    private record ClaudeOutputFormat(String type, Map<String, Object> schema) { }
    /** content is a string, or a list of content blocks for a request with documents. */
    private record ClaudeMessage(String role, Object content) { }
    private record ClaudeTextBlock(String type, String text) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ClaudeDocumentBlock(String type, ClaudeTextSource source, String title, String context,
                                       ClaudeCitationsSetting citations) { }
    private record ClaudeTextSource(String type, String media_type, String data) { }
    private record ClaudeCitationsSetting(boolean enabled) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ClaudeResponse(List<ClaudeContentBlock> content, String stop_reason, ClaudeUsage usage) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ClaudeUsage(Integer input_tokens, Integer output_tokens, Integer cache_creation_input_tokens,
                               Integer cache_read_input_tokens) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ClaudeContentBlock(String type, String text, List<ClaudeCitation> citations) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ClaudeCitation(String type, String cited_text, Integer document_index, Integer start_char_index,
                                  Integer end_char_index) { }

    /**
     * Base of every Claude client failure. Carries only safe, machine-readable details for diagnostics and retry
     * decisions: the HTTP status, the API's error type, the request id and Retry-After. Never a message or body.
     */
    public static class ClaudeChatException extends RuntimeException {
        private int status;
        private String errorType;
        private String requestId;
        private long retryAfterMillis;

        @SuppressWarnings("unchecked")
        <T extends ClaudeChatException> T withDetails(int status, String errorType, String requestId,
                                                      long retryAfterMillis) {
            this.status = status;
            this.errorType = errorType;
            this.requestId = requestId;
            this.retryAfterMillis = retryAfterMillis;
            return (T) this;
        }

        public int status() {
            return status;
        }

        public String errorType() {
            return errorType;
        }

        public String requestId() {
            return requestId;
        }

        public long retryAfterMillis() {
            return retryAfterMillis;
        }

        /**
         * A stable class for logs and reports: timeout, rate_limited, overloaded, unavailable_503, server_5xx,
         * network, unreadable_response, incomplete, refusal, rejected_4xx, auth_or_config or unavailable.
         */
        public String failureClass() {
            if (this instanceof ClaudeIncompleteResponseException) return "incomplete";
            if (this instanceof ClaudeTimeoutException) return "timeout";
            if (this instanceof ClaudeSafetyRefusalException) return "refusal";
            if (this instanceof ClaudeConfigurationException) return "auth_or_config";
            if (this instanceof ClaudeRequestRejectedException) {
                // e.g. "rejected_400:schema_too_complex" - the fixed category of the bad request, never its message.
                String category = errorType != null && errorType.contains("/")
                        ? ":" + errorType.substring(errorType.indexOf('/') + 1) : "";
                return (status > 0 ? "rejected_" + status : "rejected") + category;
            }
            if (status == 429) return "rate_limited";
            if (status == 529) return "overloaded";
            if (status == 503) return "unavailable_503";
            if (status >= 500) return "server_5xx";
            if (errorType != null && status == 0) return errorType;
            return "unavailable";
        }

        /** Clearly transient: rate limited (429), overloaded (529) or temporarily unavailable (503). */
        public boolean transientFailure() {
            return status == 429 || status == 529 || status == 503;
        }
    }
    public static class ClaudeUnavailableException extends ClaudeChatException { }
    /** A network timeout, distinct from other temporary provider failures. */
    public static class ClaudeTimeoutException extends ClaudeUnavailableException { }

    /** Why a response held no complete answer; safe for operational logs. */
    public enum IncompleteReason {
        /** Stopped at the output token limit or the context window: the text is cut off. */
        TRUNCATED,
        /** Stopped for a reason a finished chat answer never has, such as a pause or a tool call. */
        UNEXPECTED_STOP,
        /** Finished, but with no answer text (empty, thinking only, or no response body). */
        NO_TEXT
    }

    /**
     * Claude answered, but not with a complete answer. Handled like any temporary unavailability, so a
     * partial answer is never shown as a successful one. Carries no provider text.
     */
    public static class ClaudeIncompleteResponseException extends ClaudeUnavailableException {
        private final IncompleteReason reason;

        public ClaudeIncompleteResponseException(IncompleteReason reason) {
            this.reason = reason;
        }

        public IncompleteReason reason() {
            return reason;
        }
    }
    public static class ClaudeConfigurationException extends ClaudeChatException { }
    public static class ClaudeRequestRejectedException extends ClaudeChatException { }
    /** Claude declined the request (stop reason "refusal"). Never a reason to ask another provider. */
    public static class ClaudeSafetyRefusalException extends ClaudeChatException { }
}
