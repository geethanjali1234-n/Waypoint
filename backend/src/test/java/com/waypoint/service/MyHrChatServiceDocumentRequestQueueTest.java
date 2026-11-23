package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrDocumentRequestQueueSummary;
import com.waypoint.enums.UserRole;
import com.waypoint.security.TestActors;
import com.waypoint.security.VerifiedMyHrActor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MyHrChatServiceDocumentRequestQueueTest {
    private static final VerifiedMyHrActor HR = TestActors.hr("hr-1", "org-1");
    private static final VerifiedMyHrActor ADMIN = TestActors.companyAdmin("admin-1", "org-1");
    private static final VerifiedMyHrActor EMPLOYEE = TestActors.actor("employee-1", "org-1", UserRole.EMPLOYEE);

    @Test
    void hrGetsOnlyAssignedQueueCountsWithoutCallingAi() {
        Clients clients = new Clients();
        when(clients.documents.queueSummary("hr-1"))
                .thenReturn(new MyHrDocumentRequestQueueSummary(4, 1, 2, 1, 0, 0));

        assertThat(ask(clients.service, HR, "How many document requests need my review?"))
                .isEqualTo("You have 2 submitted I-983 requests awaiting your review.");
        assertThat(ask(clients.service, HR, "Can you please tell me how many document requests need my review?"))
                .isEqualTo("You have 2 submitted I-983 requests awaiting your review.");
        assertThat(ask(clients.service, HR, "Do I have any pending document requests?"))
                .isEqualTo("You have 1 pending request assigned for fulfillment and 2 submitted I-983 requests awaiting review.");
        assertThat(ask(clients.service, HR, "How many requests were returned for correction?"))
                .isEqualTo("You have 1 assigned request returned for correction.");
        assertThat(ask(clients.service, HR, "What document requests are assigned to me?"))
                .isEqualTo("You have 4 document requests assigned to you: 1 pending for fulfillment, 2 submitted I-983 awaiting review, and 1 returned for correction.");
        verify(clients.documents, org.mockito.Mockito.times(5)).queueSummary("hr-1");
        verifyNoInteractions(clients.claude, clients.ollama);
    }

    @Test
    void hrReportsZerosAndTheExistingReceivedRequestsNavigation() {
        Clients clients = new Clients();
        when(clients.documents.queueSummary("hr-1"))
                .thenReturn(new MyHrDocumentRequestQueueSummary(0, 0, 0, 0, 0, 0));

        assertThat(ask(clients.service, HR, "How many document requests need my review?"))
                .isEqualTo("You have 0 submitted I-983 requests awaiting your review.");
        assertThat(ask(clients.service, HR, "Where can I review employee document requests?"))
                .isEqualTo("Open Request Documents and choose Received requests to review documents assigned to you.");
        verify(clients.documents).queueSummary("hr-1");
        verifyNoInteractions(clients.claude, clients.ollama);
    }

    @Test
    void companyAdminGetsOrganizationCountsAndApprovalNavigationWithoutCallingAi() {
        Clients clients = new Clients();
        when(clients.documents.queueSummary("admin-1"))
                .thenReturn(new MyHrDocumentRequestQueueSummary(11, 3, 2, 1, 2, 4));

        assertThat(ask(clients.service, ADMIN, "How many document requests are pending in my organization?"))
                .isEqualTo("There are 3 pending document requests in your organization.");
        assertThat(ask(clients.service, ADMIN, "How many pending document requests are there in my organization?"))
                .isEqualTo("There are 3 pending document requests in your organization.");
        assertThat(ask(clients.service, ADMIN, "How many requests need administrator approval?"))
                .isEqualTo("There are 2 submitted document requests awaiting Company Admin approval.");
        assertThat(ask(clients.service, ADMIN, "How many documents are waiting for my approval?"))
                .isEqualTo("There are 2 submitted document requests awaiting Company Admin approval.");
        assertThat(ask(clients.service, ADMIN, "How many document requests were completed?"))
                .isEqualTo("There are 4 completed document requests in your organization.");
        assertThat(ask(clients.service, ADMIN, "How many document requests have been completed in my organization?"))
                .isEqualTo("There are 4 completed document requests in your organization.");
        assertThat(ask(clients.service, ADMIN, "How many fulfilled document requests are there in my organization?"))
                .isEqualTo("There are 4 completed document requests in your organization.");
        assertThat(ask(clients.service, ADMIN, "Where can I review document approvals?"))
                .isEqualTo("Open Document Approvals to review documents awaiting Company Admin approval.");
        verify(clients.documents, org.mockito.Mockito.times(7)).queueSummary("admin-1");
        verifyNoInteractions(clients.claude, clients.ollama);
    }

    @Test
    void companyAdminReportsZeroCountsWithoutCallingAi() {
        Clients clients = new Clients();
        when(clients.documents.queueSummary("admin-1"))
                .thenReturn(new MyHrDocumentRequestQueueSummary(0, 0, 0, 0, 0, 0));

        assertThat(ask(clients.service, ADMIN, "How many documents are waiting for my approval?"))
                .isEqualTo("There are 0 submitted document requests awaiting Company Admin approval.");
        assertThat(ask(clients.service, ADMIN, "How many document requests have been completed in my organization?"))
                .isEqualTo("There are 0 completed document requests in your organization.");
        verify(clients.documents, org.mockito.Mockito.times(2)).queueSummary("admin-1");
        verifyNoInteractions(clients.claude, clients.ollama);
    }

    @Test
    void employeeAndHrCannotObtainQueueSummariesOutsideTheirRoles() {
        Clients clients = new Clients();

        assertThat(ask(clients.service, EMPLOYEE, "How many document requests need my review?"))
                .isEqualTo("Document-request queue counts are available only to HR and Company Admin accounts.");
        assertThat(ask(clients.service, EMPLOYEE, "How many documents are waiting for my approval?"))
                .isEqualTo("Document-request queue counts are available only to HR and Company Admin accounts.");
        assertThat(ask(clients.service, HR, "How many requests need administrator approval?"))
                .isEqualTo("Organization-wide document approval summaries are available only to Company Admin accounts.");
        // HR asking about its own approvals is answered from its assigned queue (MyHrChatServiceHrApprovalQueueTest).
        assertThat(ask(clients.service, ADMIN, "How many document requests need my review?"))
                .isEqualTo("HR assigned-request summaries are available only to the HR user each request is assigned to.");
        verifyNoInteractions(clients.documents, clients.claude, clients.ollama);
    }

    @Test
    void unrelatedWorkflowQuestionKeepsExistingProviderRouting() {
        Clients clients = new Clients();
        when(clients.claude.chat(eq("Can you help me plan my week?"), anyList())).thenReturn("General guidance.");

        assertThat(ask(clients.service, HR, "Can you help me plan my week?")).isEqualTo("General guidance.");
        verifyNoInteractions(clients.documents);
    }

    private String ask(MyHrChatService service, VerifiedMyHrActor actor, String question) {
        return service.chat(actor, question, List.of()).message();
    }

    private static final class Clients {
        private final ClaudeChatClient claude = mock(ClaudeChatClient.class);
        private final OllamaClient ollama = mock(OllamaClient.class);
        private final DocumentRequestService documents = mock(DocumentRequestService.class);
        private final MyHrChatService service = new MyHrChatService(claude, ollama, mock(TimesheetService.class),
                mock(ProfileChangeRequestService.class), mock(EmployeeManagementService.class), documents,
                "claude", null, false, false);
    }
}
