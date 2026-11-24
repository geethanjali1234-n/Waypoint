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
            this(person, false);
        }

        /** toolLayer: also build the router's tool layer, so a router decision can be executed directly. */
        Conversation(WaviEvaluationWorld.Person person, boolean toolLayer) {
            this(person, options(toolLayer));
        }

        Conversation(WaviEvaluationWorld.Person person, WaviEvaluationWorld.Options options) {
            world = new WaviEvaluationWorld(person, options);
            actor = world.actor;
        }

        private static WaviEvaluationWorld.Options options(boolean toolLayer) {
            WaviEvaluationWorld.Options options = new WaviEvaluationWorld.Options();
            options.toolLayer = toolLayer;
            return options;
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
    void afterAManagerAnswerMyNameQuestionsAreAboutTheSignedInUser() {
        Conversation chat = new Conversation(WaviEvaluationWorld.EMPLOYEE);
        assertThat(chat.ask("who is my reporting manager?").text()).contains("Priya Nair");

        Answer last = chat.ask("What is my last name?");
        assertThat(last.text()).contains("Rao").doesNotContain("Nair");

        assertThat(chat.ask("who is my reporting manager?").text()).contains("Priya Nair");
        Answer first = chat.ask("What is my first name?");
        assertThat(first.text()).contains("Asha").doesNotContain("Priya");
    }

    @Test
    void anExplicitSelfReferenceGetsTheSameAnswerWithOrWithoutAPreviousSubject() {
        for (String own : List.of("What is my last name?", "What is my first name?", "What is my name?")) {
            String fresh = new Conversation(WaviEvaluationWorld.EMPLOYEE).ask(own).text();
            Conversation afterManager = new Conversation(WaviEvaluationWorld.EMPLOYEE);
            afterManager.ask("who is my reporting manager?");
            assertThat(afterManager.ask(own).text()).as(own).isEqualTo(fresh);
        }
    }

    @Test
    void anExplicitTimesheetPeriodIsNotReplacedByThePreviousAnswersPeriod() {
        for (String explicit : List.of("total hours for October 2026", "how many hours did I work in October 2026?")) {
            String fresh = new Conversation(WaviEvaluationWorld.EMPLOYEE).ask(explicit).text();
            Conversation afterDay = new Conversation(WaviEvaluationWorld.EMPLOYEE);
            afterDay.ask("how many hours did I work on October 12, 2026?");
            assertThat(afterDay.ask(explicit).text()).as(explicit).isEqualTo(fresh);
        }
    }

    // --- Self-identity and Company Admin profile ------------------------------------------------------------------

    @Test
    void anOwnProfileQuestionNeverInheritsThePreviousAnswersContext() {
        Map<WaviEvaluationWorld.Person, String> previous = Map.of(
                WaviEvaluationWorld.ADMIN, "what needs my approval?",
                WaviEvaluationWorld.HR, "what needs my approval?",
                WaviEvaluationWorld.EMPLOYEE, "who is my reporting manager?");
        previous.forEach((person, first) -> {
            for (String own : List.of("Who am I?", "What is my name?", "What is my employee ID?")) {
                String fresh = new Conversation(person).ask(own).text();
                Conversation chat = new Conversation(person);
                chat.ask(first);
                assertThat(chat.ask(own).text()).as(person.role() + " after \"" + first + "\": " + own).isEqualTo(fresh);
            }
        });
    }

    @Test
    void aCompanyAdminWithoutAnEmployeeProfileIsDescribedFromTheirAccountAndOrganization() {
        Conversation chat = new Conversation(WaviEvaluationWorld.ADMIN);
        Mockito.when(chat.world.employees.getOwnDetail(WaviEvaluationWorld.ADMIN.userId())).thenReturn(adminProfile(
                "admin.login", "owner@acme.example", "Registered Owner", "owner@acme.example"));

        assertThat(chat.ask("Who am I?").text()).contains("Name: Registered Owner", "Admin / Login ID: admin.login",
                        "Role: Company Admin", "Account status: Active", "Organization: Acme Demo Org")
                .doesNotContain("Employee ID", "Job title", "Department");
        assertThat(chat.ask("What is my name?").text()).isEqualTo("Your name in Waypoint is Registered Owner.");
        assertThat(chat.ask("What is my employee ID?").text())
                .isEqualTo("Company Admin accounts don't have an employee ID. Your Admin / Login ID is admin.login.");
    }

    @Test
    void anotherCompanyAdminOfTheSameOrganizationNeverGetsTheRegisteredAdminsName() {
        Conversation chat = new Conversation(WaviEvaluationWorld.ADMIN);
        Mockito.when(chat.world.employees.getOwnDetail(WaviEvaluationWorld.ADMIN.userId())).thenReturn(adminProfile(
                "second.admin", "second@acme.example", "Registered Owner", "owner@acme.example"));

        assertThat(chat.ask("Who am I?").text()).contains("Name: second.admin", "Admin / Login ID: second.admin")
                .doesNotContain("Registered Owner");
        assertThat(chat.ask("What is my name?").text())
                .isEqualTo("No name is recorded for your account; your Admin / Login ID is second.admin.");
    }

    @Test
    void employeeAndHrProfileAnswersAreUnchanged() {
        assertThat(new Conversation(WaviEvaluationWorld.EMPLOYEE).ask("Who am I?").text())
                .startsWith("Your profile: Name: Asha Rao; Employee ID: WPT-1001; Job title: Senior Data Analyst");
        assertThat(new Conversation(WaviEvaluationWorld.HR).ask("Who am I?").text())
                .startsWith("Your profile: Name: Hari Kumar; Employee ID: WPT-2001; Job title: HR Business Partner");
        assertThat(new Conversation(WaviEvaluationWorld.EMPLOYEE).ask("What is my employee ID?").text())
                .isEqualTo("Your employee ID is WPT-1001.");
    }

    @Test
    void phoneNumbersStayOutOfChatForEveryRole() {
        for (WaviEvaluationWorld.Person person : List.of(WaviEvaluationWorld.EMPLOYEE, WaviEvaluationWorld.HR,
                WaviEvaluationWorld.ADMIN)) {
            assertThat(new Conversation(person).ask("What is my phone number?").text()).as(person.role().name())
                    .contains("I don't show your current phone number in chat");
        }
    }

    // --- Direct reports --------------------------------------------------------------------------------------------

    @Test
    void aCompanyAdminGetsOnlyTheirAssignedDirectReportsNeverTheWholeOrganization() {
        Conversation chat = new Conversation(WaviEvaluationWorld.ADMIN);
        Mockito.when(chat.world.employees.listDirectReports(WaviEvaluationWorld.ADMIN.userId())).thenReturn(List.of(
                new com.waypoint.dto.myhr.EmployeeSummaryResponse("emp-direct", "WPT-3001", "Dana Direct", "EMPLOYEE",
                        null, null, "Analyst", null, null, WaviEvaluationWorld.ADMIN.name(), null)));

        assertThat(chat.ask("Who reports to me?").text()).isEqualTo("1 person reports to you: Dana Direct (Analyst).")
                .doesNotContain(WaviEvaluationWorld.EMPLOYEE.name(), WaviEvaluationWorld.HR.name());
        verify(chat.world.employees, never()).list(WaviEvaluationWorld.ADMIN.userId());
    }

    @Test
    void aCompanyAdminWithNoDirectReportsIsToldSoAndHrAndEmployeeAnswersAreUnchanged() {
        assertThat(new Conversation(WaviEvaluationWorld.ADMIN).ask("Who reports to me?").text())
                .isEqualTo("No one currently reports to you in Waypoint.");
        assertThat(new Conversation(WaviEvaluationWorld.HR).ask("Who reports to me?").text())
                .startsWith("2 people report to you: ");
        assertThat(new Conversation(WaviEvaluationWorld.EMPLOYEE).ask("Who reports to me?").text())
                .startsWith("Employee accounts don't have direct reports in Waypoint.");
    }

    // --- Tools the semantic router chooses: executed exactly as a router decision is ------------------------------

    /** Runs a router decision through the real tool layer (role checks, argument validation, executor). */
    private static String tool(Conversation chat, String tool, Map<String, Object> arguments, String message) {
        return chat.world.service.toolLayer().execute(WaviRouterDecision.fastPath(WaviRouterDecision.Intent.RECORD_LOOKUP,
                tool, arguments), chat.actor, java.time.LocalDate.of(2026, 11, 5), message).result().text();
    }

    @Test
    void organizationAndAccountFieldsComeFromTheSignedInUsersOwnProfileWithThePagesVisibility() {
        Conversation admin = new Conversation(WaviEvaluationWorld.ADMIN, true);
        Mockito.when(admin.world.employees.getOwnDetail(WaviEvaluationWorld.ADMIN.userId())).thenReturn(ownProfile(
                UserRole.COMPANY_ADMIN, "owner@acme.example", "hello@acme.example"));
        assertThat(tool(admin, WaviTools.GET_MY_PROFILE_FIELD, Map.of("field", "ORGANIZATION_CODE"), "org code?"))
                .isEqualTo("Your organization code is ACME.");
        assertThat(tool(admin, WaviTools.GET_MY_PROFILE_FIELD, Map.of("field", "ORGANIZATION_EMAIL"), "company email?"))
                .isEqualTo("Your organization's email is hello@acme.example.");
        assertThat(tool(admin, WaviTools.GET_MY_PROFILE_FIELD, Map.of("field", "ACCOUNT_EMAIL"), "account email?"))
                .isEqualTo("Your account email is owner@acme.example.");
        assertThat(tool(admin, WaviTools.GET_MY_PROFILE_FIELD, Map.of("field", "ORGANIZATION_NAME"), "which company?"))
                .isEqualTo("Your organization is Acme Demo Org.");

        Conversation employee = new Conversation(WaviEvaluationWorld.EMPLOYEE, true);
        Mockito.when(employee.world.employees.getOwnDetail(WaviEvaluationWorld.EMPLOYEE.userId())).thenReturn(ownProfile(
                UserRole.EMPLOYEE, "asha@acme.example", "hello@acme.example"));
        assertThat(tool(employee, WaviTools.GET_MY_PROFILE_FIELD, Map.of("field", "ORGANIZATION_CODE"), "org code?"))
                .isEqualTo("Your organization code is ACME.");
        assertThat(tool(employee, WaviTools.GET_MY_PROFILE_FIELD, Map.of("field", "ORGANIZATION_EMAIL"), "org email?"))
                .doesNotContain("hello@acme.example").contains("isn't shown in your profile");
        assertThat(tool(employee, WaviTools.GET_MY_PROFILE_FIELD, Map.of("field", "ACCOUNT_EMAIL"), "login email?"))
                .doesNotContain("asha@acme.example");
        // The existing personal-contact privacy rule is unchanged.
        assertThat(employee.ask("What is my email?").text()).contains("I don't show your current personal email in chat");
    }

    @Test
    void findPeopleFiltersTheReviewersOwnScopeByWhatTheRouterAndTheMessageName() {
        Conversation admin = directory(WaviEvaluationWorld.ADMIN);

        assertThat(tool(admin, WaviTools.FIND_PEOPLE, Map.of("filter", "JOINED", "joined", "2026-10-01/2026-10-31"),
                "Who joined in October 2026?")).startsWith("1 person who joined between 2026-10-01 and 2026-10-31 in your organization:")
                .contains("Neha New (Analyst; joined 2026-10-05)").doesNotContain("Olga Old", "Ivan Incomplete");
        assertThat(tool(admin, WaviTools.FIND_PEOPLE, Map.of("filter", "JOINED", "joined", "2026-01-01/2026-12-31"),
                "list employees who joined this year")).contains("Neha New", "Ivan Incomplete").doesNotContain("Olga Old");
        assertThat(tool(admin, WaviTools.FIND_PEOPLE, Map.of("filter", "JOINED"), "who joined recently?"))
                .startsWith("Which period do you mean?");
        assertThat(tool(admin, WaviTools.FIND_PEOPLE, Map.of("filter", "JOB_TITLE"), "Which employees are analysts?"))
                .contains("Neha New").doesNotContain("Olga Old", "Ivan Incomplete");
        assertThat(tool(admin, WaviTools.FIND_PEOPLE, Map.of("filter", "JOB_TITLE"), "who is a pilot?"))
                .startsWith("I couldn't match that job title");
        assertThat(tool(admin, WaviTools.FIND_PEOPLE, Map.of("filter", "REPORTS_TO"), "Who reports to Priya Nair?"))
                .contains("reporting to Priya Nair", "Olga Old").doesNotContain("Neha New");
        assertThat(tool(admin, WaviTools.FIND_PEOPLE, Map.of("filter", "MISSING_DETAILS"), "Which profiles are incomplete?"))
                .contains("Ivan Incomplete", "missing employee ID, job title, reporting manager").doesNotContain("Neha New", "Olga Old");
        assertThat(tool(admin, WaviTools.FIND_PEOPLE, Map.of("filter", "DEPARTMENT"), "Who is in Engineering?"))
                .contains("doesn't record departments");
        assertThat(tool(admin, WaviTools.FIND_PEOPLE, Map.of(), "list all employees")).startsWith("3 people in your organization:");
    }

    @Test
    void findPeopleIsLimitedToReviewersAndToTheirExistingScope() {
        assertThat(tool(new Conversation(WaviEvaluationWorld.EMPLOYEE, true), WaviTools.FIND_PEOPLE, Map.of(), "list all employees"))
                .isEqualTo("The employee directory is available only to HR and Company Admin accounts.");
        Conversation hr = new Conversation(WaviEvaluationWorld.HR, true);
        assertThat(tool(hr, WaviTools.FIND_PEOPLE, Map.of("filter", "JOINED", "joined", "2026-10-01/2026-10-31"), "who joined?"))
                .endsWith("among your active direct reports.");
        verify(hr.world.employees, never()).list(WaviEvaluationWorld.ADMIN.userId());
    }

    @Test
    void aPersonFollowUpAboutJoiningAnswersTheirStartDate() {
        Conversation admin = directory(WaviEvaluationWorld.ADMIN);
        admin.ask("who is Neha New?");

        assertThat(admin.ask("when did she join?").text()).isEqualTo("Neha New joined on 2026-10-05.");
    }

    @Test
    void payrollReadinessToolGivesTheSummaryCutoffAndNotReadyPeople() {
        Conversation admin = payroll(WaviEvaluationWorld.ADMIN);
        assertThat(tool(admin, WaviTools.GET_PAYROLL_READINESS, Map.of(), "Is payroll ready?")).isEqualTo(
                "Payroll readiness for November 2026: 1 of 3 employees are ready for payroll; 1 are awaiting approval, "
                        + "1 are missing a timesheet. The cutoff is 2026-11-25.");
        assertThat(tool(admin, WaviTools.GET_PAYROLL_READINESS, Map.of("ask", "CUTOFF"), "When is the cutoff?"))
                .isEqualTo("The payroll cutoff for November 2026 is 2026-11-25.");
        assertThat(tool(admin, WaviTools.GET_PAYROLL_READINESS, Map.of("ask", "NOT_READY"), "Who is not ready?")).isEqualTo(
                "2 people are not ready for payroll for November 2026:\n- Asha Rao: Awaiting approval\n- Ravi Teja: Missing timesheet");
        assertThat(tool(admin, WaviTools.GET_PAYROLL_READINESS, Map.of("month", "2026-10"), "October readiness?"))
                .startsWith("Payroll readiness for November 2026");
        verify(readiness).getReadiness(WaviEvaluationWorld.ADMIN.userId(), java.time.LocalDate.of(2026, 10, 1), null, null,
                "ALL", null, null, "EMPLOYEE_ASC", 0, 1);
    }

    @Test
    void payrollReadinessStaysWithReviewersAndTheOriginalFixedPhrasesStillAnswer() {
        Conversation employee = payroll(WaviEvaluationWorld.EMPLOYEE);
        assertThat(tool(employee, WaviTools.GET_PAYROLL_READINESS, Map.of(), "Is payroll ready?"))
                .isEqualTo("Payroll readiness is available only to HR and Company Admin accounts.");
        verify(readiness, never()).getReadiness(anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.anyInt());
        assertThat(payroll(WaviEvaluationWorld.HR).ask("When is the payroll cutoff?").text())
                .isEqualTo("The payroll cutoff for November 2026 is 2026-11-25.");
    }

    // --- The semantic router sees, chooses and safely executes the new tools -------------------------------------

    @Test
    void theRouterPromptAndSchemaOfferTheNewToolsAndTheirStructuredArguments() {
        for (UserRole role : List.of(UserRole.HR, UserRole.COMPANY_ADMIN)) {
            WaviEvaluationWorld.Person person = role == UserRole.HR ? WaviEvaluationWorld.HR : WaviEvaluationWorld.ADMIN;
            List<WaviTool> catalog = new Conversation(person, true).world.service.toolLayer().registry().all();
            WaviRouterPrompt.Request prompt = WaviRouterPrompt.build(catalog, role, java.time.LocalDate.parse("2026-11-05"),
                    null, "x");
            String schema = prompt.schema().toString();
            assertThat(prompt.system() + schema).as(role.name()).contains(WaviTools.FIND_PEOPLE, WaviTools.GET_PAYROLL_READINESS,
                    "JOINED", "JOB_TITLE", "REPORTS_TO", "MISSING_DETAILS", "NOT_READY", "CUTOFF", "ORGANIZATION_CODE",
                    "ORGANIZATION_EMAIL", "ACCOUNT_EMAIL", "START_DATE");
        }
        // The registry restricts both new tools to HR and Company Admin.
        List<WaviTool> catalog = new Conversation(WaviEvaluationWorld.EMPLOYEE, true).world.service.toolLayer().registry().all();
        for (String name : List.of(WaviTools.FIND_PEOPLE, WaviTools.GET_PAYROLL_READINESS)) {
            assertThat(catalog.stream().filter(tool -> tool.name().equals(name)).findFirst().orElseThrow().allowedRoles())
                    .as(name).containsExactlyInAnyOrder(UserRole.HR, UserRole.COMPANY_ADMIN);
        }
    }

    @Test
    void aRouterChoiceOfTheNewToolsIsAnsweredEndToEndWithinTheUsersRoleAndScope() {
        Conversation admin = routed(WaviEvaluationWorld.ADMIN, "{\"decision\":{\"tool\":\"find_people\",\"arguments\":"
                + "{\"filter\":\"JOINED\",\"joined\":\"2026-01-01/2026-12-31\"}},\"confidence\":\"HIGH\",\"language\":\"english\"}");
        stubDirectory(admin);
        assertThat(admin.ask("anyone new this year?").text()).contains("Neha New", "Ivan Incomplete").doesNotContain("Olga Old");

        Conversation payroll = routed(WaviEvaluationWorld.ADMIN, "{\"decision\":{\"tool\":\"get_payroll_readiness\",\"arguments\":"
                + "{\"ask\":\"NOT_READY\"}},\"confidence\":\"HIGH\",\"language\":\"english\"}");
        stubReadiness(payroll);
        assertThat(payroll.ask("who's holding up payroll?").text()).startsWith("2 people are not ready for payroll");

        // Even if the router picks a reviewer tool for an employee, the employee gets the fixed refusal and no data.
        Conversation employee = routed(WaviEvaluationWorld.EMPLOYEE, "{\"decision\":{\"tool\":\"find_people\",\"arguments\":"
                + "{\"filter\":\"ALL\"}},\"confidence\":\"HIGH\",\"language\":\"english\"}");
        assertThat(employee.ask("show me everyone who works here").text()).doesNotContain("Ravi Teja", "Hari Kumar");
        verify(employee.world.employees, never()).list(anyString());
    }

    @Test
    void theNewToolsAndFieldsNeverReachAnExternalProviderAsSharableHistory() {
        assertThat(WaviExternalDisclosure.classify(WaviTools.FIND_PEOPLE, Map.of(), UserRole.COMPANY_ADMIN))
                .isEqualTo(WaviExternalDisclosure.Level.NAMED);
        assertThat(WaviExternalDisclosure.classify(WaviTools.GET_PAYROLL_READINESS, Map.of(), UserRole.HR))
                .isEqualTo(WaviExternalDisclosure.Level.NAMED);
        for (String field : List.of("ORGANIZATION_NAME", "ORGANIZATION_CODE", "ORGANIZATION_EMAIL", "ACCOUNT_EMAIL")) {
            assertThat(WaviExternalDisclosure.classify(WaviTools.GET_MY_PROFILE_FIELD, Map.of("field", field), UserRole.EMPLOYEE))
                    .as(field).isEqualTo(WaviExternalDisclosure.Level.LOCAL_ONLY);
        }
        assertThat(WaviExternalDisclosure.classify(WaviTools.GET_TEAM_MEMBER, Map.of("field", "START_DATE"), UserRole.HR))
                .isEqualTo(WaviExternalDisclosure.Level.LOCAL_ONLY);
    }

    /** A conversation whose semantic router (Claude) returns exactly this decision; no provider is called. */
    private static Conversation routed(WaviEvaluationWorld.Person person, String decisionJson) {
        WaviEvaluationWorld.Options options = new WaviEvaluationWorld.Options();
        options.semanticRouter = WaviEvaluationWorld.RouterBehaviour.ABSTAIN;
        Conversation chat = new Conversation(person, options);
        Mockito.doReturn(WaviEvaluationWorld.routed(decisionJson)).when(chat.world.claude)
                .routeJsonWithUsage(anyString(), anyString(), Mockito.anyMap(), Mockito.anyInt(), anyString());
        return chat;
    }

    private static void stubDirectory(Conversation chat) {
        Mockito.when(chat.world.employees.list(chat.actor.userId())).thenReturn(List.of(
                summary("emp-n", "WPT-5001", "Neha New", "Analyst", java.time.LocalDate.of(2026, 10, 5), "Hari Kumar"),
                summary("emp-o", "WPT-5002", "Olga Old", "QA Engineer", java.time.LocalDate.of(2019, 4, 1), "Priya Nair"),
                summary("emp-i", null, "Ivan Incomplete", null, java.time.LocalDate.of(2026, 2, 2), null)));
    }

    private void stubReadiness(Conversation chat) {
        readiness = Mockito.mock(PayrollReadinessService.class);
        var period = new com.waypoint.dto.myhr.PayrollReadinessPeriodResponse(java.time.LocalDate.of(2026, 11, 1),
                "November 2026", java.time.LocalDate.of(2026, 11, 1), java.time.LocalDate.of(2026, 11, 30),
                java.time.LocalDate.of(2026, 11, 25), 20);
        var summary = new com.waypoint.dto.myhr.PayrollReadinessSummaryResponse(3, 1, 0, 0, 2, 1, 0, 1, 0,
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO);
        List<com.waypoint.dto.myhr.PayrollReadinessRowResponse> rows = List.of(
                row("Neha New", com.waypoint.enums.PayrollReadinessState.READY),
                row("Asha Rao", com.waypoint.enums.PayrollReadinessState.AWAITING_APPROVAL),
                row("Ravi Teja", com.waypoint.enums.PayrollReadinessState.MISSING_TIMESHEET));
        Mockito.when(readiness.getReadiness(anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.anyInt()))
                .thenReturn(new com.waypoint.dto.myhr.PayrollReadinessResponse(period, summary, rows, 0, 100, 3, 1));
        chat.world.service.usePayrollReadinessService(readiness);
    }

    private PayrollReadinessService readiness;

    private Conversation payroll(WaviEvaluationWorld.Person person) {
        Conversation chat = new Conversation(person, true);
        readiness = Mockito.mock(PayrollReadinessService.class);
        var period = new com.waypoint.dto.myhr.PayrollReadinessPeriodResponse(java.time.LocalDate.of(2026, 11, 1),
                "November 2026", java.time.LocalDate.of(2026, 11, 1), java.time.LocalDate.of(2026, 11, 30),
                java.time.LocalDate.of(2026, 11, 25), 20);
        var summary = new com.waypoint.dto.myhr.PayrollReadinessSummaryResponse(3, 1, 0, 0, 2, 1, 0, 1, 0,
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO);
        List<com.waypoint.dto.myhr.PayrollReadinessRowResponse> rows = List.of(
                row("Neha New", com.waypoint.enums.PayrollReadinessState.READY),
                row("Asha Rao", com.waypoint.enums.PayrollReadinessState.AWAITING_APPROVAL),
                row("Ravi Teja", com.waypoint.enums.PayrollReadinessState.MISSING_TIMESHEET));
        Mockito.when(readiness.getReadiness(anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.anyInt()))
                .thenReturn(new com.waypoint.dto.myhr.PayrollReadinessResponse(period, summary, rows, 0, 100, 3, 1));
        chat.world.service.usePayrollReadinessService(readiness);
        return chat;
    }

    private static com.waypoint.dto.myhr.PayrollReadinessRowResponse row(String name,
                                                                          com.waypoint.enums.PayrollReadinessState state) {
        var row = Mockito.mock(com.waypoint.dto.myhr.PayrollReadinessRowResponse.class);
        Mockito.when(row.employeeName()).thenReturn(name);
        Mockito.when(row.readinessState()).thenReturn(state);
        return row;
    }

    /** A reviewer's synthetic scope with join dates, titles, managers and one incomplete record. */
    private static Conversation directory(WaviEvaluationWorld.Person person) {
        Conversation chat = new Conversation(person, true);
        Mockito.when(chat.world.employees.list(person.userId())).thenReturn(List.of(
                summary("emp-n", "WPT-5001", "Neha New", "Analyst", java.time.LocalDate.of(2026, 10, 5), "Hari Kumar"),
                summary("emp-o", "WPT-5002", "Olga Old", "QA Engineer", java.time.LocalDate.of(2019, 4, 1), "Priya Nair"),
                summary("emp-i", null, "Ivan Incomplete", null, java.time.LocalDate.of(2026, 2, 2), null)));
        return chat;
    }

    private static com.waypoint.dto.myhr.EmployeeSummaryResponse summary(String id, String employeeId, String name,
                                                                         String title, java.time.LocalDate start,
                                                                         String manager) {
        return new com.waypoint.dto.myhr.EmployeeSummaryResponse(id, employeeId, name, "EMPLOYEE", null, null, title,
                null, start, manager, null);
    }

    private static com.waypoint.dto.myhr.MyHrOwnProfileResponse ownProfile(UserRole role, String accountEmail,
                                                                           String organizationEmail) {
        return new com.waypoint.dto.myhr.MyHrOwnProfileResponse("user-x", "login.x", null, role,
                com.waypoint.enums.AccountStatus.ACTIVE, null, null, null, accountEmail, null, null, null, null, null,
                null, null, null, null, WaviEvaluationWorld.ORG, "Acme Demo Org", "ACME", organizationEmail, null, null,
                null, null, null, null, null, null, null, null, null, null, null);
    }

    /** A Company Admin as production creates one: an account and organization, no employee profile. */
    private static com.waypoint.dto.myhr.MyHrOwnProfileResponse adminProfile(String loginId, String accountEmail,
                                                                             String organizationAdminName,
                                                                             String organizationAdminEmail) {
        return new com.waypoint.dto.myhr.MyHrOwnProfileResponse(WaviEvaluationWorld.ADMIN.userId(), loginId, null,
                UserRole.COMPANY_ADMIN, com.waypoint.enums.AccountStatus.ACTIVE, null, null, null, accountEmail, null,
                null, null, null, null, null, null, null, null, WaviEvaluationWorld.ORG, "Acme Demo Org", "ACME", null,
                null, null, null, null, null, organizationAdminName, organizationAdminEmail, null, null, null, null,
                null, null);
    }

    @Test
    void timesheetSubmissionGuidanceDescribesTheRealWorkflowInOrder() {
        for (WaviEvaluationWorld.Person person : List.of(WaviEvaluationWorld.EMPLOYEE, WaviEvaluationWorld.HR)) {
            String guidance = new Conversation(person).ask("How do I submit my timesheet?").text();
            // The steps of the Timesheets page, in the order the employee meets them.
            assertThat(guidance).as(person.role().name()).containsSubsequence(
                    "Open Timesheets", "Enter Timesheet", "Start entry",
                    "Upload your timesheet document", "enter hours manually",
                    "Dates, Hours and Review",
                    "supporting timesheet document is required before you can submit",
                    "save a draft", "Saved drafts",
                    "reporting manager", "Submitted Timesheets");
        }
        assertThat(new Conversation(WaviEvaluationWorld.HR).ask("How do I submit my timesheet?").text())
                .startsWith("Open Timesheets, stay on the My Timesheets tab, and choose Enter Timesheet");
    }

    @Test
    void aCompanyAdminIsStillToldTheyReviewButCannotSubmitTimesheets() {
        String guidance = new Conversation(WaviEvaluationWorld.ADMIN).ask("How do I submit my timesheet?").text();

        assertThat(guidance).contains("can review timesheets but can't submit one")
                .doesNotContain("Start entry", "Saved drafts");
    }

    @Test
    void aMissingTimesheetReplyNeverSuggestsSubmittingWithoutTheSupportingDocument() {
        String reply = new Conversation(WaviEvaluationWorld.EMPLOYEE)
                .ask("Did I submit my Mar 3, 2026 timesheet?").text();

        assertThat(reply).contains("choose Enter Timesheet", "attach a supporting timesheet document and submit")
                .doesNotContain("to add and submit");
    }

    @Test
    void afterAManagerAnswerThirdPersonNameQuestionsStillMeanTheManager() {
        Conversation chat = new Conversation(WaviEvaluationWorld.EMPLOYEE);
        chat.ask("who is my reporting manager?");

        Answer last = chat.ask("What is his last name?");
        assertThat(last.text()).contains("Nair").doesNotContain("Rao");

        Answer first = chat.ask("and her first name?");
        assertThat(first.text()).contains("Priya").doesNotContain("Asha");
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
