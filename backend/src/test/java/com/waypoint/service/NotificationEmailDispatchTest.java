package com.waypoint.service;

import com.waypoint.entity.EmployeeProfile;
import com.waypoint.entity.Notification;
import com.waypoint.entity.Organization;
import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.NotificationType;
import com.waypoint.enums.UserRole;
import com.waypoint.repository.EmployeeProfileRepository;
import com.waypoint.repository.NotificationRepository;
import com.waypoint.repository.OrganizationRepository;
import com.waypoint.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Wires the real notification persistence, email delivery and organization identity classes together (only the
 * repositories and the mail transport are mocked) to prove that a business notification both persists in-app and
 * dispatches an email to the right person with the right organization identity.
 */
@ExtendWith(MockitoExtension.class)
class NotificationEmailDispatchTest {

    @Mock UserRepository users;
    @Mock EmployeeProfileRepository profiles;
    @Mock OrganizationRepository organizations;
    @Mock NotificationRepository notifications;
    @Mock EmailService emailService;

    private InAppNotificationService inApp;
    private NotificationOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        OrganizationEmailIdentityResolver resolver = new OrganizationEmailIdentityResolver(organizations);
        InAppNotificationEmailService emailDelivery = new InAppNotificationEmailService(
                users, profiles, organizations, emailService, new EmailTemplateService(), resolver);
        ReflectionTestUtils.setField(emailDelivery, "frontendBaseUrl", "https://app.waypoint.example");
        inApp = new InAppNotificationService(notifications, emailDelivery);
        orchestrator = new NotificationOrchestrator(inApp, emailDelivery);
        when(notifications.save(any(Notification.class))).thenAnswer(call -> call.getArgument(0));
    }

    private void employee(String id, String orgId, String accountEmail) {
        when(users.findById(id)).thenReturn(Optional.of(User.builder().id(id).role(UserRole.EMPLOYEE)
                .accountStatus(AccountStatus.ACTIVE).organizationId(orgId).email(accountEmail).build()));
        lenient().when(profiles.findByUserId(id)).thenReturn(Optional.empty());
    }

    private void organization(String id, String organizationEmail, String adminEmail, String replyTo) {
        when(organizations.findById(id)).thenReturn(Optional.of(Organization.builder().id(id).organizationName("Tardus Inc")
                .organizationEmail(organizationEmail).adminEmail(adminEmail).replyToEmail(replyTo).build()));
    }

    private Notification rejection(String orgId, String userId) {
        return inApp.createNotification(orgId, userId, NotificationType.TIMESHEET_REJECTED, "Timesheet rejected",
                "Your timesheet for 2026-10-01 to 2026-10-15 was rejected by hr1. Reason: Hours mismatch on Friday. Please revise and resubmit.",
                "ts-1", "/myhr/employee/timesheets?mode=history&rejected=true&timesheetId=ts-1");
    }

    @Test
    void timesheetRejectionPersistsInAppAndEmailsTheEmployeeWithReasonPeriodAndLink() {
        employee("emp-1", "org-1", "emp1@example.com");
        organization("org-1", "hr@tardus.test", "owner@tardus.test", null);

        Notification saved = rejection("org-1", "emp-1");

        assertThat(saved.getRecipientUserId()).isEqualTo("emp-1");
        verify(notifications).save(any(Notification.class));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<EmailSenderIdentity> identity = ArgumentCaptor.forClass(EmailSenderIdentity.class);
        verify(emailService).sendHtmlEmail(eq("emp1@example.com"), eq("Waypoint: Timesheet rejected"), body.capture(),
                isNull(), identity.capture());
        assertThat(body.getValue()).contains("2026-10-01 to 2026-10-15", "Hours mismatch on Friday", "View timesheet",
                "https://app.waypoint.example/myhr/employee/timesheets?mode=history&amp;rejected=true&amp;timesheetId=ts-1");
        // Organization Email is Reply-To; Admin Email is never the sender or Reply-To.
        assertThat(identity.getValue().replyTo()).isEqualTo("hr@tardus.test");
        assertThat(identity.getValue().fromAddress()).isNull();
        assertThat(identity.getValue().replyTo()).isNotEqualTo("owner@tardus.test");
        verify(organizations).findById("org-1");
    }

    @Test
    void anExplicitReplyToOverridesTheOrganizationEmailButAdminEmailIsStillIgnored() {
        employee("emp-1", "org-1", "emp1@example.com");
        organization("org-1", "hr@tardus.test", "owner@tardus.test", "payroll@tardus.test");

        rejection("org-1", "emp-1");

        ArgumentCaptor<EmailSenderIdentity> identity = ArgumentCaptor.forClass(EmailSenderIdentity.class);
        verify(emailService).sendHtmlEmail(eq("emp1@example.com"), anyString(), anyString(), isNull(), identity.capture());
        assertThat(identity.getValue().replyTo()).isEqualTo("payroll@tardus.test");
    }

    @Test
    void aSendFailureNeverRemovesTheInAppNotificationOrThrows() {
        employee("emp-1", "org-1", "emp1@example.com");
        organization("org-1", "hr@tardus.test", null, null);
        doThrow(new IllegalStateException("smtp unavailable")).when(emailService)
                .sendHtmlEmail(anyString(), anyString(), anyString(), isNull(), any());

        Notification saved = rejection("org-1", "emp-1");

        assertThat(saved).isNotNull();
        verify(notifications).save(any(Notification.class));
        verify(emailService, times(1)).sendHtmlEmail(anyString(), anyString(), anyString(), isNull(), any());
    }

    @Test
    void anotherOrganizationsUserNeverReceivesTheEmailOrItsIdentity() {
        employee("emp-9", "org-2", "other@example.com");

        rejection("org-1", "emp-9");

        verify(notifications).save(any(Notification.class));
        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString(), any(), any());
        verify(organizations, never()).findById("org-1");
    }

    @Test
    void personalEmailIsPreferredAndOrganizationIdentityComesFromTheNotificationsOrganization() {
        employee("emp-1", "org-1", "login@example.com");
        when(profiles.findByUserId("emp-1")).thenReturn(Optional.of(EmployeeProfile.builder().userId("emp-1")
                .organizationId("org-1").personalEmail("personal@example.com").build()));
        organization("org-1", "hr@tardus.test", null, null);

        rejection("org-1", "emp-1");

        verify(emailService).sendHtmlEmail(eq("personal@example.com"), anyString(), anyString(), isNull(), any());
        verify(emailService, never()).sendHtmlEmail(eq("login@example.com"), anyString(), anyString(), any(), any());
    }

    @Test
    void anOrchestratedEventPersistsInAppOnceAndSendsExactlyOneEmail() {
        employee("hr-1", "org-1", "hr1@example.com");
        organization("org-1", "hr@tardus.test", null, null);
        NotificationEvent event = NotificationEvent.builder().organizationId("org-1").recipientUserId("hr-1")
                .type(NotificationType.DOCUMENT_REQUEST_CREATED).title("New employee document request")
                .message("Priya requested \"Employment letter\".").relatedEntityId("req-1")
                .actionPath("/myhr/hr/document-requests/req-1").email(true).actionLabel("Open Request").build();

        orchestrator.notify(event);

        verify(notifications, times(1)).save(any(Notification.class));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailService, times(1)).sendHtmlEmail(eq("hr1@example.com"), eq("New employee document request"),
                body.capture(), isNull(), any());
        assertThat(body.getValue()).contains("Employment letter", "Open Request",
                "https://app.waypoint.example/myhr/hr/document-requests/req-1");
    }

    @Test
    void anOrchestratedEventFlaggedNoEmailIsInAppOnly() {
        NotificationEvent event = NotificationEvent.builder().organizationId("org-1").recipientUserId("hr-1")
                .type(NotificationType.DOCUMENT_REQUEST_FULFILLED).title("Done").message("Fulfilled")
                .relatedEntityId("req-1").actionPath("/myhr/hr/document-requests/req-1").email(false).build();

        orchestrator.notify(event);

        verify(notifications).save(any(Notification.class));
        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString(), any(), any());
    }
}
