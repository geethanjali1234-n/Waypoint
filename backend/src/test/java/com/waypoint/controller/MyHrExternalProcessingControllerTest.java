package com.waypoint.controller;

import com.waypoint.dto.myhr.ExternalProcessingStatusResponse;
import com.waypoint.enums.ChunkAccessLevel;
import com.waypoint.enums.ExternalAiProvider;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.exception.GlobalExceptionHandler;
import com.waypoint.security.MyHrActorResolver;
import com.waypoint.security.TestActors;
import com.waypoint.security.VerifiedMyHrActor;
import com.waypoint.service.ExternalProcessingApprovalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Routes, validation and identity for external AI processing approvals; the service is mocked. */
class MyHrExternalProcessingControllerTest {

    private final MyHrActorResolver resolver = mock(MyHrActorResolver.class);
    private final ExternalProcessingApprovalService service = mock(ExternalProcessingApprovalService.class);
    private final VerifiedMyHrActor admin = TestActors.companyAdmin("admin-1", "org-1");
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new MyHrExternalProcessingController(resolver, service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void statusIsReadForTheVerifiedActor() throws Exception {
        when(resolver.resolveKnowledgeManager(any())).thenReturn(admin);
        when(service.status(admin)).thenReturn(new ExternalProcessingStatusResponse(false, true, false, false, List.of()));

        mockMvc.perform(get("/api/myhr/knowledge/external-processing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.claudeSwitchEnabled").value(false))
                .andExpect(jsonPath("$.data.claudeApprovedForCompanyWide").value(false));
    }

    @Test
    void theOrganizationAndApproverComeFromTheTokenNotTheBody() throws Exception {
        when(resolver.resolveKnowledgeManager(any())).thenReturn(admin);

        mockMvc.perform(post("/api/myhr/knowledge/external-processing/organization/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"ANTHROPIC_CLAUDE\",\"accessLevels\":[\"COMPANY_WIDE\"],"
                                + "\"approvalReference\":\"DPA-1\",\"acknowledgeExternalTransmission\":true,"
                                + "\"organizationId\":\"org-attacker\",\"approvedByUserId\":\"someone-else\"}"))
                .andExpect(status().isOk());

        verify(service).approveOrganization(admin, ExternalAiProvider.ANTHROPIC_CLAUDE,
                Set.of(ChunkAccessLevel.COMPANY_WIDE), "DPA-1", true);
    }

    @Test
    void aMissingOrUnknownProviderIsRejectedBeforeTheService() throws Exception {
        when(resolver.resolveKnowledgeManager(any())).thenReturn(admin);

        mockMvc.perform(post("/api/myhr/knowledge/external-processing/organization/revoke")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Ended\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/myhr/knowledge/external-processing/documents/kd-1/approve")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"provider\":\"OPENAI\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void aNonAdminIsRefusedWithForbidden() throws Exception {
        VerifiedMyHrActor hr = TestActors.actor("hr-1", "org-1", UserRole.HR);
        when(resolver.resolveKnowledgeManager(any())).thenReturn(hr);
        when(service.approveDocument(eq(hr), any(), anyString(), any(), any(), anyBoolean()))
                .thenThrow(new ForbiddenException("Only a Company Admin can approve or revoke external AI processing"));

        mockMvc.perform(post("/api/myhr/knowledge/external-processing/documents/kd-1/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"ANTHROPIC_CLAUDE\",\"expectedIngestionId\":\"ing-1\","
                                + "\"approvalReference\":\"DPA-1\",\"acknowledgeExternalTransmission\":true}"))
                .andExpect(status().isForbidden());
    }
}
