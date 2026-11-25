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
        if (needsClarification(decision, tool) && !identifiedPersonLookup(decision, tool, context)) {
            // A person lookup that is not sure WHO is asked about asks for the person, never the generic
            // requests-and-approvals menu, which is about a different subject altogether.
            return new Answer(tool, WaviToolResult.clarify(WaviTools.GET_TEAM_MEMBER.equals(tool.name())
                    ? WaviClarification.prompt(null, WaviTools.MissingSlot.PERSON, role) : clarification(role)));
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
    /**
     * A MEDIUM-confidence read-only person lookup whose person the message itself names ("who is john", "Ravi's job
     * title", or a position in the list just shown): the uncertainty is only whether that person exists, which the
     * scoped lookup answers exactly (summary, "couldn't find that person", or "which one?" for several matches). The
     * role check and the authorized-scope read that follow are unchanged. A pronoun-only reference ("who is he?")
     * still asks which person. Never applies to LOW confidence or any other tool.
     */
    private static boolean identifiedPersonLookup(WaviRouterDecision decision, WaviTool tool, WaviTool.Context context) {
        if (!WaviTools.GET_TEAM_MEMBER.equals(tool.name()) || decision.confidence() != WaviRouterDecision.Confidence.MEDIUM
                || context.message() == null) return false;
        String normalized = WaviSpelling.correct(context.message().toLowerCase(java.util.Locale.ROOT).strip());
        if (WaviContinuation.refersToPerson(normalized)) return false;
        return decision.arguments().get("ordinal") != null || WaviContinuation.hasNameShapedToken(context.message())
                || WaviContinuation.mayNamePersonInLowerCase(normalized);
    }

    private static boolean needsClarification(WaviRouterDecision decision, WaviTool tool) {
        if (decision.source() == WaviRouterDecision.Source.FAST_PATH) return false;
        if (decision.confidence() == WaviRouterDecision.Confidence.LOW) return true;
        return decision.confidence() == WaviRouterDecision.Confidence.MEDIUM
                && (tool.dataClass() == WaviTool.DataClass.PERSONAL || tool.dataClass() == WaviTool.DataClass.ORG_AGGREGATE);
    }
}
