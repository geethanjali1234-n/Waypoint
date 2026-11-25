package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrChatHistoryMessage;
import com.waypoint.dto.myhr.MyHrChatResponse;
import com.waypoint.enums.UserRole;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * OFFLINE conversations for HR and Company Admin through the integrated agent (MyHrChatService -> WaviAgentLoop ->
 * WaviToolLayer -> the real tools over MOCKED services with SYNTHETIC data, WaviEvaluationWorld). Claude is a
 * scripted stand-in: it chooses the tool calls each test gives it and then words the answer purely from the tool
 * results it was shown, so a test can only pass if the right data actually reached the model, and a name or figure
 * that was withheld cannot reappear. Nothing here measures how well the real model understands wording.
 *
 * <p>No rule, keyword or answer was added to make these pass; the assertions are about authorization, data flow,
 * minimization, limits and conversation state.
 */
class WaviAgentRolesTest {

    private static final ExternalRecordProcessingAuthorization RECORDS_ONLY = (organization, role) -> true;
    private static final ExternalRecordProcessingAuthorization RECORDS_AND_NAMES = new ExternalRecordProcessingAuthorization() {
        @Override
        public boolean permits(String organization, UserRole role) {
            return true;
        }

        @Override
        public boolean permitsPersonNames(String organization, UserRole role) {
            return true;
        }
    };

    /** A scripted model: per turn, rounds of tool calls, then an answer composed only from what the tools returned. */
    private static final class Model implements ClaudeChatClient.ToolConversation {
        final List<List<Map<String, Object>>> requests = new ArrayList<>();
        final List<List<ClaudeChatClient.ToolDefinition>> offered = new ArrayList<>();
        final List<String> systems = new ArrayList<>();
        private List<List<ClaudeChatClient.ToolCall>> rounds = List.of();
        private String direct;
        private int round;
        int calls;

        @SafeVarargs
        final Model turn(List<ClaudeChatClient.ToolCall>... plan) {
            this.rounds = List.of(plan);
            this.direct = null;
            this.round = 0;
            return this;
        }

        Model reply(String text) {
            this.rounds = List.of();
            this.direct = text;
            this.round = 0;
            return this;
        }

        @Override
        @SuppressWarnings("unchecked")
        public ClaudeChatClient.ToolTurn next(String system, List<Map<String, Object>> messages,
                                              List<ClaudeChatClient.ToolDefinition> tools, boolean allowTools, int max) {
            requests.add(List.copyOf(messages));
            offered.add(tools);
            systems.add(system);
            calls++;
            if (round < rounds.size()) {
                List<ClaudeChatClient.ToolCall> planned = rounds.get(round++);
                List<Map<String, Object>> echoed = new ArrayList<>();
                for (ClaudeChatClient.ToolCall call : planned) {
                    echoed.add(Map.of("type", "tool_use", "id", call.id(), "name", call.name(), "input", call.input()));
                }
                return new ClaudeChatClient.ToolTurn(null, planned, echoed, ClaudeChatClient.RouteUsage.NONE, "scripted");
            }
            String text = direct;
            if (text == null) {
                StringBuilder composed = new StringBuilder();
                Object content = messages.getLast().get("content");
                if (content instanceof List<?> blocks) {
                    for (Object block : blocks) {
                        String shown = String.valueOf(((Map<String, Object>) block).get("content"));
                        composed.append(shown.startsWith("UNTRUSTED") ? shown.substring(shown.indexOf('\n') + 1) : shown)
                                .append('\n');
                    }
                }
                text = composed.toString().strip();
            }
            return new ClaudeChatClient.ToolTurn(text, List.of(), List.of(Map.of("type", "text", "text", text)),
                    ClaudeChatClient.RouteUsage.NONE, "scripted");
        }

        /** Everything ever sent to the provider, as one string, for "never left Waypoint" assertions. */
        String everythingSent() {
            return requests.toString() + offered.toString() + systems;
        }
    }

    private static final class Chat {
        final WaviEvaluationWorld world;
        final Model model = new Model();
        final List<MyHrChatHistoryMessage> history = new ArrayList<>();

        Chat(WaviEvaluationWorld.Person person, ExternalRecordProcessingAuthorization authorization) {
            this(person, authorization, new WaviEvaluationWorld.Options());
        }

        Chat(WaviEvaluationWorld.Person person, ExternalRecordProcessingAuthorization authorization,
             WaviEvaluationWorld.Options options) {
            options.semanticRouter = WaviEvaluationWorld.RouterBehaviour.UNAVAILABLE;
            world = new WaviEvaluationWorld(person, options);
            world.service.useAgentLoop(new WaviAgentLoop(model, authorization, WaviAgentLoop.Settings.defaults(true),
                    Clock.systemUTC()));
        }

        MyHrChatResponse response(String question) {
            MyHrChatResponse response = world.service.chat(world.actor, question, List.copyOf(history), StampedHistory.SESSION);
            history.add(new MyHrChatHistoryMessage("user", question, response.historyProvenance().user()));
            history.add(new MyHrChatHistoryMessage("assistant", response.message(), response.historyProvenance().assistant()));
            return response;
        }

        String ask(String question) {
            return response(question).message();
        }
    }

    private static ClaudeChatClient.ToolCall call(String tool, Map<String, Object> input) {
        return new ClaudeChatClient.ToolCall("toolu_" + tool + input.hashCode(), tool, input);
    }

    @SafeVarargs
    private static List<ClaudeChatClient.ToolCall> round(ClaudeChatClient.ToolCall... calls) {
        return List.of(calls);
    }

    private static ClaudeChatClient.ToolCall queue(String detail) {
        return call(WaviTools.GET_REVIEW_QUEUE, Map.of("queue", "TIMESHEETS", "detail", detail));
    }

    // --- HR ------------------------------------------------------------------------------------------------------------

    @Test
    void hrWhoIsWaitingIsAnsweredWithNamesFromTheScopedQueueWhenNamesAreAuthorized() {
        Chat hari = new Chat(WaviEvaluationWorld.HR, RECORDS_AND_NAMES);
        hari.model.turn(round(queue("LIST")));
        String answer = hari.ask("whose submissions are sitting in my inbox?");
        assertThat(answer).contains("Asha Rao", "Ravi Teja").doesNotContain("Former Report");
        // Identifiers never go out, even where names may.
        assertThat(hari.model.everythingSent()).doesNotContain("WPT-", "emp-a1", "org-eval-a", "@");
    }

    @Test
    void hrDistinctPeopleAndRecordCountsComeFromTheSameScopedQueueInOneChainedAnswer() {
        Chat hari = new Chat(WaviEvaluationWorld.HR, RECORDS_AND_NAMES);
        hari.model.turn(round(queue("COUNT"), queue("LIST")));
        String answer = hari.ask("how many people, and how many sheets, am I holding up?");
        // The count of records and the distinct people come from two different results, never one from the other.
        assertThat(answer).contains("3 submitted timesheets", "3 timesheets from 2 people");
        assertThat(hari.model.calls).isEqualTo(2);
    }

    @Test
    void hrFollowUpCarriesTheVerifiedPriorTurnToTheModelAndStaysInScope() {
        Chat hari = new Chat(WaviEvaluationWorld.HR, RECORDS_AND_NAMES);
        hari.model.turn(round(queue("LIST")));
        hari.ask("who is waiting on me?");
        hari.model.turn(round(call(WaviTools.GET_TEAM_TIMESHEETS, Map.of("status", "SUBMITTED"))));
        String answer = hari.ask("and which of those came in last?");
        assertThat(answer).contains("Asha Rao", "Ravi Teja").doesNotContain("Former Report");
        // The first answer held real names, so it is not replayed as history; the second request carries the handle
        // version of that exchange in its system text, and no name anywhere.
        assertThat(hari.model.requests.get(2).toString()).doesNotContain("Asha", "Ravi");
        assertThat(hari.model.systems.get(2)).contains("who is waiting on me?", "[P1]").doesNotContain("Asha", "Ravi");
    }

    @Test
    void hrWithoutNameApprovalGetsAModelWordedAnswerOverHandlesAndNoNameEverLeaves() {
        Chat hari = new Chat(WaviEvaluationWorld.HR, RECORDS_ONLY);
        hari.model.turn(round(queue("LIST")));
        String answer = hari.ask("whose submissions are sitting in my inbox?");
        // The model worded the answer from handle-version results; the server swapped the real names in.
        assertThat(answer).startsWith("3 timesheets from 2 people are waiting for your review:")
                .contains("Asha Rao", "Ravi Teja");
        assertThat(hari.model.everythingSent()).doesNotContain("Asha", "Ravi", "Teja", "Rao").contains("[P1]", "[P2]");
        assertThat(hari.model.calls).isEqualTo(2);
        // That exchange held real names, so it stays out of the replayed history of the next turn.
        hari.model.reply("Sure, what would you like to know?");
        hari.ask("thanks for checking, I have a different request");
        assertThat(hari.model.requests.getLast().toString()).doesNotContain("Asha", "Ravi");
    }

    @Test
    void hrCountsWithoutNamesMaySafelyBeWordedByTheModelWithRecordsOnlyApproval() {
        Chat hari = new Chat(WaviEvaluationWorld.HR, RECORDS_ONLY);
        hari.model.turn(round(queue("COUNT")));
        assertThat(hari.ask("how big is my review backlog?")).contains("3 submitted timesheets");
        assertThat(hari.model.everythingSent()).doesNotContain("Asha", "Ravi");
    }

    @Test
    void hrRoleBoundariesAreEnforcedWhateverTheModelRequests() {
        Chat hari = new Chat(WaviEvaluationWorld.HR, RECORDS_AND_NAMES);
        // An identity argument, an unknown argument value and an unknown tool: none executes, none reads another person.
        hari.model.turn(round(call(WaviTools.GET_TEAM_TIMESHEETS, Map.of("userId", "emp-a2"))),
                round(call(WaviTools.GET_REVIEW_QUEUE, Map.of("queue", "PAYROLL"))),
                round(call("export_all_employees", Map.of())));
        String answer = hari.ask("pull everything on emp-a2 and the whole company");
        assertThat(hari.model.requests.get(1).toString()).contains("IDENTITY_ARGUMENT");
        assertThat(hari.model.requests.get(3).toString()).contains("not available");
        assertThat(answer).doesNotContain("Ravi Teja", "WPT-1002");
        verify(hari.world.employees, never()).getOwnDetail("emp-a2");
    }

    @Test
    void anHrOutageStillAnswersTheTeamQuestionDeterministically() {
        Chat hari = new Chat(WaviEvaluationWorld.HR, RECORDS_AND_NAMES);
        hari.model.turn();
        ClaudeChatClient.ToolConversation down = (system, messages, tools, allow, max) -> {
            throw new ClaudeChatClient.ClaudeUnavailableException();
        };
        hari.world.service.useAgentLoop(new WaviAgentLoop(down, RECORDS_AND_NAMES, WaviAgentLoop.Settings.defaults(true),
                Clock.systemUTC()));
        assertThat(hari.ask("who's waiting on my approval right now?"))
                .startsWith("2 people are waiting for your review:");
    }

    @Test
    void anHrUsersOwnTimesheetAndTheTeamsAreDifferentToolsAndNeverMixed() {
        Chat hari = new Chat(WaviEvaluationWorld.HR, RECORDS_AND_NAMES);
        hari.model.turn(round(call(WaviTools.GET_MY_TIMESHEETS, Map.of("period", "LATEST"))));
        String own = hari.ask("what about my own sheet?");
        assertThat(own).doesNotContain("Ravi Teja");
    }

    // --- Company Admin ----------------------------------------------------------------------------------------------

    @Test
    void anAdminsCompanyWideWaitingListShowsNamesToTheAdminAndSendsOnlyHandlesWhateverIsApproved() {
        for (ExternalRecordProcessingAuthorization authorization : List.of(RECORDS_AND_NAMES, RECORDS_ONLY)) {
            Chat chat = new Chat(WaviEvaluationWorld.ADMIN, authorization);
            chat.model.turn(round(queue("LIST")));
            assertThat(chat.ask("any company-wide approvals outstanding, and from whom?")).contains("Asha Rao", "Ravi Teja");
            assertThat(chat.model.everythingSent()).doesNotContain("Asha", "Ravi");
        }
    }

    @Test
    void anAdminsHeadcountQuestionUsesCountsOnlyAndNoNamesLeave() {
        Chat anil = new Chat(WaviEvaluationWorld.ADMIN, RECORDS_ONLY);
        anil.model.turn(round(call(WaviTools.GET_PEOPLE_SUMMARY, Map.of())));
        String answer = anil.ask("what is our headcount, roughly?");
        assertThat(answer).isNotBlank();
        assertThat(anil.model.everythingSent()).doesNotContain("Asha", "Ravi", "Hari Kumar", "WPT-");
    }

    @Test
    void anAdminWithNoOwnTimesheetGetsTheExistingFixedStatement() {
        Chat anil = new Chat(WaviEvaluationWorld.ADMIN, RECORDS_ONLY);
        anil.model.turn(round(call(WaviTools.GET_MY_TIMESHEETS, Map.of("period", "LATEST"))));
        assertThat(anil.ask("have I filed my hours?")).contains(MyHrChatService.ADMIN_NO_OWN_TIMESHEET_STATUS);
    }

    @Test
    void theToolsOfferedFollowTheVerifiedRoleExactly() {
        Chat employee = new Chat(WaviEvaluationWorld.EMPLOYEE, RECORDS_ONLY);
        employee.model.reply("Happy to help.");
        employee.ask("I could use some assistance with an odd thing");
        Chat hr = new Chat(WaviEvaluationWorld.HR, RECORDS_ONLY);
        hr.model.reply("Happy to help.");
        hr.ask("I could use some assistance with an odd thing");
        Chat admin = new Chat(WaviEvaluationWorld.ADMIN, RECORDS_ONLY);
        admin.model.reply("Happy to help.");
        admin.ask("I could use some assistance with an odd thing");
        assertThat(names(employee.model)).contains(WaviTools.GET_MY_TIMESHEETS)
                .doesNotContain(WaviTools.GET_REVIEW_QUEUE, WaviTools.GET_DIRECT_REPORTS, WaviTools.GET_TEAM_TIMESHEETS,
                        WaviTools.GET_PEOPLE_SUMMARY);
        assertThat(names(hr.model)).contains(WaviTools.GET_REVIEW_QUEUE, WaviTools.GET_DIRECT_REPORTS,
                WaviTools.GET_TEAM_TIMESHEETS, WaviTools.GET_PEOPLE_SUMMARY);
        // HR and Company Admin are both offered their own direct reports; the employee is not.
        assertThat(names(admin.model)).contains(WaviTools.GET_REVIEW_QUEUE, WaviTools.GET_TEAM_TIMESHEETS,
                WaviTools.GET_DIRECT_REPORTS);
    }

    private static List<String> names(Model model) {
        return model.offered.getFirst().stream().map(ClaudeChatClient.ToolDefinition::name).toList();
    }

    @Test
    void anEmployeeCannotUseAReviewerToolEvenByForgingTheCall() {
        Chat asha = new Chat(WaviEvaluationWorld.EMPLOYEE, RECORDS_AND_NAMES);
        asha.model.turn(round(queue("LIST"), call(WaviTools.GET_DIRECT_REPORTS, Map.of())));
        String answer = asha.ask("show me who is waiting for approvals across the company");
        assertThat(answer).doesNotContain("Ravi", "Former Report", "Hari Kumar");
        verify(asha.world.employees, never()).list(anyString());
        verify(asha.world.profileChanges, never()).listForAdmin(anyString());
    }

    // --- Policy access --------------------------------------------------------------------------------------------

    @Test
    void anEmployeesPolicyQuestionGoesToTheAuthorizedCitedPipelineAndNeverLeaksHrOnlyText() {
        Chat asha = new Chat(WaviEvaluationWorld.EMPLOYEE, RECORDS_ONLY);
        asha.model.turn(round(call(WaviTools.POLICY_QUESTION, Map.of())));
        String answer = asha.ask("what are we paid when someone is let go?");
        assertThat(answer).doesNotContain("2 weeks of base pay", "26 weeks");
        // The agent sent no policy text anywhere: the handoff ended its run with no further model call.
        assertThat(asha.model.calls).isEqualTo(1);
        verify(asha.world.claude, never()).chat(org.mockito.ArgumentMatchers.<MyHrGroundedPrompt>argThat(prompt ->
                prompt != null && prompt.toString().contains("2 weeks of base pay")));
    }

    @Test
    void anOtherOrganizationsPolicyTextNeverSurfacesThroughTheAgentPath() {
        var options = new WaviEvaluationWorld.Options();
        options.leakOtherOrganization = true;
        Chat anil = new Chat(WaviEvaluationWorld.ADMIN, RECORDS_AND_NAMES, options);
        anil.model.turn(round(call(WaviTools.POLICY_QUESTION, Map.of())));
        String answer = anil.ask("what is the leave entitlement here?");
        assertThat(answer).doesNotContain("ORGB-CONFIDENTIAL", "9999");
        assertThat(anil.model.everythingSent()).doesNotContain("ORGB-CONFIDENTIAL");
    }

    // --- Missing and ambiguous information, other languages -----------------------------------------------------------

    @Test
    void theModelMayAskAShortClarifyingQuestionWhereTheRequestIsAmbiguous() {
        Chat hari = new Chat(WaviEvaluationWorld.HR, RECORDS_AND_NAMES);
        hari.model.reply("Which person or period do you mean?");
        assertThat(hari.ask("show me his thing from before")).isEqualTo("Which person or period do you mean?");
    }

    @Test
    void anotherLanguageReachesTheModelBecauseNoVocabularyDecidesWhetherItIsUnderstood() {
        Chat anil = new Chat(WaviEvaluationWorld.ADMIN, RECORDS_ONLY);
        anil.model.turn(round(queue("COUNT")));
        String answer = anil.ask("Wie viele Zeiterfassungen warten gerade auf eine Freigabe?");
        assertThat(anil.model.calls).isEqualTo(2);
        assertThat(answer).contains("3 timesheets awaiting review");
    }

    @Test
    void aWithheldIdentifierIsShownAsWaypointStatesItAndNeverSent() {
        Chat asha = new Chat(WaviEvaluationWorld.EMPLOYEE, RECORDS_AND_NAMES);
        asha.model.turn(round(call(WaviTools.GET_MY_PROFILE_FIELD, Map.of("field", "EMPLOYEE_ID"))));
        assertThat(asha.ask("what number am I in the system?")).contains("WPT-1001");
        assertThat(asha.model.everythingSent()).doesNotContain("WPT-1001");
    }

    // --- Registry drift guard ---------------------------------------------------------------------------------------

    @Test
    void everyRegisteredToolIsClassifiedAndOnlyFieldBasedToolsFailClosedWithoutAField() {
        var world = new WaviEvaluationWorld(WaviEvaluationWorld.HR, new WaviEvaluationWorld.Options());
        world.service.useAgentLoop(new WaviAgentLoop(new Model(), RECORDS_ONLY, WaviAgentLoop.Settings.defaults(true),
                Clock.systemUTC()));
        for (WaviTool tool : world.service.toolLayer().registry().all()) {
            WaviExternalDisclosure.Level level = WaviExternalDisclosure.classify(tool.name(), Map.of(), UserRole.HR);
            boolean fieldBased = tool.name().equals(WaviTools.GET_MY_PROFILE_FIELD) || tool.name().equals(WaviTools.GET_TEAM_MEMBER)
                    || tool.name().equals(WaviTools.UNSUPPORTED_FIELD);
            if (!fieldBased) {
                assertThat(level).as("unclassified tool: " + tool.name()).isNotEqualTo(WaviExternalDisclosure.Level.LOCAL_ONLY);
            } else {
                assertThat(level).as(tool.name()).isEqualTo(WaviExternalDisclosure.Level.LOCAL_ONLY);
            }
        }
        assertThat(WaviExternalDisclosure.classify("brand_new_tool", Map.of(), UserRole.HR))
                .isEqualTo(WaviExternalDisclosure.Level.LOCAL_ONLY);
    }
}
