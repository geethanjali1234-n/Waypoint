package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrChatHistoryMessage;
import com.waypoint.dto.myhr.MyHrChatResponse;
import com.waypoint.enums.UserRole;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDate;
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

/**
 * The read-only capabilities added for organization-level use, through the real chat service with the synthetic
 * records of {@link WaviEvaluationWorld} (no model is called; where routing is on, the router is a Mockito mock).
 * Behaviour classes, each with several wordings and every role:
 * <ul>
 *   <li>review lists: who and what is waiting, in exactly the queue count's scope, names and dates only;</li>
 *   <li>follow-ups that ask "who" after a count, and the same question with no context;</li>
 *   <li>the user's own hours by period, with overlapping timesheets never double-counted;</li>
 *   <li>name parts, people aggregates, timesheet-period concepts, workflow topics;</li>
 *   <li>forbidden access: an Employee never gets anyone's list, a reviewer never sees out-of-scope records or values.</li>
 * </ul>
 */
class WaviEnterpriseCapabilitiesTest {

    // --- Helpers ------------------------------------------------------------------------------------------------

    private static final class Chat {
        final WaviEvaluationWorld world;
        final List<MyHrChatHistoryMessage> history = new ArrayList<>();
        MyHrChatService.RouteStage lastStage;

        Chat(WaviEvaluationWorld.Person person) {
            this(new WaviEvaluationWorld(person, new WaviEvaluationWorld.Options()));
        }

        Chat(WaviEvaluationWorld world) {
            this.world = world;
        }

        String ask(String message) {
            AtomicReference<MyHrChatService.RouteStage> stage = new AtomicReference<>();
            world.service.observeRouteStages(stage::set);
            MyHrChatResponse response = world.service.chat(world.actor, message, List.copyOf(history),
                    StampedHistory.SESSION);
            lastStage = stage.get();
            history.add(new MyHrChatHistoryMessage("user", message, response.historyProvenance().user()));
            history.add(new MyHrChatHistoryMessage("assistant", response.message(),
                    response.historyProvenance().assistant()));
            return response.message();
        }
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

    private static void assertNoRestrictedContent(String reply) {
        assertThat(reply).doesNotContain(WaviEvaluationWorld.REQUESTED_VALUE_SENTINEL, "old value",
                WaviEvaluationWorld.FORMER_REPORT.name(), "8 hours", "40 hours");
    }

    // --- Review lists -------------------------------------------------------------------------------------------

    @Test
    void anHrReviewerAskingWhoIsWaitingGetsTheAssignedListInTheCountsScope() {
        for (String question : List.of("who needs my approval for timesheets?",
                "how many timesheets are waiting for my approval, and who are they from?")) {
            Chat hr = routed(WaviEvaluationWorld.HR, "get_review_queue", "{\"queue\":\"TIMESHEETS\",\"detail\":\"LIST\"}");
            String reply = hr.ask(question);

            // 3 submitted timesheets (the count mock), from 2 people; the former report is out of scope.
            assertThat(reply).as(question).contains("3 timesheets from 2 people", "Asha Rao", "Ravi Teja")
                    .doesNotContain("Oct 19");
            assertNoRestrictedContent(reply);
        }
    }

    @Test
    void aCountFollowedByWhoQuestionsListsTheSameQueueWithoutAModel() {
        for (String followUp : List.of("for whom?", "who submitted them?", "which ones?", "whose are they")) {
            Chat hr = new Chat(WaviEvaluationWorld.HR);
            assertThat(hr.ask("how many timesheets are waiting for my approval")).contains("3");

            String listed = hr.ask(followUp);

            assertThat(hr.lastStage).as(followUp).isEqualTo(MyHrChatService.RouteStage.CONTINUATION);
            assertThat(listed).as(followUp).contains("3 timesheets from 2 people", "Asha Rao", "Ravi Teja");
            assertNoRestrictedContent(listed);
            verify(hr.world.claude, never()).chat(anyString(), anyList());
        }
    }

    @Test
    void profileChangeAndDocumentQueuesListOnlyWhatIsAssignedAndNeverTheRequestedValues() {
        Chat hr = new Chat(WaviEvaluationWorld.HR);
        hr.ask("how many profile change requests are awaiting my review");
        String changes = hr.ask("who are they from?");
        assertThat(changes).contains("2 profile change requests from 2 people", "address change", "phone number change");
        assertNoRestrictedContent(changes);

        Chat hrDocuments = routed(WaviEvaluationWorld.HR, "get_review_queue",
                "{\"queue\":\"DOCUMENT_REQUESTS\",\"detail\":\"LIST\"}");
        String documents = hrDocuments.ask("which document requests are waiting on me?");
        // Only those addressed to this HR user (2 of 3).
        assertThat(documents).contains("2 document requests from 2 people", "Employment verification letter");
        assertNoRestrictedContent(documents);
    }

    @Test
    void aCompanyAdminListIsOrganizationWide() {
        Chat admin = routed(WaviEvaluationWorld.ADMIN, "get_review_queue", "{\"queue\":\"TIMESHEETS\",\"detail\":\"LIST\"}");
        String reply = admin.ask("who has submitted timesheets that still need approval?");
        assertThat(reply).contains("3 timesheets from 2 people", "waiting for review in your organization");
    }

    @Test
    void anEmployeeNeverGetsAReviewListWhateverTheWordingOrRouterDecision() {
        Chat routedEmployee = routed(WaviEvaluationWorld.EMPLOYEE, "get_review_queue",
                "{\"queue\":\"TIMESHEETS\",\"detail\":\"LIST\"}");
        assertThat(routedEmployee.ask("who needs my approval for timesheets?"))
                .isEqualTo(MyHrChatService.TIMESHEET_QUEUE_REVIEWERS_ONLY);
        verify(routedEmployee.world.timesheets, never()).listForUser(anyString());

        Chat employee = new Chat(WaviEvaluationWorld.EMPLOYEE);
        employee.ask("what's the status of my profile change request");
        String reply = employee.ask("for whom?");
        assertThat(reply).doesNotContain("Ravi Teja");
        verify(employee.world.profileChanges, never()).listForAdmin(anyString());
    }

    @Test
    void aWhoQuestionWithNoEarlierCountAsksWhichRecordIsMeant() {
        for (WaviEvaluationWorld.Person person : List.of(WaviEvaluationWorld.EMPLOYEE, WaviEvaluationWorld.HR,
                WaviEvaluationWorld.ADMIN)) {
            Chat chat = new Chat(person);
            String reply = chat.ask("for whom?");
            assertThat(chat.lastStage).as(person.role().name()).isEqualTo(MyHrChatService.RouteStage.CLARIFY_REFERENCE);
            assertThat(reply).startsWith("Which one do you mean");
            verify(chat.world.timesheets, never()).listForUser(anyString());
        }
    }

    // --- Own hours ----------------------------------------------------------------------------------------------

    @Test
    void ownHoursAreAnsweredForAPeriodAndAStatusQuestionKeepsItsStatusAnswer() {
        Chat employee = new Chat(WaviEvaluationWorld.EMPLOYEE);
        // Last week is Oct 26 - Nov 1: 5 weekdays of 8 hours.
        String hours = employee.ask("how many hours did I log last week?");
        assertThat(hours).contains("Hours recorded for last week", "40 hours on 5 days");

        String status = new Chat(WaviEvaluationWorld.EMPLOYEE).ask("did I submit my hours for last week?");
        assertThat(status).doesNotContain("Hours recorded").contains("approved");
    }

    @Test
    void overlappingTimesheetsAreListedSeparatelyAndSharedDaysAreNeverAddedTwice() {
        Chat employee = routed(WaviEvaluationWorld.EMPLOYEE, "get_my_timesheets",
                "{\"period\":\"DAY\",\"date\":\"2026-10-12\",\"detail\":\"HOURS\"}");
        String reply = employee.ask("oct 12 timesheet hours");
        // "oct 12 timesheet" identifies the records covering Oct 12 (2026-11-16 audit): the Oct 12 week (rejected)
        // and the Oct 12-14 resubmission are each listed with their own totals and their Oct 12 hours, never summed.
        assertThat(reply).contains("2 timesheets of yours cover Mon Oct 12, 2026",
                "Mon Oct 12, 2026 to Sun Oct 18, 2026: was rejected", "40 hours on 5 days in total, 8 hours entered on Mon Oct 12, 2026",
                "Mon Oct 12, 2026 to Wed Oct 14, 2026: is submitted", "24 hours on 3 days in total, 8 hours entered on Mon Oct 12, 2026",
                "appears on 2 separate submissions, so their hours for that day aren't added together").doesNotContain("16 hours");
        // Hours ON the day, asked without identifying a record: one answer. Both submissions hold 8 hours (never
        // summed); the counted one is a resubmission after a rejection, which is the one note that applies.
        assertThat(new Chat(WaviEvaluationWorld.EMPLOYEE).ask("how many hours did I log on oct 12?"))
                .isEqualTo("8 hours recorded for Mon Oct 12, 2026. An earlier submission for this date was rejected; "
                        + "the resubmission is counted.");

        Chat october = new Chat(WaviEvaluationWorld.EMPLOYEE);
        assertThat(october.ask("total hours I put in during october"))
                .contains("3 days appear on more than one of your timesheets");
    }

    @Test
    void aHoursFollowUpKeepsThePeriodAndAdminsHaveNoOwnHours() {
        Chat employee = new Chat(WaviEvaluationWorld.EMPLOYEE);
        employee.ask("is my timesheet for last week approved");
        assertThat(employee.ask("how many hours was that?")).contains("Hours recorded for last week", "40 hours");
        assertThat(employee.lastStage).isEqualTo(MyHrChatService.RouteStage.CONTINUATION);

        Chat admin = routed(WaviEvaluationWorld.ADMIN, "get_my_timesheets", "{\"period\":\"LATEST\",\"detail\":\"HOURS\"}");
        assertThat(admin.ask("how many hours have I logged?")).startsWith("Company Admin accounts don't submit timesheets");
    }

    // --- Name parts, aggregates, concepts, workflow topics -----------------------------------------------------

    @Test
    void namePartsAnswerOnlyThePartAskedFor() {
        Chat employee = new Chat(WaviEvaluationWorld.EMPLOYEE);
        assertThat(employee.ask("What is my last name?")).isEqualTo("Your last name in Waypoint is Rao.");
        assertThat(employee.ask("what's my first name")).isEqualTo("Your first name in Waypoint is Asha.");
        assertThat(employee.ask("what is my name")).contains("Asha Rao");
        Chat routedHr = routed(WaviEvaluationWorld.HR, "get_my_profile_field", "{\"field\":\"LAST_NAME\"}");
        assertThat(routedHr.ask("surname on my record?")).isEqualTo("Your last name in Waypoint is Kumar.");
    }

    @Test
    void aPeopleAggregateGetsASpecificLimitationNotAPrivacyRefusal() {
        Chat hr = routed(WaviEvaluationWorld.HR, "unsupported_request", "{\"reason\":\"PEOPLE_AGGREGATE\"}");
        MyHrChatResponse reply = hr.world.service.chat(hr.world.actor, "how many managers are there?", List.of(),
                StampedHistory.SESSION);
        assertThat(reply.message()).isEqualTo(WaviTools.PEOPLE_AGGREGATE_REPLY).isNotEqualTo(MyHrChatService.OTHER_PERSON_REPLY);
        assertThat(reply.actions()).containsExactly(MyHrConversationIntents.Page.EMPLOYEES.name());

        Chat employee = routed(WaviEvaluationWorld.EMPLOYEE, "unsupported_request", "{\"reason\":\"PEOPLE_AGGREGATE\"}");
        MyHrChatResponse forEmployee = employee.world.service.chat(employee.world.actor, "how many managers are there?",
                List.of(), StampedHistory.SESSION);
        // Employees have no Employees page: no button.
        assertThat(forEmployee.actions()).isEmpty();
    }

    @Test
    void timesheetPeriodConceptsAreExplainedFromHowWaypointWorks() {
        for (WaviEvaluationWorld.Person person : List.of(WaviEvaluationWorld.EMPLOYEE, WaviEvaluationWorld.HR)) {
            Chat chat = routed(person, "explain_term", "{\"term\":\"TIMESHEET_PERIOD\"}");
            assertThat(chat.ask("So every timesheet has 6 days?")).contains("up to 31 days", "overlap")
                    .doesNotContain(WaviTools.HISTORY_REPLY);
        }
    }

    @Test
    void theRouterIsToldEveryNewCapabilityAndEachWorkflowTopicsMeaning() {
        WaviEvaluationWorld.Options options = new WaviEvaluationWorld.Options();
        options.toolLayer = true;
        List<WaviTool> catalog = new WaviEvaluationWorld(WaviEvaluationWorld.HR, options).service.toolLayer().registry().all();
        WaviRouterPrompt.Request prompt = WaviRouterPrompt.build(catalog, UserRole.HR, LocalDate.parse("2026-11-05"), null,
                "x");
        assertThat(prompt.system()).contains("PEOPLE_AGGREGATE", "detail LIST", "detail HOURS", "TIMESHEET_PERIOD",
                "TIMESHEET_ENTRY = enter and submit a timesheet",
                "TIMESHEET_UPLOAD = ONLY file upload problems", "LAST_NAME");
        assertThat(Mockito.mockingDetails(catalog).isMock()).isFalse();
    }
}
