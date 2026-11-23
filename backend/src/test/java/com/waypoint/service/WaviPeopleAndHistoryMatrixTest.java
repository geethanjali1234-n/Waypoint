package com.waypoint.service;

import com.waypoint.dto.myhr.EmployeeSummaryResponse;
import com.waypoint.dto.myhr.MyHrChatHistoryMessage;
import com.waypoint.dto.myhr.MyHrChatResponse;
import com.waypoint.dto.myhr.MyHrTimesheetPeriodStatus;
import com.waypoint.dto.myhr.TimesheetResponse;
import com.waypoint.enums.TimesheetStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * People references and review history as CATEGORIES, not sentences: the same checks run across generated synthetic
 * teams (different names, sizes and orders), several phrasings per intent, greetings between turns, routing on and
 * off, and every role. Names are resolved only against the reviewer's authorized scope (the service's list), so the
 * outcomes depend on data, never on a phrase list.
 */
class WaviPeopleAndHistoryMatrixTest {

    /** Synthetic teams: different sizes, name shapes (initials, two-part names) and orders. */
    private static final List<List<String[]>> TEAMS = List.of(
            List.of(new String[]{"Dana Fox", "Platform Engineer"}, new String[]{"Omar Lee", "Data Scientist"},
                    new String[]{"Priyanka Varma", "QA Lead"}),
            List.of(new String[]{"Lena K", "Recruiter"}, new String[]{"Tomas Oduya", "Designer"},
                    new String[]{"Mei Chen", "Analyst"}, new String[]{"Arjun Rao", "Payroll Specialist"},
                    new String[]{"Zoe Park", "Support Engineer"}, new String[]{"Ian Blake", "Architect"}));

    private static final class Chat {
        final WaviEvaluationWorld world;
        final List<MyHrChatHistoryMessage> history = new ArrayList<>();
        MyHrChatService.RouteStage lastStage;

        Chat(WaviEvaluationWorld world) {
            this.world = world;
        }

        String ask(String message) {
            AtomicReference<MyHrChatService.RouteStage> stage = new AtomicReference<>();
            world.service.observeRouteStages(stage::set);
            MyHrChatResponse response = world.service.chat(world.actor, message, List.copyOf(history), StampedHistory.SESSION);
            lastStage = stage.get();
            history.add(new MyHrChatHistoryMessage("user", message, response.historyProvenance().user()));
            history.add(new MyHrChatHistoryMessage("assistant", response.message(), response.historyProvenance().assistant()));
            return response.message();
        }
    }

    private static Chat withTeam(WaviEvaluationWorld.Person reviewer, List<String[]> team, boolean routing) {
        WaviEvaluationWorld.Options options = new WaviEvaluationWorld.Options();
        if (routing) options.semanticRouter = WaviEvaluationWorld.RouterBehaviour.ABSTAIN;
        WaviEvaluationWorld world = new WaviEvaluationWorld(reviewer, options);
        List<EmployeeSummaryResponse> people = new ArrayList<>();
        for (int index = 0; index < team.size(); index++) {
            people.add(new EmployeeSummaryResponse("u-" + index, "SYN-" + (100 + index), team.get(index)[0], "EMPLOYEE",
                    "w" + index + "@work.example", "home" + index + "@private.example", team.get(index)[1], null, null,
                    reviewer.name(), null));
        }
        when(world.employees.list(reviewer.userId())).thenReturn(people);
        return new Chat(world);
    }

    // --- Named people in scope: every phrasing reaches the person, never a privacy refusal ----------------------

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void everyNamedPersonInScopeIsFoundWhateverThePhrasing(boolean routing) {
        for (List<String[]> team : TEAMS) {
            for (String[] member : team) {
                String full = member[0];
                String first = full.split(" ")[0];
                for (String question : List.of("who is " + full + "?", "Who is " + first + "?",
                        "tell me about " + full, "what is " + full + "'s job title", "what's " + first + "'s designation",
                        "job title of " + full + " please")) {
                    Chat hr = withTeam(WaviEvaluationWorld.HR, team, routing);
                    String reply = hr.ask(question);
                    assertThat(reply).as(question + " routing=" + routing).contains(full).contains(member[1])
                            .doesNotContain(MyHrChatService.OTHER_PERSON_REPLY, "@private.example");
                    verify(hr.world.claude, never()).chat(anyString(), anyList());
                }
            }
        }
    }

    @Test
    void aNameOutsideTheScopeIsNeverAnsweredAndDoesNotRevealWhetherThePersonExists() {
        Chat hr = withTeam(WaviEvaluationWorld.HR, TEAMS.get(0), false);
        String reply = hr.ask("what is Nobody Outsider's job title?");
        assertThat(reply).doesNotContain("Platform Engineer", "Data Scientist", "QA Lead");
    }

    // --- Positions, pronouns and greetings in between -----------------------------------------------------------

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void listPositionsAndPronounsResolveAgainstTheListJustShownEvenAfterAGreeting(int teamIndex) {
        List<String[]> team = TEAMS.get(teamIndex);
        Chat hr = withTeam(WaviEvaluationWorld.HR, team, false);
        assertThat(hr.ask("who reports to me?")).contains(team.getFirst()[0]);
        hr.ask("thanks!");
        String[] ordinals = {"first", "second", "third"};
        for (int position = 0; position < 3; position++) {
            String reply = hr.ask("what is the job title of the " + ordinals[position] + " employee?");
            assertThat(reply).as(ordinals[position]).isEqualTo(team.get(position)[0] + "'s job title is "
                    + team.get(position)[1] + ".");
        }
        assertThat(hr.ask("and their employee id?")).isEqualTo(team.get(2)[0] + "'s employee ID is SYN-102.");
        assertThat(hr.ask("what does the last one do?")).contains(team.getLast()[0]);
    }

    @Test
    void aPositionBeyondTheListOrWithoutAnyListIsClarifiedNotGuessed() {
        Chat hr = withTeam(WaviEvaluationWorld.HR, TEAMS.get(0), false);
        assertThat(hr.ask("what is the job title of the tenth employee?")).startsWith("I can't tell who that is");
    }

    // --- Roles --------------------------------------------------------------------------------------------------

    @Test
    void employeesNeverResolveAnyoneAndAdminsResolveTheirOrganization() {
        Chat employee = withTeam(WaviEvaluationWorld.EMPLOYEE, TEAMS.get(0), false);
        String reply = employee.ask("who is Dana Fox?");
        assertThat(reply).doesNotContain("Platform Engineer");
        verify(employee.world.employees, never()).list(anyString());

        Chat admin = withTeam(WaviEvaluationWorld.ADMIN, TEAMS.get(1), false);
        assertThat(admin.ask("what is Mei Chen's job title?")).isEqualTo("Mei Chen's job title is Analyst.");
    }

    // --- Review history -----------------------------------------------------------------------------------------

    private static TimesheetResponse reviewed(String owner, String reviewerId, TimesheetStatus status, LocalDateTime when) {
        MyHrTimesheetPeriodStatus week = new MyHrTimesheetPeriodStatus(LocalDate.parse("2026-10-19"),
                LocalDate.parse("2026-10-25"), status, LocalDateTime.of(2026, 10, 21, 9, 0));
        TimesheetResponse base = WaviEvaluationWorld.timesheet(
                new WaviEvaluationWorld.Person("id-" + owner, com.waypoint.enums.UserRole.EMPLOYEE, owner, "X", "Y", null),
                WaviEvaluationWorld.HR, week);
        return new TimesheetResponse(base.id(), base.employeeUserId(), base.employeeId(), base.employeeName(),
                base.employeeRole(), base.reportingManagerUserId(), null, null, base.periodStartDate(),
                base.periodEndDate(), base.entries(), base.totalHours(), List.of(), status, base.submittedAt(), null,
                null, reviewerId, when, null, null);
    }

    @Test
    void reviewHistoryUsesEachRecordsOwnReviewerAndTimeNewestFirst() {
        WaviEvaluationWorld.Options options = new WaviEvaluationWorld.Options();
        options.semanticRouter = WaviEvaluationWorld.RouterBehaviour.ABSTAIN;
        WaviEvaluationWorld world = new WaviEvaluationWorld(WaviEvaluationWorld.HR, options);
        when(world.timesheets.listForUser(WaviEvaluationWorld.HR.userId())).thenReturn(List.of(
                reviewed("Dana Fox", WaviEvaluationWorld.HR.userId(), TimesheetStatus.APPROVED, LocalDateTime.of(2026, 10, 27, 9, 0)),
                reviewed("Omar Lee", WaviEvaluationWorld.HR.userId(), TimesheetStatus.REJECTED, LocalDateTime.of(2026, 11, 2, 9, 0)),
                // Approved by someone else: never shown as this reviewer's decision.
                reviewed("Mei Chen", "someone-else", TimesheetStatus.APPROVED, LocalDateTime.of(2026, 11, 3, 9, 0))));
        doReturn(WaviEvaluationWorld.routed("{\"decision\":{\"tool\":\"get_my_review_history\",\"arguments\":{},"
                + "\"confidence\":\"HIGH\",\"language\":\"english\"}")).when(world.claude)
                .routeJsonWithUsage(anyString(), anyString(), anyMap(), anyInt(), anyString());
        String reply = new Chat(world).ask("what was the last timesheet I reviewed?");
        assertThat(reply).contains("(2)").doesNotContain("Mei Chen");
        assertThat(reply.indexOf("Omar Lee")).isLessThan(reply.indexOf("Dana Fox"));

        WaviEvaluationWorld employeeWorld = new WaviEvaluationWorld(WaviEvaluationWorld.EMPLOYEE, options);
        doReturn(WaviEvaluationWorld.routed("{\"decision\":{\"tool\":\"get_my_review_history\",\"arguments\":{},"
                + "\"confidence\":\"HIGH\",\"language\":\"english\"}")).when(employeeWorld.claude)
                .routeJsonWithUsage(anyString(), anyString(), anyMap(), anyInt(), anyString());
        Chat employee = new Chat(employeeWorld);
        // Employees review nothing: a role answer, and no other person's record is ever read.
        assertThat(employee.ask("whose timesheet did I approve last?")).doesNotContain("Omar Lee", "Dana Fox", "Mei Chen");
        verify(employeeWorld.employees, never()).list(anyString());
    }
}
