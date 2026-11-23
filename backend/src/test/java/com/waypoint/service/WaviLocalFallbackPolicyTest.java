package com.waypoint.service;

import com.waypoint.enums.UserRole;
import com.waypoint.security.TestActors;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WaviLocalFallbackPolicyTest {
    private final ClaudeChatClient claude = mock(ClaudeChatClient.class);
    private final OllamaClient ollama = mock(OllamaClient.class);
    private final MyHrChatService service = new MyHrChatService(claude, ollama, mock(TimesheetService.class),
            mock(ProfileChangeRequestService.class), mock(EmployeeManagementService.class),
            mock(DocumentRequestService.class), "claude", mock(KnowledgeRetrievalService.class), false, false);
    private final com.waypoint.security.VerifiedMyHrActor actor = TestActors.actor("u1", "org1", UserRole.EMPLOYEE);

    @Test void springWiringRequiresClaudeAsPrimary() {
        var configuredOllama = new MyHrChatService(claude, ollama, mock(TimesheetService.class),
                mock(ProfileChangeRequestService.class), mock(EmployeeManagementService.class),
                mock(DocumentRequestService.class), "ollama", mock(KnowledgeRetrievalService.class), false, false);
        assertThatThrownBy(configuredOllama::requireClaudePrimary).isInstanceOf(IllegalStateException.class);
        assertThatCode(service::requireClaudePrimary).doesNotThrowAnyException();
    }

    @Test void fallbackNeverReachesAModelThatIsNotVerifiedLocal() {
        when(claude.chat(anyString(), anyList())).thenThrow(new ClaudeChatClient.ClaudeUnavailableException());
        when(ollama.verifiedLocalModel()).thenReturn(false);
        assertThatThrownBy(() -> service.chat(actor, "Write a polite email to my team", List.of()))
                .isInstanceOf(ClaudeChatClient.ClaudeUnavailableException.class);
        verify(ollama, never()).chat(anyString(), anyList());
    }

    @Test void fallbackCanBeSwitchedOff() {
        ReflectionTestUtils.setField(service, "localFallbackEnabled", false);
        when(claude.chat(anyString(), anyList())).thenThrow(new ClaudeChatClient.ClaudeUnavailableException());
        assertThatThrownBy(() -> service.chat(actor, "Write a polite email to my team", List.of()))
                .isInstanceOf(ClaudeChatClient.ClaudeUnavailableException.class);
        verifyNoInteractions(ollama);
    }

    @Test void permittedOutageUsesOnlyVerifiedLocalModel() {
        ReflectionTestUtils.setField(service, "localFallbackEnabled", true);
        when(claude.chat(anyString(), anyList())).thenThrow(new ClaudeChatClient.ClaudeUnavailableException());
        when(ollama.verifiedLocalModel()).thenReturn(true);
        when(ollama.chat(anyString(), anyList())).thenReturn("A local reply.");
        assertThat(service.chat(actor, "Write a polite email to my team", List.of()).message()).isEqualTo("A local reply.");
    }

    @Test void spendingDenialNeverInvokesFallback() {
        ReflectionTestUtils.setField(service, "localFallbackEnabled", true);
        when(claude.chat(anyString(), anyList())).thenThrow(new ClaudeChatClient.ClaudeSpendLimitException());
        assertThatThrownBy(() -> service.chat(actor, "Write a polite email to my team", List.of()))
                .isInstanceOf(ClaudeChatClient.ClaudeSpendLimitException.class);
        verifyNoInteractions(ollama);
    }
}
