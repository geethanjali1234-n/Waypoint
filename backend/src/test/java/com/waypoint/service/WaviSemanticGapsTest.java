package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrChatHistoryMessage;
import com.waypoint.dto.myhr.MyHrChatResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Phase 2 (2026-11-17) root causes behind the Northwind adversarial misses, each asserted in wordings that are NOT
 * the holdout's, so the holdout stays an independent measure: status vocabulary, a date naming a week, own requests
 * named by their subject, phrasal navigation and how-to-obtain wording, "send in"-style submission verbs, and the
 * user as the reviewer who decided. Every case is also asserted with the router unavailable and no model call.
 * WaviEvaluationWorld records; today is Thu Nov 5, 2026.
 */
class WaviSemanticGapsTest {

    private static final WaviEvaluationWorld.Person ASHA = WaviEvaluationWorld.EMPLOYEE;
    private static final WaviEvaluationWorld.Person HR = WaviEvaluationWorld.HR;
    private static final WaviEvaluationWorld.Person ADMIN = WaviEvaluationWorld.ADMIN;

    private static final class Chat {
        final WaviEvaluationWorld world;
        final List<MyHrChatHistoryMessage> history = new ArrayList<>();

        Chat(WaviEvaluationWorld.Person person, WaviEvaluationWorld.RouterBehaviour router) {
            WaviEvaluationWorld.Options options = new WaviEvaluationWorld.Options();
            if (router != null) options.semanticRouter = router;
            world = new WaviEvaluationWorld(person, options);
        }

        String ask(String message) {
            MyHrChatResponse response = world.service.chat(world.actor, message, List.copyOf(history), StampedHistory.SESSION);
            history.add(new MyHrChatHistoryMessage("user", message, response.historyProvenance().user()));
            history.add(new MyHrChatHistoryMessage("assistant", response.message(), response.historyProvenance().assistant()));
            return response.message();
        }
    }

    private static String ask(WaviEvaluationWorld.Person person, String... turns) {
        String plain = null;
        for (WaviEvaluationWorld.RouterBehaviour router : new WaviEvaluationWorld.RouterBehaviour[]{null,
                WaviEvaluationWorld.RouterBehaviour.UNAVAILABLE}) {
            Chat chat = new Chat(person, router);
            String last = null;
            for (String turn : turns) last = chat.ask(turn);
            verify(chat.world.claude, never()).chat(anyString(), anyList());
            verify(chat.world.claude, never()).chat(any(MyHrGroundedPrompt.class));
            if (plain == null) plain = last;
            else assertThat(last).as("same answer with the router unavailable: " + String.join(" / ", turns)).isEqualTo(plain);
        }
        return plain;
    }

    @Test
    void statusVocabularyMapsToTheRecordedStatuses() {
        // Own records: Oct 12-18 rejected, Oct 12-14 resubmission waiting, Nov 2-8 waiting, others approved.
        assertThat(ask(ASHA, "which of my timesheets got kicked back?")).startsWith("Your rejected timesheets (1):")
                .contains("Mon Oct 12, 2026 to Sun Oct 18, 2026");
        assertThat(ask(ASHA, "do I have any timesheets that need corrections")).startsWith("Your rejected timesheets");
        assertThat(ask(ASHA, "is there anything I have to fix on my timesheets?")).contains("Mon Oct 12, 2026 to Sun Oct 18, 2026")
                .doesNotContain("General guidance");
        // HR: the team's rejections, from the review scope.
        assertThat(ask(HR, "show my team's timesheets that were sent back")).contains("rejected (waiting for the employee to correct and resubmit)")
                .doesNotContain("Former Report");
        // "Approved or not" is a status question, not an approved-only filter.
        assertThat(ask(ASHA, "was my oct 12 week approved or not")).contains("Mon Oct 12, 2026 to Sun Oct 18, 2026: was rejected");
    }

    @Test
    void aDateBeforeWeekNamesTheRecordButTheWeekOfADateIsAPeriod() {
        assertThat(ask(ASHA, "what happened with my oct 19 week")).startsWith("Your timesheet covering Mon Oct 19, 2026:");
        assertThat(ask(ASHA, "hours for the week of oct 19")).startsWith("Hours recorded for the week of Mon Oct 19, 2026");
    }

    @Test
    void ownRequestsAreFoundByTheirSubjectInAnyWording() {
        // Wording with an established route keeps its more precise answer.
        assertThat(ask(ASHA, "any update on my offer letter request?")).contains("offer letter request is pending");
        assertThat(ask(ASHA, "where's my employment verification request")).contains("employment verification", "completed")
                .doesNotContain("Request a document");
        // A request named only by a document type no established route knows: the user's own document requests.
        assertThat(ask(ASHA, "where is my experience certificate request at")).startsWith("Your document requests (2):");
        // The world stubs no profile-change list, so the own list is empty: the point is that it is the user's list,
        // not how-to guidance.
        assertThat(ask(ASHA, "have I got a profile change request pending")).contains("open profile change request")
                .doesNotContain("Open Profile Change Requests and submit");
        Chat asha = new Chat(ASHA, null);
        assertThat(asha.ask("my document requests")).startsWith("Your document requests (2):");
        assertThat(asha.ask("when did I send that in?")).contains("requested Wed Oct 28, 2026");
        // Handling someone else's request is a reviewer workflow, never "how to get a document".
        // It is left to the router and the established workflow routes (it may reach the model), never pre-empted.
        assertThat(new Chat(HR, null).ask("how do I fulfil a document request"))
                .doesNotContain("Request a document. Select the document type");
        // A reviewer's "waiting for me" is their queue, never their own requests.
        assertThat(ask(HR, "are any document requests waiting on me?")).doesNotContain("Your document requests", "You don't have any");
    }

    @Test
    void navigationAndHowToObtainADocumentAreUnderstoodInAnyPhrasing() {
        assertThat(ask(ADMIN, "could you pull up the payroll readiness page for me")).contains("Payroll Readiness button");
        assertThat(ask(HR, "load my profile page please")).contains("My Profile button");
        // Without a page word, "show my profile" asks for the record, not the page.
        assertThat(ask(ASHA, "show my profile")).contains("WPT-1001");
        // A role without the page is told so by the established page route.
        assertThat(ask(ASHA, "bring up org settings")).doesNotContain("Organization Settings button");
        assertThat(ask(ASHA, "how can I get a salary certificate from HR?")).contains("Request a document");
        // A question that opens with a navigation verb but asks for data is not navigation.
        assertThat(ask(ASHA, "show me how many hours I logged last week")).contains("Hours recorded for last week");
        // "Where is my request" is a status question, not a how-to.
        assertThat(ask(ASHA, "where is my offer letter request at")).contains("offer letter request is pending")
                .doesNotContain("Request a document");
    }

    @Test
    void submissionVerbsAndTheReviewerAsDeciderAreRead() {
        assertThat(ask(ASHA, "when did I last turn in my timesheet?")).contains("2026-11-0");
        assertThat(WaviSpelling.correct("when did i hand in that sheet")).isEqualTo("when did i submit that sheet");
        // "I approved" = decisions the reviewer made; "my approved" = the reviewer's own records.
        assertThat(ask(HR, "which timesheets did I approve?")).doesNotContain("Your approved timesheets");
        assertThat(ask(HR, "show my approved timesheets")).startsWith("Your approved timesheets");
        // Employees decide nothing: their own approved records.
        assertThat(ask(ASHA, "what timesheets have I approved")).doesNotContain("you approved");
    }
}
