package com.waypoint.service;

import com.waypoint.dto.myhr.AuditActionOption;
import com.waypoint.dto.myhr.AuditHistoryResponse;
import com.waypoint.dto.myhr.AuditLogEntryResponse;
import com.waypoint.entity.AuditLog;
import com.waypoint.enums.AuditActionType;
import com.waypoint.enums.AuditEventCategory;
import com.waypoint.enums.UserRole;
import com.waypoint.security.MyHrAuthenticatedUserService;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Read side of the organization audit trail. Company-Admin only, always scoped to
 * the caller's organization, queried natively against the single {@code audit_logs}
 * collection with server-side filtering, sorting and pagination.
 *
 * <p>Every audit row — whoever wrote it ({@link AuditService} for account/reporting/
 * security events, teammates' services for other event categories) — lives in
 * {@code audit_logs}, so this needs no merge step.
 */
@Service
@RequiredArgsConstructor
public class AuditQueryService {

    private static final Set<UserRole> AUDIT_READERS = Set.of(UserRole.COMPANY_ADMIN);
    private static final int DEFAULT_PAGE_SIZE = 25;
    private static final int MAX_PAGE_SIZE = 100;

    private final MongoTemplate mongoTemplate;
    private final MyHrAuthenticatedUserService authenticatedUserService;

    /** Filter + pagination inputs for {@link #getHistory(String, AuditHistoryQuery)}. */
    @Builder
    public record AuditHistoryQuery(
            Integer page,
            Integer size,
            AuditActionType actionType,
            AuditEventCategory category,
            String actorUserId,
            String entityType,
            String entityId,
            LocalDate from,
            LocalDate to,
            String search) {
    }

    public AuditHistoryResponse getHistory(String actorUserId, AuditHistoryQuery query) {
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor =
                authenticatedUserService.require(actorUserId, AUDIT_READERS, false);

        int page = query.page() == null || query.page() < 0 ? 0 : query.page();
        int size = normalizeSize(query.size());

        List<Criteria> filters = new ArrayList<>();
        filters.add(Criteria.where("organizationId").is(actor.organizationId()));
        addEquals(filters, "actionType", query.actionType());
        addEquals(filters, "eventCategory", query.category() == null ? null : query.category().name());
        addEquals(filters, "performedByUserId", trimToNull(query.actorUserId()));
        addEquals(filters, "entityType", trimToNull(query.entityType()));
        addEquals(filters, "entityId", trimToNull(query.entityId()));
        addDateRange(filters, query.from(), query.to());
        addSearch(filters, trimToNull(query.search()));

        Criteria criteria = new Criteria().andOperator(filters.toArray(new Criteria[0]));

        long total = mongoTemplate.count(new Query(criteria), AuditLog.class);

        Query pageQuery = new Query(criteria)
                .with(Sort.by(Sort.Direction.DESC, "performedAt"))
                .skip((long) page * size)
                .limit(size);
        List<AuditLogEntryResponse> items = mongoTemplate.find(pageQuery, AuditLog.class).stream()
                .map(this::toResponse)
                .toList();

        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) total / size);

        return new AuditHistoryResponse(
                items, page, size, total, totalPages, actionCatalog(), categoryNames());
    }

    private int normalizeSize(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(requested, MAX_PAGE_SIZE);
    }

    private void addEquals(List<Criteria> filters, String field, Object value) {
        if (value != null) {
            filters.add(Criteria.where(field).is(value));
        }
    }

    private void addDateRange(List<Criteria> filters, LocalDate from, LocalDate to) {
        if (from == null && to == null) {
            return;
        }
        Criteria performedAt = Criteria.where("performedAt");
        if (from != null) {
            performedAt.gte(from.atStartOfDay());
        }
        if (to != null) {
            performedAt.lt(to.plusDays(1).atStartOfDay());
        }
        filters.add(performedAt);
    }

    private void addSearch(List<Criteria> filters, String search) {
        if (search == null) {
            return;
        }
        String quoted = Pattern.quote(search);
        filters.add(new Criteria().orOperator(
                Criteria.where("description").regex(quoted, "i"),
                Criteria.where("entityDisplayName").regex(quoted, "i"),
                Criteria.where("actorName").regex(quoted, "i"),
                Criteria.where("actorLoginId").regex(quoted, "i"),
                Criteria.where("entityId").regex(quoted, "i")));
    }

    private AuditLogEntryResponse toResponse(AuditLog log) {
        AuditEventCategory category = log.getActionType() == null
                ? null
                : AuditActionCatalog.categoryOf(log.getActionType());
        String label = log.getActionType() == null
                ? null
                : AuditActionCatalog.labelOf(log.getActionType());
        return new AuditLogEntryResponse(
                log.getId(),
                log.getPerformedAt(),
                log.getActionType(),
                label,
                category,
                log.getDescription(),
                log.getPerformedByUserId(),
                log.getActorLoginId(),
                log.getActorName(),
                log.getPerformedByRole(),
                log.getEntityType(),
                log.getEntityId(),
                log.getEntityDisplayName(),
                log.getPreviousValue(),
                log.getUpdatedValue(),
                log.getOutcome(),
                log.getSource());
    }

    private List<AuditActionOption> actionCatalog() {
        List<AuditActionOption> options = new ArrayList<>();
        for (AuditActionType type : AuditActionType.values()) {
            AuditActionCatalog.Descriptor descriptor = AuditActionCatalog.describe(type);
            options.add(new AuditActionOption(type, descriptor.label(), descriptor.category()));
        }
        options.sort((a, b) -> a.label().compareToIgnoreCase(b.label()));
        return options;
    }

    private List<String> categoryNames() {
        return Arrays.stream(AuditEventCategory.values()).map(Enum::name).toList();
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
