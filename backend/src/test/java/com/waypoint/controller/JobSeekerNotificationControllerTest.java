package com.waypoint.controller;

import com.waypoint.entity.Notification;
import com.waypoint.enums.NotificationType;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.security.JwtService;
import com.waypoint.service.InAppNotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(JobSeekerNotificationController.class)
class JobSeekerNotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InAppNotificationService inAppNotificationService;

    @MockitoBean
    private CurrentUserResolver currentUserResolver;

    @MockitoBean
    private JwtService jwtService;

    private Notification sampleNotification() {
        return Notification.builder()
                .id("notification-1")
                .recipientUserId("user-1")
                .notificationType(NotificationType.APPLICATION_SUBMITTED)
                .title("Application submitted")
                .message("Your application to Acme Corp for Backend Engineer has been submitted.")
                .relatedEntityId("application-1")
                .actionUrl("/job-seeker/applications")
                .isRead(false)
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Test
    void getMyNotifications_returnsJobSeekersNotifications() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("user-1");
        when(currentUserResolver.resolveRole(any())).thenReturn("JOB_SEEKER");
        when(inAppNotificationService.listNotifications("user-1", 50)).thenReturn(List.of(sampleNotification()));

        mockMvc.perform(get("/api/job-seeker/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].id").value("notification-1"))
                .andExpect(jsonPath("$.data[0].type").value("APPLICATION_SUBMITTED"))
                .andExpect(jsonPath("$.data[0].actionUrl").value("/job-seeker/applications"))
                .andExpect(jsonPath("$.data[0].read").value(false));
    }

    @Test
    void getMyNotifications_nonJobSeekerRole_returnsForbidden() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("user-1");
        when(currentUserResolver.resolveRole(any())).thenReturn("EMPLOYER");

        mockMvc.perform(get("/api/job-seeker/notifications"))
                .andExpect(status().isForbidden());

        verify(inAppNotificationService, never()).listNotifications(any(), anyInt());
    }

    @Test
    void getUnreadCount_returnsCount() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("user-1");
        when(currentUserResolver.resolveRole(any())).thenReturn("JOB_SEEKER");
        when(inAppNotificationService.countUnread("user-1")).thenReturn(4L);

        mockMvc.perform(get("/api/job-seeker/notifications/unread-count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(4));
    }

    @Test
    void markAsRead_returnsUpdatedNotification() throws Exception {
        Notification readNotification = sampleNotification();
        readNotification.setIsRead(true);
        readNotification.setReadAt(LocalDateTime.now());

        when(currentUserResolver.resolveUserId(any())).thenReturn("user-1");
        when(currentUserResolver.resolveRole(any())).thenReturn("JOB_SEEKER");
        when(inAppNotificationService.markAsRead("user-1", "notification-1")).thenReturn(readNotification);

        mockMvc.perform(patch("/api/job-seeker/notifications/notification-1/read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.read").value(true));
    }

    @Test
    void markAsRead_notFoundForUser_returns404() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("user-1");
        when(currentUserResolver.resolveRole(any())).thenReturn("JOB_SEEKER");
        when(inAppNotificationService.markAsRead("user-1", "notification-1"))
                .thenThrow(new ResourceNotFoundException("Notification not found"));

        mockMvc.perform(patch("/api/job-seeker/notifications/notification-1/read"))
                .andExpect(status().isNotFound());
    }

    @Test
    void markAllAsRead_invokesServiceForAuthenticatedUser() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("user-1");
        when(currentUserResolver.resolveRole(any())).thenReturn("JOB_SEEKER");

        mockMvc.perform(patch("/api/job-seeker/notifications/read-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(inAppNotificationService).markAllAsRead("user-1");
    }
}
