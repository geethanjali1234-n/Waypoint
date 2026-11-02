package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrOwnProfileResponse;
import com.waypoint.dto.myhr.ReportingManagerRef;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.security.MyHrAuthenticatedUserService.AuthenticatedMyHrUser;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WavyToolsServiceTest {
    private final EmployeeManagementService employees = mock(EmployeeManagementService.class);
    private final WavyToolsService tools = new WavyToolsService(employees, mock(TimesheetService.class),
            mock(ProfileChangeRequestService.class), mock(PayrollReadinessService.class), new WavyKnowledgeService());
    private final AuthenticatedMyHrUser actor = new AuthenticatedMyHrUser("signed-in-user", "test", "test@example.test", UserRole.EMPLOYEE, "org-1", AccountStatus.ACTIVE, false);
    private final ObjectMapper json = new ObjectMapper();

    @Test void employeesCannotCallRosterOrPayrollToolsEvenIfTheModelRequestsThem() {
        assertThatThrownBy(() -> tools.execute("find_employees", json.readTree("{\"query\":\"Alice\"}"), actor)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> tools.execute("payroll_readiness", json.readTree("{}"), actor)).isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(employees);
        assertThat(tools.definitions(actor)).noneMatch(tool -> List.of("find_employees", "payroll_readiness").contains(tool.get("name")));
    }

    @Test void callerCannotSelectAnotherPersonsProfile() {
        assertThatThrownBy(() -> tools.execute("my_team", json.readTree("{\"userId\":\"someone-else\"}"), actor)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(employees);
    }

    @Test void navigationCannotBypassRolePermissionsOrSupplyArbitraryUrls() {
        assertThatThrownBy(() -> tools.execute("open_workflow", json.readTree("{\"page\":\"employees\"}"), actor)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> tools.execute("open_workflow", json.readTree("{\"page\":\"https://untrusted.test\"}"), actor)).isInstanceOf(IllegalArgumentException.class);
        var destination = (WavyToolsService.NavigationResult) tools.execute("open_workflow", json.readTree("{\"page\":\"documents\"}"), actor);
        assertThat(destination.path()).isEqualTo("/myhr/employee/document-requests");
    }

    @Test void anAdministratorIsNotInventedAsAnAssignedHrRepresentative() {
        var profile = mock(MyHrOwnProfileResponse.class);
        when(profile.reportingManager()).thenReturn(new ReportingManagerRef("admin", "A1", "Alex Morgan", "COMPANY_ADMIN", null, null));
        when(employees.getOwnDetail("signed-in-user")).thenReturn(profile);
        var response = tools.verifiedTeam(actor);
        assertThat(response.text()).contains("Alex Morgan", "won't assume").doesNotContain("HR** role");
        verify(employees).getOwnDetail("signed-in-user");
    }

    @Test void hrIdentityComesFromTheActualAssignmentRole() {
        var profile = mock(MyHrOwnProfileResponse.class);
        when(profile.reportingManager()).thenReturn(new ReportingManagerRef("hr", "HR1", "Riley Chen", "HR", null, null));
        when(employees.getOwnDetail("signed-in-user")).thenReturn(profile);
        assertThat(tools.verifiedTeam(actor).text()).contains("Riley Chen", "HR** role");
    }

    @Test void aMissingAssignmentIsNotGuessed() {
        var profile = mock(MyHrOwnProfileResponse.class);
        when(employees.getOwnDetail("signed-in-user")).thenReturn(profile);
        assertThat(tools.verifiedTeam(actor).text()).contains("couldn't find a named");
    }
}
