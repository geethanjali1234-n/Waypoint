package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrChatHistoryMessage;
import com.waypoint.dto.myhr.MyHrChatResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Complete Employee, HR and Company Admin conversations through the question frame: the answer has the TYPE asked
 * for (a person, a count of people, the latest record, a reason that is actually recorded), follow-ups keep their
 * context, and nothing outside the verified actor's scope is read or shown. Synthetic records and mocked services
 * (WaviEvaluationWorld); the router is unavailable, so no model decides anything. Wording here is new, not taken
 * from the evaluation datasets.
 */
class WaviAnswerTypeConversationTest {

    private static final class Chat {
        final WaviEvaluationWorld world;
        final List<MyHrChatHistoryMessage> history = new ArrayList<>();

        Chat(WaviEvaluationWorld.Person person) {
            var options = new WaviEvaluationWorld.Options();
            options.semanticRouter = WaviEvaluationWorld.RouterBehaviour.UNAVAILABLE;
            options.toolLayer = true;
            world = new WaviEvaluationWorld(person, options);
        }

        MyHrChatResponse response(String question) {
            MyHrChatResponse response = world.service.chat(world.actor, question, List.copyOf(history), StampedHistory.SESSION);
            history.add(new MyHrChatHistoryMessage("user", question, response.historyProvenance().user()));
            history.add(new MyHrChatHistoryMessage("assistant", response.message(), response.historyProvenance().assistant()));
            return response;
        }

        String ask(String question) {
            return response(question).message();
        }
    }

    // --- Employee: timesheets --------------------------------------------------------------------------------------

    @Test
    void theLatestSubmissionIsSelectedFromTheSameDatesRecordsNotFilteredByStatus() {
        Chat asha = new Chat(WaviEvaluationWorld.EMPLOYEE);
        assertThat(asha.ask("list the timesheets that include Oct 13")).contains("Mon Oct 12, 2026 to Wed Oct 14, 2026",
                "Mon Oct 12, 2026 to Sun Oct 18, 2026");
        assertThat(asha.ask("which of those did I send in last?"))
                .startsWith("The most recently submitted one is Mon Oct 12, 2026 to Wed Oct 14, 2026")
                .contains("submitted Tue Oct 20, 2026", "2 separate submissions");
        // The reason follow-up keeps the same date's records and states only what is recorded.
        String why = asha.ask("why?");
        assertThat(why).contains("Mon Oct 12, 2026 to Wed Oct 14, 2026", "rejected", "no reviewer comment was recorded",
                "not why a review hasn't happened yet").doesNotContain("because", "Nov 2");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Why is my timesheet still pending?", "how come my timesheet hasn't been approved yet?",
            "why is my timesheet stuck waiting for review"})
    void aWaitingTimesheetsReasonIsWhatWaypointRecordsNeverAnInventedOne(String question) {
        String answer = new Chat(WaviEvaluationWorld.EMPLOYEE).ask(question);
        assertThat(answer).contains("waiting for review", "Mon Nov 2, 2026 to Sun Nov 8, 2026",
                "not why a review hasn't happened yet").doesNotContain("because", "Ravi");
    }

    @Test
    void hoursForOneDateAreTheDaysEntriesWithResubmissionsKeptApart() {
        Chat asha = new Chat(WaviEvaluationWorld.EMPLOYEE);
        String answer = asha.ask("how many hours count for Oct 13?");
        assertThat(answer).contains("2026-10-13", "8 hours (REJECTED", "8 hours (SUBMITTED", "not added together")
                .doesNotContain("16 hours");
        assertThat(asha.ask("and for Nov 3?")).contains("2026-11-03", "8 hours (SUBMITTED");
    }

    // --- Employee: profile, manager, requests ----------------------------------------------------------------------

    @Test
    void indirectManagerQuestionsGetThePartAskedForAndAnHonestReason() {
        Chat asha = new Chat(WaviEvaluationWorld.EMPLOYEE);
        assertThat(asha.ask("what's my supervisor's surname")).isEqualTo(
                "Your reporting manager's last name is Nair (full name: Priya Nair).");
        assertThat(asha.ask("and her first name?")).contains("first name is Priya");
        assertThat(asha.ask("why is she my manager?")).contains("Waypoint records Priya Nair as your reporting manager",
                "not the reason it was made");
        // A typed name that is not the recorded manager is never confirmed.
        assertThat(new Chat(WaviEvaluationWorld.EMPLOYEE).ask("Why do I report to Ravi Teja?"))
                .contains("doesn't show Ravi Teja as your reporting manager", "Priya Nair");
    }

    @ParameterizedTest
    @ValueSource(strings = {"What does Waypoint know about me?", "what info do you hold on me",
            "show me the data you keep about me"})
    void aBroadSelfServiceQuestionGetsTheProfileSummaryAndQuantifiersEndIt(String question) {
        Chat asha = new Chat(WaviEvaluationWorld.EMPLOYEE);
        assertThat(asha.ask(question)).contains("Asha Rao", "WPT-1001", "Senior Data Analyst");
        assertThat(asha.ask("everything")).contains("Asha Rao").doesNotContain("Which one");
        verify(asha.world.employees, never()).getOwnDetail(WaviEvaluationWorld.COLLEAGUE.userId());
    }

    @Test
    void anOpenRequestFollowUpListsOnlyTheOpenOnes() {
        Chat asha = new Chat(WaviEvaluationWorld.EMPLOYEE);
        asha.ask("any update on my document requests?");
        assertThat(asha.ask("which one is still open?")).contains("Offer letter").doesNotContain("Employment verification");
        assertThat(asha.ask("where can I see it?")).contains("Document Requests");
    }

    // --- Policy grounding and provenance ---------------------------------------------------------------------------

    @Test
    void whereAnAnswerCameFromNamesTheReauthorizedSourceFirst() {
        Chat asha = new Chat(WaviEvaluationWorld.EMPLOYEE);
        MyHrChatResponse answer = asha.response("how many vacation days do I get each month");
        assertThat(answer.message()).contains("1.5 days");
        assertThat(answer.citations()).isNotEmpty();
        MyHrChatResponse source = asha.response("where did you find that?");
        assertThat(source.message()).startsWith("Source: Annual Leave Policy - an approved company policy, current version");
        assertThat(source.citations()).isNotEmpty();
        assertThat(source.actions()).isEmpty();
        // A record answer's provenance is the user's own records, never a policy.
        Chat records = new Chat(WaviEvaluationWorld.EMPLOYEE);
        records.ask("did I submit my Nov 3 timesheet");
        assertThat(records.ask("where did you get that from?")).contains("from your Waypoint records (Timesheets)");
    }

    @Test
    void anEmployeeCannotListPolicyDocumentsAndTheCatalogIsNeverRead() {
        Chat asha = new Chat(WaviEvaluationWorld.EMPLOYEE);
        assertThat(asha.ask("which policies can I access?")).isEqualTo(MyHrChatService.POLICY_CATALOG_EMPLOYEE_REPLY);
        assertThat(asha.ask("which HR-only policies are available?")).doesNotContain("Severance");
        verify(asha.world.knowledgeManagement, never()).list(any());
    }

    // --- HR: answer types over the same review queues --------------------------------------------------------------

    @Test
    void whoHowManyPeopleAndWhichLatestAreDifferentAnswersOverTheSameScopedQueue() {
        Chat hari = new Chat(WaviEvaluationWorld.HR);
        assertThat(hari.ask("who's waiting on my approval right now?")).startsWith("2 people are waiting for your review:")
                .contains("Asha Rao: 1 timesheet", "Ravi Teja: 2 timesheets").doesNotContain("Former Report");
        assertThat(new Chat(WaviEvaluationWorld.HR).ask("how many people have timesheets pending my approval?"))
                .startsWith("2 employees have 3 timesheets waiting for your review").doesNotContain("Former Report");

        Chat team = new Chat(WaviEvaluationWorld.HR);
        assertThat(team.ask("list my team's timesheets that are awaiting review"))
                .contains("3 submitted (awaiting review) timesheets", "Asha Rao", "Ravi Teja")
                .doesNotContain("Former Report", "Hari Kumar -");
        assertThat(team.ask("which of them came in most recently?")).contains("Asha Rao's",
                "Mon Nov 2, 2026 to Sun Nov 8, 2026");
        assertThat(team.ask("how many hours is it?")).contains("40 hours");
    }

    @Test
    void anHrUsersOwnReportingRelationshipIsNotAPolicySearch() {
        Chat hari = new Chat(WaviEvaluationWorld.HR);
        assertThat(hari.ask("Why do I report to Meera Iyer?")).contains("Waypoint records Meera Iyer as your reporting manager");
        verify(hari.world.retrieval, never()).retrieve(any(), anyString());
        verify(hari.world.claude, never()).chat(anyString(), anyList());
    }

    @Test
    void hrSeesTheHrOnlyCatalogTitlesNotContent() {
        Chat hari = new Chat(WaviEvaluationWorld.HR);
        String catalog = hari.ask("Which HR-only policies can I access?");
        assertThat(catalog).contains("Severance Guidelines").doesNotContain("Annual Leave", "2 weeks of base pay");
    }

    // --- Company Admin ---------------------------------------------------------------------------------------------

    @Test
    void anAdminsOrganizationCountsLeadToTheirNames() {
        Chat anil = new Chat(WaviEvaluationWorld.ADMIN);
        anil.ask("how many timesheets are awaiting approval company-wide");
        assertThat(anil.ask("who submitted them?")).contains("Asha Rao", "Ravi Teja").doesNotContain("Which one");

        Chat people = new Chat(WaviEvaluationWorld.ADMIN);
        people.ask("how many employees are in the company?");
        assertThat(people.ask("who are they?")).contains("Asha Rao", "Ravi Teja", "Hari Kumar");
    }

    @Test
    void adminKnowledgeBaseGuidanceIsTheRealPageFlowAndTheCatalogIsByAudience() {
        Chat anil = new Chat(WaviEvaluationWorld.ADMIN);
        assertThat(anil.ask("how do I upload a new policy to the knowledge base?"))
                .contains("Add policy", "Ingest", "Activate", "Replace file", "Revoke");
        String visible = anil.ask("which policies are employees able to see?");
        assertThat(visible).contains("Annual Leave Policy", "Sick Leave Policy").doesNotContain("Severance",
                "Travel Policy (draft)");
        assertThat(anil.ask("did I submit my timesheet?")).isEqualTo(MyHrChatService.ADMIN_NO_OWN_TIMESHEET_STATUS);
    }

    // --- Authorization ---------------------------------------------------------------------------------------------

    @Test
    void anEmployeesReviewerShapedQuestionsReadNoQueueAndNameNoOne() {
        Chat asha = new Chat(WaviEvaluationWorld.EMPLOYEE);
        for (String question : List.of("who is waiting for my approvals?", "show my team's pending timesheets",
                "how many employees have timesheets awaiting review?")) {
            assertThat(asha.ask(question)).as(question).doesNotContain("Ravi", "Former Report", "Hari Kumar");
        }
        verify(asha.world.profileChanges, never()).listForAdmin(anyString());
        verify(asha.world.documents, never()).list(anyString());
        verify(asha.world.employees, never()).list(anyString());
    }
}
