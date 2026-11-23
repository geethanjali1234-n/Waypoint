package com.waypoint.service;

import com.waypoint.dto.myhr.EmployeeSummaryResponse;
import com.waypoint.dto.myhr.MyHrChatHistoryMessage;
import com.waypoint.dto.myhr.MyHrChatResponse;
import com.waypoint.dto.myhr.ProfileChangeRequestResponse;
import com.waypoint.enums.ProfileChangeField;
import com.waypoint.enums.ProfileChangeStatus;
import com.waypoint.enums.UserRole;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * People in a reviewer's own scope, and an Employee's own lists, through the real chat service with the synthetic
 * records of {@link WaviEvaluationWorld}. Scope is whatever the services already return for the actor (HR: active
 * direct reports; Company Admin: the organization); names are resolved only against that set, never from a model.
 */
class WaviTeamAndSelfServiceTest {

    private static final class Chat {
        final WaviEvaluationWorld world;
        final List<MyHrChatHistoryMessage> history = new ArrayList<>();
        MyHrChatService.RouteStage lastStage;

        Chat(WaviEvaluationWorld world) {
            this.world = world;
        }

        String ask(String message) {
            AtomicReference<MyHrChatService.RouteStage> stage = new AtomicReference<>();
            world.service.observeRouteStages(stage::set);
            MyHrChatResponse response = world.service.chat(world.actor, message, List.copyOf(history), StampedHistory.SESSION);
            lastStage = stage.get();
            history.add(new MyHrChatHistoryMessage("user", message, response.historyProvenance().user()));
            history.add(new MyHrChatHistoryMessage("assistant", response.message(), response.historyProvenance().assistant()));
            return response.message();
        }
    }

    private static Chat plain(WaviEvaluationWorld.Person person) {
        return new Chat(new WaviEvaluationWorld(person, new WaviEvaluationWorld.Options()));
    }

    private static Chat routed(WaviEvaluationWorld.Person person, String tool, String arguments) {
        WaviEvaluationWorld.Options options = new WaviEvaluationWorld.Options();
        options.semanticRouter = WaviEvaluationWorld.RouterBehaviour.ABSTAIN;
        WaviEvaluationWorld world = new WaviEvaluationWorld(person, options);
        doReturn(WaviEvaluationWorld.routed("{\"decision\":{\"tool\":\"" + tool + "\",\"arguments\":" + arguments
                + "},\"confidence\":\"HIGH\",\"language\":\"english\"}")).when(world.claude)
                .routeJsonWithUsage(anyString(), anyString(), anyMap(), anyInt(), anyString());
        return new Chat(world);
    }

    private static EmployeeSummaryResponse person(String id, String employeeId, String name, String type, String title,
                                                  String manager) {
        return new EmployeeSummaryResponse(id, employeeId, name, type, name.split(" ")[0].toLowerCase() + "@work.example",
                "private@home.example", title, null, null, manager, null);
    }

    // --- Direct-report details ----------------------------------------------------------------------------------

    @Test
    void hrGetsAWorkDetailOfANamedDirectReportInsteadOfAPrivacyRefusal() {
        for (String question : List.of("What is Ravi Teja's job title?", "what's ravi's designation",
                "what is the job title of Ravi Teja")) {
            Chat hr = plain(WaviEvaluationWorld.HR);
            String reply = hr.ask(question);
            assertThat(reply).as(question).contains("Ravi Teja").contains("QA Engineer")
                    .doesNotContain(MyHrChatService.OTHER_PERSON_REPLY, "private@home.example");
        }
        Chat routedHr = routed(WaviEvaluationWorld.HR, "get_team_member", "{\"field\":\"EMPLOYEE_ID\"}");
        // "Employee number" isn't one of the profile forms: the work-detail summary answers, and it includes the ID.
        assertThat(routedHr.ask("employee number for Asha Rao?")).contains("Asha Rao", "WPT-1001");
    }

    @Test
    void aListPositionOrPronounFollowsTheDirectReportsList() {
        Chat hr = plain(WaviEvaluationWorld.HR);
        assertThat(hr.ask("who reports to me?")).contains("Asha Rao", "Ravi Teja");

        assertThat(hr.ask("what is the job title of the second person?")).isEqualTo("Ravi Teja's job title is QA Engineer.");
        assertThat(hr.lastStage).isIn(MyHrChatService.RouteStage.PEOPLE_REFERENCE, MyHrChatService.RouteStage.CONTINUATION);
        assertThat(hr.ask("and their employee id?")).isEqualTo("Ravi Teja's employee ID is WPT-1002.");
        assertThat(hr.ask("what about the first one")).contains("Asha Rao");
    }

    @Test
    void ambiguousNamesAreOfferedForChoiceAndUnknownNamesAreNeverLookedUpElsewhere() {
        Chat hr = plain(WaviEvaluationWorld.HR);
        when(hr.world.employees.list(WaviEvaluationWorld.HR.userId())).thenReturn(List.of(
                person("emp-a1", "WPT-1001", "Asha Rao", "EMPLOYEE", "Senior Data Analyst", "Hari Kumar"),
                person("emp-a3", "WPT-1003", "Asha Menon", "EMPLOYEE", "Designer", "Hari Kumar")));
        String ambiguous = hr.ask("what is Asha's job title?");
        assertThat(ambiguous).startsWith("More than one person matches").contains("Asha Rao", "Asha Menon");

        Chat routedHr = routed(WaviEvaluationWorld.HR, "get_team_member", "{\"field\":\"JOB_TITLE\"}");
        assertThat(routedHr.ask("what is Priya Nair's job title?"))
                .startsWith("I couldn't find that person among the people you can see");
    }

    @Test
    void employeesNeverGetAnotherPersonsDetailsWhateverTheRouterDecides() {
        Chat employee = routed(WaviEvaluationWorld.EMPLOYEE, "get_team_member", "{\"field\":\"JOB_TITLE\"}");
        assertThat(employee.ask("what is Ravi Teja's job title?")).isEqualTo(MyHrChatService.OTHER_PERSON_REPLY);
        verify(employee.world.employees, never()).list(anyString());

        Chat plainEmployee = plain(WaviEvaluationWorld.EMPLOYEE);
        assertThat(plainEmployee.ask("What is Ravi Teja's job title?")).doesNotContain("QA Engineer");
        assertThat(plainEmployee.ask("What is Ravi Teja's employee ID?"))
                .isEqualTo(MyHrChatService.OTHER_PERSON_REPLY);
        verify(plainEmployee.world.employees, never()).list(anyString());
    }

    @Test
    void aCompanyAdminLooksUpPeopleInItsOrganization() {
        Chat admin = routed(WaviEvaluationWorld.ADMIN, "get_team_member", "{\"field\":\"ROLE\"}");
        when(admin.world.employees.list(WaviEvaluationWorld.ADMIN.userId())).thenReturn(List.of(
                person("hr-a1", "WPT-2001", "Hari Kumar", "HR", "HR Business Partner", "Meera Iyer")));
        assertThat(admin.ask("what role does Hari Kumar have?")).isEqualTo("Hari Kumar is an HR user, with the job title HR Business Partner.");
    }

    // --- A reviewer's team timesheets --------------------------------------------------------------------------

    @Test
    void hrSeesANamedReportsTimesheetsInReviewScopeOnly() {
        Chat hr = plain(WaviEvaluationWorld.HR);
        String ravi = hr.ask("show Ravi Teja's timesheets");
        assertThat(ravi).contains("timesheets for Ravi Teja", "submitted", "approved", "40 hours").doesNotContain("Asha Rao");

        Chat rejected = routed(WaviEvaluationWorld.HR, "get_team_timesheets", "{\"status\":\"APPROVED\"}");
        assertThat(rejected.ask("which of my team's timesheets were approved?")).contains("1 approved timesheet")
                .doesNotContain(WaviEvaluationWorld.FORMER_REPORT.name());

        // An Employee naming someone else is refused on privacy before any router or lookup; a reviewer tool chosen
        // by the router for an Employee's unnamed team question is refused by the role gate.
        Chat employee = routed(WaviEvaluationWorld.EMPLOYEE, "get_team_timesheets", "{}");
        assertThat(employee.ask("show Ravi's timesheets")).isEqualTo(MyHrChatService.OTHER_PERSON_REPLY);
        assertThat(employee.ask("pull up the timesheets my team turned in"))
                .isEqualTo(MyHrChatService.TIMESHEET_QUEUE_REVIEWERS_ONLY);
        verify(employee.world.timesheets, never()).listForUser(anyString());
    }

    // --- An Employee's own lists -------------------------------------------------------------------------------

    @Test
    void anEmployeeListsTheirOwnTimesheetsByStatusIncludingWhatNeedsTheirAction() {
        Chat employee = routed(WaviEvaluationWorld.EMPLOYEE, "list_my_timesheets", "{\"status\":\"REJECTED\"}");
        assertThat(employee.ask("show my rejected timesheets")).contains("Your rejected timesheets (1)",
                "Mon Oct 12, 2026 to Sun Oct 18, 2026", "no reviewer comment was recorded");

        Chat action = routed(WaviEvaluationWorld.EMPLOYEE, "list_my_timesheets", "{\"status\":\"NEEDS_ACTION\"}");
        assertThat(action.ask("which timesheets need my action?")).contains("rejected and needs to be corrected");

        Chat awaiting = routed(WaviEvaluationWorld.EMPLOYEE, "list_my_timesheets", "{\"status\":\"SUBMITTED\"}");
        // The Nov 2 week and the Oct 12-14 resubmission: two separate records, never merged.
        assertThat(awaiting.ask("which timesheets are awaiting approval?")).contains("(2)", "Nov 2, 2026", "Oct 14, 2026");
    }

    @Test
    void theRequestsBehindAPendingCountAreListedOnAFollowUp() {
        Chat employee = plain(WaviEvaluationWorld.EMPLOYEE);
        when(employee.world.profileChanges.listMine(WaviEvaluationWorld.EMPLOYEE.userId())).thenReturn(List.of(
                new ProfileChangeRequestResponse("p1", "emp-a1", "prof", "hr-a1", ProfileChangeField.ADDRESS, "a",
                        "b", "moved", ProfileChangeStatus.PENDING, null, null, LocalDateTime.of(2026, 10, 30, 10, 0),
                        null, null, null, "WPT-1001", "Asha Rao", null, "EMPLOYEE")));
        assertThat(employee.ask("is anything pending on my side?")).contains("document request awaiting HR");

        String listed = employee.ask("what are those?");
        assertThat(employee.lastStage).isEqualTo(MyHrChatService.RouteStage.CONTINUATION);
        assertThat(listed).contains("Your open document requests (1)", "Offer letter", "Your open profile change requests (1)",
                "address change: pending");

        Chat completed = routed(WaviEvaluationWorld.EMPLOYEE, "list_my_requests",
                "{\"kind\":\"DOCUMENT_REQUEST\",\"status\":\"COMPLETED\"}");
        assertThat(completed.ask("which of my document requests were completed?"))
                .contains("Your completed document requests (1)", "Employment verification");
    }

    @Test
    void reviewerOwnRequestListsUseOnlyTheirExistingAuthorizedServices() {
        Chat hr = routed(WaviEvaluationWorld.HR, "list_my_requests", "{\"kind\":\"DOCUMENT_REQUEST\"}");
        when(hr.world.documents.list(WaviEvaluationWorld.HR.userId())).thenReturn(List.of());
        assertThat(hr.ask("show my document requests")).contains("You don't have any document requests.");
        verify(hr.world.documents).list(WaviEvaluationWorld.HR.userId());
        verify(hr.world.documents, never()).listOwnHistory(anyString());

        Chat admin = routed(WaviEvaluationWorld.ADMIN, "list_my_requests", "{\"kind\":\"PROFILE_CHANGE\"}");
        assertThat(admin.ask("show my profile change requests"))
                .isEqualTo("Company Admin accounts don't have personal profile change requests.");
        verify(admin.world.profileChanges, never()).listMine(anyString());
    }

    @Test
    void theRouterIsGivenEachNewToolWithItsScopeAndRolesAndNoIdentityArguments() {
        WaviEvaluationWorld.Options options = new WaviEvaluationWorld.Options();
        options.toolLayer = true;
        List<WaviTool> catalog = new WaviEvaluationWorld(WaviEvaluationWorld.HR, options).service.toolLayer().registry().all();
        for (String name : List.of(WaviTools.GET_TEAM_MEMBER, WaviTools.GET_TEAM_TIMESHEETS)) {
            WaviTool tool = catalog.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
            assertThat(tool.allowedRoles()).containsExactlyInAnyOrder(UserRole.HR, UserRole.COMPANY_ADMIN);
        }
        for (WaviTool tool : catalog) {
            assertThat(tool.schema().toJsonSchema().toString()).doesNotContainIgnoringCase("userid")
                    .doesNotContainIgnoringCase("\"name\"");
        }
    }
}
