package com.waypoint.service;

import com.waypoint.dto.myhr.EmployeeSummaryResponse;
import com.waypoint.dto.myhr.MyHrChatResponse;
import com.waypoint.dto.myhr.MyHrKnowledgeStatus;
import com.waypoint.dto.myhr.MyHrOwnProfileResponse;
import com.waypoint.dto.myhr.MyHrTimesheetPeriodStatus;
import com.waypoint.enums.ChunkAccessLevel;
import com.waypoint.enums.TimesheetStatus;
import com.waypoint.enums.UserRole;
import com.waypoint.security.TestActors;
import com.waypoint.security.VerifiedMyHrActor;
import com.waypoint.service.KnowledgeRetrievalService.RetrievalResult;
import com.waypoint.service.KnowledgeRetrievalService.RetrievalStatus;
import com.waypoint.service.KnowledgeRetrievalService.RetrievedChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The eight questions a teammate reported, exactly as typed (spelling included), for Employee, HR and Company Admin,
 * plus close spelling variations. Today is fixed at Thursday 5 November 2026. Providers, Voyage and MongoDB are
 * mocks; no network call is made.
 */
class MyHrChatServiceTeammateScenariosTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);
    private static final LocalDate SUNDAY = LocalDate.of(2026, 11, 8);

    private final ClaudeChatClient claude = mock(ClaudeChatClient.class);
    private final OllamaClient ollama = mock(OllamaClient.class);
    private final KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
    private final EmployeeManagementService employees = mock(EmployeeManagementService.class);
    private final TimesheetService timesheets = mock(TimesheetService.class);
    private final VerifiedMyHrActor employee = TestActors.actor("employee-11", "org-11", UserRole.EMPLOYEE);
    private final VerifiedMyHrActor hr = TestActors.hr("hr-11", "org-11");
    private final VerifiedMyHrActor admin = TestActors.companyAdmin("admin-11", "org-11");
    private MyHrChatService service;

    @BeforeEach
    void setUp() {
        when(ollama.isLocalEndpoint()).thenReturn(true);
        org.mockito.Mockito.lenient().when(ollama.verifiedLocalModel()).thenReturn(true);
        when(claude.chat(anyString(), anyList())).thenReturn("<claude>");
        when(claude.chat(any(MyHrGroundedPrompt.class))).thenReturn("<claude>");
        when(retrieval.retrieve(any(), anyString()))
                .thenReturn(new RetrievalResult(RetrievalStatus.NO_RELEVANT_KNOWLEDGE, List.of(), 0));
        MyHrOwnProfileResponse profile = mock(MyHrOwnProfileResponse.class, Mockito.RETURNS_DEEP_STUBS);
        when(profile.reportingManager().name()).thenReturn("Priya Manager");
        when(employees.getOwnDetail(anyString())).thenReturn(profile);
        service = new MyHrChatService(claude, ollama, timesheets, mock(ProfileChangeRequestService.class), employees,
                mock(DocumentRequestService.class), "claude", retrieval, true, false);
        service.useClock(Clock.fixed(Instant.parse("2026-11-05T15:00:00Z"), ZoneOffset.UTC));
    }

    // 1. "nagivate to document requests"
    @Test
    void misspelledNavigationOpensDocumentRequestsForEveryRoleWithoutAnyProviderOrRetrieval() {
        for (VerifiedMyHrActor actor : List.of(employee, hr, admin)) {
            for (String question : List.of("nagivate to document requests", "naviagte to document requests",
                    "navigte to documnet requests", "navigate to document reqeusts", "Nagivate to Document Requests")) {
                assertThat(ask(actor, question)).as(actor.role() + " " + question)
                        .isEqualTo("Use the Document Requests button below to open the Document Requests page.");
            }
        }
        verifyNoInteractions(claude, ollama, retrieval);
    }

    @Test
    void spellingToleranceNeverTurnsAnOrdinarySentenceIntoNavigation() {
        assertThat(MyHrConversationIntents.navigationTarget("nagivate to the cafeteria")).isNull();
        assertThat(MyHrConversationIntents.navigationTarget("why do documents need requests")).isNull();
        assertThat(MyHrConversationIntents.navigationTarget("open")).isNull();
        // Short words are never changed, and too many edits are not a misspelling.
        assertThat(MyHrConversationIntents.correctNavigationWords("go to doc")).isEqualTo("go to doc");
        assertThat(MyHrConversationIntents.correctNavigationWords("renovate")).isEqualTo("renovate");
        assertThat(MyHrConversationIntents.editDistance("nagivate", "navigate")).isEqualTo(2);
        assertThat(MyHrConversationIntents.editDistance("reqeusts", "requests")).isEqualTo(1);
    }

    // 2. "who is my manager"
    @Test
    void whoIsMyManagerReturnsTheAuthenticatedUsersOwnManager() {
        for (VerifiedMyHrActor actor : List.of(employee, hr, admin)) {
            assertThat(ask(actor, "who is my manager")).isEqualTo("Your reporting manager is Priya Manager.");
            verify(employees).getOwnDetail(actor.userId());
        }
        verifyNoInteractions(claude, ollama, retrieval);
    }

    // 3. "who reports to me?"
    @Test
    void whoReportsToMeFollowsEachRolesActualCapability() {
        when(employees.listDirectReports("hr-11")).thenReturn(List.of(new EmployeeSummaryResponse("u-1", null, "Asha Rao", "EMPLOYEE",
                null, null, "Analyst", null, null, "HR User", null)));

        assertThat(ask(employee, "who reports to me?")).startsWith("Employee accounts don't have direct reports");
        assertThat(ask(hr, "who reports to me?")).isEqualTo("1 person reports to you: Asha Rao (Analyst).");
        assertThat(ask(admin, "who reports to me?")).isEqualTo("No one currently reports to you in Waypoint.");
        verify(employees).listDirectReports("hr-11");
        verify(employees, never()).list("employee-11");
        verify(employees, never()).list("admin-11");
        verifyNoInteractions(claude, ollama, retrieval);
    }

    // 4. "nenu HR ah kadha?"
    @Test
    void teluguRoleQuestionIsAnsweredFromTheServerSideRole() {
        assertThat(ask(employee, "nenu HR ah kadha?")).isEqualTo("No. Your Waypoint account role is Employee.");
        assertThat(ask(hr, "nenu HR ah kadha?")).isEqualTo("Yes. Your Waypoint account role is HR.");
        assertThat(ask(admin, "nenu HR ah kadha?")).isEqualTo("No. Your Waypoint account role is Company Admin.");
        assertThat(ask(employee, "nenu hr kada")).isEqualTo("No. Your Waypoint account role is Employee.");
        verifyNoInteractions(claude, ollama, retrieval);
    }

    // 5. "annual leave policy"
    @Test
    void annualLeavePolicyIsCitedFromAnApprovedPolicyOrGetsTheFixedNotFoundReply() {
        for (VerifiedMyHrActor actor : List.of(employee, hr, admin)) {
            MyHrChatResponse notFound = service.chat(actor, "annual leave policy", List.of());
            assertThat(notFound.message()).isEqualTo(MyHrKnowledgeGrounding.POLICY_NOT_FOUND_ANSWER);
            assertThat(notFound.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.NO_MATCHING_POLICY);
            verify(retrieval).retrieve(actor, "What is the annual leave policy? " + MyHrKnowledgeGrounding.LEAVE_SYNONYMS);
        }
        verify(ollama, never()).chat(any(MyHrGroundedPrompt.class));
        verify(claude, never()).chat(any(MyHrGroundedPrompt.class));

        String policy = "Annual leave: employees receive 20 days of paid annual leave per calendar year.";
        Mockito.reset(retrieval);
        when(retrieval.retrieve(eq(employee), anyString())).thenReturn(new RetrievalResult(RetrievalStatus.RETRIEVED,
                List.of(new RetrievedChunk("leave-00000", "leave", "Leave Policy", "stored-leave", "leave.pdf", "leave-ing",
                        0, ChunkAccessLevel.COMPANY_WIDE, LocalDateTime.of(2026, 10, 1, 9, 0), 0.9, policy)), 1));
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn("Employees receive 20 days of paid annual leave [S1].");
        MyHrChatResponse cited = service.chat(employee, "annual leave policy", List.of());
        assertThat(cited.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.GROUNDED);
        assertThat(cited.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.title()).isEqualTo("Leave Policy");
            assertThat(citation.hrOnly()).isFalse();
        });
        // Claude is never used for policy text while external processing is not approved.
        verify(claude, never()).chatWithDocuments(any());

        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn("You have 30 days of annual leave.");
        assertThat(service.chat(employee, "annual leave policy", List.of()).knowledgeStatus())
                .isEqualTo(MyHrKnowledgeStatus.UNVERIFIED);
    }

    // 6. "i need to fill the timesheets"
    @Test
    void needingToFillTimesheetsGetsTheEnterTimesheetGuidanceForTheRole() {
        for (String question : List.of("i need to fill the timesheets", "I need to fill my timesheet",
                "i want to fill up my timesheets", "filling my timesheet")) {
            assertThat(ask(employee, question)).as(question).startsWith("Open Timesheets and choose Enter Timesheet");
        }
        assertThat(ask(hr, "i need to fill the timesheets"))
                .startsWith("Open Timesheets, stay on the My Timesheets tab, and choose Enter Timesheet");
        assertThat(ask(admin, "i need to fill the timesheets"))
                .startsWith("Company Admin accounts can review timesheets but can't submit one");
        verifyNoInteractions(claude, ollama, retrieval);
    }

    // 7. "did i fill my timesheets for this week?"
    @Test
    void thisWeeksQuestionChecksExactlyMondayToSundayOfTheCurrentWeek() {
        when(timesheets.getOwnStatusesOverlapping("employee-11", MONDAY, SUNDAY)).thenReturn(List.of(
                new MyHrTimesheetPeriodStatus(MONDAY, SUNDAY, TimesheetStatus.DRAFT, null)));

        assertThat(ask(employee, "did i fill my timesheets for this week?"))
                .isEqualTo("For this week (Mon Nov 2, 2026 to Sun Nov 8, 2026): your timesheet for Mon Nov 2, 2026 to "
                        + "Sun Nov 8, 2026 is saved as a draft and has not been submitted yet.");
        assertThat(ask(admin, "did i fill my timesheets for this week?")).startsWith("Company Admin accounts don't submit");
        verify(timesheets).getOwnStatusesOverlapping("employee-11", MONDAY, SUNDAY);
        verify(timesheets, never()).getMostRecentStatusForUser(anyString());
        verify(timesheets, never()).getOwnStatusesOverlapping(eq("admin-11"), any(), any());
        verifyNoInteractions(claude, ollama, retrieval);
    }

    // 8. "facing issue is uploading the document in the timesheets"
    @Test
    void uploadTroubleGetsGuidanceFromTheUploadCodeForEveryRole() {
        for (VerifiedMyHrActor actor : List.of(employee, hr, admin)) {
            for (String question : List.of("facing issue is uploading the document in the timesheets",
                    "facing issue in uploading the document in timesheet", "timesheet document upload not working")) {
                assertThat(ask(actor, question)).as(question).isEqualTo(MyHrConversationIntents.TIMESHEET_UPLOAD_HELP);
            }
        }
        assertThat(MyHrConversationIntents.TIMESHEET_UPLOAD_HELP).contains("up to 5 MB each", "PDF", ".docx", ".xlsx",
                "CSV", "PNG", "JPG");
        verifyNoInteractions(claude, ollama, retrieval, timesheets);
    }

    private String ask(VerifiedMyHrActor actor, String question) {
        return service.chat(actor, question, List.of()).message();
    }
}
