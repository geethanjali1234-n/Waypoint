package com.waypoint.dto.myhr;

import java.util.List;

/**
 * A page of organization audit activity plus the catalog the UI needs to render
 * its filters. The catalog is returned with every page so the front end has a
 * single round trip.
 */
public record AuditHistoryResponse(
        List<AuditLogEntryResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        List<AuditActionOption> actionCatalog,
        List<String> categories) {
}
