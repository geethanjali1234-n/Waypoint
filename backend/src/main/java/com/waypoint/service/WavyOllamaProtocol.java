package com.waypoint.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.UUID;

/** Keeps provider details outside the authorized, read-only chat/tool orchestration. */
final class WavyOllamaProtocol {
    private static final ObjectMapper JSON = new ObjectMapper();
    private WavyOllamaProtocol() { }

    static ObjectNode encode(ObjectNode request, String model) {
        var body = JSON.createObjectNode();
        body.put("model", model);
        body.put("stream", false);
        body.put("think", false);
        body.put("keep_alive", "5m");
        var options = body.putObject("options");
        options.put("num_ctx", 8192);
        options.put("num_predict", 700);
        options.put("temperature", 0.3);
        var messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", request.path("system").asString(""));
        var toolNames = new HashMap<String, String>();
        for (JsonNode original : request.path("messages")) {
            JsonNode content = original.path("content");
            if (content.isString()) {
                messages.addObject().put("role", original.path("role").asString()).put("content", content.asString());
                continue;
            }
            var message = JSON.createObjectNode();
            message.put("role", original.path("role").asString());
            var text = new StringBuilder();
            var calls = JSON.createArrayNode();
            for (JsonNode block : content) {
                switch (block.path("type").asString("")) {
                    case "text" -> text.append(block.path("text").asString()).append('\n');
                    case "tool_use" -> {
                        String name = block.path("name").asString();
                        String id = block.path("id").asString();
                        toolNames.put(id, name);
                        var call = calls.addObject();
                        call.put("id", id);
                        var function = call.putObject("function");
                        function.put("name", name);
                        function.set("arguments", block.path("input"));
                    }
                    case "tool_result" -> {
                        String id = block.path("tool_use_id").asString();
                        String name = toolNames.get(id);
                        if (name == null) throw new IllegalArgumentException("Unmatched Wavy tool result");
                        messages.addObject().put("role", "tool").put("tool_name", name)
                                .put("tool_call_id", id).put("content", block.path("content").asString());
                    }
                    default -> { /* Never send private reasoning back into conversation text. */ }
                }
            }
            if (!text.isEmpty() || !calls.isEmpty()) {
                message.put("content", text.toString().trim());
                if (!calls.isEmpty()) message.set("tool_calls", calls);
                messages.add(message);
            }
        }
        var tools = body.putArray("tools");
        for (JsonNode tool : request.path("tools")) {
            var function = tools.addObject().put("type", "function").putObject("function");
            function.put("name", tool.path("name").asString());
            function.put("description", tool.path("description").asString());
            function.set("parameters", tool.path("input_schema"));
        }
        return body;
    }

    static JsonNode decode(JsonNode response) {
        if (response == null || !response.path("done").asBoolean() || response.has("error"))
            throw new IllegalStateException("Incomplete local model response");
        var result = JSON.createObjectNode();
        var content = result.putArray("content");
        JsonNode message = response.path("message");
        String text = message.path("content").asString("");
        if (!text.isBlank()) content.addObject().put("type", "text").put("text", text);
        boolean hasTools = false;
        for (JsonNode call : message.path("tool_calls")) {
            JsonNode function = call.path("function");
            JsonNode arguments = function.path("arguments");
            if (!arguments.isObject() || !function.path("name").isString())
                throw new IllegalStateException("Invalid local model tool call");
            var block = content.addObject();
            block.put("type", "tool_use").put("id", "local-" + UUID.randomUUID()).put("name", function.path("name").asString());
            block.set("input", arguments);
            hasTools = true;
        }
        result.put("stop_reason", "length".equals(response.path("done_reason").asString()) ? "max_tokens" : hasTools ? "tool_use" : "end_turn");
        return result;
    }
}
