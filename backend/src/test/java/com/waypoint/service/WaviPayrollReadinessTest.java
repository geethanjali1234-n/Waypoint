package com.waypoint.service;

import com.waypoint.dto.myhr.PayrollReadinessPeriodResponse;
import com.waypoint.dto.myhr.PayrollReadinessResponse;
import com.waypoint.dto.myhr.PayrollReadinessSummaryResponse;
import com.waypoint.enums.UserRole;
import com.waypoint.security.TestActors;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class WaviPayrollReadinessTest {
    @Test void reviewerReceivesOnlyTheAuthorizedServiceSummaryAndCutoff() {
        var payroll = mock(PayrollReadinessService.class);
        var result = mock(PayrollReadinessResponse.class);
        var summary = mock(PayrollReadinessSummaryResponse.class);
        var period = mock(PayrollReadinessPeriodResponse.class);
        when(summary.totalEmployees()).thenReturn(9L);
        when(summary.readyForPayroll()).thenReturn(6L);
        when(summary.awaitingApproval()).thenReturn(2L);
        when(period.payrollCutoffDate()).thenReturn(LocalDate.of(2026, 11, 19));
        when(result.summary()).thenReturn(summary);
        when(result.period()).thenReturn(period);
        when(payroll.getReadiness("hr-1", null, null, null, "ALL", null, null, "EMPLOYEE_ASC", 0, 1))
                .thenReturn(result);
        var claude = mock(ClaudeChatClient.class);
        var ollama = mock(OllamaClient.class);
        var service = service(claude, ollama, payroll);
        var actor = TestActors.actor("hr-1", "org-1", UserRole.HR);

        assertThat(service.chat(actor, "Check payroll status", List.of()).message())
                .contains("6 of 9", "2 are awaiting approval");
        assertThat(service.chat(actor, "When is payroll cutoff?", List.of()).message()).contains("2026-11-19");
        verify(payroll, times(2)).getReadiness("hr-1", null, null, null, "ALL", null, null, "EMPLOYEE_ASC", 0, 1);
        verifyNoInteractions(claude, ollama);
    }

    @Test void employeesCannotTriggerAReadinessLookup() {
        var payroll = mock(PayrollReadinessService.class);
        var claude = mock(ClaudeChatClient.class);
        var ollama = mock(OllamaClient.class);
        var service = service(claude, ollama, payroll);
        var reply = service.chat(TestActors.actor("employee-1", "org-1", UserRole.EMPLOYEE),
                "Check payroll status", List.of());
        assertThat(reply.message()).contains("only to HR");
        verifyNoInteractions(payroll, claude, ollama);
    }

    @Test void serviceDenialIsTerminalAndNeverReachesEitherProvider() {
        var payroll = mock(PayrollReadinessService.class);
        when(payroll.getReadiness("hr-1", null, null, null, "ALL", null, null, "EMPLOYEE_ASC", 0, 1))
                .thenThrow(new com.waypoint.exception.ForbiddenException("denied"));
        var claude = mock(ClaudeChatClient.class);
        var ollama = mock(OllamaClient.class);
        var service = service(claude, ollama, payroll);
        var actor = TestActors.actor("hr-1", "org-1", UserRole.HR);
        assertThatThrownBy(() -> service.chat(actor, "Check payroll status", List.of()))
                .isInstanceOf(com.waypoint.exception.ForbiddenException.class);
        verifyNoInteractions(claude, ollama);
    }

    @Test void missingServiceCannotCauseAProviderToGuessPayrollReadiness() {
        var claude = mock(ClaudeChatClient.class);
        var ollama = mock(OllamaClient.class);
        var service = service(claude, ollama, null);
        var reply = service.chat(TestActors.actor("hr-1", "org-1", UserRole.HR), "Check payroll status", List.of());
        assertThat(reply.message()).contains("unavailable");
        verifyNoInteractions(claude, ollama);
    }

    private static MyHrChatService service(ClaudeChatClient claude, OllamaClient ollama, PayrollReadinessService payroll) {
        var service = new MyHrChatService(claude, ollama, mock(TimesheetService.class), mock(ProfileChangeRequestService.class),
                mock(EmployeeManagementService.class), mock(DocumentRequestService.class), "claude", null, false, false);
        service.usePayrollReadinessService(payroll);
        return service;
    }
}
