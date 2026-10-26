package com.waypoint.service;

import com.waypoint.entity.Notification;
import com.waypoint.enums.NotificationType;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InAppNotificationServiceTest {

    private static final String USER_ID = "user-1";

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private InAppNotificationEmailService notificationEmailService;

    private InAppNotificationService inAppNotificationService;

    @BeforeEach
    void setUp() {
        inAppNotificationService = new InAppNotificationService(notificationRepository, notificationEmailService);
    }

    @Test
    void createNotification_validInput_savesAllFieldsWithDefaults() {
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Notification result = inAppNotificationService.createNotification(
                USER_ID, NotificationType.APPLICATION_SUBMITTED, "Application submitted",
                "Your application was submitted.", "application-1", "/job-seeker/applications");

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        Notification saved = captor.getValue();

        assertThat(saved.getRecipientUserId()).isEqualTo(USER_ID);
        assertThat(saved.getNotificationType()).isEqualTo(NotificationType.APPLICATION_SUBMITTED);
        assertThat(saved.getTitle()).isEqualTo("Application submitted");
        assertThat(saved.getMessage()).isEqualTo("Your application was submitted.");
        assertThat(saved.getRelatedEntityId()).isEqualTo("application-1");
        assertThat(saved.getActionUrl()).isEqualTo("/job-seeker/applications");
        assertThat(saved.getIsRead()).isFalse();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getReadAt()).isNull();
        assertThat(result).isSameAs(saved);
        verify(notificationEmailService).deliver(saved);
    }

    @Test
    void createInAppOnlyNotification_savesWithoutDispatchingTheGenericEmail() {
        when(notificationRepository.save(any(Notification.class))).thenAnswer(invocation -> invocation.getArgument(0));

        inAppNotificationService.createInAppOnlyNotification(USER_ID, NotificationType.APPLICATION_SUBMITTED,
                "Application submitted", "Your application was submitted.", "application-1", "/job-seeker/applications");

        verify(notificationEmailService, never()).deliver(any());
    }

    @Test
    void createNotification_missingRecipientUserId_isRejected() {
        assertThatThrownBy(() -> inAppNotificationService.createNotification(
                " ", NotificationType.APPLICATION_SUBMITTED, "Title", "Message", "id-1", "/job-seeker/applications"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Recipient user id is required");

        verifyNoInteractions(notificationRepository);
    }

    @Test
    void createNotification_missingType_isRejected() {
        assertThatThrownBy(() -> inAppNotificationService.createNotification(
                USER_ID, null, "Title", "Message", "id-1", "/job-seeker/applications"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Notification type is required");

        verifyNoInteractions(notificationRepository);
    }

    @Test
    void createNotification_blankTitle_isRejected() {
        assertThatThrownBy(() -> inAppNotificationService.createNotification(
                USER_ID, NotificationType.APPLICATION_SUBMITTED, "  ", "Message", "id-1", "/job-seeker/applications"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Notification title is required");

        verifyNoInteractions(notificationRepository);
    }

    @Test
    void createNotification_blankMessage_isRejected() {
        assertThatThrownBy(() -> inAppNotificationService.createNotification(
                USER_ID, NotificationType.APPLICATION_SUBMITTED, "Title", "", "id-1", "/job-seeker/applications"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Notification message is required");

        verifyNoInteractions(notificationRepository);
    }

    @Test
    void createNotification_blankActionUrl_isRejected() {
        assertThatThrownBy(() -> inAppNotificationService.createNotification(
                USER_ID, NotificationType.APPLICATION_SUBMITTED, "Title", "Message", "id-1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Notification action URL is required");

        verifyNoInteractions(notificationRepository);
    }

    @Test
    void listNotifications_usesAuthenticatedUserIdAndCapsLimit() {
        Notification notification = Notification.builder().id("n-1").recipientUserId(USER_ID).build();
        when(notificationRepository.findByRecipientUserIdOrderByCreatedAtDesc(eq(USER_ID), any(Pageable.class)))
                .thenReturn(List.of(notification));

        List<Notification> result = inAppNotificationService.listNotifications(USER_ID, 10);

        assertThat(result).containsExactly(notification);
        verify(notificationRepository).findByRecipientUserIdOrderByCreatedAtDesc(eq(USER_ID), any(Pageable.class));
    }

    @Test
    void listNotifications_blankAuthenticatedUserId_isRejected() {
        assertThatThrownBy(() -> inAppNotificationService.listNotifications("  ", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Authenticated user ID is required");

        verifyNoInteractions(notificationRepository);
    }

    @Test
    void countUnread_usesAuthenticatedUserId() {
        when(notificationRepository.countByRecipientUserIdAndIsReadFalse(USER_ID)).thenReturn(3L);

        long result = inAppNotificationService.countUnread(USER_ID);

        assertThat(result).isEqualTo(3L);
        verify(notificationRepository).countByRecipientUserIdAndIsReadFalse(USER_ID);
    }

    @Test
    void countUnread_blankAuthenticatedUserId_isRejected() {
        assertThatThrownBy(() -> inAppNotificationService.countUnread(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Authenticated user ID is required");

        verifyNoInteractions(notificationRepository);
    }

    @Test
    void markAsRead_queriesByNotificationIdAndAuthenticatedUserId_andSetsReadState() {
        Notification notification = Notification.builder()
                .id("n-1").recipientUserId(USER_ID).isRead(false).build();
        when(notificationRepository.findByIdAndRecipientUserId("n-1", USER_ID))
                .thenReturn(Optional.of(notification));
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Notification result = inAppNotificationService.markAsRead(USER_ID, "n-1");

        verify(notificationRepository).findByIdAndRecipientUserId("n-1", USER_ID);
        assertThat(result.getIsRead()).isTrue();
        assertThat(result.getReadAt()).isNotNull();
        verify(notificationRepository).save(notification);
    }

    @Test
    void markAsRead_alreadyRead_isIdempotentAndDoesNotSave() {
        LocalDateTime originalReadAt = LocalDateTime.now().minusHours(1);
        Notification notification = Notification.builder()
                .id("n-1").recipientUserId(USER_ID).isRead(true).readAt(originalReadAt).build();
        when(notificationRepository.findByIdAndRecipientUserId("n-1", USER_ID))
                .thenReturn(Optional.of(notification));

        Notification result = inAppNotificationService.markAsRead(USER_ID, "n-1");

        assertThat(result.getReadAt()).isEqualTo(originalReadAt);
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void markAsRead_notFoundForUser_throwsResourceNotFoundException() {
        when(notificationRepository.findByIdAndRecipientUserId("n-1", USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inAppNotificationService.markAsRead(USER_ID, "n-1"))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(notificationRepository, never()).save(any());
    }

    @Test
    void markAsRead_blankAuthenticatedUserId_isRejected() {
        assertThatThrownBy(() -> inAppNotificationService.markAsRead(null, "n-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Authenticated user ID is required");

        verifyNoInteractions(notificationRepository);
    }

    @Test
    void markAsRead_blankNotificationId_isRejected() {
        assertThatThrownBy(() -> inAppNotificationService.markAsRead(USER_ID, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Notification ID is required");

        verifyNoInteractions(notificationRepository);
    }

    @Test
    @SuppressWarnings("unchecked")
    void markAllAsRead_updatesOnlyAuthenticatedUsersUnreadNotifications_withConsistentReadAt() {
        Notification first = Notification.builder().id("n-1").recipientUserId(USER_ID).isRead(false).build();
        Notification second = Notification.builder().id("n-2").recipientUserId(USER_ID).isRead(false).build();
        when(notificationRepository.findByRecipientUserIdAndIsReadFalse(USER_ID))
                .thenReturn(List.of(first, second));

        inAppNotificationService.markAllAsRead(USER_ID);

        verify(notificationRepository).findByRecipientUserIdAndIsReadFalse(USER_ID);

        ArgumentCaptor<List<Notification>> captor = ArgumentCaptor.forClass(List.class);
        verify(notificationRepository).saveAll(captor.capture());
        List<Notification> saved = captor.getValue();

        assertThat(saved).allSatisfy(notification -> {
            assertThat(notification.getIsRead()).isTrue();
            assertThat(notification.getReadAt()).isNotNull();
        });
        assertThat(saved.get(0).getReadAt()).isEqualTo(saved.get(1).getReadAt());
    }

    @Test
    void markAllAsRead_noUnreadNotifications_doesNotCallSaveAll() {
        when(notificationRepository.findByRecipientUserIdAndIsReadFalse(USER_ID)).thenReturn(List.of());

        inAppNotificationService.markAllAsRead(USER_ID);

        verify(notificationRepository, never()).saveAll(any());
    }

    @Test
    void markAllAsRead_blankAuthenticatedUserId_isRejected() {
        assertThatThrownBy(() -> inAppNotificationService.markAllAsRead("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Authenticated user ID is required");

        verifyNoInteractions(notificationRepository);
    }
}
