package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrChatHistoryMessage;
import com.waypoint.dto.myhr.MyHrChatResponse;
import com.waypoint.dto.myhr.TimesheetEntryResponse;
import com.waypoint.dto.myhr.TimesheetResponse;
import com.waypoint.enums.TimesheetStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Wavi's hours answers agree with Timesheets > Hours Overview (one value per work date: the latest submission's
 * entry, or an earlier approved entry when the latest was rejected; drafts count, as on the page), and questions
 * about ONE timesheet - identified by a date, as "latest", or by the previous answer - are answered with that
 * record's hours. Synthetic records shaped like the 2026-11-17 real conversation; today is Thu Nov 5, 2026.
 *
 * <p>October, by hand: Oct 1-2 16 h (approved) + Oct 5-9 40.5 h (approved) + Oct 12-16 50 h (approved) +
 * Oct 19-23 43 h (resubmission, awaiting; the rejected original is replaced day by day) + Oct 26-30 40 h (awaiting)
 * = 189.5 h on 22 worked days; 106.5 h approved, 83 h awaiting approval.
 */
class WaviHoursConsistencyTest {

    private static final WaviEvaluationWorld.Person ASHA = WaviEvaluationWorld.EMPLOYEE;

    static TimesheetResponse record(String id, String from, String to, TimesheetStatus status, String submitted,
                                    double... hours) {
        List<TimesheetEntryResponse> entries = new ArrayList<>();
        LocalDate start = LocalDate.parse(from);
        for (int index = 0; index < hours.length; index++) {
            if (hours[index] <= 0) continue;
            LocalDate day = start.plusDays(index);
            entries.add(new TimesheetEntryResponse(day, day.getDayOfWeek().name(), BigDecimal.valueOf(hours[index]), "work"));
        }
        BigDecimal total = entries.stream().map(TimesheetEntryResponse::hoursWorked).reduce(BigDecimal.ZERO, BigDecimal::add);
        LocalDateTime at = submitted == null ? null : LocalDateTime.parse(submitted);
        return new TimesheetResponse(id, ASHA.userId(), ASHA.employeeId(), ASHA.name(), ASHA.role(), "hr-a1", null,
                "Hari Kumar", start, LocalDate.parse(to), entries, total, List.of(), status, at, at, at, null, null, null, null);
    }

    static List<TimesheetResponse> records() {
        return List.of(
                record("t1", "2026-09-28", "2026-10-04", TimesheetStatus.APPROVED, "2026-10-05T09:00", 8, 8, 8, 8, 8, 0, 0),
                record("t2", "2026-10-05", "2026-10-11", TimesheetStatus.APPROVED, "2026-10-12T09:00", 8, 8, 8, 8, 8.5, 0, 0),
                record("t3", "2026-10-12", "2026-10-18", TimesheetStatus.APPROVED, "2026-10-19T09:00", 8, 10, 10, 11, 11, 0, 0),
                record("t4", "2026-10-19", "2026-10-25", TimesheetStatus.REJECTED, "2026-10-26T09:00", 8, 8, 8, 9, 8, 0, 0),
                record("t5", "2026-10-19", "2026-10-25", TimesheetStatus.SUBMITTED, "2026-10-27T10:00", 8, 8, 8, 11, 8, 0, 0),
                record("t6", "2026-10-26", "2026-11-01", TimesheetStatus.SUBMITTED, "2026-11-02T09:00", 8, 8, 8, 8, 8, 0, 0),
                record("t7", "2026-11-02", "2026-11-08", TimesheetStatus.DRAFT, null, 8, 8, 0, 0, 0, 0, 0));
    }

    private static final class Chat {
        final WaviEvaluationWorld world;
        final List<MyHrChatHistoryMessage> history = new ArrayList<>();

        Chat(WaviEvaluationWorld.Options options) {
            world = new WaviEvaluationWorld(ASHA, options);
            when(world.timesheets.listForUser(ASHA.userId())).thenReturn(records());
        }

        String ask(String message) {
            MyHrChatResponse response = world.service.chat(world.actor, message, List.copyOf(history), StampedHistory.SESSION);
            history.add(new MyHrChatHistoryMessage("user", message, response.historyProvenance().user()));
            history.add(new MyHrChatHistoryMessage("assistant", response.message(), response.historyProvenance().assistant()));
            return response.message();
        }
    }

    private static Chat chat() {
        return new Chat(new WaviEvaluationWorld.Options());
    }

    // --- The shared per-day rule ---------------------------------------------------------------------------------

    @Test
    void thePerDayRuleMatchesHoursOverview() {
        LocalDate oct22 = LocalDate.parse("2026-10-22");
        TimesheetEffectiveHours.Result october = TimesheetEffectiveHours.compute(records(),
                LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"));
        assertThat(october.total()).isEqualByComparingTo("189.5");
        assertThat(october.workedDays()).isEqualTo(22);
        assertThat(october.byStatus().get(TimesheetStatus.APPROVED)).isEqualByComparingTo("106.5");
        assertThat(october.byStatus().get(TimesheetStatus.SUBMITTED)).isEqualByComparingTo("83");
        // The latest submission (the resubmission's 11 h) decides Oct 22, not the rejected original's 9 h, never 20 h.
        assertThat(october.hoursOn(oct22)).isEqualByComparingTo("11");

        // A rejected latest entry falls back to an earlier approved entry for that day; with none, the day is 0.
        List<TimesheetResponse> rejectedLast = List.of(
                record("a", "2026-10-05", "2026-10-11", TimesheetStatus.APPROVED, "2026-10-12T09:00", 8, 7),
                record("b", "2026-10-05", "2026-10-11", TimesheetStatus.REJECTED, "2026-10-13T09:00", 10, 0, 6));
        TimesheetEffectiveHours.Result week = TimesheetEffectiveHours.compute(rejectedLast,
                LocalDate.parse("2026-10-05"), LocalDate.parse("2026-10-11"));
        assertThat(week.hoursOn(LocalDate.parse("2026-10-05"))).isEqualByComparingTo("8");
        assertThat(week.day(LocalDate.parse("2026-10-05")).fallbackFromRejected()).isTrue();
        assertThat(week.hoursOn(LocalDate.parse("2026-10-06"))).isEqualByComparingTo("7");
        assertThat(week.hoursOn(LocalDate.parse("2026-10-07"))).isEqualByComparingTo("0");
        assertThat(week.total()).isEqualByComparingTo("15");
        // Drafts count, as on the page (the draft's 16 h in its own week).
        assertThat(TimesheetEffectiveHours.compute(records(), LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-08"))
                .byStatus().get(TimesheetStatus.DRAFT)).isEqualByComparingTo("16");
    }

    // --- Period totals in many wordings, with the metric asked ------------------------------------------------------

    @Test
    void aPeriodTotalIsTheHoursOverviewFigureWhateverTheWording() {
        for (String question : List.of("How many hours did I work in October?", "What is my total for October?",
                "total hours last month", "my hours for october", "how much time did I log in october")) {
            assertThat(chat().ask(question)).as(question).contains("the Hours Overview total is 189.5 hours on 22 worked days",
                    "106.5 hours approved, 83 hours awaiting approval", "counted once").doesNotContain("General guidance");
        }
        assertThat(chat().ask("How many hours were approved in October?"))
                .contains("approved hours: 106.5 hours on 12 days (Hours Overview shows 189.5 hours in total)");
        assertThat(chat().ask("How many hours did I submit in October?"))
                .contains("submitted hours (approved or awaiting approval): 189.5 hours on 22 days");
        // One day: the counted value first; the submissions differ (9 h rejected, 11 h resubmitted), so a short note.
        assertThat(chat().ask("how many hours did I record on oct 22")).isEqualTo("11 hours recorded for Thu Oct 22, 2026. "
                + "This date is on 2 submissions with different hours; the latest one is counted.");
    }

    @Test
    void aFollowUpChangesOnlyThePeriodOrTheMetric() {
        Chat asha = chat();
        assertThat(asha.ask("my hours last week")).contains("the Hours Overview total is 40 hours on 5 worked days");
        assertThat(asha.ask("and the week before?")).contains("the Hours Overview total is 43 hours on 5 worked days");
        assertThat(asha.ask("how many were approved?")).contains("approved hours: 0 hours on 0 days (Hours Overview shows 43 hours in total)");
        Chat list = chat();
        list.ask("show my timesheets for october");
        assertThat(list.ask("what's the total?")).contains("the Hours Overview total is 189.5 hours on 22 worked days");
    }

    // --- One timesheet: by date, as latest, or from the previous answer ----------------------------------------------

    @Test
    void hoursAfterATimesheetWasIdentifiedAreThatTimesheetsHours() {
        Chat asha = chat();
        assertThat(asha.ask("Did I submit my Oct 12, 2026 timesheet?")).contains("Mon Oct 12, 2026 to Sun Oct 18, 2026: is approved");
        for (String followUp : List.of("How many hours did I submit?", "How many hours did I submit for Oct 12?",
                "what's the total?", "How many hours did I submit for Oct 12 timesheet?")) {
            assertThat(asha.ask(followUp)).as(followUp).contains("Mon Oct 12, 2026 to Sun Oct 18, 2026: is approved",
                    "50 hours on 5 days in total, 8 hours entered on Mon Oct 12, 2026").doesNotContain("Which one do you mean");
        }
        // A different period after a record is a new question.
        assertThat(asha.ask("and how many hours in september?")).doesNotContain("Mon Oct 12, 2026 to Sun Oct 18, 2026: is approved");
    }

    @Test
    void overlappingSubmissionsOfOneDateStayApartAndTheCountedValueIsNamed() {
        String reply = chat().ask("How many hours did I submit for Oct 22, 2026 timesheet?");
        assertThat(reply).startsWith("2 timesheets of yours cover Thu Oct 22, 2026 (separate submissions, newest first):")
                .contains("43 hours on 5 days in total, 11 hours entered on Thu Oct 22, 2026",
                        "41 hours on 5 days in total, 9 hours entered on Thu Oct 22, 2026",
                        "Hours Overview counts 11 hours for that day").doesNotContain("20 hours");
    }

    @Test
    void theLatestTimesheetsHoursAreTheRecordsAndSubmittedHoursSkipADraft() {
        assertThat(chat().ask("What is the total for my latest timesheet?")).startsWith("Your latest timesheet (by work period):")
                .contains("Mon Nov 2, 2026 to Sun Nov 8, 2026: is saved as a draft", "16 hours on 2 days in total");
        String submitted = chat().ask("How many hours did I submit for my recent timesheet?");
        assertThat(submitted).startsWith("Your latest submitted timesheet (by work period):")
                .contains("Mon Oct 26, 2026 to Sun Nov 1, 2026: is submitted", "40 hours on 5 days in total",
                        "Your newer timesheet for Mon Nov 2, 2026 to Sun Nov 8, 2026 is still a draft")
                .doesNotContain("visibility", "can't see");
        Chat asha = chat();
        asha.ask("What is the status of my last timesheet?");
        assertThat(asha.ask("how many hours were on it?")).startsWith("Your latest timesheet (by work period):");
    }

    // --- Same answers without the router; no model; other people's hours stay refused --------------------------------

    @Test
    void hoursAnswersNeverDependOnTheRouterOrAModelAndStayTheUsersOwn() {
        WaviEvaluationWorld.Options down = new WaviEvaluationWorld.Options();
        down.semanticRouter = WaviEvaluationWorld.RouterBehaviour.UNAVAILABLE;
        for (String question : List.of("What is my total for October?", "How many hours were approved in October?",
                "How many hours did I submit for my recent timesheet?", "How many hours did I submit for Oct 12 timesheet?")) {
            Chat routerDown = new Chat(down);
            assertThat(routerDown.ask(question)).as(question).isEqualTo(chat().ask(question))
                    .doesNotContain("temporarily unavailable");
            verify(routerDown.world.claude, never()).chat(anyString(), anyList());
            verify(routerDown.world.claude, never()).chat(any(MyHrGroundedPrompt.class));
        }
        Chat asha = chat();
        assertThat(asha.ask("how many hours did Ravi Teja log in october?")).isEqualTo(MyHrChatService.OTHER_PERSON_REPLY);
        assertThat(asha.ask("what's my manager's total for october")).isEqualTo(MyHrChatService.OTHER_PERSON_REPLY);
        verify(asha.world.employees, never()).list(anyString());
        WaviEvaluationWorld admin = new WaviEvaluationWorld(WaviEvaluationWorld.ADMIN, new WaviEvaluationWorld.Options());
        assertThat(admin.service.chat(admin.actor, "What is my total for October?", List.of(), StampedHistory.SESSION).message())
                .contains("Company Admin accounts don't submit timesheets");
    }

    @Test
    void theGeneralPromptDescribesHoursAsAvailableSoAModelNeverDeniesThem() {
        assertThat(MyHrAssistantPrompt.CAPABILITIES).contains("the hours recorded on their own timesheets")
                .doesNotContain("visibility");
    }
}
