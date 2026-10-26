package com.waypoint.service;

import com.waypoint.config.PayrollReadinessProperties;
import com.waypoint.dto.myhr.PayrollReadinessBulkReminderRequest;
import com.waypoint.dto.myhr.PayrollReadinessPeriodResponse;
import com.waypoint.dto.myhr.PayrollReadinessReminderResult;
import com.waypoint.dto.myhr.PayrollReadinessReminderResultItem;
import com.waypoint.dto.myhr.PayrollReadinessResponse;
import com.waypoint.dto.myhr.PayrollReadinessRowResponse;
import com.waypoint.dto.myhr.PayrollReadinessSummaryResponse;
import com.waypoint.entity.EmployeeProfile;
import com.waypoint.entity.PayrollReadinessReminder;
import com.waypoint.entity.ReportingAssignment;
import com.waypoint.entity.Timesheet;
import com.waypoint.entity.TimesheetEntry;
import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.AuditActionType;
import com.waypoint.enums.NotificationType;
import com.waypoint.enums.PayrollReadinessReminderSource;
import com.waypoint.enums.PayrollReadinessReminderType;
import com.waypoint.enums.PayrollReadinessResponsibility;
import com.waypoint.enums.PayrollReadinessState;
import com.waypoint.enums.RelationshipType;
import com.waypoint.enums.TimesheetEntryType;
import com.waypoint.enums.TimesheetStatus;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.repository.EmployeeProfileRepository;
import com.waypoint.repository.PayrollReadinessReminderRepository;
import com.waypoint.repository.ReportingAssignmentRepository;
import com.waypoint.repository.TimesheetRepository;
import com.waypoint.repository.UserRepository;
import com.waypoint.security.MyHrAuthenticatedUserService;
import com.waypoint.security.MyHrAuthenticatedUserService.AuthenticatedMyHrUser;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.EnumSet;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PayrollReadinessService {
    private static final Set<UserRole> READINESS_ROLES = Set.of(UserRole.COMPANY_ADMIN, UserRole.HR);
    private static final RelationshipType PRIMARY_MANAGER = RelationshipType.PRIMARY_REPORTING_MANAGER;
    private static final DateTimeFormatter CUTOFF_LABEL = DateTimeFormatter.ofPattern("MMMM d", Locale.US);

    private final MyHrAuthenticatedUserService authenticatedUserService;
    private final EmployeeProfileRepository employeeProfileRepository;
    private final UserRepository userRepository;
    private final ReportingAssignmentRepository reportingAssignmentRepository;
    private final TimesheetRepository timesheetRepository;
    private final PayrollReadinessReminderRepository reminderRepository;
    private final InAppNotificationService notificationService;
    private final AuditService auditService;
    private final PayrollReadinessPeriodResolver periodResolver;
    private final PayrollReadinessProperties payrollReadinessProperties;

    public PayrollReadinessResponse getReadiness(
            String actorUserId,
            LocalDate payrollMonthDate,
            String search,
            String readiness,
            String responsibility,
            String managerId,
            String clientId,
            String sort,
            int page,
            int size) {
        AuthenticatedMyHrUser actor = requireActor(actorUserId);
        Period period = resolvePeriod(payrollMonthDate);
        validateQuery(readiness, responsibility, sort, clientId);
        List<EmployeeContext> scopedEmployees = applyManagerFilter(scopedEmployees(actor), managerId);
        List<PayrollReadinessRowResponse> overviewRows = buildRows(actor, period, scopedEmployees).stream()
                .filter(row -> matchesSearch(row, search))
                .toList();
        PayrollReadinessSummaryResponse summary = summarize(overviewRows);
        List<PayrollReadinessRowResponse> allRows = overviewRows.stream()
                .filter(row -> matchesReadiness(row, readiness))
                .filter(row -> matchesResponsibility(row, responsibility))
                .sorted(rowComparator(sort))
                .toList();
        int safeSize = Math.max(1, Math.min(size, 100));
        int safePage = Math.max(0, page);
        int from = Math.min(allRows.size(), safePage * safeSize);
        int to = Math.min(allRows.size(), from + safeSize);
        return new PayrollReadinessResponse(
                periodResponse(period),
                summary,
                allRows.subList(from, to),
                safePage,
                safeSize,
                allRows.size(),
                (int) Math.ceil((double) allRows.size() / safeSize));
    }

    public PayrollReadinessReminderResult sendReminder(String actorUserId, LocalDate payrollMonthDate, String employeeUserId) {
        return sendReminders(actorUserId, payrollMonthDate, List.of(employeeUserId), null, PayrollReadinessReminderSource.MANUAL);
    }

    public PayrollReadinessReminderResult sendBulkReminders(String actorUserId, LocalDate payrollMonthDate, PayrollReadinessBulkReminderRequest request) {
        List<String> ids = request == null || request.employeeUserIds() == null ? List.of() : request.employeeUserIds();
        return sendReminders(actorUserId, payrollMonthDate, ids, request, PayrollReadinessReminderSource.BULK);
    }

    private PayrollReadinessReminderResult sendReminders(
            String actorUserId,
            LocalDate payrollMonthDate,
            List<String> requestedIds,
            PayrollReadinessBulkReminderRequest filters,
            PayrollReadinessReminderSource source) {
        AuthenticatedMyHrUser actor = requireActor(actorUserId);
        Period period = resolvePeriod(payrollMonthDate);
        Set<String> requested = requestedIds == null ? Set.of() : requestedIds.stream()
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<EmployeeContext> scoped = applyManagerFilter(scopedEmployees(actor), filters == null ? null : filters.managerId());
        List<PayrollReadinessRowResponse> candidateRows = buildRows(actor, period, scoped).stream()
                .filter(row -> requested.isEmpty() || requested.contains(row.employeeUserId()))
                .filter(row -> filters == null || matchesSearch(row, filters.search()))
                .filter(row -> filters == null || matchesReadiness(row, filters.readiness()))
                .filter(row -> filters == null || matchesResponsibility(row, filters.responsibility()))
                .sorted(rowComparator(filters == null ? null : filters.sort()))
                .toList();
        Map<String, PayrollReadinessRowResponse> rows = candidateRows.stream()
                .collect(Collectors.toMap(PayrollReadinessRowResponse::employeeUserId, row -> row, (a, b) -> a, LinkedHashMap::new));

        List<PayrollReadinessReminderResultItem> results = new ArrayList<>();
        int sent = 0;
        int skipped = 0;
        int skippedReady = 0;
        int skippedDuplicate = 0;
        int skippedUnauthorized = 0;
        int skippedNoRecipient = 0;
        int failed = 0;
        int eligible = 0;

        for (String employeeUserId : requested) {
            if (!rows.containsKey(employeeUserId)) {
                skipped++;
                skippedUnauthorized++;
                results.add(new PayrollReadinessReminderResultItem(employeeUserId, false, true,
                        "Employee is outside your authorized payroll readiness scope", null, null, null));
            }
        }

        for (PayrollReadinessRowResponse row : rows.values()) {
            String employeeUserId = row.employeeUserId();
            if (!isReminderEligible(row.readinessState())) {
                skipped++;
                skippedReady++;
                results.add(new PayrollReadinessReminderResultItem(employeeUserId, false, true,
                        "Readiness state is not eligible for employee reminder", row.readinessState(), null, row.lastReminderAt()));
                continue;
            }
            eligible++;
            Optional<PayrollReadinessReminder> existing = reminderRepository
                    .findByOrganizationIdAndEmployeeUserIdAndPayrollMonthAndReminderType(
                            actor.organizationId(), employeeUserId, period.payrollMonthStart(), PayrollReadinessReminderType.EMPLOYEE_TIMESHEET_ACTION);
            if (existing.isPresent()) {
                skipped++;
                skippedDuplicate++;
                results.add(new PayrollReadinessReminderResultItem(employeeUserId, false, true,
                        "RECENT_REMINDER_ALREADY_SENT", row.readinessState(), null, existing.get().getSentAt()));
                continue;
            }
            try {
                ReminderContent content = reminderContent(row.readinessState(), period);
                var notification = notificationService.createNotification(
                        actor.organizationId(),
                        employeeUserId,
                        NotificationType.PAYROLL_TIMESHEET_ACTION_REQUIRED,
                        content.title(),
                        content.message(),
                        employeeUserId,
                        employeeTimesheetActionUrl(period));
                LocalDateTime sentAt = LocalDateTime.now();
                PayrollReadinessReminder reminder = PayrollReadinessReminder.builder()
                        .organizationId(actor.organizationId())
                        .employeeUserId(employeeUserId)
                        .payrollMonth(period.payrollMonthStart())
                        .requiredPeriodStart(period.requiredStart())
                        .requiredPeriodEnd(period.requiredEnd())
                        .reminderType(PayrollReadinessReminderType.EMPLOYEE_TIMESHEET_ACTION)
                        .source(source)
                        .sentByUserId(actor.userId())
                        .notificationId(notification.getId())
                        .sentAt(sentAt)
                        .build();
                reminderRepository.save(reminder);
                auditReminder(actor, source, period, row, sentAt, "SENT", null);
                sent++;
                results.add(new PayrollReadinessReminderResultItem(employeeUserId, true, false,
                        "Reminder sent", row.readinessState(), sentAt, sentAt));
            } catch (DuplicateKeyException duplicate) {
                skipped++;
                skippedDuplicate++;
                Optional<PayrollReadinessReminder> existingAfterRace = reminderRepository
                        .findByOrganizationIdAndEmployeeUserIdAndPayrollMonthAndReminderType(
                                actor.organizationId(), employeeUserId, period.payrollMonthStart(), PayrollReadinessReminderType.EMPLOYEE_TIMESHEET_ACTION);
                results.add(new PayrollReadinessReminderResultItem(employeeUserId, false, true,
                        "RECENT_REMINDER_ALREADY_SENT", row.readinessState(), null, existingAfterRace.map(PayrollReadinessReminder::getSentAt).orElse(row.lastReminderAt())));
            } catch (IllegalArgumentException ex) {
                skipped++;
                skippedNoRecipient++;
                auditReminder(actor, source, period, row, null, "SKIPPED", "Missing notification recipient");
                results.add(new PayrollReadinessReminderResultItem(employeeUserId, false, true,
                        "No valid notification recipient", row.readinessState(), null, row.lastReminderAt()));
            } catch (RuntimeException ex) {
                failed++;
                auditReminder(actor, source, period, row, null, "FAILED", "Reminder failed");
                results.add(new PayrollReadinessReminderResultItem(employeeUserId, false, false,
                        "Reminder failed", row.readinessState(), null, row.lastReminderAt()));
            }
        }
        int requestedCount = requested.isEmpty() ? candidateRows.size() : requested.size();
        return new PayrollReadinessReminderResult(requestedCount, candidateRows.size(), eligible, sent, skipped,
                skippedReady, skippedDuplicate, skippedUnauthorized, skippedNoRecipient, failed, results);
    }

    public Period resolvePeriod(LocalDate payrollMonthDate) {
        return periodResolver.resolvePayrollMonth(payrollMonthDate);
    }

    private List<EmployeeContext> scopedEmployees(AuthenticatedMyHrUser actor) {
        List<EmployeeProfile> profiles;
        if (actor.role() == UserRole.COMPANY_ADMIN) {
            profiles = employeeProfileRepository.findByOrganizationId(actor.organizationId());
        } else if (actor.role() == UserRole.HR) {
            Set<String> ids = new LinkedHashSet<>();
            employeeProfileRepository.findByUserId(actor.userId())
                    .filter(profile -> actor.organizationId().equals(profile.getOrganizationId()))
                    .map(EmployeeProfile::getUserId)
                    .ifPresent(ids::add);
            reportingAssignmentRepository.findByOrganizationIdAndReportingManagerUserIdAndRelationshipTypeAndIsActiveTrue(
                            actor.organizationId(), actor.userId(), PRIMARY_MANAGER)
                    .stream()
                    .map(ReportingAssignment::getEmployeeUserId)
                    .forEach(ids::add);
            profiles = ids.isEmpty() ? List.of() : employeeProfileRepository.findByUserIdIn(ids).stream()
                    .filter(profile -> actor.organizationId().equals(profile.getOrganizationId()))
                    .toList();
        } else {
            throw new ForbiddenException("You do not have access to payroll readiness");
        }
        Set<String> userIds = profiles.stream().map(EmployeeProfile::getUserId).collect(Collectors.toSet());
        Map<String, User> users = userIds.isEmpty() ? Map.of() : userRepository.findByIdIn(userIds).stream()
                .collect(Collectors.toMap(User::getId, user -> user, (a, b) -> a));
        Map<String, ManagerContext> managers = managerContexts(actor.organizationId(), profiles);
        return profiles.stream()
                .map(profile -> new EmployeeContext(profile, users.get(profile.getUserId()), managers.get(profile.getUserId())))
                .filter(context -> context.user() != null && context.user().getAccountStatus() == AccountStatus.ACTIVE)
                .toList();
    }

    private List<PayrollReadinessRowResponse> buildRows(AuthenticatedMyHrUser actor, Period period, List<EmployeeContext> employees) {
        Set<String> userIds = employees.stream().map(context -> context.profile().getUserId()).collect(Collectors.toSet());
        Map<String, List<Timesheet>> timesheetsByUser = userIds.isEmpty() ? Map.of() : timesheetRepository
                .findByOrganizationIdAndUserIdInAndPeriodStartDateLessThanEqualAndPeriodEndDateGreaterThanEqual(
                        actor.organizationId(), userIds, period.requiredEnd(), period.requiredStart())
                .stream()
                .collect(Collectors.groupingBy(Timesheet::getUserId));
        Map<String, LocalDateTime> lastReminderByUser = reminderRepository
                .findByOrganizationIdAndEmployeeUserIdInAndPayrollMonthAndReminderTypeIn(
                        actor.organizationId(), userIds, period.payrollMonthStart(), employeeReminderTypes())
                .stream()
                .collect(Collectors.toMap(PayrollReadinessReminder::getEmployeeUserId, PayrollReadinessReminder::getSentAt, (a, b) -> a.isAfter(b) ? a : b));
        return employees.stream()
                .map(context -> toRow(context, period, timesheetsByUser.getOrDefault(context.profile().getUserId(), List.of()), lastReminderByUser.get(context.profile().getUserId())))
                .toList();
    }

    private PayrollReadinessRowResponse toRow(EmployeeContext context, Period period, List<Timesheet> timesheets, LocalDateTime lastReminderAt) {
        CoverageSnapshot coverage = coverageSnapshot(period, timesheets);
        PayrollReadinessDecision decision = decideReadiness(coverage, timesheets);
        PayrollReadinessResponsibility responsibility = responsibilityFor(decision.state());
        return new PayrollReadinessRowResponse(
                context.profile().getUserId(),
                context.profile().getEmployeeId(),
                context.profile().getName(),
                context.user().getRole(),
                context.profile().getDesignation(),
                context.manager() == null ? null : context.manager().name(),
                period.requiredStart(),
                period.requiredEnd(),
                coverage.statuses(),
                coverage.requiredDays(),
                coverage.coveredDays(),
                coverage.missingDays(),
                coverage.approvedCoveredDays(),
                coverage.submittedCoveredDays(),
                coverage.monthSubmissionComplete(),
                coverage.coveragePercentage(),
                coverage.approvedHours(),
                coverage.workedHours(),
                coverage.overtimeHours(),
                coverage.leaveHours(),
                decision.state(),
                responsibility,
                decision.reason(),
                isReminderEligible(decision.state()),
                lastReminderAt);
    }

    private CoverageSnapshot coverageSnapshot(Period period, List<Timesheet> timesheets) {
        Set<LocalDate> requiredDates = requiredWorkDates(period.requiredStart(), period.requiredEnd());
        // Structured entry types allow payroll readiness to treat approved sick leave,
        // planned/unplanned leave, comp-off, holidays, and non-working days as accounted
        // coverage without pretending they are worked hours. Older records with no
        // entryType remain WORK; older zero-hour entries with a note still count as
        // leave for backward compatibility.
        BigDecimal standardWorkday = BigDecimal.valueOf(payrollReadinessProperties.getStandardWorkdayHours());

        List<TimesheetStatus> statuses = new ArrayList<>();
        boolean overlappingRecordWithoutEntries = false;
        // A date can be touched by more than one overlapping timesheet record
        // (corrections, resubmissions after a rejection). Each date's status
        // must be resolved from whichever record touched it MOST RECENTLY -
        // not "does any overlapping record have this status" - otherwise a
        // long-superseded rejected/submitted draft keeps blocking readiness
        // for a date that has since been approved via a newer record. This
        // mirrors exactly how the Timesheets page itself consolidates
        // overlapping records for display (consolidatePeriodEntries in
        // TimesheetsPage.jsx): take the latest record touching the date, and
        // if that latest one was rejected, fall back to whatever was most
        // recently approved before it rather than leaving the date stuck
        // looking rejected/pending forever.
        Map<LocalDate, List<DatedEntry>> entriesByDate = new HashMap<>();

        for (Timesheet timesheet : timesheets) {
            if (timesheet == null || !overlaps(timesheet, period)) continue;
            TimesheetStatus status = timesheet.getStatus();
            if (status != null && !statuses.contains(status)) statuses.add(status);
            LocalDate effectiveStart = max(timesheet.getPeriodStartDate(), period.requiredStart());
            LocalDate effectiveEnd = min(timesheet.getPeriodEndDate(), period.requiredEnd());
            List<TimesheetEntry> entries = timesheet.getEntries() == null ? List.of() : timesheet.getEntries();
            LocalDateTime recency = recencyOf(timesheet);
            boolean hasEntryInsideTargetMonth = false;
            for (TimesheetEntry entry : entries) {
                if (entry == null || entry.getWorkDate() == null) continue;
                LocalDate workDate = entry.getWorkDate();
                if (workDate.isBefore(effectiveStart) || workDate.isAfter(effectiveEnd)) continue;
                hasEntryInsideTargetMonth = true;
                BigDecimal hours = entry.getHoursWorked() == null ? BigDecimal.ZERO : entry.getHoursWorked();
                TimesheetEntryType entryType = normalizeEntryType(entry.getEntryType());
                entriesByDate.computeIfAbsent(workDate, ignored -> new ArrayList<>())
                        .add(new DatedEntry(status, hours, entryType, entry.getDescription(), recency));
            }
            if (!hasEntryInsideTargetMonth) overlappingRecordWithoutEntries = true;
        }

        Set<LocalDate> coveredDates = new HashSet<>();
        Set<LocalDate> approvedDates = new HashSet<>();
        Set<LocalDate> submittedDates = new HashSet<>();
        Set<LocalDate> draftDates = new HashSet<>();
        Set<LocalDate> rejectedDates = new HashSet<>();
        BigDecimal approvedHours = BigDecimal.ZERO;
        BigDecimal workedHours = BigDecimal.ZERO;
        BigDecimal overtimeHours = BigDecimal.ZERO;
        BigDecimal leaveHours = BigDecimal.ZERO;


        for (Map.Entry<LocalDate, List<DatedEntry>> dated : entriesByDate.entrySet()) {
            DatedEntry resolved = resolveLatest(dated.getValue());
            if (resolved == null) continue;
            LocalDate date = dated.getKey();
            TimesheetEntryType entryType = normalizeEntryType(resolved.entryType());
            if (entryType == TimesheetEntryType.WORK) {
                workedHours = workedHours.add(resolved.hours());
            }
            // Required-date coverage only tracks actual required work dates (see
            // requiredWorkDates()) - an entry on a non-required date (e.g. a
            // weekend, by default) still contributes to the hours totals below,
            // it just isn't needed to satisfy coverage of a required date.
            if (requiredDates.contains(date)) {
                coveredDates.add(date);
                switch (resolved.status()) {
                    case APPROVED -> approvedDates.add(date);
                    case SUBMITTED -> submittedDates.add(date);
                    case DRAFT -> draftDates.add(date);
                    case REJECTED -> rejectedDates.add(date);
                }
            }
            if (resolved.status() == TimesheetStatus.APPROVED) {
                if (entryType == TimesheetEntryType.WORK) {
                    approvedHours = approvedHours.add(resolved.hours());
                    if (resolved.hours().compareTo(standardWorkday) > 0) {
                        overtimeHours = overtimeHours.add(resolved.hours().subtract(standardWorkday));
                    }
                } else if (isLeaveEntryType(entryType)) {
                    leaveHours = leaveHours.add(resolved.hours().compareTo(BigDecimal.ZERO) > 0 ? resolved.hours() : standardWorkday);
                }
                if (entryType == TimesheetEntryType.WORK
                        && resolved.hours().compareTo(BigDecimal.ZERO) == 0
                        && StringUtils.hasText(resolved.description())) {
                    leaveHours = leaveHours.add(standardWorkday);
                }
            }
        }

        int requiredDays = requiredDates.size();
        int coveredDays = coveredDates.size();
        int missingDays = Math.max(0, requiredDays - coveredDays);
        double coveragePercentage = requiredDays == 0 ? 0.0 : BigDecimal.valueOf(coveredDays)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(requiredDays), 2, RoundingMode.HALF_UP)
                .doubleValue();
        return new CoverageSnapshot(
                statuses,
                requiredDays,
                coveredDays,
                missingDays,
                approvedDates.size(),
                submittedDates.size(),
                missingDays == 0 && !coveredDates.isEmpty(),
                !rejectedDates.isEmpty(),
                !draftDates.isEmpty(),
                !submittedDates.isEmpty(),
                !approvedDates.isEmpty(),
                overlappingRecordWithoutEntries,
                approvedHours.setScale(2, RoundingMode.HALF_UP),
                workedHours.setScale(2, RoundingMode.HALF_UP),
                overtimeHours.setScale(2, RoundingMode.HALF_UP),
                leaveHours.setScale(2, RoundingMode.HALF_UP),
                coveragePercentage);
    }

    // One candidate value for a single date, from one overlapping timesheet record.
    private record DatedEntry(TimesheetStatus status, BigDecimal hours, TimesheetEntryType entryType, String description, LocalDateTime recency) {}

    private LocalDateTime recencyOf(Timesheet timesheet) {
        if (timesheet.getUpdatedAt() != null) return timesheet.getUpdatedAt();
        if (timesheet.getSubmittedAt() != null) return timesheet.getSubmittedAt();
        if (timesheet.getCreatedAt() != null) return timesheet.getCreatedAt();
        return LocalDateTime.MIN;
    }

    // Resolves which of several overlapping records "wins" for one date: the
    // most recently touched one, unless that latest action was a rejection -
    // in which case fall back to the most recently APPROVED candidate (if
    // any), so an old date doesn't stay stuck looking rejected forever once
    // it's been superseded. If no approval exists at all, the rejection
    // itself stands, so the date still surfaces as needing correction
    // rather than silently vanishing as "missing".
    private DatedEntry resolveLatest(List<DatedEntry> candidates) {
        DatedEntry latest = candidates.stream().max(Comparator.comparing(DatedEntry::recency)).orElse(null);
        if (latest == null || latest.status() != TimesheetStatus.REJECTED) return latest;
        return candidates.stream()
                .filter(candidate -> candidate.status() == TimesheetStatus.APPROVED)
                .max(Comparator.comparing(DatedEntry::recency))
                .orElse(latest);
    }

    // No org calendar/work-schedule model exists yet, so a standard Mon-Fri work
    // week is the only available default (PayrollReadinessProperties#requireWeekendCoverage
    // lets an org opt into requiring weekend coverage too). Every calendar day is
    // still a valid date to log hours on (see coverageSnapshot) - this only decides
    // which dates must be covered for readiness.
    private TimesheetEntryType normalizeEntryType(TimesheetEntryType entryType) {
        return entryType == null ? TimesheetEntryType.WORK : entryType;
    }

    private boolean isLeaveEntryType(TimesheetEntryType entryType) {
        return entryType == TimesheetEntryType.SICK_LEAVE
                || entryType == TimesheetEntryType.PLANNED_LEAVE
                || entryType == TimesheetEntryType.UNPLANNED_LEAVE
                || entryType == TimesheetEntryType.COMP_OFF;
    }

    private Set<LocalDate> requiredWorkDates(LocalDate start, LocalDate end) {
        Set<LocalDate> dates = new HashSet<>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            boolean isWeekend = date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
            if (!isWeekend || payrollReadinessProperties.isRequireWeekendCoverage()) dates.add(date);
        }
        return dates;
    }

    private PayrollReadinessDecision decideReadiness(CoverageSnapshot coverage, List<Timesheet> timesheets) {
        if (timesheets.isEmpty()) {
            return new PayrollReadinessDecision(PayrollReadinessState.MISSING_TIMESHEET,
                    "No existing timesheet record overlaps the required month.");
        }
        if (coverage.coveredDays() == 0) {
            return new PayrollReadinessDecision(PayrollReadinessState.INCOMPLETE_TIMESHEET,
                    "Existing overlapping timesheet records do not contain dated entries inside the required month.");
        }
        if (coverage.hasRejectedCoverage()) {
            return new PayrollReadinessDecision(PayrollReadinessState.CORRECTION_REQUIRED,
                    "At least one required timesheet entry is rejected and requires correction.");
        }
        if (coverage.hasDraftCoverage() || coverage.hasRecordWithoutEntries()) {
            return new PayrollReadinessDecision(PayrollReadinessState.INCOMPLETE_TIMESHEET,
                    "At least one required timesheet record is incomplete or still a draft.");
        }
        if (coverage.missingDays() > 0 && coverage.hasApprovedCoverage()) {
            return new PayrollReadinessDecision(PayrollReadinessState.PARTIALLY_APPROVED,
                    "Some required month dates have approved coverage, but the target month is not fully covered by existing entries.");
        }
        if (coverage.missingDays() > 0) {
            return new PayrollReadinessDecision(PayrollReadinessState.MISSING_TIMESHEET,
                    "The required month is not fully covered by existing dated timesheet entries.");
        }
        if (coverage.hasSubmittedCoverage()) {
            return new PayrollReadinessDecision(PayrollReadinessState.AWAITING_APPROVAL,
                    "The required month is covered, but at least one timesheet entry is submitted and waiting for approval.");
        }
        if (coverage.approvedCoveredDays() == coverage.requiredDays()) {
            return new PayrollReadinessDecision(PayrollReadinessState.READY,
                    "All dated entries in the required month are approved for payroll readiness.");
        }
        return new PayrollReadinessDecision(PayrollReadinessState.INCOMPLETE_TIMESHEET,
                "Required timesheet records are not ready for payroll.");
    }

    private boolean overlaps(Timesheet timesheet, Period period) {
        return timesheet.getPeriodStartDate() != null
                && timesheet.getPeriodEndDate() != null
                && !timesheet.getPeriodStartDate().isAfter(period.requiredEnd())
                && !timesheet.getPeriodEndDate().isBefore(period.requiredStart());
    }

    private LocalDate max(LocalDate first, LocalDate second) {
        if (first == null) return second;
        return first.isAfter(second) ? first : second;
    }

    private LocalDate min(LocalDate first, LocalDate second) {
        if (first == null) return second;
        return first.isBefore(second) ? first : second;
    }

    private PayrollReadinessResponsibility responsibilityFor(PayrollReadinessState state) {
        return switch (state) {
            case MISSING_TIMESHEET, PARTIALLY_APPROVED, INCOMPLETE_TIMESHEET, CORRECTION_REQUIRED -> PayrollReadinessResponsibility.EMPLOYEE_ACTION_REQUIRED;
            case AWAITING_APPROVAL -> PayrollReadinessResponsibility.HR_ADMIN_FOLLOW_UP;
            case READY -> PayrollReadinessResponsibility.NONE;
        };
    }

    private PayrollReadinessSummaryResponse summarize(List<PayrollReadinessRowResponse> rows) {
        long ready = rows.stream().filter(row -> row.readinessState() == PayrollReadinessState.READY).count();
        long submitted = rows.stream().filter(PayrollReadinessRowResponse::monthSubmissionComplete).count();
        long partial = rows.stream().filter(row -> row.readinessState() == PayrollReadinessState.PARTIALLY_APPROVED).count();
        long missing = rows.stream().filter(row -> row.readinessState() == PayrollReadinessState.MISSING_TIMESHEET).count();
        long incomplete = rows.stream().filter(row -> row.readinessState() == PayrollReadinessState.INCOMPLETE_TIMESHEET).count();
        long awaiting = rows.stream().filter(row -> row.readinessState() == PayrollReadinessState.AWAITING_APPROVAL).count();
        long correction = rows.stream().filter(row -> row.readinessState() == PayrollReadinessState.CORRECTION_REQUIRED).count();
        long action = rows.stream().filter(row -> row.responsibility() == PayrollReadinessResponsibility.EMPLOYEE_ACTION_REQUIRED).count();
        BigDecimal approvedHours = rows.stream().map(PayrollReadinessRowResponse::approvedHours).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal workedHours = rows.stream().map(PayrollReadinessRowResponse::workedHours).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal overtimeHours = rows.stream().map(PayrollReadinessRowResponse::overtimeHours).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new PayrollReadinessSummaryResponse(rows.size(), ready, submitted, partial, action, missing, incomplete, awaiting, correction,
                approvedHours.setScale(2, RoundingMode.HALF_UP),
                workedHours.setScale(2, RoundingMode.HALF_UP),
                overtimeHours.setScale(2, RoundingMode.HALF_UP));
    }

    private Map<String, ManagerContext> managerContexts(String organizationId, List<EmployeeProfile> employees) {
        Map<String, EmployeeProfile> profileByUser = employeeProfileRepository.findByOrganizationId(organizationId).stream()
                .collect(Collectors.toMap(EmployeeProfile::getUserId, profile -> profile, (a, b) -> a));
        Set<String> employeeIds = employees.stream().map(EmployeeProfile::getUserId).collect(Collectors.toSet());
        List<ReportingAssignment> assignments = reportingAssignmentRepository
                .findByOrganizationIdAndRelationshipTypeAndIsActiveTrue(organizationId, PRIMARY_MANAGER).stream()
                .filter(assignment -> employeeIds.contains(assignment.getEmployeeUserId()))
                .toList();
        // Some managers (e.g. a Company Admin) have no EmployeeProfile document,
        // only a User account - falling straight to the raw Mongo userId in that
        // case would print an opaque ObjectId in the UI, so fall back to their
        // human-readable login ID first, same as MyHrAccountService#managerDisplayName.
        Set<String> managerIdsMissingProfile = assignments.stream()
                .map(ReportingAssignment::getReportingManagerUserId)
                .filter(managerId -> !profileByUser.containsKey(managerId))
                .collect(Collectors.toSet());
        Map<String, String> managerLoginIds = managerIdsMissingProfile.isEmpty() ? Map.of() : userRepository.findByIdIn(managerIdsMissingProfile).stream()
                .collect(Collectors.toMap(User::getId, User::getLoginId, (a, b) -> a));
        return assignments.stream()
                .collect(Collectors.toMap(ReportingAssignment::getEmployeeUserId,
                        assignment -> new ManagerContext(
                                assignment.getReportingManagerUserId(),
                                Optional.ofNullable(profileByUser.get(assignment.getReportingManagerUserId()))
                                        .map(EmployeeProfile::getName)
                                        .filter(StringUtils::hasText)
                                        .or(() -> Optional.ofNullable(managerLoginIds.get(assignment.getReportingManagerUserId())))
                                        .filter(StringUtils::hasText)
                                        .orElse(assignment.getReportingManagerUserId())),
                        (a, b) -> a));
    }

    private List<EmployeeContext> applyManagerFilter(List<EmployeeContext> employees, String managerId) {
        if (!StringUtils.hasText(managerId)) return employees;
        return employees.stream()
                .filter(context -> context.manager() != null && managerId.equals(context.manager().userId()))
                .toList();
    }

    private void validateQuery(String readiness, String responsibility, String sort, String clientId) {
        if (StringUtils.hasText(clientId)) {
            throw new BusinessException("Client filtering is not supported by the current employee/timesheet data model");
        }
        if (StringUtils.hasText(readiness) && !"ALL".equalsIgnoreCase(readiness) && !"ACTION_REQUIRED".equalsIgnoreCase(readiness) && !"MONTH_SUBMITTED".equalsIgnoreCase(readiness) && !"NEEDS_FOLLOW_UP".equalsIgnoreCase(readiness)) {
            try {
                PayrollReadinessState.valueOf(readiness.trim().toUpperCase(Locale.US));
            } catch (IllegalArgumentException ex) {
                throw new BusinessException("Unsupported payroll readiness status filter");
            }
        }
        if (StringUtils.hasText(responsibility) && !"ALL".equalsIgnoreCase(responsibility)) {
            try {
                PayrollReadinessResponsibility.valueOf(responsibility.trim().toUpperCase(Locale.US));
            } catch (IllegalArgumentException ex) {
                throw new BusinessException("Unsupported payroll readiness responsibility filter");
            }
        }
        String normalizedSort = StringUtils.hasText(sort) ? sort.trim().toUpperCase(Locale.US) : "EMPLOYEE_ASC";
        if (!Set.of("EMPLOYEE_ASC", "EMPLOYEE_DESC", "STATE", "LAST_REMINDER_DESC").contains(normalizedSort)) {
            throw new BusinessException("Unsupported payroll readiness sort");
        }
    }

    private Comparator<PayrollReadinessRowResponse> rowComparator(String sort) {
        String normalized = StringUtils.hasText(sort) ? sort.trim().toUpperCase() : "EMPLOYEE_ASC";
        return switch (normalized) {
            case "EMPLOYEE_DESC" -> Comparator.comparing(PayrollReadinessRowResponse::employeeName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)).reversed();
            case "STATE" -> Comparator.comparing(row -> row.readinessState().name());
            case "LAST_REMINDER_DESC" -> Comparator.comparing(PayrollReadinessRowResponse::lastReminderAt, Comparator.nullsLast(Comparator.reverseOrder()));
            default -> Comparator.comparing(PayrollReadinessRowResponse::employeeName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
        };
    }

    private boolean matchesSearch(PayrollReadinessRowResponse row, String search) {
        if (!StringUtils.hasText(search)) return true;
        String q = search.trim().toLowerCase(Locale.US);
        return contains(row.employeeName(), q) || contains(row.employeeId(), q);
    }

    private boolean matchesReadiness(PayrollReadinessRowResponse row, String readiness) {
        if (!StringUtils.hasText(readiness) || "ALL".equalsIgnoreCase(readiness)) return true;
        if ("NEEDS_FOLLOW_UP".equalsIgnoreCase(readiness)) return row.readinessState() != PayrollReadinessState.READY && row.readinessState() != PayrollReadinessState.AWAITING_APPROVAL;
        if ("MONTH_SUBMITTED".equalsIgnoreCase(readiness)) return row.monthSubmissionComplete();
        if ("ACTION_REQUIRED".equalsIgnoreCase(readiness)) {
            return row.responsibility() == PayrollReadinessResponsibility.EMPLOYEE_ACTION_REQUIRED;
        }
        return row.readinessState().name().equalsIgnoreCase(readiness);
    }

    private boolean matchesResponsibility(PayrollReadinessRowResponse row, String responsibility) {
        if (!StringUtils.hasText(responsibility) || "ALL".equalsIgnoreCase(responsibility)) return true;
        return row.responsibility().name().equalsIgnoreCase(responsibility);
    }

    private boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.US).contains(query);
    }

    private PayrollReadinessPeriodResponse periodResponse(Period period) {
        return new PayrollReadinessPeriodResponse(
                period.payrollMonthStart(),
                period.payrollMonthLabel(),
                period.requiredStart(),
                period.requiredEnd(),
                period.cutoffDate(),
                ChronoUnit.DAYS.between(LocalDate.now(), period.cutoffDate()));
    }

    private boolean isReminderEligible(PayrollReadinessState state) {
        return state != null && state != PayrollReadinessState.READY;
    }

    private ReminderContent reminderContent(PayrollReadinessState state, Period period) {
        String requiredMonth = period.requiredMonthLabel();
        String payrollMonth = period.payrollMonthLabel();
        String cutoff = period.cutoffDate().format(CUTOFF_LABEL);
        return switch (state) {
            case MISSING_TIMESHEET -> new ReminderContent(
                    "Timesheet Reminder - " + requiredMonth,
                    "Your " + requiredMonth + " timesheet has not been fully received for " + payrollMonth
                            + " payroll processing. Please complete the required timesheet information before " + cutoff + ".");
            case INCOMPLETE_TIMESHEET -> new ReminderContent(
                    "Incomplete Timesheet - " + requiredMonth,
                    "Your " + requiredMonth + " timesheet is incomplete for " + payrollMonth
                            + " payroll processing. Please review and complete the missing required information before " + cutoff + ".");
            case PARTIALLY_APPROVED -> new ReminderContent(
                    "Timesheet Partially Approved - " + requiredMonth,
                    "Only part of your " + requiredMonth + " timesheet is currently approved for " + payrollMonth
                            + " payroll processing. Please review the remaining required period before " + cutoff + ".");
            case AWAITING_APPROVAL -> new ReminderContent(
                    "Timesheet Approval Pending - " + requiredMonth,
                    "Your " + requiredMonth + " timesheet is recorded but is not fully approved for " + payrollMonth
                            + " payroll processing. Please review its current status before " + cutoff + ".");
            case CORRECTION_REQUIRED -> new ReminderContent(
                    "Timesheet Correction Required - " + requiredMonth,
                    "Part of your " + requiredMonth + " timesheet requires correction before it can be considered ready for "
                            + payrollMonth + " payroll processing. Please review the affected timesheet before " + cutoff + ".");
            case READY -> new ReminderContent(
                    "Timesheet Ready - " + requiredMonth,
                    "Your " + requiredMonth + " timesheet is ready for " + payrollMonth + " payroll processing.");
        };
    }

    private String employeeTimesheetActionUrl(Period period) {
        YearMonth target = YearMonth.from(period.requiredStart());
        return "/myhr/employee/timesheets?year=" + target.getYear() + "&month=" + target.getMonthValue();
    }

    private void auditReminder(AuthenticatedMyHrUser actor, PayrollReadinessReminderSource source, Period period,
                               PayrollReadinessRowResponse row, LocalDateTime sentAt, String outcome, String failureReason) {
        auditService.record(actor,
                source == PayrollReadinessReminderSource.BULK
                        ? AuditActionType.PAYROLL_READINESS_BULK_REMINDER_SENT
                        : AuditActionType.PAYROLL_READINESS_REMINDER_SENT,
                "PAYROLL_READINESS",
                row.employeeUserId(),
                row.employeeName(),
                "Payroll readiness reminder " + outcome.toLowerCase(Locale.US) + " for " + period.payrollMonthLabel(),
                null,
                Map.of(
                        "payrollMonth", period.payrollMonthStart().toString(),
                        "targetPeriodStart", period.requiredStart().toString(),
                        "targetPeriodEnd", period.requiredEnd().toString(),
                        "state", row.readinessState().name(),
                        "source", source.name(),
                        "outcome", outcome,
                        "sentAt", sentAt == null ? "" : sentAt.toString(),
                        "failureReason", failureReason == null ? "" : failureReason));
    }

    public AutomaticRunResult runAutomaticForActor(AuthenticatedMyHrUser actor, LocalDate runDate) {
        Period period = periodResolver.resolveForRunDate(runDate);
        int day = runDate.getDayOfMonth();
        PayrollReadinessReminderType employeeType = automaticEmployeeType(day);
        PayrollReadinessReminderType managementType = automaticManagementType(day);
        int evaluated = 0;
        int eligible = 0;
        int sent = 0;
        int skippedReady = 0;
        int skippedDuplicate = 0;
        int skippedNoRecipient = 0;
        int failed = 0;

        List<EmployeeContext> scoped = scopedEmployees(actor);
        if (employeeType != null && !runDate.isAfter(period.cutoffDate())) {
            evaluated = scoped.size();
            int batchSize = Math.max(1, payrollReadinessProperties.getBatchSize());
            for (int from = 0; from < scoped.size(); from += batchSize) {
                int to = Math.min(scoped.size(), from + batchSize);
                List<PayrollReadinessRowResponse> rows = buildRows(actor, period, scoped.subList(from, to));
                for (PayrollReadinessRowResponse row : rows) {
                    if (!isReminderEligible(row.readinessState())) {
                        skippedReady++;
                        continue;
                    }
                    eligible++;
                    ReminderSendOutcome outcome = sendAutomaticEmployeeReminder(actor, period, row, employeeType);
                    if (outcome.sent()) sent++;
                    else if (outcome.duplicate()) skippedDuplicate++;
                    else if (outcome.noRecipient()) skippedNoRecipient++;
                    else failed++;
                }
            }
        }

        boolean managementSent = false;
        if (managementType != null) {
            PayrollReadinessSummaryResponse scopedSummary = summarize(buildRows(actor, period, scoped));
            managementSent = sendManagementSummary(actor, period, scopedSummary, managementType);
        }

        auditService.record(actor,
                AuditActionType.PAYROLL_READINESS_AUTOMATIC_REMINDER_RUN,
                "PAYROLL_READINESS",
                period.payrollMonthStart().toString(),
                period.payrollMonthLabel(),
                "Automatic payroll readiness reminder run for " + period.payrollMonthLabel(),
                null,
                automaticRunMetadata(runDate, employeeType, managementType, evaluated, eligible, sent, skippedReady,
                        skippedDuplicate, skippedNoRecipient, failed, managementSent));
        return new AutomaticRunResult(evaluated, eligible, sent, skippedReady, skippedDuplicate, skippedNoRecipient, failed, managementSent);
    }

    private ReminderSendOutcome sendAutomaticEmployeeReminder(AuthenticatedMyHrUser actor, Period period, PayrollReadinessRowResponse row, PayrollReadinessReminderType reminderType) {
        Optional<PayrollReadinessReminder> existing = reminderRepository
                .findByOrganizationIdAndEmployeeUserIdAndPayrollMonthAndReminderType(actor.organizationId(), row.employeeUserId(), period.payrollMonthStart(), reminderType);
        if (existing.isPresent()) return ReminderSendOutcome.duplicateOutcome();
        try {
            ReminderContent content = automaticReminderContent(row.readinessState(), period, reminderType);
            var notification = notificationService.createNotification(
                    actor.organizationId(), row.employeeUserId(), NotificationType.PAYROLL_TIMESHEET_ACTION_REQUIRED,
                    content.title(), content.message(), row.employeeUserId(), employeeTimesheetActionUrl(period));
            PayrollReadinessReminder reminder = PayrollReadinessReminder.builder()
                    .organizationId(actor.organizationId())
                    .employeeUserId(row.employeeUserId())
                    .payrollMonth(period.payrollMonthStart())
                    .requiredPeriodStart(period.requiredStart())
                    .requiredPeriodEnd(period.requiredEnd())
                    .reminderType(reminderType)
                    .source(PayrollReadinessReminderSource.AUTOMATIC)
                    .sentByUserId("SYSTEM")
                    .notificationId(notification.getId())
                    .sentAt(LocalDateTime.now())
                    .build();
            reminderRepository.save(reminder);
            return ReminderSendOutcome.sentOutcome();
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            return ReminderSendOutcome.duplicateOutcome();
        } catch (IllegalArgumentException noRecipient) {
            return ReminderSendOutcome.noRecipientOutcome();
        } catch (RuntimeException failure) {
            return ReminderSendOutcome.failedOutcome();
        }
    }

    private boolean sendManagementSummary(AuthenticatedMyHrUser actor, Period period, PayrollReadinessSummaryResponse summary, PayrollReadinessReminderType reminderType) {
        Optional<PayrollReadinessReminder> existing = reminderRepository
                .findByOrganizationIdAndEmployeeUserIdAndPayrollMonthAndReminderType(actor.organizationId(), actor.userId(), period.payrollMonthStart(), reminderType);
        if (existing.isPresent()) return false;
        try {
            String persona = actor.role() == UserRole.COMPANY_ADMIN ? "admin" : "hr";
            String actionUrl = "/myhr/" + persona + "/payroll-readiness?payrollMonth=" + period.payrollMonthStart();
            String title = (reminderType == PayrollReadinessReminderType.AUTO_MANAGEMENT_CUTOFF_DAY
                    ? "Payroll Timesheet Cutoff - " : "Payroll Readiness Update - ") + period.payrollMonthLabel();
            String message = period.requiredMonthLabel() + " readiness: "
                    + summary.totalEmployees() + " employees reviewed, "
                    + summary.readyForPayroll() + " payroll ready, "
                    + summary.partiallyApproved() + " partially approved, "
                    + summary.missingTimesheet() + " missing, "
                    + summary.incompleteTimesheet() + " incomplete, "
                    + summary.awaitingApproval() + " awaiting approval, "
                    + summary.correctionRequired() + " correction required. Payroll cutoff: "
                    + period.cutoffDate().format(CUTOFF_LABEL) + ".";
            var notification = notificationService.createNotification(actor.organizationId(), actor.userId(),
                    NotificationType.PAYROLL_TIMESHEET_FOLLOW_UP_REQUIRED, title, message, period.payrollMonthStart().toString(), actionUrl);
            reminderRepository.save(PayrollReadinessReminder.builder()
                    .organizationId(actor.organizationId())
                    .employeeUserId(actor.userId())
                    .payrollMonth(period.payrollMonthStart())
                    .requiredPeriodStart(period.requiredStart())
                    .requiredPeriodEnd(period.requiredEnd())
                    .reminderType(reminderType)
                    .source(PayrollReadinessReminderSource.AUTOMATIC)
                    .sentByUserId("SYSTEM")
                    .notificationId(notification.getId())
                    .sentAt(LocalDateTime.now())
                    .build());
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }


    private Map<String, Object> automaticRunMetadata(LocalDate runDate,
                                                     PayrollReadinessReminderType employeeType,
                                                     PayrollReadinessReminderType managementType,
                                                     int evaluated,
                                                     int eligible,
                                                     int sent,
                                                     int skippedReady,
                                                     int skippedDuplicate,
                                                     int skippedNoRecipient,
                                                     int failed,
                                                     boolean managementSent) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("runDate", runDate.toString());
        metadata.put("employeeStage", employeeType == null ? "NONE" : employeeType.name());
        metadata.put("managementStage", managementType == null ? "NONE" : managementType.name());
        metadata.put("evaluated", evaluated);
        metadata.put("eligible", eligible);
        metadata.put("sent", sent);
        metadata.put("skippedReady", skippedReady);
        metadata.put("skippedDuplicate", skippedDuplicate);
        metadata.put("skippedNoRecipient", skippedNoRecipient);
        metadata.put("failed", failed);
        metadata.put("managementSummarySent", managementSent);
        return metadata;
    }

    private ReminderContent automaticReminderContent(PayrollReadinessState state, Period period, PayrollReadinessReminderType type) {
        String problem = readinessProblem(state);
        String cutoff = period.cutoffDate().format(CUTOFF_LABEL);
        String prefix = switch (type) {
            case AUTO_EMPLOYEE_DAY_5 -> "Your " + period.requiredMonthLabel() + " timesheet is not yet fully ready for " + period.payrollMonthLabel() + " payroll processing.";
            case AUTO_EMPLOYEE_DAY_7 -> "Your " + period.requiredMonthLabel() + " timesheet still requires attention for " + period.payrollMonthLabel() + " payroll processing.";
            case AUTO_EMPLOYEE_DAY_9 -> "Your " + period.requiredMonthLabel() + " timesheet is still not fully ready for payroll. The payroll cutoff is tomorrow, " + cutoff + ".";
            case AUTO_EMPLOYEE_CUTOFF_DAY -> "Your " + period.requiredMonthLabel() + " timesheet is not fully ready for " + period.payrollMonthLabel() + " payroll processing. Today is the payroll timesheet cutoff.";
            default -> "Your " + period.requiredMonthLabel() + " timesheet requires attention for payroll processing.";
        };
        String title = switch (type) {
            case AUTO_EMPLOYEE_DAY_5 -> "Timesheet Reminder - " + period.requiredMonthLabel();
            case AUTO_EMPLOYEE_DAY_7 -> "Timesheet Follow-up - " + period.requiredMonthLabel();
            case AUTO_EMPLOYEE_DAY_9 -> "Timesheet Action Required - " + period.requiredMonthLabel();
            case AUTO_EMPLOYEE_CUTOFF_DAY -> "Payroll Timesheet Cutoff Today - " + period.requiredMonthLabel();
            default -> "Timesheet Reminder - " + period.requiredMonthLabel();
        };
        return new ReminderContent(title, prefix + " " + problem + " Please review the outstanding issue before " + cutoff + ".");
    }

    private String readinessProblem(PayrollReadinessState state) {
        return switch (state) {
            case MISSING_TIMESHEET -> "Timesheet records are missing.";
            case INCOMPLETE_TIMESHEET -> "Your timesheet does not cover the complete required period.";
            case PARTIALLY_APPROVED -> "Only part of the required timesheet period is currently approved.";
            case AWAITING_APPROVAL -> "Your timesheet is recorded but is still awaiting external/client approval.";
            case CORRECTION_REQUIRED -> "Part of your timesheet requires correction.";
            case READY -> "Your timesheet is ready.";
        };
    }

    private PayrollReadinessReminderType automaticEmployeeType(int dayOfMonth) {
        if (!payrollReadinessProperties.getEmployeeReminderDays().contains(dayOfMonth)) return null;
        if (dayOfMonth == payrollReadinessProperties.getCutoffDay()) return PayrollReadinessReminderType.AUTO_EMPLOYEE_CUTOFF_DAY;
        if (dayOfMonth == 5) return PayrollReadinessReminderType.AUTO_EMPLOYEE_DAY_5;
        if (dayOfMonth == 7) return PayrollReadinessReminderType.AUTO_EMPLOYEE_DAY_7;
        if (dayOfMonth == 9) return PayrollReadinessReminderType.AUTO_EMPLOYEE_DAY_9;
        return PayrollReadinessReminderType.AUTO_EMPLOYEE_DAY_9;
    }

    private PayrollReadinessReminderType automaticManagementType(int dayOfMonth) {
        if (!payrollReadinessProperties.getManagementSummaryDays().contains(dayOfMonth)) return null;
        if (dayOfMonth == payrollReadinessProperties.getCutoffDay()) return PayrollReadinessReminderType.AUTO_MANAGEMENT_CUTOFF_DAY;
        if (dayOfMonth == 7) return PayrollReadinessReminderType.AUTO_MANAGEMENT_DAY_7;
        if (dayOfMonth == 9) return PayrollReadinessReminderType.AUTO_MANAGEMENT_DAY_9;
        return PayrollReadinessReminderType.AUTO_MANAGEMENT_DAY_9;
    }

    private Set<PayrollReadinessReminderType> employeeReminderTypes() {
        return EnumSet.of(PayrollReadinessReminderType.EMPLOYEE_TIMESHEET_ACTION,
                PayrollReadinessReminderType.AUTO_EMPLOYEE_DAY_5,
                PayrollReadinessReminderType.AUTO_EMPLOYEE_DAY_7,
                PayrollReadinessReminderType.AUTO_EMPLOYEE_DAY_9,
                PayrollReadinessReminderType.AUTO_EMPLOYEE_CUTOFF_DAY);
    }

    private AuthenticatedMyHrUser requireActor(String actorUserId) {
        return authenticatedUserService.require(actorUserId, READINESS_ROLES, false);
    }


    public record AutomaticRunResult(int evaluated, int eligible, int sent, int skippedReady, int skippedDuplicate, int skippedNoRecipient, int failed, boolean managementSummarySent) {
    }

    private record ReminderSendOutcome(boolean sent, boolean duplicate, boolean noRecipient) {
        static ReminderSendOutcome sentOutcome() { return new ReminderSendOutcome(true, false, false); }
        static ReminderSendOutcome duplicateOutcome() { return new ReminderSendOutcome(false, true, false); }
        static ReminderSendOutcome noRecipientOutcome() { return new ReminderSendOutcome(false, false, true); }
        static ReminderSendOutcome failedOutcome() { return new ReminderSendOutcome(false, false, false); }
    }

    private record ReminderContent(String title, String message) {
    }

    private record PayrollReadinessDecision(PayrollReadinessState state, String reason) {
    }

    private record CoverageSnapshot(
            List<TimesheetStatus> statuses,
            int requiredDays,
            int coveredDays,
            int missingDays,
            int approvedCoveredDays,
            int submittedCoveredDays,
            boolean monthSubmissionComplete,
            boolean hasRejectedCoverage,
            boolean hasDraftCoverage,
            boolean hasSubmittedCoverage,
            boolean hasApprovedCoverage,
            boolean hasRecordWithoutEntries,
            BigDecimal approvedHours,
            BigDecimal workedHours,
            BigDecimal overtimeHours,
            BigDecimal leaveHours,
            double coveragePercentage) {
    }

    public record Period(LocalDate payrollMonthStart, LocalDate requiredStart, LocalDate requiredEnd,
                         LocalDate cutoffDate, String payrollMonthLabel, String requiredMonthLabel) {
    }

    private record ManagerContext(String userId, String name) {
    }

    private record EmployeeContext(EmployeeProfile profile, User user, ManagerContext manager) {
    }
}

