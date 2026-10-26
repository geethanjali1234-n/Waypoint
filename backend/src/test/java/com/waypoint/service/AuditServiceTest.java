package com.waypoint.service;

import com.waypoint.entity.AuditLog;
import com.waypoint.enums.AuditActionType;
import com.waypoint.enums.AuditEventCategory;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.UserRole;
import com.waypoint.repository.AuditLogRepository;
import com.waypoint.security.MyHrAuthenticatedUserService.AuthenticatedMyHrUser;
import com.waypoint.service.AuditActorResolver.ActorInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock AuditLogRepository auditLogRepository;
    @Mock AuditActorResolver actorResolver;

    private AuditService auditService;

    @BeforeEach
    void setUp() {
        auditService = new AuditService(auditLogRepository, actorResolver);
    }

    private AuthenticatedMyHrUser actor(String userId, String loginId, UserRole role) {
        return new AuthenticatedMyHrUser(userId, loginId, userId + "@tardus.test", role, "org-1",
                AccountStatus.ACTIVE, false);
    }

    @Test
    void skipsEventWithoutOrganizationId() {
        auditService.record(AuditEvent.builder()
                .actionType(AuditActionType.ACCOUNT_STATUS_CHANGED)
                .actorUserId("hr-1")
                .build());

        verify(auditLogRepository, never()).save(any());
        verifyNoInteractions(actorResolver);
    }

    @Test
    void skipsEventWithoutActionType() {
        auditService.record(AuditEvent.builder()
                .organizationId("org-1")
                .actorUserId("hr-1")
                .build());

        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void skipsNullEventEntirely() {
        assertThatCode(() -> auditService.record((AuditEvent) null)).doesNotThrowAnyException();
        verifyNoInteractions(auditLogRepository, actorResolver);
    }

    @Test
    void skipsWriteWhenDeterministicEventIdAlreadyRecorded() {
        when(auditLogRepository.existsById("pcr:1:requested")).thenReturn(true);

        auditService.record(AuditEvent.builder()
                .eventId("pcr:1:requested")
                .organizationId("org-1")
                .actionType(AuditActionType.PROFILE_CHANGE_REQUESTED)
                .build());

        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void persistsEventWithCatalogCategoryAndDefaults() {
        when(actorResolver.resolveOne("hr-1"))
                .thenReturn(new ActorInfo("TD001", UserRole.HR, "Jane HR"));

        auditService.record(AuditEvent.builder()
                .organizationId("org-1")
                .actorUserId("hr-1")
                .actionType(AuditActionType.ACCOUNT_STATUS_CHANGED)
                .entityType("USER")
                .entityId("emp-1")
                .entityDisplayName("John Doe")
                .previousValue(Map.of("accountStatus", "ACTIVE"))
                .updatedValue(Map.of("accountStatus", "INACTIVE"))
                .build());

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog saved = captor.getValue();

        assertThat(saved.getOrganizationId()).isEqualTo("org-1");
        assertThat(saved.getPerformedByUserId()).isEqualTo("hr-1");
        assertThat(saved.getActorLoginId()).isEqualTo("TD001");
        assertThat(saved.getActorName()).isEqualTo("Jane HR");
        assertThat(saved.getPerformedByRole()).isEqualTo("HR");
        assertThat(saved.getEventCategory()).isEqualTo(AuditEventCategory.ACCOUNT_MANAGEMENT.name());
        assertThat(saved.getModule()).isEqualTo("MY_HR");
        assertThat(saved.getOutcome()).isEqualTo("SUCCESS");
        assertThat(saved.getSource()).isEqualTo("MY_HR");
        assertThat(saved.getDescription()).isEqualTo("Account status changed");
        assertThat(saved.getPerformedAt()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void usesCallerSuppliedDescriptionAndOutcomeInsteadOfDefaults() {
        when(actorResolver.resolveOne("hr-1")).thenReturn(ActorInfo.UNKNOWN);

        auditService.record(AuditEvent.builder()
                .organizationId("org-1")
                .actorUserId("hr-1")
                .actionType(AuditActionType.ACCOUNT_STATUS_CHANGED)
                .description("Custom description")
                .outcome("FAILURE")
                .failureReason("boom")
                .build());

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog saved = captor.getValue();

        assertThat(saved.getDescription()).isEqualTo("Custom description");
        assertThat(saved.getOutcome()).isEqualTo("FAILURE");
        assertThat(saved.getFailureReason()).isEqualTo("boom");
    }

    @Test
    void resolvesDisplayNameEvenWhenActorLoginIdAndRoleAreAlreadyKnown() {
        when(actorResolver.resolveOne("hr-1"))
                .thenReturn(new ActorInfo("SHOULD-NOT-OVERRIDE", UserRole.EMPLOYEE, "Jane HR"));

        auditService.record(AuditEvent.builder()
                .organizationId("org-1")
                .actorUserId("hr-1")
                .actorLoginId("TD001")
                .actorRole(UserRole.HR)
                .actionType(AuditActionType.ACCOUNT_STATUS_CHANGED)
                .build());

        verify(actorResolver).resolveOne("hr-1");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog saved = captor.getValue();

        assertThat(saved.getActorLoginId()).isEqualTo("TD001");
        assertThat(saved.getPerformedByRole()).isEqualTo("HR");
        assertThat(saved.getActorName()).isEqualTo("Jane HR");
    }

    @Test
    void doesNotResolveActorWhenActionIsSystemGenerated() {
        auditService.record(AuditEvent.builder()
                .organizationId("org-1")
                .actionType(AuditActionType.PROFILE_CHANGED)
                .build());

        verifyNoInteractions(actorResolver);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getPerformedByUserId()).isNull();
    }

    @Test
    void usesDeterministicEventIdAndHistoricalOccurredAtWhenProvided() {
        when(actorResolver.resolveOne(anyString())).thenReturn(ActorInfo.UNKNOWN);
        LocalDateTime historical = LocalDateTime.of(2026, 1, 1, 9, 0);

        auditService.record(AuditEvent.builder()
                .eventId("pcr:99:requested")
                .occurredAt(historical)
                .organizationId("org-1")
                .actorUserId("hr-1")
                .actionType(AuditActionType.PROFILE_CHANGE_REQUESTED)
                .source("PROFILE_CHANGE_RECONCILER")
                .build());

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog saved = captor.getValue();

        assertThat(saved.getId()).isEqualTo("pcr:99:requested");
        assertThat(saved.getPerformedAt()).isEqualTo(historical);
        assertThat(saved.getSource()).isEqualTo("PROFILE_CHANGE_RECONCILER");
    }

    @Test
    void neverThrowsWhenRepositoryFailsToSave() {
        when(actorResolver.resolveOne(anyString())).thenReturn(ActorInfo.UNKNOWN);
        when(auditLogRepository.save(any())).thenThrow(new RuntimeException("mongo down"));

        assertThatCode(() -> auditService.record(AuditEvent.builder()
                .organizationId("org-1")
                .actorUserId("hr-1")
                .actionType(AuditActionType.ACCOUNT_STATUS_CHANGED)
                .build())).doesNotThrowAnyException();
    }

    @Test
    void convenienceOverloadBuildsEventFromAuthenticatedActor() {
        AuthenticatedMyHrUser actor = actor("hr-1", "TD001", UserRole.HR);
        when(actorResolver.resolveOne("hr-1")).thenReturn(new ActorInfo("TD001", UserRole.HR, "Jane HR"));

        auditService.record(actor, AuditActionType.REPORTING_MANAGER_ASSIGNED, "USER", "emp-1", "John Doe",
                "John Doe assigned to reporting manager Jane HR",
                null, Map.of("reportingManagerUserId", "hr-1"));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog saved = captor.getValue();

        assertThat(saved.getOrganizationId()).isEqualTo("org-1");
        assertThat(saved.getPerformedByUserId()).isEqualTo("hr-1");
        assertThat(saved.getActionType()).isEqualTo(AuditActionType.REPORTING_MANAGER_ASSIGNED);
        assertThat(saved.getEntityId()).isEqualTo("emp-1");
        assertThat(saved.getEventCategory()).isEqualTo(AuditEventCategory.REPORTING_STRUCTURE.name());
    }

    @Test
    void convenienceOverloadDropsEventWhenActorIsNull() {
        auditService.record(null, AuditActionType.ACCOUNT_STATUS_CHANGED, "USER", "emp-1", "John Doe",
                "desc", null, null);

        verify(auditLogRepository, never()).save(any());
    }
}
