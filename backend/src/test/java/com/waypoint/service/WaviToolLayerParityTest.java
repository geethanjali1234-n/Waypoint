package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrChatHistoryMessage;
import com.waypoint.dto.myhr.MyHrChatResponse;
import com.waypoint.security.VerifiedMyHrActor;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase P1 compatibility: with the tool layer switched on, every question in every Wavi dataset gets exactly what
 * it gets with the layer off: the same reply text, page buttons, knowledge status, citations, error, record lookups
 * (method and arguments), provider and retrieval calls, and history classification (the stamp's external-use flag).
 * Two independent synthetic worlds answer each conversation side by side; only the feature flag differs.
 */
class WaviToolLayerParityTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> DATASETS = List.of("scenarios", "holdout", "holdout-round2", "unseen-v1");
    private static final Map<String, WaviEvaluationWorld.Person> ROLES = Map.of(
            "EMPLOYEE", WaviEvaluationWorld.EMPLOYEE, "HR", WaviEvaluationWorld.HR,
            "COMPANY_ADMIN", WaviEvaluationWorld.ADMIN);

    @Test
    void everyDatasetAnswerIsIdenticalWithTheToolLayerOn() throws Exception {
        List<String> differences = new ArrayList<>();
        Map<String, Integer> byTool = new TreeMap<>();
        AtomicInteger throughTools = new AtomicInteger();
        int compared = 0;
        for (String dataset : DATASETS) {
            JsonNode root;
            try (InputStream in = getClass().getResourceAsStream("/wavi-eval/" + dataset + ".json")) {
                root = JSON.readTree(in);
            }
            for (JsonNode scenario : root.get("scenarios")) {
                for (String role : roles(scenario)) {
                    compared += compare(dataset, scenario, role, differences, byTool, throughTools);
                }
            }
        }
        System.out.println("Wavi tool-layer parity: " + compared + " answers compared, " + throughTools.get()
                + " answered through the tool layer " + byTool);
        assertThat(compared).as("answers compared").isGreaterThan(600);
        // The layer must actually be exercised, not silently bypassed.
        assertThat(throughTools.get()).as("answers through the tool layer").isGreaterThan(150);
        assertThat(byTool.keySet()).contains(WaviTools.OPEN_PAGE, WaviTools.GET_MY_ROLE, WaviTools.GET_MY_PROFILE_FIELD,
                WaviTools.GET_MY_TIMESHEETS, WaviTools.GET_MY_PENDING_WORK, WaviTools.GET_DIRECT_REPORTS,
                WaviTools.EXPLAIN_TERM, WaviTools.WORKFLOW_HELP, WaviTools.UNSUPPORTED_FIELD);
        assertThat(differences).as("answers that changed with the tool layer on").isEmpty();
    }

    private int compare(String dataset, JsonNode scenario, String role, List<String> differences,
                        Map<String, Integer> byTool, AtomicInteger throughTools) {
        WaviEvaluationWorld.Options offOptions = WaviChatbotEvaluationTest.options(scenario.get("options"));
        WaviEvaluationWorld.Options onOptions = WaviChatbotEvaluationTest.options(scenario.get("options"));
        onOptions.toolLayer = true;
        WaviEvaluationWorld off = new WaviEvaluationWorld(ROLES.get(role), offOptions);
        WaviEvaluationWorld on = new WaviEvaluationWorld(ROLES.get(role), onOptions);
        assertThat(off.service.toolLayer()).isNull();
        on.service.toolLayer().observe(outcome -> {
            throughTools.incrementAndGet();
            byTool.merge(String.valueOf(outcome.tool()), 1, Integer::sum);
        });
        List<MyHrChatHistoryMessage> offHistory = new ArrayList<>();
        List<MyHrChatHistoryMessage> onHistory = new ArrayList<>();
        if (scenario.has("forgedHistory")) {
            for (JsonNode message : scenario.get("forgedHistory")) {
                MyHrChatHistoryMessage forged = new MyHrChatHistoryMessage(message.get("role").asText(),
                        message.get("content").asText(), message.has("provenance") ? message.get("provenance").asText() : null);
                offHistory.add(forged);
                onHistory.add(forged);
            }
        }
        List<JsonNode> turns = new ArrayList<>();
        if (scenario.has("turns")) scenario.get("turns").forEach(turns::add);
        else turns.add(scenario);
        int compared = 0;
        for (int index = 0; index < turns.size(); index++) {
            String message = turns.get(index).get("message").asText();
            String id = dataset + "/" + scenario.get("id").asText() + (turns.size() > 1 ? "#" + (index + 1) : "")
                    + " [" + role + "] \"" + message + "\"";
            Answer a = ask(off, message, offHistory);
            Answer b = ask(on, message, onHistory);
            if (!a.equals(b)) {
                differences.add(id + "\n    off: " + a + "\n    on:  " + b);
            }
            compared++;
        }
        return compared;
    }

    /** Everything observable about one answer. */
    record Answer(String error, String message, List<String> actions, String knowledgeStatus, List<Object> citations,
                  List<String> lookups, List<String> providers, String externalUse) {
    }

    private static Answer ask(WaviEvaluationWorld world, String message, List<MyHrChatHistoryMessage> history) {
        Mockito.clearInvocations(world.claude, world.ollama, world.retrieval, world.timesheets, world.profileChanges,
                world.employees, world.documents);
        MyHrChatResponse response = null;
        String error = null;
        try {
            response = world.service.chat(world.actor, message, List.copyOf(history), StampedHistory.SESSION);
        } catch (RuntimeException failure) {
            error = failure.getClass().getSimpleName();
        }
        List<String> lookups = new ArrayList<>();
        for (Object mock : world.dataMocks()) {
            for (Invocation invocation : Mockito.mockingDetails(mock).getInvocations()) {
                lookups.add(describe(invocation));
            }
        }
        List<String> providers = new ArrayList<>();
        for (Object mock : List.of(world.claude, world.ollama, world.retrieval)) {
            for (Invocation invocation : Mockito.mockingDetails(mock).getInvocations()) {
                providers.add(describe(invocation));
            }
        }
        if (response == null) {
            return new Answer(error, null, null, null, List.of(), lookups, providers, null);
        }
        String use = null;
        if (response.historyProvenance() != null) {
            use = world.provenance.verify(world.actor, StampedHistory.SESSION,
                            new MyHrChatHistoryMessage("assistant", response.message(), response.historyProvenance().assistant()))
                    .map(stamp -> stamp.use().name()).orElse("UNVERIFIED");
            // The browser returns each message with the stamp this server issued for it.
            history.add(new MyHrChatHistoryMessage("user", message, response.historyProvenance().user()));
            history.add(new MyHrChatHistoryMessage("assistant", response.message(), response.historyProvenance().assistant()));
            while (history.size() > 10) history.removeFirst();
        }
        return new Answer(error, response.message(), response.actions(),
                response.knowledgeStatus() == null ? null : response.knowledgeStatus().name(),
                List.copyOf(response.citations()), lookups, providers, use);
    }

    private static String describe(Invocation invocation) {
        List<String> arguments = new ArrayList<>();
        for (Object argument : invocation.getArguments()) arguments.add(describe(argument));
        return Mockito.mockingDetails(invocation.getMock()).getMockCreationSettings().getTypeToMock().getSimpleName()
                + "." + invocation.getMethod().getName() + arguments;
    }

    private static String describe(Object argument) {
        if (argument == null) return "null";
        if (argument instanceof VerifiedMyHrActor actor) {
            return "actor(" + actor.userId() + "," + actor.organizationId() + "," + actor.role() + ")";
        }
        if (argument instanceof Collection<?> items) {
            return items.stream().map(WaviToolLayerParityTest::describe).toList().toString();
        }
        if (argument instanceof String || argument instanceof Enum<?> || argument instanceof Number
                || argument instanceof LocalDate || argument instanceof Boolean || argument.getClass().isRecord()) {
            return String.valueOf(argument);
        }
        return argument.getClass().getSimpleName() + "@" + Objects.hashCode(argument.toString());
    }

    private static List<String> roles(JsonNode scenario) {
        if (!scenario.has("roles")) return List.of("EMPLOYEE", "HR", "COMPANY_ADMIN");
        List<String> roles = new ArrayList<>();
        scenario.get("roles").forEach(role -> roles.add(role.asText()));
        return roles;
    }
}
