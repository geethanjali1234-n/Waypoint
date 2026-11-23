package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrChatResponse;
import com.waypoint.enums.UserRole;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

/**
 * The router's contract with the structured-output API, and what Wavi does when the router provider fails. No model
 * is called. The API compiles the schema into a grammar and rejects one that is too large or uses an unsupported
 * keyword with HTTP 400 (platform.claude.com structured-outputs: "Schema complexity limits" and "JSON Schema
 * limitations"); each optional parameter roughly doubles part of the grammar.
 */
class WaviRouterProviderContractTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-11-12");
    /** Documented explicit limits for one request: optional parameters 24, union-type parameters 16. */
    private static final int DOCUMENTED_OPTIONAL_LIMIT = 24;
    private static final int DOCUMENTED_UNION_LIMIT = 16;

    @Test
    void claudeIsTheOnlyServingRouterProviderByDefault() {
        assertThat(WaviSemanticRouting.DEFAULT_PROVIDER_NAMES).containsExactly(WaviSemanticRouting.CLAUDE);
        assertThat(WaviSemanticRouting.DEFAULT_PROVIDER_CONFIGURATION).isEqualTo(WaviSemanticRouting.CLAUDE);
    }

    private static List<WaviTool> catalog() {
        WaviEvaluationWorld.Options options = new WaviEvaluationWorld.Options();
        options.toolLayer = true;
        return new WaviEvaluationWorld(WaviEvaluationWorld.HR, options).service.toolLayer().registry().all();
    }

    @SuppressWarnings("unchecked")
    private static void walk(Object node, int[] counts, List<String> unsupported) {
        if (node instanceof Map<?, ?> map) {
            Object required = map.get("required");
            Object properties = map.get("properties");
            if (properties instanceof Map<?, ?> props) {
                List<Object> req = required instanceof List<?> list ? (List<Object>) list : List.of();
                for (Object key : props.keySet()) if (!req.contains(key)) counts[0]++;
                for (Object value : props.values()) {
                    if (value instanceof Map<?, ?> prop && (prop.containsKey("anyOf") || prop.get("type") instanceof List)) counts[1]++;
                }
            }
            for (String keyword : List.of("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf",
                    "minLength", "maxLength", "pattern", "maxItems", "oneOf", "not")) {
                if (map.containsKey(keyword)) unsupported.add(keyword);
            }
            if (map.containsKey("format") && !List.of("date-time", "time", "date", "duration", "email", "hostname",
                    "uri", "ipv4", "ipv6", "uuid").contains(map.get("format"))) unsupported.add("format " + map.get("format"));
            if (map.containsKey("additionalProperties") && !Boolean.FALSE.equals(map.get("additionalProperties"))) {
                unsupported.add("additionalProperties");
            }
            map.values().forEach(value -> walk(value, counts, unsupported));
        } else if (node instanceof List<?> list) {
            list.forEach(value -> walk(value, counts, unsupported));
        }
    }

    @Test
    void theRouterSchemaHasNoOptionalParametersOrBranchesAndOnlySupportedKeywords() throws Exception {
        for (UserRole role : List.of(UserRole.EMPLOYEE, UserRole.HR, UserRole.COMPANY_ADMIN)) {
            WaviRouterPrompt.Request request = WaviRouterPrompt.build(catalog(), role, TODAY, null, "who reports to me");
            int[] counts = new int[2];
            List<String> unsupported = new java.util.ArrayList<>();
            walk(request.schema(), counts, unsupported);
            assertThat(unsupported).as("keywords the API rejects").isEmpty();
            // The provider only compiles the fixed envelope. Tool argument schemas remain in the prompt and are
            // enforced after parsing by the server-side policy gate.
            assertThat(counts[0]).as("optional parameters").isZero().isLessThanOrEqualTo(DOCUMENTED_OPTIONAL_LIMIT);
            assertThat(counts[1]).as("union-type parameters").isZero().isLessThanOrEqualTo(DOCUMENTED_UNION_LIMIT);
            assertThat(request.schema().toString()).doesNotContain("anyOf");
            assertThat(new ObjectMapper().writeValueAsString(request.schema())).doesNotContain("\"type\":[");
        }
    }

    @Test
    void everyToolsNotGivenValuesValidateExactlyAsAbsentArgumentsAndRealValuesStillValidate() {
        for (WaviTool tool : catalog()) {
            Map<String, Object> given = new LinkedHashMap<>();
            Map<String, Object> absent = new LinkedHashMap<>();
            for (WaviToolArguments.Spec spec : tool.schema().specs()) {
                Object sample = switch (spec.type()) {
                    case ENUM -> spec.values().getFirst();
                    case DATE -> TODAY.toString();
                    case DATE_RANGE -> TODAY + "/" + TODAY;
                    case MONTH -> TODAY.toString().substring(0, 7);
                    case INTEGER -> spec.min();
                };
                if (spec.required()) {
                    given.put(spec.name(), sample);
                    absent.put(spec.name(), sample);
                } else {
                    given.put(spec.name(), switch (spec.type()) {
                        case ENUM -> WaviToolArguments.Schema.NOT_GIVEN;
                        case DATE, DATE_RANGE, MONTH -> "";
                        case INTEGER -> 0;
                    });
                }
            }
            var withSentinels = tool.schema().validate(given, TODAY);
            var withoutThem = tool.schema().validate(absent, TODAY);
            assertThat(withSentinels.valid()).as(tool.name()).isEqualTo(withoutThem.valid());
            if (withSentinels.valid()) {
                assertThat(withSentinels.arguments().names()).as(tool.name()).isEqualTo(withoutThem.arguments().names());
            }
            // A sentinel is never a real value: an unknown enum value, a bad date or a 0 count of a required argument
            // is still rejected.
            for (WaviToolArguments.Spec spec : tool.schema().specs()) {
                if (spec.required() && spec.type() == WaviToolArguments.Type.ENUM) {
                    Map<String, Object> bad = new LinkedHashMap<>(absent);
                    bad.put(spec.name(), WaviToolArguments.Schema.NOT_GIVEN);
                    assertThat(tool.schema().validate(bad, TODAY).valid()).as(tool.name() + " " + spec.name()).isFalse();
                }
            }
        }
    }

    // --- Bad requests are classified, never retried, and their message is never logged -----------------------

    @Test
    void anInvalidRequestIsClassifiedIntoAFixedCode() {
        assertThat(ClaudeChatClient.invalidRequestCategory("Schema is too complex for compilation."))
                .isEqualTo("schema_too_complex");
        assertThat(ClaudeChatClient.invalidRequestCategory("Too many optional parameters in schema"))
                .isEqualTo("schema_optional_limit");
        assertThat(ClaudeChatClient.invalidRequestCategory("output_config.format: invalid")).isEqualTo("schema_invalid");
        assertThat(ClaudeChatClient.invalidRequestCategory("thinking.type disabled is not supported")).isEqualTo("thinking_or_effort");
        assertThat(ClaudeChatClient.invalidRequestCategory("model: not found")).isEqualTo("model");
        assertThat(ClaudeChatClient.invalidRequestCategory(null)).isEqualTo("other");
    }

    // --- When the router provider fails ------------------------------------------------------------------------

    private static WaviEvaluationWorld failingRouter(WaviEvaluationWorld.Person person) {
        WaviEvaluationWorld.Options options = new WaviEvaluationWorld.Options();
        options.semanticRouter = WaviEvaluationWorld.RouterBehaviour.ABSTAIN;
        WaviEvaluationWorld world = new WaviEvaluationWorld(person, options);
        doThrow(new ClaudeChatClient.ClaudeRequestRejectedException()).when(world.claude)
                .routeJsonWithUsage(anyString(), anyString(), anyMap(), anyInt(), anyString());
        return world;
    }

    private static String ask(WaviEvaluationWorld world, String message) {
        MyHrChatResponse response = world.service.chat(world.actor, message, List.of(), StampedHistory.SESSION);
        return response.message();
    }

    @Test
    void whenTheRouterFailsWaviNeverClaimsAQuestionIsOutOfScopeOrUnauthorizedJustBecauseItDidNotUnderstand() {
        for (WaviEvaluationWorld.Person person : List.of(WaviEvaluationWorld.EMPLOYEE, WaviEvaluationWorld.HR,
                WaviEvaluationWorld.ADMIN)) {
            String reply = ask(failingRouter(person), "who is under me");
            assertThat(reply).as(person.role().name()).isNotEqualTo(MyHrScopeGuard.OUT_OF_SCOPE_REPLY)
                    .doesNotContain(MyHrChatService.OTHER_PERSON_REPLY);
        }
        // An explicitly off-topic request is still refused as such.
        assertThat(ask(failingRouter(WaviEvaluationWorld.HR), "write a python script to sort a list"))
                .isEqualTo(MyHrScopeGuard.OUT_OF_SCOPE_REPLY);
    }

    @Test
    void exactRoutesAndAuthorizedPeopleLookupsKeepWorkingDuringARouterOutage() {
        WaviEvaluationWorld hr = failingRouter(WaviEvaluationWorld.HR);
        assertThat(ask(hr, "who reports to me?")).contains("Asha Rao", "Ravi Teja");
        assertThat(ask(hr, "Who is Ravi Teja?")).contains("QA Engineer");
        assertThat(ask(hr, "how many timesheets are waiting for my approval")).contains("3");
        WaviEvaluationWorld employee = failingRouter(WaviEvaluationWorld.EMPLOYEE);
        assertThat(ask(employee, "what is my employee id")).contains("WPT-1001");
        // Outage or not, an Employee never sees anyone else.
        assertThat(ask(employee, "Who is Ravi Teja?")).doesNotContain("QA Engineer");
    }
}
