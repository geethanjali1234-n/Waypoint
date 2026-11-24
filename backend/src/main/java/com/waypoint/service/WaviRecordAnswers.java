package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrChatResponse;
import com.waypoint.enums.UserRole;
import com.waypoint.security.VerifiedMyHrActor;

import java.util.List;

/**
 * The existing, already-authorized answers Wavi's tools reuse. Each one is the same code the deterministic routes
 * run, with the same service calls scoped to {@code actor.userId()} (implemented inside {@link MyHrChatService}).
 * So a tool and the route it replaces cannot drift apart. Methods taking only a role do no lookup.
 */
interface WaviRecordAnswers {

    /** The account role reply; with {@code asked} ANY, the user's own job title as well. */
    String role(VerifiedMyHrActor actor, MyHrConversationIntents.AskedRole asked);

    /** One field of the user's own profile, or the profile summary; {@code field} is a {@link WaviTools.ProfileField} name. */
    String profileField(VerifiedMyHrActor actor, String field);

    /** The profile questions the existing route answers, by name. */
    List<String> profileFields();

    /** The status of the user's own most recent timesheet. */
    String latestTimesheet(VerifiedMyHrActor actor);

    /** Latest submitted record, selected by submittedAt, not by the period start. */
    String latestTimesheetSubmission(VerifiedMyHrActor actor);

    /** The user's own timesheets for a day, week or month (Company Admin: a fixed reply, no lookup). */
    MyHrChatResponse timesheetRange(VerifiedMyHrActor actor, MyHrConversationIntents.TimesheetRange range);

    /** Everything waiting for the user: own open items (Employee) or review queues (HR, Company Admin). */
    MyHrChatResponse pendingWork(VerifiedMyHrActor actor);

    /**
     * Pending work narrowed to one kind ({@code area}, null = all) and, for reviewers, one person in their scope
     * ({@code ordinal}, null = everyone).
     */
    default MyHrChatResponse pendingWork(VerifiedMyHrActor actor, WaviTools.WorkArea area, Integer ordinal) {
        return pendingWork(actor);
    }

    /** HR only: the user's own active direct reports. */
    String directReports(VerifiedMyHrActor actor);

    /** The fixed direct-reports reply for a role without that lookup. */
    String directReportsOutsideRole(UserRole role);

    /** A reviewer's queue count, scoped by the service; null when the queue is not available. */
    String reviewQueue(VerifiedMyHrActor actor, WaviTools.ReviewQueue queue);

    /** The fixed reply for a role without review queues. */
    String reviewQueueOutsideRole(WaviTools.ReviewQueue queue);

    /**
     * Who and what is waiting in the reviewer's queue right now: names, periods or request types and dates only,
     * from the same scoped lookups (and the same scope) as {@link #reviewQueue}.
     */
    MyHrChatResponse reviewQueueItems(VerifiedMyHrActor actor, WaviTools.ReviewQueue queue);

    /** Counts over the people the reviewer may see (HR: active direct reports; Company Admin: organization). */
    MyHrChatResponse peopleSummary(VerifiedMyHrActor actor);

    /** Review decisions the reviewer has already made (from each record's own reviewer), newest first. */
    MyHrChatResponse reviewHistory(VerifiedMyHrActor actor, String queue, String decision);

    /** One person in the reviewer's own scope (HR: active direct reports; Company Admin: the organization). */
    MyHrChatResponse teamMember(VerifiedMyHrActor actor, String field, Integer ordinal, String message);

    /** Payroll readiness from the existing readiness service; HR and Company Admin only. */
    default String payrollReadiness(VerifiedMyHrActor actor, WaviTools.PayrollReadinessAsk ask, java.time.YearMonth month) {
        return null;
    }

    /** People in the reviewer's existing scope, all or filtered. */
    default String findPeople(VerifiedMyHrActor actor, WaviTools.PeopleFilter filter, String joined, String message) {
        return null;
    }

    /** Timesheets of people in the reviewer's review scope, optionally one named person and one status. */
    MyHrChatResponse teamTimesheets(VerifiedMyHrActor actor, String status, String message);

    /** The typed form: status, optional period, hours-within-period, and optional person position. */
    default MyHrChatResponse teamTimesheets(VerifiedMyHrActor actor, WaviTeamTimesheetQuery query, String message) {
        return teamTimesheets(actor, query.status(), message);
    }

    /** The user's own timesheets with one status (or all, or those needing their action), newest first. */
    MyHrChatResponse myTimesheetList(VerifiedMyHrActor actor, String status);

    /** The user's own profile change or document requests with one status group, newest first. */
    MyHrChatResponse myRequestList(VerifiedMyHrActor actor, String kind, String status);

    /** The user's own recorded hours for a period (or their latest timesheet when range is null). */
    MyHrChatResponse timesheetHours(VerifiedMyHrActor actor, MyHrConversationIntents.TimesheetRange range);

    MyHrChatResponse dailyTimesheetHours(VerifiedMyHrActor actor, MyHrConversationIntents.TimesheetRange range);

    /** A question about the user's own request; null when no lookup answers it. */
    String ownRequest(VerifiedMyHrActor actor, MyHrOwnRecordIntent.Intent intent);

    /** What Waypoint records about pay timing: the organization's payroll frequency, never a pay date. */
    String payrollSchedule(VerifiedMyHrActor actor);

    /** Role-specific how-to text for a Waypoint workflow; null when there is none for the role. */
    String workflowGuidance(String topic, UserRole role);

    /** The workflow topics {@link #workflowGuidance} knows, by name. */
    List<String> workflowTopics();

    /**
     * Yes or no: is the person named in {@code message} one of the people the reviewer may already see? Returns no
     * name, ID, manager or profile. Null when this implementation cannot answer.
     */
    default String personInScope(VerifiedMyHrActor actor, String message) {
        return null;
    }
    /** The titles and audiences of the active policies, as the existing catalog answer (reviewers only). Null when unavailable. */
    default MyHrChatResponse policyCatalog(VerifiedMyHrActor actor) {
        return null;
    }
    /** What is waiting for a reviewer, queue by queue, with the SERVER-calculated total and the typed numbers behind it. */
    record WorkTotals(String text, List<String> pages, List<Long> facts) {
    }
    /** Reviewers only; null for anyone else or when this implementation cannot answer. */
    default WorkTotals workTotals(VerifiedMyHrActor actor) {
        return null;
    }
    /**
     * The people a reviewer may already see (HR: active direct reports; Company Admin: the organization) with job title
     * and kind only: no e-mail, ID, manager or dates. Null when unavailable.
     */
    default MyHrChatResponse listPeople(VerifiedMyHrActor actor) {
        return null;
    }
    /** The honest fixed reply for a personal detail Wavi does not show. */
    String unsupportedField(MyHrQuestionInterpreter.UnsupportedField field);
}
