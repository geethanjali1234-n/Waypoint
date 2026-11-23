package com.waypoint.service;

import com.waypoint.service.WaviAgentEvalHarness.Config;
import com.waypoint.service.WaviAgentEvalHarness.PhaseReport;
import com.waypoint.service.WaviAgentEvalQuestions.Phase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LIVE evaluation of the Wavi agent with the REAL Claude API. Opt-in and never part of the normal suite (the name does
 * not end in Test). Synthetic data only: the services are the mocked evaluation world; no organization is enabled for
 * external processing. It needs, together:
 * <ul>
 *   <li>{@code -Dwavi.live-agent-eval=approved}</li>
 *   <li>an API key in the environment ({@code CLAUDE_API_KEY} or {@code ANTHROPIC_API_KEY}); never printed or stored</li>
 *   <li>{@code -Dwavi.live.phase=A}, {@code B}, {@code C} or {@code ALL}, and {@code -Dwavi.live.run=<label>} (v2 for the
 *       regression run): message and request counters belong to a run</li>
 *   <li>{@code -Dwavi.live.max-spend-usd=<amount>}, at most 0.50: the LIFETIME cap over every run, enforced from a
 *       persisted ledger that already holds the first run's spend (backend/.wavi-agent-live-ledger.json, outside target so a
 *       clean cannot reset it); {@code -Dwavi.live.run-max-usd} (default 0.15) is this run's own allowance on top of that</li>
 * </ul>
 * See docs/wavi/run-live-agent-eval.ps1.
 */
@EnabledIfSystemProperty(named = "wavi.live-agent-eval", matches = "approved")
class WaviLiveAgentEvaluation {

    static final Path LEDGER = Path.of(".wavi-agent-live-ledger.json");
    static final String API_URL = "https://api.anthropic.com/v1/messages";

    @Test
    void runsTheApprovedPhaseAgainstTheRealModelWithinTheCombinedCap() {
        String key = System.getenv("CLAUDE_API_KEY");
        if (key == null || key.isBlank()) key = System.getenv("ANTHROPIC_API_KEY");
        assertThat(key).as("an API key in CLAUDE_API_KEY or ANTHROPIC_API_KEY").isNotBlank();

        String phaseProperty = System.getProperty("wavi.live.phase", "");
        assertThat(phaseProperty).as("-Dwavi.live.phase").isIn("A", "B", "C", "ALL");
        String run = System.getProperty("wavi.live.run", "");
        assertThat(run).as("-Dwavi.live.run must be given explicitly (for example v2): message and request counters belong to a run, spend does not")
                .matches("[A-Za-z0-9._-]{1,20}");
        String spend = System.getProperty("wavi.live.max-spend-usd", "");
        assertThat(spend).as("-Dwavi.live.max-spend-usd must be given explicitly").isNotBlank();
        BigDecimal cap = new BigDecimal(spend);
        assertThat(cap).as("lifetime cap").isPositive().isLessThanOrEqualTo(WaviAgentEvalHarness.HARD_CAP_USD);
        BigDecimal runCap = new BigDecimal(System.getProperty("wavi.live.run-max-usd", WaviAgentEvalHarness.DEFAULT_RUN_CAP_USD.toPlainString()));
        assertThat(runCap).as("this run's own allowance").isPositive().isLessThanOrEqualTo(cap);
        assertThat(System.getProperty("wavi.live.model", WaviAgentEvalHarness.MODEL)).as("only the approved model")
                .isEqualTo(WaviAgentEvalHarness.MODEL);

        Set<Phase> phases = phaseProperty.equals("ALL") ? Set.of(Phase.A, Phase.B, Phase.C) : Set.of(Phase.valueOf(phaseProperty));
        Config config = new Config("live", API_URL, key, cap, phases, Path.of("target", "wavi-eval"), LEDGER,
                WaviAgentEvalHarness.MAX_MESSAGES, WaviAgentEvalHarness.MAX_REQUESTS, run.equals("v1") ? "" : run, run, runCap);
        List<PhaseReport> reports = WaviAgentEvalHarness.run(config);

        for (PhaseReport report : reports) {
            assertThat(report.stopReason()).as("phase " + report.phase() + " stopped").isNull();
            assertThat(report.outboundViolations()).isZero();
        }
    }
}
