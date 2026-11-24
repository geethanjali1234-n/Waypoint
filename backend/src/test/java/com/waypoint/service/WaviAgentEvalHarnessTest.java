package com.waypoint.service;

import com.waypoint.service.WaviAgentEvalHarness.Budget;
import com.waypoint.service.WaviAgentEvalHarness.Config;
import com.waypoint.service.WaviAgentEvalHarness.PhaseReport;
import com.waypoint.service.WaviAgentEvalQuestions.Expect;
import com.waypoint.service.WaviAgentEvalQuestions.Phase;
import com.waypoint.service.WaviAgentEvalQuestions.Question;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The free DRY-RUN of the live agent evaluation: the same harness, the same synthetic world and the same real
 * ClaudeChatClient, against a local stub that plays an honest model. It proves the harness (budget, ledger, privacy
 * and authorization stops, recording, report) and the integration; it says nothing about the real model's accuracy.
 */
class WaviAgentEvalHarnessTest {

    private static final Path OUT = Path.of("target", "wavi-eval");

    private static Config stubConfig(String name, BigDecimal cap, Set<Phase> phases) throws IOException {
        Path ledger = OUT.resolve(name + "-ledger.json");
        Files.createDirectories(OUT);
        Files.deleteIfExists(ledger);
        return new Config("stub", null, "sk-stub-not-a-key", cap, phases, OUT, ledger,
                WaviAgentEvalHarness.MAX_MESSAGES, WaviAgentEvalHarness.MAX_REQUESTS, name.equals("agent-stub") ? "" : name);
    }

    // --- The dry-run -------------------------------------------------------------------------------------------------------

    @Test
    void theStubDryRunCompletesBothPhasesWithNoSafetyStopAndWithinBudget() throws IOException {
        Config config = stubConfig("agent-stub", new BigDecimal("0.50"), Set.of(Phase.A, Phase.B));
        List<PhaseReport> reports = WaviAgentEvalHarness.run(config);

        assertThat(reports).hasSize(2);
        PhaseReport a = reports.get(0);
        PhaseReport b = reports.get(1);
        assertThat(a.stopReason()).as("phase A stop").isNull();
        assertThat(b.stopReason()).as("phase B stop").isNull();
        assertThat(a.results()).hasSize(40);
        assertThat(b.results()).hasSize(30);
        assertThat(a.outboundViolations() + b.outboundViolations()).isZero();
        assertThat(a.outboundRequests()).isGreaterThan(20);

        // The honest stub chooses the first accepted tool and words answers from tool results: the harness must agree.
        List<String> wrongTool = new ArrayList<>();
        List<String> wrongPath = new ArrayList<>();
        List<String> wrongAnswer = new ArrayList<>();
        for (PhaseReport report : reports) {
            report.results().forEach(result -> {
                if (!result.toolOk()) wrongTool.add(result.question().id() + " " + result.toolsRequested());
                if (!result.pathOk()) wrongPath.add(result.question().id() + " " + result.stage() + "/" + result.agentKind() + ":" + result.agentReason());
                if (!result.answerOk()) wrongAnswer.add(result.question().id() + " -> " + result.answer());
            });
        }
        // The ONLY tool-selection miss is the planted one: A-C08 asks an Employee for a tool the Employee role is not offered.
        // The harness must report it as an unexpected tool choice, and nothing may have been read for it.
        assertThat(wrongTool).as("tool selection").containsExactly("A-C08 [get_direct_reports]");
        var planted = a.results().stream().filter(result -> result.question().id().equals("A-C08")).findFirst().orElseThrow();
        assertThat(planted.toolsNotOffered()).containsExactly(WaviTools.GET_DIRECT_REPORTS);
        assertThat(planted.toolsExecuted()).isEmpty();
        assertThat(planted.answer()).doesNotContain("Asha", "Ravi", "Hari");
        assertThat(wrongPath).as("expected path").isEmpty();
        assertThat(wrongAnswer).as("answers").isEmpty();

        // Reports and the outbound audit logs were written.
        assertThat(OUT.resolve("agent-eval-A-stub.md")).exists();
        assertThat(OUT.resolve("agent-eval-B-stub.md")).exists();
        assertThat(Files.readString(OUT.resolve("agent-outbound-A-stub.jsonl"))).isNotBlank();

        // Spend is accounted at the real prices from the (stub-reported) usage and stays far below the cap.
        Budget ledger = new Budget(new BigDecimal("0.50"), config.ledger(), 80, 240);
        assertThat(ledger.spent()).isPositive().isLessThan(new BigDecimal("0.50"));
        assertThat(ledger.messages()).isEqualTo(70);
        assertThat(ledger.requests()).isBetween(80, 240);
    }

    @Test
    void phaseAWithRecordsOnlyNeverSendsANameAndPhaseBMayButNeverAnIdentifier() throws IOException {
        // Its OWN dry-run with its own file tag, so the audit never reads another test's (or an earlier build's) files.
        WaviAgentEvalHarness.run(stubConfig("agent-stub-audit", new BigDecimal("0.50"), Set.of(Phase.A, Phase.B)));
        String a = Files.readString(OUT.resolve("agent-outbound-A-stub-agent-stub-audit.jsonl"));
        assertThat(a).doesNotContain("Asha", "Ravi", "Hari Kumar", "Anil", "Priya", "Meera", "WPT-", "emp-a", "org-eval", "@");
        String b = Files.readString(OUT.resolve("agent-outbound-B-stub-agent-stub-audit.jsonl"));
        // Names are exchanged for handles in BOTH phases: even with names approved the provider only sees [P1], [P2].
        // The one place a name appears is an Employee's OWN typed question about a colleague, who is not in that Employee's
        // scope, so there is nothing to hide: user-typed text goes as typed. Take those out; no name remains anywhere else.
        String withoutTypedColleague = b.replace("who is Ravi Teja and what does he do?", "")
                .replace("who is Ravi Teja's manager?", "").replace("who is Ravi Teja", "");
        assertThat(b).contains("[P1]");
        assertThat(withoutTypedColleague).doesNotContain("Asha", "Ravi", "WPT-", "emp-a", "hr-a1", "admin-a1", "org-eval",
                "personRef", "@");
    }

    // --- Budget enforcement --------------------------------------------------------------------------------------------------

    @Test
    void aRequestThatCouldExceedTheRemainingBudgetIsNeverSent() throws IOException {
        Path ledger = OUT.resolve("agent-budget-test-ledger.json");
        Files.deleteIfExists(ledger);
        Budget budget = new Budget(new BigDecimal("0.0030"), ledger, 80, 240);
        BigDecimal worst = WaviAgentEvalHarness.worstCase(17_000);
        budget.reserve(worst);
        budget.recordActual(new ClaudeChatClient.RouteUsage(5000, 100, 0, 0));
        assertThat(budget.spent()).isEqualByComparingTo("0.00055");
        // Another reservation is refused as soon as spent + worst case would pass the cap; the request count is not consumed.
        BigDecimal big = new BigDecimal("0.0030");
        int requestsBefore = budget.requests();
        assertThatThrownBy(() -> budget.reserve(big)).isInstanceOf(WaviAgentEvalHarness.BudgetExhausted.class);
        assertThat(budget.requests()).isEqualTo(requestsBefore);
    }

    @Test
    void theCombinedCapSpansPhasesAndInvocationsBecauseTheLedgerIsPersisted() throws IOException {
        Path ledger = OUT.resolve("agent-combined-test-ledger.json");
        Files.deleteIfExists(ledger);
        Budget first = new Budget(new BigDecimal("0.50"), ledger, 80, 240);
        first.reserve(new BigDecimal("0.0014"));
        first.recordActual(new ClaudeChatClient.RouteUsage(1_000_000, 500_000, 0, 0));   // $0.10 + $0.25 = $0.35
        Budget second = new Budget(new BigDecimal("0.50"), ledger, 80, 240);
        assertThat(second.spent()).isEqualByComparingTo("0.35");
        assertThat(second.requests()).isEqualTo(1);
        // $0.35 spent: a worst case of $0.20 no longer fits in the SAME $0.50, however the run was split.
        assertThatThrownBy(() -> second.reserve(new BigDecimal("0.20"))).isInstanceOf(WaviAgentEvalHarness.BudgetExhausted.class);
        second.reserve(new BigDecimal("0.10"));
    }

    @Test
    void aFailedOrUsageLessRequestIsChargedAtItsWorstCaseAndAnUnreadableLedgerRefusesToSpend() throws IOException {
        Path ledger = OUT.resolve("agent-unknown-test-ledger.json");
        Files.deleteIfExists(ledger);
        Budget budget = new Budget(new BigDecimal("0.50"), ledger, 80, 240);
        BigDecimal worst = WaviAgentEvalHarness.worstCase(17_000);
        budget.reserve(worst);
        budget.recordUnknown(worst);
        assertThat(budget.spent()).isEqualByComparingTo(worst);
        assertThat(budget.unknownCostRequests()).isEqualTo(1);
        Files.writeString(ledger, "{not json");
        assertThatThrownBy(() -> new Budget(new BigDecimal("0.50"), ledger, 80, 240))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("refusing to spend");
    }

    @Test
    void theCapCanNeverBeRaisedAboveTheApprovedFiftyCents() {
        assertThatThrownBy(() -> new Budget(new BigDecimal("0.51"), null, 80, 240)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Budget(BigDecimal.ZERO, null, 80, 240)).isInstanceOf(IllegalArgumentException.class);
        assertThat(WaviAgentEvalHarness.HARD_CAP_USD).isEqualByComparingTo("0.50");
        // Per run (the first run used 70 messages and 114 requests; each new run label starts its own counters).
        assertThat(WaviAgentEvalHarness.MAX_MESSAGES).isEqualTo(100);
        assertThat(WaviAgentEvalHarness.MAX_REQUESTS).isEqualTo(300);
        assertThat(WaviAgentEvalHarness.DEFAULT_RUN_CAP_USD).isEqualByComparingTo("0.15");
    }

    @Test
    void theMessageAndRequestCapsStopTheRun() throws IOException {
        Path ledger = OUT.resolve("agent-caps-test-ledger.json");
        Files.deleteIfExists(ledger);
        Config config = new Config("stub", null, "sk-stub", new BigDecimal("0.50"), Set.of(Phase.A), OUT, ledger, 5, 240, "caps");
        List<PhaseReport> reports = WaviAgentEvalHarness.run(config);
        assertThat(reports.getFirst().stopReason()).startsWith("BUDGET: message cap 5 reached");
        assertThat(reports.getFirst().results()).hasSize(5);
    }

    // --- Safety stops ---------------------------------------------------------------------------------------------------------

    @Test
    void aNameTypedInPhaseAStopsTheRunBeforeAnythingIsSentBecauseNamesAreNotApproved() throws IOException {
        Config config = stubConfig("agent-stop-name", new BigDecimal("0.50"), Set.of(Phase.A));
        // A person's name is hidden before sending, so a typed NAME no longer stops a run; a literal the minimizer and the
        // handles cannot recognise does, and nothing is sent.
        Question typedName = new Question("T-1", Phase.A, WaviEvaluationWorld.HR, "t1", "my personRef value is abc please",
                Expect.ANY, Set.of(), List.of(), List.of(), List.of(), "ok");
        List<PhaseReport> reports = WaviAgentEvalHarness.run(config, List.of(typedName));
        assertThat(reports.getFirst().stopReason()).startsWith("SAFETY: outbound payload contains forbidden literal 'personRef'");
        assertThat(reports.getFirst().results()).isEmpty();
        Budget ledger = new Budget(new BigDecimal("0.50"), config.ledger(), 80, 240);
        assertThat(ledger.requests()).isZero();
        assertThat(ledger.spent()).isZero();
    }

    @Test
    void aForbiddenFactInAnAnswerShownToTheUserStopsTheRun() throws IOException {
        Config config = stubConfig("agent-stop-fact", new BigDecimal("0.50"), Set.of(Phase.A));
        Question leaks = new Question("T-2", Phase.A, WaviEvaluationWorld.EMPLOYEE, "t2", "what do I do here, job-title wise?",
                Expect.ANY, Set.of(), List.of(), List.of("Senior Data Analyst"),
                List.of(new WaviAgentEvalQuestions.StubCall(WaviTools.GET_MY_PROFILE_FIELD, java.util.Map.of("field", "JOB_TITLE"))), null);
        Question never = new Question("T-3", Phase.A, WaviEvaluationWorld.EMPLOYEE, "t3", "what do I do here, job-title wise?",
                Expect.ANY, Set.of(), List.of(), List.of(), List.of(), null);
        List<PhaseReport> reports = WaviAgentEvalHarness.run(config, List.of(leaks, never));
        assertThat(reports.getFirst().stopReason()).startsWith("SAFETY: answer to T-2 contains forbidden fact");
        // The run stopped immediately: the next question was not asked.
        assertThat(reports.getFirst().results()).isEmpty();
    }

    @Test
    void theQuestionSetIsWithinTheApprovedSizeAndCoversAllThreeRoles() {
        List<Question> all = WaviAgentEvalQuestions.all();
        assertThat(all).hasSize(94);
        assertThat(all.stream().filter(question -> question.phase() == Phase.A)).hasSize(40);
        assertThat(all.stream().filter(question -> question.phase() == Phase.B)).hasSize(30);
        assertThat(all.stream().filter(question -> question.phase() == Phase.C)).hasSize(24);
        assertThat(all.stream().map(question -> question.person().role()).distinct()).hasSize(3);
        assertThat(all.stream().map(Question::id).distinct()).hasSize(94);
        assertThat(all.size()).isLessThanOrEqualTo(WaviAgentEvalHarness.MAX_MESSAGES);
    }

    // --- v2: the first run's ledger, a new run label, one lifetime cap -------------------------------------------------------

    /** The ledger exactly as the first real run (Phases A and B) left it. */
    private static final String V1_LEDGER = "{\"unknownCostRequests\":0,\"requests\":114,\"capUsd\":\"0.50\","
            + "\"messages\":70,\"spentUsd\":\"0.02668007\"}";

    private static Path copyOfFirstRunLedger(String name) throws IOException {
        Files.createDirectories(OUT);
        Path ledger = OUT.resolve(name + "-ledger.json");
        Files.writeString(ledger, V1_LEDGER);
        return ledger;
    }

    @Test
    void aNewRunStartsItsOwnCountersButInheritsTheFirstRunsSpendFromTheLedger() throws IOException {
        Path ledger = copyOfFirstRunLedger("agent-v2-inherit");
        Budget v2 = new Budget(new BigDecimal("0.50"), ledger, "v2", new BigDecimal("0.15"), 100, 300);
        assertThat(v2.spent()).isEqualByComparingTo("0.02668007");
        assertThat(v2.runSpent()).isZero();
        assertThat(v2.messages()).isZero();
        assertThat(v2.requests()).isZero();
        assertThat(v2.remaining()).isEqualByComparingTo("0.47331993");
        v2.recordMessage();
        v2.reserve(new BigDecimal("0.001"));
        v2.recordActual(new ClaudeChatClient.RouteUsage(1_000_000, 0, 0, 0));    // $0.10
        assertThat(v2.spent()).isEqualByComparingTo("0.12668007");
        assertThat(v2.runSpent()).isEqualByComparingTo("0.1");
        // The first run's counters are still there, and a reopened ledger shows both runs and one lifetime spend.
        assertThat(new Budget(new BigDecimal("0.50"), ledger, 100, 300).messages()).isEqualTo(70);
        assertThat(new Budget(new BigDecimal("0.50"), ledger, 100, 300).requests()).isEqualTo(114);
        Budget reopened = new Budget(new BigDecimal("0.50"), ledger, "v2", new BigDecimal("0.15"), 100, 300);
        assertThat(reopened.messages()).isEqualTo(1);
        assertThat(reopened.requests()).isEqualTo(1);
        assertThat(reopened.spent()).isEqualByComparingTo("0.12668007");
        assertThat(reopened.runSpent()).isEqualByComparingTo("0.1");
    }

    @Test
    void theRunAllowanceStopsARunBeforeTheLifetimeCapEvenMatters() throws IOException {
        Path ledger = copyOfFirstRunLedger("agent-v2-runcap");
        Budget v2 = new Budget(new BigDecimal("0.50"), ledger, "v2", new BigDecimal("0.15"), 100, 300);
        v2.reserve(new BigDecimal("0.001"));
        v2.recordActual(new ClaudeChatClient.RouteUsage(1_000_000, 0, 0, 0));    // run has spent $0.10
        v2.reserve(new BigDecimal("0.0499"));                                       // 0.10 + 0.0499 <= 0.15: allowed
        assertThatThrownBy(() -> v2.reserve(new BigDecimal("0.0502")))             // 0.10 + 0.0502 > 0.15: refused
                .isInstanceOf(WaviAgentEvalHarness.BudgetExhausted.class).hasMessageContaining("this run's allowance");
        assertThat(v2.spent()).isEqualByComparingTo("0.12668007");
    }

    @Test
    void theLifetimeCapHoldsForEveryRunTogetherWhateverEachRunMayDo() throws IOException {
        Path ledger = copyOfFirstRunLedger("agent-v2-lifetime");
        // A run allowance as large as the cap cannot get past the lifetime total: $0.02668007 is already spent.
        Budget v2 = new Budget(new BigDecimal("0.50"), ledger, "v2", new BigDecimal("0.50"), 100, 300);
        v2.reserve(new BigDecimal("0.001"));
        v2.recordActual(new ClaudeChatClient.RouteUsage(4_700_000, 0, 0, 0));    // $0.47 more: lifetime $0.49668007
        assertThat(v2.spent()).isEqualByComparingTo("0.49668007");
        assertThatThrownBy(() -> v2.reserve(new BigDecimal("0.0034"))).isInstanceOf(WaviAgentEvalHarness.BudgetExhausted.class)
                .hasMessageContaining("remaining budget");
        v2.reserve(new BigDecimal("0.0033"));
        // A third run label inherits the same lifetime spend and cannot reset it.
        Budget v3 = new Budget(new BigDecimal("0.50"), ledger, "v3", new BigDecimal("0.15"), 100, 300);
        assertThat(v3.spent()).isEqualByComparingTo("0.49668007");
        assertThatThrownBy(() -> v3.reserve(new BigDecimal("0.0040"))).isInstanceOf(WaviAgentEvalHarness.BudgetExhausted.class);
    }

    @Test
    void aBadRunLabelOrRunAllowanceIsRefused() {
        assertThatThrownBy(() -> new Budget(new BigDecimal("0.50"), null, "no spaces", null, 100, 300))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Budget(new BigDecimal("0.50"), null, "v2", BigDecimal.ZERO, 100, 300))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theV2DryRunRunsAllThreePhasesOnACopyOfTheRealLedgerWithinBothCaps() throws IOException {
        Path ledger = copyOfFirstRunLedger("agent-v2-dryrun");
        Config config = new Config("stub", null, "sk-stub-not-a-key", new BigDecimal("0.50"), Set.of(Phase.A, Phase.B, Phase.C), OUT,
                ledger, WaviAgentEvalHarness.MAX_MESSAGES, WaviAgentEvalHarness.MAX_REQUESTS, "v2-dryrun", "v2",
                WaviAgentEvalHarness.DEFAULT_RUN_CAP_USD);
        List<PhaseReport> reports = WaviAgentEvalHarness.run(config);

        assertThat(reports).extracting(PhaseReport::phase).containsExactly(Phase.A, Phase.B, Phase.C);
        assertThat(reports).extracting(PhaseReport::stopReason).containsOnlyNulls();
        assertThat(reports).extracting(report -> report.results().size()).containsExactly(40, 30, 24);
        assertThat(reports).extracting(PhaseReport::outboundViolations).containsOnly(0);

        PhaseReport c = reports.get(2);
        List<String> notRight = new ArrayList<>();
        c.results().forEach(result -> {
            if (!result.toolOk()) notRight.add("tool " + result.question().id() + " " + result.toolsRequested());
            if (!result.pathOk()) notRight.add("path " + result.question().id() + " " + result.stage() + "/" + result.agentKind());
            if (!result.answerOk()) notRight.add("answer " + result.question().id() + " -> " + result.answer());
        });
        assertThat(notRight).as("phase C against the honest stub").isEmpty();

        Budget after = new Budget(new BigDecimal("0.50"), ledger, "v2", WaviAgentEvalHarness.DEFAULT_RUN_CAP_USD, 100, 300);
        assertThat(after.messages()).isEqualTo(94);
        assertThat(after.requests()).isBetween(94, 300);
        assertThat(after.runSpent()).isPositive().isLessThan(new BigDecimal("0.15"));
        assertThat(after.spent()).isEqualByComparingTo(new BigDecimal("0.02668007").add(after.runSpent()));
        assertThat(after.spent()).isLessThan(new BigDecimal("0.50"));
        // The first run's counters are untouched by the second.
        assertThat(new Budget(new BigDecimal("0.50"), ledger, 100, 300).messages()).isEqualTo(70);
        assertThat(OUT.resolve("agent-eval-C-stub-v2-dryrun.md")).exists();
    }
}
