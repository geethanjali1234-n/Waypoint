package com.waypoint.service;

import com.waypoint.config.PayrollReadinessProperties;
import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.UserRole;
import com.waypoint.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PayrollReadinessAutomaticReminderServiceTest {
    @Mock
    private PayrollReadinessService payrollReadinessService;
    @Mock
    private UserRepository userRepository;

    private PayrollReadinessProperties properties;
    private PayrollReadinessAutomaticReminderService service;

    @BeforeEach
    void setUp() {
        properties = new PayrollReadinessProperties();
        Clock clock = Clock.fixed(Instant.parse("2026-09-07T08:00:00Z"), ZoneId.of("America/Chicago"));
        service = new PayrollReadinessAutomaticReminderService(properties, payrollReadinessService, userRepository, clock);
    }

    @Test
    void schedulerDoesNothingWhenDisabled() {
        properties.setAutomaticRemindersEnabled(false);

        var result = service.runForDate(LocalDate.of(2026, 9, 7));

        assertThat(result.enabled()).isFalse();
        verify(userRepository, never()).findByAccountStatusAndRoleIn(any(), anyCollection());
        verify(payrollReadinessService, never()).runAutomaticForActor(any(), any());
    }

    @Test
    void schedulerDoesNothingOnNonReminderDay() {
        properties.setAutomaticRemindersEnabled(true);

        var result = service.runForDate(LocalDate.of(2026, 9, 6));

        assertThat(result.enabled()).isTrue();
        assertThat(result.actorsProcessed()).isZero();
        verify(userRepository, never()).findByAccountStatusAndRoleIn(any(), anyCollection());
    }

    @Test
    void schedulerRunsForActiveAdminAndHrActorsOnConfiguredDay() {
        properties.setAutomaticRemindersEnabled(true);
        User admin = activeUser("admin-1", UserRole.COMPANY_ADMIN);
        User hr = activeUser("hr-1", UserRole.HR);
        when(userRepository.findByAccountStatusAndRoleIn(eq(AccountStatus.ACTIVE), anyCollection()))
                .thenReturn(List.of(admin, hr));
        when(payrollReadinessService.runAutomaticForActor(any(), any()))
                .thenReturn(new PayrollReadinessService.AutomaticRunResult(2, 1, 1, 1, 0, 0, 0, true));

        var result = service.runForDate(LocalDate.of(2026, 9, 7));

        assertThat(result.actorsProcessed()).isEqualTo(2);
        assertThat(result.employeesEvaluated()).isEqualTo(4);
        assertThat(result.employeeNotificationsSent()).isEqualTo(2);
        verify(payrollReadinessService, times(2)).runAutomaticForActor(any(), any());
    }

    private User activeUser(String id, UserRole role) {
        return User.builder()
                .id(id)
                .loginId(id)
                .email(id + "@example.test")
                .organizationId("org-1")
                .role(role)
                .accountStatus(AccountStatus.ACTIVE)
                .build();
    }
}


