package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrChatHistoryMessage;
import com.waypoint.security.VerifiedMyHrActor;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds conversation history exactly as the browser returns it after earlier turns: each question and answer with
 * the stamp this server issued for it. Tests only; production stamps are issued by MyHrChatService itself.
 */
final class StampedHistory {

    /** The login session the stamps below belong to. */
    static final MyHrChatHistoryProvenance.Session SESSION = new MyHrChatHistoryProvenance.Session("a".repeat(64));

    final MyHrChatHistoryProvenance provenance;

    StampedHistory() {
        this(new MyHrChatHistoryProvenance(Duration.ofHours(3), Clock.systemUTC()));
    }

    StampedHistory(MyHrChatHistoryProvenance provenance) {
        this.provenance = provenance;
    }

    /** Alternating question and answer texts; each pair is one turn, stamped with use. */
    List<MyHrChatHistoryMessage> turns(VerifiedMyHrActor actor, MyHrChatHistoryProvenance.ExternalUse use,
                                       String... questionsAndAnswers) {
        return turns(actor, SESSION, use, questionsAndAnswers);
    }

    List<MyHrChatHistoryMessage> turns(VerifiedMyHrActor actor, MyHrChatHistoryProvenance.Session session,
                                       MyHrChatHistoryProvenance.ExternalUse use, String... questionsAndAnswers) {
        if (questionsAndAnswers.length % 2 != 0) {
            throw new IllegalArgumentException("Turns need a question and an answer");
        }
        List<MyHrChatHistoryMessage> history = new ArrayList<>();
        for (int index = 0; index < questionsAndAnswers.length; index += 2) {
            String turnId = provenance.newTurnId();
            history.add(new MyHrChatHistoryMessage("user", questionsAndAnswers[index],
                    provenance.stamp(actor, session, turnId, "user", questionsAndAnswers[index], use)));
            history.add(new MyHrChatHistoryMessage("assistant", questionsAndAnswers[index + 1],
                    provenance.stamp(actor, session, turnId, "assistant", questionsAndAnswers[index + 1], use)));
        }
        return history;
    }

    /** History after asking question in SESSION: the service's own answer, with the stamps it issued. */
    static List<MyHrChatHistoryMessage> asked(MyHrChatService service, VerifiedMyHrActor actor,
                                              List<MyHrChatHistoryMessage> before, String question) {
        com.waypoint.dto.myhr.MyHrChatResponse response = service.chat(actor, question, before, SESSION);
        var stamps = response.historyProvenance();
        List<MyHrChatHistoryMessage> history = new ArrayList<>(before);
        history.add(new MyHrChatHistoryMessage("user", question.strip(), stamps == null ? null : stamps.user()));
        history.add(new MyHrChatHistoryMessage("assistant", response.message(), stamps == null ? null : stamps.assistant()));
        return List.copyOf(history);
    }

    /** The same messages as a provider receives them: without stamps. */
    static List<MyHrChatHistoryMessage> unstamped(List<MyHrChatHistoryMessage> history) {
        return history.stream().map(message -> new MyHrChatHistoryMessage(message.role(), message.content())).toList();
    }

    /** A chat service using these stamps' provenance. */
    MyHrChatService service(ClaudeChatClient claude, OllamaClient ollama, String provider,
                            KnowledgeRetrievalService retrieval, boolean grounding, boolean externalSwitch,
                            boolean claudeOnly, ExternalPolicyProcessingAuthorization authorization) {
        return new MyHrChatService(claude, ollama, org.mockito.Mockito.mock(TimesheetService.class),
                org.mockito.Mockito.mock(ProfileChangeRequestService.class),
                org.mockito.Mockito.mock(EmployeeManagementService.class),
                org.mockito.Mockito.mock(DocumentRequestService.class), provider, retrieval, grounding, externalSwitch,
                claudeOnly, authorization, provenance);
    }
}
