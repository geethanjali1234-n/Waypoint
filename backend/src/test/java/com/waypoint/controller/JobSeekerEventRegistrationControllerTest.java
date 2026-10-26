package com.waypoint.controller;

import com.waypoint.dto.event.JobSeekerEventRegistrationResponse;
import com.waypoint.enums.EventMode;
import com.waypoint.enums.EventType;
import com.waypoint.enums.RegistrationStatus;
import com.waypoint.exception.UnauthorizedException;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.security.JwtService;
import com.waypoint.service.EventService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@WebMvcTest(JobSeekerEventRegistrationController.class)
class JobSeekerEventRegistrationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EventService eventService;

    @MockitoBean
    private CurrentUserResolver currentUserResolver;

    @MockitoBean
    private JwtService jwtService;

    private static final String VALID_TOKEN = "Bearer valid-token";

    private JobSeekerEventRegistrationResponse sampleRegistration() {
        return JobSeekerEventRegistrationResponse.builder()
                .id("reg-1")
                .eventId("event-1")
                .eventTitle("Career Fair")
                .companyName("Waypoint Technologies")
                .eventType(EventType.JOB_FAIR)
                .eventMode(EventMode.OFFLINE)
                .startDateTime(LocalDateTime.of(2026, 12, 1, 9, 0))
                .registrationStatus(RegistrationStatus.REGISTERED)
                .registeredAt(LocalDateTime.of(2026, 1, 1, 9, 0))
                .build();
    }

    @Test
    void getMyRegistrationsReturns200AndExpectedResponse() throws Exception {
        when(currentUserResolver.resolveUserId(org.mockito.ArgumentMatchers.any())).thenReturn("user-1");
        when(currentUserResolver.resolveRole(org.mockito.ArgumentMatchers.any())).thenReturn("JOB_SEEKER");
        when(eventService.getMyRegistrations("user-1")).thenReturn(List.of(sampleRegistration()));

        mockMvc.perform(get("/api/job-seeker/registrations").header("Authorization", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Registered events retrieved successfully"))
                .andExpect(jsonPath("$.data[0].id").value("reg-1"))
                .andExpect(jsonPath("$.data[0].eventTitle").value("Career Fair"));
    }

    @Test
    void getMyRegistrationsReturnsEmptyListWhenNoneExist() throws Exception {
        when(currentUserResolver.resolveUserId(org.mockito.ArgumentMatchers.any())).thenReturn("user-1");
        when(currentUserResolver.resolveRole(org.mockito.ArgumentMatchers.any())).thenReturn("JOB_SEEKER");
        when(eventService.getMyRegistrations("user-1")).thenReturn(List.of());

        mockMvc.perform(get("/api/job-seeker/registrations").header("Authorization", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void getMyRegistrationsReturns403WhenRoleIsNotJobSeeker() throws Exception {
        when(currentUserResolver.resolveUserId(org.mockito.ArgumentMatchers.any())).thenReturn("user-1");
        when(currentUserResolver.resolveRole(org.mockito.ArgumentMatchers.any())).thenReturn("EMPLOYER");

        mockMvc.perform(get("/api/job-seeker/registrations").header("Authorization", VALID_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Access restricted to job seekers"));

        verifyNoInteractions(eventService);
    }

    @Test
    void getMyRegistrationsReturns401WhenTokenIsInvalid() throws Exception {
        when(currentUserResolver.resolveUserId(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new UnauthorizedException("Invalid or expired token"));

        mockMvc.perform(get("/api/job-seeker/registrations").header("Authorization", "Bearer bad-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Invalid or expired token"));

        verifyNoInteractions(eventService);
    }
}
