package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrChatResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;

/**
 * Capability-level routing plumbing, with semantic routing ON and the router MOCKED to choose the capability a correct
 * router would choose. For every supported capability and role, several paraphrases - including broken grammar,
 * missing prepositions, odd casing and very short forms - must reach that capability's authorized answer: no earlier
 * stage (exact routes, interpreter, scope guard, people resolution) may hijack them with a different answer or a
 * refusal. This proves the deterministic layer leaves unfamiliar wording to the router; it does not measure the live
 * model's own accuracy, which needs the approved live evaluation.
 */
class WaviCapabilityRoutingMatrixTest {

    /** A capability: who asks, the router's decision, wordings of the same intent, and what the answer must contain. */
    private record Capability(WaviEvaluationWorld.Person role, String tool, String arguments, List<String> wordings,
                              List<String> mustContain, List<String> mustNotContain) {
    }

    private static final List<String> REFUSALS = List.of(MyHrScopeGuard.OUT_OF_SCOPE_REPLY,
            MyHrChatService.OTHER_PERSON_REPLY, "General guidance from Claude.");

    private static final List<Capability> CAPABILITIES = List.of(
            new Capability(WaviEvaluationWorld.HR, "get_direct_reports", "{}",
                    List.of("who reports to me?", "who is reporting to me", "who reports me", "who is under me",
                            "Tell me about my direct reports", "WHO ARE MY REPORTEES", "my team members?"),
                    List.of("Asha Rao", "Ravi Teja"), List.of()),
            new Capability(WaviEvaluationWorld.HR, "get_people_summary", "{}",
                    List.of("how many team members do I have?", "number of people under me", "how big is my team",
                            "count my direct reports"),
                    List.of("You have 2 active direct reports"), List.of()),
            new Capability(WaviEvaluationWorld.ADMIN, "get_people_summary", "{}",
                    List.of("how many employees are in my organization?", "how many managers are there",
                            "headcount please", "total staff in the company?"),
                    List.of("3 people in your organization", "different people are assigned as a reporting manager"),
                    List.of()),
            new Capability(WaviEvaluationWorld.HR, "get_review_queue", "{\"queue\":\"TIMESHEETS\",\"detail\":\"LIST\"}",
                    List.of("whose timesheets are waiting on me", "which employees need my timesheet approval",
                            "pending timesheet approvals - who?", "list the people I still have to approve"),
                    List.of("3 timesheets from 2 people", "Asha Rao", "Ravi Teja"),
                    List.of(WaviEvaluationWorld.FORMER_REPORT.name())),
            new Capability(WaviEvaluationWorld.HR, "get_team_member", "{\"field\":\"JOB_TITLE\"}",
                    List.of("what does ravi teja do here", "ravi teja job title", "Ravi Teja designation?",
                            "whats the role of ravi teja"),
                    List.of("QA Engineer"), List.of("@")),
            new Capability(WaviEvaluationWorld.HR, "get_team_member", "{\"field\":\"SUMMARY\"}",
                    List.of("show the profile for employee WPT-1001", "details for Waypoint ID WPT-1001"),
                    List.of("Asha Rao", "Senior Data Analyst"), List.of("@")),
            new Capability(WaviEvaluationWorld.EMPLOYEE, "list_my_timesheets", "{\"status\":\"NEEDS_ACTION\"}",
                    List.of("what do I still have to fix", "anything I need to redo on timesheets",
                            "timesheets that need my attention", "which of mine were sent back"),
                    List.of("rejected and needs to be corrected"), List.of()),
            new Capability(WaviEvaluationWorld.EMPLOYEE, "get_my_timesheets",
                    "{\"period\":\"MONTH\",\"month\":\"2026-10\",\"detail\":\"HOURS\"}",
                    List.of("hrs worked oct", "my october total hours pls", "how long did i work in october"),
                    List.of("Hours recorded for October 2026"), List.of()),
            new Capability(WaviEvaluationWorld.EMPLOYEE, "get_my_profile_field", "{\"field\":\"LAST_NAME\"}",
                    List.of("surname?", "my family name", "what is my last name", "last name on file"),
                    List.of("Rao"), List.of("Asha Rao")),
            new Capability(WaviEvaluationWorld.EMPLOYEE, "get_my_role", "{\"detail\":\"WORK_CONTEXT\"}",
                    List.of("what is my work here", "what job am i doing", "what are my work responsibilities"),
                    List.of("Employee", "Senior Data Analyst", "assigned duties"), List.of("General guidance")),
            new Capability(WaviEvaluationWorld.EMPLOYEE, "get_my_timesheets",
                    "{\"period\":\"LATEST\",\"detail\":\"SUBMISSION_DATE\"}",
                    List.of("when was my newest work record filed", "date i last sent a timesheet", "latest submission timestamp"),
                    List.of("submitted", "2026"), List.of("Which one", "General guidance")),
            new Capability(WaviEvaluationWorld.EMPLOYEE, "get_my_requests", "{\"kind\":\"DOCUMENT_REQUEST\"}",
                    List.of("where is my document request at", "document request status please"),
                    List.of("document request"), List.of("policy", "General guidance")),
            new Capability(WaviEvaluationWorld.EMPLOYEE, "get_my_pending_work", "{}",
                    List.of("what do i need to do now", "my current action items"),
                    List.of("pending"), List.of("General guidance")),
            new Capability(WaviEvaluationWorld.EMPLOYEE, "get_payroll_schedule", "{}",
                    List.of("how frequently are wages processed", "payroll cadence"),
                    List.of("monthly"), List.of("pay date is")),
            new Capability(WaviEvaluationWorld.EMPLOYEE, "explain_term", "{\"term\":\"PAYROLL_READINESS\"}",
                    List.of("define payroll readiness", "what does payroll readiness mean here"),
                    List.of("Payroll Readiness"), List.of("General guidance")),
            new Capability(WaviEvaluationWorld.EMPLOYEE, "workflow_help", "{\"topic\":\"TIMESHEET_CORRECTION\"}",
                    List.of("where do i correct a filed timesheet", "steps for fixing submitted hours"),
                    List.of("Timesheets"), List.of("approved it for you")),
            new Capability(WaviEvaluationWorld.EMPLOYEE, "unsupported_field", "{\"field\":\"HOME_ADDRESS\"}",
                    List.of("what home address do you store", "show my residential address"),
                    List.of("don't show"), List.of("General guidance")),
            new Capability(WaviEvaluationWorld.EMPLOYEE, "unsupported_request", "{\"reason\":\"WRITE_ACTION\"}",
                    List.of("change my address for me", "submit these hours now"),
                    List.of("can't"), List.of("completed")),
            new Capability(WaviEvaluationWorld.HR, "get_team_timesheets", "{\"status\":\"REJECTED\"}",
                    List.of("show rejected time records for my report", "which direct report sheets were rejected"),
                    List.of("rejected"), List.of("General guidance")),
            new Capability(WaviEvaluationWorld.HR, "get_my_review_history", "{\"queue\":\"TIMESHEETS\",\"decision\":\"APPROVED\"}",
                    List.of("my completed timesheet approvals", "decisions i made on time records"),
                    List.of("timesheets", "don't see"), List.of("General guidance")),
            new Capability(WaviEvaluationWorld.HR, "list_my_requests", "{\"kind\":\"PROFILE_CHANGE\",\"status\":\"OPEN\"}",
                    List.of("my open profile requests", "list profile changes i still have open"),
                    List.of("profile change"), List.of("General guidance")),
            new Capability(WaviEvaluationWorld.ADMIN, "open_page", "{\"page\":\"AUDIT_LOG\"}",
                    List.of("take me to the audit records", "open the audit trail"),
                    List.of("Audit"), List.of("General guidance")),
            new Capability(WaviEvaluationWorld.EMPLOYEE, "clarify", "{}",
                    List.of("is it approved?", "status please?"),
                    List.of("Which"), List.of("General guidance")));

    @Test
    void everyWordingOfASupportedCapabilityReachesItsAuthorizedAnswer() {
        List<String> failures = new ArrayList<>();
        for (Capability capability : CAPABILITIES) {
            for (String wording : capability.wordings()) {
                WaviEvaluationWorld.Options options = new WaviEvaluationWorld.Options();
                options.semanticRouter = WaviEvaluationWorld.RouterBehaviour.ABSTAIN;
                WaviEvaluationWorld world = new WaviEvaluationWorld(capability.role(), options);
                doReturn(WaviEvaluationWorld.routed("{\"decision\":{\"tool\":\"" + capability.tool() + "\",\"arguments\":"
                        + capability.arguments() + "},\"confidence\":\"HIGH\",\"language\":\"english\"}"))
                        .when(world.claude).routeJsonWithUsage(anyString(), anyString(), anyMap(), anyInt(), anyString());
                AtomicReference<MyHrChatService.RouteStage> stage = new AtomicReference<>();
                world.service.observeRouteStages(stage::set);
                MyHrChatResponse response = world.service.chat(world.actor, wording, List.of(), StampedHistory.SESSION);
                String reply = response.message();
                String label = capability.role().role() + " " + capability.tool() + " \"" + wording + "\" stage="
                        + stage.get() + " -> " + reply;
                if (REFUSALS.stream().anyMatch(reply::startsWith)) failures.add("refused/hijacked: " + label);
                else if (!capability.mustContain().stream().allMatch(reply::contains)) failures.add("wrong answer: " + label);
                else if (capability.mustNotContain().stream().anyMatch(reply::contains)) failures.add("leaked: " + label);
            }
        }
        assertThat(failures).isEmpty();
    }

    private static WaviEvaluationWorld employeeRoutedTo(String decisionJson) {
        WaviEvaluationWorld.Options options = new WaviEvaluationWorld.Options();
        options.semanticRouter = WaviEvaluationWorld.RouterBehaviour.ABSTAIN;
        WaviEvaluationWorld world = new WaviEvaluationWorld(WaviEvaluationWorld.EMPLOYEE, options);
        doReturn(WaviEvaluationWorld.routed(decisionJson)).when(world.claude)
                .routeJsonWithUsage(anyString(), anyString(), anyMap(), anyInt(), anyString());
        return world;
    }

    @Test
    void anEmployeesPeopleQuestionGetsTheLimitationAndAReviewerOnlyDecisionIsRejectedWithNoRead() {
        // What the prompt directs for an Employee: the specific limitation.
        WaviEvaluationWorld routed = employeeRoutedTo("{\"decision\":{\"tool\":\"unsupported_request\",\"arguments\":"
                + "{\"reason\":\"PEOPLE_AGGREGATE\"}},\"confidence\":\"HIGH\",\"language\":\"english\"}");
        assertThat(routed.service.chat(routed.actor, "how many employees are there?", List.of(), StampedHistory.SESSION)
                .message()).isEqualTo(WaviTools.PEOPLE_AGGREGATE_REPLY);

        // Valid provider JSON still cannot grant an Employee a reviewer capability.
        WaviEvaluationWorld forged = employeeRoutedTo("{\"decision\":{\"tool\":\"get_people_summary\",\"arguments\":{}},"
                + "\"confidence\":\"HIGH\",\"language\":\"english\"}");
        String reply = forged.service.chat(forged.actor, "how many employees are there?", List.of(), StampedHistory.SESSION)
                .message();
        assertThat(reply).doesNotContain("Ravi Teja", "active direct", "in your organization");
        org.mockito.Mockito.verify(forged.employees, org.mockito.Mockito.never()).list(anyString());
    }
}
