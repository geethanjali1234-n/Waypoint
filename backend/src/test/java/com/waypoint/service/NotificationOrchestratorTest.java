package com.waypoint.service;

import com.waypoint.entity.EmployeeProfile;
import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.NotificationType;
import com.waypoint.enums.UserRole;
import com.waypoint.repository.EmployeeProfileRepository;
import com.waypoint.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationOrchestratorTest {

    @Mock InAppNotificationService inApp;
    @Mock EmailService emailService;
    @Mock UserRepository users;
    @Mock EmployeeProfileRepository profiles;
    NotificationOrchestrator orchestrator;

    @BeforeEach void setUp() {
        orchestrator = TestOrchestrators.create(inApp, emailService, new EmailTemplateService(), users, profiles);
    }

    private static User user(String org, AccountStatus status, String email) {
        return User.builder().id("u1").loginId("u1").email(email).role(UserRole.EMPLOYEE).organizationId(org).accountStatus(status).build();
    }

    private static EmployeeProfile profile(String org, String personal, String work) {
        return EmployeeProfile.builder().userId("u1").organizationId(org).personalEmail(personal).workEmail(work).build();
    }

    private static NotificationEvent event(boolean email) {
        return NotificationEvent.builder().organizationId("org-1").recipientUserId("u1")
                .type(NotificationType.PROFILE_CHANGE_APPROVED).title("Title").message("Message")
                .relatedEntityId("r1").actionPath("/myhr/employee/x").email(email).build();
    }

    @Test void personalEmailIsPreferredOverAccountEmailAndWorkEmailIsNeverUsed() {
        when(users.findById("u1")).thenReturn(Optional.of(user("org-1", AccountStatus.ACTIVE, "login@x.com")));
        when(profiles.findByUserId("u1")).thenReturn(Optional.of(profile("org-1", "personal@x.com", "work@x.com")));
        orchestrator.notify(event(true));
        verify(inApp).createInAppOnlyNotification("org-1", "u1", NotificationType.PROFILE_CHANGE_APPROVED, "Title", "Message", "r1", "/myhr/employee/x");
        verify(emailService).sendHtmlEmail(eq("personal@x.com"), eq("Title"), anyString());
    }

    @Test void fallsBackToAccountEmailAndSkipsWorkEmail() {
        when(users.findById("u1")).thenReturn(Optional.of(user("org-1", AccountStatus.ACTIVE, "login@x.com")));
        when(profiles.findByUserId("u1")).thenReturn(Optional.of(profile("org-1", " ", "work@x.com")));
        orchestrator.notify(event(true));
        verify(emailService).sendHtmlEmail(eq("login@x.com"), anyString(), anyString());
    }

    @Test void noAddressKeepsInAppAndSendsNoEmail() {
        when(users.findById("u1")).thenReturn(Optional.of(user("org-1", AccountStatus.ACTIVE, null)));
        when(profiles.findByUserId("u1")).thenReturn(Optional.of(profile("org-1", null, "work@x.com")));
        orchestrator.notify(event(true));
        verify(inApp).createInAppOnlyNotification(anyString(), anyString(), org.mockito.ArgumentMatchers.any(), anyString(), anyString(), anyString(), anyString());
        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString());
    }

    @Test void otherOrganizationOrInactiveUsersGetNoEmail() {
        when(users.findById("u1")).thenReturn(Optional.of(user("org-2", AccountStatus.ACTIVE, "login@x.com")));
        orchestrator.notify(event(true));
        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString());
        when(users.findById("u1")).thenReturn(Optional.of(user("org-1", AccountStatus.INACTIVE, "login@x.com")));
        orchestrator.notify(event(true));
        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString());
    }

    @Test void inAppOnlyEventNeverLooksUpOrSendsEmail() {
        orchestrator.notify(event(false));
        verify(inApp).createInAppOnlyNotification(anyString(), anyString(), org.mockito.ArgumentMatchers.any(), anyString(), anyString(), anyString(), anyString());
        verify(users, never()).findById(anyString());
        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString());
    }

    @Test void emailFailureNeverPropagatesAndInAppIsKept() {
        when(users.findById("u1")).thenReturn(Optional.of(user("org-1", AccountStatus.ACTIVE, "login@x.com")));
        when(profiles.findByUserId("u1")).thenReturn(Optional.empty());
        doThrow(new RuntimeException("smtp down")).when(emailService).sendHtmlEmail(anyString(), anyString(), anyString());
        orchestrator.notify(event(true));
        verify(inApp).createInAppOnlyNotification(anyString(), anyString(), org.mockito.ArgumentMatchers.any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test void inAppFailureDoesNotStopTheEmailOrPropagate() {
        when(inApp.createInAppOnlyNotification(anyString(), anyString(), org.mockito.ArgumentMatchers.any(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("db down"));
        when(users.findById("u1")).thenReturn(Optional.of(user("org-1", AccountStatus.ACTIVE, "login@x.com")));
        when(profiles.findByUserId("u1")).thenReturn(Optional.empty());
        orchestrator.notify(event(true));
        verify(emailService).sendHtmlEmail(eq("login@x.com"), anyString(), anyString());
    }

    @Test void oneEmailEventProducesExactlyOneEmailAndNeverTheGenericNotificationEmailPath() {
        when(users.findById("u1")).thenReturn(Optional.of(user("org-1", AccountStatus.ACTIVE, "login@x.com")));
        when(profiles.findByUserId("u1")).thenReturn(Optional.empty());
        orchestrator.notify(event(true));
        verify(emailService, times(1)).sendHtmlEmail(anyString(), anyString(), anyString());
        verify(inApp, times(1)).createInAppOnlyNotification(anyString(), anyString(), org.mockito.ArgumentMatchers.any(), anyString(), anyString(), anyString(), anyString());
        // createNotification is the path that also emails; an event must never take it as well.
        verify(inApp, never()).createNotification(anyString(), anyString(), org.mockito.ArgumentMatchers.any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test void anEventWhoseDedicatedEmailAlreadyExistsSendsNoNotificationEmail() {
        orchestrator.notify(event(false));
        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString());
        verify(emailService, never()).sendHtmlEmailWithAttachments(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyList());
    }

    @Test void notifyAllDeduplicatesRecipientsByUserId() {
        orchestrator.notifyAll(List.of(event(false), event(false)));
        verify(inApp, times(1)).createInAppOnlyNotification(anyString(), anyString(), org.mockito.ArgumentMatchers.any(), anyString(), anyString(), anyString(), anyString());
    }
}
