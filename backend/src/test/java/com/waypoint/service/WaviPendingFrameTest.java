package com.waypoint.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A pending-work question in a "do I have" frame is the same question as without the frame. The frame is handled in
 * code, so the frozen pattern gains no new wording (WaviRegexFreezeTest).
 */
class WaviPendingFrameTest {

    @Test
    void theDoIHaveFrameIsRecognisedForEveryUnframedFormAndNothingElse() {
        for (String unframed : List.of("anything pending", "something waiting for me", "any pending approvals")) {
            assertThat(MyHrConversationIntents.isPendingOverviewQuestion(unframed)).as(unframed).isTrue();
            assertThat(MyHrConversationIntents.isPendingOverviewQuestion("do i have " + unframed)).as(unframed).isTrue();
        }
        assertThat(MyHrConversationIntents.isPendingOverviewQuestion("do i have a manager")).isFalse();
        assertThat(MyHrConversationIntents.isPendingOverviewQuestion("do i have ")).isFalse();
        assertThat(MyHrConversationIntents.isPendingOverviewQuestion(null)).isFalse();
    }

    @Test
    void anEmployeeAskingDoIHaveAnythingPendingGetsTheirOwnOpenItems() {
        WaviEvaluationWorld world = new WaviEvaluationWorld(WaviEvaluationWorld.EMPLOYEE, new WaviEvaluationWorld.Options());
        String reply = world.service.chat(world.actor, "do I have anything pending?", List.of(), StampedHistory.SESSION)
                .message();
        assertThat(reply).startsWith("Employees don't approve anything in Waypoint");
    }
}
