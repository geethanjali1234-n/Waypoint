package com.waypoint.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static com.waypoint.service.WaviQuestionFrame.AnswerType;
import static com.waypoint.service.WaviQuestionFrame.Concept;
import static com.waypoint.service.WaviQuestionFrame.NamePart;
import static com.waypoint.service.WaviQuestionFrame.Scope;
import static com.waypoint.service.WaviQuestionFrame.Selection;
import static org.assertj.core.api.Assertions.assertThat;

/** The question frame is grammar plus one lexicon: these check the grammar, not any one sentence's answer. */
class WaviQuestionFrameTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "Did I submit my Nov 10 timesheet?|YES_NO",
            "who is waiting for my approvals?|PERSON",
            "whose requests are pending?|PERSON",
            "How many timesheets need my approval?|COUNT",
            "How many employees have timesheets awaiting review?|PEOPLE_COUNT",
            "how many people on my team still owe a timesheet|PEOPLE_COUNT",
            "what's the status of my address change|STATUS",
            "which one was approved?|WHICH",
            "why?|REASON",
            "how come it's still pending|REASON",
            "Why can't I upload my timesheet PDF?|INSTRUCTION",
            "show my team's pending timesheets|LIST",
            "where can I see it?|LOCATION",
            "where did you find that?|SOURCE",
            "source?|SOURCE",
            "how do I upload a policy|INSTRUCTION",
            "all of them|EXPAND",
            "everything please|EXPAND"})
    void theAnswerTypeComesFromTheGrammar(String message, AnswerType expected) {
        assertThat(WaviQuestionFrame.of(message).answer()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "which was submitted most recently?|LATEST_SUBMITTED",
            "the newest one|LATEST",
            "which ones were rejected|REJECTED",
            "which one is still open?|OPEN",
            "open the timesheets page|NONE",
            "which team timesheets still need something done|NONE",
            "anything awaiting my review|PENDING"})
    void aSelectionOfAResultSetIsReadSeparatelyFromTheAnswerType(String message, Selection expected) {
        assertThat(WaviQuestionFrame.of(message).selection()).isEqualTo(expected);
    }

    @Test
    void scopeSeparatesTheUsersOwnRecordsFromTheirReviewWorkAndOrganization() {
        assertThat(WaviQuestionFrame.of("show my team's pending timesheets").scope()).isEqualTo(Scope.TEAM);
        assertThat(WaviQuestionFrame.of("who is waiting for my approvals").scope()).isEqualTo(Scope.TEAM);
        assertThat(WaviQuestionFrame.of("what's pending across the org?").scope()).isEqualTo(Scope.ORGANIZATION);
        assertThat(WaviQuestionFrame.of("is my timesheet approved").scope()).isEqualTo(Scope.SELF);
        // "take me to" is navigation, not review work.
        assertThat(WaviQuestionFrame.of("take me to timesheets").scope()).isEqualTo(Scope.SELF);
        // "what do you have on me" is the user's own record, not work waiting on them.
        assertThat(WaviQuestionFrame.of("what do you have on me").scope()).isEqualTo(Scope.SELF);
    }

    @Test
    void aFollowUpIsAShortMessageThatNamesNoRecordOfItsOwn() {
        assertThat(WaviQuestionFrame.of("why?").contextual()).isTrue();
        assertThat(WaviQuestionFrame.of("which was submitted most recently?").contextual()).isTrue();
        assertThat(WaviQuestionFrame.of("who submitted them?").contextual()).isTrue();
        assertThat(WaviQuestionFrame.of("how many hours is it?").contextual()).isTrue();
        assertThat(WaviQuestionFrame.of("which timesheets were approved?").contextual()).isFalse();
        assertThat(WaviQuestionFrame.of("what is the annual leave policy for part-time staff in their first year")
                .contextual()).isFalse();
    }

    @Test
    void conceptsAndNamePartsComeFromTheLexicon() {
        WaviQuestionFrame manager = WaviQuestionFrame.of("What's my manager's last name?");
        assertThat(manager.has(Concept.MANAGER)).isTrue();
        assertThat(manager.namePart()).isEqualTo(NamePart.LAST);
        assertThat(WaviQuestionFrame.of("what's my supervisor's surname").namePart()).isEqualTo(NamePart.LAST);
        assertThat(WaviQuestionFrame.of("who do I report to").has(Concept.MANAGER)).isTrue();
        assertThat(WaviQuestionFrame.of("What does Waypoint know about me?").has(Concept.PROFILE)).isTrue();
        assertThat(WaviQuestionFrame.of("how do I add a doc to the knowledge base").has(Concept.KNOWLEDGE_BASE)).isTrue();
    }

    @Test
    void onlyABusinessNounLosesItsPossessiveNeverAPersonsName() {
        assertThat(WaviQuestionFrame.withoutConceptPossessives("Show my team's pending timesheets"))
                .isEqualTo("Show my team pending timesheets");
        assertThat(WaviContinuation.namesThirdParty(WaviQuestionFrame.withoutConceptPossessives(
                "Show my team's pending timesheets"))).isFalse();
        assertThat(WaviContinuation.namesThirdParty(WaviQuestionFrame.withoutConceptPossessives(
                "Show Ravi's pending timesheets"))).isTrue();
    }
}
