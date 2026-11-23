package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrOwnProfileChangeHistory;
import com.waypoint.dto.myhr.MyHrOwnProfileResponse;
import com.waypoint.dto.myhr.MyHrProfileChangeQueueSummary;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.EmploymentType;
import com.waypoint.enums.ProfileChangeField;
import com.waypoint.enums.ProfileChangeStatus;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.UnauthorizedException;
import com.waypoint.security.TestActors;
import com.waypoint.security.VerifiedMyHrActor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The Employee questions from live testing, answered from the signed-in user's own records through
 * the existing scoped service lookups. None of them reach Claude or Ollama.
 */
class MyHrChatServiceEmployeeStatusTest {
    private static final VerifiedMyHrActor EMPLOYEE = TestActors.actor("employee-4", "org-1", UserRole.EMPLOYEE);
    private static final VerifiedMyHrActor HR = TestActors.hr("hr-1", "org-1");
    private static final VerifiedMyHrActor ADMIN = TestActors.companyAdmin("admin-1", "org-1");
    private static final LocalDateTime REQUESTED = LocalDateTime.of(2026, 10, 20, 9, 0);
    private static final LocalDateTime PROCESSED = LocalDateTime.of(2026, 10, 21, 15, 30);

    @Test
    void theFourLiveEmployeeQuestionsAreAnsweredFromOwnRecordsWithoutAi() {
        Clients clients = new Clients();
        when(clients.employees.getOwnDetail("employee-4")).thenReturn(ownProfile("employee-4", "TARDU004"));
        when(clients.timesheets.getMostRecentStatusForUser("employee-4")).thenReturn(Optional.empty());
        when(clients.profileChanges.mostRecentOwnStatus("employee-4")).thenReturn(Optional.of(ProfileChangeStatus.PENDING));
        when(clients.documents.listOwnHistory("employee-4")).thenReturn(List.of());

        assertThat(ask(clients.service, EMPLOYEE, "What is my employee ID?")).isEqualTo("Your employee ID is TARDU004.");
        assertThat(ask(clients.service, EMPLOYEE, "What is my latest timesheet status?")).isEqualTo("No timesheet found.");
        assertThat(ask(clients.service, EMPLOYEE, "What is the status of my latest profile change request?"))
                .isEqualTo("Your profile change request status is PENDING.");
        assertThat(ask(clients.service, EMPLOYEE, "What is the status of my latest document request?"))
                .isEqualTo("I couldn't find a document request of yours in Waypoint.");
        verify(clients.employees).getOwnDetail("employee-4");
        verify(clients.timesheets).getMostRecentStatusForUser("employee-4");
        verify(clients.profileChanges).mostRecentOwnStatus("employee-4");
        verify(clients.documents).listOwnHistory("employee-4");
        verifyNoInteractions(clients.claude, clients.ollama);
    }

    @Test
    void anEmployeeWithNoProfileChangeRequestGetsTheExistingNoRecordAnswer() {
        Clients clients = new Clients();
        when(clients.profileChanges.mostRecentOwnStatus("employee-4")).thenReturn(Optional.empty());
        when(clients.profileChanges.mostRecentOwnHistory("employee-4")).thenReturn(Optional.empty());
        when(clients.profileChanges.mostRecentOwnHistoryForField("employee-4", ProfileChangeField.PHONE_NUMBER))
                .thenReturn(Optional.empty());

        assertThat(ask(clients.service, EMPLOYEE, "What is the status of my latest profile change request?"))
                .isEqualTo("No profile change request found.");
        assertThat(ask(clients.service, EMPLOYEE, "When was my profile change request approved?"))
                .isEqualTo("I couldn't find a profile change request of yours in Waypoint.");
        assertThat(ask(clients.service, EMPLOYEE, "Has my phone number change request been approved?"))
                .isEqualTo("I couldn't find a phone number change request of yours in Waypoint.");
        verifyNoInteractions(clients.claude, clients.ollama);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "What is my profile change request status?",
            "What is the status of my latest profile change request?",
            "What's the status of my most recent profile update?",
            "Show me my latest profile change request status",
            "Can you tell me the status of my latest profile change request?",
            "Is my latest profile change request still pending?",
            "Has my profile change request been approved?",
            "Did my profile update get approved?",
    })
    void profileChangeStatusVariationsUseTheOwnStatusLookup(String question) {
        Clients clients = new Clients();
        when(clients.profileChanges.mostRecentOwnStatus("employee-4")).thenReturn(Optional.of(ProfileChangeStatus.APPROVED));

        assertThat(ask(clients.service, EMPLOYEE, question)).isEqualTo("Your profile change request status is APPROVED.");
        verify(clients.profileChanges).mostRecentOwnStatus("employee-4");
        verifyNoInteractions(clients.claude, clients.ollama);
    }

    @Test
    void phoneNumberChangeRequestWordingUsesThePhoneHistory() {
        Clients clients = new Clients();
        when(clients.profileChanges.mostRecentOwnHistoryForField("employee-4", ProfileChangeField.PHONE_NUMBER))
                .thenReturn(Optional.of(new MyHrOwnProfileChangeHistory(ProfileChangeStatus.APPROVED, REQUESTED, PROCESSED, PROCESSED)));

        assertThat(ask(clients.service, EMPLOYEE, "Has my phone number change request been approved?"))
                .isEqualTo("Your phone number change was approved on 2026-10-21.");
        assertThat(ask(clients.service, EMPLOYEE, "Did my phone number change get approved?"))
                .isEqualTo("Your phone number change was approved on 2026-10-21.");
        assertThat(ask(clients.service, EMPLOYEE, "Is my phone number change request still pending?"))
                .isEqualTo("Your latest phone number change was approved on 2026-10-21; it is not pending.");
        assertThat(ask(clients.service, EMPLOYEE, "When was my phone number change request approved?"))
                .isEqualTo("Your phone number change was approved on 2026-10-21.");
        verifyNoInteractions(clients.claude, clients.ollama);
    }

    @Test
    void profileChangeApprovalDateReflectsTheLatestRequestsStatus() {
        Clients clients = new Clients();
        when(clients.profileChanges.mostRecentOwnHistory("employee-4")).thenReturn(
                Optional.of(new MyHrOwnProfileChangeHistory(ProfileChangeStatus.APPROVED, REQUESTED, PROCESSED, PROCESSED)),
                Optional.of(new MyHrOwnProfileChangeHistory(ProfileChangeStatus.PENDING, REQUESTED, null, null)),
                Optional.of(new MyHrOwnProfileChangeHistory(ProfileChangeStatus.REJECTED, REQUESTED, PROCESSED, null)),
                Optional.of(new MyHrOwnProfileChangeHistory(ProfileChangeStatus.APPROVED, REQUESTED, null, null)),
                Optional.of(new MyHrOwnProfileChangeHistory(ProfileChangeStatus.PENDING, REQUESTED, null, null)));

        assertThat(ask(clients.service, EMPLOYEE, "When was my profile change request approved?"))
                .isEqualTo("Your latest profile change request was approved on 2026-10-21.");
        assertThat(ask(clients.service, EMPLOYEE, "When was my latest profile update approved?"))
                .isEqualTo("Your latest profile change request is still pending, so it has not been approved.");
        assertThat(ask(clients.service, EMPLOYEE, "When did my profile change request get approved?"))
                .isEqualTo("Your latest profile change request was rejected on 2026-10-21, so it was not approved.");
        assertThat(ask(clients.service, EMPLOYEE, "When was my profile change request approved?"))
                .isEqualTo("Your latest profile change request is marked approved, but no approval date was recorded.");
        assertThat(ask(clients.service, EMPLOYEE, "When did I submit my profile change request?"))
                .isEqualTo("You submitted your latest profile change request on 2026-10-20.");
        verifyNoInteractions(clients.claude, clients.ollama);
    }

    @Test
    void lookupsUseOnlyTheVerifiedActorsOwnId() {
        Clients clients = new Clients();
        VerifiedMyHrActor otherEmployee = TestActors.actor("employee-5", "org-2", UserRole.EMPLOYEE);
        when(clients.profileChanges.mostRecentOwnStatus("employee-5")).thenReturn(Optional.empty());
        when(clients.profileChanges.mostRecentOwnHistory("employee-5")).thenReturn(Optional.empty());

        assertThat(ask(clients.service, otherEmployee, "What is the status of my latest profile change request?"))
                .isEqualTo("No profile change request found.");
        assertThat(ask(clients.service, otherEmployee, "When was my profile change request approved?"))
                .isEqualTo("I couldn't find a profile change request of yours in Waypoint.");
        verify(clients.profileChanges, never()).mostRecentOwnStatus("employee-4");
        verify(clients.profileChanges, never()).mostRecentOwnHistory("employee-4");
        verifyNoInteractions(clients.claude, clients.ollama);
    }

    @Test
    void anotherEmployeesRecordIsNeverLookedUp() {
        Clients clients = new Clients();
        when(clients.claude.chat(anyString(), anyList())).thenReturn("I can only help with your own records.");

        ask(clients.service, EMPLOYEE, "What is the status of TARDU005's latest profile change request?");
        ask(clients.service, EMPLOYEE, "When was Sarah's profile change request approved?");
        // Unmatched, so no record lookup runs: the provider receives only the question, never another person's data.
        verifyNoInteractions(clients.profileChanges, clients.timesheets, clients.documents, clients.employees);
    }

    @Test
    void anUnauthenticatedChatIsRejectedBeforeAnyLookup() {
        Clients clients = new Clients();

        assertThatThrownBy(() -> clients.service.chat((VerifiedMyHrActor) null,
                "What is the status of my latest profile change request?", List.of()))
                .isInstanceOf(UnauthorizedException.class);
        verifyNoInteractions(clients.profileChanges, clients.claude, clients.ollama);
    }

    @Test
    void hrAndCompanyAdminKeepTheirOwnStatusAndQueueAnswers() {
        Clients clients = new Clients();
        when(clients.profileChanges.mostRecentOwnStatus("hr-1")).thenReturn(Optional.of(ProfileChangeStatus.REJECTED));
        when(clients.profileChanges.mostRecentOwnStatus("admin-1")).thenReturn(Optional.empty());
        when(clients.profileChanges.queueSummary("hr-1")).thenReturn(new MyHrProfileChangeQueueSummary(3, 2, 1, 0));
        when(clients.profileChanges.queueSummary("admin-1")).thenReturn(new MyHrProfileChangeQueueSummary(9, 3, 4, 2));

        assertThat(ask(clients.service, HR, "What is the status of my latest profile change request?"))
                .isEqualTo("Your profile change request status is REJECTED.");
        assertThat(ask(clients.service, ADMIN, "What is the status of my latest profile change request?"))
                .isEqualTo("No profile change request found.");
        assertThat(ask(clients.service, HR, "How many profile change requests are pending my review?"))
                .isEqualTo("You have 2 profile change requests awaiting your review.");
        assertThat(ask(clients.service, ADMIN, "How many profile change requests are pending in my organization?"))
                .isEqualTo("There are 3 pending profile change requests in your organization.");
        assertThat(ask(clients.service, EMPLOYEE, "How many profile change requests are pending?"))
                .isEqualTo("Profile-change approval queue counts are available only to HR and Company Admin accounts.");
        verifyNoInteractions(clients.claude, clients.ollama);
    }

    @Test
    void howToProfileQuestionsKeepTheirExistingRouting() {
        Clients clients = new Clients();
        when(clients.claude.chat(eq("Can you help me plan my week?"), anyList())).thenReturn("General guidance.");

        assertThat(ask(clients.service, EMPLOYEE, "How do I update my phone number?")).contains("Profile Change Requests");
        assertThat(ask(clients.service, EMPLOYEE, "Can you help me plan my week?")).isEqualTo("General guidance.");
        verifyNoInteractions(clients.profileChanges);
    }

    private String ask(MyHrChatService service, VerifiedMyHrActor actor, String question) {
        return service.chat(actor, question, List.of()).message();
    }

    private static MyHrOwnProfileResponse ownProfile(String userId, String employeeId) {
        return new MyHrOwnProfileResponse(
                userId, employeeId, employeeId, UserRole.EMPLOYEE, AccountStatus.ACTIVE,
                "John", "Doe", "John Doe", "john@account.test", "john@home.test", "555-0100",
                "Software Engineer", EmploymentType.FULL_TIME, null, "1 Main St", "Austin", "Texas", "78701",
                "org-1", "Tardus", "TD", "hr@tardus.example.com", "200 Lake Street, Austin, TX, 78701", "+15551234567",
                "https://tardus.example.com", "84-3596475", "1533740", "Jane Admin", "jane.admin@tardus.example.com",
                "+19726936993", null, "/uploads/company-logos/org-1/logo.png",
                "/uploads/company-signatures/org-1/signature.png", null, null);
    }

    private static final class Clients {
        private final ClaudeChatClient claude = mock(ClaudeChatClient.class);
        private final OllamaClient ollama = mock(OllamaClient.class);
        private final TimesheetService timesheets = mock(TimesheetService.class);
        private final ProfileChangeRequestService profileChanges = mock(ProfileChangeRequestService.class);
        private final EmployeeManagementService employees = mock(EmployeeManagementService.class);
        private final DocumentRequestService documents = mock(DocumentRequestService.class);
        private final MyHrChatService service = new MyHrChatService(claude, ollama, timesheets, profileChanges,
                employees, documents, "claude", null, false, false);
    }
}
