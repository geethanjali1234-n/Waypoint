package com.waypoint.service;

import java.util.List;

/**
 * What a tool, or the gate on its behalf, answered. The text is already rendered by deterministic Java code; a
 * record value never passes through a model to become a reply.
 *
 * @param pageKeys the page buttons to show (MyHrConversationIntents.Page names); null means "no opinion", as on
 *                 the existing routes that send none, and an empty list means "no buttons"
 * @param reasonCode a short code for logs and tests, never shown to the user
 * @param policyQuery for {@link Kind#POLICY_HANDOFF} only: the question to give the grounded policy pipeline
 */
record WaviToolResult(Kind kind, String text, List<String> pageKeys, String reasonCode, String policyQuery,
                      List<Long> facts) {

    enum Kind {
        /** The tool answered from the actor's own records or fixed Waypoint facts. */
        ANSWER,
        /** The tool ran (or the role is outside it) and says honestly what it cannot answer. */
        NOT_AVAILABLE,
        /** The gate refused the decision; no tool ran. */
        REJECTED,
        /** The gate asked the user which of a few things they mean; no tool ran. */
        CLARIFY,
        /** A company-policy question for the existing grounded pipeline; nothing was answered here. */
        POLICY_HANDOFF,
        /** A general HR or writing question for the existing general provider path; nothing was answered here. */
        GENERAL_HANDOFF
    }

    WaviToolResult {
        pageKeys = pageKeys == null ? null : List.copyOf(pageKeys);
        facts = facts == null ? List.of() : List.copyOf(facts);
    }

    /** A result with no typed facts: the text is the evidence. */
    WaviToolResult(Kind kind, String text, List<String> pageKeys, String reasonCode, String policyQuery) {
        this(kind, text, pageKeys, reasonCode, policyQuery, List.of());
    }

    /**
     * An answer that also states numbers the SERVER calculated (a total over several counts). They are typed facts: the
     * answer may quote them, and the model is never asked to add anything up.
     */
    static WaviToolResult answer(String text, List<String> pageKeys, List<Long> facts) {
        return new WaviToolResult(Kind.ANSWER, text, pageKeys, "ANSWERED", null, facts);
    }

    static WaviToolResult answer(String text, List<String> pageKeys) {
        return new WaviToolResult(Kind.ANSWER, text, pageKeys, "ANSWERED", null);
    }

    static WaviToolResult notAvailable(String reasonCode, String text, List<String> pageKeys) {
        return new WaviToolResult(Kind.NOT_AVAILABLE, text, pageKeys, reasonCode, null);
    }

    static WaviToolResult rejected(String reasonCode, String text) {
        return new WaviToolResult(Kind.REJECTED, text, List.of(), reasonCode, null);
    }

    /** No page opinion (null), like the existing clarification route. */
    static WaviToolResult clarify(String text) {
        return new WaviToolResult(Kind.CLARIFY, text, null, "CLARIFY", null);
    }

    static WaviToolResult generalHandoff() {
        return new WaviToolResult(Kind.GENERAL_HANDOFF, null, null, "GENERAL_HANDOFF", null);
    }

    static WaviToolResult policyHandoff(String query) {
        return new WaviToolResult(Kind.POLICY_HANDOFF, null, null, "POLICY_HANDOFF", query);
    }

    @Override
    public String toString() {
        // Never the reply text or query: they may hold personal records.
        return "WaviToolResult[" + kind + ", " + reasonCode + "]";
    }
}
