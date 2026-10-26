package com.waypoint.service;

import com.waypoint.entity.AuditLog;
import com.waypoint.enums.AuditActionType;
import com.waypoint.enums.UserRole;
import com.waypoint.repository.AuditLogRepository;
import com.waypoint.service.AuditActorResolver.ActorInfo;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * Single entry point for writing the organization audit trail.
 *
 * <p>Design rules:
 * <ul>
 *   <li><b>Never throws.</b> A failure to record an audit event must not roll back or
 *       break the business operation that triggered it — every failure is swallowed and logged.</li>
 *   <li><b>Best-effort enrichment.</b> Actor display name / role / login id are resolved
 *       when missing, but a missing lookup only means a sparser row, never a failure.</li>
 *   <li><b>Org-scoped.</b> Events without an organization id are dropped; the audit trail
 *       is only ever read per-organization.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final String DEFAULT_SOURCE = "MY_HR";
    private static final String MODULE = "MY_HR";
    private static final String OUTCOME_SUCCESS = "SUCCESS";

    private final AuditLogRepository auditLogRepository;
    private final AuditActorResolver actorResolver;

    /**
     * Convenience overload for the common case: an authenticated My HR user changing one entity.
     */
    public void record(com.waypoint.security.MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor,
                       AuditActionType actionType,
                       String entityType,
                       String entityId,
                       String entityDisplayName,
                       String description,
                       Object previousValue,
                       Object updatedValue) {
        record(AuditEvent.builder()
                .organizationId(actor == null ? null : actor.organizationId())
                .actorUserId(actor == null ? null : actor.userId())
                .actorLoginId(actor == null ? null : actor.loginId())
                .actorRole(actor == null ? null : actor.role())
                .actionType(actionType)
                .entityType(entityType)
                .entityId(entityId)
                .entityDisplayName(entityDisplayName)
                .description(description)
                .previousValue(previousValue)
                .updatedValue(updatedValue)
                .build());
    }

    /**
     * Records an event described entirely by the caller. All enrichment and persistence
     * failures are contained here.
     */
    public void record(AuditEvent event) {
        try {
            if (event == null) {
                return;
            }
            if (isBlank(event.getOrganizationId()) || event.getActionType() == null) {
                log.warn("Skipping audit event with missing organization id or action type: {}", event);
                return;
            }

            // Idempotent, deterministic events (backfill / reconciliation) — skip if already recorded.
            if (StringUtils.hasText(event.getEventId()) && auditLogRepository.existsById(event.getEventId())) {
                return;
            }

            LocalDateTime now = LocalDateTime.now();
            LocalDateTime performedAt = event.getOccurredAt() != null ? event.getOccurredAt() : now;

            String loginId = event.getActorLoginId();
            UserRole role = event.getActorRole();
            String name = null;
            if (!isBlank(event.getActorUserId()) && (loginId == null || role == null || name == null)) {
                ActorInfo resolved = actorResolver.resolveOne(event.getActorUserId());
                loginId = loginId != null ? loginId : resolved.loginId();
                role = role != null ? role : resolved.role();
                name = resolved.name();
            }

            AuditLog.AuditLogBuilder builder = AuditLog.builder()
                    .organizationId(event.getOrganizationId())
                    .performedByUserId(event.getActorUserId())
                    .actorLoginId(loginId)
                    .actorName(name)
                    .performedByRole(role == null ? null : role.name())
                    .actionType(event.getActionType())
                    .entityType(event.getEntityType())
                    .entityId(event.getEntityId())
                    .entityDisplayName(event.getEntityDisplayName())
                    .eventCategory(AuditActionCatalog.categoryOf(event.getActionType()).name())
                    .module(MODULE)
                    .previousValue(event.getPreviousValue())
                    .updatedValue(event.getUpdatedValue())
                    .description(event.getDescription() != null
                            ? event.getDescription()
                            : AuditActionCatalog.labelOf(event.getActionType()))
                    .outcome(event.getOutcome() != null ? event.getOutcome() : OUTCOME_SUCCESS)
                    .failureReason(event.getFailureReason())
                    .source(event.getSource() != null ? event.getSource() : DEFAULT_SOURCE)
                    .requestId(event.getRequestId())
                    .ipAddress(event.getIpAddress())
                    .userAgent(event.getUserAgent())
                    .performedAt(performedAt)
                    .createdAt(now);

            if (StringUtils.hasText(event.getEventId())) {
                builder.id(event.getEventId());
            }

            auditLogRepository.save(builder.build());
        } catch (RuntimeException exception) {
            log.error("Failed to write audit event ({}). The business operation was not affected.",
                    event == null ? "null" : event.getActionType(), exception);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
