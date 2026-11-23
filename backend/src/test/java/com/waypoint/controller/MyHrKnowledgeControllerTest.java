package com.waypoint.controller;

import com.waypoint.dto.myhr.KnowledgeDocumentResponse;
import com.waypoint.dto.myhr.KnowledgeOperationResponse;
import com.waypoint.enums.ChunkAccessLevel;
import com.waypoint.enums.KnowledgeDocumentStatus;
import com.waypoint.enums.KnowledgeIngestionStage;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.exception.UnauthorizedException;
import com.waypoint.security.JwtService;
import com.waypoint.security.MyHrActorResolver;
import com.waypoint.security.TestActors;
import com.waypoint.security.VerifiedMyHrActor;
import com.waypoint.service.KnowledgeContentReviewService;
import com.waypoint.service.KnowledgeManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Web-slice tests with every collaborator mocked: no MongoDB, storage or embedding provider. */
@WebMvcTest(value = MyHrKnowledgeController.class, properties = "waypoint.myhr.knowledge.api.enabled=true")
class MyHrKnowledgeControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean KnowledgeManagementService knowledgeManagementService;
    @MockitoBean KnowledgeContentReviewService knowledgeContentReviewService;
    @MockitoBean MyHrActorResolver actorResolver;
    @MockitoBean JwtService jwtService;

    private final VerifiedMyHrActor hr = TestActors.hr("hr-1", "org-1");

    @Test
    void unauthenticatedRequestsAreRejectedBeforeTheController() throws Exception {
        mockMvc.perform(get("/api/myhr/knowledge/documents")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/myhr/knowledge/documents/kd-1/revoke")).andExpect(status().isUnauthorized());
        verifyNoInteractions(knowledgeManagementService, actorResolver);
    }

    @Test
    void usersWhoAreNotActiveHrOrCompanyAdminsAreRefused() throws Exception {
        givenToken();
        when(actorResolver.resolveKnowledgeManager(any()))
                .thenThrow(new ForbiddenException("You do not have access to this resource"))
                .thenThrow(new UnauthorizedException("Account is not active"));

        mockMvc.perform(get("/api/myhr/knowledge/documents").header("Authorization", "Bearer token"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/myhr/knowledge/documents/kd-1/ingest").header("Authorization", "Bearer token"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(knowledgeManagementService);
    }

    @Test
    void listsMetadataOnlyForTheVerifiedActor() throws Exception {
        givenHr();
        when(knowledgeManagementService.list(hr)).thenReturn(List.of(document()));

        mockMvc.perform(get("/api/myhr/knowledge/documents").header("Authorization", "Bearer token")
                        .param("organizationId", "org-evil"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("kd-1"))
                .andExpect(jsonPath("$.data[0].title").value("Leave Policy"))
                .andExpect(jsonPath("$.data[0].pendingVersion.stage").value("INDEXED"))
                .andExpect(jsonPath("$.data[0].pendingVersion.accessLevel").value("HR_ONLY"))
                .andExpect(jsonPath("$.data[0].actions.activate").value(true))
                .andExpect(jsonPath("$.data[0].pendingVersion.contentSha256").doesNotExist())
                .andExpect(jsonPath("$.data[0].pendingVersion.approvedByUserId").doesNotExist())
                .andExpect(jsonPath("$.data[0].pendingVersion.embeddingModel").doesNotExist())
                .andExpect(jsonPath("$.data[0].organizationId").doesNotExist());
        verify(actorResolver).resolveKnowledgeManager(any());
    }

    @Test
    void addPolicyPassesTheUploadToTheServiceWithTheVerifiedActorOnly() throws Exception {
        givenHr();
        when(knowledgeManagementService.addPolicy(eq(hr), any(), eq("Leave Policy"), eq(ChunkAccessLevel.HR_ONLY)))
                .thenReturn(document());

        mockMvc.perform(multipart("/api/myhr/knowledge/documents")
                        .file(new MockMultipartFile("file", "leave.pdf", "application/pdf", "%PDF-1.7".getBytes()))
                        .param("title", "Leave Policy")
                        .param("accessLevel", "HR_ONLY")
                        .param("organizationId", "org-evil")
                        .param("userId", "admin-evil")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value("kd-1"));
        verify(knowledgeManagementService).addPolicy(eq(hr), any(), eq("Leave Policy"), eq(ChunkAccessLevel.HR_ONLY));
    }

    @Test
    void activationAndIncompleteTextAcceptanceRequireTheReviewedVersion() throws Exception {
        givenHr();
        mockMvc.perform(post("/api/myhr/knowledge/documents/kd-1/activate").header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersionId\":\" \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/myhr/knowledge/documents/kd-1/accept-incomplete-text").header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(knowledgeManagementService);

        when(knowledgeManagementService.activate(hr, "kd-1", "ingestion-1"))
                .thenReturn(new KnowledgeOperationResponse(document(), "ACTIVATED", "This version is now active."));
        mockMvc.perform(post("/api/myhr/knowledge/documents/kd-1/activate").header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersionId\":\"ingestion-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("This version is now active."))
                .andExpect(jsonPath("$.data.outcome").value("ACTIVATED"));
    }

    @Test
    void historicalRestorationRequiresTheServerProvidedHistoricalSourceId() throws Exception {
        givenHr();
        mockMvc.perform(post("/api/myhr/knowledge/documents/kd-1/restore-version").header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedHistoricalSourceDocumentId\":\" \"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(knowledgeManagementService);

        when(knowledgeManagementService.restoreHistoricalVersion(hr, "kd-1", "stored-old")).thenReturn(document());
        mockMvc.perform(post("/api/myhr/knowledge/documents/kd-1/restore-version").header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedHistoricalSourceDocumentId\":\"stored-old\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Historical version approved for restoration"))
                .andExpect(jsonPath("$.data.id").value("kd-1"));
        verify(knowledgeManagementService).restoreHistoricalVersion(hr, "kd-1", "stored-old");
    }

    @Test
    void revokeReturnsTheOperationMessage() throws Exception {
        givenHr();
        when(knowledgeManagementService.revoke(hr, "kd-1"))
                .thenReturn(new KnowledgeOperationResponse(document(), "REVOKED", "The document was revoked."));

        mockMvc.perform(post("/api/myhr/knowledge/documents/kd-1/revoke").header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("REVOKED"))
                .andExpect(jsonPath("$.message").value("The document was revoked."));
    }

    private void givenToken() {
        when(jwtService.extractUserId("token")).thenReturn("hr-1");
        when(jwtService.extractRole("token")).thenReturn(UserRole.HR.name());
    }

    private void givenHr() {
        givenToken();
        when(actorResolver.resolveKnowledgeManager(any())).thenReturn(hr);
    }

    private static KnowledgeDocumentResponse document() {
        KnowledgeDocumentResponse.Version pending = new KnowledgeDocumentResponse.Version(
                "stored-1", "leave.pdf", ChunkAccessLevel.HR_ONLY, KnowledgeIngestionStage.INDEXED, 3, 0, false,
                "ingestion-1", 12, LocalDateTime.of(2026, 10, 19, 9, 0), LocalDateTime.of(2026, 10, 19, 9, 5),
                LocalDateTime.of(2026, 10, 19, 9, 6), null, null, null, false);
        return new KnowledgeDocumentResponse("kd-1", "Leave Policy", KnowledgeDocumentStatus.LIVE, null, pending, 0,
                LocalDateTime.of(2026, 10, 19, 9, 0), null,
                new KnowledgeDocumentResponse.Actions(false, false, true, true, true, false));
    }
}
