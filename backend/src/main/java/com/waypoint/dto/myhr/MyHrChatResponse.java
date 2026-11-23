package com.waypoint.dto.myhr;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * knowledgeStatus and citations are omitted from JSON unless knowledge grounding applied, so
 * responses stay identical to the original {"message": ...} shape while grounding is disabled.
 * historyProvenance carries the server's stamps for this turn's question and answer, which the
 * browser returns with them as conversation history; it is omitted when no stamp was issued.
 * actions names the Waypoint pages the answer points to, as page keys the chat UI turns into the
 * signed-in role's own routes: null (omitted) when the route that answered leaves the shortcut to the
 * chat UI, an empty list when the answer must not offer any shortcut.
 */
public record MyHrChatResponse(
        String message,
        @JsonInclude(JsonInclude.Include.NON_NULL) MyHrKnowledgeStatus knowledgeStatus,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<MyHrChatCitation> citations,
        @JsonInclude(JsonInclude.Include.NON_NULL) HistoryProvenance historyProvenance,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<String> actions) {

    public MyHrChatResponse {
        citations = citations == null ? List.of() : List.copyOf(citations);
        actions = actions == null ? null : List.copyOf(actions);
    }

    public MyHrChatResponse(String message, MyHrKnowledgeStatus knowledgeStatus, List<MyHrChatCitation> citations,
                            HistoryProvenance historyProvenance) {
        this(message, knowledgeStatus, citations, historyProvenance, null);
    }

    public MyHrChatResponse(String message, MyHrKnowledgeStatus knowledgeStatus, List<MyHrChatCitation> citations) {
        this(message, knowledgeStatus, citations, null, null);
    }

    public MyHrChatResponse(String message) {
        this(message, null, List.of());
    }

    public MyHrChatResponse withHistoryProvenance(HistoryProvenance provenance) {
        return new MyHrChatResponse(message, knowledgeStatus, citations, provenance, actions);
    }

    /** The same answer pointing to exactly these pages; an empty list offers no shortcut. */
    public MyHrChatResponse withActions(List<String> pageKeys) {
        return new MyHrChatResponse(message, knowledgeStatus, citations, historyProvenance,
                pageKeys == null ? List.of() : pageKeys);
    }

    /** The stamps for this turn's user message (the trimmed question) and assistant message (this message). */
    public record HistoryProvenance(String user, String assistant) {
    }
}
