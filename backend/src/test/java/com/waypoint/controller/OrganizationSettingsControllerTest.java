package com.waypoint.controller;

import com.waypoint.dto.myhr.OrganizationSettingsResponse;
import com.waypoint.dto.myhr.UpdateOrganizationSettingsRequest;
import com.waypoint.enums.CompanySize;
import com.waypoint.enums.PayrollPeriod;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.security.JwtService;
import com.waypoint.service.OrganizationSettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrganizationSettingsController.class)
class OrganizationSettingsControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean OrganizationSettingsService organizationSettingsService;
    @MockitoBean CurrentUserResolver currentUserResolver;
    @MockitoBean JwtService jwtService;

    private static final String VALID_BODY = "{"
            + "\"organizationName\":\"Tardus International\","
            + "\"organizationEmail\":\"myhr.dev.tardus@waypoint.local\","
            + "\"phoneNumber\":\"+15551234567\","
            + "\"streetAddress\":\"200 Lake Street\","
            + "\"city\":\"Austin\","
            + "\"state\":\"TX\","
            + "\"zipCode\":\"78701\","
            + "\"organizationSize\":\"LARGE\","
            + "\"industry\":\"Software\","
            + "\"website\":\"https://tardus.example.com\","
            + "\"einNumber\":\"84-3596475\","
            + "\"eVerifyNumber\":\"1533740\","
            + "\"description\":\"We build things.\","
            + "\"payrollPeriod\":\"MONTHLY\","
            + "\"removeLogo\":false,"
            + "\"removeSignature\":false"
            + "}";

    private OrganizationSettingsResponse sampleResponse() {
        return new OrganizationSettingsResponse(
                "organization-1", "TD", "myhr.dev.tardus@waypoint.local", "Tardus International",
                "+15551234567", "200 Lake Street", "Austin", "TX", "78701",
                CompanySize.LARGE, "Software", "https://tardus.example.com",
                "84-3596475", "1533740", "Jane Admin", "jane.admin@tardus.example.com", "+19726936993", "We build things.",
                PayrollPeriod.MONTHLY, "/uploads/company-logos/organization-1/logo.png",
                "/uploads/company-signatures/organization-1/signature.png");
    }

    @Test
    void companyAdminCanRetrieveOrganizationSettings() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("admin-1");
        when(organizationSettingsService.getForActor("admin-1")).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/myhr/organization-settings")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.organizationId").value("organization-1"))
                .andExpect(jsonPath("$.data.organizationCode").value("TD"))
                .andExpect(jsonPath("$.data.organizationName").value("Tardus International"))
                .andExpect(jsonPath("$.data.organizationSize").value("LARGE"))
                .andExpect(jsonPath("$.data.einNumber").value("84-3596475"))
                .andExpect(jsonPath("$.data.eVerifyNumber").value("1533740"))
                .andExpect(jsonPath("$.data.payrollPeriod").value("MONTHLY"));

        verify(organizationSettingsService).getForActor(eq("admin-1"));
    }

    @Test
    void companyAdminCanUpdateOrganizationSettings() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("admin-1");
        when(organizationSettingsService.updateForActor(eq("admin-1"), any(UpdateOrganizationSettingsRequest.class)))
                .thenReturn(sampleResponse());

        mockMvc.perform(put("/api/myhr/organization-settings")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Organization settings updated"))
                .andExpect(jsonPath("$.data.organizationName").value("Tardus International"))
                .andExpect(jsonPath("$.data.einNumber").value("84-3596475"))
                .andExpect(jsonPath("$.data.eVerifyNumber").value("1533740"))
                .andExpect(jsonPath("$.data.payrollPeriod").value("MONTHLY"));

        verify(organizationSettingsService).updateForActor(eq("admin-1"), any(UpdateOrganizationSettingsRequest.class));
    }

    @Test
    void ignoresOrganizationCodeOverpostAttemptSinceTheRequestHasNoSuchField() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("admin-1");
        when(organizationSettingsService.updateForActor(eq("admin-1"), any(UpdateOrganizationSettingsRequest.class)))
                .thenReturn(sampleResponse());

        mockMvc.perform(put("/api/myhr/organization-settings")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("{", "{\"organizationCode\":\"ATTEMPTED-OVERWRITE\",")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.organizationCode").value("TD"));
    }

    @Test
    void rejectsAMissingOrganizationName() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("admin-1");

        mockMvc.perform(put("/api/myhr/organization-settings")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"organizationName\":\"Tardus International\",", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.organizationName").exists());
    }

    @Test
    void rejectsAnInvalidOrganizationEmail() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("admin-1");

        mockMvc.perform(put("/api/myhr/organization-settings")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("myhr.dev.tardus@waypoint.local", "not-an-email")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.organizationEmail").exists());
    }

    @Test
    void rejectsAnEinContainingLettersOrTheWrongNumberOfDigits() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("admin-1");

        mockMvc.perform(put("/api/myhr/organization-settings")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("84-3596475", "145236526ww")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.einNumber").value("EIN must be 9 digits, e.g. 12-3456789"));
    }

    @Test
    void surfacesADuplicateOrganizationEmailAsABadRequest() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("admin-1");
        when(organizationSettingsService.updateForActor(eq("admin-1"), any(UpdateOrganizationSettingsRequest.class)))
                .thenThrow(new BusinessException("Organization email is already in use"));

        mockMvc.perform(put("/api/myhr/organization-settings")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Organization email is already in use"));
    }

    @Test
    void rejectsAnInvalidPayrollPeriodValue() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("admin-1");

        mockMvc.perform(put("/api/myhr/organization-settings")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"payrollPeriod\":\"MONTHLY\"", "\"payrollPeriod\":\"DAILY\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void rejectsAnOversizedDescription() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("admin-1");

        mockMvc.perform(put("/api/myhr/organization-settings")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"description\":\"We build things.\"", "\"description\":\"" + "x".repeat(2001) + "\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.description").exists());
    }

    @Test
    void companyAdminCanUploadAnOrganizationLogo() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("admin-1");
        MockMultipartFile file = new MockMultipartFile("file", "logo.png", "image/png", new byte[]{1, 2, 3});
        when(organizationSettingsService.uploadLogo(eq("admin-1"), any())).thenReturn(sampleResponse());

        mockMvc.perform(multipart("/api/myhr/organization-settings/logo")
                        .file(file)
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Organization logo updated"))
                .andExpect(jsonPath("$.data.organizationLogoUrl").value("/uploads/company-logos/organization-1/logo.png"));

        verify(organizationSettingsService).uploadLogo(eq("admin-1"), any());
    }

    @Test
    void thereIsNoOrganizationSignatureUploadEndpointBecauseMyProfileIsTheOnlyPlaceToManageSignatures() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("admin-1");
        MockMultipartFile file = new MockMultipartFile("file", "signature.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/myhr/organization-settings/signature")
                        .file(file)
                        .header("Authorization", "Bearer token"))
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertNotEquals(200, result.getResponse().getStatus()));
        verifyNoInteractions(organizationSettingsService);
    }

    @Test
    void surfacesTheServiceAuthorizationGateAsAForbiddenResponse() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("hr-1");
        when(organizationSettingsService.getForActor("hr-1"))
                .thenThrow(new ForbiddenException("You do not have access to this resource"));

        mockMvc.perform(get("/api/myhr/organization-settings")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
    }
}
