package com.waypoint.controller;

import com.waypoint.dto.myhr.PayrollReadinessPeriodResponse;
import com.waypoint.dto.myhr.PayrollReadinessResponse;
import com.waypoint.dto.myhr.PayrollReadinessSummaryResponse;
import com.waypoint.exception.BusinessException;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.service.PayrollReadinessService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PayrollReadinessControllerTest {

    @Mock
    private CurrentUserResolver currentUserResolver;
    @Mock
    private PayrollReadinessService payrollReadinessService;
    @Mock
    private HttpServletRequest request;

    private PayrollReadinessController controller;

    @BeforeEach
    void setUp() {
        controller = new PayrollReadinessController(currentUserResolver, payrollReadinessService);
    }

    @Test
    void resolvesPayrollYearAndMonthNumberIntoPayrollMonthDate() {
        when(currentUserResolver.resolveUserId(request)).thenReturn("admin-1");
        PayrollReadinessResponse response = response();
        when(payrollReadinessService.getReadiness(
                eq("admin-1"), eq(LocalDate.of(2026, 9, 1)), eq("Sarah"), eq("READY"), eq("ALL"),
                eq("manager-1"), eq(null), eq("EMPLOYEE_ASC"), eq(0), eq(25)))
                .thenReturn(response);

        var apiResponse = controller.getReadiness(request, null, 2026, 9, "Sarah", "READY", null,
                "ALL", "manager-1", null, "EMPLOYEE_ASC", 0, 25);

        assertThat(apiResponse.isSuccess()).isTrue();
        assertThat(apiResponse.getData()).isSameAs(response);
        verify(payrollReadinessService).getReadiness("admin-1", LocalDate.of(2026, 9, 1), "Sarah", "READY", "ALL",
                "manager-1", null, "EMPLOYEE_ASC", 0, 25);
    }

    @Test
    void rejectsInvalidPayrollMonthNumber() {
        assertThatThrownBy(() -> controller.getReadiness(request, null, 2026, 13, null, null, null,
                "ALL", null, null, "EMPLOYEE_ASC", 0, 25))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("payrollMonthNumber must be between 1 and 12");
    }

    private PayrollReadinessResponse response() {
        return new PayrollReadinessResponse(
                new PayrollReadinessPeriodResponse(LocalDate.of(2026, 9, 1), "September 2026",
                        LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 10), 1),
                new PayrollReadinessSummaryResponse(0, 0, 0, 0, 0, 0, 0, 0, 0,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
                List.of(), 0, 25, 0, 0);
    }
}
