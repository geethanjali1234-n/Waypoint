package com.waypoint.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

/** Opt-in live local inference, no credentials, database, real employee records, or paid API. */
@EnabledIfSystemProperty(named = "wavy.local.smoke", matches = "true")
class WavyLocalModelSmokeTest {
    @Test void localModelAnswersAndConsumesAReadOnlyToolResult() {
        var model = new WavyModelClient(RestClient.builder(), true, "", "qwen3:4b-instruct-2507-q4_K_M", "ollama", "http://127.0.0.1:11434");
        assertThat(model.isConfigured()).isTrue();
        var json = new ObjectMapper();
        var request = json.createObjectNode();
        request.put("system", "You are Wavy. Be brief. To answer a timesheet status question you MUST call timesheet_summary first. Never invent counts. Tools are read-only.");
        var messages = request.putArray("messages");
        messages.addObject().put("role", "user").put("content", "Check my current timesheet status using the tool.");
        request.set("tools", json.readTree("""
                [{"name":"timesheet_summary","description":"Read the signed-in user's current timesheet status counts.","input_schema":{"type":"object","properties":{},"required":[]}}]
                """));
        var response = model.complete(request);
        assertThat(response.path("stop_reason").asString()).isEqualTo("tool_use");
        var call = response.path("content").get(0);
        assertThat(call.path("name").asString()).isEqualTo("timesheet_summary");
        messages.addObject().put("role", "assistant").set("content", response.path("content"));
        var results = messages.addObject().put("role", "user").putArray("content");
        results.addObject().put("type", "tool_result").put("tool_use_id", call.path("id").asString())
                .put("content", "{\"testFixture\":true,\"countsByStatus\":{\"SUBMITTED\":2}}");
        var answer = model.complete(request);
        assertThat(answer.path("stop_reason").asString()).isEqualTo("end_turn");
        assertThat(answer.path("content").toString()).contains("2").doesNotContain("<think>");
    }
}
