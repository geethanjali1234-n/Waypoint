package com.waypoint.enums;

/**
 * An external AI service that company content may be sent to, and for what. Each provider needs its own approval:
 * approval for one never implies approval for another.
 */
public enum ExternalAiProvider {
    /** Anthropic Claude, writing answers from retrieved company-policy excerpts. */
    ANTHROPIC_CLAUDE,
    /** Voyage AI, embedding company-policy text for vector search. */
    VOYAGE_AI
}
