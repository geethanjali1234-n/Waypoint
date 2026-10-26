package com.waypoint.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.ZoneId;
import java.util.List;

@ConfigurationProperties(prefix = "waypoint.myhr.payroll-readiness")
public class PayrollReadinessProperties {
    private boolean automaticRemindersEnabled = false;
    private int cutoffDay = 10;
    private List<Integer> employeeReminderDays = List.of(5, 7, 9, 10);
    private List<Integer> managementSummaryDays = List.of(7, 9, 10);
    private int batchSize = 250;
    private String zoneId = "America/Chicago";

    // No org-configurable work schedule exists yet, so a standard 8-hour day is
    // the default used both to (a) recognize daily overtime - approved hours
    // beyond this in a day - and (b) value an approved, described zero-hour day
    // (e.g. "Sick leave") as a full day of leave, since there is no separate
    // leave-hours field anywhere in the data model. See PayrollReadinessService.
    private int standardWorkdayHours = 8;

    // No org calendar/work-schedule model exists yet, so a standard Mon-Fri work
    // week is the default: Saturday/Sunday are not "required" dates, so having no
    // entry on a weekend never counts as missing coverage. Orgs that do require
    // weekend coverage can flip this on.
    private boolean requireWeekendCoverage = false;

    public boolean isAutomaticRemindersEnabled() { return automaticRemindersEnabled; }
    public void setAutomaticRemindersEnabled(boolean automaticRemindersEnabled) { this.automaticRemindersEnabled = automaticRemindersEnabled; }
    public int getCutoffDay() { return cutoffDay; }
    public void setCutoffDay(int cutoffDay) { this.cutoffDay = cutoffDay; }
    public List<Integer> getEmployeeReminderDays() { return employeeReminderDays; }
    public void setEmployeeReminderDays(List<Integer> employeeReminderDays) { this.employeeReminderDays = employeeReminderDays; }
    public List<Integer> getManagementSummaryDays() { return managementSummaryDays; }
    public void setManagementSummaryDays(List<Integer> managementSummaryDays) { this.managementSummaryDays = managementSummaryDays; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public String getZoneId() { return zoneId; }
    public void setZoneId(String zoneId) { this.zoneId = zoneId; }
    public ZoneId zone() { return ZoneId.of(zoneId); }
    public int getStandardWorkdayHours() { return standardWorkdayHours; }
    public void setStandardWorkdayHours(int standardWorkdayHours) { this.standardWorkdayHours = standardWorkdayHours; }
    public boolean isRequireWeekendCoverage() { return requireWeekendCoverage; }
    public void setRequireWeekendCoverage(boolean requireWeekendCoverage) { this.requireWeekendCoverage = requireWeekendCoverage; }
}
