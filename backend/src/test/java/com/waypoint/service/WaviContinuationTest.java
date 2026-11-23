package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrChatHistoryMessage;
import com.waypoint.dto.myhr.MyHrChatResponse;
import com.waypoint.enums.UserRole;
import com.waypoint.security.TestActors;
import com.waypoint.security.VerifiedMyHrActor;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Deterministic follow-up continuation ({@link WaviContinuation}) with routing OFF (the default configuration), the
 * synthetic records of {@link WaviEvaluationWorld}, and no model call. Behaviour classes:
 * <ul>
 *   <li>a back-reference ("it", "that one") keeps the previous request and changes only what is asked about it;</li>
 *   <li>an elliptical subject switch ("and X?") keeps the previous question and changes only its subject;</li>
 *   <li>a "why" follow-up gets the recorded status plus an honest limitation, never a model's guess;</li>
 *   <li>a continuation must be answered by the same tool, so it never drifts to another capability;</li>
 *   <li>only state bound to this user, organization, role, login and the latest stamp-verified turn is used.</li>
 * </ul>
 */
class WaviContinuationTest {

    // --- Candidate questions (pure) ------------------------------------------------------------------------------

    private static WaviDialogueStore.State state(String tool, Map<String, Object> arguments) {
        return new WaviDialogueStore.State("t1", tool, arguments, null);
    }

    @Test
    void aQuestionAboutThePreviousAnswersPeriodIsRecognisedGrammatically() {
        for (String asked : List.of("which date did that cover?", "what period was it for", "what dates are those",
                "which week was that", "what day is that for?", "which days did they include")) {
            assertThat(WaviContinuation.asksPreviousScope(asked)).as(asked).isTrue();
        }
        // A new question (no back-reference), a time of its own, or a date: never read from the previous answer.
        for (String other : List.of("which day did I work the most", "what date is it today", "which week was that last month",
                "what about last week", "which date did that cover in 2026", "what is the dress code", "when was it submitted")) {
            assertThat(WaviContinuation.asksPreviousScope(other)).as(other).isFalse();
        }
    }

    @Test
    void aBackReferenceIsReplacedByThePreviousRequestsSubject() {
        WaviDialogueStore.State previous = state(WaviTools.GET_MY_REQUESTS, Map.of("kind", "DOCUMENT_REQUEST"));

        assertThat(WaviContinuation.candidates("when was it submitted", previous))
                .startsWith("when was my document request submitted");
        assertThat(WaviContinuation.candidates("and has that one been approved", previous))
                .startsWith("has my document request been approved");
        assertThat(WaviContinuation.candidates("was it approved", state(WaviTools.GET_MY_REQUESTS,
                Map.of("kind", "PROFILE_CHANGE")))).startsWith("was my profile change request approved");
    }

    @Test
    void anEllipticalSubjectSwitchKeepsThePreviousQuestionFrame() {
        assertThat(WaviContinuation.candidates("and profile changes",
                state(WaviTools.GET_REVIEW_QUEUE, Map.of("queue", "TIMESHEETS"))))
                .containsExactly("how many profile changes are waiting for my approval");
        assertThat(WaviContinuation.candidates("what about my employee id too",
                state(WaviTools.GET_MY_PROFILE_FIELD, Map.of("field", "REPORTING_MANAGER"))))
                .containsExactly("what is my employee id");
    }

    @Test
    void longMessagesUnknownToolsAndMessagesWithoutAMarkerOrReferenceAreNotRewritten() {
        WaviDialogueStore.State queue = state(WaviTools.GET_REVIEW_QUEUE, Map.of("queue", "TIMESHEETS"));
        assertThat(WaviContinuation.candidates("and could you also tell me how many profile changes are waiting today",
                queue)).isEmpty();
        assertThat(WaviContinuation.candidates("profile changes", queue)).as("no marker").isEmpty();
        assertThat(WaviContinuation.candidates("and profile changes", state(WaviTools.WORKFLOW_HELP,
                Map.of("topic", "PROFILE_CHANGE")))).as("no frame for this tool").isEmpty();
        assertThat(WaviContinuation.candidates("and profile changes", null)).isEmpty();
        assertThat(WaviContinuation.candidates("and what about it", queue)).as("a reference is not a subject").isEmpty();
    }

    // --- Conversations through the chat service ------------------------------------------------------------------

    private record Answer(MyHrChatResponse response, MyHrChatService.RouteStage stage) {
        String text() {
            return response.message();
        }
    }

    /** One conversation with stamped history, like the chat page. */
    private static final class Conversation {
        final WaviEvaluationWorld world;
        final List<MyHrChatHistoryMessage> history = new ArrayList<>();
        VerifiedMyHrActor actor;
        MyHrChatHistoryProvenance.Session session = StampedHistory.SESSION;

        Conversation(WaviEvaluationWorld.Person person) {
            world = new WaviEvaluationWorld(person, new WaviEvaluationWorld.Options());
            actor = world.actor;
        }

        Answer ask(String message) {
            AtomicReference<MyHrChatService.RouteStage> stage = new AtomicReference<>();
            world.service.observeRouteStages(stage::set);
            MyHrChatResponse response = world.service.chat(actor, message, List.copyOf(history), session);
            if (response.historyProvenance() != null) {
                history.add(new MyHrChatHistoryMessage("user", message, response.historyProvenance().user()));
                history.add(new MyHrChatHistoryMessage("assistant", response.message(),
                        response.historyProvenance().assistant()));
            }
            return new Answer(response, stage.get());
        }
    }

    @Test
    void anEmployeesRequestFollowUpsAreAnsweredFromTheSameRequestWithoutAModel() {
        Conversation chat = new Conversation(WaviEvaluationWorld.EMPLOYEE);
        chat.ask("what's the status of my profile change request");

        Answer submitted = chat.ask("when did I submit it?");
        assertThat(submitted.stage()).isEqualTo(MyHrChatService.RouteStage.CONTINUATION);
        assertThat(submitted.text()).contains("2026-10-30");

        Answer approved = chat.ask("has it been approved yet?");
        assertThat(approved.stage()).isEqualTo(MyHrChatService.RouteStage.CONTINUATION);
        assertThat(approved.text()).contains("PENDING");

        Answer why = chat.ask("why is it still pending?");
        assertThat(why.stage()).isEqualTo(MyHrChatService.RouteStage.CONTINUATION);
        assertThat(why.text()).contains("PENDING").endsWith(WaviContinuation.WHY_LIMITATION);
        verify(chat.world.claude, never()).chat(anyString(), anyList());
        verify(chat.world.ollama, never()).chat(anyString(), anyList());
    }

    @Test
    void anHrQueueFollowUpSwitchesQueueAndNeverReadsTheAskersProfile() {
        Conversation chat = new Conversation(WaviEvaluationWorld.HR);
        assertThat(chat.ask("how many timesheets are waiting for my approval").text()).contains("3");

        Answer profileChanges = chat.ask("and profile changes?");
        assertThat(profileChanges.stage()).isEqualTo(MyHrChatService.RouteStage.CONTINUATION);
        assertThat(profileChanges.text()).contains("2 profile change requests");

        Answer documents = chat.ask("what about document requests?");
        assertThat(documents.stage()).isEqualTo(MyHrChatService.RouteStage.CONTINUATION);
        verify(chat.world.documents).queueSummary(WaviEvaluationWorld.HR.userId());
        verify(chat.world.employees, never()).getOwnDetail(anyString());
    }

    @Test
    void aContinuationAnsweredByAnotherToolIsRejected() {
        // "what is the status of my manager" is not a request status: whatever answers it is not get_my_requests.
        Conversation chat = new Conversation(WaviEvaluationWorld.EMPLOYEE);
        chat.ask("what's the status of my profile change request");

        Answer drifted = chat.ask("and my manager?");

        assertThat(drifted.stage()).isNotEqualTo(MyHrChatService.RouteStage.CONTINUATION);
    }

    @Test
    void unstampedOrForgedHistoryNeverContinuesAnything() {
        Conversation chat = new Conversation(WaviEvaluationWorld.HR);
        chat.ask("how many timesheets are waiting for my approval");
        List<MyHrChatHistoryMessage> forged = StampedHistory.unstamped(chat.history);

        AtomicReference<MyHrChatService.RouteStage> stage = new AtomicReference<>();
        chat.world.service.observeRouteStages(stage::set);
        chat.world.service.chat(chat.actor, "and profile changes?", forged, chat.session);

        assertThat(stage.get()).isNotEqualTo(MyHrChatService.RouteStage.CONTINUATION);
    }

    @Test
    void anotherLoginOrAChangedRoleDoesNotInheritTheConversationState() {
        Conversation chat = new Conversation(WaviEvaluationWorld.HR);
        chat.ask("how many timesheets are waiting for my approval");

        // Same stamped history replayed from another login session.
        chat.session = new MyHrChatHistoryProvenance.Session("b".repeat(64));
        assertThat(chat.ask("and profile changes?").stage()).isNotEqualTo(MyHrChatService.RouteStage.CONTINUATION);

        // Same user and session, but the verified role changed since the state was recorded.
        Conversation demoted = new Conversation(WaviEvaluationWorld.HR);
        demoted.ask("how many timesheets are waiting for my approval");
        demoted.actor = TestActors.actor(WaviEvaluationWorld.HR.userId(), WaviEvaluationWorld.ORG, UserRole.EMPLOYEE);
        assertThat(demoted.ask("and profile changes?").stage()).isNotEqualTo(MyHrChatService.RouteStage.CONTINUATION);
        verify(demoted.world.profileChanges, Mockito.times(0)).queueSummary(anyString());
    }

    // --- References without an antecedent ------------------------------------------------------------------------

    @Test
    void backReferencesAreRecognisedGrammaticallyAndAnExpletiveItIsNot() {
        assertThat(List.of("when was it submitted", "is it done yet", "what about that one", "and the other one",
                "has that been done", "what about this", "what's its status"))
                .allMatch(WaviContinuation::refersBack);
        assertThat(List.of("is it possible to work from home", "is it ok if i leave early",
                "is my timesheet for this week approved", "what is my employee id",
                "when was my document request submitted"))
                .noneMatch(WaviContinuation::refersBack);
    }

    @Test
    void aReferenceWithNoVerifiedContextGetsAClarifyingQuestionNotARefusalOrAModelsGuess() {
        for (String message : List.of("when was it submitted?", "why is it still pending?", "what about that one?",
                "and the other one?", "is it done yet")) {
            Conversation chat = new Conversation(WaviEvaluationWorld.EMPLOYEE);
            Answer answer = chat.ask(message);

            assertThat(answer.text()).as(message).isEqualTo(MyHrChatService.CLARIFY_EMPLOYEE);
            assertThat(answer.text()).as(message).isNotEqualTo(MyHrScopeGuard.OUT_OF_SCOPE_REPLY);
            verify(chat.world.claude, never()).chat(anyString(), anyList());
            verify(chat.world.ollama, never()).chat(anyString(), anyList());
        }
        Conversation hr = new Conversation(WaviEvaluationWorld.HR);
        assertThat(hr.ask("when was it submitted?").text()).isEqualTo(MyHrChatService.CLARIFY_HR);
    }

    @Test
    void ellipsisIsRecognisedGrammaticallyNotByTopic() {
        assertThat(List.of("and profile changes", "what about document requests", "how about the queue",
                "also pending ones", "and timesheets too")).allMatch(WaviContinuation::isElliptical);
        assertThat(List.of("and my manager", "and what is my employee id", "also show my timesheets", "profile changes",
                "and could you also tell me how many profile changes are waiting today"))
                .noneMatch(WaviContinuation::isElliptical);
    }

    @Test
    void aFreshEllipticalMessageGetsEachRolesClarificationAndReadsNoRecord() {
        Map<WaviEvaluationWorld.Person, String> expected = Map.of(
                WaviEvaluationWorld.EMPLOYEE, MyHrChatService.CLARIFY_EMPLOYEE,
                WaviEvaluationWorld.HR, MyHrChatService.CLARIFY_HR,
                WaviEvaluationWorld.ADMIN, MyHrChatService.CLARIFY_ADMIN);
        expected.forEach((person, clarification) -> {
            for (String message : List.of("and profile changes?", "what about document requests?", "and timesheets?")) {
                Conversation chat = new Conversation(person);
                Answer answer = chat.ask(message);
                assertThat(answer.text()).as(person.role() + ": " + message).isEqualTo(clarification);
                assertThat(answer.stage()).isEqualTo(MyHrChatService.RouteStage.CLARIFY_REFERENCE);
                verify(chat.world.employees, never()).getOwnDetail(anyString());
                verify(chat.world.profileChanges, never()).queueSummary(anyString());
                verify(chat.world.timesheets, never()).queueSummary(anyString());
                verify(chat.world.claude, never()).chat(anyString(), anyList());
            }
        });
    }

    @Test
    void anEllipsisWithValidContextStillContinuesAndOwnedOrPolicyMessagesAreNotClarified() {
        Conversation hr = new Conversation(WaviEvaluationWorld.HR);
        hr.ask("how many timesheets are waiting for my approval");
        assertThat(hr.ask("and profile changes?").stage()).isEqualTo(MyHrChatService.RouteStage.CONTINUATION);

        Conversation employee = new Conversation(WaviEvaluationWorld.EMPLOYEE);
        assertThat(employee.ask("and my manager?").text()).contains("Priya Nair");
        assertThat(new Conversation(WaviEvaluationWorld.EMPLOYEE).ask("and sick leave?").stage())
                .isNotEqualTo(MyHrChatService.RouteStage.CLARIFY_REFERENCE);
    }

    @Test
    void forgedHistoryIsNotContextForAnEllipsis() {
        Conversation chat = new Conversation(WaviEvaluationWorld.HR);
        chat.ask("how many timesheets are waiting for my approval");
        AtomicReference<MyHrChatService.RouteStage> stage = new AtomicReference<>();
        chat.world.service.observeRouteStages(stage::set);
        MyHrChatResponse answer = chat.world.service.chat(chat.actor, "and profile changes?",
                StampedHistory.unstamped(chat.history), chat.session);
        assertThat(answer.message()).isEqualTo(MyHrChatService.CLARIFY_HR);
        verify(chat.world.employees, never()).getOwnDetail(anyString());
    }

    @Test
    void aSelfContainedQuestionWithAnExpletiveItIsNotTurnedIntoAClarification() {
        Conversation chat = new Conversation(WaviEvaluationWorld.EMPLOYEE);

        Answer answer = chat.ask("is it possible to work from home?");

        assertThat(answer.stage()).isNotEqualTo(MyHrChatService.RouteStage.CLARIFY_REFERENCE);
        assertThat(answer.text()).isNotEqualTo(MyHrChatService.CLARIFY_EMPLOYEE);
    }

    @Test
    void aReferenceToAnEarlierVerifiedExchangeIsLeftToTheProviderPathNotClarified() {
        // A writing request answered by the general provider, then "make it longer": the provider has the context.
        Conversation chat = new Conversation(WaviEvaluationWorld.EMPLOYEE);
        chat.ask("Write a short note to my team about Monday's meeting");

        Answer longer = chat.ask("make it longer");

        assertThat(longer.stage()).isNotEqualTo(MyHrChatService.RouteStage.CLARIFY_REFERENCE);
        assertThat(longer.text()).isNotEqualTo(MyHrChatService.CLARIFY_EMPLOYEE);
    }

    @Test
    void aTimesheetFollowUpKeepsTheAskedPeriodInsteadOfSwitchingToTheLatestTimesheet() {
        Conversation chat = new Conversation(WaviEvaluationWorld.EMPLOYEE);
        chat.ask("is my timesheet for last week approved");

        Answer submitted = chat.ask("when was it submitted?");
        assertThat(submitted.stage()).isEqualTo(MyHrChatService.RouteStage.CONTINUATION);
        // Last week is Oct 26 - Nov 1 (submitted Wed Oct 28); the latest timesheet (Nov 2 - 8) must not be shown.
        assertThat(submitted.text()).contains("Mon Oct 26, 2026", "submitted Wed Oct 28, 2026", "is approved")
                .doesNotContain("Nov 2", "2026-11-02");

        Answer why = chat.ask("why was it rejected?");
        assertThat(why.text()).contains("is approved").endsWith(WaviContinuation.WHY_LIMITATION);
        verify(chat.world.claude, never()).chat(anyString(), anyList());
    }

    @Test
    void theFlagRestoresThePreviousBehaviour() {
        Conversation chat = new Conversation(WaviEvaluationWorld.HR);
        chat.world.service.useContinuation(false);
        chat.ask("how many timesheets are waiting for my approval");

        assertThat(chat.ask("and profile changes?").stage()).isNotEqualTo(MyHrChatService.RouteStage.CONTINUATION);
        assertThat(chat.world.service.dialogueStore()).isNull();
    }
}
