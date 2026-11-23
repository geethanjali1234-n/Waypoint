package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrChatResponse;
import com.waypoint.enums.UserRole;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OFFLINE: the agent-only tools (membership yes/no, Knowledge Base steps, policy catalog) over the synthetic world,
 * with a scripted model. They reuse existing service answers and permission checks; these tests prove who is offered
 * what, what comes back, and that a yes/no question exposes no identity, manager or profile.
 */
class WaviAgentToolsTest {

    private static final ExternalRecordProcessingAuthorization ALLOW = (organization, role) -> true;

    private static final class Model implements ClaudeChatClient.ToolConversation {
        final List<List<Map<String, Object>>> requests = new ArrayList<>();
        final List<List<ClaudeChatClient.ToolDefinition>> offered = new ArrayList<>();
        private final ClaudeChatClient.ToolCall call;
        private final String answer;
        int calls;

        Model(ClaudeChatClient.ToolCall call, String answer) {
            this.call = call;
            this.answer = answer;
        }

        @Override
        public ClaudeChatClient.ToolTurn next(String system, List<Map<String, Object>> messages,
                                              List<ClaudeChatClient.ToolDefinition> tools, boolean allowTools, int max) {
            requests.add(List.copyOf(messages));
            offered.add(tools);
            calls++;
            if (calls == 1 && call != null) {
                return new ClaudeChatClient.ToolTurn(null, List.of(call),
                        List.of(Map.of("type", "tool_use", "id", call.id(), "name", call.name(), "input", call.input())),
                        ClaudeChatClient.RouteUsage.NONE, "scripted");
            }
            return new ClaudeChatClient.ToolTurn(answer, List.of(), List.of(Map.of("type", "text", "text", answer)),
                    ClaudeChatClient.RouteUsage.NONE, "scripted");
        }
    }

    private static ClaudeChatClient.ToolCall call(String tool) {
        return new ClaudeChatClient.ToolCall("toolu_" + tool, tool, Map.of());
    }

    private static WaviEvaluationWorld world(WaviEvaluationWorld.Person person, Model model) {
        var options = new WaviEvaluationWorld.Options();
        options.semanticRouter = WaviEvaluationWorld.RouterBehaviour.UNAVAILABLE;
        var world = new WaviEvaluationWorld(person, options);
        world.service.useAgentLoop(new WaviAgentLoop(model, ALLOW, WaviAgentLoop.Settings.defaults(true), Clock.systemUTC()));
        return world;
    }

    private static String ask(WaviEvaluationWorld world, String question) {
        MyHrChatResponse response = world.service.chat(world.actor, question, List.of(), StampedHistory.SESSION);
        return response.message();
    }

    private static List<String> names(Model model) {
        return model.offered.getFirst().stream().map(ClaudeChatClient.ToolDefinition::name).toList();
    }

    // --- Registry and offering -----------------------------------------------------------------------------------------------

    @Test
    void theAgentToolsExistOnlyWhileTheAgentIsEnabledAndAreNeverOfferedToTheRouter() {
        var without = new WaviEvaluationWorld(WaviEvaluationWorld.HR, optionsWithToolLayer());
        assertThat(without.service.toolLayer().registry().all()).noneMatch(tool -> tool instanceof WaviTool.AgentOnly);

        Model model = new Model(null, "Happy to help.");
        var with = world(WaviEvaluationWorld.HR, model);
        List<WaviTool> agentOnly = with.service.toolLayer().registry().all().stream()
                .filter(tool -> tool instanceof WaviTool.AgentOnly).toList();
        assertThat(agentOnly).extracting(WaviTool::name).containsExactlyInAnyOrder(WaviAgentTools.CHECK_PERSON_IN_SCOPE,
                WaviAgentTools.KNOWLEDGE_BASE_HELP, WaviAgentTools.LIST_POLICY_CATALOG, WaviAgentTools.GET_WORK_TOTALS,
                WaviAgentTools.LIST_PEOPLE);
        // The router's tool list is the registry without them (the router prompt and its spend ceiling are unchanged).
        List<WaviTool> routerTools = with.service.toolLayer().registry().all().stream()
                .filter(tool -> !(tool instanceof WaviTool.AgentOnly)).toList();
        assertThat(routerTools).hasSize(without.service.toolLayer().registry().all().size());
    }

    private static WaviEvaluationWorld.Options optionsWithToolLayer() {
        var options = new WaviEvaluationWorld.Options();
        options.toolLayer = true;
        options.semanticRouter = WaviEvaluationWorld.RouterBehaviour.UNAVAILABLE;
        return options;
    }

    @Test
    void theMembershipToolIsOfferedToReviewersOnlyAndTheOtherTwoToEveryRole() {
        for (var role : List.of(WaviEvaluationWorld.EMPLOYEE, WaviEvaluationWorld.HR, WaviEvaluationWorld.ADMIN)) {
            Model model = new Model(null, "Happy to help.");
            ask(world(role, model), "I need some help with something unusual");
            boolean reviewer = role.role() != UserRole.EMPLOYEE;
            assertThat(names(model).contains(WaviAgentTools.CHECK_PERSON_IN_SCOPE)).as(role.role().name()).isEqualTo(reviewer);
            assertThat(names(model)).as(role.role().name()).contains(WaviAgentTools.KNOWLEDGE_BASE_HELP, WaviAgentTools.LIST_POLICY_CATALOG);
        }
    }

    // --- Minimum disclosure ----------------------------------------------------------------------------------------------------

    @Test
    void aMembershipQuestionReturnsYesWithNoNameIdManagerOrProfileAndTheUserSeesTheName() {
        Model model = new Model(call(WaviAgentTools.CHECK_PERSON_IN_SCOPE), "Yes, [P1] is one of the people you can see.");
        var world = world(WaviEvaluationWorld.HR, model);
        String answer = ask(world, "is Ravi Teja one of my people?");
        assertThat(answer).isEqualTo("Yes, Ravi Teja is one of the people you can see.");
        String result = model.requests.get(1).toString();
        assertThat(result).contains("Yes - the person named in the question is one of the people you can see")
                .doesNotContain("Ravi", "Teja", "WPT-", "Hari", "QA Engineer", "Manager");
    }

    @Test
    void aMembershipQuestionAboutSomeoneOutsideTheScopeIsAnHonestNoAndRevealsNobody() {
        Model model = new Model(call(WaviAgentTools.CHECK_PERSON_IN_SCOPE), "No, I can't find Mei Chen among the people you can see.");
        var world = world(WaviEvaluationWorld.ADMIN, model);
        String answer = ask(world, "is Mei Chen part of my organization?");
        assertThat(answer).contains("No");
        assertThat(model.requests.get(1).toString()).contains("No - nobody you can see").doesNotContain("Ravi", "Asha");
    }

    @Test
    void anEmployeeForgingTheMembershipToolGetsNothing() {
        Model model = new Model(call(WaviAgentTools.CHECK_PERSON_IN_SCOPE), "I can only show your own records.");
        var world = world(WaviEvaluationWorld.EMPLOYEE, model);
        ask(world, "is Ravi Teja in my organization?");
        assertThat(model.requests.get(1).toString()).contains("is_error=true").contains("not available").doesNotContain("Yes -");
    }

    // --- Knowledge Base steps and the policy catalog ---------------------------------------------------------------------------

    @Test
    void knowledgeBaseStepsComeFromTheExistingGuidanceForTheRole() {
        Model admin = new Model(call(WaviAgentTools.KNOWLEDGE_BASE_HELP), "Open Knowledge Base, choose Add policy, then Ingest and Activate.");
        String answer = ask(world(WaviEvaluationWorld.ADMIN, admin), "how do I get a new policy into the system?");
        assertThat(answer).contains("Add policy", "Ingest", "Activate");
        assertThat(admin.requests.get(1).toString()).contains("Add policy", "Replace file", "Revoke");

        Model employee = new Model(call(WaviAgentTools.KNOWLEDGE_BASE_HELP), "That page is not available to Employee accounts.");
        ask(world(WaviEvaluationWorld.EMPLOYEE, employee), "how do I upload a policy?");
        assertThat(employee.requests.get(1).toString()).contains("isn't available to Employee accounts").doesNotContain("Add policy");
    }

    @Test
    void thePolicyCatalogIsTheExistingReviewerListingAndAnEmployeeGetsTheFixedExplanation() {
        Model admin = new Model(call(WaviAgentTools.LIST_POLICY_CATALOG), "The company-wide policies include the Annual Leave Policy.");
        String answer = ask(world(WaviEvaluationWorld.ADMIN, admin), "which policies exist, and who can read them?");
        assertThat(answer).contains("Annual Leave Policy");
        assertThat(admin.requests.get(1).toString()).contains("Annual Leave Policy").doesNotContain("1.5 days", "accrue");

        Model employee = new Model(call(WaviAgentTools.LIST_POLICY_CATALOG), "I can't list the policy documents for Employee accounts.");
        ask(world(WaviEvaluationWorld.EMPLOYEE, employee), "which policies exist?");
        assertThat(employee.requests.get(1).toString()).contains(MyHrChatService.POLICY_CATALOG_EMPLOYEE_REPLY.substring(0, 40))
                .doesNotContain("Annual Leave", "Severance");
    }

    @Test
    void theNewToolsAreClassifiedForExternalDisclosure() {
        assertThat(WaviExternalDisclosure.classify(WaviAgentTools.CHECK_PERSON_IN_SCOPE, Map.of(), UserRole.HR))
                .isEqualTo(WaviExternalDisclosure.Level.RECORDS);
        assertThat(WaviExternalDisclosure.classify(WaviAgentTools.LIST_POLICY_CATALOG, Map.of(), UserRole.HR))
                .isEqualTo(WaviExternalDisclosure.Level.RECORDS);
        assertThat(WaviExternalDisclosure.classify(WaviAgentTools.KNOWLEDGE_BASE_HELP, Map.of(), UserRole.EMPLOYEE))
                .isEqualTo(WaviExternalDisclosure.Level.PUBLIC);
    }
}
