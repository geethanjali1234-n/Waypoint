package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrChatHistoryMessage;

import java.util.List;

/**
 * The last check before a question no route answered may reach company-policy retrieval or an AI provider. Pure
 * policy over the message, its history and the router's outcome; extracted from MyHrChatService so the decision
 * that guards every external call is one small, separately tested unit.
 *
 * <ol>
 *   <li>Explicitly off-topic (coding, trivia, an unrelated subject, an instruction override, or a no-signal
 *       follow-up to an off-topic turn): {@link Verdict#OUT_OF_SCOPE}.</li>
 *   <li>Not recognised by the fixed vocabulary, but not off-topic: {@link Verdict#ROUTER_UNAVAILABLE} when the
 *       router that understands other wording failed, otherwise {@link Verdict#UNRECOGNIZED}. Never claims the
 *       question was off-topic.</li>
 *   <li>The router failed on an in-scope question that is neither a policy question nor a writing task:
 *       {@link Verdict#ROUTER_UNAVAILABLE_BUSINESS} rather than a general model guess at a business question.</li>
 *   <li>Otherwise {@link Verdict#PROCEED}.</li>
 * </ol>
 */
final class WaviProviderGate {

    enum Verdict {
        PROCEED, OUT_OF_SCOPE, UNRECOGNIZED, ROUTER_UNAVAILABLE,
        /** Rule 3: an in-scope business question the failed router should have routed. The user's message may hold
         *  a personal record reference, so - unlike the other non-answers - it is not marked for external use. */
        ROUTER_UNAVAILABLE_BUSINESS
    }

    private WaviProviderGate() {
    }

    /**
     * @param routerJudgedInScope a router decided this is an HR, policy or general question (lifts only the
     *                            vocabulary-based refusal, never an explicit one - see MyHrScopeGuard)
     * @param routerFailed        the router was asked and failed, timed out or was unavailable
     */
    static Verdict decide(String message, List<MyHrChatHistoryMessage> history, boolean routerJudgedInScope,
                          boolean routerFailed) {
        if (!routerJudgedInScope && MyHrScopeGuard.decide(message, history) == MyHrScopeGuard.Decision.OUT_OF_SCOPE) {
            boolean explicit = MyHrScopeGuard.isExplicitlyOutOfScope(message);
            if (routerFailed && !explicit) return Verdict.ROUTER_UNAVAILABLE;
            if (!explicit && !MyHrScopeGuard.continuesOffTopicConversation(message, history)) return Verdict.UNRECOGNIZED;
            return Verdict.OUT_OF_SCOPE;
        }
        if (routerFailed && !MyHrKnowledgeGrounding.isPolicyQuestion(message) && !MyHrScopeGuard.isWritingTask(message)) {
            return Verdict.ROUTER_UNAVAILABLE_BUSINESS;
        }
        return Verdict.PROCEED;
    }
}
