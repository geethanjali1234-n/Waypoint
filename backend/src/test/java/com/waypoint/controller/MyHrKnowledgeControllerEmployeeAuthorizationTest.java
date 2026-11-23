package com.waypoint.controller;

import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.GlobalExceptionHandler;
import com.waypoint.repository.UserRepository;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.security.JwtService;
import com.waypoint.security.MyHrActorResolver;
import com.waypoint.security.MyHrAuthenticatedUserService;
import com.waypoint.service.KnowledgeManagementService;
import com.waypoint.service.KnowledgeContentReviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;
import java.util.stream.Stream;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every knowledge management endpoint, called by an Employee through the real token verification
 * and the real database role check: only the user lookup and the management service are mocked,
 * and the management service must never be reached. Each request is otherwise well formed, so the
 * refusal comes from authorization and not from validation.
 */
class MyHrKnowledgeControllerEmployeeAuthorizationTest {

    private static final String SECRET = "waypoint-test-only-jwt-signing-secret-0123456789";
    private static final long ONE_HOUR = 3_600_000;

    private final UserRepository userRepository = mock(UserRepository.class);
    private final KnowledgeManagementService knowledgeManagementService = mock(KnowledgeManagementService.class);
    private final KnowledgeContentReviewService knowledgeContentReviewService = mock(KnowledgeContentReviewService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        MyHrActorResolver actorResolver = new MyHrActorResolver(
                new CurrentUserResolver(new JwtService(SECRET, ONE_HOUR)),
                new MyHrAuthenticatedUserService(userRepository));
        mockMvc = MockMvcBuilders
                .standaloneSetup(new MyHrKnowledgeController(actorResolver, knowledgeManagementService, knowledgeContentReviewService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        when(userRepository.findById("employee-1")).thenReturn(Optional.of(User.builder()
                .id("employee-1")
                .loginId("WRAGT-E1")
                .email("employee-1@waypoint.local")
                .role(UserRole.EMPLOYEE)
                .organizationId("org-1")
                .accountStatus(AccountStatus.ACTIVE)
                .mustChangePassword(false)
                .build()));
    }

    static Stream<Arguments> knowledgeManagementEndpoints() {
        return Stream.of(
                Arguments.of("settings", get("/api/myhr/knowledge/settings")),
                Arguments.of("list", get("/api/myhr/knowledge/documents")),
                Arguments.of("preview pending extracted text", get("/api/myhr/knowledge/documents/kd-1/pending/extracted-text")),
                Arguments.of("review pending content", post("/api/myhr/knowledge/documents/kd-1/content-review")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedSourceDocumentId\":\"stored-1\",\"findingsFingerprint\":\"" + "a".repeat(64) + "\",\"indexableTextFingerprint\":\"" + "b".repeat(64) + "\",\"decision\":\"INCLUDE_FLAGGED_TEXT\"}")),
                Arguments.of("add policy", multipart("/api/myhr/knowledge/documents")
                        .file(policyFile())
                        .param("title", "Leave Policy")
                        .param("accessLevel", "COMPANY_WIDE")),
                Arguments.of("replace policy", multipart("/api/myhr/knowledge/documents/kd-1/replacement")
                        .file(policyFile())
                        .param("accessLevel", "HR_ONLY")),
                Arguments.of("restore historical version", post("/api/myhr/knowledge/documents/kd-1/restore-version")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedHistoricalSourceDocumentId\":\"stored-old\"}")),
                Arguments.of("accept incomplete text", post("/api/myhr/knowledge/documents/kd-1/accept-incomplete-text")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersionId\":\"stored-1\"}")),
                Arguments.of("ingest", post("/api/myhr/knowledge/documents/kd-1/ingest")),
                Arguments.of("activate", post("/api/myhr/knowledge/documents/kd-1/activate")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersionId\":\"ingestion-1\"}")),
                Arguments.of("revoke", post("/api/myhr/knowledge/documents/kd-1/revoke")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("knowledgeManagementEndpoints")
    void anEmployeeIsRefusedBeforeTheManagementService(String endpoint, AbstractMockHttpServletRequestBuilder<?> request)
            throws Exception {
        mockMvc.perform(withToken(request, token("EMPLOYEE")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(knowledgeManagementService);
        verifyNoInteractions(knowledgeContentReviewService);
    }

    /** A former HR user's old token still claims HR; the current database role decides. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("knowledgeManagementEndpoints")
    void anEmployeeHoldingAStaleHrTokenIsRefusedBeforeTheManagementService(
            String endpoint, AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
        mockMvc.perform(withToken(request, token("HR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(knowledgeManagementService);
        verifyNoInteractions(knowledgeContentReviewService);
    }

    private static RequestBuilder withToken(AbstractMockHttpServletRequestBuilder<?> request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private static String token(String roleClaim) {
        return new JwtService(SECRET, ONE_HOUR).generateToken("employee-1", "employee-1@waypoint.local", roleClaim);
    }

    private static MockMultipartFile policyFile() {
        return new MockMultipartFile("file", "leave-policy.txt", "text/plain", "Leave policy".getBytes());
    }
}
