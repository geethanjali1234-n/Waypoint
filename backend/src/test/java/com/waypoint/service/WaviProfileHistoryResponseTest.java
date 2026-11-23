package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrOwnProfileChangeHistory;
import com.waypoint.enums.ProfileChangeField;
import com.waypoint.enums.ProfileChangeStatus;
import com.waypoint.enums.UserRole;
import com.waypoint.security.TestActors;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class WaviProfileHistoryResponseTest {
    @Test void unavailableEffectiveDateIsNotReplacedWithApprovalDate() {
        var profiles = mock(ProfileChangeRequestService.class);
        var processed = LocalDateTime.of(2026, 3, 12, 12, 0);
        when(profiles.mostRecentOwnHistoryForField("u1", ProfileChangeField.PHONE_NUMBER))
                .thenReturn(Optional.of(new MyHrOwnProfileChangeHistory(ProfileChangeStatus.APPROVED,
                        processed.minusDays(1), processed, null)));
        var claude = mock(ClaudeChatClient.class);
        var ollama = mock(OllamaClient.class);
        var service = new MyHrChatService(claude, ollama, mock(TimesheetService.class), profiles,
                mock(EmployeeManagementService.class), mock(DocumentRequestService.class), "claude",
                mock(KnowledgeRetrievalService.class), false, false);
        var answer = service.chat(TestActors.actor("u1", "org1", UserRole.EMPLOYEE),
                "When was my phone number last updated?", List.of());
        assertThat(answer.message()).containsIgnoringCase("no confirmed effective update date");
        verifyNoInteractions(claude, ollama);
    }
}
