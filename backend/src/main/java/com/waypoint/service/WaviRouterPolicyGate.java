package com.waypoint.service;

import com.waypoint.enums.UserRole;

/**
 * The server-side check every router decision passes before any tool runs. It is the authority, whatever the
 * router believed:
 * <ol>
 *   <li>the tool must exist in the registry;</li>
 *   <li>no argument may name a user, employee, organization or role (identity comes from the verified actor);</li>
 *   <li>the arguments must match the tool's strict schema, including date windows and bounds;</li>
 *   <li>a decision a model was unsure of is turned into a clarifying question, never a guessed lookup;</li>
 *   <li>a verified role the tool does not serve gets the tool's fixed outside-role reply, with no lookup.</li>
 * </ol>
 * The gate never throws for bad router output: it answers with a safe fixed reply.
 */
final class WaviRouterPolicyGate {

    static final String CANNOT_HELP_REPLY = "I can't help with that in chat. I can look up your own Waypoint "
            + "records, open Waypoint pages for you and answer questions about Waypoint and your company's policies.";

    /** What the gate decided. */
    sealed interface Verdict permits Execute, Answer {
    }

    /** Run {@code tool} for the verified actor with these validated arguments. */
    record Execute(WaviTool tool, WaviToolArguments.Validated arguments) implements Verdict {
    }

    /** Answer with {@code result}; no tool runs. {@code tool} is null when the decision named no known tool. */
    record Answer(WaviTool tool, WaviToolResult result) implements Verdict {
    }

    private final WaviToolRegistry registry;

    WaviRouterPolicyGate(WaviToolRegistry registry) {
        this.registry = registry;
    }

    Verdict check(WaviRouterDecision decision, UserRole role, WaviTool.Context context) {
        if (decision == null || role == null || context == null) {
            return new Answer(null, WaviToolResult.rejected("NO_DECISION", CANNOT_HELP_REPLY));
        }
        WaviTool tool = registry.find(decision.tool()).orElse(null);
        if (tool == null) {
            return new Answer(null, WaviToolResult.rejected("UNKNOWN_TOOL", CANNOT_HELP_REPLY));
        }
        // Before the schema, so the reason is recorded precisely: an identity argument is never "just invalid".
        if (decision.arguments().keySet().stream().anyMatch(WaviToolArguments::isIdentityName)) {
            return new Answer(tool, WaviToolResult.rejected("IDENTITY_ARGUMENT", CANNOT_HELP_REPLY));
        }
        WaviToolArguments.Validation validation = tool.schema().validate(decision.arguments(), context.today());
        if (!validation.valid()) {
            return new Answer(tool, WaviToolResult.rejected(validation.problem(), CANNOT_HELP_REPLY));
        }
        String inconsistent = tool.checkArguments(validation.arguments(), context);
        if (inconsistent != null) {
            return new Answer(tool, WaviToolResult.rejected(inconsistent, CANNOT_HELP_REPLY));
        }
        if (needsClarification(decision, tool)) {
            return new Answer(tool, WaviToolResult.clarify(clarification(role)));
        }
        if (!tool.allowedRoles().contains(role)) {
            return new Answer(tool, tool.outsideRole(role, validation.arguments(), context));
        }
        return new Execute(tool, validation.arguments());
    }

    /** The same clarifying question the existing interpretation fallback asks, for the verified role. */
    static String clarification(UserRole role) {
        return switch (role) {
            case HR -> MyHrChatService.CLARIFY_HR;
            case COMPANY_ADMIN -> MyHrChatService.CLARIFY_ADMIN;
            default -> MyHrChatService.CLARIFY_EMPLOYEE;
        };
    }

    /**
     * The deterministic fast path is always certain. A model's low-confidence decision, or a medium-confidence
     * one that would read a record or a queue, gets a clarifying question instead.
     */
    private static boolean needsClarification(WaviRouterDecision decision, WaviTool tool) {
        if (decision.source() == WaviRouterDecision.Source.FAST_PATH) return false;
        if (decision.confidence() == WaviRouterDecision.Confidence.LOW) return true;
        return decision.confidence() == WaviRouterDecision.Confidence.MEDIUM
                && (tool.dataClass() == WaviTool.DataClass.PERSONAL || tool.dataClass() == WaviTool.DataClass.ORG_AGGREGATE);
    }
}
