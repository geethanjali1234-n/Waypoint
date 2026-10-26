package com.waypoint.controller;

import com.waypoint.entity.Notification;
import com.waypoint.enums.NotificationType;
import com.waypoint.enums.UserRole;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.security.JwtService;
import com.waypoint.security.MyHrAuthenticatedUserService;
import com.waypoint.service.InAppNotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MyHrNotificationController.class)
class MyHrNotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InAppNotificationService inAppNotificationService;

    @MockitoBean
    private CurrentUserResolver currentUserResolver;

    @MockitoBean
    private MyHrAuthenticatedUserService authenticatedUserService;

    @MockitoBean
    private JwtService jwtService;

    private Notification sampleNotification() {
        return Notification.builder()
                .id("notification-1")
                .organizationId("org-1")
                .recipientUserId("user-1")
                .notificationType(NotificationType.TIMESHEET_SUBMITTED)
                .title("Timesheet submitted")
                .message("Your timesheet for Sep 1 - Sep 30, 2026 has been submitted.")
                .relatedEntityId("timesheet-1")
                .actionUrl("/myhr/employee/timesheets")
                .isRead(false)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private MyHrAuthenticatedUserService.AuthenticatedMyHrUser sampleUser() {
        return new MyHrAuthenticatedUserService.AuthenticatedMyHrUser(
                "user-1", "emp1", "user-1@test.com", UserRole.EMPLOYEE, "org-1", com.waypoint.enums.AccountStatus.ACTIVE, false);
    }

    @Test
    void getMyNotifications_returnsMyHrNotifications() throws Exception {
        when(jwtService.extractUserId("token")).thenReturn("user-1");
        when(jwtService.extractRole("token")).thenReturn("EMPLOYEE");
        when(currentUserResolver.resolveUserId(any())).thenReturn("user-1");
        when(authenticatedUserService.require(any(), any(), eq(false))).thenReturn(sampleUser());
        when(inAppNotificationService.listNotifications("user-1", 50)).thenReturn(List.of(sampleNotification()));

        mockMvc.perform(get("/api/myhr/notifications").header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].id").value("notification-1"))
                .andExpect(jsonPath("$.data[0].type").value("TIMESHEET_SUBMITTED"))
                .andExpect(jsonPath("$.data[0].actionUrl").value("/myhr/employee/timesheets"))
                .andExpect(jsonPath("$.data[0].read").value(false));
    }

    @Test
    void getUnreadCount_returnsCount() throws Exception {
        when(jwtService.extractUserId("token")).thenReturn("user-1");
        when(jwtService.extractRole("token")).thenReturn("EMPLOYEE");
        when(currentUserResolver.resolveUserId(any())).thenReturn("user-1");
        when(authenticatedUserService.require(any(), any(), eq(false))).thenReturn(sampleUser());
        when(inAppNotificationService.countUnread("user-1")).thenReturn(3L);

        mockMvc.perform(get("/api/myhr/notifications/unread-count").header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(3));
    }

    @Test
    void markAsRead_returnsUpdatedNotification() throws Exception {
        Notification readNotification = sampleNotification();
        readNotification.setIsRead(true);
        readNotification.setReadAt(LocalDateTime.now());

        when(jwtService.extractUserId("token")).thenReturn("user-1");
        when(jwtService.extractRole("token")).thenReturn("EMPLOYEE");
        when(currentUserResolver.resolveUserId(any())).thenReturn("user-1");
        when(authenticatedUserService.require(any(), any(), eq(false))).thenReturn(sampleUser());
        when(inAppNotificationService.markAsRead("user-1", "notification-1")).thenReturn(readNotification);

        mockMvc.perform(patch("/api/myhr/notifications/notification-1/read").header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.read").value(true));
    }

    @Test
    void markAllAsRead_invokesServiceForAuthenticatedUser() throws Exception {
        when(jwtService.extractUserId("token")).thenReturn("user-1");
        when(jwtService.extractRole("token")).thenReturn("EMPLOYEE");
        when(currentUserResolver.resolveUserId(any())).thenReturn("user-1");
        when(authenticatedUserService.require(any(), any(), eq(false))).thenReturn(sampleUser());

        mockMvc.perform(patch("/api/myhr/notifications/read-all").header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(inAppNotificationService).markAllAsRead("user-1");
    }
}
