package com.waypoint.service;

import com.waypoint.enums.UserRole;
import java.util.List;

/** User-facing capability descriptions, gated by the same catalog roles as execution. */
final class WaviCapabilities {
    static final String WORK_CONTEXT_NOTE = " This profile lookup shows your job title, but not your assigned duties. "
            + "Check your approved offer letter or ask your reporting manager about responsibilities. "
            + "You can ask me separately about pending tasks or submitted work.";
    static final String SCOPE = "HR can read permitted work details of active direct reports, assigned review queues "
            + "and available review history. Company Admin reads are organization-scoped. Employees read their own "
            + "records. The backend checks every lookup; conversation text never grants access. ";

    private WaviCapabilities() {}

    static String describe(List<WaviTool> tools, UserRole role) {
        StringBuilder text = new StringBuilder();
        for (WaviTool tool : tools) {
            if (!tool.allowedRoles().contains(role)) continue;
            String description = tool.contract(role).introduction();
            if (description != null) text.append("- ").append(description).append('\n');
        }
        return text.toString();
    }
}
