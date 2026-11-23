package com.waypoint.service;

/**
 * A reviewer's typed question about timesheets in their review scope: which status, which period (null = any), whether
 * the hours worked within that period are asked rather than the submissions, and which person (null = everyone in
 * scope, or the person the message names). {@code ordinal} is a position in the reviewer's own people list; it is
 * re-resolved against the CURRENT authorized scope on every use and never carries an identity.
 */
record WaviTeamTimesheetQuery(String status, MyHrConversationIntents.TimesheetRange range, boolean hours,
                              Integer ordinal) {
    WaviTeamTimesheetQuery {
        status = status == null ? WaviTools.TimesheetStatusFilter.ANY.name() : status;
    }
}
