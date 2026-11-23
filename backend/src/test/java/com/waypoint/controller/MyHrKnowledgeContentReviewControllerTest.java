package com.waypoint.controller;

import com.waypoint.dto.myhr.KnowledgeContentPreviewResponse;
import com.waypoint.dto.myhr.KnowledgeContentReviewRequest;
import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.KnowledgeContentReviewDecision;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.GlobalExceptionHandler;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.repository.UserRepository;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.security.JwtService;
import com.waypoint.security.MyHrActorResolver;
import com.waypoint.security.MyHrAuthenticatedUserService;
import com.waypoint.security.VerifiedMyHrActor;
import com.waypoint.service.DocumentTextExtractor.FindingCategory;
import com.waypoint.service.KnowledgeContentReviewService;
import com.waypoint.service.KnowledgeManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * G2 Phase 1C endpoints through the real token verification, database role check, request
 * validation and exception handler; only the user lookup and the review service are mocked.
 * Employee refusal (403) for both endpoints is covered by MyHrKnowledgeControllerEmployeeAuthorizationTest.
 */
class MyHrKnowledgeContentReviewControllerTest {

    private static final String SECRET = "waypoint-test-only-jwt-signing-secret-0123456789";
    private static final long ONE_HOUR = 3_600_000;
    private static final String PREVIEW = "/api/myhr/knowledge/documents/kd-1/pending/extracted-text";
    private static final String REVIEW = "/api/myhr/knowledge/documents/kd-1/content-review";
    private static final String FINDINGS = "a".repeat(64);
    private static final String TEXT = "b".repeat(64);

    private final UserRepository userRepository = mock(UserRepository.class);
    private final KnowledgeManagementService management = mock(KnowledgeManagementService.class);
    private final KnowledgeContentReviewService contentReview = mock(KnowledgeContentReviewService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        MyHrActorResolver actorResolver = new MyHrActorResolver(
                new CurrentUserResolver(new JwtService(SECRET, ONE_HOUR)),
                new MyHrAuthenticatedUserService(userRepository));
        mockMvc = MockMvcBuilders.standaloneSetup(new MyHrKnowledgeController(actorResolver, management, contentReview))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        user("hr-1", UserRole.HR);
        user("admin-1", UserRole.COMPANY_ADMIN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"hr-1", "admin-1"})
    void hrAndCompanyAdminCanPreviewThePendingText(String userId) throws Exception {
        when(contentReview.preview(any(), eq("kd-1"))).thenReturn(new KnowledgeContentPreviewResponse(
                "stored-1", FINDINGS, TEXT, "Leave policy <b>text</b>", List.of(new KnowledgeContentPreviewResponse.Passage(
                "instruction-0", FindingCategory.INSTRUCTION_LIKE, "normalized extracted text", 0, 5, false, true, "Leave")),
                true, false));

        mockMvc.perform(get(PREVIEW).header("Authorization", "Bearer " + token(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.indexableText").value("Leave policy <b>text</b>"))
                .andExpect(jsonPath("$.data.findingsFingerprint").value(FINDINGS))
                .andExpect(jsonPath("$.data.passages[0].category").value("INSTRUCTION_LIKE"));

        ArgumentCaptor<VerifiedMyHrActor> actor = ArgumentCaptor.forClass(VerifiedMyHrActor.class);
        verify(contentReview).preview(actor.capture(), eq("kd-1"));
        assertThat(actor.getValue().userId()).isEqualTo(userId);
        assertThat(actor.getValue().organizationId()).isEqualTo("org-1");
    }

    @Test
    void aValidReviewIsPassedToTheServiceWithTheVerifiedActor() throws Exception {
        mockMvc.perform(post(REVIEW).header("Authorization", "Bearer " + token("admin-1"))
                        .contentType(MediaType.APPLICATION_JSON).content(body("INCLUDE_FLAGGED_TEXT", FINDINGS, TEXT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Content review recorded"));

        ArgumentCaptor<KnowledgeContentReviewRequest> request = ArgumentCaptor.forClass(KnowledgeContentReviewRequest.class);
        ArgumentCaptor<VerifiedMyHrActor> actor = ArgumentCaptor.forClass(VerifiedMyHrActor.class);
        verify(contentReview).review(actor.capture(), eq("kd-1"), request.capture());
        assertThat(actor.getValue().role()).isEqualTo(UserRole.COMPANY_ADMIN);
        assertThat(request.getValue().decision()).isEqualTo(KnowledgeContentReviewDecision.INCLUDE_FLAGGED_TEXT);
        assertThat(request.getValue().expectedSourceDocumentId()).isEqualTo("stored-1");
    }

    @Test
    void malformedReviewRequestsAreRejectedBeforeTheService() throws Exception {
        for (String invalid : List.of(
                body("INCLUDE_FLAGGED_TEXT", "not-a-hash", TEXT),
                body("INCLUDE_FLAGGED_TEXT", FINDINGS, "B".repeat(64)),
                body("APPROVE_EVERYTHING", FINDINGS, TEXT),
                "{\"expectedSourceDocumentId\":\"stored-1\",\"findingsFingerprint\":\"" + FINDINGS
                        + "\",\"indexableTextFingerprint\":\"" + TEXT + "\"}",
                "{\"expectedSourceDocumentId\":\"\",\"findingsFingerprint\":\"" + FINDINGS
                        + "\",\"indexableTextFingerprint\":\"" + TEXT + "\",\"decision\":\"INCLUDE_FLAGGED_TEXT\"}",
                "{\"expectedSourceDocumentId\":\"stored-1\",\"findingsFingerprint\":\"" + FINDINGS
                        + "\",\"indexableTextFingerprint\":\"" + TEXT + "\",\"decision\":\"INCLUDE_FLAGGED_TEXT\","
                        + "\"excludedPassageIds\":" + tooManyIds() + "}")) {
            mockMvc.perform(post(REVIEW).header("Authorization", "Bearer " + token("hr-1"))
                            .contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }
        verifyNoInteractions(contentReview);
    }

    @Test
    void anotherOrganizationsDocumentIsANotFound() throws Exception {
        when(contentReview.preview(any(), eq("kd-1"))).thenThrow(new ResourceNotFoundException("Knowledge document not found"));
        when(contentReview.review(any(), eq("kd-1"), any())).thenThrow(new ResourceNotFoundException("Knowledge document not found"));

        mockMvc.perform(get(PREVIEW).header("Authorization", "Bearer " + token("hr-1")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Knowledge document not found"));
        mockMvc.perform(post(REVIEW).header("Authorization", "Bearer " + token("hr-1"))
                        .contentType(MediaType.APPLICATION_JSON).content(body("INCLUDE_FLAGGED_TEXT", FINDINGS, TEXT)))
                .andExpect(status().isNotFound());
    }

    @Test
    void aRejectedDecisionIsAClientErrorWithItsFixedMessage() throws Exception {
        String message = "Excluding flagged indexed text is not available until passage-level extraction offsets are preserved";
        when(contentReview.review(any(), eq("kd-1"), any())).thenThrow(new BusinessException(message));

        mockMvc.perform(post(REVIEW).header("Authorization", "Bearer " + token("hr-1"))
                        .contentType(MediaType.APPLICATION_JSON).content(body("EXCLUDE_FLAGGED_TEXT", FINDINGS, TEXT)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(message));
    }

    @Test
    void unauthenticatedCallsNeverReachTheService() throws Exception {
        mockMvc.perform(get(PREVIEW)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(REVIEW).contentType(MediaType.APPLICATION_JSON).content(body("INCLUDE_FLAGGED_TEXT", FINDINGS, TEXT)))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(contentReview);
    }

    private void user(String id, UserRole role) {
        when(userRepository.findById(id)).thenReturn(Optional.of(User.builder()
                .id(id).loginId(id.toUpperCase()).email(id + "@waypoint.local").role(role)
                .organizationId("org-1").accountStatus(AccountStatus.ACTIVE).mustChangePassword(false).build()));
    }

    private static String token(String userId) {
        return new JwtService(SECRET, ONE_HOUR).generateToken(userId, userId + "@waypoint.local", "HR");
    }

    private static String body(String decision, String findings, String text) {
        return "{\"expectedSourceDocumentId\":\"stored-1\",\"findingsFingerprint\":\"" + findings
                + "\",\"indexableTextFingerprint\":\"" + text + "\",\"decision\":\"" + decision + "\"}";
    }

    private static String tooManyIds() {
        return "[" + String.join(",", java.util.Collections.nCopies(33, "\"p\"")) + "]";
    }
}
