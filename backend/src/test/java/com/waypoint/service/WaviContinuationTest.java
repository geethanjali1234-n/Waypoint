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

        assertThat(chat.ask("Who am I?").text()).contains("Name: not recorded", "Admin / Login ID: second.admin")
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
    void eachRoleSeesOnlyTheirOwnContactAndEmploymentDetails() {
        for (WaviEvaluationWorld.Person person : List.of(WaviEvaluationWorld.EMPLOYEE, WaviEvaluationWorld.HR)) {
            Conversation chat = new Conversation(person);
            Mockito.when(chat.world.employees.getOwnDetail(person.userId())).thenReturn(selfProfile(person,
                    "+1 555 0100", "me.personal@mail.example", "1 Main St", "Austin", "TX", "73301",
                    java.time.LocalDate.of(2024, 3, 1), com.waypoint.enums.EmploymentType.values()[0]));
            String role = person.role().name();
            assertThat(chat.ask("What is my phone number?").text()).as(role).isEqualTo("Your phone number in Waypoint is +1 555 0100.");
            assertThat(chat.ask("What is my email?").text()).as(role).isEqualTo(
                    "Your personal email is me.personal@mail.example. Your sign-in (account) email is " + person.userId() + "@acme.example.");
            assertThat(chat.ask("What is my address?").text()).as(role).isEqualTo("Your address in Waypoint is 1 Main St, Austin, TX 73301.");
            assertThat(chat.ask("What is my start date?").text()).as(role).isEqualTo("Your start date in Waypoint is 2024-03-01.");
            assertThat(chat.ask("What is my employment type?").text()).as(role).startsWith("Your employment type in Waypoint is ");
            // Unchanged: pay and leave balance are not recorded in Waypoint.
            assertThat(chat.ask("What is my salary?").text()).as(role).contains("can't see pay or salary information");
        }
    }

    @Test
    void missingSelfProfileValuesAreSaidToBeMissingNotInvented() {
        Conversation chat = new Conversation(WaviEvaluationWorld.EMPLOYEE);
        Mockito.when(chat.world.employees.getOwnDetail(WaviEvaluationWorld.EMPLOYEE.userId())).thenReturn(selfProfile(
                WaviEvaluationWorld.EMPLOYEE, null, null, null, null, null, null, null, null));
        assertThat(chat.ask("What is my phone number?").text()).startsWith("No phone number is recorded for your account.");
        assertThat(chat.ask("What is my address?").text()).isEqualTo("No home address is recorded for your account.");
        assertThat(chat.ask("What is my start date?").text()).isEqualTo("No start date is recorded for your account.");
        assertThat(chat.ask("What is my email?").text()).isEqualTo("Your sign-in (account) email is "
                + WaviEvaluationWorld.EMPLOYEE.userId() + "@acme.example.");
    }

    @Test
    void aCompanyAdminWithoutAnEmployeeProfileGetsOnlyTheirOwnAccountAndAdminRecordValues() {
        Conversation owner = new Conversation(WaviEvaluationWorld.ADMIN);
        Mockito.when(owner.world.employees.getOwnDetail(WaviEvaluationWorld.ADMIN.userId())).thenReturn(adminContact(
                "owner@acme.example", "owner@acme.example", "+1 555 0199"));
        assertThat(owner.ask("What is my phone number?").text())
                .isEqualTo("Your phone number on your organization's admin record is +1 555 0199.");
        assertThat(owner.ask("What is my email?").text()).isEqualTo("Your sign-in (account) email is owner@acme.example.");

        // Another administrator of the same organization never gets the registered admin's phone number.
        Conversation second = new Conversation(WaviEvaluationWorld.ADMIN);
        Mockito.when(second.world.employees.getOwnDetail(WaviEvaluationWorld.ADMIN.userId())).thenReturn(adminContact(
                "second@acme.example", "owner@acme.example", "+1 555 0199"));
        assertThat(second.ask("What is my phone number?").text()).startsWith("No phone number is recorded for your account")
                .doesNotContain("0199");
    }

    @Test
    void anotherPersonsContactDetailsAreNeverAnsweredWithTheUsersOwnOrAnyoneElses() {
        Conversation admin = new Conversation(WaviEvaluationWorld.ADMIN, true);
        Mockito.when(admin.world.employees.getOwnDetail(WaviEvaluationWorld.ADMIN.userId())).thenReturn(adminContact(
                "owner@acme.example", "owner@acme.example", "+1 555 0199"));
        // The router path names no person, so the message is re-checked.
        for (String other : List.of("What is Ravi Teja's phone number?", "what is my manager's phone number?",
                "show my team's phone numbers")) {
            assertThat(tool(admin, WaviTools.UNSUPPORTED_FIELD, Map.of("field", "PHONE_NUMBER"), other)).as(other)
                    .isEqualTo(MyHrChatService.OWN_DETAILS_ONLY);
        }
        // The deterministic path never takes a named person's phone question as the user's own.
        assertThat(admin.ask("What is Ravi Teja's phone number?").text()).doesNotContain("0199");
        // The directory and person lookups never carry contact details.
        Mockito.when(admin.world.employees.list(WaviEvaluationWorld.ADMIN.userId())).thenReturn(List.of(new com.waypoint.dto.myhr
                .EmployeeSummaryResponse("emp-x", "WPT-1", "Xia Example", "EMPLOYEE", "xia.work@acme.example",
                "xia.personal@mail.example", "Analyst", null, null, "Hari Kumar", null)));
        assertThat(tool(admin, WaviTools.FIND_PEOPLE, Map.of(), "list all employees")).doesNotContain("xia.personal@mail.example");
    }

    @Test
    void aProfileThatIsNotTheSignedInUsersIsNeverShown() {
        Conversation chat = new Conversation(WaviEvaluationWorld.EMPLOYEE);
        // A lookup that returned someone else (for example another organization's record) is refused.
        Mockito.when(chat.world.employees.getOwnDetail(WaviEvaluationWorld.EMPLOYEE.userId())).thenReturn(selfProfile(
                WaviEvaluationWorld.HR, "+1 555 0177", null, null, null, null, null, null, null));
        assertThat(chat.ask("What is my phone number?").text()).doesNotContain("0177");
    }

    @Test
    void ownSensitiveValuesAreLocalOnlyNeverLoggedAndNeverSentToAProvider() {
        for (String field : List.of("PHONE_NUMBER", "PERSONAL_EMAIL", "HOME_ADDRESS", "START_DATE", "EMPLOYMENT_TYPE")) {
            assertThat(WaviExternalDisclosure.classify(WaviTools.UNSUPPORTED_FIELD, Map.of("field", field), UserRole.EMPLOYEE))
                    .as(field).isEqualTo(WaviExternalDisclosure.Level.LOCAL_ONLY);
        }
        assertThat(WaviExternalDisclosure.classify(WaviTools.UNSUPPORTED_FIELD, Map.of("field", "PAY"), UserRole.EMPLOYEE))
                .isEqualTo(WaviExternalDisclosure.Level.PUBLIC);
        assertThat(WaviExternalDisclosure.classify(WaviTools.GET_MY_PROFILE_FIELD, Map.of("field", "ACCOUNT_EMAIL"), UserRole.EMPLOYEE))
                .isEqualTo(WaviExternalDisclosure.Level.LOCAL_ONLY);

        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(MyHrChatService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs = new ch.qos.logback.core.read.ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            Conversation chat = new Conversation(WaviEvaluationWorld.EMPLOYEE);
            Mockito.when(chat.world.employees.getOwnDetail(WaviEvaluationWorld.EMPLOYEE.userId())).thenReturn(selfProfile(
                    WaviEvaluationWorld.EMPLOYEE, "+1 555 0100", "me.personal@mail.example", "1 Main St", "Austin", "TX",
                    "73301", null, null));
            chat.ask("What is my phone number?");
            chat.ask("What is my email?");
            chat.ask("What is my address?");
            chat.ask("Can you write me a short poem about Mondays?");
            String everythingSentToProviders = Mockito.mockingDetails(chat.world.claude).getInvocations().toString()
                    + Mockito.mockingDetails(chat.world.ollama).getInvocations().toString();
            assertThat(everythingSentToProviders).doesNotContain("555 0100", "me.personal@mail.example", "1 Main St");
            assertThat(logs.list).extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .noneMatch(line -> line.contains("555 0100") || line.contains("me.personal@mail.example")
                            || line.contains("1 Main St"));
        } finally {
            logger.detachAppender(logs);
        }
    }

    /** A signed-in Employee/HR user's own profile with the given contact and employment values (synthetic). */
    private static com.waypoint.dto.myhr.MyHrOwnProfileResponse selfProfile(WaviEvaluationWorld.Person person, String phone,
                                                                            String personalEmail, String street, String city,
                                                                            String state, String zip, java.time.LocalDate start,
                                                                            com.waypoint.enums.EmploymentType type) {
        return new com.waypoint.dto.myhr.MyHrOwnProfileResponse(person.userId(), person.userId(), person.employeeId(),
                person.role(), com.waypoint.enums.AccountStatus.ACTIVE, null, null, person.name(),
                person.userId() + "@acme.example", personalEmail, phone, person.jobTitle(), type, start, street, city, state,
                zip, WaviEvaluationWorld.ORG, "Acme Demo Org", "ACME", null, null, null, null, null, null, null, null, null,
                null, null, null, null, null);
    }

    /** A Company Admin with no employee profile: account email, and the organization's recorded admin contact. */
    private static com.waypoint.dto.myhr.MyHrOwnProfileResponse adminContact(String accountEmail, String adminEmail,
                                                                             String adminPhone) {
        return new com.waypoint.dto.myhr.MyHrOwnProfileResponse(WaviEvaluationWorld.ADMIN.userId(), "admin.login", null,
                UserRole.COMPANY_ADMIN, com.waypoint.enums.AccountStatus.ACTIVE, null, null, null, accountEmail, null, null,
                null, null, null, null, null, null, null, WaviEvaluationWorld.ORG, "Acme Demo Org", "ACME", null, null, null,
                null, null, null, "Registered Owner", adminEmail, adminPhone, null, null, null, null, null);
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
        // The signed-in user's own sign-in email is theirs to see, for every role.
        assertThat(tool(employee, WaviTools.GET_MY_PROFILE_FIELD, Map.of("field", "ACCOUNT_EMAIL"), "login email?"))
                .isEqualTo("Your account email is asha@acme.example.");
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

    // --- Explicit new questions never inherit the approval-queue context -------------------------------------------

    private static final String PENDING_WORK_DECISION = "{\"decision\":{\"tool\":\"get_my_pending_work\",\"arguments\":{}},"
            + "\"confidence\":\"HIGH\",\"language\":\"english\"}";

    private static void adminWithoutEmployeeProfile(Conversation chat) {
        Mockito.when(chat.world.employees.getOwnDetail(WaviEvaluationWorld.ADMIN.userId())).thenReturn(adminProfile(
                "dana.admin", "dana@acme.example", "Dana Admin", "dana@acme.example"));
    }

    @Test
    void ownIdentityAndDirectReportsQuestionsAfterPendingApprovalsAnswerThemselvesDeterministicOrRouted() {
        for (boolean routed : List.of(false, true)) {
            for (String question : List.of("Who am I?", "What is my name?", "Who reports to me?")) {
                Conversation fresh = new Conversation(WaviEvaluationWorld.ADMIN);
                adminWithoutEmployeeProfile(fresh);
                String expected = fresh.ask(question).text();

                Conversation chat = routed ? routed(WaviEvaluationWorld.ADMIN, PENDING_WORK_DECISION)
                        : new Conversation(WaviEvaluationWorld.ADMIN);
                adminWithoutEmployeeProfile(chat);
                assertThat(chat.ask(routed ? "What are my pending works?" : "what needs my approval?").text())
                        .startsWith("Waiting for you");
                assertThat(chat.ask(question).text()).as((routed ? "routed: " : "deterministic: ") + question)
                        .isEqualTo(expected).doesNotContain("Waiting for you", "waiting for review");
            }
        }
    }

    @Test
    void whoIsANamedPersonAfterPendingApprovalsIsAboutThatPersonAndTheirOwnNameMeansThemselves() {
        Conversation chat = new Conversation(WaviEvaluationWorld.ADMIN);
        adminWithoutEmployeeProfile(chat);
        chat.ask("what needs my approval?");
        assertThat(chat.ask("Who is Asha Rao?").text()).startsWith("Asha Rao: Employee ID: WPT-1001")
                .doesNotContain("waiting for review");
        assertThat(chat.ask("Who is Dana?").text()).startsWith("That's you. Your profile: Name: Dana Admin; Admin / Login ID: dana.admin");

        Conversation routedChat = routed(WaviEvaluationWorld.ADMIN, PENDING_WORK_DECISION);
        adminWithoutEmployeeProfile(routedChat);
        routedChat.ask("What are my pending works?");
        assertThat(routedChat.ask("Who is Dana Admin?").text()).startsWith("That's you.");
        // A pronoun is still a follow-up to the person just discussed.
        Conversation people = new Conversation(WaviEvaluationWorld.HR);
        people.ask("who is Asha Rao?");
        assertThat(people.ask("what is her job title?").text()).isEqualTo("Asha Rao's job title is Senior Data Analyst.");
    }

    @Test
    void askingTheSameQuestionAgainGivesTheSameKindOfAnswer() {
        Conversation chat = routed(WaviEvaluationWorld.ADMIN, PENDING_WORK_DECISION);
        String first = chat.ask("What are my pending works?").text();
        assertThat(first).startsWith("Waiting for you");
        assertThat(chat.ask("What are my pending works?").text()).isEqualTo(first);
        assertThat(chat.ask("what are my pending works").text()).isEqualTo(first);
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
    // --- Company Admin identity and routing through the real chat entry -------------------------------------------

    private static final String[] ADMIN_CONTEXTS = {null, "what needs my approval?", "show my profile change requests"};

    private static WaviEvaluationWorld.Options routedOptions() {
        WaviEvaluationWorld.Options options = new WaviEvaluationWorld.Options();
        options.semanticRouter = WaviEvaluationWorld.RouterBehaviour.ABSTAIN;
        return options;
    }

    /** A Company Admin with no employee profile whose organization admin email was edited away from the login email. */
    private static Conversation adminChat(boolean routed, boolean soleAdmin) {
        Conversation chat = routed ? new Conversation(WaviEvaluationWorld.ADMIN, routedOptions())
                : new Conversation(WaviEvaluationWorld.ADMIN);
        if (routed) routeByMessage(chat);
        var profile = adminProfile("TARDUADMIN", "login@acme.example", "Vidya Admin Example", "owner@acme.example");
        Mockito.when(chat.world.employees.getOwnDetail(WaviEvaluationWorld.ADMIN.userId())).thenReturn(profile);
        Mockito.when(chat.world.employees.isSoleCompanyAdmin(WaviEvaluationWorld.ADMIN.userId())).thenReturn(soleAdmin);
        stubPeople(chat, WaviEvaluationWorld.ADMIN);
        return chat;
    }

    private static void stubPeople(Conversation chat, WaviEvaluationWorld.Person reader) {
        var people = List.of(
                summary("e1", "WPT-1", "Sam Coder", "Software Engineer", java.time.LocalDate.of(2026, 10, 20), "Hari Kumar"),
                summary("e2", "WPT-2", "Olga Old", "QA Engineer", java.time.LocalDate.of(2019, 4, 1), "Hari Kumar"));
        Mockito.when(chat.world.employees.list(reader.userId())).thenReturn(people);
    }

    /** Simulated Claude routing: picks the tool a capable router would for each test question. */
    private static void routeByMessage(Conversation chat) {
        Mockito.doAnswer(call -> WaviEvaluationWorld.routed(simulatedDecision(call.<String>getArgument(1))))
                .when(chat.world.claude).routeJsonWithUsage(anyString(), anyString(), Mockito.anyMap(),
                        Mockito.anyInt(), anyString());
    }

    private static String simulatedDecision(String prompt) {
        String text = prompt.toLowerCase(java.util.Locale.ROOT);
        String question = text.contains("current message") ? text.substring(text.lastIndexOf("current message")) : text;
        String tool;
        if (question.contains("approve profile change"))
            tool = "{\"tool\":\"workflow_help\",\"arguments\":{\"topic\":\"PROFILE_CHANGE_REVIEW\"}}";
        else if (question.contains("last name")) tool = "{\"tool\":\"get_my_profile_field\",\"arguments\":{\"field\":\"LAST_NAME\"}}";
        else if (question.contains("employers")) tool = "{\"tool\":\"clarify\",\"arguments\":{}}";
        else if (question.contains("my employees")) tool = "{\"tool\":\"find_people\",\"arguments\":{\"filter\":\"ALL\"}}";
        else if (question.contains("employee id") && !question.contains("my employee id"))
            tool = "{\"tool\":\"get_team_member\",\"arguments\":{\"field\":\"EMPLOYEE_ID\"}}";
        else if (question.contains("who is ")) tool = "{\"tool\":\"get_team_member\",\"arguments\":{\"field\":\"SUMMARY\"}}";
        else if (question.contains("who am i")) tool = "{\"tool\":\"get_my_profile_field\",\"arguments\":{\"field\":\"PROFILE\"}}";
        else if (question.contains("my name")) tool = "{\"tool\":\"get_my_profile_field\",\"arguments\":{\"field\":\"NAME\"}}";
        else if (question.contains("login id")) tool = "{\"tool\":\"get_my_profile_field\",\"arguments\":{\"field\":\"LOGIN_ID\"}}";
        else if (question.contains("software engineer")) tool = "{\"tool\":\"find_people\",\"arguments\":{\"filter\":\"JOB_TITLE\"}}";
        else if (question.contains("timesheets")) tool = "{\"tool\":\"get_team_timesheets\",\"arguments\":{\"status\":\"SUBMITTED\"}}";
        else if (question.contains("join")) tool = question.contains("she") || question.contains("he ")
                ? "{\"tool\":\"get_team_member\",\"arguments\":{\"field\":\"START_DATE\"}}"
                : "{\"tool\":\"find_people\",\"arguments\":{\"filter\":\"JOINED\",\"joined\":\"2026-08-07/2026-11-05\"}}";
        else if (question.contains("job title")) tool = "{\"tool\":\"get_team_member\",\"arguments\":{\"field\":\"JOB_TITLE\"}}";
        else if (question.contains("profile change")) tool = "{\"tool\":\"get_my_requests\",\"arguments\":{\"kind\":\"PROFILE_CHANGE\"}}";
        else tool = "{\"tool\":\"get_my_pending_work\",\"arguments\":{}}";
        return "{\"decision\":" + tool + ",\"confidence\":\"HIGH\",\"language\":\"english\"}";
    }

    @Test
    void aSoleCompanyAdminIsNamedAsMyProfileShowsInEveryContextAndRoutingMode() {
        for (boolean routed : new boolean[] {false, true}) {
            for (String context : ADMIN_CONTEXTS) {
                String where = (routed ? "routed" : "fixed") + " after " + context;
                Conversation chat = adminChat(routed, true);
                if (context != null) chat.ask(context);
                assertThat(chat.ask("Who am I?").text()).as(where).contains("Vidya Admin Example")
                        .contains("Admin / Login ID: TARDUADMIN").doesNotContain("Name: TARDUADMIN");

                chat = adminChat(routed, true);
                if (context != null) chat.ask(context);
                assertThat(chat.ask("What is my name?").text()).as(where)
                        .isEqualTo("Your name in Waypoint is Vidya Admin Example.");
                verify(chat.world.claude, never()).chat(anyString(), anyList());
            }
        }
    }

    @Test
    void oneOfSeveralAdminsNeverGetsTheOrganizationAdminsNameAndNoNameIsInvented() {
        for (boolean routed : new boolean[] {false, true}) {
            for (String context : ADMIN_CONTEXTS) {
                String where = (routed ? "routed" : "fixed") + " after " + context;
                Conversation chat = adminChat(routed, false);
                if (context != null) chat.ask(context);
                assertThat(chat.ask("Who am I?").text()).as(where).doesNotContain("Vidya")
                        .contains("Name: not recorded").contains("Admin / Login ID: TARDUADMIN");
                assertThat(chat.ask("What is my name?").text()).as(where).doesNotContain("Vidya")
                        .contains("No name is recorded").contains("TARDUADMIN")
                        .doesNotContainIgnoringCase("profile change");
            }
        }
    }

    @Test
    void theLoginIdIsAnsweredForEveryRoleAfterUnrelatedQuestions() {
        for (String context : ADMIN_CONTEXTS) {
            Conversation admin = adminChat(true, true);
            if (context != null) admin.ask(context);
            assertThat(admin.ask("What is my login ID?").text()).as("admin after " + context)
                    .isEqualTo("Your Admin / Login ID is TARDUADMIN.");
            verify(admin.world.claude, never()).chat(anyString(), anyList());
        }
        Conversation employee = new Conversation(WaviEvaluationWorld.EMPLOYEE, routedOptions());
        routeByMessage(employee);
        var own = new com.waypoint.dto.myhr.MyHrOwnProfileResponse(WaviEvaluationWorld.EMPLOYEE.userId(), "asha.login",
                null, UserRole.EMPLOYEE, com.waypoint.enums.AccountStatus.ACTIVE, null, null, null, "asha@acme.example",
                null, null, null, null, null, null, null, null, null, WaviEvaluationWorld.ORG, "Acme Demo Org", "ACME",
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        Mockito.when(employee.world.employees.getOwnDetail(WaviEvaluationWorld.EMPLOYEE.userId())).thenReturn(own);
        employee.ask("show my profile change requests");
        assertThat(employee.ask("What is my login ID?").text()).isEqualTo("Your login ID is asha.login.");
        verify(employee.world.claude, never()).chat(anyString(), anyList());
    }

    @Test
    void directoryQuestionsAreFilteredForReviewersAfterUnrelatedQuestionsAndRefusedForEmployees() {
        for (String context : ADMIN_CONTEXTS) {
            for (WaviEvaluationWorld.Person reviewer : List.of(WaviEvaluationWorld.ADMIN, WaviEvaluationWorld.HR)) {
                String where = reviewer.role() + " after " + context;
                Conversation chat = new Conversation(reviewer, routedOptions());
                routeByMessage(chat);
                stubPeople(chat, reviewer);
                if (context != null) chat.ask(context);
                assertThat(chat.ask("Who works as a Software Engineer?").text()).as(where).contains("Sam Coder")
                        .doesNotContain("Olga Old").doesNotContainIgnoringCase("approval");
                assertThat(chat.ask("Who joined recently in my organization?").text()).as(where).contains("Sam Coder")
                        .doesNotContain("Olga Old").doesNotContainIgnoringCase("approval");
                verify(chat.world.claude, never()).chat(anyString(), anyList());
            }
        }
        Conversation employee = new Conversation(WaviEvaluationWorld.EMPLOYEE, routedOptions());
        routeByMessage(employee);
        assertThat(employee.ask("Who works as a Software Engineer?").text())
                .contains("only to HR and Company Admin").doesNotContain("Sam Coder");
        verify(employee.world.employees, never()).list(anyString());
    }

    @Test
    void anExplicitQuestionAfterPendingApprovalsIsNotTreatedAsAFollowUp() {
        Conversation chat = adminChat(false, true);
        chat.ask("what needs my approval?");
        assertThat(chat.ask("Who works as a Software Engineer?").stage())
                .isNotEqualTo(MyHrChatService.RouteStage.FRAME);
    }
    // --- Explicit new questions outrank the previous answer's context ---------------------------------------------

    private static final boolean[] ROUTING_MODES = {false, true};

    private static Conversation chatAs(WaviEvaluationWorld.Person person, boolean routed) {
        if (person == WaviEvaluationWorld.ADMIN) {
            Conversation admin = adminChat(routed, true);
            Mockito.when(admin.world.employees.list(WaviEvaluationWorld.ADMIN.userId())).thenReturn(List.of(
                    summary("e1", "WPT-1", "Sowmya Grandhi", "Software Engineer", java.time.LocalDate.of(2026, 10, 20), "Hari Kumar"),
                    summary("e2", "WPT-2", "Hari Kumar", "HR Business Partner", java.time.LocalDate.of(2019, 4, 1), null)));
            return admin;
        }
        Conversation chat = routed ? new Conversation(person, routedOptions()) : new Conversation(person);
        if (routed) routeByMessage(chat);
        return chat;
    }

    @Test
    void employersAfterPendingWorkIsANewQuestionNotTheApprovalList() {
        for (boolean routed : ROUTING_MODES) {
            for (WaviEvaluationWorld.Person person : List.of(WaviEvaluationWorld.ADMIN, WaviEvaluationWorld.HR,
                    WaviEvaluationWorld.EMPLOYEE)) {
                String where = person.role() + (routed ? " routed" : " fixed");
                Conversation chat = chatAs(person, routed);
                chat.ask("What are my pending works?");
                Answer employers = chat.ask("Who are my employers?");
                assertThat(employers.stage()).as(where).isNotEqualTo(MyHrChatService.RouteStage.FRAME);
                // Neither the people waiting for review nor an employee list: "employers" is not "employees".
                assertThat(employers.text()).as(where).doesNotContain("waiting for review", "Sowmya Grandhi",
                        "Asha Rao: 1 timesheet", "Employee accounts");
            }
        }
    }

    @Test
    void myLastNameAfterAnEmployeeListIsAboutTheSignedInUser() {
        for (boolean routed : ROUTING_MODES) {
            Conversation admin = chatAs(WaviEvaluationWorld.ADMIN, routed);
            assertThat(admin.ask("Who are my employees?").text()).contains("Hari Kumar");
            Answer last = admin.ask("What is my last name?");
            assertThat(last.stage()).as("routed=" + routed).isNotEqualTo(MyHrChatService.RouteStage.PEOPLE_REFERENCE);
            assertThat(last.text()).as("routed=" + routed).contains("Vidya Admin Example")
                    .doesNotContain("Hari Kumar", "Sowmya", "WPT-");

            Conversation hr = chatAs(WaviEvaluationWorld.HR, routed);
            hr.ask("Who reports to me?");
            assertThat(hr.ask("What is my last name?").text()).as("HR routed=" + routed)
                    .doesNotContain("Asha", "Ravi", "WPT-").containsIgnoringCase("name");
        }
    }

    @Test
    void anAdminsNamePartsUseTheFullNameSourceAndSayWhenTheyAreNotStoredSeparately() {
        for (boolean routed : ROUTING_MODES) {
            for (String ask : List.of("My last name", "What is my last name?")) {
                Conversation admin = chatAs(WaviEvaluationWorld.ADMIN, routed);
                assertThat(admin.ask("What is my name?").text()).isEqualTo("Your name in Waypoint is Vidya Admin Example.");
                String reply = admin.ask(ask).text();
                assertThat(reply).as(ask + " routed=" + routed)
                        .isEqualTo("Waypoint doesn't store your last name separately; your name is recorded as one "
                                + "full name: Vidya Admin Example.")
                        // Never a surname guessed from the display name's words.
                        .doesNotContain("last name in Waypoint is Example");
            }
            // A second admin with no recorded name: nothing is invented.
            Conversation other = adminChat(routed, false);
            assertThat(other.ask("My last name").text()).as("routed=" + routed)
                    .isEqualTo("Your last name is not available in your profile.");
        }
    }

    @Test
    void whoIsANamedPersonAfterTheirEmployeeIdGivesTheirSummaryNotTheIdAgain() {
        for (boolean routed : ROUTING_MODES) {
            for (String who : List.of("Who is Sowmya?", "who is sowmya?")) {
                Conversation admin = chatAs(WaviEvaluationWorld.ADMIN, routed);
                assertThat(admin.ask("What is Sowmya's employee ID?").text()).contains("WPT-1");
                String summary = admin.ask(who).text();
                assertThat(summary).as(who + " routed=" + routed).contains("Sowmya Grandhi", "Software Engineer",
                        "Reporting manager: Hari Kumar");
                // Follow-ups about the same person still continue.
                assertThat(admin.ask("What is her job title?").text()).as(who).contains("Software Engineer");
                assertThat(admin.ask("And their reporting manager?").text()).as(who).contains("Hari Kumar");
            }
        }
    }
    // --- The same explicit question is interpreted the same way after any previous topic --------------------------

    private static final List<String> PREVIOUS_TOPICS = java.util.Arrays.asList(null, "What are my pending works?",
            "Who are my employees?", "Show submitted timesheets", "What is Sowmya's employee ID?", "Who joined recently?");

    /** The router prompt of the last routed message (what Claude would be shown). */
    private static String lastRouterPrompt(Conversation chat) {
        org.mockito.ArgumentCaptor<String> prompt = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(chat.world.claude, Mockito.atLeastOnce()).routeJsonWithUsage(anyString(), prompt.capture(),
                Mockito.anyMap(), Mockito.anyInt(), anyString());
        return prompt.getValue();
    }

    @Test
    void whoJoinedRecentlyGetsTheSameRouterPromptAndAnswerAfterEveryPreviousTopic() {
        for (WaviEvaluationWorld.Person reviewer : List.of(WaviEvaluationWorld.ADMIN, WaviEvaluationWorld.HR)) {
            String freshAnswer = null;
            String freshPrompt = null;
            for (String topic : PREVIOUS_TOPICS) {
                String where = reviewer.role() + " after " + topic;
                Conversation chat = chatAs(reviewer, true);
                if (reviewer == WaviEvaluationWorld.HR) stubPeople(chat, reviewer);
                if (topic != null) chat.ask(topic);
                Answer answer = chat.ask("Who joined recently?");
                String prompt = lastRouterPrompt(chat);
                assertThat(answer.stage()).as(where).isEqualTo(MyHrChatService.RouteStage.ROUTER);
                assertThat(answer.text()).as(where).contains("joined between").doesNotContainIgnoringCase("timesheet")
                        .doesNotContain("awaiting review");
                if (freshAnswer == null) {
                    freshAnswer = answer.text();
                    freshPrompt = prompt;
                }
                // Byte-identical router input: an unrelated earlier topic cannot change Claude's choice.
                assertThat(prompt).as(where).isEqualTo(freshPrompt);
                assertThat(answer.text()).as(where).isEqualTo(freshAnswer);
            }
        }
    }

    @Test
    void withoutTheRouterWhoJoinedRecentlyNeverBorrowsThePreviousRecordOrPerson() {
        for (String topic : PREVIOUS_TOPICS) {
            Conversation chat = chatAs(WaviEvaluationWorld.ADMIN, false);
            if (topic != null) chat.ask(topic);
            Answer answer = chat.ask("Who joined recently?");
            assertThat(answer.stage()).as("after " + topic).isNotIn(MyHrChatService.RouteStage.FRAME,
                    MyHrChatService.RouteStage.CONTINUATION, MyHrChatService.RouteStage.CLARIFY_REFERENCE);
            assertThat(answer.text()).as("after " + topic).doesNotContain("Sowmya Grandhi joined on", "Asha Rao's",
                    "awaiting review", "Which one do you mean");
        }
    }

    @Test
    void anUnsupportedEmployersQuestionIsRoutedIdenticallyAndNeverReturnsPeopleOrApprovals() {
        String freshPrompt = null;
        String freshAnswer = null;
        for (String topic : PREVIOUS_TOPICS) {
            Conversation chat = chatAs(WaviEvaluationWorld.ADMIN, true);
            if (topic != null) chat.ask(topic);
            String answer = chat.ask("Who are my employers?").text();
            String prompt = lastRouterPrompt(chat);
            if (freshPrompt == null) {
                freshPrompt = prompt;
                freshAnswer = answer;
            }
            assertThat(prompt).as("after " + topic).isEqualTo(freshPrompt);
            assertThat(answer).as("after " + topic).isEqualTo(freshAnswer)
                    .doesNotContain("Sowmya", "Hari Kumar", "Asha Rao", "waiting for review", "Employee accounts");
        }
        for (String topic : PREVIOUS_TOPICS) {
            Conversation chat = chatAs(WaviEvaluationWorld.ADMIN, false);
            if (topic != null) chat.ask(topic);
            assertThat(chat.ask("Who are my employers?").text()).as("fixed after " + topic)
                    .doesNotContain("Sowmya", "Hari Kumar", "Asha Rao", "waiting for review", "Employee accounts");
        }
    }

    @Test
    void genuineFollowUpsStillUseThePreviousAnswer() {
        for (boolean routed : ROUTING_MODES) {
            Conversation chat = chatAs(WaviEvaluationWorld.ADMIN, routed);
            chat.ask("What is Sowmya's employee ID?");
            assertThat(chat.ask("When did she join?").text()).as("routed=" + routed)
                    .contains("Sowmya Grandhi joined on 2026-10-20");
            assertThat(chat.ask("What is her job title?").text()).contains("Software Engineer");
        }
        // Pronoun, omitted-subject and "who else" questions stay follow-ups; questions stating their own subject do not.
        assertThat(WaviQuestionFrame.of("When did she join?").independent()).isFalse();
        assertThat(WaviQuestionFrame.of("What about last month?").independent()).isFalse();
        assertThat(WaviQuestionFrame.of("And their reporting manager?").independent()).isFalse();
        assertThat(WaviQuestionFrame.of("Who else?").independent()).isFalse();
        assertThat(WaviQuestionFrame.of("Who joined recently?").independent()).isTrue();
        assertThat(WaviQuestionFrame.of("Who are my employers?").independent()).isTrue();
    }

    @Test
    void anEmployeeAskingWhoJoinedRecentlyGetsNoDirectoryAfterAnyTopic() {
        for (String topic : java.util.Arrays.asList(null, "What are my pending works?", "Show my timesheets")) {
            Conversation chat = chatAs(WaviEvaluationWorld.EMPLOYEE, true);
            if (topic != null) chat.ask(topic);
            assertThat(chat.ask("Who joined recently?").text()).as("after " + topic)
                    .contains("only to HR and Company Admin").doesNotContain("Sowmya", "Ravi Teja");
            verify(chat.world.employees, never()).list(anyString());
        }
    }
    @Test
    void aNamedPersonQuestionAfterAnotherPersonIsAboutTheNamedPersonNotThePreviousOne() {
        for (boolean routed : ROUTING_MODES) {
            Conversation chat = chatAs(WaviEvaluationWorld.ADMIN, routed);
            assertThat(chat.ask("What is Hari Kumar's employee ID?").text()).contains("WPT-2");
            assertThat(chat.ask("who is sowmya?").text()).as("routed=" + routed).contains("Sowmya Grandhi")
                    .doesNotContain("Hari Kumar:");
        }
    }
    // --- "Requests" without a kind: ask which, then answer from the existing review and request tools -------------

    private static final String REQUEST_SCOPE_QUESTION =
            "Do you mean requests waiting for your review, or your own profile change or document requests?";
    private static final String REQUEST_KIND_QUESTION =
            "Which requests do you mean: profile change requests or document requests?";

    @Test
    void aReviewerAskingHowManyRequestsTheyHaveIsAskedWhichAndTheAnswerResolvesIt() {
        for (WaviEvaluationWorld.Person reviewer : List.of(WaviEvaluationWorld.ADMIN, WaviEvaluationWorld.HR)) {
            for (boolean routed : ROUTING_MODES) {
                for (String topic : java.util.Arrays.asList(null, "What are my pending works?")) {
                    String where = reviewer.role() + (routed ? " routed" : " fixed") + " after " + topic;
                    Conversation chat = chatAs(reviewer, routed);
                    if (topic != null) chat.ask(topic);
                    String asked = chat.ask("How many requests do I have?").text();
                    assertThat(asked).as(where).isEqualTo(REQUEST_SCOPE_QUESTION);
                    // The reply resolves the clarification: the reviewer's queues, from the existing count service.
                    String review = chat.ask("the ones waiting for my review").text();
                    assertThat(review).as(where).contains("pending profile change requests")
                            .doesNotContainIgnoringCase("can't").doesNotContainIgnoringCase("cannot");
                }
                Conversation own = chatAs(reviewer, routed);
                own.ask("How many requests do I have?");
                assertThat(own.ask("my profile change requests").text()).as(reviewer.role() + " own")
                        .containsIgnoringCase("profile change request").doesNotContain("awaiting approval in your organization");
            }
        }
    }

    @Test
    void howToApproveRequestsAsksWhichKindAndThenGivesThatKindsApprovalSteps() {
        for (boolean routed : ROUTING_MODES) {
            for (String topic : java.util.Arrays.asList(null, "What are my pending works?")) {
                String where = (routed ? "routed" : "fixed") + " after " + topic;
                Conversation chat = chatAs(WaviEvaluationWorld.ADMIN, routed);
                if (topic != null) chat.ask(topic);
                assertThat(chat.ask("How to approve requests?").text()).as(where).isEqualTo(REQUEST_KIND_QUESTION);
                assertThat(chat.ask("profile change requests").text()).as(where)
                        .contains("select a pending request to review it").doesNotContain("Document Approvals");

                Conversation documents = chatAs(WaviEvaluationWorld.ADMIN, routed);
                if (topic != null) documents.ask(topic);
                documents.ask("How to approve requests?");
                assertThat(documents.ask("document requests").text()).as(where).contains("Open Document Approvals");
            }
        }
    }

    @Test
    void anEmployeesRequestsAreOnlyTheirOwnAndNeverAReviewQueue() {
        for (boolean routed : ROUTING_MODES) {
            Conversation chat = chatAs(WaviEvaluationWorld.EMPLOYEE, routed);
            String reply = chat.ask("How many requests do I have?").text();
            assertThat(reply).as("routed=" + routed).doesNotContain(REQUEST_SCOPE_QUESTION, "waiting for your review",
                    "in your organization", "Ravi Teja");
            verify(chat.world.employees, never()).list(anyString());
        }
    }
    @Test
    void hrAskingHowToApproveProfileChangeRequestsGetsTheReviewStepsNotTheSubmissionSteps() {
        for (boolean routed : ROUTING_MODES) {
            Conversation chat = chatAs(WaviEvaluationWorld.HR, routed);
            assertThat(chat.ask("How to approve requests?").text()).isEqualTo(REQUEST_KIND_QUESTION);
            assertThat(chat.ask("profile change requests").text()).as("routed=" + routed)
                    .contains("select a pending request to review it").doesNotContain("submit a request");
            assertThat(chatAs(WaviEvaluationWorld.HR, routed).ask("How do I approve profile change requests?").text())
                    .contains("select a pending request to review it");
            assertThat(chatAs(WaviEvaluationWorld.EMPLOYEE, routed).ask("How do I approve profile change requests?").text())
                    .contains("Employees don't review profile change requests");
        }
    }
    // --- Typos are normalized before framing; failed or ambiguous turns never capture explicit questions ----------

    private static final String CONTEXT_MARKER = "previous answered request in this conversation: ";

    /**
     * Emulates the live decision observed in manual testing: shown the pending-work context, Claude answered a
     * person question with a requests clarification; without context it chose the person lookup.
     */
    private static void routeLikeObservedLiveClaude(Conversation chat) {
        Mockito.doAnswer(call -> {
            String prompt = call.<String>getArgument(1).toLowerCase(java.util.Locale.ROOT);
            int at = prompt.indexOf(CONTEXT_MARKER);
            boolean context = at >= 0 && !prompt.startsWith("none", at + CONTEXT_MARKER.length());
            if (context && prompt.contains("who is")) {
                return WaviEvaluationWorld.routed("{\"decision\":{\"tool\":\"clarify\",\"arguments\":{\"topic\":\"REQUESTS\","
                        + "\"missing\":\"SCOPE\"}},\"confidence\":\"HIGH\",\"language\":\"english\"}");
            }
            return WaviEvaluationWorld.routed(simulatedDecision(call.<String>getArgument(1)));
        }).when(chat.world.claude).routeJsonWithUsage(anyString(), anyString(), Mockito.anyMap(), Mockito.anyInt(), anyString());
    }

    private static final List<String> UNRELATED_TOPICS = java.util.Arrays.asList(null, "What are my pending works?",
            "Show submitted timesheets", "What is Sowmya's employee ID?");

    @Test
    void aDoubledKeystrokeIsCorrectedBeforeTheQuestionIsFramed() {
        assertThat(WaviSpelling.correct("wwho is john")).isEqualTo("who is john");
        assertThat(WaviSpelling.correct("whhat is myy last name")).isEqualTo("what is my last name");
        assertThat(WaviSpelling.correct("show my timmesheets")).isEqualTo("show my timesheets");
        // Names and ordinary words are left alone.
        assertThat(WaviSpelling.correct("who is anna")).isEqualTo("who is anna");
        assertThat(WaviQuestionFrame.of("wwho is john").independent()).isTrue();
        assertThat(WaviQuestionFrame.of("who is john").independent()).isTrue();
    }

    @Test
    void aMistypedWhoQuestionAndThenAnOwnNameQuestionAreAnsweredTheSameAfterAnyTopic() {
        for (WaviEvaluationWorld.Person reviewer : List.of(WaviEvaluationWorld.ADMIN, WaviEvaluationWorld.HR)) {
            String ownLastName = reviewer == WaviEvaluationWorld.ADMIN
                    ? "Waypoint doesn't store your last name separately; your name is recorded as one full name: Vidya Admin Example."
                    : "Your last name in Waypoint is Kumar.";
            for (boolean routed : ROUTING_MODES) {
                for (String topic : UNRELATED_TOPICS) {
                    String where = reviewer.role() + (routed ? " routed" : " fixed") + " after " + topic;
                    Conversation chat = chatAs(reviewer, false);
                    if (routed) {
                        chat = new Conversation(reviewer, routedOptions());
                        routeLikeObservedLiveClaude(chat);
                        if (reviewer == WaviEvaluationWorld.ADMIN) {
                            var profile = adminProfile("TARDUADMIN", "login@acme.example", "Vidya Admin Example",
                                    "owner@acme.example");
                            Mockito.when(chat.world.employees.getOwnDetail(WaviEvaluationWorld.ADMIN.userId())).thenReturn(profile);
                            Mockito.when(chat.world.employees.isSoleCompanyAdmin(WaviEvaluationWorld.ADMIN.userId())).thenReturn(true);
                        }
                        Mockito.when(chat.world.employees.list(reviewer.userId())).thenReturn(List.of(
                                summary("e1", "WPT-1", "Sowmya Grandhi", "Software Engineer", java.time.LocalDate.of(2026, 10, 20), "Hari Kumar")));
                    }
                    if (topic != null) chat.ask(topic);
                    String who = chat.ask("wwho is john").text();
                    assertThat(who).as(where).doesNotContain("Do you mean requests", "profile change or document",
                            "waiting for review");
                    if (routed) assertThat(who).as(where).contains("couldn't find that person");
                    assertThat(chat.ask("what is my last name").text()).as(where).isEqualTo(ownLastName);
                    // The verified name is answered locally: never sent to a model, never in a router prompt.
                    verify(chat.world.claude, never()).chat(anyString(), anyList());
                    if (routed) {
                        org.mockito.ArgumentCaptor<String> prompts = org.mockito.ArgumentCaptor.forClass(String.class);
                        verify(chat.world.claude, Mockito.atLeastOnce()).routeJsonWithUsage(anyString(), prompts.capture(),
                                Mockito.anyMap(), Mockito.anyInt(), anyString());
                        assertThat(prompts.getAllValues()).as(where)
                                .noneMatch(p -> p.contains("Vidya Admin Example") || p.contains("Kumar."));
                    }
                }
            }
        }
    }

    @Test
    void anExplicitQuestionAfterAnAmbiguousClarificationIsNotTreatedAsItsAnswer() {
        for (WaviEvaluationWorld.Person person : List.of(WaviEvaluationWorld.ADMIN, WaviEvaluationWorld.HR,
                WaviEvaluationWorld.EMPLOYEE)) {
            for (boolean routed : ROUTING_MODES) {
                Conversation chat = chatAs(person, routed);
                // A clarification is left pending (the frame's own requests clarification, or for an Employee the
                // router's), exactly as after the live "wwho is john" turn.
                String asked = chat.ask("How many requests do I have?").text();
                if (person != WaviEvaluationWorld.EMPLOYEE) assertThat(asked).isEqualTo(REQUEST_SCOPE_QUESTION);
                String last = chat.ask("what is my last name").text();
                assertThat(last).as(person.role() + " routed=" + routed).containsIgnoringCase("last name")
                        .doesNotContainIgnoringCase("profile change request").doesNotContain("status");
                // A genuine slot answer still resolves the clarification.
                if (person != WaviEvaluationWorld.EMPLOYEE) {
                    Conversation resolving = chatAs(person, routed);
                    resolving.ask("How many requests do I have?");
                    assertThat(resolving.ask("the ones waiting for my review").text())
                            .contains("pending profile change requests");
                }
            }
        }
    }
    // --- A MEDIUM-confidence person lookup that names the person runs the scoped lookup -------------------------

    private static String decision(String tool, String arguments, String confidence) {
        return "{\"decision\":{\"tool\":\"" + tool + "\",\"arguments\":" + arguments + "},\"confidence\":\"" + confidence
                + "\",\"language\":\"english\"}";
    }

    /** Person questions get get_team_member at the given confidence; anything else as the usual simulation. */
    private static Conversation personLookupChat(WaviEvaluationWorld.Person person, String confidence,
                                                 List<com.waypoint.dto.myhr.EmployeeSummaryResponse> people) {
        Conversation chat = new Conversation(person, routedOptions());
        Mockito.doAnswer(call -> {
            String prompt = call.<String>getArgument(1);
            String text = prompt.toLowerCase(java.util.Locale.ROOT);
            String question = text.contains("current message") ? text.substring(text.lastIndexOf("current message")) : text;
            return WaviEvaluationWorld.routed(question.contains("who is") || question.contains("who's")
                    ? decision("get_team_member", "{\"field\":\"SUMMARY\"}", confidence) : simulatedDecision(prompt));
        }).when(chat.world.claude).routeJsonWithUsage(anyString(), anyString(), Mockito.anyMap(), Mockito.anyInt(), anyString());
        if (people != null) Mockito.when(chat.world.employees.list(person.userId())).thenReturn(people);
        return chat;
    }

    private static final List<com.waypoint.dto.myhr.EmployeeSummaryResponse> ADMIN_PEOPLE = List.of(
            summary("e1", "WPT-1", "Sowmya Grandhi", "Software Engineer", java.time.LocalDate.of(2026, 10, 20), "Hari Kumar"),
            summary("e2", "WPT-2", "Hari Kumar", "HR Business Partner", java.time.LocalDate.of(2019, 4, 1), null));

    @Test
    void knownAndUnknownPeopleAreLookedUpAtHighAndMediumConfidenceAfterAnyTopic() {
        for (String confidence : List.of("HIGH", "MEDIUM")) {
            for (String topic : java.util.Arrays.asList(null, "What are my pending works?", "Show submitted timesheets",
                    "What is Sowmya's employee ID?")) {
                String where = confidence + " after " + topic;
                for (String known : List.of("who is sowmya", "Who is Sowmya?")) {
                    Conversation chat = personLookupChat(WaviEvaluationWorld.ADMIN, confidence, ADMIN_PEOPLE);
                    if (topic != null) chat.ask(topic);
                    assertThat(chat.ask(known).text()).as(where + " " + known)
                            .contains("Sowmya Grandhi", "Software Engineer").doesNotContain(MyHrChatService.CLARIFY_ADMIN);
                }
                for (String unknown : List.of("who is john", "wwho is john", "Who is John?")) {
                    Conversation chat = personLookupChat(WaviEvaluationWorld.ADMIN, confidence, ADMIN_PEOPLE);
                    if (topic != null) chat.ask(topic);
                    assertThat(chat.ask(unknown).text()).as(where + " " + unknown)
                            .contains("couldn't find that person").doesNotContain(MyHrChatService.CLARIFY_ADMIN);
                }
            }
        }
    }

    @Test
    void hrLooksUpOnlyTheirDirectReportsAtMediumConfidence() {
        for (String topic : java.util.Arrays.asList(null, "What are my pending works?")) {
            Conversation chat = personLookupChat(WaviEvaluationWorld.HR, "MEDIUM", null);
            if (topic != null) chat.ask(topic);
            assertThat(chat.ask("who is asha").text()).as("after " + topic).contains("Asha Rao")
                    .doesNotContain(MyHrChatService.CLARIFY_HR);
            Conversation unknown = personLookupChat(WaviEvaluationWorld.HR, "MEDIUM", null);
            if (topic != null) unknown.ask(topic);
            assertThat(unknown.ask("who is john").text()).as("after " + topic)
                    .contains("couldn't find that person", "your active direct reports")
                    .doesNotContain(MyHrChatService.CLARIFY_HR);
        }
    }

    @Test
    void anEmployeesMediumConfidencePersonLookupIsStillRefusedWithoutReadingTheDirectory() {
        for (String confidence : List.of("HIGH", "MEDIUM")) {
            Conversation chat = personLookupChat(WaviEvaluationWorld.EMPLOYEE, confidence, null);
            String reply = chat.ask("who is ravi").text();
            assertThat(reply).as(confidence).isEqualTo(MyHrChatService.OTHER_PERSON_REPLY);
            verify(chat.world.employees, never()).list(anyString());
        }
    }

    @Test
    void aMediumConfidenceLookupOfSeveralMatchesOrOfAPronounAsksWhichPersonNeverTheRequestsMenu() {
        List<com.waypoint.dto.myhr.EmployeeSummaryResponse> twoSowmyas = List.of(ADMIN_PEOPLE.get(0),
                summary("e3", "WPT-3", "Sowmya Rao", "QA Engineer", java.time.LocalDate.of(2025, 1, 6), "Hari Kumar"));
        String several = personLookupChat(WaviEvaluationWorld.ADMIN, "MEDIUM", twoSowmyas).ask("who is sowmya").text();
        assertThat(several).contains("Sowmya Grandhi", "Sowmya Rao").doesNotContain("Employee ID:",
                MyHrChatService.CLARIFY_ADMIN);

        String pronoun = personLookupChat(WaviEvaluationWorld.ADMIN, "MEDIUM", ADMIN_PEOPLE).ask("who is he?").text();
        assertThat(pronoun).isEqualTo("Which person in your authorized scope do you mean?");
    }

    @Test
    void aCompanyAdminNeverReadsAnotherOrganizationsPeopleThroughAMediumConfidenceLookup() {
        Conversation chat = personLookupChat(WaviEvaluationWorld.ADMIN, "MEDIUM", ADMIN_PEOPLE);
        // Only the signed-in admin's own organization's list is ever read; a name outside it is simply not found.
        assertThat(chat.ask("who is zed outsider").text()).contains("couldn't find that person");
        verify(chat.world.employees, Mockito.atLeastOnce()).list(WaviEvaluationWorld.ADMIN.userId());
        verify(chat.world.employees, never()).list(org.mockito.ArgumentMatchers.argThat(
                id -> !WaviEvaluationWorld.ADMIN.userId().equals(id)));
    }

    @Test
    void otherToolsKeepTheirMediumConfidenceClarification() {
        Conversation chat = new Conversation(WaviEvaluationWorld.ADMIN, routedOptions());
        Mockito.doReturn(WaviEvaluationWorld.routed(decision("get_review_queue", "{\"queue\":\"TIMESHEETS\"}", "MEDIUM")))
                .when(chat.world.claude).routeJsonWithUsage(anyString(), anyString(), Mockito.anyMap(), Mockito.anyInt(), anyString());
        assertThat(chat.ask("timesheet things for me to look at maybe").text()).isEqualTo(MyHrChatService.CLARIFY_ADMIN);
    }
}
