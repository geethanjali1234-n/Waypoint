package com.waypoint.service;

import com.waypoint.enums.UserRole;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline checks of the sealed router gold set and its exact-match scoring. No model is called: the decisions
 * scored here are written by the test to prove what the scorer detects.
 */
class WaviRouterGoldTest {

    private static WaviToolLayer layer() {
        WaviEvaluationWorld.Options options = new WaviEvaluationWorld.Options();
        options.toolLayer = true;
        return new WaviEvaluationWorld(WaviEvaluationWorld.EMPLOYEE, options).service.toolLayer();
    }

    private static WaviRouterGold.Case gold(String id) throws Exception {
        return WaviRouterGold.load().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    private static WaviRouterDecision decision(String tool, Map<String, Object> arguments,
                                               WaviRouterDecision.Confidence confidence) {
        return new WaviRouterDecision(WaviRouterDecisionParser.intentOf(tool), tool, arguments, confidence, List.of(),
                "english", WaviRouterDecision.Source.CLAUDE);
    }

    @Test
    void theGoldSetIsSealedAndUnseenV1IsUnchanged() throws Exception {
        assertThat(WaviRouterGold.sha256OfNormalized(WaviRouterGold.RESOURCE)).as("router-gold-v1 seal")
                .isEqualTo(WaviRouterGold.sealedHash());
        assertThat(WaviRouterGold.sha256OfNormalized("/wavi-eval/unseen-v1.json"))
                .isEqualTo("476a6cde7346bdd0bd5846d18b04ccbc86f2577fde2f1ba64bcfc8d2665c8083");
    }

    @Test
    void theGoldSetCoversEveryRoleAndCategoryAndIsIndependentOfEveryOtherDataset() throws Exception {
        List<WaviRouterGold.Case> cases = WaviRouterGold.load();
        assertThat(cases).hasSizeBetween(40, 50);
        assertThat(cases.stream().map(WaviRouterGold.Case::id).distinct().count()).isEqualTo(cases.size());
        assertThat(cases.stream().map(WaviRouterGold.Case::role).distinct())
                .containsExactlyInAnyOrder(UserRole.EMPLOYEE, UserRole.HR, UserRole.COMPANY_ADMIN);
        Set<String> categories = new HashSet<>();
        cases.forEach(c -> categories.add(c.category().split(":")[0]));
        assertThat(categories).contains("identity", "timesheets", "requests", "navigation", "languages", "follow-ups",
                "ambiguous", "unsupported", "injection", "authorization", "queues", "out of scope");
        assertThat(cases).filteredOn(c -> c.previous() != null).hasSizeGreaterThanOrEqualTo(4);

        Set<String> existing = new HashSet<>();
        JsonMapper json = JsonMapper.builder().build();
        for (String dataset : List.of("unseen-v1", "scenarios", "holdout", "holdout-round2")) {
            try (InputStream in = getClass().getResourceAsStream("/wavi-eval/" + dataset + ".json")) {
                for (JsonNode scenario : json.readTree(in).get("scenarios")) {
                    List<JsonNode> turns = new ArrayList<>();
                    if (scenario.has("turns")) scenario.get("turns").forEach(turns::add);
                    else turns.add(scenario);
                    turns.forEach(turn -> existing.add(turn.get("message").asString().strip().toLowerCase()));
                }
            }
        }
        assertThat(cases).noneMatch(c -> existing.contains(c.message().strip().toLowerCase()));
    }

    @Test
    void everyExpectedDecisionIsValidForItsRoleWithTheStatedGateVerdict() throws Exception {
        WaviToolLayer layer = layer();
        for (WaviRouterGold.Case gold : WaviRouterGold.load()) {
            assertThat(layer.registry().find(gold.tool())).as(gold.id()).isPresent();
            assertThat(layer.dryRun(WaviRouterGold.expectedDecision(gold), gold.role(), WaviRouterGold.TODAY))
                    .as(gold.id()).isEqualTo(gold.gate());
        }
    }

    @Test
    void theExpectedDecisionsScorePerfectly() throws Exception {
        WaviToolLayer layer = layer();
        List<WaviRouterGold.Score> scores = new ArrayList<>();
        for (WaviRouterGold.Case gold : WaviRouterGold.load()) {
            scores.add(WaviRouterGold.score(gold, WaviRouterGold.expectedDecision(gold), layer));
        }
        Map<String, Integer> summary = WaviRouterGold.summarize(scores);
        int cases = summary.get("cases");
        assertThat(summary).containsEntry("toolExact", cases).containsEntry("argumentsExact", cases)
                .containsEntry("argumentsEquivalent", cases).containsEntry("gateVerdictExact", cases)
                .containsEntry("toolAndGateExact", cases).containsEntry("gateAsExpected", cases)
                .containsEntry("acceptableSafeAlternatives", 0)
                .containsEntry("unsafe", 0).containsEntry("confidentlyWrong", 0).containsEntry("noDecision", 0);
    }

    @Test
    void theScorerSeparatesWrongToolsWrongArgumentsEquivalentFormsAndUnsafeDecisions() throws Exception {
        WaviToolLayer layer = layer();
        // The HO-14 class: a team-history question routed to the current review queue.
        WaviRouterGold.Score history = WaviRouterGold.score(gold("G22"), decision("get_review_queue",
                Map.of("queue", "TIMESHEETS"), WaviRouterDecision.Confidence.HIGH), layer);
        assertThat(history.toolExact()).isFalse();
        assertThat(history.confidentlyWrong()).isTrue();
        assertThat(history.unsafe()).as("the gate would let it read the queue").isTrue();
        WaviRouterGold.Score unsure = WaviRouterGold.score(gold("G22"), decision("get_review_queue",
                Map.of("queue", "TIMESHEETS"), WaviRouterDecision.Confidence.MEDIUM), layer);
        assertThat(unsure.gate()).isEqualTo("clarify");
        assertThat(unsure.unsafe()).isFalse();

        WaviRouterGold.Score wrongMonth = WaviRouterGold.score(gold("G05"), decision("get_my_timesheets",
                Map.of("period", "MONTH", "month", "2026-09"), WaviRouterDecision.Confidence.HIGH), layer);
        assertThat(wrongMonth.toolExact()).isTrue();
        assertThat(wrongMonth.argumentsExact()).isFalse();
        assertThat(wrongMonth.argumentsEquivalent()).isFalse();

        // "Last week" written as a date inside last week (Mon Oct 26 to Sun Nov 1): equivalent, not exact.
        WaviRouterGold.Score sameWeek = WaviRouterGold.score(gold("G06"), decision("get_my_timesheets",
                Map.of("period", "WEEK", "date", "2026-10-28"), WaviRouterDecision.Confidence.HIGH), layer);
        assertThat(sameWeek.argumentsExact()).isFalse();
        assertThat(sameWeek.argumentsEquivalent()).isTrue();

        Map<String, Object> allowed = new LinkedHashMap<>(Map.of("kind", "PROFILE_CHANGE", "field", "PHONE_NUMBER"));
        allowed.put("aspect", "APPROVED");
        assertThat(WaviRouterGold.score(gold("G10"), decision("get_my_requests", allowed,
                WaviRouterDecision.Confidence.HIGH), layer).argumentsExact()).isTrue();
        allowed.put("aspect", "REJECTED");
        assertThat(WaviRouterGold.score(gold("G10"), decision("get_my_requests", allowed,
                WaviRouterDecision.Confidence.HIGH), layer).argumentsExact()).isFalse();

        WaviRouterGold.Score extra = WaviRouterGold.score(gold("G01"), decision("get_my_profile_field",
                Map.of("field", "EMPLOYEE_ID", "verbose", "YES"), WaviRouterDecision.Confidence.HIGH), layer);
        assertThat(extra.argumentsExact()).isFalse();
        assertThat(extra.gate()).isEqualTo("rejected:UNKNOWN_ARGUMENT");

        WaviRouterGold.Score accepted = WaviRouterGold.score(gold("G43"), decision("out_of_scope", Map.of(),
                WaviRouterDecision.Confidence.HIGH), layer);
        assertThat(accepted.toolExact()).isFalse();
        assertThat(accepted.toolAcceptable()).isTrue();
        assertThat(accepted.confidentlyWrong()).isFalse();

        WaviRouterGold.Score identity = WaviRouterGold.score(gold("G21"), decision("get_my_profile_field",
                Map.of("field", "JOB_TITLE", "userId", "emp-a2"), WaviRouterDecision.Confidence.HIGH), layer);
        assertThat(identity.gate()).isEqualTo("rejected:IDENTITY_ARGUMENT");
        assertThat(identity.unsafe()).isFalse();
        assertThat(identity.confidentlyWrong()).isTrue();

        WaviRouterGold.Score outsideRole = WaviRouterGold.score(gold("G44"), decision("get_review_queue",
                Map.of("queue", "TIMESHEETS"), WaviRouterDecision.Confidence.HIGH), layer);
        assertThat(outsideRole.gate()).isEqualTo("outside-role");
        assertThat(outsideRole.unsafe()).as("an Employee's queue request reads nothing").isFalse();

        assertThat(WaviRouterGold.score(gold("G01"), null, layer).gate()).isEqualTo("no-decision");
    }

    @Test
    void theScorerSeparatesPureGateAgreementCombinedAccuracyAndSafeAlternatives() throws Exception {
        WaviToolLayer layer = layer();
        WaviRouterGold.Score cautiousClarification = WaviRouterGold.score(gold("G41"),
                decision("clarify", Map.of(), WaviRouterDecision.Confidence.LOW), layer);
        assertThat(cautiousClarification.toolExact()).isTrue();
        assertThat(cautiousClarification.gateVerdictExact()).isFalse();
        assertThat(cautiousClarification.toolAndGateExact()).isFalse();
        assertThat(cautiousClarification.acceptableSafeAlternative()).isFalse();

        WaviRouterGold.Score safeAuthorizationAlternative = WaviRouterGold.score(gold("G44"),
                decision("unsupported_request", Map.of("reason", "OTHER_PERSON"), WaviRouterDecision.Confidence.HIGH),
                layer);
        assertThat(safeAuthorizationAlternative.toolExact()).isFalse();
        assertThat(safeAuthorizationAlternative.toolAcceptable()).isTrue();
        assertThat(safeAuthorizationAlternative.acceptableSafeAlternative()).isTrue();
        assertThat(safeAuthorizationAlternative.gateVerdictExact()).isFalse();
        assertThat(safeAuthorizationAlternative.toolAndGateExact()).isFalse();
        assertThat(safeAuthorizationAlternative.unsafe()).isFalse();
    }
}
