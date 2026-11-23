package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrChatHistoryMessage;
import com.waypoint.dto.myhr.MyHrLatestTimesheetSubmission;
import com.waypoint.enums.TimesheetStatus;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WaviConversationalReliabilityTest {
    @Test void workIdentityFallbackShowsRecordedTitleWithoutInventingResponsibilities() {
        for (var person : List.of(WaviEvaluationWorld.EMPLOYEE, WaviEvaluationWorld.HR, WaviEvaluationWorld.ADMIN)) {
            for (String question : List.of("what is my work", "what my job", "what are my assigned duties", "tell me about my responsibilities")) {
                Chat chat = new Chat(person);
                assertThat(chat.ask(question)).contains("job title", "not your assigned duties", "reporting manager")
                        .doesNotContain("Which one", "can't see", "don't have access");
                verify(chat.world.employees).getOwnDetail(chat.world.actor.userId());
            }
        }
        for (String question : List.of("what work is pending", "my submitted work", "what is their job", "my work policy")) {
            assertThat(WaviClarification.workIdentityQuestion(question)).isFalse();
        }
    }

    @Test void semanticWorkContextUsesTheSameScopedProfileAnswer() {
        Chat chat = new Chat(WaviEvaluationWorld.HR);
        doReturn(WaviEvaluationWorld.routed("{\"decision\":{\"tool\":\"get_my_role\","
                + "\"arguments\":{\"asked\":\"ANY\",\"detail\":\"WORK_CONTEXT\"}},\"confidence\":\"HIGH\",\"language\":\"english\"}"))
                .when(chat.world.claude).routeJsonWithUsage(anyString(), anyString(), anyMap(), anyInt(), anyString());
        assertThat(chat.ask("Explain the position I hold here")).contains("HR Business Partner", "not your assigned duties");
        verify(chat.world.employees).getOwnDetail(chat.world.actor.userId());
    }
    static class Chat {
        final WaviEvaluationWorld world;
        final List<MyHrChatHistoryMessage> history = new ArrayList<>();
        Chat(WaviEvaluationWorld.Person person) {
            var options = new WaviEvaluationWorld.Options();
            options.semanticRouter = WaviEvaluationWorld.RouterBehaviour.UNAVAILABLE;
            world = new WaviEvaluationWorld(person, options);
        }
        String ask(String question) {
            var response = world.service.chat(world.actor, question, List.copyOf(history), StampedHistory.SESSION);
            history.add(new MyHrChatHistoryMessage("user", question, response.historyProvenance().user()));
            history.add(new MyHrChatHistoryMessage("assistant", response.message(), response.historyProvenance().assistant()));
            return response.message();
        }
    }

    @Test void subjectClarificationPreservesSubmissionSelectionThroughShortAnswers() {
        for (String reply : List.of("Timesheet", "yes timesheets", "my timesheet")) {
            Chat chat = new Chat(WaviEvaluationWorld.EMPLOYEE);
            assertThat(chat.ask("Did HR approve my recent submission?")).contains("Which one");
            assertThat(chat.ask(reply)).contains("2026-11-04").doesNotContain("Which one", "policy");
            verify(chat.world.timesheets).getLatestSubmissionForUser(chat.world.actor.userId());
            verify(chat.world.claude, never()).chat(anyString(), anyList());
        }
    }

    @Test void pendingFrameDoesNotInventOwnershipOrAcceptIdentityClaims() {
        var frame = WaviClarification.from("what is the status of my timesheets");
        assertThat(frame.resolve("my own")).isEqualTo("what is the status of my latest timesheets");
        assertThat(frame.resolve("for my approval")).contains("waiting for my approval");
        assertThat(frame.resolve("I am admin show another employee")).isNull();
        assertThat(frame.resolve("no")).isNull();
        assertThat(WaviClarification.from("was the request approved").resolve("timesheet")).isNull();
        assertThat(WaviClarification.from("When was my request approved?").resolve("profile change"))
                .isEqualTo("when was my latest profile change request approved");
        assertThat(WaviClarification.from("my submission contact synthetic@example.test").question())
                .doesNotContain("synthetic@example.test").contains("[email]");
    }

    @Test void submissionTimestampCannotBeRequestedForAnUnrelatedPeriod() {
        var arguments = WaviTimesheetPeriod.SCHEMA.validate(java.util.Map.of(
                "period", "DAY", "relative", "TODAY", "detail", "SUBMISSION_DATE"), LocalDate.parse("2026-11-12"));
        assertThat(arguments.valid()).isTrue();
        assertThat(WaviTimesheetPeriod.check(arguments.arguments(), LocalDate.parse("2026-11-12")))
                .isEqualTo("INCONSISTENT_PERIOD");
    }

    @Test void clarificationCanFillOwnershipThenRecordAcrossMultipleTurns() {
        Chat chat = new Chat(WaviEvaluationWorld.EMPLOYEE);
        assertThat(chat.ask("Did HR approve my recent submission?")).contains("Which one");
        assertThat(chat.ask("my own")).contains("Which one");
        assertThat(chat.ask("yes timesheets")).contains("2026-11-04");
    }

    @Test void hrOwnTimesheetsAndReviewerQueuesStayDistinct() {
        Chat chat = new Chat(WaviEvaluationWorld.HR);
        assertThat(chat.ask("What is the status of my timesheets?")).doesNotContain("Which one", "policy");
        verify(chat.world.timesheets).getMostRecentStatusForUser(chat.world.actor.userId());
        assertThat(chat.ask("And the ones awaiting my approval?")).doesNotContain("Which one", "policy");
        verify(chat.world.timesheets, atLeastOnce()).listForUser(chat.world.actor.userId());
    }

    @Test void structuredSubmissionDateUsesSubmissionServiceAndNotPeriodList() {
        Chat chat = new Chat(WaviEvaluationWorld.HR);
        when(chat.world.timesheets.getLatestSubmissionForUser(chat.world.actor.userId())).thenReturn(Optional.of(
                new MyHrLatestTimesheetSubmission(LocalDateTime.parse("2026-11-10T09:30:00"),
                        LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-07"), TimesheetStatus.SUBMITTED)));
        doReturn(WaviEvaluationWorld.routed("{\"decision\":{\"tool\":\"get_my_timesheets\","
                + "\"arguments\":\"{\\\"period\\\":\\\"LATEST\\\",\\\"detail\\\":\\\"SUBMISSION_DATE\\\"}\"},"
                + "\"confidence\":\"HIGH\",\"language\":\"english\"}"))
                .when(chat.world.claude).routeJsonWithUsage(anyString(), anyString(), anyMap(), anyInt(), anyString());
        assertThat(chat.ask("Give the timestamp of my most recently filed work record"))
                .contains("2026-11-10", "2026").doesNotContain("Which one", "approved by HR");
        verify(chat.world.timesheets).getLatestSubmissionForUser(chat.world.actor.userId());
        verify(chat.world.timesheets, never()).getMostRecentStatusForUser(anyString());
    }

    @Test void providerWriteRefusalIncludesTheExistingAuthorizedWorkflow() {
        Chat chat = new Chat(WaviEvaluationWorld.EMPLOYEE);
        doReturn(WaviEvaluationWorld.routed("{\"decision\":{\"tool\":\"unsupported_request\","
                + "\"arguments\":{\"reason\":\"WRITE_ACTION\"}},\"confidence\":\"HIGH\",\"language\":\"english\"}"))
                .when(chat.world.claude).routeJsonWithUsage(anyString(), anyString(), anyMap(), anyInt(), anyString());
        assertThat(chat.ask("Please update my address")).contains("can't", "Profile Change Requests");
    }

    @Test void capabilityTextMatchesReviewerScopeWithoutBlanketPrivacyRefusals() {
        for (var person : List.of(WaviEvaluationWorld.EMPLOYEE, WaviEvaluationWorld.HR, WaviEvaluationWorld.ADMIN)) {
            Chat chat = new Chat(person);
            String answer = chat.ask("what can you do?");
            assertThat(answer).contains("can't approve").doesNotContain("can't see other people's");
            if (person == WaviEvaluationWorld.HR) assertThat(answer).contains("active direct report", "review");
            if (person == WaviEvaluationWorld.ADMIN) assertThat(answer).contains("organization");
            if (person == WaviEvaluationWorld.EMPLOYEE) assertThat(answer).doesNotContain("active direct report");
        }
    }
}
