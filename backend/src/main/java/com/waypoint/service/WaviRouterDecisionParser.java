package com.waypoint.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns a router's JSON text into a {@link WaviRouterDecision}, or nothing. Only the expected shape is accepted:
 * an object with "decision" {tool, arguments}, "confidence" and "language". In provider output, arguments is a
 * compact JSON object encoded as a string so the structured-output grammar stays shallow; object arguments remain
 * accepted for existing in-process providers and tests. Argument values must be strings or whole numbers. Anything
 * else (extra fields, nested objects, arrays, fractions, booleans, oversized text) makes the whole output invalid.
 * The decision is then checked again, against the real schemas, by
 * {@link WaviRouterPolicyGate}.
 */
final class WaviRouterDecisionParser {

    private static final ObjectMapper JSON = new ObjectMapper();
    static final int MAX_OUTPUT_CHARS = 4_000;
    private static final int MAX_VALUE_CHARS = 40;
    private static final int MAX_ARGUMENT_JSON_CHARS = 1_500;

    private WaviRouterDecisionParser() {
    }

    static Optional<WaviRouterDecision> parse(String output, WaviRouterDecision.Source source) {
        if (output == null || output.isBlank() || output.length() > MAX_OUTPUT_CHARS) return Optional.empty();
        JsonNode root;
        try {
            root = JSON.readTree(output.strip());
        } catch (JacksonException invalid) {
            return Optional.empty();
        }
        if (root == null || !root.isObject() || !onlyFields(root, List.of("decision", "confidence", "language"))) {
            return Optional.empty();
        }
        JsonNode decision = root.get("decision");
        if (decision == null || !decision.isObject() || !onlyFields(decision, List.of("tool", "arguments"))) {
            return Optional.empty();
        }
        JsonNode tool = decision.get("tool");
        JsonNode arguments = decision.get("arguments");
        if (tool == null || !tool.isString() || !WaviToolRegistry.isValidName(tool.asString())) return Optional.empty();
        arguments = argumentObject(arguments);
        if (arguments == null || arguments.size() > WaviToolArguments.MAX_ARGUMENTS) return Optional.empty();
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : arguments.properties()) {
            JsonNode value = entry.getValue();
            if (entry.getKey().length() > MAX_VALUE_CHARS) return Optional.empty();
            if (value.isString() && value.asString().length() <= MAX_VALUE_CHARS) {
                values.put(entry.getKey(), value.asString());
            } else if (value.isIntegralNumber() && value.canConvertToInt()) {
                values.put(entry.getKey(), value.intValue());
            } else {
                return Optional.empty();
            }
        }
        WaviRouterDecision.Confidence confidence = confidence(root.get("confidence"));
        if (confidence == null) return Optional.empty();
        String name = tool.asString();
        return Optional.of(new WaviRouterDecision(intentOf(name), name, values, confidence, List.of(),
                language(root.get("language")), source));
    }

    /** Decodes the compact provider envelope without allowing a nested or oversized argument payload. */
    private static JsonNode argumentObject(JsonNode arguments) {
        if (arguments != null && arguments.isObject()) return arguments;
        if (arguments == null || !arguments.isString()) return null;
        String text = arguments.asString();
        if (text.length() > MAX_ARGUMENT_JSON_CHARS) return null;
        try {
            JsonNode parsed = JSON.readTree(text);
            return parsed != null && parsed.isObject() ? parsed : null;
        } catch (JacksonException invalid) {
            return null;
        }
    }

    /** The intent follows from the tool; it is never taken from the model. */
    static WaviRouterDecision.Intent intentOf(String tool) {
        return switch (tool) {
            case WaviTools.OPEN_PAGE -> WaviRouterDecision.Intent.NAVIGATE;
            case WaviTools.GET_REVIEW_QUEUE, WaviTools.GET_DIRECT_REPORTS, WaviTools.GET_MY_PENDING_WORK ->
                    WaviRouterDecision.Intent.QUEUE;
            case WaviTools.EXPLAIN_TERM -> WaviRouterDecision.Intent.EXPLAIN;
            case WaviTools.WORKFLOW_HELP -> WaviRouterDecision.Intent.WORKFLOW_HELP;
            case WaviTools.POLICY_QUESTION -> WaviRouterDecision.Intent.POLICY;
            case WaviTools.GENERAL_HELP -> WaviRouterDecision.Intent.GENERAL_HELP;
            case WaviTools.CLARIFY -> WaviRouterDecision.Intent.CLARIFY;
            case WaviTools.UNSUPPORTED_FIELD, WaviTools.UNSUPPORTED_REQUEST -> WaviRouterDecision.Intent.UNSUPPORTED;
            case WaviTools.OUT_OF_SCOPE -> WaviRouterDecision.Intent.OUT_OF_SCOPE;
            default -> WaviRouterDecision.Intent.RECORD_LOOKUP;
        };
    }

    private static boolean onlyFields(JsonNode node, List<String> allowed) {
        for (String name : node.propertyNames()) {
            if (!allowed.contains(name)) return false;
        }
        return true;
    }

    private static WaviRouterDecision.Confidence confidence(JsonNode node) {
        if (node == null || !node.isString()) return null;
        try {
            return WaviRouterDecision.Confidence.valueOf(node.asString());
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    /** Informational only: lowercase letters and hyphens, at most 20 characters, or null. */
    private static String language(JsonNode node) {
        if (node == null || !node.isString()) return null;
        String text = node.asString().strip().toLowerCase(java.util.Locale.ROOT);
        if (text.isEmpty() || text.length() > 20) return null;
        for (char c : text.toCharArray()) {
            if (!(c >= 'a' && c <= 'z') && c != '-') return null;
        }
        return text;
    }
}
