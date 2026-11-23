package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrChatHistoryMessage;
import com.waypoint.dto.myhr.MyHrDocumentRequestQueueSummary;
import com.waypoint.dto.myhr.MyHrProfileChangeQueueSummary;
import com.waypoint.dto.myhr.MyHrTimesheetQueueSummary;
import com.waypoint.enums.UserRole;
import com.waypoint.security.TestActors;
import com.waypoint.security.VerifiedMyHrActor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * HR "my approval / my review / assigned to me" queue questions, as reported in HR workflow testing. Each is
 * answered from the existing assignment-scoped queue service for the verified actor, never by Claude or Ollama.
 */
class MyHrChatServiceHrApprovalQueueTest {
    private static final String TIMESHEET_QUESTION = "How many timesheets are waiting for my approval?";
    private static final String PROFILE_QUESTION = "How many profile change requests are pending my review?";
    private static final String DOCUMENT_QUESTION = "How many document requests are pending my approval?";

    private static final VerifiedMyHrActor HR = TestActors.hr("hr-1", "org-1");
    private static final VerifiedMyHrActor OTHER_ORG_HR = TestActors.hr("hr-2", "org-2");
    private static final VerifiedMyHrActor ADMIN = TestActors.companyAdmin("admin-1", "org-1");
    private static final VerifiedMyHrActor EMPLOYEE = TestActors.actor("employee-1", "org-1", UserRole.EMPLOYEE);

    @Test
    void theThreeReportedHrQuestionsReturnAssignedCountsWithoutCallingAi() {
        Clients clients = new Clients();
        when(clients.timesheets.queueSummary("hr-1")).thenReturn(new MyHrTimesheetQueueSummary(6, 3, 2, 1));
        when(clients.profileChanges.queueSummary("hr-1")).thenReturn(new MyHrProfileChangeQueueSummary(5, 2, 2, 1));
        when(clients.documents.queueSummary("hr-1")).thenReturn(new MyHrDocumentRequestQueueSummary(6, 4, 2, 0, 0, 0));

        assertThat(ask(clients.service, HR, TIMESHEET_QUESTION))
                .isEqualTo("You have 3 employee timesheets awaiting your approval.");
        assertThat(ask(clients.service, HR, PROFILE_QUESTION))
                .isEqualTo("You have 2 profile change requests awaiting your review.");
        assertThat(ask(clients.service, HR, DOCUMENT_QUESTION))
                .isEqualTo("You have 2 submitted I-983 requests awaiting your approval, and 4 pending requests "
                        + "assigned to you to fulfill. Offer letters and other documents you send are approved by a "
                        + "Company Admin, so they are not counted here.");
        verify(clients.timesheets).queueSummary("hr-1");
        verify(clients.profileChanges).queueSummary("hr-1");
        verify(clients.documents).queueSummary("hr-1");
        verifyNoInteractions(clients.claude, clients.ollama);
    }

    @Test
    void zeroAndSingularCountsAreWordedAccurately() {
        Clients clients = new Clients();
        when(clients.timesheets.queueSummary("hr-1"))
                .thenReturn(new MyHrTimesheetQueueSummary(0, 0, 0, 0), new MyHrTimesheetQueueSummary(1, 1, 0, 0));
        when(clients.profileChanges.queueSummary("hr-1"))
                .thenReturn(new MyHrProfileChangeQueueSummary(0, 0, 0, 0), new MyHrProfileChangeQueueSummary(1, 1, 0, 0));
        when(clients.documents.queueSummary("hr-1"))
                .thenReturn(new MyHrDocumentRequestQueueSummary(0, 0, 0, 0, 0, 0),
                        new MyHrDocumentRequestQueueSummary(2, 1, 1, 0, 0, 0));

        assertThat(ask(clients.service, HR, TIMESHEET_QUESTION))
                .isEqualTo("You have 0 employee timesheets awaiting your approval.");
        assertThat(ask(clients.service, HR, TIMESHEET_QUESTION))
                .isEqualTo("You have 1 employee timesheet awaiting your approval.");
        assertThat(ask(clients.service, HR, PROFILE_QUESTION))
                .isEqualTo("You have 0 profile change requests awaiting your review.");
        assertThat(ask(clients.service, HR, PROFILE_QUESTION))
                .isEqualTo("You have 1 profile change request awaiting your review.");
        assertThat(ask(clients.service, HR, DOCUMENT_QUESTION)).startsWith(
                "You have 0 submitted I-983 requests awaiting your approval, and 0 pending requests assigned to you");
        assertThat(ask(clients.service, HR, DOCUMENT_QUESTION)).startsWith(
                "You have 1 submitted I-983 request awaiting your approval, and 1 pending request assigned to you");
        verifyNoInteractions(clients.claude, clients.ollama);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "How many timesheets are pending my approval?",
            "How many timesheets are awaiting my approval",
            "Can you please tell me how many timesheets are waiting for my approval?",
            "Do I have any employee timesheets waiting for my approval?",
            "How many timesheets do I need to approve?",
    })
    void timesheetApprovalVariationsUseTheAssignedQueue(String question) {
        Clients clients = new Clients();
        when(clients.timesheets.queueSummary("hr-1")).thenReturn(new MyHrTimesheetQueueSummary(4, 2, 1, 1));

        assertThat(ask(clients.service, HR, question)).isEqualTo("You have 2 employee timesheets awaiting your approval.");
        verifyNoInteractions(clients.claude, clients.ollama);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "How many timesheets are pending my review?",
            "How many timesheets are assigned to me?",
            "How many timesheets are awaiting my action?",
            "Are there any timesheets in my queue?",
    })
    void timesheetReviewVariationsUseTheAssignedQueue(String question) {
        Clients clients = new Clients();
        when(clients.timesheets.queueSummary("hr-1")).thenReturn(new MyHrTimesheetQueueSummary(4, 2, 1, 1));

        assertThat(ask(clients.service, HR, question)).isEqualTo("You have 2 submitted timesheets awaiting your review.");
        verifyNoInteractions(clients.claude, clients.ollama);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "How many profile change requests are waiting for my approval?",
            "How many profile change requests are assigned to me?",
            "How many profile updates are awaiting my action?",
            "Do I have any profile change requests pending my review?",
            "Could you check how many employee profile changes need my approval?",
    })
    void profileChangeVariationsUseTheAssignedQueue(String question) {
        Clients clients = new Clients();
        when(clients.profileChanges.queueSummary("hr-1")).thenReturn(new MyHrProfileChangeQueueSummary(3, 2, 1, 0));

        assertThat(ask(clients.service, HR, question)).isEqualTo("You have 2 profile change requests awaiting your review.");
        verifyNoInteractions(clients.claude, clients.ollama);
    }

    @Test
    void documentVariationsUseTheAssignedQueue() {
        Clients clients = new Clients();
        when(clients.documents.queueSummary("hr-1")).thenReturn(new MyHrDocumentRequestQueueSummary(5, 3, 2, 0, 0, 0));

        assertThat(ask(clients.service, HR, "How many document requests are assigned to me?"))
                .isEqualTo("You have 3 pending requests assigned for fulfillment and 2 submitted I-983 requests awaiting review.");
        assertThat(ask(clients.service, HR, "How many document requests are awaiting my action?"))
                .isEqualTo("You have 3 pending requests assigned for fulfillment and 2 submitted I-983 requests awaiting review.");
        assertThat(ask(clients.service, HR, "How many documents are waiting for my approval?"))
                .startsWith("You have 2 submitted I-983 requests awaiting your approval, and 3 pending requests");
        assertThat(ask(clients.service, HR, "How many document requests are awaiting my approval?"))
                .startsWith("You have 2 submitted I-983 requests awaiting your approval, and 3 pending requests");
        verify(clients.documents, times(4)).queueSummary("hr-1");
        verifyNoInteractions(clients.claude, clients.ollama);
    }

    @Test
    void eachHrUserOnlyEverQueriesItsOwnQueue() {
        Clients clients = new Clients();
        when(clients.timesheets.queueSummary("hr-1")).thenReturn(new MyHrTimesheetQueueSummary(9, 9, 0, 0));
        when(clients.timesheets.queueSummary("hr-2")).thenReturn(new MyHrTimesheetQueueSummary(1, 1, 0, 0));
        when(clients.profileChanges.queueSummary("hr-2")).thenReturn(new MyHrProfileChangeQueueSummary(0, 0, 0, 0));
        when(clients.documents.queueSummary("hr-2")).thenReturn(new MyHrDocumentRequestQueueSummary(0, 0, 0, 0, 0, 0));

        assertThat(ask(clients.service, OTHER_ORG_HR, TIMESHEET_QUESTION))
                .isEqualTo("You have 1 employee timesheet awaiting your approval.");
        assertThat(ask(clients.service, OTHER_ORG_HR, PROFILE_QUESTION))
                .isEqualTo("You have 0 profile change requests awaiting your review.");
        assertThat(ask(clients.service, OTHER_ORG_HR, DOCUMENT_QUESTION)).startsWith("You have 0 submitted I-983");
        // The verified actor's own id is the only scope passed down; the service resolves its organization.
        verify(clients.timesheets, never()).queueSummary("hr-1");
        verify(clients.timesheets).queueSummary("hr-2");
        verify(clients.profileChanges).queueSummary("hr-2");
        verify(clients.documents).queueSummary("hr-2");
        verify(clients.profileChanges, never()).queueSummary("hr-1");
        verify(clients.documents, never()).queueSummary("hr-1");
        verifyNoInteractions(clients.claude, clients.ollama);
    }

    @Test
    void employeesAreRefusedWithoutAnyQueueLookupOrAi() {
        Clients clients = new Clients();

        assertThat(ask(clients.service, EMPLOYEE, TIMESHEET_QUESTION))
                .isEqualTo("Timesheet review queue counts are available only to HR and Company Admin accounts.");
        assertThat(ask(clients.service, EMPLOYEE, PROFILE_QUESTION))
                .isEqualTo("Profile-change approval queue counts are available only to HR and Company Admin accounts.");
        assertThat(ask(clients.service, EMPLOYEE, DOCUMENT_QUESTION))
                .isEqualTo("Document-request queue counts are available only to HR and Company Admin accounts.");
        assertThat(ask(clients.service, EMPLOYEE, "How many documents are waiting for my approval?"))
                .isEqualTo("Document-request queue counts are available only to HR and Company Admin accounts.");
        verifyNoInteractions(clients.timesheets, clients.profileChanges, clients.documents, clients.claude, clients.ollama);
    }

    @Test
    void companyAdminGetsOrganizationCountsForTheSameQuestions() {
        Clients clients = new Clients();
        when(clients.timesheets.queueSummary("admin-1")).thenReturn(new MyHrTimesheetQueueSummary(10, 4, 5, 1));
        when(clients.profileChanges.queueSummary("admin-1")).thenReturn(new MyHrProfileChangeQueueSummary(9, 3, 4, 2));
        when(clients.documents.queueSummary("admin-1")).thenReturn(new MyHrDocumentRequestQueueSummary(11, 3, 2, 1, 2, 4));

        assertThat(ask(clients.service, ADMIN, TIMESHEET_QUESTION))
                .isEqualTo("There are 4 submitted timesheets awaiting approval in your organization.");
        assertThat(ask(clients.service, ADMIN, PROFILE_QUESTION))
                .isEqualTo("There are 3 pending profile change requests in your organization.");
        assertThat(ask(clients.service, ADMIN, DOCUMENT_QUESTION))
                .isEqualTo("There are 2 submitted document requests awaiting Company Admin approval.");
        // Existing Company Admin phrasings are unchanged.
        assertThat(ask(clients.service, ADMIN, "How many documents are waiting for my approval?"))
                .isEqualTo("There are 2 submitted document requests awaiting Company Admin approval.");
        assertThat(ask(clients.service, ADMIN, "How many timesheets are pending approval in my organization?"))
                .isEqualTo("There are 4 submitted timesheets awaiting approval in your organization.");
        assertThat(ask(clients.service, ADMIN, "How many profile change requests are waiting for my review?"))
                .isEqualTo("HR assigned-review summaries are available only to the HR user each profile change is assigned to.");
        verifyNoInteractions(clients.claude, clients.ollama);
    }

    @Test
    void hrStillCannotObtainOrganizationWideSummaries() {
        Clients clients = new Clients();

        assertThat(ask(clients.service, HR, "How many requests need administrator approval?"))
                .isEqualTo("Organization-wide document approval summaries are available only to Company Admin accounts.");
        assertThat(ask(clients.service, HR, "How many timesheets are pending approval in my organization?"))
                .isEqualTo("Organization-wide timesheet summaries are available only to Company Admin accounts.");
        assertThat(ask(clients.service, HR, "How many employee profile updates need approval?"))
                .isEqualTo("Organization-wide profile-change summaries are available only to Company Admin accounts.");
        verifyNoInteractions(clients.timesheets, clients.profileChanges, clients.documents, clients.claude, clients.ollama);
    }

    @Test
    void greetingsAndGeneralQuestionsKeepTheirExistingRouting() {
        Clients clients = new Clients();
        when(clients.claude.chat(eq("Can you help me plan my week?"), anyList())).thenReturn("General guidance.");

        assertThat(ask(clients.service, HR, "hello")).isNotBlank();
        verifyNoInteractions(clients.claude);
        assertThat(clients.service.chat(HR, "Can you help me plan my week?",
                List.of(new MyHrChatHistoryMessage("user", TIMESHEET_QUESTION))).message())
                .isEqualTo("General guidance.");
        verify(clients.claude).chat(eq("Can you help me plan my week?"), anyList());
        verify(clients.ollama, never()).chat(anyString(), anyList());
        verifyNoInteractions(clients.timesheets, clients.profileChanges, clients.documents);
    }

    private String ask(MyHrChatService service, VerifiedMyHrActor actor, String question) {
        return service.chat(actor, question, List.of()).message();
    }

    private static final class Clients {
        private final ClaudeChatClient claude = mock(ClaudeChatClient.class);
        private final OllamaClient ollama = mock(OllamaClient.class);
        private final TimesheetService timesheets = mock(TimesheetService.class);
        private final ProfileChangeRequestService profileChanges = mock(ProfileChangeRequestService.class);
        private final DocumentRequestService documents = mock(DocumentRequestService.class);
        private final MyHrChatService service = new MyHrChatService(claude, ollama, timesheets, profileChanges,
                mock(EmployeeManagementService.class), documents, "claude", null, false, false);
    }
}
