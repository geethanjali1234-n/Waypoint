package com.waypoint.service;

import com.waypoint.enums.UserRole;

import java.util.Map;
import java.util.Set;

/**
 * The single place that decides what part of a tool result may be sent to an external AI provider. Permission to see
 * data in Waypoint is not permission to transmit it to Anthropic, so every result is classified by the tool and the
 * (validated) field it answered, never by what the model asks for:
 *
 * <ul>
 *   <li>{@link Level#PUBLIC}: fixed, role-wide Waypoint text; always sendable.</li>
 *   <li>{@link Level#RECORDS}: facts about work (periods, statuses, hours, counts) with no person's name and no
 *       identifier; sendable once the organization authorized external record processing, and always scrubbed by
 *       {@link WaviDataMinimizer}.</li>
 *   <li>{@link Level#NAMED}: the answer names people (a manager, a team member, who is waiting). Sendable only when the
 *       organization ALSO authorized sending person names
 *       ({@link ExternalRecordProcessingAuthorization#permitsPersonNames}).</li>
 *   <li>{@link Level#LOCAL_ONLY}: identifiers and contact details (employee ID, work e-mail, whole-profile summaries).
 *       Never sent; the existing deterministic reply is shown to the user as it is.</li>
 * </ul>
 *
 * Fail closed: a tool or field this table does not know is LOCAL_ONLY, and a test asserts every registered tool is
 * classified. The table is data about Waypoint's own tools and fields, not a reading of the user's wording.
 */
final class WaviExternalDisclosure {

    enum Level { PUBLIC, RECORDS, NAMED, LOCAL_ONLY }

    private WaviExternalDisclosure() {
    }

    private static final Set<String> PUBLIC_TOOLS = Set.of(WaviAgentTools.KNOWLEDGE_BASE_HELP, WaviTools.OPEN_PAGE, WaviTools.WORKFLOW_HELP,
            WaviTools.EXPLAIN_TERM, WaviTools.GET_PAYROLL_SCHEDULE, WaviTools.UNSUPPORTED_FIELD,
            WaviTools.UNSUPPORTED_REQUEST, WaviTools.GENERAL_HELP, WaviTools.POLICY_QUESTION, WaviTools.CLARIFY,
            WaviTools.OUT_OF_SCOPE);
    private static final Set<String> RECORD_TOOLS = Set.of(WaviAgentTools.CHECK_PERSON_IN_SCOPE, WaviAgentTools.LIST_POLICY_CATALOG,
            WaviAgentTools.GET_WORK_TOTALS,
            WaviTools.GET_MY_TIMESHEETS, WaviTools.LIST_MY_TIMESHEETS,
            WaviTools.GET_MY_REQUESTS, WaviTools.LIST_MY_REQUESTS, WaviTools.GET_PEOPLE_SUMMARY, WaviTools.GET_MY_ROLE);
    private static final Set<String> NAMED_TOOLS = Set.of(WaviAgentTools.LIST_PEOPLE, WaviTools.GET_DIRECT_REPORTS, WaviTools.GET_TEAM_TIMESHEETS,
            WaviTools.GET_MY_REVIEW_HISTORY, WaviTools.FIND_PEOPLE, WaviTools.GET_PAYROLL_READINESS);

    /** Per tool, the argument values (of its "field" argument) that put the answer in a stricter level. */
    private static final Map<String, Map<String, Level>> FIELD_LEVELS = Map.of(
            WaviTools.GET_MY_PROFILE_FIELD, Map.of(
                    "NAME", Level.NAMED, "FIRST_NAME", Level.NAMED, "LAST_NAME", Level.NAMED,
                    "REPORTING_MANAGER", Level.NAMED,
                    "EMPLOYEE_ID", Level.LOCAL_ONLY, "WORK_EMAIL", Level.LOCAL_ONLY, "PROFILE", Level.LOCAL_ONLY,
                    "JOB_TITLE", Level.RECORDS, "DEPARTMENT", Level.RECORDS),
            WaviTools.GET_TEAM_MEMBER, Map.of(
                    "JOB_TITLE", Level.NAMED, "ROLE", Level.NAMED, "REPORTING_MANAGER", Level.NAMED,
                    "EMPLOYEE_ID", Level.LOCAL_ONLY, "WORK_EMAIL", Level.LOCAL_ONLY, "SUMMARY", Level.LOCAL_ONLY));

    /**
     * @param tool      the registry name
     * @param arguments the VALIDATED arguments the tool ran with (never the model's raw input)
     */
    static Level classify(String tool, Map<String, Object> arguments, UserRole role) {
        if (tool == null) return Level.LOCAL_ONLY;
        if (PUBLIC_TOOLS.contains(tool)) return Level.PUBLIC;
        if (RECORD_TOOLS.contains(tool)) return Level.RECORDS;
        if (NAMED_TOOLS.contains(tool)) return Level.NAMED;
        Object field = arguments == null ? null : arguments.get("field");
        Map<String, Level> byField = FIELD_LEVELS.get(tool);
        if (byField != null) {
            return field == null ? Level.LOCAL_ONLY : byField.getOrDefault(String.valueOf(field), Level.LOCAL_ONLY);
        }
        if (WaviTools.GET_REVIEW_QUEUE.equals(tool)) {
            return arguments != null && "LIST".equals(String.valueOf(arguments.get("detail"))) ? Level.NAMED : Level.RECORDS;
        }
        if (WaviTools.GET_MY_PENDING_WORK.equals(tool)) {
            // A reviewer's pending work names the people waiting; an employee's names only themselves.
            return role == UserRole.EMPLOYEE ? Level.RECORDS : Level.NAMED;
        }
        return Level.LOCAL_ONLY;
    }

    /**
     * Whether a result of this level may go to the provider for this organization and role, with person handles
     * unavailable (the older, stricter reading: names then need their own approval).
     */
    static boolean sendable(Level level, String organizationId, UserRole role, ExternalRecordProcessingAuthorization authorization) {
        return sendable(level, organizationId, role, authorization, false);
    }

    /**
     * @param handlesActive person names in the result are replaced by opaque handles before it is sent
     *                      ({@link WaviPersonHandles}). Handles keep the NAMES (and what derives from them) inside
     *                      Waypoint, but the records behind them remain personal data under the organization's record
     *                      approval, so NAMED results still need that approval. Without handles, names need their own.
     */
    static boolean sendable(Level level, String organizationId, UserRole role, ExternalRecordProcessingAuthorization authorization,
                            boolean handlesActive) {
        return switch (level) {
            case PUBLIC -> true;
            case RECORDS -> authorization.permits(organizationId, role);
            case NAMED -> authorization.permits(organizationId, role)
                    && (handlesActive || authorization.permitsPersonNames(organizationId, role));
            case LOCAL_ONLY -> false;
        };
    }
}
