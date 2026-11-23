package com.waypoint.dto.myhr;

/** Whether a chat answer was grounded in the approved MyHR knowledge base; absent when grounding did not apply. */
public enum MyHrKnowledgeStatus {
    /** The answer cites at least one approved company policy source. */
    GROUNDED,
    /** Company policies were searched, but no approved policy was cited for this answer. */
    NO_MATCHING_POLICY,
    /** Company policy search failed, so the answer is not based on company policy documents. */
    KNOWLEDGE_UNAVAILABLE,
    /** Policy excerpts were supplied but the answer cited none, so it was withheld as unverifiable. */
    UNVERIFIED
}
