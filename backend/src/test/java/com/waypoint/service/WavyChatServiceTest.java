package com.waypoint.service;

import com.waypoint.dto.myhr.WavyChatRequest;
import com.waypoint.dto.myhr.WavyChatResponse;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.UserRole;
import com.waypoint.security.MyHrAuthenticatedUserService.AuthenticatedMyHrUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WavyChatServiceTest {
    private final WavyModelClient model = mock(WavyModelClient.class);
    private final WavyToolsService tools = mock(WavyToolsService.class);
    private final WavyKnowledgeService knowledge = new WavyKnowledgeService();
    private final WavyChatService service = new WavyChatService(model, tools, knowledge);
    private final ObjectMapper json = new ObjectMapper();
    private final AuthenticatedMyHrUser employee = new AuthenticatedMyHrUser("employee-1", "test", "test@example.test", UserRole.EMPLOYEE, "org-1", AccountStatus.ACTIVE, false);
    private WavyChatRequest question(String text) { return new WavyChatRequest(text, "/myhr/employee/timesheets", List.of()); }

    @BeforeEach void setup() { when(model.isConfigured()).thenReturn(true); when(tools.definitions(employee)).thenReturn(List.of()); }

    @Test void missingCredentialsReturnAnExplicitGuidanceModeWithoutCallingAProvider() {
        when(model.isConfigured()).thenReturn(false);
        assertThat(service.reply(employee, question("Hello")).mode()).isEqualTo("limited");
        verify(model, never()).complete(any());
    }

    @Test void hrIdentityUsesTheVerifiedAssignmentEvenWhenAiIsDisabled() {
        when(model.isConfigured()).thenReturn(false);
        var verified = new WavyChatResponse("Your assigned reporting manager is Riley, with HR role.", "verified", null, List.of(), List.of(), true);
        when(tools.verifiedTeam(employee)).thenReturn(verified);
        assertThat(service.reply(employee, question("Who is my HR?"))).isEqualTo(verified);
        verify(model, never()).complete(any());
    }

    @Test void toolCallsUseTheServerActorAndProduceSourcesOnlyAfterSuccessfulReads() {
        when(model.complete(any())).thenReturn(
                json.readTree("{\"stop_reason\":\"tool_use\",\"content\":[{\"type\":\"tool_use\",\"id\":\"call-1\",\"name\":\"timesheet_summary\",\"input\":{}}]}"),
                json.readTree("{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"You have two submitted timesheets.\"}]}"));
        when(tools.execute(eq("timesheet_summary"), any(), eq(employee))).thenReturn(Map.of("SUBMITTED", 2));
        when(tools.action("timesheet_summary", employee)).thenReturn(new WavyChatResponse.Action("Timesheets", "/myhr/employee/timesheets"));
        var response = service.reply(employee, question("Check my timesheets"));
        assertThat(response.mode()).isEqualTo("ai");
        assertThat(response.confirmed()).isTrue();
        assertThat(response.sources()).anyMatch(source -> source.title().equals("Timesheets API"));
        verify(tools).execute(eq("timesheet_summary"), any(), eq(employee));
        var requests = ArgumentCaptor.forClass(ObjectNode.class);
        verify(model, times(2)).complete(requests.capture());
        assertThat(requests.getValue().path("system").asString()).contains("server-verified role is EMPLOYEE");
    }

    @Test void deniedToolsDoNotProduceConfirmedDataOrRestrictedNavigation() {
        when(model.complete(any())).thenReturn(
                json.readTree("{\"stop_reason\":\"tool_use\",\"content\":[{\"type\":\"tool_use\",\"id\":\"call-1\",\"name\":\"find_employees\",\"input\":{\"query\":\"Alice\"}}]}"),
                json.readTree("{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"That information is not available.\"}]}"));
        when(tools.execute(eq("find_employees"), any(), eq(employee))).thenThrow(new IllegalArgumentException("Denied"));
        var response = service.reply(employee, question("Find Alice"));
        assertThat(response.confirmed()).isFalse();
        assertThat(response.actions()).isEmpty();
        assertThat(response.sources()).noneMatch(source -> source.id().equals("find_employees"));
    }

    @Test void providerErrorsReturnGuidanceWithoutExposingTheProviderResponse() {
        when(model.complete(any())).thenThrow(new RuntimeException("secret-provider-response"));
        var response = service.reply(employee, question("Hi"));
        assertThat(response.mode()).isEqualTo("limited");
        assertThat(response.notice()).doesNotContain("secret-provider-response");
    }

    @Test void repeatedToolRequestsAreBounded() {
        when(model.complete(any())).thenReturn(json.readTree("{\"stop_reason\":\"tool_use\",\"content\":[{\"type\":\"tool_use\",\"id\":\"call-1\",\"name\":\"my_team\",\"input\":{}}]}"));
        when(tools.execute(anyString(), any(), eq(employee))).thenReturn(Map.of());
        assertThat(service.reply(employee, question("Help me" )).mode()).isEqualTo("limited");
        verify(model, times(3)).complete(any());
    }

    @Test void rateLimitStopsFurtherModelRequests() {
        when(model.isConfigured()).thenReturn(false);
        for (int i = 0; i < 12; i++) service.reply(employee, question("Hello"));
        assertThat(service.reply(employee, question("Hello")).notice()).contains("slow down");
    }
}
