package com.waypoint.service;

import com.waypoint.service.WaviAnswerVerifier.Evidence;
import com.waypoint.service.WaviAnswerVerifier.Kind;
import com.waypoint.service.WaviAnswerVerifier.Problem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OFFLINE: the typed-evidence verifier across languages. Evidence is what the model was shown (people as handles);
 * the checks are on figures, dates, handles and placeholders, not on English capitalisation, so a correct answer in
 * Spanish, German, French or Hindi (Latin script) passes and the same fabrication in any of them is caught.
 */
class WaviAnswerVerifierTest {

    private static final String QUEUE = String.join("\n", "3 timesheets from 2 people are waiting for your review:",
            "- [P1] - Mon Nov 2, 2026 to Sun Nov 8, 2026, submitted Wed Nov 4, 2026 at 17:00 (40 hours)",
            "- [P2] - Mon Nov 2, 2026 to Sun Nov 8, 2026, submitted Wed Nov 4, 2026 at 17:00 (38.5 hours)",
            "- [P2] - Mon Oct 26, 2026 to Sun Nov 1, 2026, submitted Wed Oct 28, 2026 at 17:00 (40 hours)");

    private static Evidence evidence(String... results) {
        Evidence evidence = new Evidence();
        for (String result : results) evidence.addResult(result);
        evidence.addContext("2026-11-05");
        evidence.addWords("Wavi Waypoint Company Admin Human Resources Employees Timesheets");
        return evidence;
    }

    private static List<Problem> verify(String answer) {
        return WaviAnswerVerifier.verify(answer, evidence(QUEUE));
    }

    // --- Figures -----------------------------------------------------------------------------------------------------------

    @Test
    void figuresFromTheResultsAreSupportedInAnyLanguage() {
        assertThat(verify("3 timesheets from 2 people are waiting: [P1] and [P2].")).isEmpty();
        assertThat(verify("Hay 3 hojas de horas pendientes de [P1] y [P2], con 40 horas cada una.")).isEmpty();
        assertThat(verify("Es warten 3 Zeiterfassungen von 2 Personen auf Ihre Freigabe, [P2] hat 38,5 Stunden eingetragen.")).isEmpty();
        assertThat(verify("[P2] a 38.5 heures et [P1] en a 40 : 3 feuilles de temps en tout.")).isEmpty();
    }

    @Test
    void anUnsupportedFigureIsRejectedInEveryLanguage() {
        for (String answer : List.of("7 timesheets are waiting.", "Hay 7 hojas de horas pendientes.",
                "Es warten 7 Zeiterfassungen.", "Il y a 7 feuilles de temps.", "[P1] ke 7 timesheets pending hain.")) {
            assertThat(verify(answer)).as(answer).extracting(Problem::kind).contains(Kind.NUMBER);
            assertThat(verify(answer)).as(answer).extracting(Problem::token).contains("7");
        }
    }

    @Test
    void aSumOrOtherArithmeticOnTheResultsIsNotSupported() {
        // 40 + 38.5 + 40 = 118.5; 3 + 2 = 5: neither number is stated, counted as rows, or counted as people.
        assertThat(verify("Together they logged 118.5 hours.")).extracting(Problem::token).contains("118.5");
        assertThat(verify("That is 6 items in total.")).extracting(Problem::token).contains("6");
    }

    @Test
    void structuralCountsAreRowsAndDistinctPeopleOfOneResult() {
        // 3 list rows and 2 distinct people; the people count does not need to be printed in the result.
        Evidence evidence = evidence("- [P1] week one\n- [P2] week one\n- [P2] week two");
        assertThat(WaviAnswerVerifier.verify("2 people have 3 timesheets.", evidence)).isEmpty();
        assertThat(WaviAnswerVerifier.verify("4 people have timesheets.", evidence)).extracting(Problem::token).contains("4");
    }

    @Test
    void numbersMatchByValueNotByDigitsInsideALongerNumber() {
        Evidence evidence = evidence("Total 2040 minutes, 8.5 hours, 08 days");
        assertThat(WaviAnswerVerifier.verify("8 days and 8,5 hours", evidence)).isEmpty();
        assertThat(WaviAnswerVerifier.verify("40 minutes", evidence)).extracting(Problem::token).contains("40");
        // The fraction of 8.5 is not a figure of its own (and the helper's date, 2026-11-05, has no 6 in it).
        assertThat(WaviAnswerVerifier.verify("6 hours", evidence)).extracting(Problem::token).contains("6");
        assertThat(WaviAnswerVerifier.verify("2 hours", evidence)).extracting(Problem::token).contains("2");
    }

    @Test
    void listMarkersNumberLinesAndAreNotClaims() {
        assertThat(verify("1. first\n2. second\n3) third")).isEmpty();
        assertThat(verify("1. first\n2. second and 9 more")).extracting(Problem::token).contains("9");
    }

    // --- Dates ---------------------------------------------------------------------------------------------------------------

    @Test
    void theSameDateInAnyWrittenFormIsTheSameFact() {
        for (String answer : List.of("Submitted on 2026-11-04.", "Eingereicht am 04.11.2026.", "Enviado el 4/11/2026.",
                "Submitted Nov 4, 2026.", "Enviado el 4 de noviembre de 2026.")) {
            assertThat(verify(answer)).as(answer).isEmpty();
        }
    }

    @Test
    void aDateThatIsNotInTheEvidenceIsRejectedWhateverTheFormat() {
        for (String answer : List.of("Submitted on 2026-11-09.", "Eingereicht am 09.11.2026.", "Submitted Nov 9, 2026.")) {
            assertThat(verify(answer)).as(answer).extracting(Problem::kind).contains(Kind.DATE);
        }
        assertThat(verify("Enviado el 9 de noviembre de 2026.")).extracting(Problem::token).contains("9");
    }

    // --- People and placeholders ---------------------------------------------------------------------------------------------

    @Test
    void onlyHandlesTheModelWasShownAreAccepted() {
        assertThat(verify("[P1] and [P2] are waiting.")).isEmpty();
        assertThat(verify("[P3] is waiting.")).extracting(Problem::kind).containsExactly(Kind.UNKNOWN_HANDLE);
    }

    @Test
    void aPlaceholderMustBeOneTheServerIssued() {
        Evidence evidence = evidence(QUEUE);
        evidence.addResult("[[L1]] - this result is withheld from you.");
        assertThat(WaviAnswerVerifier.verify("Here is your result: [[L1]]", evidence)).isEmpty();
        assertThat(WaviAnswerVerifier.verify("Here is your result: [[L2]]", evidence)).extracting(Problem::kind)
                .containsExactly(Kind.UNKNOWN_PLACEHOLDER);
    }

    @Test
    void aPlainNameNotInTheEvidenceIsRejectedWhereCapitalsMeanNames() {
        assertThat(verify("The manager is Maria Gonzalez.")).extracting(Problem::kind).contains(Kind.NAME);
        // A name inside a longer sentence does not switch the check off (a run of capitals is a name, not German nouns).
        assertThat(verify("Your reporting manager is Maria Gonzalez and she approved it.")).extracting(Problem::kind).contains(Kind.NAME);
        assertThat(verify("Ihr Vorgesetzter ist Maria Gonzalez und sie hat es genehmigt.")).extracting(Problem::kind).contains(Kind.NAME);
        // Product and role names the model may legitimately capitalise are vocabulary, not people.
        assertThat(verify("Ask Human Resources or your Company Admin.")).isEmpty();
        assertThat(verify("Your Company Admin can help.")).isEmpty();
    }

    @Test
    void capitalisedNounsInGermanAreNotTreatedAsNamesButFigureFabricationIsStillCaught() {
        String german = "Ihre Zeiterfassung wurde von Ihrem Vorgesetzten genehmigt, und die Freigabe durch die Personalabteilung "
                + "ist erfolgt. Alle Arbeitsstunden der Woche wurden korrekt erfasst.";
        assertThat(WaviAnswerVerifier.capitalSignalUsable(german)).isFalse();
        assertThat(verify(german)).isEmpty();
        assertThat(verify(german + " Es waren 99 Stunden.")).extracting(Problem::token).contains("99");
        assertThat(WaviAnswerVerifier.capitalSignalUsable("the manager and the team lead reviewed the hours today")).isTrue();
    }

    @Test
    void anEmptyAnswerIsNotAnAnswer() {
        assertThat(verify("")).isNotEmpty();
        assertThat(verify("   ")).isNotEmpty();
    }

    @Test
    void problemsAreReportedWithTheirKindsForLogsAndNeverTheText() {
        List<Problem> problems = verify("7 timesheets for [P9] on 2026-12-01");
        assertThat(WaviAnswerVerifier.kinds(problems)).contains("NUMBER:1", "DATE:1", "UNKNOWN_HANDLE:1");
    }
}
