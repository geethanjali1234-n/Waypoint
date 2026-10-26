package com.waypoint.dto.myhr;

import java.util.List;

/** A bounded page of document requests for the worklist. */
public record DocumentRequestPageResponse(
        List<DocumentRequestResponse> items,
        int page,
        int size,
        long totalItems,
        int totalPages) {
}
