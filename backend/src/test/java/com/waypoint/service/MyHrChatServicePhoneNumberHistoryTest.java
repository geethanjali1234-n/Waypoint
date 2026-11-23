package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrChatResponse;
import com.waypoint.dto.myhr.MyHrOwnProfileChangeHistory;
import com.waypoint.enums.ProfileChangeField;
import com.waypoint.enums.ProfileChangeStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MyHrChatServicePhoneNumberHistoryTest {

    @Test
    void answersPhoneApprovalAndDatesFromTheAuthenticatedUsersOwnHistory() {
        ClaudeChatClient claude = mock(ClaudeChatClient.class);
        OllamaClient ollama = mock(OllamaClient.class);
        ProfileChangeRequestService changes = mock(ProfileChangeRequestService.class);
        MyHrOwnProfileChangeHistory approved = new MyHrOwnProfileChangeHistory(
                ProfileChangeStatus.APPROVED,
                LocalDateTime.of(2026, 10, 1, 9, 30),
                LocalDateTime.of(2026, 10, 2, 15, 45),
                LocalDateTime.of(2026, 10, 2, 15, 42));
        when(changes.mostRecentOwnHistoryForField("signed-in-user", ProfileChangeField.PHONE_NUMBER))
                .thenReturn(Optional.of(approved));
        MyHrChatService service = service(claude, ollama, changes);

        assertThat(answer(service, "Did my phone number change get approved?"))
                .isEqualTo("Your phone number change was approved on 2026-10-02.");
        assertThat(answer(service, "When did I request a phone number change?"))
                .isEqualTo("You requested a phone number change on 2026-10-01.");
        assertThat(answer(service, "When was my phone number change approved?"))
                .isEqualTo("Your phone number change was approved on 2026-10-02.");
        assertThat(answer(service, "When did I last update my phone number?"))
                .isEqualTo("Your phone number was last updated on 2026-10-02.");

        verify(changes, times(4)).mostRecentOwnHistoryForField("signed-in-user", ProfileChangeField.PHONE_NUMBER);
        verifyNoInteractions(claude, ollama);
    }

    @Test
    void distinguishesPendingAndRejectedPhoneNumberChangesWithoutCallingAi() {
        ProfileChangeRequestService changes = mock(ProfileChangeRequestService.class);
        when(changes.mostRecentOwnHistoryForField("signed-in-user", ProfileChangeField.PHONE_NUMBER))
                .thenReturn(Optional.of(new MyHrOwnProfileChangeHistory(
                        ProfileChangeStatus.PENDING, LocalDateTime.of(2026, 10, 3, 11, 0), null, null)));
        MyHrChatService pendingService = service(mock(ClaudeChatClient.class), mock(OllamaClient.class), changes);

        assertThat(answer(pendingService, "Is my phone number update pending?"))
                .isEqualTo("Your phone number update is pending, requested on 2026-10-03.");
        assertThat(answer(pendingService, "When was my phone number change approved?"))
                .isEqualTo("Your phone number update is still pending, so it has not been approved.");
        assertThat(answer(pendingService, "When did I last update my phone number?"))
                .isEqualTo("Your phone number has not been updated from this request because it is pending.");

        ProfileChangeRequestService rejectedChanges = mock(ProfileChangeRequestService.class);
        when(rejectedChanges.mostRecentOwnHistoryForField("signed-in-user", ProfileChangeField.PHONE_NUMBER))
                .thenReturn(Optional.of(new MyHrOwnProfileChangeHistory(
                        ProfileChangeStatus.REJECTED, LocalDateTime.of(2026, 10, 4, 10, 0),
                        LocalDateTime.of(2026, 10, 5, 8, 0), null)));
        MyHrChatService rejectedService = service(mock(ClaudeChatClient.class), mock(OllamaClient.class), rejectedChanges);

        assertThat(answer(rejectedService, "Was my phone number change rejected?"))
                .isEqualTo("Your phone number change was rejected on 2026-10-05.");
    }

    @Test
    void doesNotInventAnEffectiveDateForLegacyApprovedRequestsOrMissingHistory() {
        ProfileChangeRequestService legacyChanges = mock(ProfileChangeRequestService.class);
        when(legacyChanges.mostRecentOwnHistoryForField("signed-in-user", ProfileChangeField.PHONE_NUMBER))
                .thenReturn(Optional.of(new MyHrOwnProfileChangeHistory(
                        ProfileChangeStatus.APPROVED, LocalDateTime.of(2026, 9, 1, 9, 0),
                        LocalDateTime.of(2026, 9, 2, 9, 0), null)));
        MyHrChatService legacyService = service(mock(ClaudeChatClient.class), mock(OllamaClient.class), legacyChanges);

        assertThat(answer(legacyService, "When did I last update my phone number?"))
                .isEqualTo("Your phone number change was approved, but no confirmed effective update date was recorded.");

        ProfileChangeRequestService missingChanges = mock(ProfileChangeRequestService.class);
        when(missingChanges.mostRecentOwnHistoryForField("signed-in-user", ProfileChangeField.PHONE_NUMBER))
                .thenReturn(Optional.empty());
        MyHrChatService missingService = service(mock(ClaudeChatClient.class), mock(OllamaClient.class), missingChanges);

        assertThat(answer(missingService, "Did my phone number change get approved?"))
                .isEqualTo("I couldn't find a phone number change request of yours in Waypoint.");
    }

    private MyHrChatService service(ClaudeChatClient claude, OllamaClient ollama,
                                    ProfileChangeRequestService changes) {
        return new MyHrChatService(claude, ollama, mock(TimesheetService.class), changes,
                mock(EmployeeManagementService.class), "claude");
    }

    private String answer(MyHrChatService service, String question) {
        MyHrChatResponse response = service.chat(question, List.of(), "signed-in-user");
        return response.message();
    }
}
