package com.waypoint.service;

import com.waypoint.service.WaviAnswerVerifier.Evidence;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OFFLINE grounding challenge: model-style answers, correct and fabricated, in six languages, checked against typed
 * evidence. The cases reproduce the CLASSES of failure seen in the real-model evaluation (capitalised German nouns,
 * generic capitalised phrases, spelled-out and re-formatted figures, dates in other formats, an invented person, a sum)
 * with new wording; they are not the evaluated questions. It measures the verifier alone: how many correct answers pass
 * (the false-rejection rate that cost answers live) and how many fabrications are caught.
 */
class WaviGroundingChallengeTest {

    private static final String QUEUE = String.join("\n", "3 timesheets from 2 people are waiting for your review:",
            "- [P1] - Mon Nov 2, 2026 to Sun Nov 8, 2026, submitted Wed Nov 4, 2026 at 17:00 (40 hours)",
            "- [P2] - Mon Nov 2, 2026 to Sun Nov 8, 2026, submitted Wed Nov 4, 2026 at 17:00 (38.5 hours)",
            "- [P2] - Mon Oct 26, 2026 to Sun Nov 1, 2026, submitted Wed Oct 28, 2026 at 17:00 (40 hours)");
    private static final String PROFILE = "Your job title is Senior Data Analyst and your reporting manager is [P3].";

    private record Case(String language, String answer, boolean supported) {
    }

    /** What the user asked, by language: the evidence always includes the question that produced the answer. */
    private static String question(String language) {
        return switch (language) {
            case "de" -> "Wie viele Zeiterfassungen warten auf meine Freigabe und wer ist mein Vorgesetzter?";
            case "es" -> "\u00bfCu\u00e1ntas hojas de horas esperan mi revisi\u00f3n?";
            case "fr" -> "Combien de feuilles de temps attendent ma validation ?";
            case "hi-latn" -> "Mere kitne timesheets pending hain?";
            case "pt" -> "Quantas folhas de horas esperam minha revis\u00e3o?";
            case "it" -> "Quanti fogli ore aspettano la mia revisione?";
            default -> "How many timesheets are waiting for my review?";
        };
    }

    private static Evidence evidence(String language) {
        Evidence evidence = new Evidence();
        evidence.addUserText(question(language));
        evidence.addResult(QUEUE);
        evidence.addResult(PROFILE);
        evidence.addContext("2026-11-18");
        evidence.addWords("Wavi Waypoint Company Admin Human Resources Employee HR Timesheets Knowledge Base");
        return evidence;
    }

    private static List<Case> cases() {
        List<Case> cases = new ArrayList<>();
        // ---- correct answers: must pass -----------------------------------------------------------------------------------
        cases.add(new Case("en", "3 timesheets from 2 people are waiting: [P1] and [P2].", true));
        cases.add(new Case("en", "[P2] logged 38.5 hours for the week of Nov 2 and 40 hours the week before.", true));
        cases.add(new Case("en", "Ask Human Resources or your Company Admin if you need the Knowledge Base page.", true));
        cases.add(new Case("en", "1. [P1] submitted on 2026-11-04.\n2. [P2] submitted on 2026-11-04 and 2026-10-28.", true));
        cases.add(new Case("en", "Your job title is Senior Data Analyst, and [P3] is your reporting manager.", true));
        cases.add(new Case("es", "Hay 3 hojas de horas pendientes de revisión: [P1] y [P2], enviadas el 4 de noviembre de 2026.", true));
        cases.add(new Case("es", "[P2] registró 38,5 horas y [P1] registró 40 horas esa semana.", true));
        cases.add(new Case("es", "Tu cargo es Senior Data Analyst y tu responsable es [P3].", true));
        cases.add(new Case("de", "Es warten 3 Zeiterfassungen von 2 Personen auf Ihre Freigabe: [P1] und [P2].", true));
        cases.add(new Case("de", "Ihre Zeiterfassung wurde am 04.11.2026 eingereicht und wartet auf die Freigabe durch Ihren Vorgesetzten.", true));
        cases.add(new Case("de", "[P2] hat 38,5 Stunden und [P1] hat 40 Stunden Arbeitszeit eingetragen, insgesamt in 3 Einträgen.", true));
        cases.add(new Case("de", "Der Jobtitel in der Personalakte lautet Senior Data Analyst, Ihr Vorgesetzter ist [P3].", true));
        cases.add(new Case("fr", "Vous avez 3 feuilles de temps en attente de [P1] et de [P2], envoyées le 04/11/2026.", true));
        cases.add(new Case("fr", "[P2] a saisi 38,5 heures et [P1] a saisi 40 heures cette semaine-là.", true));
        cases.add(new Case("fr", "Votre poste est Senior Data Analyst et votre responsable est [P3].", true));
        cases.add(new Case("hi-latn", "[P2] ke 2 timesheets pending hain, 40 ghante aur 38.5 ghante ke saath.", true));
        cases.add(new Case("hi-latn", "Aapka job title Senior Data Analyst hai aur aapke manager [P3] hain.", true));
        cases.add(new Case("pt", "Existem 3 folhas de horas pendentes: [P1] e [P2], enviadas em 04/11/2026.", true));
        cases.add(new Case("it", "Ci sono 3 fogli ore in attesa di [P1] e [P2], inviati il 4 novembre 2026.", true));
        // ---- fabrications: must be rejected ---------------------------------------------------------------------------------
        cases.add(new Case("en", "7 timesheets from 5 people are waiting.", false));
        cases.add(new Case("en", "Together they logged 118.5 hours.", false));
        cases.add(new Case("en", "[P1] submitted on 2026-11-09.", false));
        cases.add(new Case("en", "Your reporting manager is Maria Gonzalez and she approved the timesheets.", false));
        cases.add(new Case("en", "[P1], [P2] and [P9] are waiting.", false));
        cases.add(new Case("es", "Hay 9 hojas de horas pendientes de [P1] y [P2].", false));
        cases.add(new Case("es", "Enviada el 9 de noviembre de 2026 por [P1].", false));
        cases.add(new Case("es", "Su responsable es María González y ya lo aprobó.", false));
        cases.add(new Case("de", "Es warten 9 Zeiterfassungen auf Ihre Freigabe durch die Personalabteilung.", false));
        cases.add(new Case("de", "[P2] hat 99 Stunden eingetragen und die Freigabe ist am 09.11.2026 erfolgt.", false));
        cases.add(new Case("fr", "Vous avez 9 feuilles de temps en attente.", false));
        cases.add(new Case("fr", "[P2] a saisi 99 heures cette semaine, envoyées le 09/11/2026.", false));
        cases.add(new Case("hi-latn", "[P2] ke 9 timesheets pending hain.", false));
        cases.add(new Case("pt", "Existem 12 folhas de horas pendentes de [P1].", false));
        cases.add(new Case("it", "Ci sono 9 fogli ore in attesa di [P1] e [P7].", false));
        return cases;
    }

    @Test
    void everyCorrectAnswerPassesAndEveryFabricationIsCaughtAcrossSixLanguages() {
        List<String> falseRejections = new ArrayList<>();
        List<String> missed = new ArrayList<>();
        int supported = 0;
        int fabricated = 0;
        for (Case challenge : cases()) {
            boolean accepted = WaviAnswerVerifier.verify(challenge.answer(), evidence(challenge.language())).isEmpty();
            if (challenge.supported()) {
                supported++;
                if (!accepted) falseRejections.add(challenge.language() + ": " + challenge.answer() + " -> "
                        + WaviAnswerVerifier.verify(challenge.answer(), evidence(challenge.language())));
            } else {
                fabricated++;
                if (accepted) missed.add(challenge.language() + ": " + challenge.answer());
            }
        }
        // Correct answers and fabrications in en, es, de, fr, hi (Latin script), pt and it.
        assertThat(supported).isEqualTo(19);
        assertThat(fabricated).isEqualTo(15);
        assertThat(falseRejections).as("correct answers wrongly rejected").isEmpty();
        assertThat(missed).as("fabrications that got through").isEmpty();
    }

    /**
     * A KNOWN LIMIT, asserted so it stays visible: where the user writes in a language that capitalises its nouns, a
     * capital says nothing about a name, so a plain invented name in the answer is not caught by the capital check.
     * Figures, dates, handles and placeholders are still verified; the protection for people is that real names exist
     * only as handles and the model is told never to write one.
     */
    @Test
    void anInventedPlainNameInACapitalNounLanguageIsNotCaughtByCapitalsAlone() {
        String answer = "Ihr Vorgesetzter ist Maria Gonzalez und sie hat es genehmigt.";
        assertThat(WaviAnswerVerifier.verify(answer, evidence("de"))).extracting(WaviAnswerVerifier.Problem::kind)
                .doesNotContain(WaviAnswerVerifier.Kind.NAME);
        // The same sentence in English is caught.
        assertThat(WaviAnswerVerifier.verify("Your manager is Maria Gonzalez and she approved it.", evidence("en")))
                .extracting(WaviAnswerVerifier.Problem::kind).contains(WaviAnswerVerifier.Kind.NAME);
    }

    /**
     * A KNOWN LIMIT, asserted so it stays visible: a day of the month or a year in the evidence counts as a number, so an
     * invented quantity that happens to equal one (here the 8 of "Sun Nov 8") is not caught. Making dates ISO-only would
     * avoid it but reject correct answers that name a month in another language.
     */
    @Test
    void aFabricatedQuantityEqualToADayInTheEvidenceIsNotCaught() {
        assertThat(WaviAnswerVerifier.verify("Hay 8 hojas de horas pendientes.", evidence("es"))).isEmpty();
        assertThat(WaviAnswerVerifier.verify("Hay 9 hojas de horas pendientes.", evidence("es"))).isNotEmpty();
    }
}
