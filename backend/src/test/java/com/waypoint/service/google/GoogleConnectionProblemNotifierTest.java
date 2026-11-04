package com.waypoint.service.google;

import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.NotificationType;
import com.waypoint.enums.UserRole;
import com.waypoint.repository.UserRepository;
import com.waypoint.service.InAppNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GoogleConnectionProblemNotifierTest {

    private final UserRepository users = mock(UserRepository.class);
    private final InAppNotificationService notifications = mock(InAppNotificationService.class);
    private GoogleConnectionProblemNotifier notifier;

    private static User admin(String id, String orgId) {
        return User.builder().id(id).organizationId(orgId).role(UserRole.COMPANY_ADMIN).accountStatus(AccountStatus.ACTIVE).build();
    }

    @BeforeEach
    void setUp() {
        notifier = new GoogleConnectionProblemNotifier(users, notifications);
        when(users.findByOrganizationIdAndRoleAndAccountStatus("org-itap", UserRole.COMPANY_ADMIN, AccountStatus.ACTIVE))
                .thenReturn(List.of(admin("itap-admin-1", "org-itap"), admin("itap-admin-2", "org-itap")));
        when(users.findByOrganizationIdAndRoleAndAccountStatus("org-abc", UserRole.COMPANY_ADMIN, AccountStatus.ACTIVE))
                .thenReturn(List.of(admin("abc-admin", "org-abc")));
    }

    @Test
    void everyActiveCompanyAdminOfThatOrganizationIsToldToReconnect() {
        notifier.onReauthRequired(new GoogleConnectionReauthRequiredEvent("org-itap", "INVALID_GRANT"));

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notifications).createNotification(eq("org-itap"), eq("itap-admin-1"), eq(NotificationType.ACCOUNT_ACTIVITY),
                eq(GoogleConnectionProblemNotifier.TITLE), message.capture(), eq("org-itap"), eq("/myhr/admin/organization"));
        verify(notifications).createNotification(eq("org-itap"), eq("itap-admin-2"), eq(NotificationType.ACCOUNT_ACTIVITY),
                eq(GoogleConnectionProblemNotifier.TITLE), anyString(), eq("org-itap"), eq("/myhr/admin/organization"));
        assertThat(message.getValue()).contains("reconnect").doesNotContain("INVALID_GRANT");
    }

    @Test
    void otherOrganizationsAdminsAreNeverNotified() {
        notifier.onReauthRequired(new GoogleConnectionReauthRequiredEvent("org-itap", "HTTP_401"));

        verify(notifications, never()).createNotification(anyString(), eq("abc-admin"), any(), anyString(), anyString(), anyString(), anyString());
        verify(users, never()).findByOrganizationIdAndRoleAndAccountStatus(eq("org-abc"), any(), any());
    }

    @Test
    void aUserFromAnotherOrganizationReturnedByTheLookupIsStillSkipped() {
        when(users.findByOrganizationIdAndRoleAndAccountStatus("org-itap", UserRole.COMPANY_ADMIN, AccountStatus.ACTIVE))
                .thenReturn(List.of(admin("itap-admin-1", "org-itap"), admin("stray", "org-abc")));

        notifier.onReauthRequired(new GoogleConnectionReauthRequiredEvent("org-itap", "HTTP_401"));

        verify(notifications).createNotification(eq("org-itap"), eq("itap-admin-1"), any(), anyString(), anyString(), anyString(), anyString());
        verify(notifications, never()).createNotification(anyString(), eq("stray"), any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void oneAdminsFailureDoesNotStopTheOthers() {
        doThrow(new IllegalStateException("boom")).when(notifications)
                .createNotification(eq("org-itap"), eq("itap-admin-1"), any(), anyString(), anyString(), anyString(), anyString());

        notifier.onReauthRequired(new GoogleConnectionReauthRequiredEvent("org-itap", "HTTP_401"));

        verify(notifications).createNotification(eq("org-itap"), eq("itap-admin-2"), any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void emptyOrMissingEventsDoNothing() {
        notifier.onReauthRequired(null);
        notifier.onReauthRequired(new GoogleConnectionReauthRequiredEvent(" ", "X"));
        notifier.onReauthRequired(new GoogleConnectionReauthRequiredEvent(null, "X"));

        verifyNoInteractions(users, notifications);
    }

    @Test
    void theNotificationTextCarriesNoTokenAddressOrGoogleDetail() {
        String text = GoogleConnectionProblemNotifier.TITLE + " " + GoogleConnectionProblemNotifier.MESSAGE;

        assertThat(text).doesNotContain("@").doesNotContain("token").doesNotContain("invalid_grant").doesNotContain("HTTP_");
    }
}
