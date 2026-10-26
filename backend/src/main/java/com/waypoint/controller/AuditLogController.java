package com.waypoint.controller;

import com.waypoint.common.ApiResponse;
import com.waypoint.dto.myhr.AuditHistoryResponse;
import com.waypoint.enums.AuditActionType;
import com.waypoint.enums.AuditEventCategory;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.service.AuditQueryService;
import com.waypoint.service.AuditQueryService.AuditHistoryQuery;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Read-only Audit History for Company Admins. Every response is scoped to the
 * caller's organization by {@link AuditQueryService}.
 */
@RestController
@RequestMapping("/api/myhr/audit")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditQueryService auditQueryService;
    private final CurrentUserResolver currentUserResolver;

    @GetMapping
    public ApiResponse<AuditHistoryResponse> history(
            HttpServletRequest servletRequest,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) AuditActionType actionType,
            @RequestParam(required = false) AuditEventCategory category,
            @RequestParam(required = false) String actorUserId,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String search) {

        String userId = currentUserResolver.resolveUserId(servletRequest);
        AuditHistoryQuery query = AuditHistoryQuery.builder()
                .page(page)
                .size(size)
                .actionType(actionType)
                .category(category)
                .actorUserId(actorUserId)
                .entityType(entityType)
                .entityId(entityId)
                .from(from)
                .to(to)
                .search(search)
                .build();

        return new ApiResponse<>(true, "Audit history retrieved successfully",
                auditQueryService.getHistory(userId, query));
    }
}
