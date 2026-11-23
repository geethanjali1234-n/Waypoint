package com.waypoint.dto.myhr;

/**
 * An approved company policy the answer cites as [sourceNumber]. Carries display metadata only:
 * no internal ids, storage keys or policy text.
 */
public record MyHrChatCitation(int sourceNumber, String title, String documentName, boolean hrOnly) {
}
