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
 * An Employee asking for a LIST of their own timesheets for a period gets the matching records themselves (work
 * period, status, submission time), not a per-period status summary - in several wordings, with follow-ups that
 * change the period or status, distinct same-period submissions kept apart, and nobody else's records.
 * WaviEvaluationWorld's Employee has 7 timesheets: Sep 28-Oct 4, Oct 5-11, Oct 19-25, Oct 26-Nov 1 approved;
 * Oct 12-18 rejected plus an Oct 12-14 resubmission; Nov 2-8 waiting. Today is Thu Nov 5, 2026.
 */
class WaviOwnTimesheetListTest {

    private static final WaviEvaluationWorld.Person ASHA = WaviEvaluationWorld.EMPLOYEE;

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

    private static Chat chat(WaviEvaluationWorld.Person person) {
        return new Chat(new WaviEvaluationWorld(person, new WaviEvaluationWorld.Options()));
    }

    private static final String OCT_FIRST = "Mon Sep 28, 2026 to Sun Oct 4, 2026";
    private static final String NOV = "Mon Nov 2, 2026 to Sun Nov 8, 2026";

    @Test
    void aListQuestionForAPeriodListsEveryMatchingRecordInAnyWording() {
        for (String question : List.of("Show my timesheets for October", "list my timesheets in october",
                "which of my timesheets are from october?", "what timesheets did I submit in October?",
                "my timesheets for october", "give me all my submissions for october")) {
            String reply = chat(ASHA).ask(question);
            assertThat(reply).as(question).startsWith("Your timesheets covering October 2026 (6):")
                    .contains(OCT_FIRST, "Mon Oct 5, 2026 to Sun Oct 11, 2026",
                            "Mon Oct 12, 2026 to Sun Oct 18, 2026: was rejected",
                            "Mon Oct 12, 2026 to Wed Oct 14, 2026: is submitted",
                            "Mon Oct 26, 2026 to Sun Nov 1, 2026: is approved", "submitted ")
                    .doesNotContain(NOV, "For October 2026");
        }
    }

    @Test
    void otherPeriodShapesAreListedTooAndAnEmptyPeriodSaysSo() {
        assertThat(chat(ASHA).ask("show my timesheets for last week")).startsWith("Your timesheets covering last week (1):")
                .contains("Mon Oct 26, 2026 to Sun Nov 1, 2026");
        assertThat(chat(ASHA).ask("list my timesheets between oct 10 and oct 20"))
                .startsWith("Your timesheets covering the dates you named (4):").doesNotContain(OCT_FIRST);
        assertThat(chat(ASHA).ask("show my rejected timesheets for october"))
                .startsWith("Your rejected timesheets covering October 2026 (1):").contains("Mon Oct 12, 2026 to Sun Oct 18, 2026");
        assertThat(chat(ASHA).ask("show my timesheets for march 2026"))
                .isEqualTo("I don't see any timesheets of yours covering March 2026.");
    }

    @Test
    void followUpsChangeOnlyThePeriodOrTheStatusOfTheList() {
        Chat asha = chat(ASHA);
        asha.ask("show my timesheets for october");
        assertThat(asha.ask("only the rejected ones")).startsWith("Your rejected timesheets covering October 2026 (1):");
        // The status filter is kept for the new period: none rejected in September.
        assertThat(asha.ask("and september?")).isEqualTo("I don't see any rejected timesheets of yours covering September 2026.");
        assertThat(asha.ask("all")).startsWith("Your timesheets covering September 2026 (1):").contains(OCT_FIRST);
        assertThat(asha.ask("what about this week?")).startsWith("Your timesheets covering this week (1):").contains(NOV);
    }

    @Test
    void statusCountAndHoursQuestionsForAPeriodKeepTheirOwnAnswers() {
        assertThat(chat(ASHA).ask("is my timesheet for october approved?")).startsWith("For October 2026");
        assertThat(chat(ASHA).ask("what's the status of my timesheets in october?")).startsWith("For October 2026");
        assertThat(chat(ASHA).ask("how many hours did I work in october?")).startsWith("Hours recorded for October 2026");
        assertThat(chat(ASHA).ask("how many timesheets did I submit in october?"))
                .startsWith("Your timesheets covering October 2026 (6):");
    }

    private static TimesheetResponse sheet(String from, String to, TimesheetStatus status, String submittedAt) {
        return WaviEvaluationWorld.timesheet(ASHA, WaviEvaluationWorld.HR, new MyHrTimesheetPeriodStatus(
                LocalDate.parse(from), LocalDate.parse(to), status,
                submittedAt == null ? null : LocalDateTime.parse(submittedAt)));
    }

    @Test
    void sameDatesSubmittedTwiceStayTwoMarkedRecordsAndNothingIsInvented() {
        WaviEvaluationWorld world = new WaviEvaluationWorld(ASHA, new WaviEvaluationWorld.Options());
        when(world.timesheets.listForUser(ASHA.userId())).thenReturn(List.of(
                sheet("2026-10-19", "2026-10-25", TimesheetStatus.REJECTED, "2026-10-26T09:00"),
                sheet("2026-10-19", "2026-10-25", TimesheetStatus.SUBMITTED, "2026-10-27T15:30"),
                sheet("2026-10-12", "2026-10-18", TimesheetStatus.APPROVED, null)));
        String reply = new Chat(world).ask("show my timesheets for october");
        assertThat(reply).startsWith("Your timesheets covering October 2026 (3):")
                .contains("is submitted and waiting for approval (submitted Tue Oct 27, 2026 at 15:30) "
                                + "[submission 1 of 2 for this period, most recent]",
                        "was rejected and needs to be corrected and resubmitted (submitted Mon Oct 26, 2026 at 09:00)",
                        "[submission 2 of 2 for this period]",
                        // No submission time on the record: said so, not made up.
                        "Mon Oct 12, 2026 to Sun Oct 18, 2026: is approved (no submission time recorded)");
    }

    @Test
    void nobodyElsesTimesheetsAreListed() {
        Chat asha = chat(ASHA);
        assertThat(asha.ask("show Ravi Teja's timesheets for october")).isEqualTo(MyHrChatService.OTHER_PERSON_REPLY);
        assertThat(asha.ask("show my team's timesheets for october")).doesNotContain("Your timesheets", "Ravi");
        verify(asha.world.employees, never()).list(anyString());

        assertThat(chat(WaviEvaluationWorld.ADMIN).ask("show my timesheets for october"))
                .isEqualTo(MyHrChatService.ADMIN_NO_TIMESHEET_SUBMISSIONS);

        // HR submits timesheets too: their own list holds only their own records, never their reports'.
        String hr = chat(WaviEvaluationWorld.HR).ask("show my timesheets for october");
        assertThat(hr).startsWith("Your timesheets covering October 2026").doesNotContain("Ravi", "Asha");
    }
}
