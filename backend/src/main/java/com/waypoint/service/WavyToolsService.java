package com.waypoint.service;

import com.waypoint.dto.myhr.WavyChatResponse;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.security.MyHrAuthenticatedUserService.AuthenticatedMyHrUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WavyToolsService {
    private final EmployeeManagementService employees;
    private final TimesheetService timesheets;
    private final ProfileChangeRequestService profileChanges;
    private final PayrollReadinessService payroll;
    private final WavyKnowledgeService knowledge;

    public boolean isManager(AuthenticatedMyHrUser actor) {
        return Set.of(UserRole.HR, UserRole.COMPANY_ADMIN).contains(actor.role());
    }
    public String base(AuthenticatedMyHrUser actor) {
        return "/myhr/" + (actor.role() == UserRole.COMPANY_ADMIN ? "admin" : actor.role() == UserRole.HR ? "hr" : "employee");
    }

    public Map<String, Object> myTeam(AuthenticatedMyHrUser actor) {
        var profile = employees.getOwnDetail(actor.userId());
        var manager = profile.reportingManager();
        return Map.of("hasReportingManager", manager != null, "managerName", manager == null ? "" : safe(manager.name()),
                "managerRole", manager == null ? "" : safe(manager.roleLabel()),
                "isAssignedHr", manager != null && "HR".equals(manager.roleLabel()),
                "separateHrAssignmentAvailable", false);
    }

    public WavyChatResponse verifiedTeam(AuthenticatedMyHrUser actor) {
        var team = myTeam(actor);
        String name = (String) team.get("managerName");
        String text;
        if (!(boolean) team.get("hasReportingManager") || name.isBlank()) {
            text = "I couldn't find a named reporting-manager assignment in your profile. Ask your organization administrator to confirm your HR contact.";
        } else if ((boolean) team.get("isAssignedHr")) {
            text = "Your assigned reporting manager is **" + name + "**, who has the **HR** role. You can check the assignment on My Profile.";
        } else {
            text = "Your reporting manager is **" + name + "**. Your profile doesn't provide a separate assigned HR contact, so I won't assume they're your HR representative.";
        }
        return new WavyChatResponse(text, "verified", null,
                List.of(new WavyChatResponse.Action("View My Profile", base(actor) + "/profile")),
                List.of(new WavyChatResponse.Source("my-team", "Your authenticated profile")), true);
    }

    public Object execute(String name, JsonNode input, AuthenticatedMyHrUser actor) {
        if ("open_workflow".equals(name)) {
            if (input == null || !input.isObject() || input.size() != 1 || !input.path("page").isString()) throw new IllegalArgumentException("Invalid navigation input");
            return navigation(input.path("page").asString(), actor);
        }
        boolean searchTool = Set.of("find_employees", "search_waypoint_help").contains(name);
        if (input == null || !input.isObject() || input.size() != (searchTool ? 1 : 0)) throw new IllegalArgumentException("Invalid tool input");
        String query = searchTool ? input.path("query").asString("").trim() : "";
        if (searchTool && (query.length() < 2 || query.length() > 120 || !input.path("query").isString())) throw new IllegalArgumentException("Invalid search query");
        return switch (name) {
            case "my_team" -> myTeam(actor);
            case "find_employees" -> {
                requireManager(actor);
                String term = query.toLowerCase(Locale.ROOT);
                yield employees.list(actor.userId()).stream().filter(person ->
                        (safe(person.name()) + " " + safe(person.employeeId()) + " " + safe(person.designation())).toLowerCase(Locale.ROOT).contains(term))
                        .limit(5).map(person -> Map.of("name", safe(person.name()), "employeeId", safe(person.employeeId()), "designation", safe(person.designation()))).toList();
            }
            case "timesheet_summary" -> Map.of("scope", actor.role() == UserRole.EMPLOYEE ? "your records" : "records visible to your account",
                    "countsByStatus", timesheets.listForUser(actor.userId()).stream()
                            .collect(Collectors.groupingBy(record -> record.status().name(), Collectors.counting())));
            case "profile_request_summary" -> {
                var requests = isManager(actor) ? profileChanges.listForAdmin(actor.userId()) : profileChanges.listMine(actor.userId());
                yield Map.of("scope", isManager(actor) ? "authorized review queue" : "your requests", "countsByStatus", requests.stream()
                        .collect(Collectors.groupingBy(record -> record.status().name(), Collectors.counting())));
            }
            case "payroll_readiness" -> {
                requireManager(actor);
                var result = payroll.getReadiness(actor.userId(), null, null, null, "ALL", null, null, "EMPLOYEE_ASC", 0, 1);
                yield Map.of("period", result.period(), "summary", result.summary());
            }
            case "search_waypoint_help" -> knowledge.search(query);
            default -> throw new IllegalArgumentException("Unsupported tool");
        };
    }

    public List<Map<String, Object>> definitions(AuthenticatedMyHrUser actor) {
        var tools = new java.util.ArrayList<Map<String, Object>>();
        tools.add(Map.of("name", "open_workflow", "description", "Offer a navigation button for a permitted Waypoint page. This opens a page only and never performs an HR action.",
                "input_schema", Map.of("type", "object", "additionalProperties", false, "required", List.of("page"), "properties", Map.of("page", Map.of("type", "string", "enum",
                        isManager(actor) ? List.of("profile", "timesheets", "documents", "profile-requests", "employees", "payroll") : List.of("profile", "timesheets", "documents", "profile-requests"))))));
        tools.add(definition("my_team", "Check the signed-in user's actual reporting-manager assignment and role. Use for who is my HR/manager. Never assume every manager is HR.", false));
        tools.add(definition("timesheet_summary", "Read current timesheet status counts scoped by the server to this account. No salary or payroll payment amounts.", false));
        tools.add(definition("profile_request_summary", "Read profile request status counts for your own requests or the authorized HR/admin review queue.", false));
        tools.add(definition("search_waypoint_help", "Retrieve maintained Waypoint product workflow guidance. This is not organization-specific HR policy.", true));
        if (isManager(actor)) {
            tools.add(definition("find_employees", "Find employees by name, employee ID or designation within the server-authorized roster. Ask for a search term first.", true));
            tools.add(definition("payroll_readiness", "Read the current authorized payroll summary and actual cutoff date. Never infer payment amounts or dates.", false));
        }
        return List.copyOf(tools);
    }

    public WavyChatResponse.Action action(String tool, AuthenticatedMyHrUser actor) {
        String base = base(actor);
        return switch (tool) {
            case "my_team" -> new WavyChatResponse.Action("My Profile", base + "/profile");
            case "find_employees" -> new WavyChatResponse.Action("Employee Management", base + "/employees");
            case "timesheet_summary" -> new WavyChatResponse.Action("Timesheets", base + "/timesheets");
            case "profile_request_summary" -> new WavyChatResponse.Action("Profile Requests", base + "/profile-change-requests");
            case "payroll_readiness" -> new WavyChatResponse.Action("Payroll Readiness", base + "/timesheets?view=payroll");
            default -> null;
        };
    }

    private Map<String, Object> definition(String name, String description, boolean query) {
        return Map.of("name", name, "description", description, "input_schema", Map.of("type", "object", "additionalProperties", false,
                "properties", query ? Map.of("query", Map.of("type", "string", "minLength", 2, "maxLength", 120)) : Map.of(),
                "required", query ? List.of("query") : List.of()));
    }
    public record NavigationResult(String label, String path) { }
    private NavigationResult navigation(String page, AuthenticatedMyHrUser actor) {
        if (Set.of("employees", "payroll").contains(page)) requireManager(actor);
        return switch (page) {
            case "profile" -> new NavigationResult("My Profile", base(actor) + "/profile");
            case "timesheets" -> new NavigationResult("Timesheets", base(actor) + "/timesheets");
            case "documents" -> new NavigationResult("Document Requests", base(actor) + "/document-requests");
            case "profile-requests" -> new NavigationResult("Profile Requests", base(actor) + "/profile-change-requests");
            case "employees" -> new NavigationResult("Employee Management", base(actor) + "/employees");
            case "payroll" -> new NavigationResult("Payroll Readiness", base(actor) + "/timesheets?view=payroll");
            default -> throw new IllegalArgumentException("Unsupported page");
        };
    }
    private void requireManager(AuthenticatedMyHrUser actor) {
        if (!isManager(actor)) throw new ForbiddenException("This tool is not available for your role");
    }
    private static String safe(String value) { return value == null ? "" : value; }
}
