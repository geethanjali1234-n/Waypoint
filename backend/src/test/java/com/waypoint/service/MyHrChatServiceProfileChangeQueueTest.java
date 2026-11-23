package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrProfileChangeQueueSummary;
import com.waypoint.enums.UserRole;
import com.waypoint.security.TestActors;
import com.waypoint.security.VerifiedMyHrActor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MyHrChatServiceProfileChangeQueueTest {
    private static final VerifiedMyHrActor HR = TestActors.hr("hr-1", "org-1");
    private static final VerifiedMyHrActor ADMIN = TestActors.companyAdmin("admin-1", "org-1");
    private static final VerifiedMyHrActor EMPLOYEE = TestActors.actor("employee-1", "org-1", UserRole.EMPLOYEE);

    @Test
    void hrGetsOnlyItsAssignedPendingCountWithoutCallingAi() {
        Clients clients = new Clients();
        when(clients.profileChanges.queueSummary("hr-1"))
                .thenReturn(new MyHrProfileChangeQueueSummary(4, 2, 1, 1));

        assertThat(ask(clients.service, HR, "How many profile change requests are waiting for my review?"))
                .isEqualTo("You have 2 profile change requests awaiting your review.");
        assertThat(ask(clients.service, HR, "Can you please tell me how many profile change requests are waiting for my review?"))
                .isEqualTo("You have 2 profile change requests awaiting your review.");
        assertThat(ask(clients.service, HR, "Do I have any pending employee profile updates?"))
                .isEqualTo("You have 2 pending employee profile updates assigned to you.");
        assertThat(ask(clients.service, HR, "How many profile change requests are pending?"))
                .isEqualTo("You have 2 pending employee profile updates assigned to you.");
        assertThat(ask(clients.service, HR, "Where can I review employee profile changes?"))
                .isEqualTo("Open Profile Change Requests to review profile changes assigned to you.");
        verify(clients.profileChanges, org.mockito.Mockito.times(4)).queueSummary("hr-1");
        verifyNoInteractions(clients.claude, clients.ollama);
    }

    @Test
    void hrReportsAnEmptyAssignedQueueAccurately() {
        Clients clients = new Clients();
        when(clients.profileChanges.queueSummary("hr-1"))
                .thenReturn(new MyHrProfileChangeQueueSummary(0, 0, 0, 0));

        assertThat(ask(clients.service, HR, "How many profile change requests are waiting for my review?"))
                .isEqualTo("You have 0 profile change requests awaiting your review.");
        verify(clients.profileChanges).queueSummary("hr-1");
        verifyNoInteractions(clients.claude, clients.ollama);
    }

    @Test
    void companyAdminGetsOrganizationCountsAndNavigationWithoutCallingAi() {
        Clients clients = new Clients();
        when(clients.profileChanges.queueSummary("admin-1"))
                .thenReturn(new MyHrProfileChangeQueueSummary(9, 3, 4, 2));

        assertThat(ask(clients.service, ADMIN, "How many profile change requests are pending in my organization?"))
                .isEqualTo("There are 3 pending profile change requests in your organization.");
        assertThat(ask(clients.service, ADMIN, "How many pending profile change requests are there in my organization?"))
                .isEqualTo("There are 3 pending profile change requests in your organization.");
        assertThat(ask(clients.service, ADMIN, "How many employee profile updates need approval?"))
                .isEqualTo("There are 3 employee profile updates awaiting approval in your organization.");
        assertThat(ask(clients.service, ADMIN, "How many profile change requests were approved?"))
                .isEqualTo("There are 4 approved profile change requests in your organization.");
        assertThat(ask(clients.service, ADMIN, "How many profile change requests have been approved in my organization?"))
                .isEqualTo("There are 4 approved profile change requests in your organization.");
        assertThat(ask(clients.service, ADMIN, "How many requests were rejected?"))
                .isEqualTo("There are 2 rejected profile change requests in your organization.");
        assertThat(ask(clients.service, ADMIN,
                "How many profile change requests were rejected in my organization?"))
                .isEqualTo("There are 2 rejected profile change requests in your organization.");
        assertThat(ask(clients.service, ADMIN, "How many rejected profile change requests are there in my organization?"))
                .isEqualTo("There are 2 rejected profile change requests in your organization.");
        assertThat(ask(clients.service, ADMIN, "Where can I review profile change requests?"))
                .isEqualTo("Open Profile Change Requests to review profile changes in your organization.");
        verify(clients.profileChanges, org.mockito.Mockito.times(8)).queueSummary("admin-1");
        verifyNoInteractions(clients.claude, clients.ollama);
    }

    @Test
    void employeeAndHrCannotObtainProfileQueuesOutsideTheirRoles() {
        Clients clients = new Clients();

        assertThat(ask(clients.service, EMPLOYEE, "How many profile change requests are pending?"))
                .isEqualTo("Profile-change approval queue counts are available only to HR and Company Admin accounts.");
        assertThat(ask(clients.service, HR, "How many employee profile updates need approval?"))
                .isEqualTo("Organization-wide profile-change summaries are available only to Company Admin accounts.");
        assertThat(ask(clients.service, ADMIN, "How many profile change requests are waiting for my review?"))
                .isEqualTo("HR assigned-review summaries are available only to the HR user each profile change is assigned to.");
        verifyNoInteractions(clients.profileChanges, clients.claude, clients.ollama);
    }

    @Test
    void existingWorkflowQuestionKeepsItsProviderRouting() {
        Clients clients = new Clients();
        when(clients.claude.chat(eq("Can you help me plan my week?"), anyList())).thenReturn("General guidance.");

        assertThat(ask(clients.service, HR, "Can you help me plan my week?")).isEqualTo("General guidance.");
        verifyNoInteractions(clients.profileChanges);
    }

    private String ask(MyHrChatService service, VerifiedMyHrActor actor, String question) {
        return service.chat(actor, question, List.of()).message();
    }

    private static final class Clients {
        private final ClaudeChatClient claude = mock(ClaudeChatClient.class);
        private final OllamaClient ollama = mock(OllamaClient.class);
        private final ProfileChangeRequestService profileChanges = mock(ProfileChangeRequestService.class);
        private final MyHrChatService service = new MyHrChatService(claude, ollama, mock(TimesheetService.class),
                profileChanges, mock(EmployeeManagementService.class), mock(DocumentRequestService.class),
                "claude", null, false, false);
    }
}
