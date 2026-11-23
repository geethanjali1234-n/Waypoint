package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrKnowledgeStatus;
import com.waypoint.enums.ChunkAccessLevel;
import com.waypoint.service.KnowledgeRetrievalService.RetrievalResult;
import com.waypoint.service.KnowledgeRetrievalService.RetrievalStatus;
import com.waypoint.service.KnowledgeRetrievalService.RetrievedChunk;
import com.waypoint.service.MyHrKnowledgeGrounding.GroundedAnswer;
import com.waypoint.service.MyHrKnowledgeGrounding.Grounding;
import com.waypoint.service.MyHrKnowledgeGrounding.Source;
import com.waypoint.service.MyHrKnowledgeGrounding.UnverifiedReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The citation formats finish() accepts for supplied policy excerpts. Only the S id of a source supplied with the
 * request ([S1]) is a citation, and it is returned in the existing numbered form ([1]); any other way of pointing at a
 * source - including a bare number, which may be a section number from the policy text - is removed, never converted.
 */
class MyHrKnowledgeCitationFormatTest {

    private static final String QUESTION = "How many unused vacation days can I carry forward?";
    private static final String EXCERPT = "2. Carry forward\nEmployees may carry forward up to 5 unused vacation days.";
    private static final String ANSWER = "You can carry forward up to 5 unused vacation days";

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "[S1].   | [1].   | 1",
            ". [S1]  | . [1]  | 1",
            "[S2].   | [2].   | 2",
            "[S1, S2]. | [1, 2]. | 1;2",
            "[S1,S2]. | [1, 2]. | 1;2",
            "[S1][S2]. | [1][2]. | 1;2",
            "[S2, S1, S2]. | [2, 1]. | 2;1"})
    void aSuppliedSourceIdIsACitationAndIsReturnedInTheNumberedForm(String modelMarker, String returnedMarker,
                                                                     String citedNumbers) {
        GroundedAnswer finished = MyHrKnowledgeGrounding.finish(twoSources(), ANSWER + " " + modelMarker.strip());

        assertThat(finished.status()).isEqualTo(MyHrKnowledgeStatus.GROUNDED);
        assertThat(finished.unverifiedReason()).isNull();
        assertThat(finished.message()).isEqualTo(ANSWER + " " + returnedMarker.strip());
        assertThat(finished.cited()).extracting(source -> String.valueOf(source.number()))
                .containsExactly(citedNumbers.split(";"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            " [1].",
            " [2].",
            " [1, 2].",
            " [s1].",
            " [S01].",
            " [S0].",
            " [S3].",
            " [S1 ].",
            " [ S1].",
            " [S 1].",
            " [Source 1].",
            " [source id=\"S1\"].",
            " (S1).",
            " (1).",
            " [^1].",
            " 【S1】.",
            " (source: leave.pdf).",
            " [leave].",
            " [stored-leave]."})
    void anythingElseIsNotACitationAndTheAnswerIsWithheld(String marker) {
        GroundedAnswer finished = MyHrKnowledgeGrounding.finish(twoSources(), ANSWER + marker);

        assertThat(finished.status()).as(marker).isEqualTo(MyHrKnowledgeStatus.UNVERIFIED);
        assertThat(finished.unverifiedReason()).isEqualTo(UnverifiedReason.NO_VALID_CITATION);
        assertThat(finished.cited()).isEmpty();
        assertThat(finished.message()).isEqualTo(MyHrKnowledgeGrounding.UNVERIFIED_ANSWER);
    }

    @Test
    void aTitleOrDocumentNameIsNotACitation() {
        for (String answer : List.of("According to the Employee Leave Policy, " + ANSWER + ".",
                ANSWER + " (leave.pdf).", ANSWER + " [Employee Leave Policy].")) {
            GroundedAnswer finished = MyHrKnowledgeGrounding.finish(twoSources(), answer);
            assertThat(finished.status()).as(answer).isEqualTo(MyHrKnowledgeStatus.UNVERIFIED);
            assertThat(finished.cited()).isEmpty();
        }
    }

    @Test
    void invalidMarkersAreRemovedAndOnlySuppliedIdsAreKept() {
        List<Source> sources = twoSources().sources();

        MyHrKnowledgeGrounding.CitedAnswer cited = MyHrKnowledgeGrounding.cite(
                "A [S1, 2]. B [2][S2]. C [S9] [s2] [S1, S1].", sources);

        assertThat(cited.answer()).isEqualTo("A [1]. B[2]. C [1].");
        assertThat(cited.cited()).extracting(Source::number).containsExactly(1, 2);
        // With no sources supplied (no matching policy), every marker is removed and nothing is cited.
        MyHrKnowledgeGrounding.CitedAnswer none = MyHrKnowledgeGrounding.cite("A draft [S1] [1].", List.of());
        assertThat(none.answer()).isEqualTo("A draft.");
        assertThat(none.cited()).isEmpty();
    }

    @Test
    void theModelIsAskedForSourceIdsAndSourcesAreLabelledWithThem() {
        Grounding grounding = twoSources();

        assertThat(grounding.prompt().systemPrompt()).contains("Put the source id in square brackets",
                "for example [S1]", "never cite an id that is not in the current <policy_context>");
        assertThat(grounding.prompt().userMessage()).contains("<source id=\"S1\" title=\"Employee Leave Policy\"",
                "<source id=\"S2\" title=\"Employee Handbook\"").doesNotContain("<source id=\"1\"", "<source id=\"2\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "I couldn't verify an answer to this in the approved company policies, so I won't guess. "
                    + "Please check the policy document or contact HR.",
            "I couldn't find this in the approved company policies, so I won't guess. Please check with HR.",
            "I can't see your leave balance. Please check with HR."})
    void aRefusalWithoutACitationIsWithheldAsUnverified(String answer) {
        GroundedAnswer finished = MyHrKnowledgeGrounding.finish(twoSources(), answer);

        assertThat(finished.status()).isEqualTo(MyHrKnowledgeStatus.UNVERIFIED);
        assertThat(finished.unverifiedReason()).isEqualTo(UnverifiedReason.NO_VALID_CITATION);
        assertThat(finished.cited()).isEmpty();
    }

    private static Grounding twoSources() {
        return MyHrKnowledgeGrounding.prepare(QUESTION, List.of(), new RetrievalResult(RetrievalStatus.RETRIEVED,
                List.of(chunk("leave", "Employee Leave Policy", EXCERPT),
                        chunk("handbook", "Employee Handbook", "Vacation requests need manager approval.")), 2),
                Set.of(ChunkAccessLevel.COMPANY_WIDE));
    }

    private static RetrievedChunk chunk(String document, String title, String text) {
        return new RetrievedChunk(document + "-ingestion-00000", document, title, "stored-" + document,
                document + ".pdf", document + "-ingestion", 0, ChunkAccessLevel.COMPANY_WIDE,
                LocalDateTime.of(2026, 10, 1, 9, 0), 0.9, text);
    }
}
