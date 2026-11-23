package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrChatHistoryMessage;
import com.waypoint.dto.myhr.MyHrChatResponse;
import com.waypoint.dto.myhr.TimesheetResponse;
import com.waypoint.enums.TimesheetStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static com.waypoint.service.WaviHoursConsistencyTest.record;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * "Did I submit my <date / month> timesheet(s)?": the user's own timesheets covering that date or overlapping that
 * month, never the latest unrelated one and never "which record do you mean?". The four 2026-11-17 production
 * questions plus variants; the production failures came from a confident router decision taken mid-conversation
 * that dropped the typed period, so each question is also asked after an earlier turn with such a router.
 */
class WaviTimesheetStatusByDateTest {

    private static final Instant TODAY = Instant.parse("2026-11-17T15:00:00Z");
    private static final String CLARIFY = "{\"decision\":{\"tool\":\"clarify\",\"arguments\":{}},\"confidence\":\"HIGH\","
            + "\"language\":\"english\"}";
    private static final String LATEST = "{\"decision\":{\"tool\":\"get_my_timesheets\",\"arguments\":{\"period\":"
            + "\"LATEST\"}},\"confidence\":\"HIGH\",\"language\":\"english\"}";

    /** The daily-hours fixture plus an approved Oct 12-18 week (8 h on weekdays). */
    static List<TimesheetResponse> records() {
        List<TimesheetResponse> records = new ArrayList<>(WaviDailyHoursAnswerTest.records());
        records.add(record("h", "2026-10-12", "2026-10-18", TimesheetStatus.APPROVED, "2026-10-19T09:00", 8, 8, 8, 8, 8, 0, 0));
        return records;
    }

    private static final class Chat {
        final WaviEvaluationWorld world;
        final List<MyHrChatHistoryMessage> history = new ArrayList<>();

        Chat(WaviEvaluationWorld.Person person, String routerDecision) {
            WaviEvaluationWorld.Options options = new WaviEvaluationWorld.Options();
            if (routerDecision != null) options.routerProvider = scripted(routerDecision);
            world = new WaviEvaluationWorld(person, options);
            world.service.useClock(Clock.fixed(TODAY, ZoneOffset.UTC));
            when(world.timesheets.listForUser(person.userId())).thenReturn(records());
        }

        String ask(String message) {
            MyHrChatResponse response = world.service.chat(world.actor, message, List.copyOf(history), StampedHistory.SESSION);
            history.add(new MyHrChatHistoryMessage("user", message, response.historyProvenance().user()));
            history.add(new MyHrChatHistoryMessage("assistant", response.message(), response.historyProvenance().assistant()));
            return response.message();
        }
    }

    /** A confident router that answers every message with the same decision: never a real model. */
    private static WaviSemanticRouting.Provider scripted(String decisionJson) {
        return new WaviSemanticRouting.Provider() {
            public String name() { return "scripted"; }
            public WaviRouterDecision.Source source() { return WaviRouterDecision.Source.values()[0]; }
            public boolean usable() { return true; }
            public String route(WaviRouterPrompt.Request request, WaviSemanticRouting.Settings settings) { return decisionJson; }
        };
    }

    private static String ask(String question) {
        return new Chat(WaviEvaluationWorld.EMPLOYEE, null).ask(question);
    }

    /** Asked after an earlier answered turn, with a router that would discard the typed period. */
    private static String askMidConversation(String question, String routerDecision) {
        Chat chat = new Chat(WaviEvaluationWorld.EMPLOYEE, routerDecision);
        chat.ask("what is my employee ID?");
        return chat.ask(question);
    }

    @Test
    void theFourProductionQuestions() {
        // One weekly timesheet covers the date: its status; the period total and the day's entry kept apart.
        assertThat(ask("Did I submit my Nov 14, 2026 timesheet?")).isEqualTo("Your timesheet covering Sat Nov 14, 2026:\n"
                + "- Mon Nov 9, 2026 to Sun Nov 15, 2026: is approved (submitted Mon Nov 16, 2026 at 09:00); "
                + "40 hours for the whole period, none entered on Sat Nov 14, 2026");
        // Three submissions cover the date: each one with its own status, newest first - never the latest timesheet.
        String nov10 = ask("Did I submit my Nov 10, 2026 timesheet?");
        assertThat(nov10).startsWith("3 timesheets of yours cover Tue Nov 10, 2026").contains(
                "Mon Nov 9, 2026 to Wed Nov 11, 2026: is submitted and waiting for approval",
                "Mon Nov 9, 2026 to Wed Nov 11, 2026: was rejected",
                "Mon Nov 9, 2026 to Sun Nov 15, 2026: is approved", "8 hours entered on Tue Nov 10, 2026")
                .doesNotContain("most recent", "Which one do you mean");
        // A month: every timesheet overlapping it, with its status.
        assertThat(ask("Did I submit my October 2026 timesheets?")).startsWith("For October 2026")
                .contains("Mon Oct 12, 2026 to Sun Oct 18, 2026 was rejected", "Mon Oct 19, 2026 to Sun Oct 25, 2026 is approved")
                .doesNotContain("Which one do you mean");
        assertThat(ask("Did I submit my Oct 15, 2026 timesheet?")).isEqualTo("Your timesheet covering Thu Oct 15, 2026:\n"
                + "- Mon Oct 12, 2026 to Sun Oct 18, 2026: is approved (submitted Mon Oct 19, 2026 at 09:00); "
                + "40 hours for the whole period, 8 hours entered on Thu Oct 15, 2026");
    }

    @Test
    void aRouterDecisionThatDropsTheTypedPeriodIsNotActedOn() {
        for (String question : List.of("Did I submit my Nov 14, 2026 timesheet?", "Did I submit my Nov 10, 2026 timesheet?",
                "Did I submit my October 2026 timesheets?", "Did I submit my Oct 15, 2026 timesheet?",
                "Have I submitted my November 2026 timesheets?", "Is my 11/10/2026 timesheet approved?")) {
            String expected = ask(question);
            assertThat(askMidConversation(question, CLARIFY)).as("clarify: " + question).isEqualTo(expected)
                    .doesNotContain("Which one do you mean");
            assertThat(askMidConversation(question, LATEST)).as("latest: " + question).isEqualTo(expected)
                    .doesNotContain("most recent timesheet");
        }
        // No period typed: the router's latest-timesheet decision still answers, unchanged.
        assertThat(askMidConversation("Did I submit my timesheet?", LATEST)).doesNotContain("Which one do you mean");
    }

    @Test
    void wordingAndDateFormatVariants() {
        String nov10 = ask("Did I submit my Nov 10, 2026 timesheet?");
        for (String question : List.of("Have I submitted my timesheet for Nov 10, 2026?",
                "Was my timesheet for 2026-11-10 submitted?", "Is my November 10, 2026 timesheet approved?",
                "Did I submit my 11/10/2026 timesheet?", "did i submit my timesheet for 10 Nov 2026")) {
            assertThat(ask(question)).as(question).isEqualTo(nov10);
        }
    }

    @Test
    void draftRejectedResubmittedAndNoMatch() {
        // Draft only: entered, not submitted.
        assertThat(ask("Did I submit my Nov 16, 2026 timesheet?")).contains("Mon Nov 16, 2026 to Tue Nov 17, 2026: "
                + "is saved as a draft and has not been submitted yet");
        // A rejected resubmission beside the approved week: both, each with its own status.
        assertThat(ask("Have I submitted my timesheet for Nov 3, 2026?")).startsWith("2 timesheets of yours cover Tue Nov 3, 2026")
                .contains("Tue Nov 3, 2026 to Wed Nov 4, 2026: was rejected", "Mon Nov 2, 2026 to Sun Nov 8, 2026: is approved");
        // Nothing covers the date: said so, nothing invented.
        assertThat(ask("Did I submit my Dec 1, 2025 timesheet?"))
                .startsWith("I don't see a timesheet of yours whose period covers Mon Dec 1, 2025");
    }

    /** Shaped like the verified live records: an approved Oct 31-Nov 29 sheet and two pending Nov 8-14 submissions. */
    static List<TimesheetResponse> liveShaped() {
        return List.of(
                record("m", "2026-10-31", "2026-11-29", TimesheetStatus.APPROVED, "2026-11-12T14:46",
                        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 8, 8, 8, 8, 8, 8, 8, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                record("p1", "2026-11-08", "2026-11-14", TimesheetStatus.SUBMITTED, "2026-11-11T13:02", 0, 0, 8, 8, 8, 0, 0),
                record("p2", "2026-11-08", "2026-11-14", TimesheetStatus.SUBMITTED, "2026-11-11T15:25", 0, 0, 8, 8, 8, 0, 0));
    }

    @Test
    void liveShapedFollowUps() {
        Chat asha = new Chat(WaviEvaluationWorld.EMPLOYEE, null);
        when(asha.world.timesheets.listForUser(WaviEvaluationWorld.EMPLOYEE.userId())).thenReturn(liveShaped());
        String first = asha.ask("Did I submit my Nov 10, 2026 timesheet?");
        String approved = asha.ask("Which one was approved?");
        String why = asha.ask("Why were the hours counted only once?");
        assertThat(first).startsWith("3 timesheets of yours cover Tue Nov 10, 2026");
        assertThat(approved).contains("Sat Oct 31, 2026 to Sun Nov 29, 2026").doesNotContain("Sun Nov 8, 2026 to");
        assertThat(why).startsWith("Each work date is counted once").endsWith("Tue Nov 10, 2026 is on 3 of your "
                + "submissions, each with 8 hours, so Hours Overview counts 8 hours for it, not the 24 hours they would add up to.");
        // The same explanation straight after the status answer, without the approved-list step.
        Chat direct = new Chat(WaviEvaluationWorld.EMPLOYEE, null);
        when(direct.world.timesheets.listForUser(WaviEvaluationWorld.EMPLOYEE.userId())).thenReturn(liveShaped());
        direct.ask("Did I submit my Nov 10, 2026 timesheet?");
        // Straight after the status answer the record follow-up answers it: each submission's 8 hours, counted once.
        assertThat(direct.ask("Why were the hours counted only once?")).contains("8 hours entered on Tue Nov 10, 2026",
                "aren't added together", "Hours Overview counts 8 hours for that day");
    }

    @Test
    void followUpsAndAccessAreUnchanged() {
        Chat asha = new Chat(WaviEvaluationWorld.EMPLOYEE, null);
        asha.ask("Did I submit my Nov 10, 2026 timesheet?");
        assertThat(asha.ask("Which one was approved?")).startsWith("Your approved timesheets covering")
                .contains("Mon Nov 9, 2026 to Sun Nov 15, 2026").doesNotContain("was rejected");

        Chat admin = new Chat(WaviEvaluationWorld.ADMIN, CLARIFY);
        assertThat(admin.ask("Did I submit my Nov 10, 2026 timesheet?")).contains("Company Admin accounts");
        Chat other = new Chat(WaviEvaluationWorld.EMPLOYEE, null);
        assertThat(other.ask("Did Ravi Teja submit his Nov 10, 2026 timesheet?")).isEqualTo(MyHrChatService.OTHER_PERSON_REPLY);
        verify(other.world.employees, never()).list(anyString());
    }
}
