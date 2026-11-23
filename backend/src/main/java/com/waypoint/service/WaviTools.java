package com.waypoint.service;

import com.waypoint.enums.ProfileChangeField;
import com.waypoint.enums.RequestedDocumentType;
import com.waypoint.enums.UserRole;
import com.waypoint.security.VerifiedMyHrActor;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static com.waypoint.service.WaviToolArguments.Schema;
import static com.waypoint.service.WaviToolArguments.Spec;
import static com.waypoint.service.WaviToolArguments.Validated;

/**
 * Wavi's tool catalog. Every tool is read-only and answers through {@link WaviRecordAnswers}: the same
 * authorized lookups and reply code the deterministic routes use, scoped to the verified actor. No tool takes a
 * user, employee, organization or role argument, and no tool accepts free text.
 */
final class WaviTools {

    private WaviTools() {
    }

    static final String OPEN_PAGE = "open_page";
    static final String GET_MY_ROLE = "get_my_role";
    static final String GET_MY_PROFILE_FIELD = "get_my_profile_field";
    static final String UNSUPPORTED_FIELD = "unsupported_field";
    static final String GET_MY_TIMESHEETS = "get_my_timesheets";
    static final String GET_MY_PENDING_WORK = "get_my_pending_work";
    static final String GET_DIRECT_REPORTS = "get_direct_reports";
    static final String GET_REVIEW_QUEUE = "get_review_queue";
    static final String GET_MY_REQUESTS = "get_my_requests";
    static final String GET_PAYROLL_SCHEDULE = "get_payroll_schedule";
    static final String EXPLAIN_TERM = "explain_term";
    static final String WORKFLOW_HELP = "workflow_help";
    static final String POLICY_QUESTION = "policy_question";
    static final String GENERAL_HELP = "general_help";
    static final String CLARIFY = "clarify";
    static final String OUT_OF_SCOPE = "out_of_scope";
    static final String UNSUPPORTED_REQUEST = "unsupported_request";
    static final String GET_TEAM_MEMBER = "get_team_member";
    static final String GET_TEAM_TIMESHEETS = "get_team_timesheets";
    static final String LIST_MY_TIMESHEETS = "list_my_timesheets";
    static final String LIST_MY_REQUESTS = "list_my_requests";
    static final String GET_MY_REVIEW_HISTORY = "get_my_review_history";
    static final String GET_PEOPLE_SUMMARY = "get_people_summary";

    enum ReviewHistoryQueue { TIMESHEETS, PROFILE_CHANGES }

    enum ReviewDecision { ANY, APPROVED, REJECTED }

    /** The work details of a person in the reviewer's scope that chat shows. Never personal contact, pay or dates. */
    enum TeamField { SUMMARY, JOB_TITLE, EMPLOYEE_ID, ROLE, REPORTING_MANAGER, WORK_EMAIL }

    enum TimesheetStatusFilter { ANY, NEEDS_ACTION, DRAFT, SUBMITTED, APPROVED, REJECTED }

    enum RequestListKind { DOCUMENT_REQUEST, PROFILE_CHANGE }

    enum RequestStatusFilter { ANY, OPEN, COMPLETED, REJECTED }

    /** The user's own profile details the existing profile route answers. */
    enum ProfileField { PROFILE, NAME, FIRST_NAME, LAST_NAME, EMPLOYEE_ID, JOB_TITLE, DEPARTMENT, WORK_EMAIL,
        REPORTING_MANAGER }

    enum ReviewQueue { TIMESHEETS, PROFILE_CHANGES, DOCUMENT_REQUESTS }

    /**
     * Which kind of pending work is asked about. Reviewers: the matching review queue (items waiting for THEIR
     * action). Employees: their own items waiting for THEIR action (drafts and rejected timesheets to correct, open
     * requests). ALL: every kind.
     */
    enum WorkArea { ALL, TIMESHEETS, PROFILE_CHANGES, DOCUMENT_REQUESTS }

    /** LIST: each submission with status, period, submission time and hours. HOURS: hours within the period asked. */
    enum TeamTimesheetDetail { LIST, HOURS }

    /** COUNT: how many are waiting. LIST: who and what is waiting (names, periods or types, dates). */
    enum ReviewDetail { COUNT, LIST }

    enum RoleDetail { ACCOUNT, WORK_CONTEXT }

    enum ClarificationTopic { TIMESHEETS, REQUESTS, PEOPLE, WORK, GENERAL }
    enum MissingSlot { SUBJECT, SCOPE, PERIOD, PERSON, DETAIL }

    enum RequestKind { PROFILE_CHANGE, DOCUMENT_REQUEST, OFFER_LETTER }

    /**
     * Why a work, HR or Waypoint question cannot be answered by any tool: the router's honest alternative to the
     * nearest-looking tool. (A question unrelated to work is out_of_scope instead.)
     */
    enum UnsupportedReason {
        /** History, trends or counts over a past date range (e.g. what a team submitted last week). */
        HISTORY_OR_DATE_RANGE,
        /** Another person's records or details. */
        OTHER_PERSON,
        /** Counts or lists of people by role, job title, manager or team ("how many managers are there?"). */
        PEOPLE_AGGREGATE,
        /** Changing anything: approve, reject, submit, update, delete, send. */
        WRITE_ACTION,
        /** Something Waypoint does not record or chat cannot look up. */
        NOT_AVAILABLE_IN_WAYPOINT
    }

    static final String HISTORY_REPLY = "I can't look up history or counts for a past period in chat. I can tell you "
            + "what is waiting for review right now, or the status of your own timesheets for a day, week or month. "
            + "Open Timesheets for earlier submissions.";
    static final String PEOPLE_AGGREGATE_REPLY = "I can't count or list people by role, job title or team in chat. "
            + "HR and Company Admin accounts can see employees and their reporting managers on the Employees page; "
            + "HR users can also ask me who reports to them.";
    static final String NOT_AVAILABLE_REPLY = "That isn't something I can look up in Waypoint chat. Open the related "
            + "Waypoint page, or ask HR.";

    /** The timesheet-upload troubleshooting topic; the others are the existing workflow guidance topics. */
    static final String TIMESHEET_UPLOAD_TOPIC = "TIMESHEET_UPLOAD";

    static final Set<UserRole> ALL_ROLES = Set.copyOf(EnumSet.of(UserRole.EMPLOYEE, UserRole.HR, UserRole.COMPANY_ADMIN));
    static final Set<UserRole> REVIEWERS = Set.copyOf(EnumSet.of(UserRole.HR, UserRole.COMPANY_ADMIN));
    static final Set<UserRole> HR_ONLY = Set.of(UserRole.HR);

    private static final String TIMESHEETS_PAGE = MyHrConversationIntents.Page.TIMESHEETS.name();
    private static final String EMPLOYEES_PAGE = MyHrConversationIntents.Page.EMPLOYEES.name();
    private static final String PROFILE_PAGE = MyHrConversationIntents.Page.MY_PROFILE.name();

    interface Executor {
        WaviToolResult run(VerifiedMyHrActor actor, Validated arguments, WaviTool.Context context);
    }

    interface OutsideRole {
        WaviToolResult reply(UserRole role, Validated arguments, WaviTool.Context context);
    }

    interface ArgumentCheck {
        String check(Validated arguments, WaviTool.Context context);
    }

    /** One tool, declared as data plus its executor. */
    record Definition(String name, String description, Schema schema, Set<UserRole> allowedRoles,
                      WaviTool.DataClass dataClass, boolean shareable, ArgumentCheck argumentCheck, Executor executor,
                      OutsideRole outside) implements WaviTool {

        Definition {
            allowedRoles = Set.copyOf(allowedRoles);
        }

        @Override
        public boolean externallyShareable() {
            return shareable;
        }

        @Override
        public String checkArguments(Validated arguments, Context context) {
            return argumentCheck == null ? null : argumentCheck.check(arguments, context);
        }

        @Override
        public WaviToolResult execute(VerifiedMyHrActor actor, Validated arguments, Context context) {
            return executor.run(actor, arguments, context);
        }

        @Override
        public WaviToolResult outsideRole(UserRole role, Validated arguments, Context context) {
            return outside == null
                    ? WaviToolResult.notAvailable("ROLE_NOT_ALLOWED", WaviRouterPolicyGate.CANNOT_HELP_REPLY, List.of())
                    : outside.reply(role, arguments, context);
        }

        @Override
        public String toString() {
            return "WaviTool[" + name + "]";
        }
    }

    /**
     * A team-timesheet period is optional; when given it must be one consistent day, week, month or explicit range (never LATEST,
     * which selects one record rather than a period). Null when consistent.
     */
    static String teamPeriodProblem(WaviToolArguments.Validated arguments, java.time.LocalDate today) {
        boolean periodDetail = arguments.has("date") || arguments.has("month") || arguments.has("range")
                || arguments.has("relative") || arguments.has("weeksAgo");
        if (!arguments.has("period")) return periodDetail ? "INCONSISTENT_PERIOD" : null;
        if (arguments.enumValue("period", WaviTimesheetPeriod.Period.class) == WaviTimesheetPeriod.Period.LATEST) {
            return "INCONSISTENT_PERIOD";
        }
        return WaviTimesheetPeriod.range(arguments, today) == null ? "INCONSISTENT_PERIOD" : null;
    }

    static List<WaviTool> catalog(WaviRecordAnswers answers) {
        List<WaviTool> tools = new ArrayList<>();

        tools.add(new Definition(OPEN_PAGE, "Open a Waypoint page for the user (shows a button to it). Navigation only: answers no question about data.",
                Schema.of(Spec.enumOf("page", true, MyHrConversationIntents.Page.class, "The page to open")),
                ALL_ROLES, WaviTool.DataClass.PUBLIC_APP, true, null,
                (actor, arguments, context) -> {
                    MyHrConversationIntents.Page page = arguments.enumValue("page", MyHrConversationIntents.Page.class);
                    // A page the verified role does not have: said plainly, with no button.
                    return page.availableTo(actor.role())
                            ? WaviToolResult.answer(MyHrConversationIntents.navigationReply(page), List.of(page.name()))
                            : WaviToolResult.notAvailable("PAGE_NOT_AVAILABLE",
                            MyHrConversationIntents.unavailablePageReply(page, actor.role()), List.of());
                }, null));

        tools.add(new Definition(GET_MY_ROLE, "The user's own Waypoint account role (Employee, HR, Company Admin), or whether they have a given role. "
                + "With asked ANY, also their recorded job title. detail WORK_CONTEXT for what their work/job is or assigned responsibilities: "
                + "shows role/title and explains that this profile lookup does not contain duties. Never pending tasks or another person's role.",
                Schema.of(Spec.enumOf("asked", false, MyHrConversationIntents.AskedRole.class,
                        "The role asked about; ANY (default) when the user asks what their role is"),
                        Spec.enumOf("detail", false, RoleDetail.class, "ACCOUNT by default; WORK_CONTEXT for job/work responsibilities")),
                ALL_ROLES, WaviTool.DataClass.PERSONAL, false, null,
                (actor, arguments, context) -> {
                    MyHrConversationIntents.AskedRole asked = arguments.has("asked")
                            ? arguments.enumValue("asked", MyHrConversationIntents.AskedRole.class)
                            : MyHrConversationIntents.AskedRole.ANY;
                    boolean work = "WORK_CONTEXT".equals(arguments.text("detail"));
                    return WaviToolResult.answer(answers.role(actor, work ? MyHrConversationIntents.AskedRole.ANY : asked)
                            + (work ? WaviCapabilities.WORK_CONTEXT_NOTE : ""), List.of());
                }, null));

        tools.add(new Definition(GET_MY_PROFILE_FIELD,
                "One detail of the user's OWN profile (name, employee ID, job title, reporting manager) or a summary. Never "
                        + "another person's profile; not phone, email, address, pay or leave balance (see unsupported_field).",
                Schema.of(Spec.enumOf("field", true, ProfileField.class, "The detail asked about")),
                ALL_ROLES, WaviTool.DataClass.PERSONAL, false, null,
                (actor, arguments, context) -> WaviToolResult.answer(
                        answers.profileField(actor, arguments.enumValue("field", ProfileField.class).name()), null),
                null));

        tools.add(new Definition(UNSUPPORTED_FIELD,
                "A personal detail Wavi does not show in chat (phone, personal email, address, start date, employment "
                        + "type, pay, leave balance): an honest reply saying where to find it.",
                Schema.of(Spec.enumOf("field", true, MyHrQuestionInterpreter.UnsupportedField.class, "The detail asked about")),
                ALL_ROLES, WaviTool.DataClass.PUBLIC_APP, false, null,
                (actor, arguments, context) -> {
                    MyHrQuestionInterpreter.UnsupportedField field =
                            arguments.enumValue("field", MyHrQuestionInterpreter.UnsupportedField.class);
                    boolean onProfile = field != MyHrQuestionInterpreter.UnsupportedField.PAY
                            && field != MyHrQuestionInterpreter.UnsupportedField.LEAVE_BALANCE;
                    return WaviToolResult.notAvailable("FIELD_NOT_SHOWN", answers.unsupportedField(field),
                            onProfile ? List.of(PROFILE_PAGE) : List.of());
                }, null));

        tools.add(new Definition(GET_MY_TIMESHEETS,
                "The user's OWN timesheets: the latest one, or those covering one day, week, month or explicit date range. detail STATUS "
                        + "(default): status and dates by period. detail SUBMISSION_DATE with period LATEST: when the user last submitted, selected by submittedAt, with its status. detail HOURS: period hours; DAILY_HOURS: actual entries per date, required for every-day or daily-comparison questions; PERIOD: only the dates selected, including follow-ups asking which day. Only the user's own: "
                        + "not a team's or another person's timesheets, not counts over a period.",
                WaviTimesheetPeriod.SCHEMA, ALL_ROLES, WaviTool.DataClass.PERSONAL, false,
                (arguments, context) -> WaviTimesheetPeriod.check(arguments, context.today()),
                (actor, arguments, context) -> {
                    if ("DAILY_HOURS".equals(arguments.text("detail"))) {
                        var daily = answers.dailyTimesheetHours(actor, WaviTimesheetPeriod.range(arguments, context.today()));
                        return daily == null ? WaviToolResult.notAvailable("NO_LOOKUP", WaviRouterPolicyGate.CANNOT_HELP_REPLY, null)
                                : WaviToolResult.answer(daily.message(), daily.actions());
                    }
                    if ("PERIOD".equals(arguments.text("detail"))) {
                        var range = WaviTimesheetPeriod.range(arguments, context.today());
                        return range == null ? WaviToolResult.clarify("Which timesheet date or period do you mean?")
                                : WaviToolResult.answer(range.singleDay() ? "The selected date is " + range.from() + "."
                                        : "The selected period is " + range.from() + " to " + range.to() + ".", null);
                    }
                    boolean hours = arguments.has("detail") && "HOURS".equals(arguments.text("detail"));
                    if (arguments.enumValue("period", WaviTimesheetPeriod.Period.class) == WaviTimesheetPeriod.Period.LATEST) {
                        if ("SUBMISSION_DATE".equals(arguments.text("detail"))) {
                            return WaviToolResult.answer(answers.latestTimesheetSubmission(actor), null);
                        }
                        if (hours) {
                            var latest = answers.timesheetHours(actor, null);
                            return latest == null ? WaviToolResult.notAvailable("NO_LOOKUP",
                                    WaviRouterPolicyGate.CANNOT_HELP_REPLY, null)
                                    : WaviToolResult.answer(latest.message(), latest.actions());
                        }
                        return WaviToolResult.answer(answers.latestTimesheet(actor), null);
                    }
                    if (hours) {
                        var recorded = answers.timesheetHours(actor, WaviTimesheetPeriod.range(arguments, context.today()));
                        return recorded == null ? WaviToolResult.notAvailable("NO_LOOKUP",
                                WaviRouterPolicyGate.CANNOT_HELP_REPLY, null)
                                : WaviToolResult.answer(recorded.message(), recorded.actions());
                    }
                    var response = answers.timesheetRange(actor, WaviTimesheetPeriod.range(arguments, context.today()));
                    return WaviToolResult.answer(response.message(), response.actions());
                }, null));

        tools.add(new Definition(GET_MY_PENDING_WORK,
                "Work waiting RIGHT NOW. HR and Company Admin: items waiting for THEIR review (submitted timesheets, "
                        + "pending profile changes, document requests); a rejected timesheet waits for the employee, not "
                        + "the reviewer. Employee: their own items waiting for them (drafts, rejected timesheets to "
                        + "correct, open requests). area narrows to one kind (\"pending work for timesheets\" = "
                        + "TIMESHEETS); ordinal (reviewers) narrows to one person in their scope. Not history.",
                Schema.of(Spec.enumOf("area", false, WorkArea.class, "ALL (default) or one kind of work"),
                        Spec.integer("ordinal", false, 1, 50, "Reviewers: position of the person in their people list")),
                ALL_ROLES, WaviTool.DataClass.PERSONAL, false, null,
                (actor, arguments, context) -> {
                    WorkArea area = arguments.has("area") ? arguments.enumValue("area", WorkArea.class) : null;
                    Integer ordinal = arguments.has("ordinal") ? arguments.integer("ordinal") : null;
                    var response = answers.pendingWork(actor, area, ordinal);
                    return WaviToolResult.answer(response.message(), response.actions());
                }, null));

        tools.add(new Definition(GET_DIRECT_REPORTS, "HR: the names of the people who currently report directly to the user. Not their records, timesheets or "
                        + "submissions.",
                Schema.NONE, HR_ONLY, WaviTool.DataClass.ORG_AGGREGATE, false, null,
                (actor, arguments, context) -> WaviToolResult.answer(answers.directReports(actor), List.of(EMPLOYEES_PAGE)),
                (role, arguments, context) -> WaviToolResult.notAvailable("NO_DIRECT_REPORTS_LOOKUP",
                        answers.directReportsOutsideRole(role),
                        role == UserRole.EMPLOYEE ? List.of() : List.of(EMPLOYEES_PAGE))));

        tools.add(new Definition(GET_REVIEW_QUEUE,
                "HR and Company Admin: the timesheets, profile change requests or document requests waiting for the "
                        + "user's review RIGHT NOW. detail COUNT (default): how many. detail LIST: who and what is "
                        + "waiting (employee names with periods, change types or document types and dates), e.g. \"who "
                        + "needs my approval\", \"for whom?\", \"who submitted them?\". Only the user's own review "
                        + "scope. Not history, not past date ranges, not other employees' details.",
                Schema.of(Spec.enumOf("queue", true, ReviewQueue.class, "Which review queue"),
                        Spec.enumOf("detail", false, ReviewDetail.class, "COUNT (default) or LIST")),
                REVIEWERS, WaviTool.DataClass.ORG_AGGREGATE, false, null,
                (actor, arguments, context) -> {
                    ReviewQueue queue = arguments.enumValue("queue", ReviewQueue.class);
                    if (arguments.has("detail")
                            && arguments.enumValue("detail", ReviewDetail.class) == ReviewDetail.LIST) {
                        var listed = answers.reviewQueueItems(actor, queue);
                        return listed == null ? WaviToolResult.notAvailable("QUEUE_UNAVAILABLE",
                                WaviRouterPolicyGate.CANNOT_HELP_REPLY, null)
                                : WaviToolResult.answer(listed.message(), listed.actions());
                    }
                    String text = answers.reviewQueue(actor, queue);
                    return text == null
                            ? WaviToolResult.notAvailable("QUEUE_UNAVAILABLE", WaviRouterPolicyGate.CANNOT_HELP_REPLY, null)
                            : WaviToolResult.answer(text, null);
                },
                (role, arguments, context) -> WaviToolResult.notAvailable("REVIEWERS_ONLY",
                        answers.reviewQueueOutsideRole(arguments.enumValue("queue", ReviewQueue.class)), List.of())));

        tools.add(new Definition(GET_MY_REQUESTS,
                "The user's OWN profile change, document or offer letter requests: status, dates or rejection. Not anyone "
                        + "else's requests; cannot create or change a request.",
                Schema.of(Spec.enumOf("kind", true, RequestKind.class, "Which kind of request"),
                        Spec.enumOf("aspect", false, MyHrOwnRecordIntent.Aspect.class, "What about it (default STATUS)"),
                        Spec.enumOf("field", false, ProfileChangeField.class, "PROFILE_CHANGE only: one changed detail"),
                        Spec.enumOf("documentType", false, RequestedDocumentType.class, "DOCUMENT_REQUEST only")),
                ALL_ROLES, WaviTool.DataClass.PERSONAL, false,
                (arguments, context) -> {
                    RequestKind kind = arguments.enumValue("kind", RequestKind.class);
                    if (arguments.has("field") && kind != RequestKind.PROFILE_CHANGE) return "INCONSISTENT_REQUEST";
                    if (arguments.has("documentType") && kind != RequestKind.DOCUMENT_REQUEST) return "INCONSISTENT_REQUEST";
                    return null;
                },
                (actor, arguments, context) -> {
                    String text = answers.ownRequest(actor, requestIntent(actor.role(), arguments));
                    return text == null
                            ? WaviToolResult.notAvailable("NO_LOOKUP", WaviRouterPolicyGate.CANNOT_HELP_REPLY, null)
                            : WaviToolResult.answer(text, null);
                }, null));

        tools.add(new Definition(GET_PAYROLL_SCHEDULE,
                "How often the user's organization runs payroll in Waypoint. Waypoint records no pay dates or pay amounts.",
                Schema.NONE, ALL_ROLES, WaviTool.DataClass.PERSONAL, false, null,
                (actor, arguments, context) -> WaviToolResult.answer(answers.payrollSchedule(actor), null), null));

        tools.add(new Definition(EXPLAIN_TERM, "What a Waypoint concept means: PAYROLL, PAYROLL_READINESS, or TIMESHEET_PERIOD (how "
                        + "Waypoint timesheet periods and days work, e.g. \"does every timesheet have 6 days?\", \"why do "
                        + "two of my timesheets overlap?\"). Only these concepts.",
                Schema.of(Spec.enumOf("term", true, MyHrConversationIntents.Term.class, "The term")),
                ALL_ROLES, WaviTool.DataClass.PUBLIC_APP, true, null,
                (actor, arguments, context) -> {
                    MyHrConversationIntents.Term term = arguments.enumValue("term", MyHrConversationIntents.Term.class);
                    boolean reviewer = actor.role() == UserRole.HR || actor.role() == UserRole.COMPANY_ADMIN;
                    return WaviToolResult.answer(MyHrConversationIntents.definitionReply(term, actor.role()),
                            term == MyHrConversationIntents.Term.PAYROLL_READINESS && reviewer
                                    ? List.of(MyHrConversationIntents.Page.PAYROLL_READINESS.name())
                                    : List.of(TIMESHEETS_PAGE));
                }, null));

        List<String> topics = new ArrayList<>();
        topics.add(TIMESHEET_UPLOAD_TOPIC);
        topics.addAll(answers.workflowTopics());
        tools.add(new Definition(WORKFLOW_HELP, "How the user does something in Waypoint (role-specific), "
                        + "including actions Wavi can't do itself. Topics: TIMESHEET_ENTRY = enter and submit a "
                        + "timesheet; TIMESHEET_UPLOAD = ONLY file upload problems; TIMESHEET_CORRECTION = fix and "
                        + "resubmit; TIMESHEET_REJECTED = after rejection; TIMESHEET_REVIEW = approve or reject timesheets; "
                        + "TIMESHEET_STATUS = timesheet history; PROFILE_CHANGE = request a profile change; "
                        + "PROFILE_CHANGE_REVIEW = approve profile changes; DOCUMENT_REQUEST = request a document; "
                        + "DOCUMENT_REQUEST_STATUS = document request history; DOCUMENT_APPROVAL = approve documents; "
                        + "OFFER_LETTER = request an offer letter; OFFER_LETTER_COPY = copy of one; "
                        + "ONBOARDING_UPLOAD = upload onboarding documents; EMPLOYEE_CREATE = add an employee; "
                        + "ORGANIZATION_SETTINGS = payroll period etc.",
                Schema.of(Spec.enumOf("topic", true, topics, "The workflow")),
                ALL_ROLES, WaviTool.DataClass.PUBLIC_APP, true, null,
                (actor, arguments, context) -> {
                    String topic = arguments.text("topic");
                    if (TIMESHEET_UPLOAD_TOPIC.equals(topic)) {
                        return WaviToolResult.answer(MyHrConversationIntents.TIMESHEET_UPLOAD_HELP, List.of(TIMESHEETS_PAGE));
                    }
                    String text = answers.workflowGuidance(topic, actor.role());
                    return text == null
                            ? WaviToolResult.notAvailable("NO_GUIDANCE", WaviRouterPolicyGate.CANNOT_HELP_REPLY, null)
                            : WaviToolResult.answer(text, null);
                }, null));

        tools.add(new Definition(GET_TEAM_MEMBER,
                "HR and Company Admin: a work detail of ONE person they can already see in Waypoint (HR: their active "
                        + "direct reports; Company Admin: their organization) - job title, employee ID, role, reporting "
                        + "manager, work email or a summary. The server resolves the person from the original message by "
                        + "name or Waypoint employee ID, or from ordinal, the position in the list Wavi just showed (\"the second person\" = 2). "
                        + "Never pay, personal contact, documents or anyone outside that scope.",
                Schema.of(Spec.enumOf("field", false, TeamField.class, "The detail asked about (SUMMARY by default)"),
                        Spec.integer("ordinal", false, 1, 50, "Position in the list just shown, when no name is given")),
                REVIEWERS, WaviTool.DataClass.ORG_AGGREGATE, false, null,
                (actor, arguments, context) -> listed(answers.teamMember(actor,
                        arguments.has("field") ? arguments.text("field") : TeamField.SUMMARY.name(),
                        arguments.has("ordinal") ? arguments.integer("ordinal") : null,
                        context.message())),
                (role, arguments, context) -> WaviToolResult.notAvailable("OWN_RECORDS_ONLY",
                        MyHrChatService.OTHER_PERSON_REPLY, List.of())));

        tools.add(new Definition(GET_TEAM_TIMESHEETS,
                "HR and Company Admin: timesheets of people in the user's review scope (HR: timesheets naming them as "
                        + "reviewer from their active direct reports; Company Admin: the organization), each submission "
                        + "with status, period, submission time and hours, optionally for one person named in the message "
                        + "(\"show Mani's timesheets\") or by ordinal, one status (\"which ones were rejected\") and one "
                        + "day, week, month or explicit range (period/date/month/range/relative/weeksAgo, as get_my_timesheets). detail HOURS "
                        + "for hours worked within that period (\"how many hours did Mani work last week\"). Newest first, "
                        + "at most 10.",
                Schema.of(Spec.enumOf("status", false, TimesheetStatusFilter.class, "ANY by default"),
                        Spec.enumOf("period", false, WaviTimesheetPeriod.Period.class, "DAY, WEEK or MONTH when one is asked"),
                        Spec.date("date", false, WaviTimesheetPeriod.PAST_DAYS, WaviTimesheetPeriod.FUTURE_DAYS,
                                "A day in the period (DAY or WEEK)"),
                        Spec.month("month", false, WaviTimesheetPeriod.PAST_MONTHS, WaviTimesheetPeriod.FUTURE_MONTHS,
                                "The month (MONTH)"),
                        Spec.dateRange("range", false, WaviTimesheetPeriod.PAST_DAYS, WaviTimesheetPeriod.FUTURE_DAYS,
                                "Inclusive explicit range, YYYY-MM-DD/YYYY-MM-DD (RANGE)"),
                        Spec.enumOf("relative", false, WaviTimesheetPeriod.Relative.class, "As get_my_timesheets"),
                        Spec.integer("weeksAgo", false, 1, WaviTimesheetPeriod.MAX_WEEKS_AGO, "With relative WEEKS_AGO only"),
                        Spec.enumOf("detail", false, TeamTimesheetDetail.class, "LIST (default) or HOURS"),
                        Spec.integer("ordinal", false, 1, 50, "Position of the person in the user's people list")),
                REVIEWERS, WaviTool.DataClass.ORG_AGGREGATE, false,
                (arguments, context) -> teamPeriodProblem(arguments, context.today()),
                (actor, arguments, context) -> listed(answers.teamTimesheets(actor, new WaviTeamTimesheetQuery(
                        arguments.has("status") ? arguments.text("status") : TimesheetStatusFilter.ANY.name(),
                        arguments.has("period") ? WaviTimesheetPeriod.range(arguments, context.today()) : null,
                        TeamTimesheetDetail.HOURS.name().equals(arguments.text("detail")),
                        arguments.has("ordinal") ? arguments.integer("ordinal") : null), context.message())),
                (role, arguments, context) -> WaviToolResult.notAvailable("REVIEWERS_ONLY",
                        MyHrChatService.TIMESHEET_QUEUE_REVIEWERS_ONLY, List.of())));

        tools.add(new Definition(GET_PEOPLE_SUMMARY,
                "HR and Company Admin: counts over the people the user can already see (HR: their active direct "
                        + "reports; Company Admin: their organization) - how many people, how many per role, the most "
                        + "common job titles, and how many different reporting managers (\"how many team members do I "
                        + "have?\", \"how many employees are in my organization?\", \"how many managers are there?\"). "
                        + "Counts only; names come from get_direct_reports or get_team_member.",
                Schema.NONE, REVIEWERS, WaviTool.DataClass.ORG_AGGREGATE, false, null,
                (actor, arguments, context) -> listed(answers.peopleSummary(actor)),
                (role, arguments, context) -> WaviToolResult.notAvailable("UNSUPPORTED_PEOPLE_AGGREGATE",
                        PEOPLE_AGGREGATE_REPLY, List.of())));

        tools.add(new Definition(GET_MY_REVIEW_HISTORY,
                "HR and Company Admin: reviews the user ALREADY DID - timesheets they approved or rejected, or profile "
                        + "change requests they processed - newest first, with whose and when (\"what was the last "
                        + "timesheet I reviewed?\", \"whose did I approve most recently?\"). Not pending work (use "
                        + "get_review_queue).",
                Schema.of(Spec.enumOf("queue", false, ReviewHistoryQueue.class, "TIMESHEETS by default"),
                        Spec.enumOf("decision", false, ReviewDecision.class, "ANY by default")),
                REVIEWERS, WaviTool.DataClass.ORG_AGGREGATE, false, null,
                (actor, arguments, context) -> listed(answers.reviewHistory(actor,
                        arguments.has("queue") ? arguments.text("queue") : ReviewHistoryQueue.TIMESHEETS.name(),
                        arguments.has("decision") ? arguments.text("decision") : ReviewDecision.ANY.name())),
                (role, arguments, context) -> WaviToolResult.notAvailable("REVIEWERS_ONLY",
                        MyHrChatService.TIMESHEET_QUEUE_REVIEWERS_ONLY, List.of())));

        tools.add(new Definition(LIST_MY_TIMESHEETS,
                "The user's OWN timesheets as a list, newest first: all, one status (DRAFT, SUBMITTED = awaiting "
                        + "approval, APPROVED, REJECTED) or NEEDS_ACTION (drafts not submitted and rejected ones to "
                        + "correct), with any reviewer comment on a rejection. For one day, week or month use "
                        + "get_my_timesheets.",
                Schema.of(Spec.enumOf("status", false, TimesheetStatusFilter.class, "ANY by default")),
                ALL_ROLES, WaviTool.DataClass.PERSONAL, false, null,
                (actor, arguments, context) -> listed(answers.myTimesheetList(actor,
                        arguments.has("status") ? arguments.text("status") : TimesheetStatusFilter.ANY.name())),
                null));

        tools.add(new Definition(LIST_MY_REQUESTS,
                "The user's OWN profile change or document requests as a list (\"what are those two requests?\", "
                        + "\"which requests were approved?\"): OPEN (pending, submitted or returned to them), COMPLETED, "
                        + "REJECTED or ANY, with dates and any recorded rejection reason.",
                Schema.of(Spec.enumOf("kind", true, RequestListKind.class, "Which requests"),
                        Spec.enumOf("status", false, RequestStatusFilter.class, "ANY by default")),
                ALL_ROLES, WaviTool.DataClass.PERSONAL, false, null,
                (actor, arguments, context) -> listed(answers.myRequestList(actor, arguments.text("kind"),
                        arguments.has("status") ? arguments.text("status") : RequestStatusFilter.ANY.name())),
                null));

        // The user's own message is the policy query: a router can choose this tool but cannot write the query.
        tools.add(new Definition(POLICY_QUESTION,
                "A question about the company's own rules and policies, answered from approved policy documents with "
                        + "citations. Not personal records.",
                Schema.NONE, ALL_ROLES, WaviTool.DataClass.POLICY, false, null,
                (actor, arguments, context) -> WaviToolResult.policyHandoff(null), null));

        // General HR knowledge or help writing a work message: answered by the existing general provider path, with
        // its history protections; no record is read here.
        tools.add(new Definition(GENERAL_HELP,
                "General HR or workplace knowledge, or help writing a work message, needing no company record or policy.",
                Schema.NONE, ALL_ROLES, WaviTool.DataClass.PUBLIC_APP, false, null,
                (actor, arguments, context) -> WaviToolResult.generalHandoff(), null));

        tools.add(new Definition(CLARIFY, "Ask which of the user's records they mean, when it is unclear.",
                Schema.of(Spec.enumOf("topic", false, ClarificationTopic.class, "Known business topic, if any"),
                        Spec.enumOf("missing", false, MissingSlot.class, "The missing slot only; do not ask an unrelated menu")),
                ALL_ROLES, WaviTool.DataClass.PUBLIC_APP, false, null,
                (actor, arguments, context) -> WaviToolResult.clarify(WaviClarification.prompt(
                        arguments.enumValue("topic", ClarificationTopic.class), arguments.enumValue("missing", MissingSlot.class), actor.role())),
                null));

        // A relevant question no tool answers exactly: an honest fixed reply, never the nearest tool. Reads nothing.
        tools.add(new Definition(UNSUPPORTED_REQUEST,
                "A work, HR or Waypoint question that NO other tool answers exactly: history or counts over a past "
                        + "period, another person's information, Wavi itself changing or submitting "
                        + "anything (not how-to), or something Waypoint does not record. "
                        + "Choose this instead of a tool that only answers a similar question.",
                Schema.of(Spec.enumOf("reason", true, UnsupportedReason.class, "Why no tool can answer it")),
                ALL_ROLES, WaviTool.DataClass.PUBLIC_APP, false, null,
                (actor, arguments, context) -> {
                    UnsupportedReason reason = arguments.enumValue("reason", UnsupportedReason.class);
                    return switch (reason) {
                        case HISTORY_OR_DATE_RANGE -> WaviToolResult.notAvailable("UNSUPPORTED_HISTORY", HISTORY_REPLY,
                                List.of(TIMESHEETS_PAGE));
                        case OTHER_PERSON -> WaviToolResult.notAvailable("UNSUPPORTED_OTHER_PERSON",
                                MyHrChatService.OTHER_PERSON_REPLY, List.of());
                        case PEOPLE_AGGREGATE -> WaviToolResult.notAvailable("UNSUPPORTED_PEOPLE_AGGREGATE",
                                PEOPLE_AGGREGATE_REPLY, List.of(EMPLOYEES_PAGE));
                        case WRITE_ACTION -> WaviToolResult.notAvailable("UNSUPPORTED_WRITE",
                                MyHrChatService.ACTION_REQUEST_REPLY, List.of());
                        case NOT_AVAILABLE_IN_WAYPOINT -> WaviToolResult.notAvailable("UNSUPPORTED_CAPABILITY",
                                NOT_AVAILABLE_REPLY, List.of());
                    };
                }, null));

        tools.add(new Definition(OUT_OF_SCOPE, "A request unrelated to work, HR, the company or Waypoint (weather, sports, trivia, coding, creative writing). "
                        + "For a work question no tool can answer, use unsupported_request instead.",
                Schema.NONE, ALL_ROLES, WaviTool.DataClass.PUBLIC_APP, true, null,
                (actor, arguments, context) -> WaviToolResult.answer(MyHrScopeGuard.OUT_OF_SCOPE_REPLY, null), null));

        return List.copyOf(tools);
    }

    private static WaviToolResult listed(com.waypoint.dto.myhr.MyHrChatResponse response) {
        return response == null || response.message() == null
                ? WaviToolResult.notAvailable("NO_LOOKUP", WaviRouterPolicyGate.CANNOT_HELP_REPLY, null)
                : WaviToolResult.answer(response.message(), response.actions());
    }

    /** The own-record intent the existing own-record route answers, built from validated arguments only. */
    static MyHrOwnRecordIntent.Intent requestIntent(UserRole role, Validated arguments) {
        RequestKind kind = arguments.enumValue("kind", RequestKind.class);
        MyHrOwnRecordIntent.Aspect aspect = arguments.has("aspect")
                ? arguments.enumValue("aspect", MyHrOwnRecordIntent.Aspect.class) : MyHrOwnRecordIntent.Aspect.STATUS;
        MyHrOwnRecordIntent.Subject subject = switch (kind) {
            case PROFILE_CHANGE -> MyHrOwnRecordIntent.Subject.PROFILE_CHANGE;
            case DOCUMENT_REQUEST -> MyHrOwnRecordIntent.Subject.DOCUMENT_REQUEST;
            case OFFER_LETTER -> MyHrOwnRecordIntent.Subject.OFFER_LETTER;
        };
        // "Is my request pending?": an Employee's own requests. A reviewer's PENDING_CHECK would read their review
        // queue instead, so for HR and Company Admin it stays a question about their own request.
        if (aspect == MyHrOwnRecordIntent.Aspect.PENDING && role == UserRole.EMPLOYEE) {
            return new MyHrOwnRecordIntent.Intent(MyHrOwnRecordIntent.Kind.PENDING_CHECK, subject, aspect, null, null);
        }
        RequestedDocumentType documentType = kind == RequestKind.OFFER_LETTER ? RequestedDocumentType.OFFER_LETTER
                : arguments.enumValue("documentType", RequestedDocumentType.class);
        return new MyHrOwnRecordIntent.Intent(MyHrOwnRecordIntent.Kind.OWN, subject, aspect,
                arguments.enumValue("field", ProfileChangeField.class), documentType);
    }
}
