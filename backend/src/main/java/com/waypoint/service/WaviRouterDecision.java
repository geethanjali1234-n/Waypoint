package com.waypoint.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The only thing a Wavi router may decide: which tool to run, with which arguments. It never carries a user id,
 * employee id, organization id or role. Those come from the verified actor alone, and
 * {@link WaviRouterPolicyGate} rejects a decision that tries to name one.
 *
 * <p>The deterministic fast path ({@link Source#FAST_PATH}) and, when enabled, the semantic routers
 * ({@link WaviSemanticRouting}) produce decisions; all of them go through exactly the same gate.
 */
record WaviRouterDecision(Intent intent, String tool, Map<String, Object> arguments, Confidence confidence,
                          List<String> clarificationOptions, String language, Source source) {

    /** What kind of request the router believes this is; informational, the tool decides what runs. */
    enum Intent { RECORD_LOOKUP, QUEUE, NAVIGATE, EXPLAIN, WORKFLOW_HELP, POLICY, GENERAL_HELP, CLARIFY, UNSUPPORTED,
        OUT_OF_SCOPE }

    enum Confidence { LOW, MEDIUM, HIGH }

    /** Who produced the decision. */
    enum Source { FAST_PATH, CLAUDE, HOSTED_FALLBACK, OLLAMA_DEV }

    static final int MAX_CLARIFICATION_OPTIONS = 4;

    WaviRouterDecision {
        // An immutable copy: a router can never change the arguments after the gate has checked them.
        arguments = arguments == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
        List<String> options = new ArrayList<>();
        if (clarificationOptions != null) {
            clarificationOptions.stream().filter(Objects::nonNull).limit(MAX_CLARIFICATION_OPTIONS).forEach(options::add);
        }
        clarificationOptions = List.copyOf(options);
        confidence = confidence == null ? Confidence.LOW : confidence;
    }

    /** A decision made by an existing deterministic route: always high confidence. */
    static WaviRouterDecision fastPath(Intent intent, String tool, Map<String, Object> arguments) {
        return new WaviRouterDecision(intent, tool, arguments, Confidence.HIGH, List.of(), null, Source.FAST_PATH);
    }
}
