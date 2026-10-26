package com.waypoint.controller;

import com.waypoint.entity.Notification;
import com.waypoint.enums.NotificationType;
import com.waypoint.exception.ResourceNotFoundException;
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

@WebMvcTest(EmployerNotificationController.class)
class EmployerNotificationControllerTest {

    private static final String VALID_TOKEN = "valid-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InAppNotificationService inAppNotificationService;

    @MockitoBean
    private JwtService jwtService;

    private Notification sampleNotification() {
        return Notification.builder()
                .id("notification-1")
                .recipientUserId("employer-user-1")
                .notificationType(NotificationType.NEW_APPLICATION_RECEIVED)
                .title("New application received")
                .message("Jordan Rivera applied for Backend Engineer.")
                .relatedEntityId("application-1")
                .actionUrl("/employer/jobs/job-1/applicants/application-1")
                .isRead(false)
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Test
    void getMyNotifications_validEmployerToken_returnsNotifications() throws Exception {
        when(jwtService.extractUserId(VALID_TOKEN)).thenReturn("employer-user-1");
        when(jwtService.extractRole(VALID_TOKEN)).thenReturn("EMPLOYER");
        when(inAppNotificationService.listNotifications("employer-user-1", 50))
                .thenReturn(List.of(sampleNotification()));

        mockMvc.perform(get("/api/employer/notifications")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].id").value("notification-1"))
                .andExpect(jsonPath("$.data[0].type").value("NEW_APPLICATION_RECEIVED"))
                .andExpect(jsonPath("$.data[0].actionUrl").value("/employer/jobs/job-1/applicants/application-1"));
    }

    @Test
    void getUnreadCount_validEmployerToken_returnsCount() throws Exception {
        when(jwtService.extractUserId(VALID_TOKEN)).thenReturn("employer-user-1");
        when(jwtService.extractRole(VALID_TOKEN)).thenReturn("EMPLOYER");
        when(inAppNotificationService.countUnread("employer-user-1")).thenReturn(3L);

        mockMvc.perform(get("/api/employer/notifications/unread-count")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(3));
    }

    @Test
    void markAsRead_validEmployerToken_returnsUpdatedNotification() throws Exception {
        Notification readNotification = sampleNotification();
        readNotification.setIsRead(true);
        readNotification.setReadAt(LocalDateTime.now());

        when(jwtService.extractUserId(VALID_TOKEN)).thenReturn("employer-user-1");
        when(jwtService.extractRole(VALID_TOKEN)).thenReturn("EMPLOYER");
        when(inAppNotificationService.markAsRead("employer-user-1", "notification-1")).thenReturn(readNotification);

        mockMvc.perform(patch("/api/employer/notifications/notification-1/read")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.read").value(true));
    }

    @Test
    void markAsRead_notFoundForEmployer_returns404() throws Exception {
        when(jwtService.extractUserId(VALID_TOKEN)).thenReturn("employer-user-1");
        when(jwtService.extractRole(VALID_TOKEN)).thenReturn("EMPLOYER");
        when(inAppNotificationService.markAsRead("employer-user-1", "notification-1"))
                .thenThrow(new ResourceNotFoundException("Notification not found"));

        mockMvc.perform(patch("/api/employer/notifications/notification-1/read")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isNotFound());
    }

    @Test
    void markAllAsRead_validEmployerToken_invokesService() throws Exception {
        when(jwtService.extractUserId(VALID_TOKEN)).thenReturn("employer-user-1");
        when(jwtService.extractRole(VALID_TOKEN)).thenReturn("EMPLOYER");

        mockMvc.perform(patch("/api/employer/notifications/read-all")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(inAppNotificationService).markAllAsRead("employer-user-1");
    }

    @Test
    void getMyNotifications_missingAuthorizationHeader_returns401() throws Exception {
        mockMvc.perform(get("/api/employer/notifications"))
                .andExpect(status().isUnauthorized());

        verify(inAppNotificationService, never()).listNotifications(any(), anyInt());
    }

    @Test
    void getMyNotifications_jobSeekerToken_returns403() throws Exception {
        when(jwtService.extractUserId(VALID_TOKEN)).thenReturn("some-user");
        when(jwtService.extractRole(VALID_TOKEN)).thenReturn("JOB_SEEKER");

        mockMvc.perform(get("/api/employer/notifications")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isForbidden());

        verify(inAppNotificationService, never()).listNotifications(any(), anyInt());
    }
}
