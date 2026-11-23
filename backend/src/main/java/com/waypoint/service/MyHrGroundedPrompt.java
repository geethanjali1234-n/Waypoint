package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrChatHistoryMessage;

import java.util.List;

/**
 * One fully built, provider-neutral knowledge-grounded chat request. Claude and the Ollama
 * fallback receive exactly this system prompt, history and final user message, so a fallback
 * never sees different - or additional - policy context than the primary provider.
 *
 * <p>containsPolicyContext is true when the user message carries company policy excerpts; such a
 * prompt may only go to a provider approved for policy text.
 */
public record MyHrGroundedPrompt(
        String systemPrompt,
        List<MyHrChatHistoryMessage> history,
        String userMessage,
        boolean containsPolicyContext) {

    public MyHrGroundedPrompt {
        history = history == null ? List.of() : List.copyOf(history);
    }

    /** Assumes policy context is present, so the most restrictive routing applies. */
    public MyHrGroundedPrompt(String systemPrompt, List<MyHrChatHistoryMessage> history, String userMessage) {
        this(systemPrompt, history, userMessage, true);
    }

    /** Prompts, questions and policy excerpts stay out of logs. */
    @Override
    public String toString() {
        return "MyHrGroundedPrompt[historyMessages=" + history.size() + ", policyContext=" + containsPolicyContext + "]";
    }
}
