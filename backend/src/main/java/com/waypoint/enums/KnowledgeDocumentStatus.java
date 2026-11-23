package com.waypoint.enums;

/** Lifecycle of a knowledge base entry. Only LIVE entries serve chunks; REVOKED is final. */
public enum KnowledgeDocumentStatus {
    LIVE,
    REVOKED
}
