package com.waypoint.enums;

/**
 * The automatic notifications sent for a document request with a due date while someone has to act on it
 * (see DocumentRequestReminderRules for the workflows and recipients). Each type is sent at most once per
 * request and due date.
 */
public enum DocumentRequestReminderType {
    /** To the person who has to act, three days before the due date. */
    DUE_IN_3_DAYS,
    /** To the person who has to act, one day before the due date. */
    DUE_IN_1_DAY,
    /** Once, after the due date has passed. */
    OVERDUE,
    /** To the employee whose returned Signed I-983 has to be corrected, three days before the due date. */
    CORRECTION_DUE_IN_3_DAYS,
    /** To the employee whose returned Signed I-983 has to be corrected, one day before the due date. */
    CORRECTION_DUE_IN_1_DAY,
    /** Once, to that employee, after the due date has passed. */
    CORRECTION_OVERDUE
}
