package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrDailyHours;
import com.waypoint.enums.TimesheetStatus;
import com.waypoint.enums.UserRole;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WaviEnterpriseContractTest {
    @Test void everyCapabilityHasAnAuditableContractBoundToRealServices() throws Exception {
        var options = new WaviEvaluationWorld.Options(); options.toolLayer = true;
        var catalog = new WaviEvaluationWorld(WaviEvaluationWorld.HR, options).service.toolLayer().registry().all();
        List<Map<String, Object>> matrix = new ArrayList<>();
        for (var tool : catalog) {
            var contract = tool.contract(UserRole.HR);
            assertThat(contract.result()).isNotBlank();
            assertThat(contract.context()).isNotBlank();
            assertThat(contract.limitation()).isNotBlank();
            for (var binding : contract.backends()) {
                assertThat(Arrays.stream(binding.service().getDeclaredMethods()).map(java.lang.reflect.Method::getName))
                        .as(tool.name() + " backend binding").contains(binding.method());
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("capability", tool.name()); row.put("purpose", tool.description());
            row.put("roles", tool.allowedRoles()); row.put("parameters", tool.schema().toRouterJsonSchema());
            row.put("backend", contract.backends().stream().map(b -> b.service().getSimpleName() + "." + b.method()).toList());
            row.put("result", contract.result()); row.put("context", contract.context()); row.put("limitations", contract.limitation());
            row.put("security", List.of("VerifiedMyHrActor", "WaviRouterPolicyGate", "service authorization", "read-only"));
            row.put("coverage", List.of("WaviToolLayerTest", "WaviToolLayerParityTest", "WaviCapabilityRoutingMatrixTest",
                    "WaviContinuationTest", "WaviRouterProviderContractTest"));
            matrix.add(row);
        }
        assertThat(matrix).hasSize(catalog.size()).hasSizeGreaterThan(20);
        var target = java.nio.file.Path.of("target", "wavi-eval", "capability-contract-matrix.json");
        java.nio.file.Files.createDirectories(target.getParent());
        java.nio.file.Files.writeString(target, new tools.jackson.databind.ObjectMapper().writeValueAsString(matrix));
    }

    @Test void generatedDailyRecordsNeverBecomeAnAssumedUniformWeek() {
        var random = new Random(72941);
        var start = LocalDate.parse("2026-10-12");
        for (int sample = 0; sample < 40; sample++) {
            List<MyHrDailyHours.Entry> entries = new ArrayList<>();
            for (int offset = 0; offset < 5; offset++) entries.add(new MyHrDailyHours.Entry(start.plusDays(offset),
                    BigDecimal.valueOf(random.nextInt(33), 1)));
            var data = new MyHrDailyHours(start, start.plusDays(6), List.of(
                    new MyHrDailyHours.Submission(start, start.plusDays(6), TimesheetStatus.APPROVED, entries),
                    new MyHrDailyHours.Submission(start, start, TimesheetStatus.SUBMITTED,
                            List.of(new MyHrDailyHours.Entry(start, BigDecimal.TEN)))), false);
            String answer = WaviDailyHoursAnswer.render(data);
            for (var entry : entries) assertThat(answer).contains(entry.day() + ": " + entry.hours().stripTrailingZeros().toPlainString() + " hours");
            assertThat(answer).contains(start.plusDays(5) + ": no recorded hours entry", "SUBMITTED", "APPROVED", "not official payable totals");
            assertThat(answer).doesNotContain("every day", "40 hours");
        }
    }

    @Test void typedDailyDecisionReturnsActualEntriesForBothSelfServiceRolesAndNoAdminRead() {
        for (var person : List.of(WaviEvaluationWorld.EMPLOYEE, WaviEvaluationWorld.HR, WaviEvaluationWorld.ADMIN)) {
            for (String verb : List.of("Present", "DISPLAY", "inspect")) {
                var chat = new WaviConversationalReliabilityTest.Chat(person);
                var start = LocalDate.parse("2026-10-12");
                var data = new MyHrDailyHours(start, start.plusDays(6), List.of(new MyHrDailyHours.Submission(
                        start, start.plusDays(6), TimesheetStatus.APPROVED, List.of(new MyHrDailyHours.Entry(start, new BigDecimal("8")),
                        new MyHrDailyHours.Entry(start.plusDays(1), new BigDecimal("6"))))), false);
                lenient().when(chat.world.timesheets.getOwnDailyHours(anyString(), any(), any())).thenReturn(data);
                doReturn(WaviEvaluationWorld.routed("{\"decision\":{\"tool\":\"get_my_timesheets\",\"arguments\":{\"period\":\"WEEK\",\"date\":\"2026-10-12\",\"detail\":\"DAILY_HOURS\"}},\"confidence\":\"HIGH\",\"language\":\"english\"}"))
                        .when(chat.world.claude).routeJsonWithUsage(anyString(), anyString(), anyMap(), anyInt(), anyString());
                String answer = chat.ask(verb + " recorded work per calendar day");
                if (person.role() == UserRole.COMPANY_ADMIN) {
                    assertThat(answer).contains("don't submit timesheets");
                    verify(chat.world.timesheets, never()).getOwnDailyHours(anyString(), any(), any());
                } else {
                    assertThat(answer).contains("2026-10-12: 8 hours", "2026-10-13: 6 hours", "2026-10-14: no recorded hours entry");
                    verify(chat.world.timesheets).getOwnDailyHours(chat.world.actor.userId(), start, start.plusDays(6));
                }
            }
        }
    }

    @Test void resolvedRelativeDateIsIncludedWithoutRecordsOrRawHistory() {
        var options = new WaviEvaluationWorld.Options(); options.toolLayer = true;
        var catalog = new WaviEvaluationWorld(WaviEvaluationWorld.HR, options).service.toolLayer().registry().all();
        var state = new WaviDialogueStore.State("turn", WaviTools.GET_MY_TIMESHEETS,
                Map.of("period", "DAY", "relative", "TODAY", "detail", "HOURS"), Instant.parse("2026-11-05T23:59:00Z"),
                null, LocalDate.parse("2026-11-05"));
        var prompt = WaviRouterPrompt.build(catalog, UserRole.HR, LocalDate.parse("2026-11-06"), state, "which date?");
        assertThat(prompt.user()).contains("resolved period", "\"from\":\"2026-11-05\"", "\"to\":\"2026-11-05\"");
        assertThat(prompt.user()).doesNotContain("8 hours", "Hari Kumar");
    }

    @Test void topicClarificationDoesNotUseAnUnrelatedRecordMenu() {
        assertThat(WaviClarification.prompt(WaviTools.ClarificationTopic.TIMESHEETS, WaviTools.MissingSlot.PERIOD, UserRole.HR))
                .contains("date, week or month").doesNotContain("profile", "document");
        assertThat(WaviClarification.prompt(WaviTools.ClarificationTopic.TIMESHEETS, WaviTools.MissingSlot.SCOPE, UserRole.HR))
                .contains("own timesheets", "assigned for your review");
    }

    @Test void dateFollowUpSurvivesGreetingWithoutAnotherRecordLookup() {
        var chat = new WaviConversationalReliabilityTest.Chat(WaviEvaluationWorld.EMPLOYEE);
        chat.world.service.useClock(Clock.fixed(Instant.parse("2026-11-05T23:59:00Z"), ZoneOffset.UTC));
        doReturn(WaviEvaluationWorld.routed("{\"decision\":{\"tool\":\"get_my_timesheets\",\"arguments\":{\"period\":\"DAY\",\"relative\":\"TODAY\",\"detail\":\"HOURS\"}},\"confidence\":\"HIGH\",\"language\":\"english\"}"))
                .when(chat.world.claude).routeJsonWithUsage(anyString(), anyString(), anyMap(), anyInt(), anyString());
        assertThat(chat.ask("Summarize my recorded effort")).contains("8 hours");
        chat.world.service.useClock(Clock.fixed(Instant.parse("2026-11-06T00:01:00Z"), ZoneOffset.UTC));
        clearInvocations(chat.world.claude, chat.world.timesheets);
        assertThat(chat.ask("thanks")).isNotBlank();
        verify(chat.world.claude, never()).routeJsonWithUsage(anyString(), anyString(), anyMap(), anyInt(), anyString());
        doAnswer(call -> {
            assertThat((String) call.getArgument(1)).contains("resolved period", "\"from\":\"2026-11-05\"");
            return WaviEvaluationWorld.routed("{\"decision\":{\"tool\":\"get_my_timesheets\",\"arguments\":{\"period\":\"DAY\",\"date\":\"2026-11-05\",\"detail\":\"PERIOD\"}},\"confidence\":\"HIGH\",\"language\":\"english\"}");
        }).when(chat.world.claude).routeJsonWithUsage(anyString(), anyString(), anyMap(), anyInt(), anyString());
        assertThat(chat.ask("Which date did that cover?")).isEqualTo("The selected date is 2026-11-05.");
        verifyNoInteractions(chat.world.timesheets);
    }

    @Test void mistakenPolicyHandoffCannotGenerateAnAvailableOwnRecordAnswer() {
        var chat = new WaviConversationalReliabilityTest.Chat(WaviEvaluationWorld.EMPLOYEE);
        doReturn(WaviEvaluationWorld.routed("{\"decision\":{\"tool\":\"policy_question\",\"arguments\":{}},\"confidence\":\"HIGH\",\"language\":\"english\"}"))
                .when(chat.world.claude).routeJsonWithUsage(anyString(), anyString(), anyMap(), anyInt(), anyString());
        assertThat(chat.ask("Has my recent timesheet been accepted?")).contains("timesheet").doesNotContain("policy", "not sure");
        verify(chat.world.timesheets).getMostRecentStatusForUser(chat.world.actor.userId());
        verify(chat.world.claude, never()).chat(anyString(), anyList());
        verifyNoInteractions(chat.world.retrieval);
    }

    @Test void typedClarificationContextResolvesOwnershipWithoutRepeatingTheTopic() {
        var chat = new WaviConversationalReliabilityTest.Chat(WaviEvaluationWorld.HR);
        doReturn(WaviEvaluationWorld.routed("{\"decision\":{\"tool\":\"clarify\",\"arguments\":{\"topic\":\"TIMESHEETS\",\"missing\":\"SCOPE\"}},\"confidence\":\"HIGH\",\"language\":\"english\"}"))
                .when(chat.world.claude).routeJsonWithUsage(anyString(), anyString(), anyMap(), anyInt(), anyString());
        assertThat(chat.ask("Pull the work entries")).contains("own timesheets", "assigned for your review");
        clearInvocations(chat.world.claude);
        assertThat(chat.ask("my own")).contains("timesheet").doesNotContain("Do you mean", "Which one");
        verify(chat.world.timesheets).getMostRecentStatusForUser(chat.world.actor.userId());
        verify(chat.world.claude, never()).routeJsonWithUsage(anyString(), anyString(), anyMap(), anyInt(), anyString());
    }

    @Test void conversationArgumentsAreCanonicalValuesNotProviderSentinels() {
        var result = WaviTimesheetPeriod.SCHEMA.validate(Map.of("period", "DAY", "date", "2026-11-05",
                "month", "", "relative", "UNSPECIFIED", "weeksAgo", 0, "detail", "UNSPECIFIED"), LocalDate.parse("2026-11-05"));
        assertThat(result.valid()).isTrue();
        assertThat(result.arguments().routingValues()).containsExactlyInAnyOrderEntriesOf(Map.of("period", "DAY", "date", "2026-11-05"));
    }

    @Test void unavailableHoursMissingEntriesAndRecordedZeroAreDifferentAnswers() {
        var day = LocalDate.parse("2026-11-05");
        assertThat(WaviDailyHoursAnswer.render(null)).contains("temporarily unavailable");
        assertThat(WaviDailyHoursAnswer.render(new MyHrDailyHours(null, null, List.of(), false))).contains("don't see a timesheet");
        var data = new MyHrDailyHours(day, day.plusDays(2), List.of(new MyHrDailyHours.Submission(day, day.plusDays(2), null,
                List.of(new MyHrDailyHours.Entry(day, BigDecimal.ZERO), new MyHrDailyHours.Entry(day.plusDays(1), null)))), false);
        assertThat(WaviDailyHoursAnswer.render(data)).contains(day + ": 0 hours", "status not recorded",
                day.plusDays(1) + ": hours not recorded", day.plusDays(2) + ": no recorded hours entry");
    }
}
