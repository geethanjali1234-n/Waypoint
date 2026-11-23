package com.waypoint.service;

import com.waypoint.enums.UserRole;
import com.waypoint.security.VerifiedMyHrActor;

import java.time.LocalDate;
import java.util.Set;

/**
 * One read-only capability Wavi can use: a fixed Java operation over the existing actor-scoped services, with a
 * strict argument schema. A tool never receives identity from its arguments. It is given the verified actor by
 * {@link WaviToolLayer}, only after {@link WaviRouterPolicyGate} has checked its role and arguments. No tool
 * changes data.
 */
interface WaviTool {

    /** What a tool's answer may contain. */
    enum DataClass {
        /** The verified user's own records. */
        PERSONAL,
        /** Counts or lists scoped by the services to the reviewer's assignments or organization. */
        ORG_AGGREGATE,
        /** Fixed facts about Waypoint itself, the same for everyone in a role. */
        PUBLIC_APP,
        /** A company-policy question, answered by the existing grounded retrieval pipeline. */
        POLICY
    }

    /**
     * Marker: a tool offered only to the tool-use agent. The semantic router never sees it, so the router's prompt and
     * its approved spend ceiling are unchanged.
     */
    interface AgentOnly {
    }

    /** What a tool needs from the request besides the actor: today's date, from the service's clock. */
    /**
     * @param message the verified user's own message, held by the server. Tools that name a person resolve it only
     *                against records the services already scope to the actor; a model never supplies a name or id.
     */
    record Context(LocalDate today, String message) {
        Context(LocalDate today) {
            this(today, null);
        }
    }

    /** Stable snake_case name; also the router-facing name. */
    String name();

    /** Router-facing description of what the tool answers. */
    String description();

    default WaviCapabilityContract contract(UserRole role) {
        return WaviCapabilityContract.forTool(this, role);
    }

    WaviToolArguments.Schema schema();

    /** Roles whose request runs {@link #execute}; every other role gets {@link #outsideRole}, with no lookup. */
    Set<UserRole> allowedRoles();

    DataClass dataClass();

    /**
     * Whether the reply may later be sent to an external AI provider as conversation history: only fixed,
     * role-wide text, never a personal record.
     */
    default boolean externallyShareable() {
        return dataClass() == DataClass.PUBLIC_APP;
    }

    /** Cross-argument rules the schema cannot express; a problem code, or null when the arguments are consistent. */
    default String checkArguments(WaviToolArguments.Validated arguments, Context context) {
        return null;
    }

    /** Runs the tool for the verified actor. Only called for a role in {@link #allowedRoles()}. */
    WaviToolResult execute(VerifiedMyHrActor actor, WaviToolArguments.Validated arguments, Context context);

    /**
     * The fixed reply for a verified role this tool does not serve. It gets the role, not the actor, so it cannot
     * look anything up.
     */
    WaviToolResult outsideRole(UserRole role, WaviToolArguments.Validated arguments, Context context);
}
