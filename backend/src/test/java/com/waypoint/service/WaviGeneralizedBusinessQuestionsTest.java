package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrChatHistoryMessage;
import com.waypoint.dto.myhr.MyHrChatResponse;
import com.waypoint.dto.myhr.MyHrTimesheetPeriodStatus;
import com.waypoint.dto.myhr.TimesheetResponse;
import com.waypoint.enums.TimesheetStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Classes of business question found by probing the real chat service with unfamiliar wording (2026-11-14/15), each
 * asserted on the VALUES the reply must contain, computed from the synthetic records below - not on which route ran:
 * explicit date spans, whose records a question is about (a group, several people, a relationship, a lower-case or
 * unknown name), who has not submitted, per-day hours, the latest record, and follow-ups that change only the
 * period, status or granularity. Every question is asked in several wordings. Today is Thu Nov 5, 2026.
 */
class WaviGeneralizedBusinessQuestionsTest {

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
                LocalDate.parse(to), status, LocalDateTime.parse(submittedAt)));
    }

    /**
     * Ravi: Oct 5-11 and Oct 12-18 approved (40 h each), last week rejected (40 h) plus a 3-day resubmission (24 h),
     * this week waiting (40 h). Asha: last week approved (40 h), this week waiting (40 h). A former report of HR's is
     * out of HR's scope.
     */
    private static List<TimesheetResponse> records(WaviEvaluationWorld.Person reviewer) {
        List<TimesheetResponse> records = new ArrayList<>(List.of(
                sheet(RAVI, reviewer, "2026-10-05", "2026-10-11", TimesheetStatus.APPROVED, "2026-10-12T09:00"),
                sheet(RAVI, reviewer, "2026-10-12", "2026-10-18", TimesheetStatus.APPROVED, "2026-10-19T09:00"),
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
        when(world.timesheets.listForUser(person.userId())).thenReturn(records(person));
        return new Chat(world);
    }

    private static Chat employee() {
        return new Chat(new WaviEvaluationWorld(ASHA, new WaviEvaluationWorld.Options()));
    }

    // --- Explicit date spans -------------------------------------------------------------------------------------

    @Test
    void aDateSpanCoversEveryDayBetweenItsTwoDatesNotOnlyTheFirst() {
        LocalDate today = LocalDate.parse("2026-11-05");
        for (String text : List.of("between oct 5 and oct 18", "from october 5 to october 18", "oct 5 to 18",
                "oct 5-18", "2026-10-05 to 2026-10-18", "5th of october through the 18th of october",
                "oct 5, 2026 until oct 18, 2026")) {
            assertThat(WaviDateSpan.find("hours " + text, today, 92)).as(text)
                    .containsExactly(LocalDate.parse("2026-10-05"), LocalDate.parse("2026-10-18"));
        }
        // A span may end after today; the end is the first such date on or after the start.
        assertThat(WaviDateSpan.find("nov 2 to nov 8", today, 92))
                .containsExactly(LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-08"));
        assertThat(WaviDateSpan.find("dec 28 to jan 3", today, 92))
                .containsExactly(LocalDate.parse("2025-12-28"), LocalDate.parse("2026-01-03"));
        // Not spans: one date, a quantity after "to", two unrelated dates, "and" without "between", too long.
        for (String text : List.of("hours on oct 14", "oct 5 to 8 hours", "was oct 5 better than oct 18",
                "oct 5 and oct 18", "jan 1 to oct 30", "2 weeks ago", "I need 5 to 10 days off")) {
            assertThat(WaviDateSpan.find(text, today, 92)).as(text).isNull();
        }
    }

    @Test
    void explicitDaysAndRangesWinOverMonthRecognitionRegardlessOfCommaSpacing() {
        LocalDate today = LocalDate.parse("2026-11-05");
        for (String text : List.of("oct 23,2026", "oct 23, 2026", "october 23 2026", "sep 14,2026")) {
            LocalDate expected = text.startsWith("sep") ? LocalDate.parse("2026-09-14") : LocalDate.parse("2026-10-23");
            assertThat(WaviDateSpan.single("hours on " + text, today)).as(text).isEqualTo(expected);
            MyHrConversationIntents.TimesheetRange range = MyHrConversationIntents.periodIn("hours on " + text, today);
            assertThat(range).as(text).isNotNull();
            assertThat(range.from()).as(text).isEqualTo(expected);
            assertThat(range.to()).as(text).isEqualTo(expected);
            assertThat(range.unit()).as(text).isEqualTo(MyHrConversationIntents.TimesheetRange.Unit.DAY);
        }
        for (String text : List.of("sep 7,2026 - sep 9,2026", "sep 7, 2026 through september 9,2026",
                "oct 30,2026 to nov 2,2026")) {
            LocalDate[] expected = text.startsWith("oct")
                    ? new LocalDate[]{LocalDate.parse("2026-10-30"), LocalDate.parse("2026-11-02")}
                    : new LocalDate[]{LocalDate.parse("2026-09-07"), LocalDate.parse("2026-09-09")};
            assertThat(WaviDateSpan.find("hours from " + text, today, 92)).as(text).containsExactly(expected);
            MyHrConversationIntents.TimesheetRange range = MyHrConversationIntents.periodIn("hours from " + text, today);
            assertThat(range).as(text).isNotNull();
            assertThat(range.from()).as(text).isEqualTo(expected[0]);
            assertThat(range.to()).as(text).isEqualTo(expected[1]);
            assertThat(range.unit()).as(text).isEqualTo(MyHrConversationIntents.TimesheetRange.Unit.SPAN);
        }
    }

    @Test
    void anExplicitDayOverridesAStoredMonthAndUsesTheEffectiveHoursForThatDay() {
        WaviEvaluationWorld world = new WaviEvaluationWorld(ASHA, new WaviEvaluationWorld.Options());
        TimesheetResponse original = sheet(ASHA, HR, "2026-10-23", "2026-10-23", TimesheetStatus.APPROVED,
                "2026-10-24T09:00");
        TimesheetResponse elevenHours = new TimesheetResponse(original.id(), original.employeeUserId(), original.employeeId(),
                original.employeeName(), original.employeeRole(), original.reportingManagerUserId(),
                original.reportingManagerLoginId(), original.reportingManagerName(), original.periodStartDate(),
                original.periodEndDate(), List.of(new com.waypoint.dto.myhr.TimesheetEntryResponse(LocalDate.parse("2026-10-23"),
                        "FRIDAY", java.math.BigDecimal.valueOf(11), "work")), java.math.BigDecimal.valueOf(11),
                original.supportingDocuments(), original.status(), original.submittedAt(), original.createdAt(), original.updatedAt(),
                original.reviewedByUserId(), original.reviewedAt(), original.reviewComment(), original.changeReason());
        when(world.timesheets.listForUser(ASHA.userId())).thenReturn(List.of(elevenHours));
        Chat chat = new Chat(world);

        chat.ask("show my hours for october");
        String day = chat.ask("how many hours did i submitted for oct 23,2026");
        // One-day answer convention (2026-11-17): the day's value first, for the metric asked.
        assertThat(day).isEqualTo("11 submitted hours recorded for Fri Oct 23, 2026.")
                .doesNotContain("Hours recorded for October 2026");

        String span = chat.ask("how many hours did i submit for sep 7,2026 - sep 9,2026");
        assertThat(span).contains("Mon Sep 7, 2026 to Wed Sep 9, 2026")
                .doesNotContain("Hours for which period?", "timesheets, profile changes, or document requests");
    }

    @Test
    void hoursForADateSpanAreTotalledOverTheWholeSpanForTheUserAndForAPersonInScope() {
        for (String question : List.of("what did Ravi Teja work between oct 5 and oct 18",
                "hours logged by Ravi Teja from 2026-10-05 to 2026-10-18", "ravi teja hours oct 5 to 18")) {
            assertThat(reviewer(HR).ask(question)).as(question).contains("Hours recorded for Ravi Teja",
                    "Mon Oct 5, 2026 to Sun Oct 18, 2026", "Total across these submissions: 80 hours")
                    .doesNotContain("Asha", "Oct 26");
        }
        // The employee's own records (WaviEvaluationWorld): Oct 19-25 approved, 40 hours.
        assertThat(employee().ask("my hours from oct 19 to 25")).contains("Mon Oct 19, 2026 to Sun Oct 25, 2026",
                "40 hours on 5 days").doesNotContain("Oct 12", "Oct 26, 2026 to");
    }

    // --- Whose records ---------------------------------------------------------------------------------------------

    @Test
    void aGroupQuestionCoversTheReviewersWholeScopeAndTotalsOnlyPeopleWithoutOverlappingSubmissions() {
        for (String question : List.of("how many hours did my team log last week?", "total hours for everyone last week",
                "staff hours for last week")) {
            String reply = reviewer(HR).ask(question);
            assertThat(reply).as(question).contains("your review scope covering last week", "Asha Rao - ",
                    "Ravi Teja - ", "Asha Rao: 40 hours", "Ravi Teja: not totalled (overlapping submissions)")
                    .doesNotContain(WaviEvaluationWorld.FORMER_REPORT.name(), "General guidance");
        }
        assertThat(reviewer(ADMIN).ask("list rejected timesheets in the organization"))
                .contains("1 rejected (waiting for the employee to correct and resubmit) timesheet for your organization",
                        "Ravi Teja - Mon Oct 26, 2026 to Sun Nov 1, 2026").doesNotContain("Asha");
    }

    @Test
    void severalPeopleNamedInFullAreAnsweredTogetherNotAskedWhichOne() {
        String reply = reviewer(HR).ask("compare Ravi Teja and Asha Rao hours this week");
        assertThat(reply).contains("Asha Rao: 40 hours", "Ravi Teja: 40 hours").doesNotContain("Which one do you mean");
        assertThat(reviewer(ADMIN).ask("Hari Kumar and Ravi Teja timesheets for this week"))
                .contains("Ravi Teja - Mon Nov 2, 2026 to Sun Nov 8, 2026", "No matching timesheets for Hari Kumar")
                .doesNotContain("Asha");
    }

    @Test
    void aLowerCaseNameIsResolvedInScopeAndAnUnknownNameIsNeverAnsweredAsSomeoneElse() {
        assertThat(reviewer(HR).ask("hours for ravi this week")).contains("Hours recorded for Ravi Teja covering this week",
                "40 hours on 5 days").doesNotContain("of yours");
        for (String question : List.of("show timesheets for Former Report", "timesheets for Priya Nair last month",
                "how many hours did Neha Varma log last week?")) {
            Chat hr = reviewer(HR);
            assertThat(hr.ask(question)).as(question).isEqualTo(MyHrChatService.PERSON_NOT_IN_SCOPE_HR);
            verify(hr.world.claude, never()).chat(anyString(), org.mockito.ArgumentMatchers.anyList());
        }
    }

    @Test
    void recordsOfSomeoneNamedByRelationshipAreNeverTheAskersOwn() {
        for (String question : List.of("how many hours did my manager log last week", "my supervisor's timesheet for last week",
                "show my boss's hours for october")) {
            Chat asha = employee();
            assertThat(asha.ask(question)).as(question).isEqualTo(MyHrChatService.OTHER_PERSON_REPLY);
            verify(asha.world.timesheets, never()).listForUser(anyString());
            assertThat(reviewer(HR).ask(question)).as(question).startsWith("Which person in your authorized scope do you mean?");
        }
        // About the user's OWN record: still answered from it.
        assertThat(employee().ask("did my manager approve my timesheet?")).contains("Your most recent timesheet");
    }

    @Test
    void whoHasNotSubmittedIsAnsweredFromTheScopeAndRefusedForAnEmployee() {
        for (String question : List.of("who hasn't submitted a timesheet for last week?",
                "whose timesheet is missing for last week", "who still hasn't turned in a timesheet for last week?")) {
            // HR: both reports have a submission covering last week (Ravi's rejected one was resubmitted).
            assertThat(reviewer(HR).ask(question)).as(question)
                    .contains("Everyone among your active direct reports (2) has a submitted timesheet covering last week");
            // Admin scope also holds Hari Kumar, who has none.
            assertThat(reviewer(ADMIN).ask(question)).as(question).contains("1 of 3", "- Hari Kumar")
                    .doesNotContain("- Ravi Teja", "- Asha Rao");
            Chat asha = employee();
            assertThat(asha.ask(question)).as(question).isEqualTo(MyHrChatService.TIMESHEET_QUEUE_REVIEWERS_ONLY);
            verify(asha.world.employees, never()).list(anyString());
        }
        // Two weeks ago (Oct 19-25) nobody in HR's scope submitted; a rejected-only week is named as such.
        assertThat(reviewer(HR).ask("who didn't submit timesheets for the week of oct 19?")).contains("2 of 2",
                "- Asha Rao", "- Ravi Teja");
        assertThat(reviewer(ADMIN).ask("who submitted timesheets this week?"))
                .contains("2 timesheets for your organization covering this week", "Asha Rao - ", "Ravi Teja - ")
                .doesNotContain("don't submit");
    }

    // --- Granularity, latest record and follow-ups -----------------------------------------------------------------

    @Test
    void perDayHoursForAPersonShowEachDayAndNeverAddASharedDay() {
        for (String question : List.of("Ravi Teja's hours on each day last week",
                "give me a day-wise breakup of Ravi Teja's hours for last week")) {
            String reply = reviewer(HR).ask(question);
            assertThat(reply).as(question).contains("Hours per day for Ravi Teja covering last week",
                    "- Mon Oct 26, 2026: 8 hours (submitted, awaiting review", "8 hours (rejected, returned to the employee",
                    "- Thu Oct 29, 2026: 8 hours\n", "not added together").doesNotContain("Total:");
        }
        assertThat(reviewer(HR).ask("daily hours for Asha Rao last week")).contains("- Fri Oct 30, 2026: 8 hours",
                "Total: 40 hours.");
    }

    @Test
    void theLatestTimesheetOfAPersonIsOneRecordAndItRefersToIt() {
        Chat hr = reviewer(HR);
        assertThat(hr.ask("show Ravi Teja's latest timesheet")).startsWith("Ravi Teja's latest timesheet (by work period): "
                + "Mon Nov 2, 2026 to Sun Nov 8, 2026, submitted, awaiting review, submitted Wed Nov 4, 2026 at 11:00")
                .doesNotContain("Oct 26");
        assertThat(hr.ask("when did he send it in?")).contains("submitted Wed Nov 4, 2026 at 11:00").doesNotContain("Oct 26");
    }

    @Test
    void aFollowUpChangesOnlyThePeriodStatusOrGranularityAndKeepsThePerson() {
        Chat hr = reviewer(HR);
        assertThat(hr.ask("how many hours did Ravi Teja log this week?")).contains("40 hours on 5 days");
        assertThat(hr.ask("and the week before?")).contains("Hours recorded for Ravi Teja covering the week of Mon Oct 26, 2026",
                "24 hours on 3 days", "haven't added them together").doesNotContain("Asha");
        assertThat(hr.ask("day by day")).contains("Hours per day for Ravi Teja", "- Mon Oct 26, 2026: 8 hours (");

        Chat list = reviewer(HR);
        assertThat(list.ask("show Ravi Teja's timesheets")).contains("5 timesheets for Ravi Teja");
        assertThat(list.ask("only the rejected ones")).contains("1 rejected (waiting for the employee to correct and resubmit) "
                + "timesheet for Ravi Teja").doesNotContain("approved,");
        assertThat(list.ask("just the submitted ones")).contains("2 submitted (awaiting review) timesheets for Ravi Teja");
        assertThat(list.ask("and for this week?")).contains("1 submitted (awaiting review) timesheet for Ravi Teja covering this week");
    }

    @Test
    void anEmployeesOwnListsFollowUpsAndReviewerAreAnsweredFromTheirOwnRecords() {
        // Own records (WaviEvaluationWorld): 7 timesheets, 4 approved, 1 rejected (Oct 12-18).
        assertThat(employee().ask("which of my timesheets were rejected?")).contains("Your rejected timesheets (1)",
                "Mon Oct 12, 2026 to Sun Oct 18, 2026").doesNotContain("I can only check your most recent");
        assertThat(employee().ask("how many of my timesheets got approved?")).contains("Your approved timesheets (4)");
        assertThat(employee().ask("show timesheets")).contains("Your timesheets (7)");

        Chat asha = employee();
        assertThat(asha.ask("how much did I work 2 weeks ago")).contains("Mon Oct 19, 2026 to Sun Oct 25, 2026",
                "40 hours on 5 days");
        Chat latest = employee();
        latest.ask("show my latest timesheet");
        assertThat(latest.ask("and the one before?")).contains("Mon Oct 26, 2026 to Sun Nov 1, 2026", "is approved");

        for (String question : List.of("who reviews my timesheet?", "who signs off on my timesheet",
                "who looks after approving my timesheet?")) {
            assertThat(employee().ask(question)).as(question).contains("Your reporting manager, " + ASHA.manager()
                    + ", reviews the timesheets you submit");
        }
    }

    @Test
    void anOwnPeriodTotalIsGivenOnceWhenNoDayIsOnTwoTimesheets() {
        // Nov 1 falls on the Oct 26 - Nov 1 timesheet (no hours that day) and Nov 2-8 holds 40 hours.
        assertThat(employee().ask("my hours this month")).contains("Total recorded across these timesheets: 40 hours "
                + "(not all of these timesheets are approved).");
        // October has three days on two of the user's timesheets: no total.
        assertThat(employee().ask("how many hours did I work in october?")).contains("haven't added them together")
                .doesNotContain("Total recorded");
    }

    // --- Totals without a unit, context after spans, history, clarification (round 3) ---------------------------

    @Test
    void aTotalWithoutAUnitMeansHoursForTheUserAPersonOrThePreviousAnswerButNeverPayOrACount() {
        assertThat(employee().ask("what's my total for october")).contains("Hours recorded for October 2026",
                "haven't added them together");
        Chat asha = employee();
        asha.ask("how many hours did I work last week?");
        assertThat(asha.ask("and the total for september?")).contains("Hours recorded for September 2026", "24 hours on 3 days");

        Chat hr = reviewer(HR);
        assertThat(hr.ask("what's the total for Ravi Teja in october")).contains("Hours recorded for Ravi Teja covering October 2026")
                .doesNotContain("Job title", "Asha");
        Chat follow = reviewer(HR);
        follow.ask("how many hours did Ravi Teja log this week?");
        assertThat(follow.ask("what's the total?")).contains("Hours recorded for Ravi Teja covering this week", "40 hours on 5 days");

        // Not hours: a pay total (Wavi never shows pay) and a count of records.
        assertThat(employee().ask("what's my total pay for october")).doesNotContain("Hours recorded");
        assertThat(reviewer(HR).ask("total number of timesheets pending")).doesNotContain("Hours recorded");
    }

    @Test
    void aBareAmountQuestionWithNothingToContinueAsksForThePeriodInsteadOfGuessing() {
        Chat asha = employee();
        assertThat(asha.ask("how many hours?")).startsWith("Hours for which period?");
        verify(asha.world.claude, never()).chat(anyString(), org.mockito.ArgumentMatchers.anyList());
        assertThat(reviewer(HR).ask("what's the total?")).startsWith("Whose hours, and for which period?");
    }

    @Test
    void anOwnDateSpanAnswerIsRememberedSoTheNextPeriodKeepsTheHoursQuestion() {
        Chat asha = employee();
        assertThat(asha.ask("my hours between oct 19 and oct 25")).contains("40 hours on 5 days");
        // The same-length span before it (Oct 12-18): rejected 40 h plus a 3-day resubmission, never added.
        assertThat(asha.ask("and the week before that?")).contains("Hours recorded", "Mon Oct 12, 2026 to Sun Oct 18, 2026",
                "24 hours on 3 days", "haven't added them together");
        assertThat(asha.ask("what about last week?")).contains("Hours recorded for last week", "40 hours on 5 days");
    }

    @Test
    void anOwnCountForAPeriodAndSubmissionHistoryComeFromTheUsersOwnList() {
        assertThat(employee().ask("how many timesheets did I submit in october?"))
                .contains("Your timesheets covering October 2026 (6)").doesNotContain("Nov 2, 2026 to");
        assertThat(employee().ask("show my submission history")).contains("Your timesheets (7)");
    }

    private static TimesheetResponse reviewed(TimesheetResponse t, String reviewerId, String at) {
        return new TimesheetResponse(t.id(), t.employeeUserId(), t.employeeId(), t.employeeName(), t.employeeRole(),
                t.reportingManagerUserId(), t.reportingManagerLoginId(), t.reportingManagerName(), t.periodStartDate(),
                t.periodEndDate(), t.entries(), t.totalHours(), t.supportingDocuments(), t.status(), t.submittedAt(),
                t.createdAt(), t.updatedAt(), reviewerId, LocalDateTime.parse(at), t.reviewComment(), t.changeReason());
    }

    @Test
    void whenAndByWhomAnOwnTimesheetWasReviewedComesFromTheRecordAndIsNeverInvented() {
        WaviEvaluationWorld world = new WaviEvaluationWorld(ASHA, new WaviEvaluationWorld.Options());
        when(world.timesheets.listForUser(ASHA.userId())).thenReturn(List.of(
                reviewed(sheet(ASHA, HR, "2026-10-19", "2026-10-25", TimesheetStatus.APPROVED, "2026-10-26T09:00"),
                        HR.userId(), "2026-10-27T14:00"),
                reviewed(sheet(ASHA, HR, "2026-10-12", "2026-10-18", TimesheetStatus.REJECTED, "2026-10-19T09:00"),
                        "admin-zz", "2026-10-20T10:00"),
                sheet(ASHA, HR, "2026-11-02", "2026-11-08", TimesheetStatus.SUBMITTED, "2026-11-04T10:00")));
        String october = new Chat(world).ask("when was my october timesheet approved?");
        assertThat(october).contains("Mon Oct 19, 2026 to Sun Oct 25, 2026: approved by Hari Kumar on Tue Oct 27, 2026",
                // Reviewed by someone the record does not name: the date, and no invented name.
                "Mon Oct 12, 2026 to Sun Oct 18, 2026: rejected on Tue Oct 20, 2026; the reviewer's name isn't shown")
                .doesNotContain("Nov 2");
        assertThat(new Chat(world).ask("who approved my last timesheet?"))
                .contains("None of your timesheets (your latest) has been approved", "not reviewed yet (submitted)");
    }

    @Test
    void aClarificationIsResolvedByTheOneWordAnswerInsteadOfBeingAskedAgain() {
        Chat asha = employee();
        assertThat(asha.ask("what's the status?")).isEqualTo(MyHrChatService.CLARIFY_EMPLOYEE);
        assertThat(asha.ask("timesheet")).contains("Your most recent timesheet").isNotEqualTo(MyHrChatService.CLARIFY_EMPLOYEE);
    }

    @Test
    void theyWithNobodySelectedIsTheReviewersScopeAndNeverTheAskersOwnRecords() {
        assertThat(reviewer(HR).ask("how many hours did they log last week")).contains("your review scope covering last week",
                "Asha Rao: 40 hours").doesNotContain("of yours");
        Chat asha = employee();
        assertThat(asha.ask("how many hours did they log last week")).isEqualTo(MyHrChatService.OTHER_PERSON_REPLY);
        verify(asha.world.timesheets, never()).listForUser(anyString());
    }

    @Test
    void aStatusQuestionThatMatchesNothingSaysWhatStatusTheRecordsAreIn() {
        String reply = reviewer(HR).ask("was Ravi Teja's timesheet for last week approved?");
        assertThat(reply).contains("I don't see any approved timesheets for Ravi Teja covering last week",
                "In other statuses I see", "rejected, returned to the employee to correct and resubmit",
                "submitted, awaiting review").doesNotContain("Asha", "Nov 2, 2026 to");
        assertThat(reviewer(HR).ask("how many timesheets were rejected last month?"))
                .contains("1 rejected (waiting for the employee to correct and resubmit) timesheet for your review scope",
                        "Ravi Teja - Mon Oct 26, 2026 to Sun Nov 1, 2026").doesNotContain("can't count");
    }

    @Test
    void recordAnswersAreTheSameWhenTheSemanticRouterIsUnavailableOrAbstains() {
        for (String question : List.of("what's my total for october", "how many timesheets did I submit in october?",
                "my hours between oct 5 and oct 18", "how many hours did i submit for oct 23,2026",
                "how many hours did i submit for sep 7,2026 - sep 9,2026", "which of my timesheets were rejected?")) {
            String plain = employee().ask(question);
            for (WaviEvaluationWorld.RouterBehaviour behaviour : List.of(WaviEvaluationWorld.RouterBehaviour.UNAVAILABLE,
                    WaviEvaluationWorld.RouterBehaviour.ABSTAIN)) {
                WaviEvaluationWorld.Options options = new WaviEvaluationWorld.Options();
                options.semanticRouter = behaviour;
                assertThat(new Chat(new WaviEvaluationWorld(ASHA, options)).ask(question)).as(question + " / " + behaviour)
                        .isEqualTo(plain).doesNotContain("temporarily unavailable");
            }
        }
        for (String question : List.of("who hasn't submitted for last week?", "what's the total for Ravi Teja in october",
                "how many hours did my team log last week?")) {
            String plain = reviewer(HR).ask(question);
            WaviEvaluationWorld.Options options = new WaviEvaluationWorld.Options();
            options.semanticRouter = WaviEvaluationWorld.RouterBehaviour.UNAVAILABLE;
            WaviEvaluationWorld world = new WaviEvaluationWorld(HR, options);
            when(world.timesheets.listForUser(HR.userId())).thenReturn(records(HR));
            assertThat(new Chat(world).ask(question)).as(question).isEqualTo(plain).doesNotContain("temporarily unavailable");
        }
    }

    @Test
    void existenceQuestionsAboutReviewWorkGetTheWaitingRecordsAndConsequenceQuestionsGetNoRecordStatus() {
        for (String question : List.of("are there timesheets I still have to review?",
                "is there any pending work for timesheets?", "do we have timesheets left to review?")) {
            assertThat(reviewer(HR).ask(question)).as(question).contains("3 timesheets from 2 people are waiting for your review");
        }
        assertThat(reviewer(HR).ask("how do I approve a timesheet?")).startsWith("Open Timesheets");
        Chat asha = employee();
        assertThat(asha.ask("what happens if I submit my timesheet late for oct 5 to oct 11?"))
                .doesNotContain("Your most recent timesheet", "which record or detail");
        verify(asha.world.timesheets, never()).getMostRecentStatusForUser(anyString());
    }
}
