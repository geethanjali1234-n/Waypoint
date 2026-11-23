package com.waypoint.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WaviQuestionUnderstandingTest {

    @Test
    void reviewerCountsAndRoleDependentWorkRetainTheirActualMeaning() {
        var count = WaviQuestionUnderstanding.from(WaviTools.GET_REVIEW_QUEUE, Map.of("queue", "TIMESHEETS"), null);
        assertThat(count.operation()).isEqualTo(WaviQuestionUnderstanding.Operation.COUNT);
        var list = WaviQuestionUnderstanding.from(WaviTools.GET_REVIEW_QUEUE,
                Map.of("queue", "TIMESHEETS", "detail", "LIST"), null);
        assertThat(list.operation()).isEqualTo(WaviQuestionUnderstanding.Operation.LIST);
        var work = WaviQuestionUnderstanding.from(WaviTools.GET_MY_PENDING_WORK, Map.of(), null);
        assertThat(work.routerContext(com.waypoint.enums.UserRole.EMPLOYEE)).containsEntry("subject", "VERIFIED_USER");
        for (var role : java.util.List.of(com.waypoint.enums.UserRole.HR, com.waypoint.enums.UserRole.COMPANY_ADMIN)) {
            assertThat(work.routerContext(role)).containsEntry("subject", "AUTHORIZED_SCOPE");
        }
    }

    @Test
    void clarificationAndRequestSelectionSurviveSemanticContextWithoutPrivateReferences() {
        var clarification = WaviQuestionUnderstanding.from(WaviTools.CLARIFY,
                Map.of("topic", "TIMESHEETS", "missing", "PERIOD"), null);
        assertThat(clarification.domain()).isEqualTo(WaviQuestionUnderstanding.Domain.TIMESHEET);
        assertThat(clarification.needsClarification()).isTrue();
        assertThat(clarification.filters()).containsEntry("missing", "PERIOD");
        var request = WaviQuestionUnderstanding.from(WaviTools.GET_MY_REQUESTS,
                Map.of("kind", "DOCUMENT_REQUEST", "aspect", "REQUESTED_DATE", "documentType", "OFFER_LETTER",
                        "personReference", "private-identity"), null);
        assertThat(request.filters()).containsEntry("aspect", "REQUESTED_DATE")
                .containsEntry("documentType", "OFFER_LETTER");
        assertThat(request.routerContext().toString()).doesNotContain("private-identity");
    }

    @Test
    void itDerivesRangeHoursFromTheValidatedToolContract() {
        WaviQuestionUnderstanding understanding = WaviQuestionUnderstanding.from(WaviTools.GET_MY_TIMESHEETS,
                Map.of("period", "RANGE", "range", "2026-09-07/2026-09-09", "detail", "HOURS"),
                LocalDate.parse("2026-11-05"));

        assertThat(understanding.domain()).isEqualTo(WaviQuestionUnderstanding.Domain.TIMESHEET);
        assertThat(understanding.operation()).isEqualTo(WaviQuestionUnderstanding.Operation.CALCULATE);
        assertThat(understanding.subject()).isEqualTo(WaviQuestionUnderstanding.Subject.VERIFIED_USER);
        assertThat(understanding.period()).isEqualTo(WaviQuestionUnderstanding.Period.RANGE);
        assertThat(understanding.metric()).isEqualTo(WaviQuestionUnderstanding.Metric.HOURS);
        assertThat(understanding.routerContext()).containsEntry("domain", "TIMESHEET")
                .containsEntry("period", "RANGE");
    }

    @Test
    void itKeepsOnlySafeFiltersAndDistinguishesProfileChangesFromDocumentRequests() {
        WaviQuestionUnderstanding profile = WaviQuestionUnderstanding.from(WaviTools.LIST_MY_REQUESTS,
                Map.of("kind", "PROFILE_CHANGE", "status", "PENDING", "personReference", "other-user-id"), null);
        WaviQuestionUnderstanding document = WaviQuestionUnderstanding.from(WaviTools.GET_MY_REQUESTS,
                Map.of("kind", "DOCUMENT", "ordinal", 1, "personReference", "other-user-id"), null);

        assertThat(profile.domain()).isEqualTo(WaviQuestionUnderstanding.Domain.PROFILE);
        assertThat(document.domain()).isEqualTo(WaviQuestionUnderstanding.Domain.DOCUMENT_REQUEST);
        assertThat(profile.routerContext().toString()).doesNotContain("other-user-id", "personReference");
        assertThat(document.routerContext().toString()).doesNotContain("other-user-id", "personReference");
    }

    @Test
    void dialogueStateDerivesSemanticsWithoutTreatingThemAsExecutionAuthority() {
        WaviDialogueStore.State state = new WaviDialogueStore.State("turn", WaviTools.GET_MY_TIMESHEETS,
                Map.of("period", "DAY", "date", "2026-10-23", "detail", "DAILY_HOURS"),
                Instant.parse("2026-11-05T14:00:00Z"), null, LocalDate.parse("2026-11-05"));

        assertThat(state.understanding().metric()).isEqualTo(WaviQuestionUnderstanding.Metric.DAILY_HOURS);
        assertThat(state.understanding().period()).isEqualTo(WaviQuestionUnderstanding.Period.DAY);
        assertThat(state.arguments()).containsOnlyKeys("period", "date", "detail");
    }
}
