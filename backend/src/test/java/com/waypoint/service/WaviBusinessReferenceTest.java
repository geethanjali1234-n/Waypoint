package com.waypoint.service;

import com.waypoint.dto.myhr.EmployeeSummaryResponse;
import com.waypoint.dto.myhr.MyHrChatHistoryMessage;
import com.waypoint.dto.myhr.MyHrChatResponse;
import com.waypoint.dto.myhr.MyHrLatestTimesheetSubmission;
import com.waypoint.dto.myhr.MyHrRecentTimesheetStatus;
import com.waypoint.dto.myhr.MyHrTimesheetPeriodStatus;
import com.waypoint.dto.myhr.TimesheetResponse;
import com.waypoint.enums.TimesheetStatus;
import com.waypoint.enums.UserRole;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Real-application failure classes, reproduced as complete multi-turn conversations through the real chat service:
 * which records a business question selects (person, period, status, submission), what the answer says about them,
 * and what a follow-up ("he", "it", "this employee", "all", "timesheets", another name) refers to. Records are
 * synthetic but shaped like production: every submission or resubmission is its own record, so records can share
 * or overlap a period. Today is Thu Nov 5, 2026, so "last week" is Mon Oct 26 to Sun Nov 1.
 */
class WaviBusinessReferenceTest {

    private static final WaviEvaluationWorld.Person HR = WaviEvaluationWorld.HR;
    private static final WaviEvaluationWorld.Person ADMIN = WaviEvaluationWorld.ADMIN;
    private static final WaviEvaluationWorld.Person ASHA = WaviEvaluationWorld.EMPLOYEE;
    private static final WaviEvaluationWorld.Person RAVI = WaviEvaluationWorld.COLLEAGUE;

    private static final class Chat {
        final WaviEvaluationWorld world;
        final List<MyHrChatHistoryMessage> history = new ArrayList<>();

        Chat(WaviEvaluationWorld world) {
            this.world = world;
        }

        String ask(String message) {
            MyHrChatResponse response = world.service.chat(world.actor, message, List.copyOf(history), StampedHistory.SESSION);
            history.add(new MyHrChatHistoryMessage("user", message, response.historyProvenance().user()));
            history.add(new MyHrChatHistoryMessage("assistant", response.message(), response.historyProvenance().assistant()));
            return response.message();
        }
    }

    private static TimesheetResponse sheet(WaviEvaluationWorld.Person owner, WaviEvaluationWorld.Person reviewer,
                                           String from, String to, TimesheetStatus status, String submittedAt) {
        return WaviEvaluationWorld.timesheet(owner, reviewer, new MyHrTimesheetPeriodStatus(LocalDate.parse(from),
                LocalDate.parse(to), status, submittedAt == null ? null : LocalDateTime.parse(submittedAt)));
    }

    /**
     * Ravi: six weekly submissions plus a corrected resubmission of last week's first three days (the original was
     * rejected). Asha: last week approved, this week waiting. A former report, out of HR's scope, also names HR.
     */
    private static List<TimesheetResponse> teamRecords(WaviEvaluationWorld.Person reviewer) {
        List<TimesheetResponse> records = new ArrayList<>(List.of(
                sheet(RAVI, reviewer, "2026-09-28", "2026-10-04", TimesheetStatus.APPROVED, "2026-10-05T09:00"),
                sheet(RAVI, reviewer, "2026-10-05", "2026-10-11", TimesheetStatus.APPROVED, "2026-10-12T09:00"),
                sheet(RAVI, reviewer, "2026-10-12", "2026-10-18", TimesheetStatus.APPROVED, "2026-10-19T09:00"),
                sheet(RAVI, reviewer, "2026-10-19", "2026-10-25", TimesheetStatus.APPROVED, "2026-10-26T09:00"),
                sheet(RAVI, reviewer, "2026-10-26", "2026-11-01", TimesheetStatus.REJECTED, "2026-11-02T09:15"),
                sheet(RAVI, reviewer, "2026-10-26", "2026-10-28", TimesheetStatus.SUBMITTED, "2026-11-03T16:45"),
                sheet(RAVI, reviewer, "2026-11-02", "2026-11-08", TimesheetStatus.SUBMITTED, "2026-11-04T11:00"),
                sheet(ASHA, reviewer, "2026-10-26", "2026-11-01", TimesheetStatus.APPROVED, "2026-11-02T10:00"),
                sheet(ASHA, reviewer, "2026-11-02", "2026-11-08", TimesheetStatus.SUBMITTED, "2026-11-04T12:30")));
        if (reviewer == HR) {
            records.add(sheet(WaviEvaluationWorld.FORMER_REPORT, HR, "2026-10-26", "2026-11-01", TimesheetStatus.SUBMITTED,
                    "2026-11-02T08:00"));
        }
        return records;
    }

    private static Chat reviewer(WaviEvaluationWorld.Person person) {
        WaviEvaluationWorld world = new WaviEvaluationWorld(person, new WaviEvaluationWorld.Options());
        when(world.timesheets.listForUser(person.userId())).thenReturn(teamRecords(person));
        return new Chat(world);
    }

    // --- One employee's hours for one period -------------------------------------------------------------------

    @Test
    void hoursForOneEmployeeAndWeekSelectOnlyMatchingSubmissionsAndNeverAddOverlappingDays() {
        for (String question : List.of("How many hours did Ravi Teja log last week?",
                "Ravi Teja's total hours for last week", "how much time did Ravi Teja record last week in hours")) {
            String reply = reviewer(HR).ask(question);
            assertThat(reply).as(question).contains("Hours recorded for Ravi Teja covering last week",
                    "Mon Oct 26, 2026 to Sun Nov 1, 2026", "40 hours on 5 days",
                    "Mon Oct 26, 2026 to Wed Oct 28, 2026", "24 hours on 3 days",
                    "rejected, returned to the employee to correct and resubmit", "submitted, awaiting review",
                    "haven't added them together");
            // Only last week's two submissions: none of Ravi's other weeks, nobody else.
            assertThat(reply).as(question).doesNotContain("Sep 28", "Oct 19, 2026 to", "Nov 2, 2026 to", "Asha",
                    "Former Report", "Total across");
        }
    }

    @Test
    void hoursForOneEmployeeWithoutOverlapGiveTheTotalOnce() {
        String reply = reviewer(HR).ask("how many hours did Asha Rao work last week?");
        assertThat(reply).contains("Hours recorded for Asha Rao covering last week", "approved", "40 hours on 5 days")
                .doesNotContain("Ravi", "haven't added");
    }

    // --- Person and period carried by follow-ups ---------------------------------------------------------------

    @Test
    void aPronounKeepsThePersonAndPeriodAndANewNameKeepsTheQuestion() {
        Chat hr = reviewer(HR);
        String listed = hr.ask("show Ravi Teja's timesheets for last week");
        assertThat(listed).contains("2 timesheets for Ravi Teja covering last week", "submitted Mon Nov 2, 2026 at 09:15",
                "submitted Tue Nov 3, 2026 at 16:45").doesNotContain("Sep 28", "Asha");

        String hours = hr.ask("How many hours did he submit?");
        assertThat(hours).contains("Hours recorded for Ravi Teja covering last week", "40 hours on 5 days",
                "24 hours on 3 days").doesNotContain("Asha", "Sep 28");

        // A new name with no question of its own keeps the previous question (hours, last week).
        String other = hr.ask("what about Asha Rao?");
        assertThat(other).contains("Hours recorded for Asha Rao covering last week", "40 hours on 5 days")
                .doesNotContain("Ravi");
    }

    @Test
    void aRouterAnsweredPersonQuestionIsRememberedForTheNextPronoun() {
        WaviEvaluationWorld.Options options = new WaviEvaluationWorld.Options();
        options.semanticRouter = WaviEvaluationWorld.RouterBehaviour.ABSTAIN;
        WaviEvaluationWorld world = new WaviEvaluationWorld(HR, options);
        when(world.timesheets.listForUser(HR.userId())).thenReturn(teamRecords(HR));
        doReturn(WaviEvaluationWorld.routed("{\"decision\":{\"tool\":\"get_team_timesheets\",\"arguments\":"
                + "{\"period\":\"WEEK\",\"relative\":\"LAST\",\"detail\":\"LIST\"}},\"confidence\":\"HIGH\","
                + "\"language\":\"english\"}")).when(world.claude)
                .routeJsonWithUsage(anyString(), anyString(), anyMap(), anyInt(), anyString());
        Chat hr = new Chat(world);
        assertThat(hr.ask("pull up what Ravi Teja turned in for last week")).contains("Ravi Teja", "covering last week");
        assertThat(hr.ask("how many hours did he put in?")).contains("Hours recorded for Ravi Teja covering last week");
    }

    // --- Distinct submissions and who has to act -------------------------------------------------------------

    @Test
    void reviewQueueRowsForSamePeriodSubmissionsStayDistinguishable() {
        WaviEvaluationWorld world = new WaviEvaluationWorld(ADMIN, new WaviEvaluationWorld.Options());
        when(world.timesheets.listForUser(ADMIN.userId())).thenReturn(List.of(
                sheet(RAVI, HR, "2026-11-02", "2026-11-08", TimesheetStatus.SUBMITTED, "2026-11-04T09:00"),
                sheet(RAVI, HR, "2026-11-02", "2026-11-08", TimesheetStatus.SUBMITTED, "2026-11-04T15:30"),
                sheet(ASHA, HR, "2026-11-02", "2026-11-08", TimesheetStatus.SUBMITTED, "2026-11-04T12:00"),
                sheet(ASHA, HR, "2026-11-02", "2026-11-08", TimesheetStatus.SUBMITTED, "2026-11-04T12:00"),
                sheet(ASHA, HR, "2026-10-26", "2026-11-01", TimesheetStatus.REJECTED, "2026-11-02T10:00")));
        String reply = new Chat(world).ask("is there any pending work for timesheets?");
        assertThat(reply).contains("4 timesheets from 2 people are waiting for review in your organization",
                "submitted Wed Nov 4, 2026 at 09:00", "submitted Wed Nov 4, 2026 at 15:30",
                "[separate record 1 of 2 with these details]", "[separate record 2 of 2 with these details]");
        // Only reviewer work: the rejected one waits for the employee; no other kind of work is mixed in.
        assertThat(reply).doesNotContain("Oct 26", "profile change", "document request");
    }

    @Test
    void rejectedTimesheetsAreDescribedAsWaitingForTheEmployeeNotAsReviewerActionNeeded() {
        String rejected = reviewer(ADMIN).ask("show Ravi Teja's rejected timesheets");
        assertThat(rejected).contains("rejected (waiting for the employee to correct and resubmit)", "Ravi Teja")
                .doesNotContain("action-needed", "approved,");

        WaviEvaluationWorld.Options options = new WaviEvaluationWorld.Options();
        options.semanticRouter = WaviEvaluationWorld.RouterBehaviour.ABSTAIN;
        WaviEvaluationWorld world = new WaviEvaluationWorld(ADMIN, options);
        when(world.timesheets.listForUser(ADMIN.userId())).thenReturn(teamRecords(ADMIN));
        doReturn(WaviEvaluationWorld.routed("{\"decision\":{\"tool\":\"get_team_timesheets\",\"arguments\":"
                + "{\"status\":\"NEEDS_ACTION\"}},\"confidence\":\"HIGH\",\"language\":\"english\"}")).when(world.claude)
                .routeJsonWithUsage(anyString(), anyString(), anyMap(), anyInt(), anyString());
        String needsAction = new Chat(world).ask("which team timesheets still need something done");
        assertThat(needsAction).contains("1 rejected (waiting for the employee to correct and resubmit) timesheet")
                .doesNotContain("action-needed", "awaiting review");
    }

    // --- Pending work narrowed by kind and person, and clarification completion --------------------------------

    @Test
    void pendingWorkForOneKindListsOnlyThatKind() {
        String reply = reviewer(ADMIN).ask("pending work for timesheets");
        assertThat(reply).contains("timesheets from 2 people are waiting for review in your organization", "Ravi Teja",
                "Asha Rao").doesNotContain("profile change", "document request", "Waiting for you (");
    }

    @Test
    void pendingWorkForAReferencedEmployeeIsNarrowedByEachShortReplyAndKeptForTheNextName() {
        Chat admin = reviewer(ADMIN);
        assertThat(admin.ask("what is Ravi Teja's job title?")).contains("Ravi Teja", "QA Engineer");

        String all = admin.ask("Is there pending work for this employee?");
        assertThat(all).contains("Work waiting for your review from Ravi Teja", "Ravi Teja - Mon Oct 26, 2026 to Wed Oct 28, 2026",
                "Ravi Teja - Mon Nov 2, 2026 to Sun Nov 8, 2026", "profile change request from Ravi Teja",
                "Rejected timesheets are not listed").doesNotContain("Asha");

        String timesheets = admin.ask("timesheets");
        assertThat(timesheets).contains("2 timesheets from Ravi Teja are waiting for review")
                .doesNotContain("profile change", "Asha", "Mon Oct 26, 2026 to Sun Nov 1");

        String everything = admin.ask("all");
        assertThat(everything).contains("Work waiting for your review from Ravi Teja", "profile change request");

        String next = admin.ask("and Asha Rao?");
        assertThat(next).contains("Work waiting for your review from Asha Rao").doesNotContain("Ravi");
    }

    @Test
    void anUnresolvedPersonReferenceAsksWhoAndTheNameAnswersTheOriginalQuestion() {
        Chat admin = reviewer(ADMIN);
        assertThat(admin.ask("is there pending work for this employee?"))
                .startsWith("Which person in your authorized scope do you mean?");
        assertThat(admin.ask("Ravi Teja")).contains("Work waiting for your review from Ravi Teja");
    }

    // --- Latest record versus the record just referenced --------------------------------------------------------

    @Test
    void whenDidISubmitItRefersToTheTimesheetJustShownNotTheLatestSubmissionOfAnotherPeriod() {
        WaviEvaluationWorld world = new WaviEvaluationWorld(ASHA, new WaviEvaluationWorld.Options());
        TimesheetResponse latestPeriod = sheet(ASHA, HR, "2026-11-02", "2026-11-08", TimesheetStatus.SUBMITTED,
                "2026-11-04T10:00");
        TimesheetResponse correction = sheet(ASHA, HR, "2026-10-12", "2026-10-14", TimesheetStatus.SUBMITTED,
                "2026-11-05T09:00");
        when(world.timesheets.listForUser(ASHA.userId())).thenReturn(List.of(latestPeriod, correction,
                sheet(ASHA, HR, "2026-10-26", "2026-11-01", TimesheetStatus.APPROVED, "2026-11-02T08:00")));
        when(world.timesheets.getMostRecentStatusForUser(ASHA.userId())).thenReturn(Optional.of(
                new MyHrRecentTimesheetStatus(LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-08"),
                        TimesheetStatus.SUBMITTED)));
        when(world.timesheets.getLatestSubmissionForUser(ASHA.userId())).thenReturn(Optional.of(
                new MyHrLatestTimesheetSubmission(LocalDateTime.parse("2026-11-05T09:00"), LocalDate.parse("2026-10-12"),
                        LocalDate.parse("2026-10-14"), TimesheetStatus.SUBMITTED)));
        Chat employee = new Chat(world);

        assertThat(employee.ask("what's the status of my most recent timesheet?")).contains("2026-11-02 to 2026-11-08");
        String referenced = employee.ask("when did I submit it?");
        assertThat(referenced).startsWith("That timesheet (Mon Nov 2, 2026 to Sun Nov 8, 2026) was submitted Wed Nov 4, 2026 at 10:00.")
                .contains("most recent submission overall was a different timesheet (Mon Oct 12, 2026 to Wed Oct 14, 2026)",
                        "Thu Nov 5, 2026 at 09:00");

        // Asked on its own, the latest SUBMISSION is a different question with a different record.
        assertThat(new Chat(world).ask("when did I last submit a timesheet?")).contains("2026-11-05", "2026-10-12");
    }

    // --- Security ----------------------------------------------------------------------------------------------

    @Test
    void anEmployeesPronounNeverReachesAnyoneElsesRecords() {
        WaviEvaluationWorld world = new WaviEvaluationWorld(ASHA, new WaviEvaluationWorld.Options());
        Chat employee = new Chat(world);
        employee.ask("what is my employee id?");
        String reply = employee.ask("how many hours did he submit last week?");
        assertThat(reply).doesNotContain("Ravi", "Hours recorded for Ravi");
        verify(world.employees, never()).list(anyString());
    }

    @Test
    void aStoredPersonIsPinnedSoAChangedScopeNeverAnswersAboutWhoeverNowHoldsThatPosition() {
        Chat hr = reviewer(HR);
        assertThat(hr.ask("what is Ravi Teja's job title?")).contains("Ravi Teja");
        // Ravi leaves HR's scope; someone else now holds his position in the list.
        when(hr.world.employees.list(HR.userId())).thenReturn(List.of(
                new EmployeeSummaryResponse("emp-new", "WPT-1100", "Neha Varma", "EMPLOYEE", "n@work.example", null,
                        "Analyst", null, null, "Hari Kumar", null),
                new EmployeeSummaryResponse(ASHA.userId(), ASHA.employeeId(), ASHA.name(), "EMPLOYEE", "a@work.example",
                        null, ASHA.jobTitle(), null, null, "Hari Kumar", null)));
        String reply = hr.ask("how many hours did he log last week?");
        assertThat(reply).contains("no longer among the people you can see").doesNotContain("Asha", "Neha", "40 hours");
    }

    @Test
    void outOfScopePeopleAreNeverSelectedEvenWhenTheirRecordsNameTheReviewer() {
        String reply = reviewer(HR).ask("how many hours did Former Report log last week?");
        assertThat(reply).doesNotContain("Hours recorded for Former Report", "Former Report - ");
    }

    @Test
    void theServerOnlyPersonReferenceIsNeverShownToARouter() {
        WaviDialogueStore.State previous = new WaviDialogueStore.State("t1", WaviTools.GET_TEAM_TIMESHEETS,
                Map.of("status", "ANY", "ordinal", 2, MyHrChatService.PERSON_REFERENCE, "0123456789abcdef01234567"),
                Instant.parse("2026-11-05T14:00:00Z"));
        WaviRouterPrompt.Request request = WaviRouterPrompt.build(List.of(), UserRole.HR, LocalDate.parse("2026-11-05"),
                previous, "how many hours did he submit?");
        assertThat(request.user()).contains("ordinal").doesNotContain(MyHrChatService.PERSON_REFERENCE,
                "0123456789abcdef01234567");
    }

    @Test
    void typedTeamPeriodArgumentsAreValidatedLikeTheUsersOwnPeriods() {
        WaviEvaluationWorld.Options options = new WaviEvaluationWorld.Options();
        options.toolLayer = true;
        WaviToolRegistry registry = new WaviEvaluationWorld(HR, options).service.toolLayer().registry();
        WaviToolLayer layer = new WaviToolLayer(registry);
        LocalDate today = LocalDate.parse("2026-11-05");
        assertThat(layer.dryRun(decision(Map.of("period", "WEEK", "relative", "LAST", "detail", "HOURS")), UserRole.HR, today))
                .isEqualTo("allowed");
        assertThat(layer.dryRun(decision(Map.of("period", "LATEST")), UserRole.HR, today)).startsWith("rejected:");
        assertThat(layer.dryRun(decision(Map.of("relative", "LAST")), UserRole.HR, today)).startsWith("rejected:");
        assertThat(layer.dryRun(decision(Map.of("period", "WEEK")), UserRole.HR, today)).startsWith("rejected:");
        assertThat(layer.dryRun(decision(Map.of("period", "WEEK", "relative", "LAST")), UserRole.EMPLOYEE, today))
                .isEqualTo("outside-role");
    }

    private static WaviRouterDecision decision(Map<String, Object> arguments) {
        return new WaviRouterDecision(WaviRouterDecision.Intent.RECORD_LOOKUP, WaviTools.GET_TEAM_TIMESHEETS, arguments,
                WaviRouterDecision.Confidence.HIGH, List.of(), "english", WaviRouterDecision.Source.CLAUDE);
    }
}
