package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrChatHistoryMessage;
import com.waypoint.dto.myhr.MyHrDocumentRequestQueueSummary;
import com.waypoint.dto.myhr.MyHrOwnProfileChangeHistory;
import com.waypoint.dto.myhr.MyHrOwnProfileResponse;
import com.waypoint.dto.myhr.MyHrProfileChangeQueueSummary;
import com.waypoint.dto.myhr.MyHrRecentTimesheetStatus;
import com.waypoint.dto.myhr.MyHrTimesheetQueueSummary;
import com.waypoint.enums.ProfileChangeField;
import com.waypoint.enums.ProfileChangeStatus;
import com.waypoint.enums.TimesheetStatus;
import com.waypoint.enums.UserRole;
import com.waypoint.security.TestActors;
import com.waypoint.security.VerifiedMyHrActor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.Invocation;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.waypoint.enums.UserRole.COMPANY_ADMIN;
import static com.waypoint.enums.UserRole.EMPLOYEE;
import static com.waypoint.enums.UserRole.HR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The Wavi routing audit's question matrix as a regression suite: previously working questions, every question
 * the audit found failing, and natural variations, for Employee, HR and Company Admin. Each row names the one
 * authorized lookup that must answer it (or a fixed reply, or the model for general questions) and checks that
 * personal-record and queue questions never reach Claude or Ollama, never get generic how-to guidance, and only
 * ever look up the signed-in user's own id.
 */
class MyHrChatServiceIntentRoutingMatrixTest {
    private static final String HOW_TO_PROFILE = "Open Profile Change Requests and submit a request";
    private static final String HOW_TO_TIMESHEET = "Open Timesheets and choose Enter Timesheet";

    /** Where a question must be answered. */
    enum Route {
        PROFILE_DETAIL(c -> verify(c.employees).getOwnDetail(c.id())),
        PROFILE_STATUS(c -> verify(c.profiles).mostRecentOwnStatus(c.id())),
        PROFILE_HISTORY(c -> verify(c.profiles).mostRecentOwnHistory(c.id())),
        PHONE_HISTORY(c -> verify(c.profiles).mostRecentOwnHistoryForField(c.id(), ProfileChangeField.PHONE_NUMBER)),
        ADDRESS_HISTORY(c -> verify(c.profiles).mostRecentOwnHistoryForField(c.id(), ProfileChangeField.ADDRESS)),
        NAME_HISTORY(c -> verify(c.profiles).mostRecentOwnHistoryForField(c.id(), ProfileChangeField.NAME)),
        TIMESHEET_STATUS(c -> verify(c.timesheets).getMostRecentStatusForUser(c.id())),
        TIMESHEET_SUBMISSION(c -> verify(c.timesheets).getLatestSubmissionForUser(c.id())),
        /** A reviewer's scoped timesheet list (the same list the review page uses), read for the reviewer only. */
        TEAM_TIMESHEETS(c -> verify(c.timesheets).listForUser(c.id())),
        DOCUMENT_HISTORY(c -> verify(c.documents).listOwnHistory(c.id())),
        HR_OWN_DOCUMENTS(c -> verify(c.documents).list(c.id(), 0, 1, null, null, null, null, "mine")),
        PROFILE_QUEUE(c -> verify(c.profiles).queueSummary(c.id())),
        TIMESHEET_QUEUE(c -> verify(c.timesheets).queueSummary(c.id())),
        DOCUMENT_QUEUE(c -> verify(c.documents).queueSummary(c.id())),
        /** A reviewer's name lookup that finds no one in scope: only their own people list is read, no record. */
        SCOPE_ONLY(c -> verifyNoInteractions(c.profiles, c.timesheets, c.documents)),
        /** A deterministic reply with no record lookup: a denial, refusal or explanation. */
        FIXED(c -> verifyNoInteractions(c.profiles, c.timesheets, c.documents, c.employees)),
        /** General questions keep reaching the configured model. */
        MODEL(c -> verify(c.claude).chat(anyString(), anyList()));

        private final Consumer<Clients> verification;

        Route(Consumer<Clients> verification) {
            this.verification = verification;
        }
    }

    static Stream<Arguments> matrix() {
        return Stream.of(
                // --- Previously working: unchanged ---
                row(EMPLOYEE, "What is my employee ID?", Route.PROFILE_DETAIL, "Your employee ID is TARDU004."),
                row(EMPLOYEE, "Can you tell me my employee ID?", Route.PROFILE_DETAIL, "Your employee ID is TARDU004."),
                row(EMPLOYEE, "What is my latest timesheet status?", Route.TIMESHEET_STATUS, "Your most recent timesheet"),
                row(EMPLOYEE, "When did I last submit my timesheet?", Route.TIMESHEET_SUBMISSION, null),
                row(EMPLOYEE, "My timesheet was rejected", Route.TIMESHEET_STATUS, "I checked: your most recent timesheet"),
                row(EMPLOYEE, "What is my profile change request status?", Route.PROFILE_STATUS, "Your profile change request status is PENDING."),
                row(EMPLOYEE, "What is the status of my profile change request?", Route.PROFILE_STATUS, "status is PENDING"),
                row(EMPLOYEE, "Is my profile update approved?", Route.PROFILE_STATUS, "status is PENDING"),
                row(EMPLOYEE, "Did my phone number change get approved?", Route.PHONE_HISTORY, "Your phone number change was approved on 2026-10-21."),
                row(EMPLOYEE, "When did I last update my phone number?", Route.PHONE_HISTORY, "Your phone number was last updated on 2026-10-21."),
                row(EMPLOYEE, "What is the status of my latest document request?", Route.DOCUMENT_HISTORY, "I couldn't find a document request of yours"),
                row(EMPLOYEE, "Is my offer letter still pending?", Route.DOCUMENT_HISTORY, "offer letter request"),
                row(EMPLOYEE, "What documents have I requested?", Route.DOCUMENT_HISTORY, null),
                row(HR, "How many timesheets are waiting for my approval?", Route.TIMESHEET_QUEUE, "You have 2 employee timesheets awaiting your approval."),
                row(HR, "How many profile change requests are pending my review?", Route.PROFILE_QUEUE, "You have 2 profile change requests awaiting your review."),
                row(HR, "How many document requests are pending my approval?", Route.DOCUMENT_QUEUE, "submitted I-983"),
                row(COMPANY_ADMIN, "How many timesheets are waiting for my approval?", Route.TIMESHEET_QUEUE, "in your organization"),
                row(COMPANY_ADMIN, "How many document requests are pending in my organization?", Route.DOCUMENT_QUEUE, "in your organization"),
                row(EMPLOYEE, "How many timesheets are waiting for my approval?", Route.FIXED, "available only to HR and Company Admin"),

                // --- Failed in the audit: generic how-to guidance or the model ---
                row(EMPLOYEE, "What is the status of my latest profile change request?", Route.PROFILE_STATUS, "status is PENDING"),
                row(EMPLOYEE, "Can you tell me the status of my latest profile change request?", Route.PROFILE_STATUS, "status is PENDING"),
                row(EMPLOYEE, "Has my profile change request been approved?", Route.PROFILE_STATUS, "status is PENDING"),
                row(EMPLOYEE, "Was my profile change approved?", Route.PROFILE_STATUS, "status is PENDING"),
                row(EMPLOYEE, "Did my profile change request get rejected?", Route.PROFILE_STATUS, "status is PENDING"),
                row(EMPLOYEE, "What's the status of my profile change?", Route.PROFILE_STATUS, "status is PENDING"),
                row(EMPLOYEE, "Check my profile change request status", Route.PROFILE_STATUS, "status is PENDING"),
                row(EMPLOYEE, "Status of my profile change request", Route.PROFILE_STATUS, "status is PENDING"),
                row(EMPLOYEE, "my profile change request status", Route.PROFILE_STATUS, "status is PENDING"),
                row(EMPLOYEE, "Any update on my profile change request?", Route.PROFILE_STATUS, "status is PENDING"),
                row(EMPLOYEE, "What happened to my profile change request?", Route.PROFILE_STATUS, "status is PENDING"),
                row(EMPLOYEE, "Did HR approve my profile change?", Route.PROFILE_STATUS, "status is PENDING"),
                row(EMPLOYEE, "Was my profile change request rejected?", Route.PROFILE_STATUS, "status is PENDING"),
                row(EMPLOYEE, "When was my profile change request approved?", Route.PROFILE_HISTORY, "Your latest profile change request was approved on 2026-10-21."),
                row(EMPLOYEE, "Can you tell me when my profile change request was approved?", Route.PROFILE_HISTORY, "approved on 2026-10-21"),
                row(EMPLOYEE, "When did I submit my profile change request?", Route.PROFILE_HISTORY, "You submitted your latest profile change request on 2026-10-20."),
                row(EMPLOYEE, "Why was my profile change request rejected?", Route.PROFILE_HISTORY, "so it wasn't rejected"),
                row(EMPLOYEE, "Has my phone number change request been approved?", Route.PHONE_HISTORY, "Your phone number change was approved on 2026-10-21."),
                row(EMPLOYEE, "Has my phone number change been approved?", Route.PHONE_HISTORY, "approved on 2026-10-21"),
                row(EMPLOYEE, "Was my phone number change approved?", Route.PHONE_HISTORY, "approved on 2026-10-21"),
                row(EMPLOYEE, "Can you tell me if my phone number change was approved?", Route.PHONE_HISTORY, "approved on 2026-10-21"),
                row(EMPLOYEE, "Could you check whether my phone number change request has been approved?", Route.PHONE_HISTORY, "approved on 2026-10-21"),
                row(EMPLOYEE, "What is the status of my phone number change?", Route.PHONE_HISTORY, "approved on 2026-10-21"),
                row(EMPLOYEE, "Has my address change been approved?", Route.ADDRESS_HISTORY, "Your address change was approved on 2026-10-21."),
                row(EMPLOYEE, "Is my address update still pending?", Route.ADDRESS_HISTORY, "Your latest address change was approved"),
                row(EMPLOYEE, "Has my name change been approved?", Route.NAME_HISTORY, "Your name change was approved"),
                row(EMPLOYEE, "What is the status of my latest timesheet?", Route.TIMESHEET_STATUS, "Your most recent timesheet"),
                row(EMPLOYEE, "Was my timesheet approved?", Route.TIMESHEET_STATUS, "Your most recent timesheet"),
                row(EMPLOYEE, "Is my timesheet still pending?", Route.TIMESHEET_STATUS, "Your most recent timesheet"),
                row(EMPLOYEE, "Why was my timesheet rejected?", Route.TIMESHEET_STATUS, "I checked: your most recent timesheet"),
                row(EMPLOYEE, "What is the status of my employment verification letter request?", Route.DOCUMENT_HISTORY, "employment verification letter request"),
                row(EMPLOYEE, "Has my offer letter been approved?", Route.DOCUMENT_HISTORY, "offer letter request"),
                row(EMPLOYEE, "Do I have any pending profile change requests?", Route.PROFILE_STATUS, "Yes, you have a pending profile change request."),
                row(EMPLOYEE, "Do I have any pending document requests?", Route.DOCUMENT_HISTORY, "You don't have any open document requests"),
                row(EMPLOYEE, "Do I have any pending timesheets?", Route.TIMESHEET_STATUS, "Your most recent timesheet"),

                // --- HR and Company Admin: own records, own document requests, pending questions ---
                row(HR, "What is the status of my latest profile change request?", Route.PROFILE_STATUS, "status is PENDING"),
                row(COMPANY_ADMIN, "Was my profile change approved?", Route.PROFILE_STATUS, "status is PENDING"),
                row(HR, "Has my phone number change request been approved?", Route.PHONE_HISTORY, "approved on 2026-10-21"),
                row(HR, "Was my timesheet approved?", Route.TIMESHEET_STATUS, "Your most recent timesheet"),
                // "Is my ... still pending?" is the reviewer's own record, not their review queue.
                row(HR, "Is my timesheet still pending?", Route.TIMESHEET_STATUS, "Your most recent timesheet"),
                row(COMPANY_ADMIN, "Is my profile change request still pending?", Route.PROFILE_STATUS, "status is PENDING"),
                row(HR, "What is the status of my latest document request?", Route.HR_OWN_DOCUMENTS, "Your latest document request for yourself (Employment letter) is pending"),
                row(HR, "What documents have I requested?", Route.HR_OWN_DOCUMENTS, "Your latest document request for yourself"),
                row(COMPANY_ADMIN, "What is the status of my latest document request?", Route.FIXED, "Company Admin accounts don't request documents for themselves"),
                row(HR, "Do I have any pending profile change requests?", Route.PROFILE_QUEUE, "assigned to you"),
                row(HR, "Do I have any pending timesheets?", Route.TIMESHEET_QUEUE, "You have 2 submitted timesheets awaiting your review."),
                row(HR, "Do I have any pending document requests?", Route.DOCUMENT_QUEUE, "assigned for fulfillment"),
                row(COMPANY_ADMIN, "Do I have any pending profile change requests?", Route.PROFILE_QUEUE, "in your organization"),
                row(COMPANY_ADMIN, "Do I have any pending timesheets?", Route.TIMESHEET_QUEUE, "in your organization"),

                // --- Another person's record and unmatched queue counts: deterministic, no lookup ---
                row(EMPLOYEE, "What is TARDU005's profile change request status?", Route.FIXED, MyHrChatService.OTHER_PERSON_REPLY),
                row(EMPLOYEE, "What is Sarah's timesheet status?", Route.FIXED, MyHrChatService.OTHER_PERSON_REPLY),
                row(EMPLOYEE, "Show me TARDU005's employee ID", Route.FIXED, MyHrChatService.OTHER_PERSON_REPLY),
                // An employee ID that is not among the reviewer's own people: said so (only their people list is read).
                row(HR, "What is TARDU005's profile change request status?", Route.SCOPE_ONLY, MyHrChatService.PERSON_NOT_IN_SCOPE_HR),
                // A named person who is not in the reviewer's scope: said so, rather than generic page guidance.
                row(COMPANY_ADMIN, "What is Sarah's timesheet status?", Route.SCOPE_ONLY, MyHrChatService.PERSON_NOT_IN_SCOPE_ADMIN),
                row(EMPLOYEE, "How many timesheets were rejected this month?", Route.FIXED, "available only to HR and Company Admin"),
                // A status count over a period is now answered from the reviewer's own scoped list (none here), not refused.
                row(HR, "How many timesheets were rejected this month?", Route.TEAM_TIMESHEETS, "timesheets for your review scope covering this month"),
                row(COMPANY_ADMIN, "How many profile change requests are still open?", Route.FIXED, "in your organization"),

                // --- General and how-to questions keep their routes ---
                row(EMPLOYEE, "How do I update my phone number?", Route.FIXED, HOW_TO_PROFILE),
                row(EMPLOYEE, "How do I submit my timesheet?", Route.FIXED, HOW_TO_TIMESHEET),
                row(EMPLOYEE, "hello", Route.FIXED, "Hello! How can I help you today?"),
                row(EMPLOYEE, "Tell me about vacation policy", Route.FIXED, "can't check the approved company policies"),
                // Waypoint records no leave balance: a fixed honest reply, never a model's guess.
                row(EMPLOYEE, "Hello Wavi, what is my PTO balance?", Route.FIXED, "I can't see leave or PTO balances"),
                row(EMPLOYEE, "When should I submit my timesheet?", Route.MODEL, "model answer"),
                row(HR, "Can you help me plan my week?", Route.MODEL, "model answer"));
    }

    private static Arguments row(UserRole role, String question, Route route, String expected) {
        return Arguments.of(role, question, route, expected);
    }

    @ParameterizedTest(name = "{0} | {1} -> {2}")
    @MethodSource("matrix")
    void routesEachQuestionToItsAuthorizedAnswer(UserRole role, String question, Route route, String expected) {
        Clients clients = new Clients(role);

        String answer = clients.ask(question);

        route.verification.accept(clients);
        if (expected != null) assertThat(answer).as(question).contains(expected);
        if (route != Route.MODEL) verifyNoInteractions(clients.claude, clients.ollama);
        // A personal-status lookup is never replaced by generic how-to guidance.
        if (route != Route.FIXED && route != Route.MODEL) {
            assertThat(answer).as(question).doesNotContain(HOW_TO_PROFILE).doesNotContain(HOW_TO_TIMESHEET);
        }
        clients.assertOnlyOwnIdWasLookedUp();
    }

    @Test
    void eachActorOnlyEverLooksUpItsOwnRecordsAcrossOrganizations() {
        for (VerifiedMyHrActor actor : List.of(TestActors.actor("employee-a", "org-a", EMPLOYEE),
                TestActors.actor("employee-b", "org-b", EMPLOYEE), TestActors.hr("hr-b", "org-b"))) {
            Clients clients = new Clients(actor);
            for (String question : List.of("What is the status of my latest profile change request?",
                    "Has my phone number change request been approved?", "When was my profile change request approved?",
                    "Was my timesheet approved?", "Do I have any pending profile change requests?")) {
                clients.ask(question);
            }
            clients.assertOnlyOwnIdWasLookedUp();
            verifyNoInteractions(clients.claude, clients.ollama);
        }
    }

    @Test
    void claudeStillFallsBackToOllamaForGeneralQuestions() {
        Clients clients = new Clients(EMPLOYEE);
        when(clients.claude.chat(anyString(), anyList())).thenThrow(new ClaudeChatClient.ClaudeUnavailableException());
        when(clients.ollama.chat(anyString(), anyList())).thenReturn("ollama answer");

        assertThat(clients.ask("Can you help me plan my week?")).isEqualTo("ollama answer");
        assertThat(clients.ask("What is the status of my latest profile change request?")).contains("status is PENDING");
        verify(clients.ollama).chat(eq("Can you help me plan my week?"), anyList());
        verifyNoInteractions(clients.timesheets, clients.documents);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Your phone number change was approved on 2026-10-21.",
            "Your address change was approved on 2026-10-21.",
            "Your personal email update is pending, requested on 2026-10-20.",
            "Your name change was rejected on 2026-10-21.",
    })
    void profileChangeDetailsInHistoryNeverReachTheModel(String earlierAnswer) {
        Clients clients = new Clients(EMPLOYEE);

        clients.service.chat(clients.actor, "Can you help me plan my week?", List.of(
                new MyHrChatHistoryMessage("user", "Check that for me"),
                new MyHrChatHistoryMessage("assistant", earlierAnswer)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MyHrChatHistoryMessage>> sent = ArgumentCaptor.forClass(List.class);
        verify(clients.claude).chat(eq("Can you help me plan my week?"), sent.capture());
        assertThat(sent.getValue()).isEmpty();
    }

    /** Mocked services with representative data; every lookup is recorded for the scoping check. */
    private static final class Clients {
        private final ClaudeChatClient claude = mock(ClaudeChatClient.class);
        private final OllamaClient ollama = mock(OllamaClient.class);
        private final TimesheetService timesheets = mock(TimesheetService.class);
        private final ProfileChangeRequestService profiles = mock(ProfileChangeRequestService.class);
        private final EmployeeManagementService employees = mock(EmployeeManagementService.class);
        private final DocumentRequestService documents = mock(DocumentRequestService.class);
        private final VerifiedMyHrActor actor;
        private final MyHrChatService service;

        Clients(UserRole role) {
            this(TestActors.actor("user-" + role.name().toLowerCase(), "org-1", role));
        }

        Clients(VerifiedMyHrActor actor) {
            this.actor = actor;
            // a mocked Ollama stands for the verified local model the fallback requires
            org.mockito.Mockito.lenient().when(ollama.verifiedLocalModel()).thenReturn(true);
            String id = actor.userId();
            LocalDateTime requested = LocalDateTime.of(2026, 10, 20, 9, 0);
            LocalDateTime processed = LocalDateTime.of(2026, 10, 21, 9, 0);
            when(claude.chat(anyString(), anyList())).thenReturn("model answer");
            MyHrOwnProfileResponse profile = mock(MyHrOwnProfileResponse.class);
            when(profile.employeeId()).thenReturn("TARDU004");
            when(employees.getOwnDetail(id)).thenReturn(profile);
            when(timesheets.getMostRecentStatusForUser(id)).thenReturn(Optional.of(new MyHrRecentTimesheetStatus(
                    LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 19), TimesheetStatus.SUBMITTED)));
            when(timesheets.getLatestSubmissionForUser(id)).thenReturn(Optional.empty());
            when(timesheets.queueSummary(id)).thenReturn(new MyHrTimesheetQueueSummary(4, 2, 1, 1));
            when(profiles.mostRecentOwnStatus(id)).thenReturn(Optional.of(ProfileChangeStatus.PENDING));
            when(profiles.mostRecentOwnHistory(id)).thenReturn(Optional.of(
                    new MyHrOwnProfileChangeHistory(ProfileChangeStatus.APPROVED, requested, processed, processed)));
            when(profiles.mostRecentOwnHistoryForField(eq(id), any())).thenReturn(Optional.of(
                    new MyHrOwnProfileChangeHistory(ProfileChangeStatus.APPROVED, requested, processed, processed)));
            when(profiles.queueSummary(id)).thenReturn(new MyHrProfileChangeQueueSummary(3, 2, 1, 0));
            when(documents.listOwnHistory(id)).thenReturn(List.of());
            when(documents.queueSummary(id)).thenReturn(new MyHrDocumentRequestQueueSummary(3, 1, 1, 1, 1, 0));
            com.waypoint.dto.myhr.DocumentRequestPageResponse ownRequests = hrOwnRequests();
            when(documents.list(id, 0, 1, null, null, null, null, "mine")).thenReturn(ownRequests);
            service = new MyHrChatService(claude, ollama, timesheets, profiles, employees, documents,
                    "claude", null, false, false);
        }

        String id() {
            return actor.userId();
        }

        String ask(String question) {
            return service.chat(actor, question, List.of()).message();
        }

        void assertOnlyOwnIdWasLookedUp() {
            for (Object service : List.of(timesheets, profiles, employees, documents)) {
                for (Invocation invocation : mockingDetails(service).getInvocations()) {
                    assertThat(invocation.getArgument(0, Object.class)).as(invocation.toString()).isEqualTo(actor.userId());
                }
            }
        }

        private static com.waypoint.dto.myhr.DocumentRequestPageResponse hrOwnRequests() {
            com.waypoint.dto.myhr.DocumentRequestResponse request = mock(com.waypoint.dto.myhr.DocumentRequestResponse.class);
            when(request.requestTitle()).thenReturn("Employment letter");
            when(request.status()).thenReturn(com.waypoint.enums.DocumentRequestStatus.PENDING);
            when(request.requestedAt()).thenReturn(LocalDateTime.of(2026, 10, 22, 9, 0));
            return new com.waypoint.dto.myhr.DocumentRequestPageResponse(List.of(request), 0, 1, 1, 1);
        }
    }
}
