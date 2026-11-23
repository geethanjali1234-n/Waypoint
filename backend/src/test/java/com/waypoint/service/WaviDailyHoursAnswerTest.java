package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrChatHistoryMessage;
import com.waypoint.dto.myhr.MyHrChatResponse;
import com.waypoint.dto.myhr.TimesheetResponse;
import com.waypoint.enums.TimesheetStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.waypoint.service.WaviHoursConsistencyTest.record;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * One answer-first convention for "how many (submitted / approved) hours on <date>": the Hours Overview value under
 * the metric asked, in one sentence, with a note only when it changes how the figure reads, and the submissions one
 * follow-up away. Synthetic records shaped like the 2026-11-17 live questions (Oct 10 and Nov 10, 2026).
 */
class WaviDailyHoursAnswerTest {

    private static final WaviEvaluationWorld.Person ASHA = WaviEvaluationWorld.EMPLOYEE;
    /** Nov 10 is held by a rejected submission and its resubmission: the one note that applies. */
    private static final String RESUBMITTED = " An earlier submission for this date was rejected; the resubmission is counted.";

    /**
     * Oct 5-11 approved, no entry on Sat Oct 10. Nov 10 on three submissions of 8 h: the approved week, a rejected
     * Nov 9-11 and its resubmission (latest, awaiting approval). Nov 3: approved 7 h, then a rejected 9 h (latest).
     * Nov 4: only a rejected 6 h. Nov 16-17: a draft of 5 h each.
     */
    static List<TimesheetResponse> records() {
        return List.of(
                record("a", "2026-10-05", "2026-10-11", TimesheetStatus.APPROVED, "2026-10-12T09:00", 8, 8, 8, 8, 8, 0, 0),
                record("b", "2026-11-09", "2026-11-15", TimesheetStatus.APPROVED, "2026-11-16T09:00", 8, 8, 8, 8, 8, 0, 0),
                record("c", "2026-11-09", "2026-11-11", TimesheetStatus.REJECTED, "2026-11-17T09:00", 8, 8, 8),
                record("d", "2026-11-09", "2026-11-11", TimesheetStatus.SUBMITTED, "2026-11-18T09:00", 8, 8, 8),
                record("e", "2026-11-02", "2026-11-08", TimesheetStatus.APPROVED, "2026-11-09T09:00", 0, 7, 0, 0, 0, 0, 0),
                record("f", "2026-11-03", "2026-11-04", TimesheetStatus.REJECTED, "2026-11-10T09:00", 9, 6),
                record("g", "2026-11-16", "2026-11-17", TimesheetStatus.DRAFT, null, 5, 5));
    }

    private static final class Chat {
        final WaviEvaluationWorld world;
        final List<MyHrChatHistoryMessage> history = new ArrayList<>();

        Chat(WaviEvaluationWorld.Person person) {
            world = new WaviEvaluationWorld(person, new WaviEvaluationWorld.Options());
            when(world.timesheets.listForUser(person.userId())).thenReturn(records());
        }

        String ask(String message) {
            MyHrChatResponse response = world.service.chat(world.actor, message, List.copyOf(history), StampedHistory.SESSION);
            history.add(new MyHrChatHistoryMessage("user", message, response.historyProvenance().user()));
            history.add(new MyHrChatHistoryMessage("assistant", response.message(), response.historyProvenance().assistant()));
            return response.message();
        }
    }

    private static String ask(String question) {
        return new Chat(ASHA).ask(question);
    }

    @Test
    void bothLiveQuestionsGetTheSameAnswerFirstSentence() {
        // A covered day with no entry: zero, and why - never a claim that no work happened.
        assertThat(ask("How many hours did I submit for Oct 10, 2026?")).isEqualTo(
                "0 submitted hours recorded for Sat Oct 10, 2026. Your timesheet covering this date has no hours entered for it.");
        // Three submissions of the same 8 h: one sentence, no list; one short note because one of them was rejected.
        assertThat(ask("How many hours did I submit for Nov 10, 2026?"))
                .isEqualTo("8 submitted hours recorded for Tue Nov 10, 2026." + RESUBMITTED);
    }

    @Test
    void theMetricAskedChangesOnlyWhatIsCounted() {
        assertThat(ask("How many hours did I record on Nov 10, 2026?")).isEqualTo("8 hours recorded for Tue Nov 10, 2026." + RESUBMITTED);
        // The latest entry is awaiting approval: not approved yet, and said so.
        assertThat(ask("How many hours were approved on Nov 10, 2026?")).isEqualTo("0 approved hours recorded for "
                + "Tue Nov 10, 2026. 8 hours for this date are submitted and awaiting approval.");
        // A draft only: entered, not submitted.
        assertThat(ask("How many hours did I submit for Nov 16, 2026?")).isEqualTo("0 submitted hours recorded for "
                + "Mon Nov 16, 2026. Your draft has 5 hours for this date that haven't been submitted yet.");
        assertThat(ask("How many hours did I record on Nov 16, 2026?")).isEqualTo("5 hours recorded for Mon Nov 16, 2026.");
    }

    @Test
    void aRejectedLatestEntryFallsBackToTheApprovedOneOrCountsNothing() {
        assertThat(ask("How many hours did I record on Nov 3, 2026?")).isEqualTo("7 hours recorded for Tue Nov 3, 2026. "
                + "The latest submission for this date was rejected, so the earlier approved entry is counted.");
        assertThat(ask("How many hours did I record on Nov 4, 2026?")).isEqualTo("0 hours recorded for Wed Nov 4, 2026. "
                + "The latest submission for this date was rejected, so nothing counts until it is corrected and resubmitted.");
    }

    @Test
    void aDateNoTimesheetCoversIsSaidSo() {
        assertThat(ask("How many hours did I submit for Dec 1, 2025?")).startsWith("I don't see a timesheet of yours covering");
    }

    @Test
    void dateFormatsGiveTheSameAnswer() {
        for (String date : List.of("Nov 10, 2026", "November 10, 2026", "2026-11-10", "11/10/2026", "10 Nov 2026")) {
            assertThat(ask("How many hours did I submit for " + date + "?")).as(date)
                    .isEqualTo("8 submitted hours recorded for Tue Nov 10, 2026." + RESUBMITTED);
        }
    }

    @Test
    void theSubmissionsTheApprovedOneAndTheCountingRuleAreOneFollowUpAway() {
        Chat asha = new Chat(ASHA);
        assertThat(asha.ask("How many hours did I submit for Nov 10, 2026?")).isEqualTo("8 submitted hours recorded for Tue Nov 10, 2026." + RESUBMITTED);
        String list = asha.ask("Show the timesheets for that date.");
        assertThat(list).startsWith("Your timesheets covering").contains("Mon Nov 9, 2026 to Sun Nov 15, 2026: is approved",
                "Mon Nov 9, 2026 to Wed Nov 11, 2026: is submitted", "Mon Nov 9, 2026 to Wed Nov 11, 2026: was rejected");
        assertThat(asha.ask("Which one was approved?")).startsWith("Your approved timesheets covering")
                .contains("Mon Nov 9, 2026 to Sun Nov 15, 2026").doesNotContain("was rejected");

        Chat why = new Chat(ASHA);
        why.ask("How many hours did I submit for Nov 10, 2026?");
        assertThat(why.ask("Why were these hours counted once?")).startsWith("Each work date is counted once")
                .contains("the latest submission's entry counts", "Tue Nov 10, 2026 is on 3 of your submissions");
    }

    @Test
    void accessIsUnchanged() {
        WaviEvaluationWorld admin = new WaviEvaluationWorld(WaviEvaluationWorld.ADMIN, new WaviEvaluationWorld.Options());
        assertThat(admin.service.chat(admin.actor, "How many hours did I submit for Nov 10, 2026?", List.of(),
                StampedHistory.SESSION).message()).contains("Company Admin accounts don't submit timesheets");
        Chat asha = new Chat(ASHA);
        assertThat(asha.ask("How many hours did Ravi Teja submit for Nov 10, 2026?")).isEqualTo(MyHrChatService.OTHER_PERSON_REPLY);
        verify(asha.world.employees, never()).list(anyString());
    }
}
