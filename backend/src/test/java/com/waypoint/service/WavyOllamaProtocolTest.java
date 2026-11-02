package com.waypoint.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class WavyOllamaProtocolTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void preservesHistoryToolIdsArgumentsAndErrorResultsAcrossRounds() {
        var request = json.createObjectNode();
        request.put("system", "Trusted system rules");
        request.set("messages", json.readTree("""
                [{"role":"user","content":"Check my timesheets"},
                 {"role":"assistant","content":[{"type":"tool_use","id":"call-1","name":"timesheet_summary","input":{}}]},
                 {"role":"user","content":[{"type":"tool_result","tool_use_id":"call-1","is_error":true,"content":"Not permitted. Do not infer missing data."}]}]
                """));
        var encoded = WavyOllamaProtocol.encode(request, "local-model");
        assertThat(encoded.path("messages").size()).isEqualTo(4);
        assertThat(encoded.at("/messages/0/role").asString()).isEqualTo("system");
        assertThat(encoded.at("/messages/2/tool_calls/0/function/name").asString()).isEqualTo("timesheet_summary");
        assertThat(encoded.at("/messages/3/role").asString()).isEqualTo("tool");
        assertThat(encoded.at("/messages/3/tool_call_id").asString()).isEqualTo("call-1");
        assertThat(encoded.at("/messages/3/content").asString()).contains("Not permitted");
        assertThat(encoded.at("/options/num_ctx").asInt()).isEqualTo(8192);
        assertThat(encoded.path("think").asBoolean()).isFalse();
    }

    @Test void discardsPrivateReasoningAndRejectsTruncatedOrInvalidResponses() {
        var decoded = WavyOllamaProtocol.decode(json.readTree("""
                {"done":true,"done_reason":"stop","message":{"content":"Here to help.","thinking":"private reasoning"}}
                """));
        assertThat(decoded.toString()).doesNotContain("private reasoning");
        assertThat(decoded.path("stop_reason").asString()).isEqualTo("end_turn");
        assertThat(WavyOllamaProtocol.decode(json.readTree("{\"done\":true,\"done_reason\":\"length\",\"message\":{\"content\":\"partial\"}}")).path("stop_reason").asString()).isEqualTo("max_tokens");
        assertThatThrownBy(() -> WavyOllamaProtocol.decode(json.readTree("{\"done\":false}"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> WavyOllamaProtocol.decode(json.readTree("{\"done\":true,\"message\":{\"tool_calls\":[{\"function\":{\"name\":\"my_team\",\"arguments\":\"invalid\"}}]}}"))).isInstanceOf(IllegalStateException.class);
    }
}
