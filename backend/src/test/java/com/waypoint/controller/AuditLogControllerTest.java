package com.waypoint.controller;

import com.waypoint.dto.myhr.AuditHistoryResponse;
import com.waypoint.enums.AuditActionType;
import com.waypoint.enums.AuditEventCategory;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.security.JwtService;
import com.waypoint.service.AuditQueryService;
import com.waypoint.service.AuditQueryService.AuditHistoryQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuditLogController.class)
class AuditLogControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean AuditQueryService auditQueryService;
    @MockitoBean CurrentUserResolver currentUserResolver;
    @MockitoBean JwtService jwtService;

    @Test
    void returnsHistoryForTheAuthenticatedCaller() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("admin-1");
        when(auditQueryService.getHistory(eq("admin-1"), any())).thenReturn(
                new AuditHistoryResponse(List.of(), 0, 25, 0, 0, List.of(), List.of()));

        mockMvc.perform(get("/api/myhr/audit").header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(25));
    }

    @Test
    void forwardsAllQueryParametersToTheService() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("admin-1");
        when(auditQueryService.getHistory(eq("admin-1"), any())).thenReturn(
                new AuditHistoryResponse(List.of(), 1, 10, 0, 0, List.of(), List.of()));

        mockMvc.perform(get("/api/myhr/audit")
                        .header("Authorization", "Bearer token")
                        .param("page", "1")
                        .param("size", "10")
                        .param("actionType", "ACCOUNT_STATUS_CHANGED")
                        .param("category", "ACCOUNT_MANAGEMENT")
                        .param("actorUserId", "hr-1")
                        .param("entityType", "USER")
                        .param("entityId", "emp-1")
                        .param("from", "2026-01-01")
                        .param("to", "2026-01-31")
                        .param("search", "jane"))
                .andExpect(status().isOk());

        var captor = forClass(AuditHistoryQuery.class);
        verify(auditQueryService).getHistory(eq("admin-1"), captor.capture());
        AuditHistoryQuery query = captor.getValue();

        assertThat(query.page()).isEqualTo(1);
        assertThat(query.size()).isEqualTo(10);
        assertThat(query.actionType()).isEqualTo(AuditActionType.ACCOUNT_STATUS_CHANGED);
        assertThat(query.category()).isEqualTo(AuditEventCategory.ACCOUNT_MANAGEMENT);
        assertThat(query.actorUserId()).isEqualTo("hr-1");
        assertThat(query.entityType()).isEqualTo("USER");
        assertThat(query.entityId()).isEqualTo("emp-1");
        assertThat(query.from()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(query.to()).isEqualTo(LocalDate.of(2026, 1, 31));
        assertThat(query.search()).isEqualTo("jane");
    }

    @Test
    void leavesFiltersNullWhenNoQueryParametersAreGiven() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("admin-1");
        when(auditQueryService.getHistory(eq("admin-1"), any())).thenReturn(
                new AuditHistoryResponse(List.of(), 0, 25, 0, 0, List.of(), List.of()));

        mockMvc.perform(get("/api/myhr/audit").header("Authorization", "Bearer token"))
                .andExpect(status().isOk());

        var captor = forClass(AuditHistoryQuery.class);
        verify(auditQueryService).getHistory(eq("admin-1"), captor.capture());
        AuditHistoryQuery query = captor.getValue();

        assertThat(query.page()).isNull();
        assertThat(query.size()).isNull();
        assertThat(query.actionType()).isNull();
        assertThat(query.category()).isNull();
        assertThat(query.search()).isNull();
    }
}
