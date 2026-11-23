package com.waypoint.service;

import com.waypoint.enums.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * OFFLINE: the real Spring property binding of the agent's feature flag, organization approval, person-name approval
 * and limits (no live context, no database): what an operator can and cannot turn on by configuration.
 */
class WaviAgentConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> {
                // The same property conversion Spring Boot applies (durations such as 20s); the model client is a
                // pre-built stand-in, never wired or called.
                context.getBeanFactory().setConversionService(
                        org.springframework.boot.convert.ApplicationConversionService.getSharedInstance());
                context.getBeanFactory().registerSingleton("claudeChatClient", mock(ClaudeChatClient.class));
            })
            .withUserConfiguration(WaviAgentLoop.class, ExternalRecordProcessingAuthorization.Configured.class);

    private static WaviAgentLoop.Outcome hrQueueList(WaviAgentLoop loop, ExternalRecordProcessingAuthorization unused) {
        var world = new WaviEvaluationWorld(WaviEvaluationWorld.HR, new WaviEvaluationWorld.Options());
        world.service.useAgentLoop(loop);
        return loop.run(world.actor, "who is waiting on me?", List.of(), null, LocalDate.of(2026, 11, 18),
                world.service.toolLayer());
    }

    @Test
    void withNoConfigurationTheAgentIsOffAndNothingIsApproved() {
        runner.run(context -> {
            WaviAgentLoop loop = context.getBean(WaviAgentLoop.class);
            assertThat(loop.enabled()).isFalse();
            var authorization = context.getBean(ExternalRecordProcessingAuthorization.class);
            assertThat(authorization.permits(WaviEvaluationWorld.ORG, UserRole.HR)).isFalse();
            assertThat(authorization.permitsPersonNames(WaviEvaluationWorld.ORG, UserRole.HR)).isFalse();
        });
    }

    @Test
    void enablingTheFlagAloneStillSendsNothingBecauseNoOrganizationIsApproved() {
        runner.withPropertyValues("waypoint.myhr.agent.enabled=true").run(context -> {
            WaviAgentLoop loop = context.getBean(WaviAgentLoop.class);
            assertThat(loop.enabled()).isTrue();
            assertThat(hrQueueList(loop, null).reason()).isEqualTo("not-authorized");
        });
    }

    @Test
    void anApprovedOrganizationAloneGetsRecordsButNeverNamesAndAnotherOrganizationGetsNothing() {
        runner.withPropertyValues("waypoint.myhr.agent.enabled=true",
                "waypoint.myhr.agent.approved-organization-ids=" + WaviEvaluationWorld.ORG).run(context -> {
            var authorization = context.getBean(ExternalRecordProcessingAuthorization.class);
            assertThat(authorization.permits(WaviEvaluationWorld.ORG, UserRole.EMPLOYEE)).isTrue();
            assertThat(authorization.permitsPersonNames(WaviEvaluationWorld.ORG, UserRole.EMPLOYEE)).isFalse();
            assertThat(authorization.permits(WaviEvaluationWorld.OTHER_ORG, UserRole.EMPLOYEE)).isFalse();
            assertThat(WaviExternalDisclosure.sendable(WaviExternalDisclosure.Level.NAMED, WaviEvaluationWorld.ORG,
                    UserRole.HR, authorization)).isFalse();
            assertThat(WaviExternalDisclosure.sendable(WaviExternalDisclosure.Level.RECORDS, WaviEvaluationWorld.ORG,
                    UserRole.HR, authorization)).isTrue();
        });
    }

    @Test
    void aNameApprovalWithoutTheRecordApprovalPermitsNothing() {
        runner.withPropertyValues("waypoint.myhr.agent.approved-person-name-organization-ids=" + WaviEvaluationWorld.ORG)
                .run(context -> {
                    var authorization = context.getBean(ExternalRecordProcessingAuthorization.class);
                    assertThat(authorization.permits(WaviEvaluationWorld.ORG, UserRole.HR)).isFalse();
                    assertThat(authorization.permitsPersonNames(WaviEvaluationWorld.ORG, UserRole.HR)).isFalse();
                });
    }

    @Test
    void bothApprovalsTogetherPermitNamesForThatOrganizationOnly() {
        runner.withPropertyValues("waypoint.myhr.agent.approved-organization-ids=" + WaviEvaluationWorld.ORG,
                "waypoint.myhr.agent.approved-person-name-organization-ids=" + WaviEvaluationWorld.ORG).run(context -> {
            var authorization = context.getBean(ExternalRecordProcessingAuthorization.class);
            assertThat(authorization.permitsPersonNames(WaviEvaluationWorld.ORG, UserRole.HR)).isTrue();
            assertThat(authorization.permitsPersonNames(WaviEvaluationWorld.OTHER_ORG, UserRole.HR)).isFalse();
        });
    }

    @Test
    void limitsOutsideTheSupportedRangeFailAtStartupNotDuringAConversation() {
        runner.withPropertyValues("waypoint.myhr.agent.max-model-calls=50").run(context ->
                assertThat(context).hasFailed());
        runner.withPropertyValues("waypoint.myhr.agent.max-output-tokens=10").run(context ->
                assertThat(context).hasFailed());
        runner.withPropertyValues("waypoint.myhr.agent.per-user-per-hour=0").run(context ->
                assertThat(context).hasFailed());
    }

    @Test
    void theDocumentedDefaultsAreTheSmallOnes() {
        var defaults = WaviAgentLoop.Settings.defaults(false);
        assertThat(defaults.enabled()).isFalse();
        assertThat(defaults.maxModelCalls()).isEqualTo(4);
        assertThat(defaults.maxToolCalls()).isEqualTo(5);
        assertThat(defaults.maxOutputTokens()).isEqualTo(1024);
        assertThat(defaults.maxTotalTokens()).isEqualTo(24_000);
        assertThat(defaults.perUserPerHour()).isEqualTo(30);
        assertThat(Map.of("deadline", defaults.deadline().toSeconds())).containsEntry("deadline", 20L);
    }
}
