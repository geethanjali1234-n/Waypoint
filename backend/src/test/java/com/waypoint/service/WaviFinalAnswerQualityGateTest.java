package com.waypoint.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A release gate for complete synthetic chat answers. It runs the real chat service with verified synthetic actors,
 * mocked business services and provider-unavailable fallback, so a correct tool name alone cannot pass: the expected
 * data, filtering, response text and authorization outcome must all match.
 */
class WaviFinalAnswerQualityGateTest {

    /** Recorded baseline from sealed unseen-v1 before this gate existed; a floor is not a release-readiness target. */
    private static final long SEALED_UNSEEN_V1_FINAL_ANSWER_FLOOR = 128;

    @Test
    void supportedCapabilityAnswersAndIndependentHoldoutsMustBeFullyCorrect() throws IOException {
        WaviChatbotEvaluationTest evaluation = new WaviChatbotEvaluationTest();
        List<String> failures = new ArrayList<>();
        for (String dataset : List.of("scenarios", "holdout", "holdout-round2")) {
            for (WaviChatbotEvaluationTest.Result result : evaluation.evaluate("/wavi-eval/" + dataset + ".json")) {
                if (!result.passed()) {
                    failures.add(dataset + "/" + result.scenario() + " [" + result.role() + "] " + result.failures());
                }
            }
        }
        assertThat(failures).as("complete final-answer quality failures").isEmpty();
    }

    @Test
    void sealedUnseenFinalAnswersCannotRegressBelowTheRecordedBaseline() throws Exception {
        assertThat(WaviChatbotEvaluationTest.sha256OfNormalized("/wavi-eval/unseen-v1.json"))
                .isEqualTo(WaviChatbotEvaluationTest.sealedHash("/wavi-eval/unseen-v1.seal"));
        List<WaviChatbotEvaluationTest.Result> results = new WaviChatbotEvaluationTest()
                .evaluate("/wavi-eval/unseen-v1.json");
        long fullyCorrect = results.stream().filter(WaviChatbotEvaluationTest.Result::passed).count();
        assertThat(results).hasSize(178);
        assertThat(fullyCorrect).as("sealed unseen-v1 final-answer correctness").isGreaterThanOrEqualTo(
                SEALED_UNSEEN_V1_FINAL_ANSWER_FLOOR);
        assertThat(results.stream().filter(result -> !result.failures().get("authorization").isEmpty()).toList())
                .as("sealed unseen-v1 authorization failures").isEmpty();
    }
}
