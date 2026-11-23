package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrChatResponse;
import com.waypoint.enums.UserRole;
import com.waypoint.security.VerifiedMyHrActor;

import java.util.List;
import java.util.Set;

/**
 * Tools offered only to the tool-use agent. Each reuses an existing, already-authorized service answer; none opens a
 * new data path.
 * <ul>
 *   <li>{@code check_person_in_scope}: a yes/no membership question needs no profile, ID or manager, so it returns
 *       none (minimum disclosure). The person is found from the user's own message against the people the reviewer
 *       may already see.</li>
 *   <li>{@code knowledge_base_help}: the existing Knowledge Base steps for the role.</li>
 *   <li>{@code list_policy_catalog}: the existing policy catalog (titles and audiences, never content), which the
 *       service itself refuses for an Employee.</li>
 * </ul>
 */
final class WaviAgentTools {

    static final String CHECK_PERSON_IN_SCOPE = "check_person_in_scope";
    static final String KNOWLEDGE_BASE_HELP = "knowledge_base_help";
    static final String LIST_POLICY_CATALOG = "list_policy_catalog";
    static final String GET_WORK_TOTALS = "get_work_totals";
    static final String LIST_PEOPLE = "list_people";

    /**
     * Tools that can be about one specific person. The model names the person with a server-issued handle in an extra
     * {@code person} argument; the agent loop resolves it against the actor's CURRENT scope and the tool then runs for
     * that person exactly as if the user had typed their name. No tool schema changes (the router never sees this).
     */
    static final java.util.Set<String> PERSON_AWARE = java.util.Set.of(WaviTools.GET_TEAM_MEMBER,
            WaviTools.GET_TEAM_TIMESHEETS, CHECK_PERSON_IN_SCOPE);

    /**
     * What the agent tells the model about a tool. Mostly the registry's own description; a few are sharper for the
     * agent (the router's prompt, and its approved spend ceiling, keep the registry's text).
     */
    static String agentDescription(String name, String registryDescription) {
        return switch (name) {
            case WaviTools.POLICY_QUESTION -> "What a company policy SAYS: rules, limits, entitlements, deadlines, answered "
                    + "from approved policy documents with citations. Not which policies exist or who may see them (use "
                    + "list_policy_catalog for that) and not personal records.";
            default -> registryDescription;
        };
    }

    private WaviAgentTools() {
    }

    /** A catalog tool that only the agent sees. */
    record AgentTool(WaviTools.Definition inner) implements WaviTool, WaviTool.AgentOnly {
        @Override
        public String name() {
            return inner.name();
        }

        @Override
        public String description() {
            return inner.description();
        }

        @Override
        public WaviToolArguments.Schema schema() {
            return inner.schema();
        }

        @Override
        public Set<UserRole> allowedRoles() {
            return inner.allowedRoles();
        }

        @Override
        public DataClass dataClass() {
            return inner.dataClass();
        }

        @Override
        public boolean externallyShareable() {
            return inner.externallyShareable();
        }

        @Override
        public String checkArguments(WaviToolArguments.Validated arguments, Context context) {
            return inner.checkArguments(arguments, context);
        }

        @Override
        public WaviToolResult execute(VerifiedMyHrActor actor, WaviToolArguments.Validated arguments, Context context) {
            return inner.execute(actor, arguments, context);
        }

        @Override
        public WaviToolResult outsideRole(UserRole role, WaviToolArguments.Validated arguments, Context context) {
            return inner.outsideRole(role, arguments, context);
        }

        @Override
        public String toString() {
            return "WaviAgentTool[" + name() + "]";
        }
    }

    static List<WaviTool> catalog(WaviRecordAnswers answers) {
        String knowledgePage = MyHrConversationIntents.Page.KNOWLEDGE_BASE.name();
        WaviTools.Definition inScope = new WaviTools.Definition(CHECK_PERSON_IN_SCOPE,
                "HR and Company Admin: whether the person NAMED in the user's message is one of the people the user can "
                        + "see in Waypoint (HR: active direct reports; Company Admin: the organization). Answers yes or no "
                        + "only: no name, ID, manager or profile. Use it for \"is X on my team\" or \"is X in my "
                        + "organization\"; use get_team_member when the user asks for a detail about the person.",
                WaviToolArguments.Schema.NONE, WaviTools.REVIEWERS, WaviTool.DataClass.ORG_AGGREGATE, false, null,
                (actor, arguments, context) -> {
                    String answer = answers.personInScope(actor, context.message());
                    return answer == null ? WaviToolResult.notAvailable("NO_ANSWER", WaviRouterPolicyGate.CANNOT_HELP_REPLY, List.of())
                            : WaviToolResult.answer(answer, List.of());
                },
                (role, arguments, context) -> WaviToolResult.notAvailable("ROLE_NOT_ALLOWED", MyHrChatService.OTHER_PERSON_REPLY, List.of()));

        WaviTools.Definition knowledgeBase = new WaviTools.Definition(KNOWLEDGE_BASE_HELP,
                "How to add, ingest, activate, replace or revoke a company policy in the Knowledge Base, with the page's "
                        + "real steps for the user's role (Employees are told the page is not available to them). Not what a "
                        + "policy says: use policy_question for that.",
                WaviToolArguments.Schema.NONE, WaviTools.ALL_ROLES, WaviTool.DataClass.PUBLIC_APP, true, null,
                (actor, arguments, context) -> {
                    String guidance = answers.workflowGuidance("KNOWLEDGE_BASE", actor.role());
                    if (guidance == null) {
                        return WaviToolResult.notAvailable("NO_ANSWER", WaviRouterPolicyGate.CANNOT_HELP_REPLY, List.of());
                    }
                    boolean reviewer = actor.role() == UserRole.HR || actor.role() == UserRole.COMPANY_ADMIN;
                    return WaviToolResult.answer(guidance, reviewer ? List.of(knowledgePage) : List.of());
                }, null);

        WaviTools.Definition catalog = new WaviTools.Definition(LIST_POLICY_CATALOG,
                "WHICH policies exist and who may see each (\"which policies are there\", \"which are HR-only\", \"which can "
                        + "employees read\"): titles grouped by audience, from the Knowledge Base listing (an Employee is told it "
                        + "is not available to them). Titles and audiences only, never policy content: use policy_question for "
                        + "what a policy says.",
                WaviToolArguments.Schema.NONE, WaviTools.ALL_ROLES, WaviTool.DataClass.ORG_AGGREGATE, false, null,
                (actor, arguments, context) -> {
                    MyHrChatResponse answer = answers.policyCatalog(actor);
                    return answer == null ? WaviToolResult.notAvailable("NO_ANSWER", WaviRouterPolicyGate.CANNOT_HELP_REPLY, List.of())
                            : WaviToolResult.answer(answer.message(), answer.actions());
                }, null);

        WaviTools.Definition totals = new WaviTools.Definition(GET_WORK_TOTALS,
                "HR and Company Admin: everything waiting for the user's action right now, queue by queue (timesheets, profile "
                        + "changes, documents) with the SERVER-CALCULATED total across them (\"how big is my backlog\", \"how many "
                        + "items need me in all\"). State the total as given; never add the counts yourself. For who or which "
                        + "items, use get_review_queue.",
                WaviToolArguments.Schema.NONE, WaviTools.REVIEWERS, WaviTool.DataClass.ORG_AGGREGATE, false, null,
                (actor, arguments, context) -> {
                    WaviRecordAnswers.WorkTotals result = answers.workTotals(actor);
                    return result == null ? WaviToolResult.notAvailable("NO_ANSWER", WaviRouterPolicyGate.CANNOT_HELP_REPLY, List.of())
                            : WaviToolResult.answer(result.text(), result.pages(), result.facts());
                },
                (role, arguments, context) -> WaviToolResult.notAvailable("ROLE_NOT_ALLOWED", MyHrChatService.OTHER_PERSON_REPLY, List.of()));

        WaviTools.Definition people = new WaviTools.Definition(LIST_PEOPLE,
                "HR and Company Admin: the people the user can see (HR: active direct reports; Company Admin: the whole "
                        + "organization), each with job title and kind only; no e-mail, ID, manager or dates. Use it to list or "
                        + "count people, or to see who exists; use get_team_member for details about one person and "
                        + "get_people_summary for counts by role.",
                WaviToolArguments.Schema.NONE, WaviTools.REVIEWERS, WaviTool.DataClass.ORG_AGGREGATE, false, null,
                (actor, arguments, context) -> {
                    MyHrChatResponse listed = answers.listPeople(actor);
                    return listed == null ? WaviToolResult.notAvailable("NO_ANSWER", WaviRouterPolicyGate.CANNOT_HELP_REPLY, List.of())
                            : WaviToolResult.answer(listed.message(), listed.actions());
                },
                (role, arguments, context) -> WaviToolResult.notAvailable("ROLE_NOT_ALLOWED", MyHrChatService.OTHER_PERSON_REPLY, List.of()));

        return List.of(new AgentTool(inScope), new AgentTool(knowledgeBase), new AgentTool(catalog), new AgentTool(totals),
                new AgentTool(people));
    }
}
