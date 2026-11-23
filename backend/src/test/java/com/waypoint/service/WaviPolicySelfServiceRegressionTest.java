package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrChatHistoryMessage;
import com.waypoint.dto.myhr.MyHrChatResponse;
import com.waypoint.dto.myhr.MyHrKnowledgeStatus;
import com.waypoint.enums.UserRole;
import com.waypoint.enums.ChunkAccessLevel;
import com.waypoint.security.TestActors;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises complete conversations using independent synthetic records and mocked business and provider services. */
class WaviPolicySelfServiceRegressionTest {
    private static final class Conversation {
        final WaviEvaluationWorld world;
        final List<MyHrChatHistoryMessage> history = new ArrayList<>();

        Conversation(WaviEvaluationWorld.Person person) {
            var options = new WaviEvaluationWorld.Options();
            options.semanticRouter = WaviEvaluationWorld.RouterBehaviour.UNAVAILABLE;
            options.toolLayer = true;
            world = new WaviEvaluationWorld(person, options);
        }

        MyHrChatResponse ask(String question) {
            MyHrChatResponse response = world.service.chat(world.actor, question, List.copyOf(history), StampedHistory.SESSION);
            history.add(new MyHrChatHistoryMessage("user", question, response.historyProvenance().user()));
            history.add(new MyHrChatHistoryMessage("assistant", response.message(), response.historyProvenance().assistant()));
            return response;
        }
    }

    @Test void broadSelfServiceAndQuantifierFollowUpsUseOnlyTheVerifiedUsersProfile() {
        for (var person : WaviEvaluationWorld.PEOPLE) {
            for (String question : List.of("What information does Waypoint have about me?",
                    "Show the details stored in my employee record", "List the profile data you keep for me")) {
                Conversation chat = new Conversation(person);
                MyHrChatResponse first = chat.ask(question);
                assertThat(first.message()).contains(person.name(), person.employeeId(), person.jobTitle(),
                        "profile details I can show").doesNotContain("Which one", "clarify");
                MyHrChatResponse followUp = chat.ask("all the details please");
                assertThat(followUp.message()).contains(person.name(), person.employeeId()).doesNotContain("Which one");
                assertThat(chat.ask("everything").message()).contains(person.name()).doesNotContain("Which one");
                assertThat(chat.ask("all").message()).contains(person.name()).doesNotContain("Which one");
                verify(chat.world.employees, atLeastOnce()).getOwnDetail(person.userId());
                verify(chat.world.employees, never()).getOwnDetail(WaviEvaluationWorld.COLLEAGUE.userId());
            }
        }
        assertThat(WaviClarification.asksForOwnProfileOverview("Show my colleague's information" )).isFalse();
        assertThat(WaviClarification.asksForOwnProfileOverview("Show me the profile of employee WPT-1001")).isFalse();
        assertThat(WaviClarification.asksForOwnProfileOverview("Give the timestamp of my most recently filed work record"))
                .isFalse();
        assertThat(WaviClarification.asksForOwnProfileOverview("Show the status of my document requests" )).isFalse();
    }

    @Test void aReplacedPolicyVersionIsNotMisrepresentedAsTheEarlierCitation() {
        Conversation chat = new Conversation(WaviEvaluationWorld.EMPLOYEE);
        assertThat(chat.ask("What does the annual leave policy say?").message()).contains("1.5 days");
        var replacement = new KnowledgeRetrievalService.RetrievedChunk("replacement-0", WaviEvaluationWorld.LEAVE.id(),
                WaviEvaluationWorld.LEAVE.title(), "source-replacement", "replacement.pdf", "new-ingestion", 0,
                ChunkAccessLevel.COMPANY_WIDE, LocalDateTime.of(2026, 11, 6, 9, 0), 0.9,
                "Full-time employees accrue 2.5 days of annual leave per month.", WaviEvaluationWorld.ORG);
        doReturn(new KnowledgeRetrievalService.RetrievalResult(KnowledgeRetrievalService.RetrievalStatus.RETRIEVED,
                List.of(replacement), 1)).when(chat.world.retrieval).retrieve(eq(chat.world.actor), anyString());

        MyHrChatResponse cited = chat.ask("Explain the previous source");
        assertThat(cited.message()).contains("changed since my earlier answer").doesNotContain("1.5", "2.5");
        assertThat(cited.citations()).isEmpty();
        assertThat(chat.ask("What does the annual leave policy say?").message()).contains("2.5 days").doesNotContain("1.5");
    }

    @Test void aCitationSurvivesThanksButAnExplicitNewPolicySubjectTakesPrecedence() {
        Conversation chat = new Conversation(WaviEvaluationWorld.EMPLOYEE);
        assertThat(chat.ask("What does the annual leave policy say?").message()).contains("1.5 days");
        assertThat(chat.ask("thanks").message()).contains("welcome");
        MyHrChatResponse source = chat.ask("What does the cited policy say?");
        assertThat(source.message()).contains("1.5 days", "[1]");
        assertThat(source.citations()).singleElement()
                .satisfies(c -> assertThat(c.title()).isEqualTo(WaviEvaluationWorld.LEAVE.title()));

        MyHrChatResponse switched = chat.ask("What source supports the remote work policy?");
        assertThat(switched.message()).contains("remotely", "2 days").doesNotContain("annual leave");
        assertThat(switched.citations()).singleElement()
                .satisfies(c -> assertThat(c.title()).isEqualTo(WaviEvaluationWorld.REMOTE.title()));
    }

    @Test void disabledKnowledgeNeverFallsThroughToAnUngroundedPolicyModel() {
        Conversation chat = new Conversation(WaviEvaluationWorld.EMPLOYEE);
        MyHrChatService disabled = new MyHrChatService(chat.world.claude, chat.world.ollama, chat.world.timesheets,
                chat.world.profileChanges, chat.world.employees, chat.world.documents, "claude", chat.world.retrieval,
                false, false);
        MyHrChatResponse response = disabled.chat(chat.world.actor, "What is the vacation policy?", List.of());
        assertThat(response.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.KNOWLEDGE_UNAVAILABLE);
        assertThat(response.message()).contains("can't check");
        verify(chat.world.claude, never()).chat(anyString(), anyList());
        verify(chat.world.retrieval, never()).retrieve(any(), anyString());
    }

    @Test void unknownApplicationAccessDoesNotBecomeAnInventedFeature() {
        Conversation chat = new Conversation(WaviEvaluationWorld.EMPLOYEE);
        MyHrChatService disabled = new MyHrChatService(chat.world.claude, chat.world.ollama, chat.world.timesheets,
                chat.world.profileChanges, chat.world.employees, chat.world.documents, "claude", chat.world.retrieval,
                false, false);
        MyHrChatResponse response = disabled.chat(chat.world.actor, "Can I see my pay stub in Waypoint?", List.of());
        assertThat(response.message()).contains("can't verify", "available in Waypoint");
        assertThat(response.actions()).isEmpty();
        verify(chat.world.claude, never()).chat(anyString(), anyList());
    }

    @Test void conflictingGeneratedStatementsBecomeTheSameCurrentApprovedEvidence() {
        Conversation chat = new Conversation(WaviEvaluationWorld.EMPLOYEE);
        when(chat.world.ollama.chat(any(MyHrGroundedPrompt.class))).thenReturn(
                "Employees do not earn annual leave. [S1]", "Employees earn annual leave. [S1]");

        MyHrChatResponse first = chat.ask("What does the annual leave policy say?");
        MyHrChatResponse second = chat.ask("What does the annual leave policy say?");

        assertThat(first.knowledgeStatus()).isEqualTo(MyHrKnowledgeStatus.GROUNDED);
        assertThat(second.message()).isEqualTo(first.message());
        assertThat(first.message()).contains("Full-time employees accrue 1.5 days of annual leave per month",
                "18 days per year", "[1]").doesNotContain("do not earn");
        assertThat(first.citations()).singleElement().satisfies(c -> assertThat(c.title()).isEqualTo(WaviEvaluationWorld.LEAVE.title()));
        assertThat(first.actions()).isNull(); // The UI suppresses heuristic shortcuts for policy responses.
    }

    @Test void aSourceFollowUpRetrievesCurrentAuthorizedEvidenceInsteadOfTrustingTheEarlierAnswer() {
        Conversation chat = new Conversation(WaviEvaluationWorld.EMPLOYEE);
        MyHrChatResponse first = chat.ask("How many unused leave days may be carried forward?");
        assertThat(first.message()).contains("5 unused", "[1]");
        MyHrChatResponse next = chat.ask("Please explain source 1");
        assertThat(next.message()).contains("5 unused", "[1]").doesNotContain("Which one");
        assertThat(next.citations()).singleElement().satisfies(c -> assertThat(c.title()).isEqualTo(WaviEvaluationWorld.LEAVE.title()));
        verify(chat.world.retrieval, times(2)).retrieve(eq(chat.world.actor), anyString());
        verify(chat.world.ollama, times(1)).chat(any(MyHrGroundedPrompt.class));

        doReturn(new KnowledgeRetrievalService.RetrievalResult(
                KnowledgeRetrievalService.RetrievalStatus.NO_RELEVANT_KNOWLEDGE, List.of(), 0))
                .when(chat.world.retrieval).retrieve(eq(chat.world.actor), anyString());
        MyHrChatResponse revoked = chat.ask("What does that citation say?");
        assertThat(revoked.message()).contains("currently available").doesNotContain("5 unused");
        assertThat(revoked.citations()).isEmpty();
    }

    @Test void citedHistoryCannotBeReusedByAnotherLoginUserRoleOrOrganization() {
        Conversation chat = new Conversation(WaviEvaluationWorld.EMPLOYEE);
        chat.ask("What does the annual leave policy say?");
        var otherSession = new MyHrChatHistoryProvenance.Session("different-session");
        var otherUser = TestActors.actor("someone-else", WaviEvaluationWorld.ORG, UserRole.EMPLOYEE);
        var otherOrg = TestActors.actor(chat.world.actor.userId(), WaviEvaluationWorld.OTHER_ORG, UserRole.EMPLOYEE);
        var otherRole = TestActors.actor(chat.world.actor.userId(), WaviEvaluationWorld.ORG, UserRole.HR);
        for (var actor : List.of(otherUser, otherOrg, otherRole)) {
            var response = chat.world.service.chat(actor, "Explain source 1", chat.history, StampedHistory.SESSION);
            assertThat(response.message()).contains("don't have a verified source").doesNotContain("1.5", "18 days");
            assertThat(response.citations()).isEmpty();
        }
        var response = chat.world.service.chat(chat.world.actor, "Explain source 1", chat.history, otherSession);
        assertThat(response.message()).contains("don't have a verified source").doesNotContain("1.5", "18 days");
        verify(chat.world.retrieval, times(1)).retrieve(any(), anyString());
    }

    @Test void policyTextDoesNotEstablishAnUnsupportedApplicationWorkflow() {
        Conversation chat = new Conversation(WaviEvaluationWorld.EMPLOYEE);
        when(chat.world.ollama.chat(any(MyHrGroundedPrompt.class)))
                .thenReturn("Open Leave Requests in Waypoint and submit it there. [S1]");
        MyHrChatResponse response = chat.ask("How can I submit a leave request in Waypoint?");
        assertThat(response.message()).contains("can't verify", "workflow", "[1]")
                .doesNotContain("Open Leave Requests", "click", "submit it there");
        assertThat(response.actions()).isNull();
    }
}
