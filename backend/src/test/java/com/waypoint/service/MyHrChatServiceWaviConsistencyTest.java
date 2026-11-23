package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrChatResponse;
import com.waypoint.dto.myhr.MyHrOwnProfileResponse;
import com.waypoint.dto.myhr.MyHrRecentTimesheetStatus;
import com.waypoint.dto.myhr.ReportingManagerRef;
import com.waypoint.enums.TimesheetStatus;
import com.waypoint.enums.UserRole;
import com.waypoint.security.TestActors;
import com.waypoint.security.VerifiedMyHrActor;
import com.waypoint.service.KnowledgeRetrievalService.RetrievalResult;
import com.waypoint.service.KnowledgeRetrievalService.RetrievalStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Wavi consistency: closely related wording must take the same deterministic path, answered from
 * Waypoint's own workflows or the verified user's own records, never from a model that might invent
 * account data or a different process. Every collaborator is a mock; no database or model is reached.
 */
class MyHrChatServiceWaviConsistencyTest {

    private final ClaudeChatClient claude = mock(ClaudeChatClient.class);
    private final OllamaClient ollama = mock(OllamaClient.class);
    private final TimesheetService timesheets = mock(TimesheetService.class);
    private final ProfileChangeRequestService profileChanges = mock(ProfileChangeRequestService.class);
    private final EmployeeManagementService employees = mock(EmployeeManagementService.class);
    private final KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
    private final VerifiedMyHrActor employee = TestActors.actor("employee-5", "org-5", UserRole.EMPLOYEE);
    private final VerifiedMyHrActor hr = TestActors.hr("hr-5", "org-5");
    private final VerifiedMyHrActor admin = TestActors.companyAdmin("admin-5", "org-5");
    private MyHrChatService service;

    @BeforeEach
    void setUp() {
        when(ollama.isLocalEndpoint()).thenReturn(true);
        org.mockito.Mockito.lenient().when(ollama.verifiedLocalModel()).thenReturn(true);
        service = new MyHrChatService(claude, ollama, timesheets, profileChanges, employees, "ollama", retrieval, true, false);
    }

    private String ask(VerifiedMyHrActor actor, String question) {
        return service.chat(actor, question, List.of()).message();
    }

    private void assertNoModelOrRetrieval() {
        verifyNoInteractions(claude, retrieval);
        verify(ollama, org.mockito.Mockito.never()).chat(any(MyHrGroundedPrompt.class));
        verify(ollama, org.mockito.Mockito.never()).chat(anyString(), any());
    }

    // 1. Already correct; must stay so.
    @Test
    void submittingATimesheetKeepsItsWorkflowSteps() {
        assertThat(ask(employee, "How do I submit my timesheet?")).contains("choose Enter Timesheet");
        assertNoModelOrRetrieval();
    }

    // 2. Was answered by the model ("contact your supervisor").
    @ParameterizedTest
    @ValueSource(strings = {"Where can I check my timesheet status?", "How can I check my timesheet status?",
            "Where do I see if my timesheet was approved?", "How can I track my submitted timesheets?"})
    void whereToCheckATimesheetStatusPointsToSubmittedTimesheets(String question) {
        String answer = ask(employee, question);

        assertThat(answer).contains("Open Timesheets and choose Submitted Timesheets").contains("status")
                .doesNotContainIgnoringCase("supervisor");
        assertNoModelOrRetrieval();
    }

    @Test
    void timesheetStatusGuidanceIsRoleSpecific() {
        assertThat(ask(hr, "Where can I check my timesheet status?"))
                .contains("stay on the My Timesheets tab").contains("Team Timesheets tab");
        assertThat(ask(admin, "Where can I check my timesheet status?"))
                .contains("Company Admin accounts don't submit timesheets").doesNotContain("My Timesheets tab");
        assertNoModelOrRetrieval();
    }

    // 3. Was sent to Profile Change Requests.
    @Test
    void onboardingDocumentsAreUploadedInDocumentRequests() {
        String answer = ask(employee, "Where can I upload my onboarding documents?");

        assertThat(answer).contains("uploaded in Document Requests, not in your profile")
                .contains("Request a document, then Offer Letter").doesNotContain("Profile Change Requests");
        assertThat(ask(hr, "How do I upload onboarding documents?")).contains("Request from employee");
        assertNoModelOrRetrieval();
    }

    // 4. Was generic advice.
    @Test
    void documentRequestStatusIsOnTheDocumentRequestsPage() {
        assertThat(ask(employee, "How can I check my document request status?"))
                .startsWith("Open Document Requests").contains("Pending, Completed or Rejected");
        assertThat(ask(hr, "Where can I see the status of my document requests?"))
                .contains("choose My requests").contains("Received requests");
        assertThat(ask(admin, "How do I track document request status?")).contains("Document Approvals");
        assertNoModelOrRetrieval();
    }

    // 5. The authenticated own-profile lookup existed but only matched exact phrasings.
    @ParameterizedTest
    @ValueSource(strings = {"Can you tell me my employee ID?", "Could you please tell me my employee ID",
            "Please show me my employee ID.", "What is my employee ID?"})
    void politeEmployeeIdQuestionsUseTheVerifiedUsersOwnProfile(String question) {
        MyHrOwnProfileResponse profile = mock(MyHrOwnProfileResponse.class);
        when(profile.employeeId()).thenReturn("WRAGT002");
        when(employees.getOwnDetail("employee-5")).thenReturn(profile);

        assertThat(ask(employee, question)).isEqualTo("Your employee ID is WRAGT002.");
        verify(employees).getOwnDetail("employee-5");
        assertNoModelOrRetrieval();
    }

    @Test
    void aMissingEmployeeIdIsReportedNotInvented() {
        when(employees.getOwnDetail("employee-5")).thenReturn(mock(MyHrOwnProfileResponse.class));

        assertThat(ask(employee, "Can you tell me my employee ID?")).isEqualTo("Your employee ID is not available in your profile.");
        assertNoModelOrRetrieval();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Who am I?", "Who am I", "who am i"})
    void whoAmIUsesTheVerifiedUsersProfileWithoutAnyProvider(String question) {
        MyHrOwnProfileResponse profile = mock(MyHrOwnProfileResponse.class);
        when(profile.name()).thenReturn("Employee Five");
        when(profile.employeeId()).thenReturn("WRAGT005");
        when(profile.designation()).thenReturn("Analyst");
        when(employees.getOwnDetail("employee-5")).thenReturn(profile);

        assertThat(ask(employee, question)).as(question)
                .isEqualTo("Your profile: Name: Employee Five; Employee ID: WRAGT005; Job title: Analyst; "
                        + "Department: not available in your self-profile; Work email: not available in your self-profile.");
        assertNoModelOrRetrieval();
    }

    @Test
    void politeTimesheetStatusQuestionsUseTheVerifiedUsersOwnRecord() {
        when(timesheets.getMostRecentStatusForUser("employee-5")).thenReturn(Optional.of(new MyHrRecentTimesheetStatus(
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15), TimesheetStatus.APPROVED)));

        assertThat(ask(employee, "Can you check my timesheet status?"))
                .isEqualTo("Your most recent timesheet for 2026-10-01 to 2026-10-15 was approved.");
        when(timesheets.getMostRecentStatusForUser("employee-5")).thenReturn(Optional.empty());
        assertThat(ask(employee, "Could you tell me my latest timesheet status")).isEqualTo("No timesheet found.");
        assertNoModelOrRetrieval();
    }

    // 6. Already personalized; must stay so.
    @Test
    void reportingManagerLookupIsUnchanged() {
        MyHrOwnProfileResponse profile = mock(MyHrOwnProfileResponse.class);
        when(profile.reportingManager()).thenReturn(new ReportingManagerRef("hr-5", "WRAGT001", "TEST HR", "HR", null, null));
        when(employees.getOwnDetail("employee-5")).thenReturn(profile);

        assertThat(ask(employee, "Who is my reporting manager?")).isEqualTo("Your reporting manager is TEST HR.");
        assertNoModelOrRetrieval();
    }

    // 7. Was answered with how to request a new letter.
    @ParameterizedTest
    @ValueSource(strings = {"Where can I download my approved offer letter?", "How do I view my offer letter?",
            "Where is my signed offer letter?"})
    void anExistingOfferLetterIsFoundOnTheOfferLetterRequest(String question) {
        String answer = ask(employee, question);

        assertThat(answer).startsWith("Open Document Requests and open your Offer Letter request")
                .contains("Preview and Download").doesNotContain("attach the six required onboarding documents");
        assertNoModelOrRetrieval();
    }

    @Test
    void offerLetterRetrievalIsRoleSpecificAndRequestingOneIsUnchanged() {
        assertThat(ask(hr, "Where can I download my approved offer letter?")).contains("choose My requests");
        assertThat(ask(admin, "Where can I download my approved offer letter?"))
                .contains("Company Admin accounts don't receive offer letters");
        assertThat(ask(employee, "How do I request an offer letter?")).contains("attach the six required onboarding documents");
        assertThat(ask(employee, "I'm a new hire and want my official offer letter"))
                .contains("attach the six required onboarding documents");
        assertNoModelOrRetrieval();
    }

    // --- Unchanged paths --------------------------------------------------------------------------

    @Test
    void correctionsAndActionRequestsKeepTheirExistingAnswers() {
        assertThat(ask(employee, "How do I fix a rejected timesheet?")).contains("correct the hours and resubmit it");
        assertThat(ask(employee, "Can you approve my timesheet?"))
                .startsWith(MyHrChatService.ACTION_REQUEST_REPLY).contains("Employees don't review timesheets");
        assertNoModelOrRetrieval();
    }

    @Test
    void policyQuestionsStillGoToCompanyPolicyRetrieval() {
        when(retrieval.retrieve(any(), anyString())).thenReturn(new RetrievalResult(RetrievalStatus.NO_RELEVANT_KNOWLEDGE, List.of(), 0));
        when(ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn("General guidance.");

        // Location wording does not capture policy questions: they are still retrieved, never given workflow text.
        for (String question : List.of("Where can I find the timesheet policy?", "How many vacation days do I get?")) {
            MyHrChatResponse response = service.chat(employee, question, List.of());
            assertThat(response.message()).as(question).isEqualTo(MyHrKnowledgeGrounding.POLICY_NOT_FOUND_ANSWER);
        }
        assertThat(ask(employee, "When is the timesheet deadline?")).isEqualTo("General guidance.");
        verify(retrieval, org.mockito.Mockito.times(3)).retrieve(any(), anyString());
    }
}
