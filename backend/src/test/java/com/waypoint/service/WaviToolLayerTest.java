package com.waypoint.service;

import com.waypoint.enums.UserRole;
import com.waypoint.exception.UnauthorizedException;
import com.waypoint.security.TestActors;
import com.waypoint.security.VerifiedMyHrActor;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;
import org.springframework.dao.DataAccessResourceFailureException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase P1: the Wavi tool layer on its own (gate, tools, bounds), against the synthetic people and records of
 * {@link WaviEvaluationWorld}: Employee Asha Rao, HR Hari Kumar, Company Admin Anil Shah, and colleague Ravi Teja,
 * whose records nobody else may see. Every service is a mock, so every lookup is observable.
 */
class WaviToolLayerTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-11-05");
    private static final List<WaviEvaluationWorld.Person> PEOPLE = WaviEvaluationWorld.PEOPLE;

    /** One decision run for one person, with every lookup it caused. */
    record Run(WaviToolLayer.Outcome outcome, List<Invocation> lookups, List<Invocation> providers,
               WaviEvaluationWorld world) {
        String text() {
            return outcome.result().text();
        }

        WaviToolResult.Kind kind() {
            return outcome.result().kind();
        }
    }

    private static WaviEvaluationWorld world(WaviEvaluationWorld.Person person) {
        WaviEvaluationWorld.Options options = new WaviEvaluationWorld.Options();
        options.toolLayer = true;
        return new WaviEvaluationWorld(person, options);
    }

    private static Run run(WaviEvaluationWorld.Person person, WaviRouterDecision decision) {
        return run(world(person), decision);
    }

    private static Run run(WaviEvaluationWorld world, WaviRouterDecision decision) {
        Mockito.clearInvocations(world.timesheets, world.profileChanges, world.employees, world.documents,
                world.claude, world.ollama, world.retrieval);
        WaviToolLayer.Outcome outcome = world.service.toolLayer().execute(decision, world.actor, TODAY);
        List<Invocation> lookups = new ArrayList<>();
        for (Object mock : world.dataMocks()) lookups.addAll(Mockito.mockingDetails(mock).getInvocations());
        List<Invocation> providers = new ArrayList<>();
        for (Object mock : List.of(world.claude, world.ollama, world.retrieval)) {
            providers.addAll(Mockito.mockingDetails(mock).getInvocations());
        }
        return new Run(outcome, lookups, providers, world);
    }

    private static WaviRouterDecision fast(String tool, Map<String, Object> arguments) {
        return WaviRouterDecision.fastPath(WaviRouterDecision.Intent.RECORD_LOOKUP, tool, arguments);
    }

    private static WaviRouterDecision model(String tool, Map<String, Object> arguments,
                                            WaviRouterDecision.Confidence confidence) {
        return new WaviRouterDecision(WaviRouterDecision.Intent.RECORD_LOOKUP, tool, arguments, confidence, List.of(),
                "en", WaviRouterDecision.Source.CLAUDE);
    }

    /** Valid arguments for every tool, so each can be run for each role. */
    private static final Map<String, List<Map<String, Object>>> SAMPLES = new LinkedHashMap<>();

    static {
        SAMPLES.put(WaviTools.OPEN_PAGE, List.of(Map.of("page", "TIMESHEETS"), Map.of("page", "AUDIT_LOG")));
        SAMPLES.put(WaviTools.GET_MY_ROLE, List.of(Map.of(), Map.of("asked", "HR")));
        SAMPLES.put(WaviTools.GET_MY_PROFILE_FIELD, Stream.of(WaviTools.ProfileField.values())
                .map(field -> Map.<String, Object>of("field", field.name())).toList());
        SAMPLES.put(WaviTools.UNSUPPORTED_FIELD, List.of(Map.of("field", "PAY"), Map.of("field", "PHONE_NUMBER")));
        SAMPLES.put(WaviTools.GET_MY_TIMESHEETS, List.of(Map.of("period", "LATEST"),
                Map.of("period", "MONTH", "month", "2026-10"), Map.of("period", "WEEK", "relative", "LAST"),
                Map.of("period", "DAY", "date", "2026-10-14"),
                Map.of("period", "WEEK", "relative", "WEEKS_AGO", "weeksAgo", 2)));
        SAMPLES.put(WaviTools.GET_MY_PENDING_WORK, List.of(Map.of()));
        SAMPLES.put(WaviTools.GET_DIRECT_REPORTS, List.of(Map.of()));
        SAMPLES.put(WaviTools.GET_REVIEW_QUEUE, Stream.of(WaviTools.ReviewQueue.values())
                .map(queue -> Map.<String, Object>of("queue", queue.name())).toList());
        SAMPLES.put(WaviTools.GET_MY_REQUESTS, List.of(Map.of("kind", "PROFILE_CHANGE"),
                Map.of("kind", "PROFILE_CHANGE", "field", "PHONE_NUMBER", "aspect", "APPROVED"),
                Map.of("kind", "DOCUMENT_REQUEST"), Map.of("kind", "OFFER_LETTER", "aspect", "PENDING")));
        SAMPLES.put(WaviTools.GET_PAYROLL_SCHEDULE, List.of(Map.of()));
        SAMPLES.put(WaviTools.EXPLAIN_TERM, List.of(Map.of("term", "PAYROLL"), Map.of("term", "PAYROLL_READINESS")));
        SAMPLES.put(WaviTools.WORKFLOW_HELP, List.of(Map.of("topic", "TIMESHEET_UPLOAD"), Map.of("topic", "TIMESHEET_ENTRY")));
        SAMPLES.put(WaviTools.POLICY_QUESTION, List.of(Map.of()));
        SAMPLES.put(WaviTools.GENERAL_HELP, List.of(Map.of()));
        SAMPLES.put(WaviTools.UNSUPPORTED_REQUEST, Stream.of(WaviTools.UnsupportedReason.values())
                .map(reason -> Map.<String, Object>of("reason", reason.name())).toList());
        SAMPLES.put(WaviTools.CLARIFY, List.of(Map.of()));
        SAMPLES.put(WaviTools.OUT_OF_SCOPE, List.of(Map.of()));
        SAMPLES.put(WaviTools.GET_PEOPLE_SUMMARY, List.of(Map.of()));
        SAMPLES.put(WaviTools.GET_TEAM_MEMBER, List.of(Map.of("field", "JOB_TITLE"), Map.of("ordinal", 2)));
        SAMPLES.put(WaviTools.GET_TEAM_TIMESHEETS, List.of(Map.of(), Map.of("status", "REJECTED")));
        SAMPLES.put(WaviTools.LIST_MY_TIMESHEETS, List.of(Map.of("status", "NEEDS_ACTION")));
        SAMPLES.put(WaviTools.LIST_MY_REQUESTS, List.of(Map.of("kind", "DOCUMENT_REQUEST", "status", "OPEN")));
        SAMPLES.put(WaviTools.GET_MY_REVIEW_HISTORY, List.of(Map.of(), Map.of("queue", "PROFILE_CHANGES",
                "decision", "APPROVED")));
    }

    // --- Catalog --------------------------------------------------------------------------------------------------

    @Test
    void theCatalogIsFixedStrictAndHasNoIdentityArguments() {
        WaviToolRegistry registry = world(WaviEvaluationWorld.EMPLOYEE).service.toolLayer().registry();

        assertThat(registry.all()).extracting(WaviTool::name).containsExactlyInAnyOrderElementsOf(SAMPLES.keySet());
        for (WaviTool tool : registry.all()) {
            Map<String, Object> schema = tool.schema().toJsonSchema();
            assertThat(schema).as(tool.name()).containsEntry("type", "object").containsEntry("additionalProperties", false);
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
            assertThat(properties.keySet()).as(tool.name()).noneMatch(WaviToolArguments::isIdentityName);
            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) schema.get("required");
            assertThat(properties.keySet()).containsAll(required);
            assertThat(tool.description()).isNotBlank();
            assertThat(tool.allowedRoles()).isNotEmpty();
        }
        assertThat(registry.find(WaviTools.GET_DIRECT_REPORTS).orElseThrow().allowedRoles()).containsExactly(UserRole.HR);
        assertThat(registry.find(WaviTools.GET_REVIEW_QUEUE).orElseThrow().allowedRoles())
                .containsExactlyInAnyOrder(UserRole.HR, UserRole.COMPANY_ADMIN);
        assertThat(registry.availableTo(UserRole.EMPLOYEE)).extracting(WaviTool::name)
                .doesNotContain(WaviTools.GET_DIRECT_REPORTS, WaviTools.GET_REVIEW_QUEUE);
        // Only fixed, role-wide answers may be reused as history for an external provider.
        assertThat(registry.all().stream().filter(WaviTool::externallyShareable).map(WaviTool::name))
                .containsExactlyInAnyOrder(WaviTools.OPEN_PAGE, WaviTools.EXPLAIN_TERM, WaviTools.WORKFLOW_HELP,
                        WaviTools.OUT_OF_SCOPE);
    }

    @Test
    void aSchemaCannotDeclareAnIdentityArgumentAndTheRegistryRejectsBadNames() {
        for (String name : List.of("userId", "employeeId", "orgId", "organizationId", "role", "email", "actor",
                "targetUser", "managerId", "tenant")) {
            assertThatThrownBy(() -> WaviToolArguments.Spec.enumOf(name, true, List.of("A"), ""))
                    .as(name).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> WaviToolArguments.Spec.enumOf("user_id", true, List.of("A"), ""))
                .isInstanceOf(IllegalArgumentException.class);
        WaviTool tool = WaviTools.catalog(new NoAnswers()).getFirst();
        assertThatThrownBy(() -> new WaviToolRegistry(List.of(tool, tool))).isInstanceOf(IllegalArgumentException.class);
        assertThat(WaviToolRegistry.isValidName("open_page")).isTrue();
        for (String bad : List.of("Open_Page", "open page", "x", "1tool", "open-page", "a".repeat(41))) {
            assertThat(WaviToolRegistry.isValidName(bad)).as(bad).isFalse();
        }
    }

    @Test
    void everyProfileFieldAndWorkflowTopicReachesTheExistingAnswer() {
        WaviEvaluationWorld world = world(WaviEvaluationWorld.EMPLOYEE);
        for (WaviTools.ProfileField field : WaviTools.ProfileField.values()) {
            Run run = run(world, fast(WaviTools.GET_MY_PROFILE_FIELD, Map.of("field", field.name())));
            assertThat(run.kind()).as(field.name()).isEqualTo(WaviToolResult.Kind.ANSWER);
        }
        WaviTool workflow = world.service.toolLayer().registry().find(WaviTools.WORKFLOW_HELP).orElseThrow();
        @SuppressWarnings("unchecked")
        List<String> topics = (List<String>) ((Map<String, Object>) ((Map<String, Object>) workflow.schema()
                .toJsonSchema().get("properties")).get("topic")).get("enum");
        assertThat(topics).contains("TIMESHEET_UPLOAD", "TIMESHEET_ENTRY", "PROFILE_CHANGE", "DOCUMENT_REQUEST")
                .hasSize(16);
        for (WaviEvaluationWorld.Person person : PEOPLE) {
            WaviEvaluationWorld someone = world(person);
            for (String topic : topics) {
                Run run = run(someone, fast(WaviTools.WORKFLOW_HELP, Map.of("topic", topic)));
                assertThat(run.text()).as(person.role() + " " + topic).isNotBlank();
                assertThat(run.lookups()).as("workflow help never reads a record").isEmpty();
            }
        }
    }

    // --- Gate -----------------------------------------------------------------------------------------------------

    @Test
    void anUnknownToolOrNoDecisionRunsNothing() {
        for (WaviEvaluationWorld.Person person : PEOPLE) {
            Run unknown = run(person, fast("delete_employee", Map.of()));
            assertThat(unknown.kind()).isEqualTo(WaviToolResult.Kind.REJECTED);
            assertThat(unknown.outcome().result().reasonCode()).isEqualTo("UNKNOWN_TOOL");
            assertThat(unknown.outcome().executed()).isFalse();
            assertThat(unknown.lookups()).isEmpty();
            assertThat(unknown.text()).isEqualTo(WaviRouterPolicyGate.CANNOT_HELP_REPLY);

            Run none = run(person, null);
            assertThat(none.outcome().result().reasonCode()).isEqualTo("NO_DECISION");
            assertThat(none.lookups()).isEmpty();
        }
    }

    @Test
    void aDecisionNamingAPersonOrganizationOrRoleIsRejectedBeforeAnyLookup() {
        List<String> identityNames = List.of("userId", "user_id", "employeeId", "EMPLOYEE_ID", "organizationId",
                "orgId", "org", "role", "actor", "email", "targetUser", "loginId", "managerId", "tenantId");
        for (WaviEvaluationWorld.Person person : PEOPLE) {
            WaviEvaluationWorld world = world(person);
            for (String name : identityNames) {
                for (String tool : List.of(WaviTools.GET_MY_PROFILE_FIELD, WaviTools.GET_MY_TIMESHEETS,
                        WaviTools.GET_REVIEW_QUEUE, WaviTools.GET_DIRECT_REPORTS)) {
                    Map<String, Object> arguments = new HashMap<>(SAMPLES.get(tool).getFirst());
                    arguments.put(name, name.toLowerCase().contains("org") ? WaviEvaluationWorld.OTHER_ORG
                            : WaviEvaluationWorld.COLLEAGUE.userId());
                    // Even a model that is sure must not choose whose records are read.
                    for (WaviRouterDecision decision : List.of(fast(tool, arguments),
                            model(tool, arguments, WaviRouterDecision.Confidence.HIGH))) {
                        Run run = run(world, decision);
                        assertThat(run.outcome().result().reasonCode()).as(person.role() + " " + tool + " " + name)
                                .isEqualTo("IDENTITY_ARGUMENT");
                        assertThat(run.outcome().executed()).isFalse();
                        assertThat(run.lookups()).isEmpty();
                    }
                }
            }
        }
    }

    @Test
    void argumentsOutsideTheSchemaAreRejected() {
        Map<String, Map<String, Object>> invalid = new LinkedHashMap<>();
        invalid.put("UNKNOWN_ARGUMENT", Map.of("field", "NAME", "verbose", "yes"));
        invalid.put("INVALID_ARGUMENT", Map.of("field", "WPT-1002"));
        invalid.put("MISSING_ARGUMENT", Map.of());
        Map<String, Object> withNull = new HashMap<>();
        withNull.put("field", null);
        invalid.put("NULL_ARGUMENT", withNull);
        for (Map.Entry<String, Map<String, Object>> entry : invalid.entrySet()) {
            Run run = run(WaviEvaluationWorld.EMPLOYEE, fast(WaviTools.GET_MY_PROFILE_FIELD, entry.getValue()));
            assertThat(run.outcome().result().reasonCode()).as(entry.getKey()).isEqualTo(entry.getKey());
            assertThat(run.lookups()).isEmpty();
        }
        Map<String, Object> tooMany = new LinkedHashMap<>();
        for (int index = 0; index < 10; index++) tooMany.put("a" + index, "x");
        assertThat(run(WaviEvaluationWorld.EMPLOYEE, fast(WaviTools.GET_MY_PROFILE_FIELD, tooMany))
                .outcome().result().reasonCode()).isEqualTo("TOO_MANY_ARGUMENTS");

        List<Map<String, Object>> badPeriods = List.of(
                Map.of("period", "DAY", "date", "2010-01-01"),          // before the window
                Map.of("period", "DAY", "date", "2030-01-01"),          // after the window
                Map.of("period", "DAY", "date", "14/10/2026"),          // not ISO
                Map.of("period", "DAY", "date", "2026-02-30"),          // not a date
                Map.of("period", "MONTH", "month", "2019-01"),          // before the window
                Map.of("period", "MONTH", "month", "October"),          // not ISO
                Map.of("period", "WEEK", "relative", "WEEKS_AGO", "weeksAgo", 99),
                Map.of("period", "WEEK", "relative", "WEEKS_AGO", "weeksAgo", "2"),
                Map.of("period", "YEAR"),
                Map.of("period", 3));
        for (Map<String, Object> arguments : badPeriods) {
            Run run = run(WaviEvaluationWorld.EMPLOYEE, fast(WaviTools.GET_MY_TIMESHEETS, arguments));
            assertThat(run.kind()).as(arguments.toString()).isEqualTo(WaviToolResult.Kind.REJECTED);
            assertThat(run.lookups()).as(arguments.toString()).isEmpty();
        }
        List<Map<String, Object>> inconsistent = List.of(
                Map.of("period", "LATEST", "date", "2026-10-14"),
                Map.of("period", "WEEK", "date", "2026-10-14", "relative", "LAST"),
                Map.of("period", "DAY", "month", "2026-10"),
                Map.of("period", "MONTH", "relative", "TODAY"),
                Map.of("period", "WEEK", "relative", "WEEKS_AGO"),
                Map.of("period", "WEEK", "weeksAgo", 2),
                Map.of("period", "DAY"));
        for (Map<String, Object> arguments : inconsistent) {
            Run run = run(WaviEvaluationWorld.EMPLOYEE, fast(WaviTools.GET_MY_TIMESHEETS, arguments));
            assertThat(run.outcome().result().reasonCode()).as(arguments.toString()).isEqualTo("INCONSISTENT_PERIOD");
            assertThat(run.lookups()).isEmpty();
        }
        Run mismatched = run(WaviEvaluationWorld.EMPLOYEE, fast(WaviTools.GET_MY_REQUESTS,
                Map.of("kind", "DOCUMENT_REQUEST", "field", "PHONE_NUMBER")));
        assertThat(mismatched.outcome().result().reasonCode()).isEqualTo("INCONSISTENT_REQUEST");
        assertThat(mismatched.lookups()).isEmpty();
    }

    @Test
    void anUnsureModelDecisionBecomesAClarifyingQuestionNotALookup() {
        Run low = run(WaviEvaluationWorld.EMPLOYEE, model(WaviTools.OPEN_PAGE, Map.of("page", "TIMESHEETS"),
                WaviRouterDecision.Confidence.LOW));
        assertThat(low.kind()).isEqualTo(WaviToolResult.Kind.CLARIFY);
        assertThat(low.text()).isEqualTo(MyHrChatService.CLARIFY_EMPLOYEE);
        Run mediumPersonal = run(WaviEvaluationWorld.HR, model(WaviTools.GET_MY_PROFILE_FIELD, Map.of("field", "NAME"),
                WaviRouterDecision.Confidence.MEDIUM));
        assertThat(mediumPersonal.kind()).isEqualTo(WaviToolResult.Kind.CLARIFY);
        assertThat(mediumPersonal.text()).isEqualTo(MyHrChatService.CLARIFY_HR);
        assertThat(mediumPersonal.lookups()).isEmpty();
        Run mediumQueue = run(WaviEvaluationWorld.ADMIN, model(WaviTools.GET_REVIEW_QUEUE, Map.of("queue", "TIMESHEETS"),
                WaviRouterDecision.Confidence.MEDIUM));
        assertThat(mediumQueue.kind()).isEqualTo(WaviToolResult.Kind.CLARIFY);
        assertThat(mediumQueue.lookups()).isEmpty();
        // A fixed Waypoint fact is safe to give at medium confidence; a sure decision runs, for the actor only.
        Run mediumPublic = run(WaviEvaluationWorld.EMPLOYEE, model(WaviTools.OPEN_PAGE, Map.of("page", "TIMESHEETS"),
                WaviRouterDecision.Confidence.MEDIUM));
        assertThat(mediumPublic.kind()).isEqualTo(WaviToolResult.Kind.ANSWER);
        Run high = run(WaviEvaluationWorld.EMPLOYEE, model(WaviTools.GET_MY_PROFILE_FIELD,
                Map.of("field", "REPORTING_MANAGER"), WaviRouterDecision.Confidence.HIGH));
        assertThat(high.text()).isEqualTo("Your reporting manager is Priya Nair.");
        assertThat(high.lookups()).allSatisfy(call -> assertThat((Object) call.getArgument(0))
                .isEqualTo(WaviEvaluationWorld.EMPLOYEE.userId()));
    }

    // --- Authorization ----------------------------------------------------------------------------------------------

    @Test
    void everyToolForEveryRoleReadsOnlyTheVerifiedUsersOwnRecordsAndCallsNoProvider() {
        int runs = 0;
        for (WaviEvaluationWorld.Person person : PEOPLE) {
            WaviEvaluationWorld world = world(person);
            for (Map.Entry<String, List<Map<String, Object>>> entry : SAMPLES.entrySet()) {
                for (Map<String, Object> arguments : entry.getValue()) {
                    Run run = run(world, fast(entry.getKey(), arguments));
                    String label = person.role() + " " + entry.getKey() + " " + arguments;
                    assertThat(run.kind()).as(label).isNotEqualTo(WaviToolResult.Kind.REJECTED);
                    for (Invocation lookup : run.lookups()) {
                        for (Object argument : lookup.getArguments()) {
                            if (argument instanceof String text && text.matches("^[a-z]+-[a-z]\\d+$")) {
                                assertThat(text).as(label + " " + lookup.getMethod().getName())
                                        .isEqualTo(person.userId());
                            }
                        }
                    }
                    // Tools never call an AI provider or company-policy retrieval themselves.
                    assertThat(run.providers()).as(label).isEmpty();
                    String text = run.text() == null ? "" : run.text();
                    assertThat(text).as(label).doesNotContain(WaviEvaluationWorld.OTHER_ORG_SECRET);
                    // Only the people tools, for reviewers, show someone else's work details - people the services
                    // already let that reviewer see. Every other tool, and every tool for an Employee, shows none.
                    boolean peopleTool = entry.getKey().equals(WaviTools.GET_TEAM_MEMBER)
                            || entry.getKey().equals(WaviTools.GET_TEAM_TIMESHEETS);
                    boolean reviewer = person != WaviEvaluationWorld.EMPLOYEE;
                    if (!(peopleTool && reviewer)) {
                        assertThat(text).as(label).doesNotContain(WaviEvaluationWorld.COLLEAGUE.employeeId());
                    }
                    if (person == WaviEvaluationWorld.EMPLOYEE || (person == WaviEvaluationWorld.ADMIN && !peopleTool)) {
                        assertThat(text).as(label).doesNotContain("Ravi Teja");
                    }
                    assertThat(text.length()).isLessThanOrEqualTo(WaviToolLayer.MAX_REPLY_CHARS);
                    if (run.outcome().result().pageKeys() != null) {
                        assertThat(run.outcome().result().pageKeys()).hasSizeLessThanOrEqualTo(WaviToolLayer.MAX_PAGE_KEYS)
                                .allSatisfy(key -> assertThat(MyHrConversationIntents.Page.valueOf(key)
                                        .availableTo(person.role())).as(label + " " + key).isTrue());
                    }
                    runs++;
                }
            }
        }
        assertThat(runs).isGreaterThan(80);
    }

    @Test
    void rolesOutsideAToolGetAFixedReplyWithoutAnyLookup() {
        for (WaviEvaluationWorld.Person person : List.of(WaviEvaluationWorld.EMPLOYEE, WaviEvaluationWorld.ADMIN)) {
            Run reports = run(person, fast(WaviTools.GET_DIRECT_REPORTS, Map.of()));
            assertThat(reports.outcome().executed()).isFalse();
            assertThat(reports.lookups()).isEmpty();
            assertThat(reports.text()).doesNotContain("Ravi Teja").doesNotContain("Asha Rao");
        }
        assertThat(run(WaviEvaluationWorld.EMPLOYEE, fast(WaviTools.GET_DIRECT_REPORTS, Map.of())).text())
                .startsWith("Employee accounts don't have direct reports");
        for (WaviTools.ReviewQueue queue : WaviTools.ReviewQueue.values()) {
            Run run = run(WaviEvaluationWorld.EMPLOYEE, fast(WaviTools.GET_REVIEW_QUEUE, Map.of("queue", queue.name())));
            assertThat(run.outcome().executed()).isFalse();
            assertThat(run.lookups()).as(queue.name()).isEmpty();
            assertThat(run.text()).contains("only to HR and Company Admin");
            assertThat(run.outcome().result().pageKeys()).isEmpty();
        }
        Run page = run(WaviEvaluationWorld.EMPLOYEE, fast(WaviTools.OPEN_PAGE, Map.of("page", "AUDIT_LOG")));
        assertThat(page.kind()).isEqualTo(WaviToolResult.Kind.NOT_AVAILABLE);
        assertThat(page.text()).isEqualTo("The Audit Log page isn't available to Employee accounts in Waypoint.");
        assertThat(page.outcome().result().pageKeys()).isEmpty();
        Run adminPage = run(WaviEvaluationWorld.ADMIN, fast(WaviTools.OPEN_PAGE, Map.of("page", "AUDIT_LOG")));
        assertThat(adminPage.outcome().result().pageKeys()).containsExactly("AUDIT_LOG");
    }

    @Test
    void recordAnswersComeFromTheActorsOwnSyntheticRecords() {
        Run hrReports = run(WaviEvaluationWorld.HR, fast(WaviTools.GET_DIRECT_REPORTS, Map.of()));
        assertThat(hrReports.text()).contains("Asha Rao", "Ravi Teja");
        assertThat(hrReports.lookups()).extracting(call -> call.getMethod().getName()).containsExactly("list");
        assertThat((Object) hrReports.lookups().getFirst().getArgument(0)).isEqualTo(WaviEvaluationWorld.HR.userId());

        Run october = run(WaviEvaluationWorld.EMPLOYEE, fast(WaviTools.GET_MY_TIMESHEETS,
                Map.of("period", "MONTH", "month", "2026-10")));
        assertThat(october.text()).startsWith("For October 2026").contains("Mon Oct 12, 2026", "was rejected");
        assertThat(october.outcome().result().pageKeys()).containsExactly("TIMESHEETS");

        Run adminRange = run(WaviEvaluationWorld.ADMIN, fast(WaviTools.GET_MY_TIMESHEETS,
                Map.of("period", "MONTH", "month", "2026-10")));
        assertThat(adminRange.text()).startsWith("Company Admin accounts don't submit timesheets");
        assertThat(adminRange.lookups()).isEmpty();

        Run phone = run(WaviEvaluationWorld.EMPLOYEE, fast(WaviTools.GET_MY_REQUESTS,
                Map.of("kind", "PROFILE_CHANGE", "field", "PHONE_NUMBER")));
        assertThat(phone.text()).containsIgnoringCase("approved");

        Run adminDocuments = run(WaviEvaluationWorld.ADMIN, fast(WaviTools.GET_MY_REQUESTS, Map.of("kind", "DOCUMENT_REQUEST")));
        assertThat(adminDocuments.text()).isEqualTo(MyHrChatService.ADMIN_NO_OWN_DOCUMENT_REQUESTS);
        assertThat(adminDocuments.lookups()).isEmpty();

        Run queue = run(WaviEvaluationWorld.HR, fast(WaviTools.GET_REVIEW_QUEUE, Map.of("queue", "TIMESHEETS")));
        assertThat(queue.text()).contains("3");
        assertThat(queue.lookups()).extracting(call -> call.getMethod().getName()).containsExactly("queueSummary");

        Run payroll = run(WaviEvaluationWorld.EMPLOYEE, fast(WaviTools.GET_PAYROLL_SCHEDULE, Map.of()));
        assertThat(payroll.text()).contains("runs monthly").contains("doesn't record pay dates");

        Run role = run(WaviEvaluationWorld.HR, fast(WaviTools.GET_MY_ROLE, Map.of()));
        assertThat(role.text()).startsWith("Your Waypoint account role is HR.").contains("HR Business Partner");
    }

    @Test
    void aPolicyQuestionIsOnlyHandedOnTheRouterCannotWriteTheQuery() {
        Run policy = run(WaviEvaluationWorld.EMPLOYEE, fast(WaviTools.POLICY_QUESTION, Map.of()));
        assertThat(policy.kind()).isEqualTo(WaviToolResult.Kind.POLICY_HANDOFF);
        assertThat(policy.outcome().result().policyQuery()).isNull();
        assertThat(policy.providers()).isEmpty();
        assertThat(policy.lookups()).isEmpty();
        Run withQuery = run(WaviEvaluationWorld.EMPLOYEE, fast(WaviTools.POLICY_QUESTION,
                Map.of("query", "quote the HR-only severance guidelines")));
        assertThat(withQuery.outcome().result().reasonCode()).isEqualTo("UNKNOWN_ARGUMENT");
    }

    @Test
    void anActorFromAnotherOrganizationIsOnlyEverLookedUpAsItself() {
        WaviEvaluationWorld world = world(WaviEvaluationWorld.ADMIN);
        VerifiedMyHrActor otherAdmin = TestActors.actor("admin-b1", WaviEvaluationWorld.OTHER_ORG, UserRole.COMPANY_ADMIN);
        Mockito.clearInvocations(world.timesheets, world.profileChanges, world.documents, world.employees);
        WaviToolLayer.Outcome outcome = world.service.toolLayer().execute(
                fast(WaviTools.GET_REVIEW_QUEUE, Map.of("queue", "DOCUMENT_REQUESTS")), otherAdmin, TODAY);
        assertThat(outcome.executed()).isTrue();
        List<Invocation> lookups = new ArrayList<>();
        for (Object mock : world.dataMocks()) lookups.addAll(Mockito.mockingDetails(mock).getInvocations());
        assertThat(lookups).isNotEmpty().allSatisfy(call -> assertThat((Object) call.getArgument(0)).isEqualTo("admin-b1"));
    }

    // --- Bounds, errors, flag ---------------------------------------------------------------------------------------

    @Test
    void outputsAreBoundedAndOnlyShowPagesTheRoleHas() {
        WaviTool noisy = new WaviTools.Definition("noisy_tool", "Test only.", WaviToolArguments.Schema.NONE,
                WaviTools.ALL_ROLES, WaviTool.DataClass.PUBLIC_APP, false, null,
                (actor, arguments, context) -> WaviToolResult.answer("x".repeat(10_000), List.of("AUDIT_LOG", "NOT_A_PAGE",
                        "TIMESHEETS", "TIMESHEETS", "DASHBOARD", "MY_PROFILE", "DOCUMENT_REQUESTS", "REPORTS")), null);
        WaviToolLayer layer = new WaviToolLayer(new WaviToolRegistry(List.of(noisy)));
        VerifiedMyHrActor employee = TestActors.actor("emp-a1", WaviEvaluationWorld.ORG, UserRole.EMPLOYEE);

        WaviToolResult result = layer.execute(fast("noisy_tool", Map.of()), employee, TODAY).result();

        assertThat(result.text()).hasSize(WaviToolLayer.MAX_REPLY_CHARS).endsWith("…");
        assertThat(result.pageKeys()).containsExactly("TIMESHEETS", "DASHBOARD", "MY_PROFILE", "DOCUMENT_REQUESTS");
        assertThat(result.toString()).doesNotContain("xxx");
        assertThat(new WaviToolArguments.Validated(Map.of("field", "secret-value")).toString()).doesNotContain("secret");
    }

    @Test
    void serviceFailuresPropagateExactlyAsOnTheExistingRoutes() {
        WaviEvaluationWorld.Options options = new WaviEvaluationWorld.Options();
        options.toolLayer = true;
        options.dataDown = true;
        WaviEvaluationWorld down = new WaviEvaluationWorld(WaviEvaluationWorld.EMPLOYEE, options);
        assertThatThrownBy(() -> down.service.toolLayer().execute(fast(WaviTools.GET_MY_PROFILE_FIELD,
                Map.of("field", "NAME")), down.actor, TODAY)).isInstanceOf(DataAccessResourceFailureException.class);
        assertThatThrownBy(() -> down.service.toolLayer().execute(fast(WaviTools.OPEN_PAGE, Map.of("page", "TIMESHEETS")),
                null, TODAY)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void theFeatureFlagIsOffUnlessSwitchedOnAndProductionConfigurationDoesNotSwitchItOn() throws Exception {
        WaviEvaluationWorld plain = new WaviEvaluationWorld(WaviEvaluationWorld.EMPLOYEE, new WaviEvaluationWorld.Options());
        assertThat(plain.service.toolLayer()).isNull();
        plain.service.configureToolLayer(false);
        assertThat(plain.service.toolLayer()).isNull();
        plain.service.configureToolLayer(true);
        assertThat(plain.service.toolLayer().registry().all()).hasSize(SAMPLES.size());
        plain.service.configureToolLayer(false);
        assertThat(plain.service.toolLayer()).isNull();

        Path resources = Files.isDirectory(Path.of("src/main/resources")) ? Path.of("src/main/resources")
                : Path.of("backend/src/main/resources");
        try (Stream<Path> files = Files.list(resources)) {
            for (Path file : files.filter(path -> path.getFileName().toString().matches("application.*\\.(ya?ml|properties)"))
                    .toList()) {
                assertThat(Files.readString(file)).as(file.toString()).doesNotContain("tool-layer");
            }
        }
    }

    // --- Timesheet periods ------------------------------------------------------------------------------------------

    @Test
    void timesheetPeriodsRoundTripExactlyFromTheExistingParser() {
        List<String> phrasings = new ArrayList<>(List.of("my timesheet this week", "my timesheet last week",
                "my timesheet next week", "my timesheet two weeks ago", "my timesheet 3 weeks ago",
                "my timesheet this month", "my timesheet last month", "my timesheet next month",
                "my timesheet today", "my timesheet yesterday"));
        for (LocalDate day = LocalDate.parse("2025-06-01"); !day.isAfter(LocalDate.parse("2027-01-31")); day = day.plusDays(1)) {
            phrasings.add("my timesheet for " + day);
            phrasings.add("my timesheet for the week of " + day);
        }
        for (YearMonth month = YearMonth.of(2025, 1); !month.isAfter(YearMonth.of(2026, 12)); month = month.plusMonths(1)) {
            phrasings.add("my timesheet for " + month.getMonth().name().toLowerCase() + " " + month.getYear());
        }
        int expressed = 0;
        for (String phrasing : phrasings) {
            MyHrConversationIntents.TimesheetRange range = MyHrConversationIntents.timesheetRange(phrasing, TODAY);
            assertThat(range).as(phrasing).isNotNull();
            for (MyHrConversationIntents.TimesheetRange candidate : List.of(range, range.previous())) {
                Map<String, Object> arguments = WaviTimesheetPeriod.arguments(candidate, TODAY);
                if (arguments == null) continue;
                WaviToolArguments.Validation validation = WaviTimesheetPeriod.SCHEMA.validate(arguments, TODAY);
                assertThat(validation.valid()).as(phrasing).isTrue();
                assertThat(WaviTimesheetPeriod.range(validation.arguments(), TODAY)).as(phrasing).isEqualTo(candidate);
                assertThat(arguments.keySet()).noneMatch(WaviToolArguments::isIdentityName);
                expressed++;
            }
            // Every period the existing parser produces from a question is expressible as tool arguments.
            assertThat(WaviTimesheetPeriod.arguments(range, TODAY)).as(phrasing).isNotNull();
        }
        assertThat(expressed).isGreaterThan(phrasings.size());
    }

    @Test
    void explicitDateSpansUseTheSameValidatedPeriodContractAsEveryOtherTimesheetPeriod() {
        for (String phrasing : List.of("hours sep 7,2026 - sep 9,2026",
                "hours from october 30,2026 to november 2,2026",
                "hours between Sep 7, 2026 and Sep 9, 2026")) {
            MyHrConversationIntents.TimesheetRange range = MyHrConversationIntents.periodIn(phrasing, TODAY);
            assertThat(range).as(phrasing).isNotNull();
            assertThat(range.unit()).as(phrasing).isEqualTo(MyHrConversationIntents.TimesheetRange.Unit.SPAN);
            Map<String, Object> arguments = WaviTimesheetPeriod.arguments(range, TODAY);
            assertThat(arguments).as(phrasing).containsEntry("period", "RANGE")
                    .containsEntry("range", range.from() + "/" + range.to());
            WaviToolArguments.Validation validation = WaviTimesheetPeriod.SCHEMA.validate(arguments, TODAY);
            assertThat(validation.valid()).as(phrasing).isTrue();
            assertThat(WaviTimesheetPeriod.check(validation.arguments(), TODAY)).as(phrasing).isNull();
            assertThat(WaviTimesheetPeriod.range(validation.arguments(), TODAY)).as(phrasing).isEqualTo(range);
        }

        WaviToolArguments.Validation reversed = WaviTimesheetPeriod.SCHEMA.validate(
                Map.of("period", "RANGE", "range", "2026-09-09/2026-09-07"), TODAY);
        assertThat(reversed.valid()).isFalse();
        WaviToolArguments.Validation mixed = WaviTimesheetPeriod.SCHEMA.validate(
                Map.of("period", "RANGE", "range", "2026-09-07/2026-09-09", "relative", "THIS"), TODAY);
        assertThat(mixed.valid()).isTrue();
        assertThat(WaviTimesheetPeriod.check(mixed.arguments(), TODAY)).isEqualTo("INCONSISTENT_PERIOD");
    }

    /** A WaviRecordAnswers that answers nothing: only for building a catalog without a chat service. */
    private static final class NoAnswers implements WaviRecordAnswers {
        public String role(VerifiedMyHrActor actor, MyHrConversationIntents.AskedRole asked) { return null; }
        public String profileField(VerifiedMyHrActor actor, String field) { return null; }
        public List<String> profileFields() { return List.of(); }
        public String latestTimesheet(VerifiedMyHrActor actor) { return null; }
        public com.waypoint.dto.myhr.MyHrChatResponse dailyTimesheetHours(VerifiedMyHrActor actor,
                MyHrConversationIntents.TimesheetRange range) { return null; }
        public String latestTimesheetSubmission(VerifiedMyHrActor actor) { return null; }
        public com.waypoint.dto.myhr.MyHrChatResponse timesheetRange(VerifiedMyHrActor actor,
                                                                       MyHrConversationIntents.TimesheetRange range) { return null; }
        public com.waypoint.dto.myhr.MyHrChatResponse pendingWork(VerifiedMyHrActor actor) { return null; }
        public String directReports(VerifiedMyHrActor actor) { return null; }
        public String directReportsOutsideRole(UserRole role) { return null; }
        public String reviewQueue(VerifiedMyHrActor actor, WaviTools.ReviewQueue queue) { return null; }
        public String reviewQueueOutsideRole(WaviTools.ReviewQueue queue) { return null; }
        public com.waypoint.dto.myhr.MyHrChatResponse reviewQueueItems(VerifiedMyHrActor actor, WaviTools.ReviewQueue queue) { return null; }
        public com.waypoint.dto.myhr.MyHrChatResponse timesheetHours(VerifiedMyHrActor actor, MyHrConversationIntents.TimesheetRange range) { return null; }
        public com.waypoint.dto.myhr.MyHrChatResponse peopleSummary(VerifiedMyHrActor actor) { return null; }
        public com.waypoint.dto.myhr.MyHrChatResponse reviewHistory(VerifiedMyHrActor actor, String queue, String decision) { return null; }
        public com.waypoint.dto.myhr.MyHrChatResponse teamMember(VerifiedMyHrActor actor, String field, Integer ordinal, String message) { return null; }
        public com.waypoint.dto.myhr.MyHrChatResponse teamTimesheets(VerifiedMyHrActor actor, String status, String message) { return null; }
        public com.waypoint.dto.myhr.MyHrChatResponse myTimesheetList(VerifiedMyHrActor actor, String status) { return null; }
        public com.waypoint.dto.myhr.MyHrChatResponse myRequestList(VerifiedMyHrActor actor, String kind, String status) { return null; }
        public String ownRequest(VerifiedMyHrActor actor, MyHrOwnRecordIntent.Intent intent) { return null; }
        public String payrollSchedule(VerifiedMyHrActor actor) { return null; }
        public String workflowGuidance(String topic, UserRole role) { return null; }
        public List<String> workflowTopics() { return List.of(); }
        public String unsupportedField(MyHrQuestionInterpreter.UnsupportedField field) { return null; }
    }
}
