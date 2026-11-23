package com.waypoint.service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small, server-derived description of a completed Wavi request. It is deliberately not a second router and never
 * grants access: the validated tool name and arguments remain the execution authority. Its purpose is to give
 * continuations and semantic routing one stable vocabulary for the domain, operation, selected period, metric and
 * safe filters that were actually answered.
 */
record WaviQuestionUnderstanding(Domain domain, Operation operation, Subject subject, Period period, Metric metric,
                                 Map<String, Object> filters, boolean needsClarification) {

    enum Domain { TIMESHEET, PROFILE, DOCUMENT_REQUEST, REQUEST, REVIEW_QUEUE, PEOPLE, POLICY, WORKFLOW, NAVIGATION, GENERAL, UNKNOWN }
    enum Operation { GET, LIST, COUNT, CALCULATE, EXPLAIN, NAVIGATE, CLARIFY, UNSUPPORTED, UNKNOWN }
    enum Subject { VERIFIED_USER, AUTHORIZED_SCOPE, ROLE_DEPENDENT, APPLICATION, NONE }
    enum Period { NONE, LATEST, DAY, WEEK, MONTH, RANGE }
    enum Metric { NONE, STATUS, HOURS, DAILY_HOURS, SUBMISSION_DATE, PERIOD, SUMMARY }

    WaviQuestionUnderstanding {
        domain = domain == null ? Domain.UNKNOWN : domain;
        operation = operation == null ? Operation.UNKNOWN : operation;
        subject = subject == null ? Subject.NONE : subject;
        period = period == null ? Period.NONE : period;
        metric = metric == null ? Metric.NONE : metric;
        filters = filters == null ? Map.of() : Map.copyOf(filters);
    }

    static WaviQuestionUnderstanding from(String tool, Map<String, Object> arguments, LocalDate referenceDate) {
        Map<String, Object> safe = safeFilters(arguments);
        if (tool == null) return new WaviQuestionUnderstanding(Domain.UNKNOWN, Operation.UNKNOWN, Subject.NONE,
                Period.NONE, Metric.NONE, safe, false);
        return switch (tool) {
            case WaviTools.GET_MY_TIMESHEETS -> new WaviQuestionUnderstanding(Domain.TIMESHEET,
                    detail(arguments) == Metric.HOURS || detail(arguments) == Metric.DAILY_HOURS ? Operation.CALCULATE : Operation.GET,
                    Subject.VERIFIED_USER, period(arguments), detail(arguments), safe, false);
            case WaviTools.LIST_MY_TIMESHEETS -> new WaviQuestionUnderstanding(Domain.TIMESHEET, Operation.LIST,
                    Subject.VERIFIED_USER, period(arguments), Metric.SUMMARY, safe, false);
            case WaviTools.GET_TEAM_TIMESHEETS -> new WaviQuestionUnderstanding(Domain.TIMESHEET,
                    "HOURS".equals(value(arguments, "detail")) ? Operation.CALCULATE : Operation.LIST,
                    Subject.AUTHORIZED_SCOPE, period(arguments), "HOURS".equals(value(arguments, "detail"))
                            ? Metric.HOURS : Metric.SUMMARY, safe, false);
            case WaviTools.GET_MY_PROFILE_FIELD, WaviTools.GET_MY_ROLE -> new WaviQuestionUnderstanding(Domain.PROFILE,
                    Operation.GET, Subject.VERIFIED_USER, Period.NONE, Metric.NONE, safe, false);
            case WaviTools.GET_MY_REQUESTS, WaviTools.LIST_MY_REQUESTS -> new WaviQuestionUnderstanding(
                    "PROFILE_CHANGE".equals(value(arguments, "kind")) ? Domain.PROFILE : Domain.DOCUMENT_REQUEST,
                    tool.equals(WaviTools.LIST_MY_REQUESTS) ? Operation.LIST : Operation.GET,
                    Subject.VERIFIED_USER, Period.NONE, Metric.STATUS, safe, false);
            case WaviTools.GET_MY_PENDING_WORK -> new WaviQuestionUnderstanding(Domain.WORKFLOW,
                    Operation.LIST, Subject.ROLE_DEPENDENT, Period.NONE, Metric.SUMMARY, safe, false);
            case WaviTools.GET_REVIEW_QUEUE -> new WaviQuestionUnderstanding(Domain.REVIEW_QUEUE,
                    "LIST".equals(value(arguments, "detail")) ? Operation.LIST : Operation.COUNT,
                    Subject.AUTHORIZED_SCOPE, Period.NONE, Metric.SUMMARY, safe, false);
            case WaviTools.GET_MY_REVIEW_HISTORY ->
                    new WaviQuestionUnderstanding(Domain.REVIEW_QUEUE, "COUNT".equals(value(arguments, "detail"))
                            ? Operation.COUNT : Operation.LIST, Subject.AUTHORIZED_SCOPE, Period.NONE, Metric.SUMMARY, safe, false);
            case WaviTools.GET_DIRECT_REPORTS, WaviTools.GET_TEAM_MEMBER, WaviTools.GET_PEOPLE_SUMMARY ->
                    new WaviQuestionUnderstanding(Domain.PEOPLE, WaviTools.GET_PEOPLE_SUMMARY.equals(tool) ? Operation.COUNT
                            : Operation.GET, Subject.AUTHORIZED_SCOPE, Period.NONE, Metric.SUMMARY, safe, false);
            case WaviTools.POLICY_QUESTION -> new WaviQuestionUnderstanding(Domain.POLICY, Operation.EXPLAIN,
                    Subject.APPLICATION, Period.NONE, Metric.NONE, safe, false);
            case WaviTools.WORKFLOW_HELP, WaviTools.EXPLAIN_TERM -> new WaviQuestionUnderstanding(Domain.WORKFLOW,
                    Operation.EXPLAIN, Subject.APPLICATION, Period.NONE, Metric.NONE, safe, false);
            case WaviTools.OPEN_PAGE -> new WaviQuestionUnderstanding(Domain.NAVIGATION, Operation.NAVIGATE,
                    Subject.APPLICATION, Period.NONE, Metric.NONE, safe, false);
            case WaviTools.CLARIFY -> new WaviQuestionUnderstanding(clarificationDomain(arguments), Operation.CLARIFY, Subject.NONE,
                    Period.NONE, Metric.NONE, safe, true);
            case WaviTools.UNSUPPORTED_FIELD, WaviTools.UNSUPPORTED_REQUEST, WaviTools.OUT_OF_SCOPE ->
                    new WaviQuestionUnderstanding(Domain.UNKNOWN, Operation.UNSUPPORTED, Subject.NONE, Period.NONE,
                            Metric.NONE, safe, false);
            default -> new WaviQuestionUnderstanding(Domain.GENERAL, Operation.EXPLAIN, Subject.APPLICATION,
                    Period.NONE, Metric.NONE, safe, false);
        };
    }

    /** Safe, compact context for a router. Identity and server-only person references are excluded. */
    Map<String, Object> routerContext() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("domain", domain.name());
        context.put("operation", operation.name());
        context.put("subject", subject.name());
        context.put("period", period.name());
        context.put("metric", metric.name());
        if (!filters.isEmpty()) context.put("filters", filters);
        if (needsClarification) context.put("needsClarification", true);
        return Map.copyOf(context);
    }

    /** Pending work means personal action for Employees and assigned review work for reviewers. */
    Map<String, Object> routerContext(com.waypoint.enums.UserRole role) {
        Map<String, Object> context = new LinkedHashMap<>(routerContext());
        if (subject == Subject.ROLE_DEPENDENT) {
            context.put("subject", (role == com.waypoint.enums.UserRole.EMPLOYEE
                    ? Subject.VERIFIED_USER : role == com.waypoint.enums.UserRole.HR
                    || role == com.waypoint.enums.UserRole.COMPANY_ADMIN
                    ? Subject.AUTHORIZED_SCOPE : Subject.NONE).name());
        }
        return Map.copyOf(context);
    }

    private static Domain clarificationDomain(Map<String, Object> arguments) {
        String topic = value(arguments, "topic");
        if (topic == null) return Domain.UNKNOWN;
        return switch (topic) {
            case "TIMESHEETS" -> Domain.TIMESHEET;
            case "REQUESTS" -> Domain.REQUEST;
            case "PEOPLE" -> Domain.PEOPLE;
            case "WORK" -> Domain.WORKFLOW;
            default -> Domain.UNKNOWN;
        };
    }

    private static Period period(Map<String, Object> arguments) {
        String value = value(arguments, "period");
        if (value == null) return Period.NONE;
        try { return Period.valueOf(value); } catch (IllegalArgumentException invalid) { return Period.NONE; }
    }

    private static Metric detail(Map<String, Object> arguments) {
        String value = value(arguments, "detail");
        if (value == null) return Metric.STATUS;
        try { return Metric.valueOf(value); } catch (IllegalArgumentException invalid) { return Metric.NONE; }
    }

    private static String value(Map<String, Object> arguments, String name) {
        Object value = arguments == null ? null : arguments.get(name);
        return value == null ? null : value.toString();
    }

    private static Map<String, Object> safeFilters(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) return Map.of();
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String name : new String[]{"detail", "status", "queue", "kind", "field", "area", "ordinal", "range",
                "aspect", "documentType", "decision", "topic", "missing"}) {
            Object value = arguments.get(name);
            if (value != null) safe.put(name, value);
        }
        return safe;
    }
}
