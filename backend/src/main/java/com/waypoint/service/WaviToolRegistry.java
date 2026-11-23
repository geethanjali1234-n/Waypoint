package com.waypoint.service;

import com.waypoint.enums.UserRole;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The fixed catalog of Wavi tools. Names are unique, lower snake_case and checked at construction, so a
 * misconfigured catalog fails when it is built, never during a conversation.
 */
final class WaviToolRegistry {

    private final Map<String, WaviTool> tools;

    WaviToolRegistry(List<WaviTool> catalog) {
        Map<String, WaviTool> byName = new LinkedHashMap<>();
        for (WaviTool tool : catalog) {
            String name = tool.name();
            if (!isValidName(name)) throw new IllegalArgumentException("Invalid Wavi tool name: " + name);
            if (tool.allowedRoles() == null || tool.allowedRoles().isEmpty()) {
                throw new IllegalArgumentException("Wavi tool without roles: " + name);
            }
            if (byName.putIfAbsent(name, tool) != null) throw new IllegalArgumentException("Duplicate Wavi tool: " + name);
        }
        this.tools = Collections.unmodifiableMap(byName);
    }

    Optional<WaviTool> find(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(tools.get(name));
    }

    List<WaviTool> all() {
        return List.copyOf(tools.values());
    }

    /** The tools a router may offer this role: those that run for it (outside-role replies are not offered). */
    List<WaviTool> availableTo(UserRole role) {
        return tools.values().stream().filter(tool -> tool.allowedRoles().contains(role)).toList();
    }

    static boolean isValidName(String name) {
        if (name == null || name.length() < 3 || name.length() > 40) return false;
        if (name.charAt(0) < 'a' || name.charAt(0) > 'z') return false;
        for (char c : name.toCharArray()) {
            if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '_') return false;
        }
        return true;
    }
}
