package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrTimesheetQueueSummary;
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

class MyHrChatServiceTimesheetQueueTest {
    private static final VerifiedMyHrActor HR = TestActors.hr("hr-1", "org-1");
    private static final VerifiedMyHrActor ADMIN = TestActors.companyAdmin("admin-1", "org-1");
    private static final VerifiedMyHrActor EMPLOYEE = TestActors.actor("employee-1", "org-1", UserRole.EMPLOYEE);

    @Test
    void hrGetsItsActiveDirectReportQueueWithoutCallingAi() {
        Clients clients = new Clients();
        when(clients.timesheets.queueSummary("hr-1"))
                .thenReturn(new MyHrTimesheetQueueSummary(5, 2, 2, 1));

        assertThat(ask(clients.service, HR, "How many timesheets are waiting for my review?"))
                .isEqualTo("You have 2 submitted timesheets awaiting your review.");
        assertThat(ask(clients.service, HR, "Do I have any employee timesheets awaiting approval?"))
                .isEqualTo("You have 2 employee timesheets awaiting your approval.");
        assertThat(ask(clients.service, HR, "How many assigned timesheets are pending?"))
                .isEqualTo("You have 2 assigned timesheets pending review.");
        assertThat(ask(clients.service, HR, "Where can I review submitted timesheets?"))
                .isEqualTo("Open Timesheets and select Team Timesheets to review submitted timesheets for your active direct reports.");
        verify(clients.timesheets, org.mockito.Mockito.times(3)).queueSummary("hr-1");
        verifyNoInteractions(clients.claude, clients.ollama);
    }

    @Test
    void hrReportsAnEmptyAssignedTimesheetQueueAccurately() {
        Clients clients = new Clients();
        when(clients.timesheets.queueSummary("hr-1"))
                .thenReturn(new MyHrTimesheetQueueSummary(0, 0, 0, 0));

        assertThat(ask(clients.service, HR, "Can you please tell me how many timesheets are waiting for my review?"))
                .isEqualTo("You have 0 submitted timesheets awaiting your review.");
        verify(clients.timesheets).queueSummary("hr-1");
        verifyNoInteractions(clients.claude, clients.ollama);
    }

    @Test
    void companyAdminGetsOrganizationCountsWithoutCallingAi() {
        Clients clients = new Clients();
        when(clients.timesheets.queueSummary("admin-1"))
                .thenReturn(new MyHrTimesheetQueueSummary(10, 3, 5, 2));

        assertThat(ask(clients.service, ADMIN, "How many timesheets are submitted in my organization?"))
                .isEqualTo("There are 3 submitted timesheets in your organization.");
        assertThat(ask(clients.service, ADMIN, "How many timesheets are awaiting review?"))
                .isEqualTo("There are 3 timesheets awaiting review in your organization.");
        assertThat(ask(clients.service, ADMIN, "How many timesheets have been approved?"))
                .isEqualTo("There are 5 approved timesheets in your organization.");
        assertThat(ask(clients.service, ADMIN, "How many timesheets were rejected?"))
                .isEqualTo("There are 2 rejected timesheets in your organization.");
        assertThat(ask(clients.service, ADMIN, "Where can I review employee timesheets?"))
                .isEqualTo("Open Timesheets to review submitted timesheets in your organization.");
        verify(clients.timesheets, org.mockito.Mockito.times(4)).queueSummary("admin-1");
        verifyNoInteractions(clients.claude, clients.ollama);
    }

    @Test
    void companyAdminGetsPendingApprovalAndApprovedOrganizationCountsWithoutCallingAi() {
        Clients clients = new Clients();
        when(clients.timesheets.queueSummary("admin-1"))
                .thenReturn(new MyHrTimesheetQueueSummary(3, 1, 1, 1));

        assertThat(ask(clients.service, ADMIN, "How many timesheets are pending approval in my organization?"))
                .isEqualTo("There is 1 submitted timesheet awaiting approval in your organization.");
        assertThat(ask(clients.service, ADMIN, "How many timesheets are awaiting approval in my organization?"))
                .isEqualTo("There is 1 submitted timesheet awaiting approval in your organization.");
        assertThat(ask(clients.service, ADMIN, "How many timesheets have been approved in my organization?"))
                .isEqualTo("There is 1 approved timesheet in your organization.");
        assertThat(ask(clients.service, ADMIN, "How many approved timesheets are there in the organization?"))
                .isEqualTo("There is 1 approved timesheet in your organization.");
        verify(clients.timesheets, org.mockito.Mockito.times(4)).queueSummary("admin-1");
        verifyNoInteractions(clients.claude, clients.ollama);
    }

    @Test
    void companyAdminReportsZeroOrganizationCountsWithoutCallingAi() {
        Clients clients = new Clients();
        when(clients.timesheets.queueSummary("admin-1"))
                .thenReturn(new MyHrTimesheetQueueSummary(0, 0, 0, 0));

        assertThat(ask(clients.service, ADMIN, "How many timesheets are pending approval in my organization?"))
                .isEqualTo("There are 0 submitted timesheets awaiting approval in your organization.");
        assertThat(ask(clients.service, ADMIN, "How many timesheets have been approved in my organization?"))
                .isEqualTo("There are 0 approved timesheets in your organization.");
        verify(clients.timesheets, org.mockito.Mockito.times(2)).queueSummary("admin-1");
        verifyNoInteractions(clients.claude, clients.ollama);
    }

    @Test
    void queueQuestionsCannotCrossRoleBoundaries() {
        Clients clients = new Clients();

        assertThat(ask(clients.service, EMPLOYEE, "How many timesheets are waiting for my review?"))
                .isEqualTo("Timesheet review queue counts are available only to HR and Company Admin accounts.");
        assertThat(ask(clients.service, EMPLOYEE, "How many timesheets are pending approval in my organization?"))
                .isEqualTo("Timesheet review queue counts are available only to HR and Company Admin accounts.");
        assertThat(ask(clients.service, HR, "How many timesheets are awaiting review?"))
                .isEqualTo("Organization-wide timesheet summaries are available only to Company Admin accounts.");
        assertThat(ask(clients.service, HR, "How many timesheets have been approved in my organization?"))
                .isEqualTo("Organization-wide timesheet summaries are available only to Company Admin accounts.");
        assertThat(ask(clients.service, ADMIN, "How many assigned timesheets are pending?"))
                .isEqualTo("HR assigned-review summaries are available only to HR users reviewing their active direct reports.");
        verifyNoInteractions(clients.timesheets, clients.claude, clients.ollama);
    }

    @Test
    void existingWorkflowQuestionKeepsItsProviderRouting() {
        Clients clients = new Clients();
        when(clients.claude.chat(eq("Can you help me plan my week?"), anyList())).thenReturn("General guidance.");

        assertThat(ask(clients.service, HR, "Can you help me plan my week?")).isEqualTo("General guidance.");
        verifyNoInteractions(clients.timesheets);
    }

    private String ask(MyHrChatService service, VerifiedMyHrActor actor, String question) {
        return service.chat(actor, question, List.of()).message();
    }

    private static final class Clients {
        private final ClaudeChatClient claude = mock(ClaudeChatClient.class);
        private final OllamaClient ollama = mock(OllamaClient.class);
        private final TimesheetService timesheets = mock(TimesheetService.class);
        private final MyHrChatService service = new MyHrChatService(claude, ollama, timesheets,
                mock(ProfileChangeRequestService.class), mock(EmployeeManagementService.class),
                mock(DocumentRequestService.class), "claude", null, false, false);
    }
}
