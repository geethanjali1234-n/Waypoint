package com.waypoint.controller;

import com.waypoint.common.ApiResponse;
import com.waypoint.dto.myhr.MyHrEmployeeHoursReportResponse;
import com.waypoint.dto.myhr.MyHrReportSearchResult;
import com.waypoint.dto.myhr.MyHrTimesheetReportResponse;
import com.waypoint.dto.myhr.MyHrWorkforceReportResponse;
import com.waypoint.enums.UserRole;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.security.MyHrAuthenticatedUserService;
import com.waypoint.service.MyHrReportService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/myhr/reports")
@RequiredArgsConstructor
public class MyHrReportController {

    private static final Set<UserRole> REPORT_ROLES = Set.of(UserRole.COMPANY_ADMIN, UserRole.HR);
    private static final int DEFAULT_PERIOD_DAYS = 30;
    private static final int MAX_PERIOD_DAYS = 366;

    private final CurrentUserResolver currentUserResolver;
    private final MyHrAuthenticatedUserService authenticatedUserService;
    private final MyHrReportService myHrReportService;

    @GetMapping("/workforce")
    public ApiResponse<MyHrWorkforceReportResponse> workforceReport(
            HttpServletRequest request,
            @RequestParam(name = "periodDays", required = false) Integer periodDays) {

        String userId = currentUserResolver.resolveUserId(request);
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor =
                authenticatedUserService.require(userId, REPORT_ROLES, false);

        int resolvedPeriodDays = periodDays == null
                ? DEFAULT_PERIOD_DAYS
                : Math.max(1, Math.min(periodDays, MAX_PERIOD_DAYS));

        MyHrWorkforceReportResponse response = myHrReportService.getWorkforceReport(actor, resolvedPeriodDays);
        return new ApiResponse<>(true, "Workforce report", response);
    }

    @GetMapping("/employee-hours")
    public ApiResponse<MyHrEmployeeHoursReportResponse> employeeHoursReport(
            HttpServletRequest request,
            @RequestParam String employeeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        String userId = currentUserResolver.resolveUserId(request);
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor =
                authenticatedUserService.require(userId, REPORT_ROLES, false);

        MyHrEmployeeHoursReportResponse response =
                myHrReportService.getEmployeeHoursReport(actor, employeeId, startDate, endDate);
        return new ApiResponse<>(true, "Employee hours report", response);
    }

    @GetMapping("/employees/search")
    public ApiResponse<List<MyHrReportSearchResult>> searchEmployees(
            HttpServletRequest request,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "managerOnly", defaultValue = "false") boolean managerOnly,
            @RequestParam(name = "limit", defaultValue = "10") int limit) {

        String userId = currentUserResolver.resolveUserId(request);
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor =
                authenticatedUserService.require(userId, REPORT_ROLES, false);

        return new ApiResponse<>(true, "Employees loaded",
                myHrReportService.searchEmployees(actor, query, managerOnly, Math.max(1, Math.min(limit, 25))));
    }

    @GetMapping("/timesheets")
    public ApiResponse<MyHrTimesheetReportResponse> timesheetReport(
            HttpServletRequest request,
            @RequestParam(name = "scope", defaultValue = "ACCESSIBLE") String scope,
            @RequestParam(name = "scopeId", required = false) String scopeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "issue", required = false) String issue,
            @RequestParam(name = "sort", defaultValue = "PERIOD_DESC") String sort,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {

        String userId = currentUserResolver.resolveUserId(request);
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor =
                authenticatedUserService.require(userId, REPORT_ROLES, false);

        MyHrTimesheetReportResponse response = myHrReportService.getTimesheetReport(
                actor,
                scope,
                scopeId,
                startDate,
                endDate,
                search,
                status,
                issue,
                sort,
                Math.max(0, page),
                Math.max(1, Math.min(size, 100)));
        return new ApiResponse<>(true, "Timesheet report", response);
    }
}
