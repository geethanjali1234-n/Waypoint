package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Data-layer failures (MongoDB down or timing out) during a supported business question. Wavi must not crash the
 * turn with a raw exception message, must not invent a record, and must not hand the question to a model that
 * would guess. Synthetic world; no provider is called.
 */
class WaviFailureInjectionTest {

    private record Case(String question, Runnable inject) { }

    @Test
    void aFailingDataServiceGivesAnHonestAnswerNeverAnInventedOrModelOne() {
        for (RuntimeException failure : List.of(new DataAccessResourceFailureException("mongo down"),
                new QueryTimeoutException("mongo timeout"))) {
            for (String kind : List.of("profile", "latest", "requests")) {
                var options = new WaviEvaluationWorld.Options();
                options.semanticRouter = WaviEvaluationWorld.RouterBehaviour.UNAVAILABLE;
                WaviEvaluationWorld world = new WaviEvaluationWorld(WaviEvaluationWorld.EMPLOYEE, options);
                String question = switch (kind) {
                    case "profile" -> {
                        doThrow(failure).when(world.employees).getOwnDetail(anyString());
                        yield "What is my employee ID?";
                    }
                    case "latest" -> {
                        doThrow(failure).when(world.timesheets).getLatestSubmissionForUser(anyString());
                        yield "What is the status of my latest timesheet?";
                    }
                    default -> {
                        doThrow(failure).when(world.documents).listOwnHistory(anyString());
                        yield "What is the status of my document request?";
                    }
                };
                String label = failure.getClass().getSimpleName() + " / " + question;
                MyHrChatResponse response = world.service.chat(world.actor, question, List.of(), StampedHistory.SESSION);
                assertThat(response.message()).as(label).isNotBlank()
                        .doesNotContain("mongo", "WPT-1001", "Exception")
                        .isNotEqualTo("General guidance from Claude.");
                assertThat(response.historyProvenance()).as(label).isNotNull();
                verify(world.claude, never()).chat(anyString(), anyList());
            }
        }
    }
}
