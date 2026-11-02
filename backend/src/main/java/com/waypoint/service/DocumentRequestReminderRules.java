package com.waypoint.service;

import com.waypoint.entity.DocumentRequest;
import com.waypoint.enums.DocumentRequestReminderType;
import com.waypoint.enums.DocumentRequestStatus;
import com.waypoint.enums.RequestedDocumentType;
import org.springframework.data.mongodb.core.query.Criteria;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Which document requests are overdue, and which reminder workflows exist. A request is only reminded about while
 * its stored status says a clearly identified person has to act, and only when it has a due date (requiredBy).
 *
 * <pre>
 * Workflow            Requests                                             Reminders (3 and 1 days before)   Overdue alert
 * HR-to-employee      TO_EMPLOYEE, PENDING                                 employee                          HR requester*
 * Employee-to-HR      TO_MANAGER, not I-983, PENDING / RETURNED_FOR_       assigned HR*                      assigned HR*
 *                     CORRECTION (approval returned) / APPROVED (to send)
 * I-983 review        TO_MANAGER, I-983, SUBMITTED                         assigned HR reviewer*             assigned HR reviewer*
 * I-983 correction    TO_MANAGER, I-983, RETURNED_FOR_CORRECTION           submitting employee               submitting employee
 * (* falls back to one active Company Admin when that HR user is unavailable)
 * </pre>
 *
 * Not reminded: a non-I-983 employee request in SUBMITTED is waiting for Company Admin approval, and any Company
 * Admin may approve it, so there is no single responsible person. It still counts as overdue for the badge.
 */
final class DocumentRequestReminderRules {

    static final String TO_EMPLOYEE = "TO_EMPLOYEE";
    static final String TO_MANAGER = "TO_MANAGER";

    private DocumentRequestReminderRules() {
    }

    /** Whether a scope includes I-983 requests: only I-983, never I-983, or either. */
    enum I983 { ONLY, EXCLUDED, ANY }

    /** A set of requests by direction, stored status and document type. Used both as a query and as a re-check. */
    record Scope(String direction, Set<DocumentRequestStatus> statuses, I983 i983) {

        Criteria criteria() {
            Criteria criteria = Criteria.where("requestDirection").is(direction);
            criteria = statuses.size() == 1 ? criteria.and("status").is(statuses.iterator().next())
                    : criteria.and("status").in(statuses);
            if (i983 == I983.ONLY) criteria = criteria.and("documentType").is(RequestedDocumentType.I983);
            if (i983 == I983.EXCLUDED) criteria = criteria.and("documentType").ne(RequestedDocumentType.I983);
            return criteria;
        }

        boolean matches(DocumentRequest request) {
            if (!direction.equals(request.getRequestDirection()) || !statuses.contains(request.getStatus())) return false;
            boolean isI983 = request.getDocumentType() == RequestedDocumentType.I983;
            return switch (i983) {
                case ONLY -> isI983;
                case EXCLUDED -> !isI983;
                case ANY -> true;
            };
        }
    }

    /**
     * Open requests that count as overdue once their due date has passed (the badge and the OVERDUE filter).
     * Completed, rejected and cancelled requests, and an approved I-983, are never overdue.
     */
    static final List<Scope> OVERDUE_SCOPES = List.of(
            new Scope(TO_EMPLOYEE, Set.of(DocumentRequestStatus.PENDING), I983.ANY),
            new Scope(TO_MANAGER, Set.of(DocumentRequestStatus.PENDING, DocumentRequestStatus.SUBMITTED,
                    DocumentRequestStatus.RETURNED_FOR_CORRECTION), I983.ANY),
            new Scope(TO_MANAGER, Set.of(DocumentRequestStatus.APPROVED), I983.EXCLUDED));

    static boolean isOverdue(DocumentRequest request, LocalDate today) {
        return request.getRequiredBy() != null && request.getRequiredBy().isBefore(today)
                && OVERDUE_SCOPES.stream().anyMatch(scope -> scope.matches(request));
    }

    /** The same rule as {@link #isOverdue} as a MongoDB condition. Requests without a due date never match. */
    static Criteria overdueCriteria(LocalDate today) {
        return new Criteria().orOperator(OVERDUE_SCOPES.stream()
                .map(scope -> scope.criteria().and("requiredBy").lt(today)).toArray(Criteria[]::new));
    }

    enum Stage { DUE_IN_3_DAYS, DUE_IN_1_DAY, OVERDUE }

    /** Who receives a reminder: a user on the request, optionally replaced by one active Company Admin when unavailable. */
    record Recipient(Function<DocumentRequest, String> userId, boolean companyAdminFallback) {
    }

    /** The wording of one reminder. {@code who} is the other person on the request (may be null), {@code when} is "tomorrow" or "in 3 days". */
    interface Wording {
        Text text(Stage stage, DocumentRequest request, String who, String when, String due);
    }

    record Text(String title, String message, String actionLabel) {
    }

    record Rule(String name, Scope scope,
                DocumentRequestReminderType dueIn3Days, DocumentRequestReminderType dueIn1Day, DocumentRequestReminderType overdue,
                Recipient dueSoonRecipient, Recipient overdueRecipient,
                Function<DocumentRequest, String> otherPersonId, Wording wording) {

        DocumentRequestReminderType type(Stage stage) {
            return switch (stage) {
                case DUE_IN_3_DAYS -> dueIn3Days;
                case DUE_IN_1_DAY -> dueIn1Day;
                case OVERDUE -> overdue;
            };
        }

        Recipient recipient(Stage stage) {
            return stage == Stage.OVERDUE ? overdueRecipient : dueSoonRecipient;
        }
    }

    private static final Recipient EMPLOYEE_ON_HR_REQUEST = new Recipient(DocumentRequest::getRequestedForUserId, false);
    private static final Recipient HR_REQUESTER = new Recipient(DocumentRequest::getRequestedByUserId, true);
    private static final Recipient ASSIGNED_HR = new Recipient(DocumentRequest::getRequestedForUserId, true);
    private static final Recipient SUBMITTING_EMPLOYEE = new Recipient(DocumentRequest::getRequestedByUserId, false);

    static final List<Rule> RULES = List.of(
            // F1: HR asked an employee for a document.
            new Rule("hr-to-employee", new Scope(TO_EMPLOYEE, Set.of(DocumentRequestStatus.PENDING), I983.ANY),
                    DocumentRequestReminderType.DUE_IN_3_DAYS, DocumentRequestReminderType.DUE_IN_1_DAY,
                    DocumentRequestReminderType.OVERDUE, EMPLOYEE_ON_HR_REQUEST, HR_REQUESTER,
                    DocumentRequest::getRequestedForUserId,
                    (stage, request, who, when, due) -> stage == Stage.OVERDUE
                            ? new Text("Document request overdue", (who == null ? "The employee" : who)
                                    + " has not completed \"" + request.getRequestTitle() + "\", which was due on " + due + ".", "View Request")
                            : new Text("Document request due soon",
                                    "\"" + request.getRequestTitle() + "\" is due " + when + ", on " + due + ".", "Open Request")),
            // An employee asked their HR for a document, and HR has to prepare, revise or send it.
            new Rule("employee-to-hr", new Scope(TO_MANAGER, Set.of(DocumentRequestStatus.PENDING,
                    DocumentRequestStatus.RETURNED_FOR_CORRECTION, DocumentRequestStatus.APPROVED), I983.EXCLUDED),
                    DocumentRequestReminderType.DUE_IN_3_DAYS, DocumentRequestReminderType.DUE_IN_1_DAY,
                    DocumentRequestReminderType.OVERDUE, ASSIGNED_HR, ASSIGNED_HR,
                    DocumentRequest::getRequestedByUserId,
                    (stage, request, who, when, due) -> stage == Stage.OVERDUE
                            ? new Text("Employee document request overdue", (who == null ? "An employee" : who)
                                    + " requested \"" + request.getRequestTitle() + "\", which was due on " + due
                                    + " and is not completed yet.", "View Request")
                            : new Text("Employee document request due soon", (who == null ? "An employee" : who)
                                    + " requested \"" + request.getRequestTitle() + "\". It is due " + when + ", on " + due + ".", "Open Request")),
            // An employee submitted a Signed I-983 and HR has to review it.
            new Rule("i983-review", new Scope(TO_MANAGER, Set.of(DocumentRequestStatus.SUBMITTED), I983.ONLY),
                    DocumentRequestReminderType.DUE_IN_3_DAYS, DocumentRequestReminderType.DUE_IN_1_DAY,
                    DocumentRequestReminderType.OVERDUE, ASSIGNED_HR, ASSIGNED_HR,
                    DocumentRequest::getRequestedByUserId,
                    (stage, request, who, when, due) -> stage == Stage.OVERDUE
                            ? new Text("Signed I-983 review overdue", possessive(who) + " Signed I-983 was due on " + due
                                    + " and is still waiting for review.", "Review I-983")
                            : new Text("Signed I-983 review due soon", possessive(who) + " Signed I-983 is due " + when
                                    + ", on " + due + ". Please review it.", "Review I-983")),
            // HR returned a Signed I-983 and the employee has to correct and resubmit it.
            new Rule("i983-correction", new Scope(TO_MANAGER, Set.of(DocumentRequestStatus.RETURNED_FOR_CORRECTION), I983.ONLY),
                    DocumentRequestReminderType.CORRECTION_DUE_IN_3_DAYS, DocumentRequestReminderType.CORRECTION_DUE_IN_1_DAY,
                    DocumentRequestReminderType.CORRECTION_OVERDUE, SUBMITTING_EMPLOYEE, SUBMITTING_EMPLOYEE,
                    request -> null,
                    (stage, request, who, when, due) -> stage == Stage.OVERDUE
                            ? new Text("Signed I-983 correction overdue", "Your returned Signed I-983 was due on " + due
                                    + ". Please correct and resubmit it.", "View I-983")
                            : new Text("Signed I-983 correction due soon", "Your returned Signed I-983 is due " + when
                                    + ", on " + due + ". Please correct and resubmit it.", "View I-983")));

    private static String possessive(String who) {
        return who == null ? "An employee's" : who + "'s";
    }
}
