package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrLatestTimesheetSubmission;
import com.waypoint.dto.myhr.MyHrRecentTimesheetStatus;
import com.waypoint.enums.TimesheetStatus;
import com.waypoint.enums.UserRole;
import com.waypoint.security.TestActors;
import com.waypoint.security.VerifiedMyHrActor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 2 timesheet regressions: "My timesheet was rejected. What should I do?" reached Claude, and
 * "When did I last submit my timesheet?" was not answered. Both are now deterministic, read only the
 * signed-in user's own records and never call either provider. Configured as shared-readonly runs.
 */
class MyHrChatServiceTimesheetPhase2Test {

    private static final LocalDate START = LocalDate.of(2026, 10, 13);
    private static final LocalDate END = LocalDate.of(2026, 10, 19);

    private final ClaudeChatClient claude = mock(ClaudeChatClient.class);
    private final OllamaClient ollama = mock(OllamaClient.class);
    private final TimesheetService timesheets = mock(TimesheetService.class);
    private final KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
    private final VerifiedMyHrActor employee = TestActors.actor("employee-9", "org-9", UserRole.EMPLOYEE);
    private final VerifiedMyHrActor hr = TestActors.actor("hr-9", "org-9", UserRole.HR);
    private final VerifiedMyHrActor admin = TestActors.actor("admin-9", "org-9", UserRole.COMPANY_ADMIN);
    private MyHrChatService service;

    @BeforeEach
    void setUp() {
        service = new MyHrChatService(claude, ollama, timesheets, mock(ProfileChangeRequestService.class),
                mock(EmployeeManagementService.class), "claude", retrieval, false, false);
    }

    private String ask(VerifiedMyHrActor actor, String question) {
        return service.chat(actor, question, List.of()).message();
    }

    private void latest(VerifiedMyHrActor actor, TimesheetStatus status) {
        when(timesheets.getMostRecentStatusForUser(actor.userId()))
                .thenReturn(Optional.of(new MyHrRecentTimesheetStatus(START, END, status)));
    }

    // --- Issue 1: rejection guidance ------------------------------------------------------------

    @Test
    void rejectedTimesheetGetsTheLookedUpStatusThenTheVerifiedResubmitWorkflow() {
        latest(employee, TimesheetStatus.REJECTED);

        for (String question : List.of("My timesheet was rejected. What should I do?", "My manager rejected my timesheet",
                "my timesheet got rejected", "How do I fix my rejected timesheet?")) {
            assertThat(ask(employee, question)).as(question)
                    .startsWith("I checked: your most recent timesheet, for 2026-10-13 to 2026-10-19, was rejected.")
                    // what needs the timesheet opened, not retrieved
                    .contains("I can't see the reviewer's reason or your hours", "choose Submitted Timesheets",
                            "Revise & Resubmit", "reason for change", "approved changed timesheet",
                            "submit it again to your reporting manager")
                    .doesNotContain("My Timesheets tab", "Workday", "ADP");
        }
        verify(timesheets, org.mockito.Mockito.times(4)).getMostRecentStatusForUser("employee-9");
        verifyNoInteractions(claude, ollama, retrieval);
    }

    @Test
    void rejectionGuidanceSaysWhatItFoundWhenTheLatestTimesheetIsNotRejected() {
        latest(employee, TimesheetStatus.APPROVED);
        assertThat(ask(employee, "My timesheet was rejected. What should I do?"))
                .startsWith("I checked: your most recent timesheet, for 2026-10-13 to 2026-10-19, was approved. "
                        + "I can only check your most recent timesheet")
                .contains("Revise & Resubmit");

        latest(employee, TimesheetStatus.DRAFT);
        assertThat(ask(employee, "My timesheet was rejected. What should I do?"))
                .startsWith("I checked: your most recent timesheet, for 2026-10-13 to 2026-10-19, is a draft and "
                        + "hasn't been submitted.");

        when(timesheets.getMostRecentStatusForUser("employee-9")).thenReturn(Optional.empty());
        assertThat(ask(employee, "My timesheet was rejected. What should I do?"))
                .startsWith("I couldn't find a timesheet of yours in Waypoint.");
        verifyNoInteractions(claude, ollama, retrieval);
    }

    @Test
    void rejectionGuidanceIsRoleSpecificAndLooksUpOnlyTheSignedInUser() {
        latest(hr, TimesheetStatus.REJECTED);
        assertThat(ask(hr, "My timesheet was rejected. What should I do?"))
                .startsWith("I checked: your most recent timesheet").contains("stay on the My Timesheets tab");
        verify(timesheets).getMostRecentStatusForUser("hr-9");

        // Company Admins don't submit timesheets: no lookup, no resubmit steps.
        assertThat(ask(admin, "My timesheet was rejected. What should I do?"))
                .startsWith("Company Admin accounts don't submit timesheets").doesNotContain("Revise & Resubmit");
        verify(timesheets, never()).getMostRecentStatusForUser("admin-9");
        verify(timesheets, never()).getMostRecentStatusForUser("employee-9");
        verifyNoInteractions(claude, ollama, retrieval);
    }

    @Test
    void existingTimesheetAnswersAreUnchanged() {
        when(claude.chat(anyString(), anyList())).thenReturn("model answer");
        latest(employee, TimesheetStatus.REJECTED);

        // The general correction workflow and the status lookup keep their answers.
        assertThat(ask(employee, "How do I fix a rejected timesheet?"))
                .startsWith("On Timesheets: a draft can be changed").doesNotContain("I checked");
        assertThat(ask(employee, "What is my timesheet status?")).isEqualTo(
                "Your most recent timesheet for 2026-10-13 to 2026-10-19 was rejected. Please review it in Waypoint for next steps.");
        // "Why was my timesheet rejected?" is about the user's own record, so it gets the same looked-up answer as
        // "My timesheet was rejected" instead of reaching the model; a timing-policy question still does.
        assertThat(ask(employee, "Why was my timesheet rejected?"))
                .startsWith("I checked: your most recent timesheet, for 2026-10-13 to 2026-10-19, was rejected.")
                .contains("a rejected period shows the reviewer's reason");
        assertThat(ask(employee, "When should I submit my timesheet?")).isEqualTo("model answer");
        verify(timesheets, never()).getLatestSubmissionForUser(anyString());
    }

    // --- Issue 2: last submission date ------------------------------------------------------------

    @Test
    void lastSubmissionDateComesFromTheRecordedSubmissionTimeWithoutAnyProvider() {
        when(timesheets.getLatestSubmissionForUser("employee-9")).thenReturn(Optional.of(new MyHrLatestTimesheetSubmission(
                LocalDateTime.of(2026, 10, 20, 14, 5), START, END, TimesheetStatus.APPROVED)));

        for (String question : List.of("When did I last submit my timesheet?", "when did i submit my timesheet",
                "When was my last timesheet submitted?", "Can you tell me when I last submitted my timesheet?",
                "What date did I submit my latest timesheet?")) {
            assertThat(ask(employee, question)).as(question).isEqualTo("You last submitted a timesheet on 2026-10-20, "
                    + "for 2026-10-13 to 2026-10-19. It has since been approved. If you resubmitted that timesheet, "
                    + "this is the date of the latest resubmission.");
        }
        verifyNoInteractions(claude, ollama, retrieval);
    }

    @Test
    void lastSubmissionReportsMissingRecordsDraftsAndMissingTimestampsWithoutInventingADate() {
        when(timesheets.getLatestSubmissionForUser("employee-9")).thenReturn(Optional.empty());
        assertThat(ask(employee, "When did I last submit my timesheet?"))
                .isEqualTo("I couldn't find a timesheet you've submitted in Waypoint. Drafts don't count until they're submitted.");

        when(timesheets.getLatestSubmissionForUser("employee-9"))
                .thenReturn(Optional.of(new MyHrLatestTimesheetSubmission(null, null, null, null)));
        assertThat(ask(employee, "When did I last submit my timesheet?"))
                .startsWith("I found a submitted timesheet, but Waypoint has no submission date recorded for it")
                .doesNotContainPattern("\\d{4}-\\d{2}-\\d{2}");

        when(timesheets.getLatestSubmissionForUser("employee-9")).thenReturn(Optional.of(new MyHrLatestTimesheetSubmission(
                LocalDateTime.of(2026, 10, 21, 9, 30), START, END, TimesheetStatus.SUBMITTED)));
        assertThat(ask(employee, "When did I last submit my timesheet?"))
                .startsWith("You last submitted a timesheet on 2026-10-21").contains("It's awaiting review.");
        verifyNoInteractions(claude, ollama, retrieval);
    }

    @Test
    void lastSubmissionIsLookedUpOnlyForTheSignedInUserAndNotForCompanyAdmins() {
        when(timesheets.getLatestSubmissionForUser("hr-9")).thenReturn(Optional.of(new MyHrLatestTimesheetSubmission(
                LocalDateTime.of(2026, 10, 22, 8, 0), START, END, TimesheetStatus.REJECTED)));
        assertThat(ask(hr, "When did I last submit my timesheet?"))
                .startsWith("You last submitted a timesheet on 2026-10-22").contains("It has since been rejected.");
        verify(timesheets).getLatestSubmissionForUser("hr-9");

        assertThat(ask(admin, "When did I last submit my timesheet?"))
                .startsWith("Company Admin accounts don't submit timesheets");
        verify(timesheets, never()).getLatestSubmissionForUser("admin-9");
        verify(timesheets, never()).getLatestSubmissionForUser("employee-9");
        verifyNoInteractions(claude, ollama, retrieval);
    }
}
