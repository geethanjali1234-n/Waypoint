package com.waypoint.enums;

public enum RequestedDocumentType {
    I983,
    EMPLOYMENT_VERIFICATION,
    EXPERIENCE_LETTER,
    /**
     * Retired: new requests are rejected (see DocumentRequestService#parseDocumentType). Kept only so requests
     * already stored with this type still load; remove once none remain in the database.
     */
    @Deprecated
    EMPLOYMENT_LETTER,
    OFFER_LETTER,
    OTHER
}
