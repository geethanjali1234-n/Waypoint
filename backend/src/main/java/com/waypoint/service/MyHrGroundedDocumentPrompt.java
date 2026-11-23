package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrChatHistoryMessage;

import java.util.List;

/**
 * A knowledge-grounded request for a provider that cites documents itself (Claude citations). It carries the
 * same trusted rules, escaped history and escaped question as the text prompt, but each authorized source is
 * its own plain-text document, so the provider reports which passage of which supplied document supports each
 * statement instead of writing citation markers. Documents are in source order: document index i is source
 * i + 1, the number the chat API already shows.
 */
public record MyHrGroundedDocumentPrompt(
        String systemPrompt,
        List<MyHrChatHistoryMessage> history,
        List<Document> documents,
        String question) {

    public MyHrGroundedDocumentPrompt {
        history = history == null ? List.of() : List.copyOf(history);
        documents = List.copyOf(documents);
    }

    /** Prompts, questions and policy text stay out of logs. */
    @Override
    public String toString() {
        return "MyHrGroundedDocumentPrompt[historyMessages=" + history.size() + ", documents=" + documents.size() + "]";
    }

    /** One authorized source. context is shown to the provider but never cited; null when there is none. */
    public record Document(String title, String context, String text) {

        @Override
        public String toString() {
            return "Document[textLength=" + (text == null ? 0 : text.length()) + "]";
        }
    }
}
