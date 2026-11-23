package com.waypoint.service;

import com.waypoint.enums.UserRole;
import java.util.List;

/** Executable catalog metadata, shared by router context, capability introductions and the internal audit matrix.
 * Roles and parameters remain on WaviTool: duplicating permission rules here would create a second authority.
 * Backend bindings are checked against actual service methods by the capability-contract tests.
 * Legacy directory/reviewer lists still use full scoped service results: response truncation is not database
 * pagination. The dedicated daily-hours projection is bounded at query time. Do not present either as an
 * unrestricted database capability or a guarantee that arbitrarily large tenant lists fit in one response.
 */
record WaviCapabilityContract(List<Binding> backends, String result, String context, String limitation, String introduction) {
    record Binding(Class<?> service, String method) {}
    WaviCapabilityContract { backends = List.copyOf(backends); }
    private static Binding bind(Class<?> service, String method) { return new Binding(service, method); }

    static WaviCapabilityContract forTool(WaviTool tool, UserRole role) {
        String own = "Own record only; re-read through the verified actor; never infer values from conversation text.";
        String scoped = "Re-resolve referenced people against current active-report/organization scope; never use history as permission.";
        String noData = "No employee record lookup; do not imply a record was read or changed.";
        return switch (tool.name()) {
            case WaviTools.GET_MY_ROLE, WaviTools.GET_MY_PROFILE_FIELD -> contract("Recorded profile/work identity", "Selected profile field", own, null,
                    bind(EmployeeManagementService.class, "getOwnDetail"));
            case WaviTools.GET_MY_TIMESHEETS -> contract("Period status, submission date, period hours or daily entries as requested",
                    "Resolved date/period and requested detail; PERIOD returns dates, DAILY_HOURS reads each day", own
                            + " Missing entries are not zero; overlapping submissions are not payable totals.",
                    role == UserRole.COMPANY_ADMIN ? null : "Check your latest timesheet status, latest submission date, period statuses and recorded hours.",
                    bind(TimesheetService.class, "getMostRecentStatusForUser"), bind(TimesheetService.class, "getLatestSubmissionForUser"),
                    bind(TimesheetService.class, "getOwnStatusesOverlapping"), bind(TimesheetService.class, "getOwnDailyHours"),
                    bind(TimesheetService.class, "listForUser"));
            case WaviTools.GET_DIRECT_REPORTS -> contract("Active direct-report list", "List order is not permanent identity", scoped,
                    "List your active direct reports.", bind(EmployeeManagementService.class, "list"));
            case WaviTools.GET_TEAM_MEMBER -> contract("Permitted work fields of one currently authorized person", "Selected person and requested field", scoped,
                    role == UserRole.HR ? "Look up permitted work details for an active direct report."
                            : "Look up permitted work details for people in your organization.", bind(EmployeeManagementService.class, "list"));
            case WaviTools.GET_PEOPLE_SUMMARY -> contract("People counts, never submission counts", "Authorized population", scoped,
                    "Summarize the people within your authorized scope.", bind(EmployeeManagementService.class, "list"));
            case WaviTools.GET_REVIEW_QUEUE -> contract("COUNT or LIST of currently assigned review items", "Queue and count/list detail", scoped,
                    "Count or list items assigned for your review.", bind(TimesheetService.class, "queueSummary"),
                    bind(ProfileChangeRequestService.class, "queueSummary"), bind(DocumentRequestService.class, "queueSummary"),
                    bind(TimesheetService.class, "listForUser"), bind(ProfileChangeRequestService.class, "listForAdmin"),
                    bind(DocumentRequestService.class, "list"), bind(EmployeeManagementService.class, "list"));
            case WaviTools.GET_MY_REVIEW_HISTORY -> contract("Available recorded decisions made by the actor", "Review type and decision filter", scoped
                    + " Current status alone is not evidence of who reviewed a record.", "Show available decisions you made as a reviewer.",
                    bind(TimesheetService.class, "listForUser"), bind(ProfileChangeRequestService.class, "listForAdmin"));
            case WaviTools.GET_TEAM_TIMESHEETS -> contract("Scoped timesheet list", "Selected person/status", scoped, null,
                    bind(TimesheetService.class, "listForUser"), bind(EmployeeManagementService.class, "list"));
            case WaviTools.LIST_MY_TIMESHEETS -> contract("Own filtered list; not a single latest value", "Status filter", own, null,
                    bind(TimesheetService.class, "listForUser"));
            case WaviTools.GET_MY_REQUESTS, WaviTools.LIST_MY_REQUESTS -> contract("Own request status/dates or filtered list", "Request kind, field, status and aspect", own, null,
                    bind(ProfileChangeRequestService.class, "mostRecentOwnStatus"), bind(DocumentRequestService.class, "listOwnHistory"));
            case WaviTools.GET_MY_PENDING_WORK -> contract("Own action items or assigned reviewer work, according to verified role", "Work scope and item kind", scoped, null,
                    bind(TimesheetService.class, "getMostRecentStatusForUser"), bind(TimesheetService.class, "queueSummary"),
                    bind(ProfileChangeRequestService.class, "mostRecentOwnStatus"), bind(ProfileChangeRequestService.class, "queueSummary"),
                    bind(DocumentRequestService.class, "listOwnHistory"), bind(DocumentRequestService.class, "queueSummary"));
            case WaviTools.GET_PAYROLL_SCHEDULE -> contract("Recorded payroll frequency; not an invented pay date", "Organization settings resolved from actor", own, null,
                    bind(TimesheetService.class, "getPayrollPeriodForOrganization"));
            case WaviTools.POLICY_QUESTION -> contract("Cited approved policy answer", "Policy question; never a personal status lookup",
                    "Policy sources cannot establish employee record facts.", null, bind(KnowledgeRetrievalService.class, "retrieve"));
            case WaviTools.GENERAL_HELP -> contract("General workplace guidance", "No record context", noData, null,
                    bind(ClaudeChatClient.class, "chat"), bind(OllamaClient.class, "chat"));
            case WaviTools.WORKFLOW_HELP, WaviTools.OPEN_PAGE, WaviTools.EXPLAIN_TERM -> contract("Verified-role workflow/navigation or application explanation",
                    "Workflow topic or page", noData + " Workflow actions remain in the Waypoint UI.", null);
            case WaviTools.CLARIFY -> contract("Question for a missing intent slot", "Preserve topic/aspect and ask only for missing scope/record/date", noData, null);
            case WaviTools.UNSUPPORTED_FIELD, WaviTools.UNSUPPORTED_REQUEST, WaviTools.OUT_OF_SCOPE -> contract("Explicit limitation or scope response",
                    "Unsupported operation, not a provider error", noData + " Never claim missing permission merely because routing failed.", null);
            default -> throw new IllegalArgumentException("Missing capability contract: " + tool.name());
        };
    }

    private static WaviCapabilityContract contract(String result, String context, String limitation, String introduction, Binding... bindings) {
        return new WaviCapabilityContract(List.of(bindings), result, context, limitation, introduction);
    }
}
