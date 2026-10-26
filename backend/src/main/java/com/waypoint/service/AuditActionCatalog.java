package com.waypoint.service;

import com.waypoint.enums.AuditActionType;
import com.waypoint.enums.AuditEventCategory;

import java.util.EnumMap;
import java.util.Map;

/**
 * Static description of every {@link AuditActionType}: the {@link AuditEventCategory}
 * it belongs to and a human-readable label. Kept as a single source of truth so both
 * the write path (stamping {@code eventCategory} on each {@link com.waypoint.entity.AuditLog})
 * and the read path (building filter options for the UI) stay in sync.
 *
 * <p>The catalog intentionally lists actions that are not emitted yet (timesheets,
 * payroll, documents, recruitment). They are part of the audit surface and are
 * wired as those workflows land.
 */
public final class AuditActionCatalog {

    /** Immutable label + category pair for one {@link AuditActionType}. */
    public record Descriptor(AuditActionType actionType, AuditEventCategory category, String label) {
    }

    private static final Map<AuditActionType, Descriptor> CATALOG = buildCatalog();

    private AuditActionCatalog() {
    }

    public static Descriptor describe(AuditActionType actionType) {
        Descriptor descriptor = CATALOG.get(actionType);
        if (descriptor != null) {
            return descriptor;
        }
        // Defensive fallback so a newly added enum value never breaks the write path.
        return new Descriptor(actionType, AuditEventCategory.ACCOUNT_MANAGEMENT, humanize(actionType.name()));
    }

    public static AuditEventCategory categoryOf(AuditActionType actionType) {
        return describe(actionType).category();
    }

    public static String labelOf(AuditActionType actionType) {
        return describe(actionType).label();
    }

    public static Map<AuditActionType, Descriptor> all() {
        return CATALOG;
    }

    private static Map<AuditActionType, Descriptor> buildCatalog() {
        Map<AuditActionType, Descriptor> map = new EnumMap<>(AuditActionType.class);

        put(map, AuditActionType.HR_ACCOUNT_CREATED, AuditEventCategory.ACCOUNT_MANAGEMENT, "HR account created");
        put(map, AuditActionType.EMPLOYEE_ACCOUNT_CREATED, AuditEventCategory.ACCOUNT_MANAGEMENT, "Employee account created");
        put(map, AuditActionType.ACCOUNT_CREATED, AuditEventCategory.ACCOUNT_MANAGEMENT, "Account created");
        put(map, AuditActionType.ACCOUNT_UPDATED, AuditEventCategory.ACCOUNT_MANAGEMENT, "Account updated");
        put(map, AuditActionType.ACCOUNT_STATUS_CHANGED, AuditEventCategory.ACCOUNT_MANAGEMENT, "Account status changed");

        put(map, AuditActionType.REPORTING_MANAGER_ASSIGNED, AuditEventCategory.REPORTING_STRUCTURE, "Reporting manager assigned");
        put(map, AuditActionType.REPORTING_MANAGER_CHANGED, AuditEventCategory.REPORTING_STRUCTURE, "Reporting manager changed");

        put(map, AuditActionType.PROFILE_CHANGED, AuditEventCategory.PROFILE, "Profile changed");
        put(map, AuditActionType.PROFILE_CHANGE_REQUESTED, AuditEventCategory.PROFILE, "Profile change requested");
        put(map, AuditActionType.PROFILE_CHANGE_APPROVED, AuditEventCategory.PROFILE, "Profile change approved");
        put(map, AuditActionType.PROFILE_CHANGE_REJECTED, AuditEventCategory.PROFILE, "Profile change rejected");

        put(map, AuditActionType.TIMESHEET_CREATED, AuditEventCategory.TIMESHEET, "Timesheet created");
        put(map, AuditActionType.TIMESHEET_SUBMITTED, AuditEventCategory.TIMESHEET, "Timesheet submitted");
        put(map, AuditActionType.TIMESHEET_UPDATED, AuditEventCategory.TIMESHEET, "Timesheet updated");
        put(map, AuditActionType.TIMESHEET_DOCUMENT_UPLOADED, AuditEventCategory.TIMESHEET, "Timesheet document uploaded");

        put(map, AuditActionType.PAYROLL_PERIOD_CHANGED, AuditEventCategory.PAYROLL, "Payroll period changed");
        put(map, AuditActionType.PAYROLL_PROCESSED, AuditEventCategory.PAYROLL, "Payroll processed");
        put(map, AuditActionType.PAYROLL_READINESS_REMINDER_SENT, AuditEventCategory.PAYROLL, "Payroll readiness reminder sent");
        put(map, AuditActionType.PAYROLL_READINESS_BULK_REMINDER_SENT, AuditEventCategory.PAYROLL, "Payroll readiness bulk reminder sent");
        put(map, AuditActionType.PAYROLL_READINESS_AUTOMATIC_REMINDER_RUN, AuditEventCategory.PAYROLL, "Payroll readiness automatic reminder run");
        put(map, AuditActionType.PAYSTUB_GENERATED, AuditEventCategory.PAYROLL, "Paystub generated");
        put(map, AuditActionType.PAYSTUB_REGENERATED, AuditEventCategory.PAYROLL, "Paystub regenerated");

        put(map, AuditActionType.DOCUMENT_UPLOADED, AuditEventCategory.DOCUMENT, "Document uploaded");
        put(map, AuditActionType.DOCUMENT_REPLACED, AuditEventCategory.DOCUMENT, "Document replaced");
        put(map, AuditActionType.DOCUMENT_ACCESSED, AuditEventCategory.DOCUMENT, "Document accessed");
        put(map, AuditActionType.DOCUMENT_REQUEST_CREATED, AuditEventCategory.DOCUMENT, "Document request created");
        put(map, AuditActionType.DOCUMENT_REQUEST_FULFILLED, AuditEventCategory.DOCUMENT, "Document request fulfilled");
        put(map, AuditActionType.DOCUMENT_REQUEST_REJECTED, AuditEventCategory.DOCUMENT, "Document request rejected");
        put(map, AuditActionType.DOCUMENT_REQUEST_CANCELLED, AuditEventCategory.DOCUMENT, "Document request cancelled");
        put(map, AuditActionType.OFFER_REQUEST_CREATED, AuditEventCategory.DOCUMENT, "Offer request created");
        put(map, AuditActionType.OFFER_DRAFT_SAVED, AuditEventCategory.DOCUMENT, "Offer draft saved");
        put(map, AuditActionType.OFFER_GENERATED, AuditEventCategory.DOCUMENT, "Offer generated");
        put(map, AuditActionType.OFFER_SENT_TO_EMPLOYEE, AuditEventCategory.DOCUMENT, "Offer sent to employee");
        put(map, AuditActionType.OFFER_VIEWED, AuditEventCategory.DOCUMENT, "Offer viewed");
        put(map, AuditActionType.OFFER_SIGNED_DOCUMENT_UPLOADED, AuditEventCategory.DOCUMENT, "Signed offer uploaded");
        put(map, AuditActionType.OFFER_REVISED, AuditEventCategory.DOCUMENT, "Offer revised");
        put(map, AuditActionType.OFFER_COMPLETED, AuditEventCategory.DOCUMENT, "Offer completed");
        put(map, AuditActionType.OFFER_EMAILED, AuditEventCategory.DOCUMENT, "Offer emailed");
        put(map, AuditActionType.OFFER_SUBMITTED_FOR_APPROVAL, AuditEventCategory.DOCUMENT, "Document submitted for approval");
        put(map, AuditActionType.OFFER_APPROVED, AuditEventCategory.DOCUMENT, "Document approved");
        put(map, AuditActionType.OFFER_REJECTED, AuditEventCategory.DOCUMENT, "Document rejected");
        put(map, AuditActionType.I983_SUBMITTED, AuditEventCategory.DOCUMENT, "I-983 submitted");
        put(map, AuditActionType.I983_RESUBMITTED, AuditEventCategory.DOCUMENT, "I-983 resubmitted");
        put(map, AuditActionType.I983_DOCUMENT_ACCESSED, AuditEventCategory.DOCUMENT, "I-983 document reviewed");
        put(map, AuditActionType.I983_DOCUMENT_REPLACED, AuditEventCategory.DOCUMENT, "I-983 document replaced");
        put(map, AuditActionType.I983_SIGNATURE_APPLIED, AuditEventCategory.DOCUMENT, "I-983 signature applied");
        put(map, AuditActionType.I983_APPROVED, AuditEventCategory.DOCUMENT, "I-983 approved");
        put(map, AuditActionType.I983_RETURNED_FOR_CORRECTION, AuditEventCategory.DOCUMENT, "I-983 returned for correction");
        put(map, AuditActionType.I983_REJECTED, AuditEventCategory.DOCUMENT, "I-983 rejected");

        put(map, AuditActionType.JOB_CREATED, AuditEventCategory.RECRUITMENT, "Job posting created");
        put(map, AuditActionType.JOB_UPDATED, AuditEventCategory.RECRUITMENT, "Job posting updated");
        put(map, AuditActionType.EVENT_CREATED, AuditEventCategory.RECRUITMENT, "Event created");
        put(map, AuditActionType.EVENT_UPDATED, AuditEventCategory.RECRUITMENT, "Event updated");

        put(map, AuditActionType.REPORT_GENERATED, AuditEventCategory.REPORTING_OUTPUT, "Report generated");

        put(map, AuditActionType.PASSWORD_CHANGED, AuditEventCategory.SECURITY, "Password changed");
        put(map, AuditActionType.ACCOUNT_EMAIL_CHANGED, AuditEventCategory.SECURITY, "Account email changed");
        put(map, AuditActionType.ORGANIZATION_SETTINGS_UPDATED, AuditEventCategory.ACCOUNT_MANAGEMENT, "Organization settings updated");

        return map;
    }

    private static void put(Map<AuditActionType, Descriptor> map, AuditActionType type,
                            AuditEventCategory category, String label) {
        map.put(type, new Descriptor(type, category, label));
    }

    private static String humanize(String enumName) {
        String lower = enumName.toLowerCase().replace('_', ' ');
        return lower.isEmpty() ? lower : Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
