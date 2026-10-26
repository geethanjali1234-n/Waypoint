package com.waypoint.service;

import com.waypoint.dto.myhr.TimesheetEntryRequest;
import com.waypoint.dto.myhr.TimesheetEntryResponse;
import com.waypoint.dto.myhr.TimesheetReviewRequest;
import com.waypoint.dto.myhr.TimesheetResponse;
import com.waypoint.dto.myhr.TimesheetSupportingDocumentResponse;
import com.waypoint.dto.myhr.TimesheetUpsertRequest;
import com.waypoint.entity.AuditLog;
import com.waypoint.entity.EmployeeProfile;
import com.waypoint.entity.ReportingAssignment;
import com.waypoint.entity.StoredDocument;
import com.waypoint.entity.Timesheet;
import com.waypoint.entity.TimesheetEntry;
import com.waypoint.entity.User;
import com.waypoint.entity.OrganizationSettings;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.DocumentType;
import com.waypoint.enums.AuditActionType;
import com.waypoint.enums.NotificationType;
import com.waypoint.enums.PayrollPeriod;
import com.waypoint.enums.RelationshipType;
import com.waypoint.enums.TimesheetEntryType;
import com.waypoint.enums.TimesheetStatus;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.repository.AuditLogRepository;
import com.waypoint.repository.EmployeeProfileRepository;
import com.waypoint.repository.OrganizationSettingsRepository;
import com.waypoint.repository.ReportingAssignmentRepository;
import com.waypoint.repository.StoredDocumentRepository;
import com.waypoint.repository.TimesheetRepository;
import com.waypoint.repository.UserRepository;
import com.waypoint.security.MyHrAuthenticatedUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class TimesheetService {

    private static final Set<UserRole> MY_HR_ROLES =
            Set.of(UserRole.COMPANY_ADMIN, UserRole.HR, UserRole.EMPLOYEE);
    private static final Set<UserRole> TIMESHEET_CREATORS =
            Set.of(UserRole.HR, UserRole.EMPLOYEE);
    private static final Set<UserRole> TIMESHEET_REVIEWERS =
            Set.of(UserRole.COMPANY_ADMIN, UserRole.HR);

    private final TimesheetRepository timesheetRepository;
    private final ReportingAssignmentRepository reportingAssignmentRepository;
    private final EmployeeProfileRepository employeeProfileRepository;
    private final UserRepository userRepository;
    private final StoredDocumentRepository storedDocumentRepository;
    private final AuditLogRepository auditLogRepository;
    private final OrganizationSettingsRepository organizationSettingsRepository;
    private final InAppNotificationService inAppNotificationService;
    private final MyHrAuthenticatedUserService authenticatedUserService;

    public PayrollPeriod getPayrollPeriodForOrganization(String actorUserId) {
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor = requireActor(actorUserId);
        return organizationSettingsRepository.findByOrganizationId(actor.organizationId())
                .map(OrganizationSettings::getPayrollPeriod)
                .filter(Objects::nonNull)
                .orElse(PayrollPeriod.MONTHLY);
    }

    public List<TimesheetResponse> listForUser(String actorUserId) {
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor = requireActor(actorUserId);
        List<Timesheet> records = visibleRecords(actor).stream()
                .sorted(Comparator
                        .comparing(Timesheet::getPeriodStartDate,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Timesheet::getSubmittedAt,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        return toResponses(records);
    }

    public TimesheetResponse getForUser(String actorUserId, String timesheetId) {
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor = requireActor(actorUserId);
        Timesheet timesheet = findTimesheet(timesheetId);
        if (!canAccess(actor, timesheet)) {
            throw new ResourceNotFoundException("Timesheet not found");
        }
        return toResponse(timesheet);
    }

    public TimesheetResponse create(String actorUserId, TimesheetUpsertRequest request) {
        return save(actorUserId, null, request);
    }

    public TimesheetResponse update(String actorUserId, String timesheetId, TimesheetUpsertRequest request) {
        return save(actorUserId, timesheetId, request);
    }

    public TimesheetResponse review(
            String actorUserId,
            String timesheetId,
            TimesheetReviewRequest request) {

        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor = requireActor(actorUserId);
        validateReviewRequest(request);

        Timesheet timesheet = findTimesheet(timesheetId);
        if (!canReview(actor, timesheet)) {
            throw new ResourceNotFoundException("Timesheet not found");
        }
        if (timesheet.getStatus() != TimesheetStatus.SUBMITTED) {
            throw new BusinessException("Only submitted timesheets can be reviewed");
        }

        LocalDateTime now = LocalDateTime.now();
        timesheet.setStatus(request.status());
        timesheet.setReviewedByUserId(actor.userId());
        timesheet.setReviewedAt(now);
        timesheet.setReviewComment(trimToNull(request.comment()));
        timesheet.setUpdatedAt(now);

        Timesheet reviewed = timesheetRepository.save(timesheet);
        logReviewAudit(actor, reviewed, request.status(), request.comment());
        notifyTimesheetReview(actor, reviewed, request.status(), request.comment());
        return toResponse(reviewed);
    }

    public TimesheetResponse getConsolidatedPeriodForUser(String actorUserId, LocalDate start, LocalDate end) {
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor = requireActor(actorUserId);
        if (start == null || end == null || start.isAfter(end)) {
            throw new BusinessException("Valid start and end dates are required for the pay period");
        }

        List<Timesheet> timesheets = timesheetRepository
                .findByOrganizationIdAndUserIdOrderBySubmittedAtDesc(actor.organizationId(), actor.userId())
                .stream()
                .filter(ts -> ts.getPeriodStartDate() != null && ts.getPeriodEndDate() != null)
                .filter(ts -> !start.isAfter(ts.getPeriodEndDate()) && !end.isBefore(ts.getPeriodStartDate()))
                .toList();

        Map<LocalDate, TimesheetEntry> consolidatedEntries = new LinkedHashMap<>();
        List<Timesheet> chronological = timesheets.stream()
                .sorted(Comparator.comparing(
                        (Timesheet ts) -> ts.getUpdatedAt() != null ? ts.getUpdatedAt() : (ts.getSubmittedAt() != null ? ts.getSubmittedAt() : ts.getCreatedAt()),
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        for (Timesheet ts : chronological) {
            if (ts.getEntries() != null) {
                for (TimesheetEntry entry : ts.getEntries()) {
                    if (entry.getWorkDate() != null && !entry.getWorkDate().isBefore(start) && !entry.getWorkDate().isAfter(end)) {
                        consolidatedEntries.put(entry.getWorkDate(), entry);
                    }
                }
            }
        }

        List<TimesheetEntry> entries = consolidatedEntries.values().stream()
                .sorted(Comparator.comparing(TimesheetEntry::getWorkDate))
                .toList();

        BigDecimal total = entries.stream()
                .map(TimesheetEntry::getHoursWorked)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        TimesheetStatus status = timesheets.stream().anyMatch(ts -> ts.getStatus() == TimesheetStatus.SUBMITTED)
                ? TimesheetStatus.SUBMITTED
                : (timesheets.stream().anyMatch(ts -> ts.getStatus() == TimesheetStatus.APPROVED)
                        ? TimesheetStatus.APPROVED
                        : (timesheets.isEmpty() ? TimesheetStatus.SUBMITTED : timesheets.get(0).getStatus()));

        Timesheet synthetic = Timesheet.builder()
                .id("period-" + start + "-" + end)
                .organizationId(actor.organizationId())
                .userId(actor.userId())
                .profileId(employeeProfileRepository.findByUserId(actor.userId()).map(EmployeeProfile::getId).orElse(null))
                .reportingManagerUserId(timesheets.isEmpty() ? null : timesheets.get(0).getReportingManagerUserId())
                .periodStartDate(start)
                .periodEndDate(end)
                .entries(entries)
                .totalHours(total)
                .status(status)
                .submittedAt(timesheets.isEmpty() ? LocalDateTime.now() : timesheets.get(0).getSubmittedAt())
                .build();

        return toResponse(synthetic);
    }

    private TimesheetResponse save(
            String actorUserId,
            String timesheetId,
            TimesheetUpsertRequest request) {

        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor = requireActor(actorUserId);
        if (!TIMESHEET_CREATORS.contains(actor.role())) {
            throw new ForbiddenException("Company Admin users can review timesheets but cannot submit one");
        }

        validateRequest(request);
        List<String> supportingDocumentIds = sanitizedDocumentIds(request.supportingDocumentIds());
        for (String documentId : supportingDocumentIds) {
            StoredDocument document = storedDocumentRepository.findById(documentId)
                    .orElseThrow(() -> new BusinessException("Supporting document not found"));
            if (!actor.organizationId().equals(document.getOrganizationId())
                    || !actor.userId().equals(document.getOwnerUserId())
                    || document.getDocumentType() != DocumentType.TIMESHEET_SUPPORTING_DOCUMENT) {
                throw new ForbiddenException("Attach a timesheet document uploaded by you in this organization");
            }
        }
        ReportingAssignment assignment = reportingAssignmentRepository
                .findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                        actor.userId(), RelationshipType.PRIMARY_REPORTING_MANAGER)
                .filter(candidate -> actor.organizationId().equals(candidate.getOrganizationId()))
                .orElseThrow(() -> new BusinessException(
                        "An active reporting manager must be assigned before saving a timesheet"));

        Timesheet timesheet = timesheetId == null
                ? findExistingPeriod(actor, request).orElseGet(Timesheet::new)
                : findTimesheet(timesheetId);

        if (timesheet.getId() != null && !actor.userId().equals(timesheet.getUserId())) {
            throw new ForbiddenException("You can only edit your own timesheets");
        }
        boolean isExisting = timesheet.getId() != null;
        boolean correctingSubmitted = isExisting
                && (timesheet.getStatus() == TimesheetStatus.SUBMITTED
                    || timesheet.getStatus() == TimesheetStatus.REJECTED
                    || timesheet.getStatus() == TimesheetStatus.APPROVED);
        TimesheetStatus previousStatus = timesheet.getStatus();
        BigDecimal previousTotalHours = timesheet.getTotalHours();

        if (correctingSubmitted) {
            if (!Boolean.TRUE.equals(request.confirmReedit())) {
                throw new BusinessException("Confirm re-editing this submitted timesheet before saving corrections");
            }
            if (trimToNull(request.changeReason()) == null) {
                throw new BusinessException("Reason for change is required when re-editing a submitted timesheet");
            }
        }

        LocalDateTime now = LocalDateTime.now();
        if (timesheet.getCreatedAt() == null) {
            timesheet.setCreatedAt(now);
        }
        timesheet.setOrganizationId(actor.organizationId());
        timesheet.setUserId(actor.userId());
        timesheet.setProfileId(employeeProfileRepository.findByUserId(actor.userId())
                .map(EmployeeProfile::getId)
                .orElse(null));
        timesheet.setReportingManagerUserId(assignment.getReportingManagerUserId());

        List<TimesheetEntry> submittedEntries = toEntries(request.entries());
        LocalDate effectiveStart = request.periodStartDate();
        LocalDate effectiveEnd = request.periodEndDate();
        List<TimesheetEntry> finalEntries = submittedEntries;

        if (correctingSubmitted && timesheet.getEntries() != null && !timesheet.getEntries().isEmpty()) {
            // Keep previously recorded days that fall outside the range being corrected so a
            // partial correction never erases untouched dates.
            List<TimesheetEntry> preserved = new ArrayList<>();
            for (TimesheetEntry existingEntry : timesheet.getEntries()) {
                LocalDate workDate = existingEntry.getWorkDate();
                if (workDate != null
                        && (workDate.isBefore(request.periodStartDate())
                            || workDate.isAfter(request.periodEndDate()))) {
                    preserved.add(existingEntry);
                }
            }
            if (!preserved.isEmpty()) {
                preserved.addAll(submittedEntries);
                preserved.sort(Comparator.comparing(TimesheetEntry::getWorkDate));
                finalEntries = preserved;
                LocalDate minDate = finalEntries.get(0).getWorkDate();
                LocalDate maxDate = finalEntries.get(finalEntries.size() - 1).getWorkDate();
                if (minDate != null && minDate.isBefore(effectiveStart)) {
                    effectiveStart = minDate;
                }
                if (maxDate != null && maxDate.isAfter(effectiveEnd)) {
                    effectiveEnd = maxDate;
                }
            }
        }

        timesheet.setPeriodStartDate(effectiveStart);
        timesheet.setPeriodEndDate(effectiveEnd);
        timesheet.setEntries(finalEntries);
        timesheet.setTotalHours(totalHours(finalEntries));
        timesheet.setSupportingDocumentIds(supportingDocumentIds);
        timesheet.setStatus(request.status());
        timesheet.setChangeReason(trimToNull(request.changeReason()));
        timesheet.setSubmittedAt(request.status() == TimesheetStatus.SUBMITTED ? now : null);
        timesheet.setReviewedByUserId(null);
        timesheet.setReviewedAt(null);
        timesheet.setReviewComment(null);
        timesheet.setUpdatedAt(now);

        Timesheet saved = timesheetRepository.save(timesheet);

        if (request.status() == TimesheetStatus.SUBMITTED || correctingSubmitted) {
            logTimesheetAudit(actor, saved, correctingSubmitted, previousStatus, previousTotalHours, request.changeReason());
        }
        if (request.status() == TimesheetStatus.SUBMITTED) {
            notifyTimesheetSubmission(actor, saved, assignment.getReportingManagerUserId(), correctingSubmitted);
        }

        return toResponse(saved);
    }

    private List<Timesheet> visibleRecords(
            MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor) {

        if (actor.role() == UserRole.COMPANY_ADMIN) {
            return timesheetRepository.findByOrganizationIdOrderByPeriodStartDateDesc(actor.organizationId())
                    .stream()
                    .filter(timesheet -> timesheet.getStatus() != TimesheetStatus.DRAFT)
                    .toList();
        }

        Map<String, Timesheet> records = new LinkedHashMap<>();
        timesheetRepository.findByOrganizationIdAndUserIdOrderBySubmittedAtDesc(
                        actor.organizationId(), actor.userId())
                .stream()
                .sorted(Comparator.comparing(
                        (Timesheet ts) -> ts.getUpdatedAt() != null ? ts.getUpdatedAt() : (ts.getSubmittedAt() != null ? ts.getSubmittedAt() : ts.getCreatedAt()),
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .forEach(timesheet -> records.put(timesheet.getId(), timesheet));

        if (actor.role() == UserRole.HR) {
            timesheetRepository.findByOrganizationIdAndReportingManagerUserIdOrderBySubmittedAtDesc(
                            actor.organizationId(), actor.userId())
                    .stream()
                    .filter(timesheet -> timesheet.getStatus() != TimesheetStatus.DRAFT)
                    .sorted(Comparator.comparing(
                            (Timesheet ts) -> ts.getUpdatedAt() != null ? ts.getUpdatedAt() : (ts.getSubmittedAt() != null ? ts.getSubmittedAt() : ts.getCreatedAt()),
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .forEach(timesheet -> records.put(timesheet.getId(), timesheet));
        }

        return new ArrayList<>(records.values());
    }

    private boolean canAccess(
            MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor,
            Timesheet timesheet) {

        if (!actor.organizationId().equals(timesheet.getOrganizationId())) {
            return false;
        }
        if (actor.userId().equals(timesheet.getUserId())) {
            return true;
        }
        if (timesheet.getStatus() == TimesheetStatus.DRAFT) {
            return false;
        }
        if (actor.role() == UserRole.COMPANY_ADMIN) {
            return true;
        }
        return actor.role() == UserRole.HR
                && activeDirectReportIds(actor).contains(timesheet.getUserId());
    }

    private Set<String> activeDirectReportIds(
            MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor) {
        List<ReportingAssignment> assignments = reportingAssignmentRepository
                .findByOrganizationIdAndReportingManagerUserIdAndRelationshipTypeAndIsActiveTrue(
                        actor.organizationId(), actor.userId(), RelationshipType.PRIMARY_REPORTING_MANAGER);
        if (assignments.isEmpty()) {
            assignments = reportingAssignmentRepository.findByReportingManagerUserId(actor.userId());
        }
        return assignments.stream()
                .filter(assignment -> Boolean.TRUE.equals(assignment.getIsActive()))
                .filter(assignment -> actor.organizationId().equals(assignment.getOrganizationId()))
                .map(ReportingAssignment::getEmployeeUserId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean canReview(
            MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor,
            Timesheet timesheet) {

        if (!TIMESHEET_REVIEWERS.contains(actor.role())
                || !actor.organizationId().equals(timesheet.getOrganizationId())) {
            return false;
        }
        if (actor.role() == UserRole.COMPANY_ADMIN) {
            return true;
        }
        return activeDirectReportIds(actor).contains(timesheet.getUserId());
    }

    private Optional<Timesheet> findExistingPeriod(
            MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor,
            TimesheetUpsertRequest request) {
        // Only ever reuse an in-progress DRAFT for a fresh submission. Filed timesheets
        // (SUBMITTED / APPROVED / REJECTED) are never overwritten here: each new
        // submission becomes its own record so previously recorded/approved days for
        // other dates are preserved, and per-day status is resolved when the period is
        // consolidated for display.
        List<Timesheet> userTimesheets = timesheetRepository
                .findByOrganizationIdAndUserIdOrderBySubmittedAtDesc(
                        actor.organizationId(), actor.userId())
                .stream()
                .filter(timesheet -> timesheet.getStatus() == TimesheetStatus.DRAFT)
                .toList();

        Optional<Timesheet> exact = userTimesheets.stream()
                .filter(timesheet -> request.periodStartDate().equals(timesheet.getPeriodStartDate())
                        && request.periodEndDate().equals(timesheet.getPeriodEndDate()))
                .findFirst();
        if (exact.isPresent()) {
            return exact;
        }

        return userTimesheets.stream()
                .filter(timesheet -> {
                    boolean periodOverlap = timesheet.getPeriodStartDate() != null
                            && timesheet.getPeriodEndDate() != null
                            && !request.periodStartDate().isAfter(timesheet.getPeriodEndDate())
                            && !request.periodEndDate().isBefore(timesheet.getPeriodStartDate());
                    boolean entryOverlap = timesheet.getEntries() != null && timesheet.getEntries().stream()
                            .anyMatch(entry -> entry.getWorkDate() != null
                                    && !entry.getWorkDate().isBefore(request.periodStartDate())
                                    && !entry.getWorkDate().isAfter(request.periodEndDate()));
                    return periodOverlap || entryOverlap;
                })
                .findFirst();
    }

    private MyHrAuthenticatedUserService.AuthenticatedMyHrUser requireActor(String actorUserId) {
        return authenticatedUserService.require(actorUserId, MY_HR_ROLES, false);
    }

    private Timesheet findTimesheet(String timesheetId) {
        return timesheetRepository.findById(timesheetId)
                .orElseThrow(() -> new ResourceNotFoundException("Timesheet not found"));
    }

    private List<String> sanitizedDocumentIds(List<String> rawIds) {
        if (rawIds == null || rawIds.isEmpty()) {
            return List.of();
        }
        Set<String> deduped = new LinkedHashSet<>();
        for (String rawId : rawIds) {
            String trimmed = trimToNull(rawId);
            if (trimmed == null) {
                continue;
            }
            if (trimmed.length() > 120) {
                throw new BusinessException("Supporting document ID cannot exceed 120 characters");
            }
            deduped.add(trimmed);
        }
        return List.copyOf(deduped);
    }

    private void validateRequest(TimesheetUpsertRequest request) {
        if (request == null) {
            throw new BusinessException("Timesheet request is required");
        }
        if (request.status() == null) {
            throw new BusinessException("Timesheet status is required");
        }
        if (request.status() != TimesheetStatus.DRAFT
                && request.status() != TimesheetStatus.SUBMITTED) {
            throw new BusinessException("Timesheets can only be saved as drafts or submitted");
        }
        if (request.periodStartDate() == null || request.periodEndDate() == null) {
            throw new BusinessException("Timesheet period is required");
        }
        if (request.periodStartDate().isAfter(request.periodEndDate())) {
            throw new BusinessException("Timesheet period start must be on or before the end date");
        }
        if (ChronoUnit.DAYS.between(request.periodStartDate(), request.periodEndDate()) >= 31) {
            throw new BusinessException("Timesheet periods cannot exceed 31 days");
        }
        if (request.entries() == null || request.entries().isEmpty()) {
            throw new BusinessException("At least one timesheet entry is required");
        }

        LocalDate today = LocalDate.now();

        Set<LocalDate> dates = new LinkedHashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (TimesheetEntryRequest entry : request.entries()) {
            if (entry == null || entry.workDate() == null || entry.hoursWorked() == null) {
                throw new BusinessException("Each timesheet entry requires a date and hours");
            }
            if (entry.workDate().isBefore(request.periodStartDate())
                    || entry.workDate().isAfter(request.periodEndDate())) {
                throw new BusinessException("Timesheet entries must fall within the selected period");
            }
            if (entry.workDate().isAfter(today)) {
                throw new BusinessException("Hours cannot be recorded for future dates");
            }
            if (!dates.add(entry.workDate())) {
                throw new BusinessException("A timesheet can contain only one entry per work date");
            }
            if (entry.hoursWorked().compareTo(BigDecimal.ZERO) < 0
                    || entry.hoursWorked().compareTo(BigDecimal.valueOf(24)) > 0) {
                throw new BusinessException("Hours worked must be between 0 and 24 per day");
            }
            total = total.add(entry.hoursWorked());
        }

        boolean hasMeaningfulZeroHourEntry = request.entries().stream()
                .filter(java.util.Objects::nonNull)
                .anyMatch(entry -> entry.hoursWorked() != null
                        && entry.hoursWorked().compareTo(BigDecimal.ZERO) == 0
                        && (entry.entryType() != null && entry.entryType() != TimesheetEntryType.WORK
                        || StringUtils.hasText(entry.description())));
        if (request.status() == TimesheetStatus.SUBMITTED
                && total.compareTo(BigDecimal.ZERO) <= 0
                && !hasMeaningfulZeroHourEntry) {
            throw new BusinessException("A submitted timesheet must contain at least one hour or an accounted non-work entry");
        }
    }

    private void validateReviewRequest(TimesheetReviewRequest request) {
        if (request == null || request.status() == null) {
            throw new BusinessException("Review status is required");
        }
        if (request.status() != TimesheetStatus.APPROVED
                && request.status() != TimesheetStatus.REJECTED) {
            throw new BusinessException("Review status must be APPROVED or REJECTED");
        }
        if (request.status() == TimesheetStatus.REJECTED
                && (request.comment() == null || request.comment().isBlank())) {
            throw new BusinessException("A reason for rejection is required when rejecting a timesheet");
        }
    }

    private List<TimesheetEntry> toEntries(List<TimesheetEntryRequest> entries) {
        return entries.stream()
                .sorted(Comparator.comparing(TimesheetEntryRequest::workDate))
                .map(entry -> TimesheetEntry.builder()
                        .workDate(entry.workDate())
                        .dayOfWeek(entry.workDate().getDayOfWeek().name())
                        .hoursWorked(entry.hoursWorked().setScale(2, RoundingMode.HALF_UP))
                        .entryType(entry.entryType() == null ? TimesheetEntryType.WORK : entry.entryType())
                        .description(trimToNull(entry.description()))
                        .build())
                .toList();
    }

    private BigDecimal totalHours(List<TimesheetEntry> entries) {
        return entries.stream()
                .map(TimesheetEntry::getHoursWorked)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private TimesheetResponse toResponse(Timesheet timesheet) {
        EmployeeProfile profile = timesheet.getUserId() == null
                ? null
                : employeeProfileRepository.findByUserId(timesheet.getUserId()).orElse(null);
        User employee = timesheet.getUserId() == null
                ? null
                : userRepository.findById(timesheet.getUserId()).orElse(null);
        User reportingManager = timesheet.getReportingManagerUserId() == null
                ? null
                : userRepository.findById(timesheet.getReportingManagerUserId()).orElse(null);
        EmployeeProfile reportingManagerProfile = timesheet.getReportingManagerUserId() == null
                ? null
                : employeeProfileRepository.findByUserId(timesheet.getReportingManagerUserId()).orElse(null);
        List<TimesheetSupportingDocumentResponse> documents = supportingDocuments(timesheet.getSupportingDocumentIds());

        return buildResponse(timesheet, profile, employee, reportingManager, reportingManagerProfile, documents);
    }

    /**
     * Resolves a whole list of timesheets in a handful of queries instead of one round trip
     * per row (profile + employee + manager + supporting document, each previously a separate
     * lookup) - the list views (organization/team review, "my timesheets") were doing up to
     * 4 sequential network calls per record against the shared Atlas cluster.
     */
    private List<TimesheetResponse> toResponses(List<Timesheet> timesheets) {
        if (timesheets.isEmpty()) {
            return List.of();
        }

        Set<String> userIds = new LinkedHashSet<>();
        Set<String> documentIds = new LinkedHashSet<>();
        for (Timesheet timesheet : timesheets) {
            if (timesheet.getUserId() != null) {
                userIds.add(timesheet.getUserId());
            }
            if (timesheet.getReportingManagerUserId() != null) {
                userIds.add(timesheet.getReportingManagerUserId());
            }
            if (timesheet.getSupportingDocumentIds() != null) {
                for (String documentId : timesheet.getSupportingDocumentIds()) {
                    if (StringUtils.hasText(documentId)) {
                        documentIds.add(documentId);
                    }
                }
            }
        }

        // Plain HashMaps built by hand rather than Collectors.toMap()/Map.of(): both choke on
        // the null keys/values that show up here in practice (a timesheet with no reporting
        // manager yet, a stored document with no file name) - HashMap tolerates them.
        Map<String, EmployeeProfile> profilesByUserId = new HashMap<>();
        for (EmployeeProfile profile : employeeProfileRepository.findByUserIdIn(userIds)) {
            profilesByUserId.putIfAbsent(profile.getUserId(), profile);
        }
        Map<String, User> usersById = new HashMap<>();
        for (User user : userRepository.findAllById(userIds)) {
            usersById.putIfAbsent(user.getId(), user);
        }
        Map<String, String> documentNamesById = new HashMap<>();
        if (!documentIds.isEmpty()) {
            for (StoredDocument document : storedDocumentRepository.findAllById(documentIds)) {
                documentNamesById.putIfAbsent(document.getId(), trimToNull(document.getFileName()));
            }
        }

        return timesheets.stream()
                .map(timesheet -> {
                    EmployeeProfile profile = profilesByUserId.get(timesheet.getUserId());
                    User employee = usersById.get(timesheet.getUserId());
                    User reportingManager = usersById.get(timesheet.getReportingManagerUserId());
                    EmployeeProfile reportingManagerProfile = profilesByUserId.get(timesheet.getReportingManagerUserId());
                    List<TimesheetSupportingDocumentResponse> documents = timesheet.getSupportingDocumentIds() == null
                            ? List.of()
                            : timesheet.getSupportingDocumentIds().stream()
                            .filter(StringUtils::hasText)
                            .map(documentId -> new TimesheetSupportingDocumentResponse(documentId, documentNamesById.get(documentId)))
                            .toList();
                    return buildResponse(timesheet, profile, employee, reportingManager, reportingManagerProfile, documents);
                })
                .toList();
    }

    private TimesheetResponse buildResponse(
            Timesheet timesheet,
            EmployeeProfile profile,
            User employee,
            User reportingManager,
            EmployeeProfile reportingManagerProfile,
            List<TimesheetSupportingDocumentResponse> documents) {

        String employeeName = resolveDisplayName(profile, employee, timesheet.getUserId());
        String reportingManagerName = resolveDisplayName(reportingManagerProfile, reportingManager,
                reportingManager != null ? reportingManager.getLoginId() : null);

        List<TimesheetEntryResponse> entries = timesheet.getEntries() == null
                ? List.of()
                : timesheet.getEntries().stream()
                .map(entry -> new TimesheetEntryResponse(
                        entry.getWorkDate(), entry.getDayOfWeek(), entry.getHoursWorked(),
                        entry.getEntryType() == null ? TimesheetEntryType.WORK : entry.getEntryType(),
                        entry.getDescription()))
                .toList();

        return new TimesheetResponse(
                timesheet.getId(),
                timesheet.getUserId(),
                profile == null ? null : profile.getEmployeeId(),
                employeeName,
                employee == null ? null : employee.getRole(),
                timesheet.getReportingManagerUserId(),
                reportingManager == null ? null : reportingManager.getLoginId(),
                reportingManagerName,
                timesheet.getPeriodStartDate(),
                timesheet.getPeriodEndDate(),
                entries,
                timesheet.getTotalHours(),
                documents,
                timesheet.getStatus(),
                timesheet.getSubmittedAt(),
                timesheet.getCreatedAt(),
                timesheet.getUpdatedAt(),
                timesheet.getReviewedByUserId(),
                timesheet.getReviewedAt(),
                timesheet.getReviewComment(),
                timesheet.getChangeReason());
    }

    private String resolveDisplayName(EmployeeProfile profile, User user, String fallback) {
        String name = profile == null ? null : trimToNull(profile.getName());
        if (name == null && profile != null) {
            String combined = ((trimToNull(profile.getFirstName()) == null ? "" : profile.getFirstName().trim())
                    + " "
                    + (trimToNull(profile.getLastName()) == null ? "" : profile.getLastName().trim())).trim();
            name = trimToNull(combined);
        }
        if (name == null && user != null) {
            name = trimToNull(user.getLoginId());
        }
        return name != null ? name : fallback;
    }

    private List<TimesheetSupportingDocumentResponse> supportingDocuments(List<String> supportingDocumentIds) {
        if (supportingDocumentIds == null || supportingDocumentIds.isEmpty()) {
            return List.of();
        }
        Map<String, String> namesById = new HashMap<>();
        for (StoredDocument document : storedDocumentRepository.findAllById(supportingDocumentIds)) {
            namesById.putIfAbsent(document.getId(), trimToNull(document.getFileName()));
        }
        return supportingDocumentIds.stream()
                .filter(StringUtils::hasText)
                .map(documentId -> new TimesheetSupportingDocumentResponse(documentId, namesById.get(documentId)))
                .toList();
    }

    private String resolveEmployeeName(Timesheet timesheet) {
        if (timesheet == null || timesheet.getUserId() == null) {
            return "Employee";
        }
        EmployeeProfile profile = employeeProfileRepository.findByUserId(timesheet.getUserId()).orElse(null);
        String employeeName = profile == null ? null : trimToNull(profile.getName());
        if (employeeName == null && profile != null) {
            employeeName = ((trimToNull(profile.getFirstName()) == null ? "" : profile.getFirstName().trim())
                    + " "
                    + (trimToNull(profile.getLastName()) == null ? "" : profile.getLastName().trim())).trim();
        }
        if (employeeName == null || employeeName.isBlank()) {
            User employee = userRepository.findById(timesheet.getUserId()).orElse(null);
            if (employee != null) {
                employeeName = trimToNull(employee.getLoginId());
            }
        }
        if (employeeName == null || employeeName.isBlank()) {
            employeeName = timesheet.getUserId();
        }
        return employeeName;
    }

    private void logTimesheetAudit(
            MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor,
            Timesheet timesheet,
            boolean correctingSubmitted,
            TimesheetStatus previousStatus,
            BigDecimal previousTotalHours,
            String changeReason) {

        AuditActionType action = correctingSubmitted
                ? AuditActionType.TIMESHEET_UPDATED
                : AuditActionType.TIMESHEET_SUBMITTED;

        Map<String, Object> previousValue = new LinkedHashMap<>();
        if (correctingSubmitted) {
            if (previousStatus != null) previousValue.put("status", previousStatus.name());
            if (previousTotalHours != null) previousValue.put("totalHours", previousTotalHours);
        }

        Map<String, Object> updatedValue = new LinkedHashMap<>();
        if (timesheet.getStatus() != null) updatedValue.put("status", timesheet.getStatus().name());
        if (timesheet.getTotalHours() != null) updatedValue.put("totalHours", timesheet.getTotalHours());
        if (timesheet.getPeriodStartDate() != null) updatedValue.put("periodStartDate", timesheet.getPeriodStartDate().toString());
        if (timesheet.getPeriodEndDate() != null) updatedValue.put("periodEndDate", timesheet.getPeriodEndDate().toString());
        if (changeReason != null && !changeReason.isBlank()) {
            updatedValue.put("changeReason", changeReason.trim());
        }

        String description = correctingSubmitted
                ? String.format("Timesheet updated for period %s to %s%s",
                        timesheet.getPeriodStartDate(),
                        timesheet.getPeriodEndDate(),
                        changeReason != null && !changeReason.isBlank() ? " with reason: " + changeReason.trim() : "")
                : String.format("Timesheet submitted for period %s to %s with %s hours",
                        timesheet.getPeriodStartDate(),
                        timesheet.getPeriodEndDate(),
                        timesheet.getTotalHours());

        LocalDateTime now = LocalDateTime.now();
        AuditLog auditLog = AuditLog.builder()
                .organizationId(actor.organizationId())
                .performedByUserId(actor.userId())
                .actorLoginId(actor.loginId())
                .actorName(resolveEmployeeName(timesheet))
                .performedByRole(actor.role() == null ? null : actor.role().name())
                .actionType(action)
                .entityType("TIMESHEET")
                .entityId(timesheet.getId())
                .module("MY_HR")
                .eventCategory("TIMESHEETS")
                .entityDisplayName(timesheet.getPeriodStartDate() + " to " + timesheet.getPeriodEndDate())
                .previousValue(previousValue.isEmpty() ? null : previousValue)
                .updatedValue(updatedValue)
                .description(description)
                .outcome("SUCCESS")
                .source("MY_HR_TIMESHEETS")
                .performedAt(now)
                .createdAt(now)
                .build();

        auditLogRepository.save(auditLog);
    }

    private void logReviewAudit(
            MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor,
            Timesheet timesheet,
            TimesheetStatus newStatus,
            String comment) {

        LocalDateTime now = LocalDateTime.now();
        AuditLog auditLog = AuditLog.builder()
                .organizationId(actor.organizationId())
                .performedByUserId(actor.userId())
                .actorLoginId(actor.loginId())
                .performedByRole(actor.role() == null ? null : actor.role().name())
                .actionType(AuditActionType.TIMESHEET_UPDATED)
                .entityType("TIMESHEET")
                .entityId(timesheet.getId())
                .module("MY_HR")
                .eventCategory("TIMESHEETS")
                .entityDisplayName(timesheet.getPeriodStartDate() + " to " + timesheet.getPeriodEndDate())
                .description("Timesheet " + newStatus + (comment != null && !comment.isBlank() ? " with note: " + comment.trim() : ""))
                .outcome("SUCCESS")
                .source("MY_HR_TIMESHEETS")
                .performedAt(now)
                .createdAt(now)
                .build();

        auditLogRepository.save(auditLog);
    }

    private void notifyTimesheetSubmission(
            MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor,
            Timesheet timesheet,
            String reportingManagerUserId,
            boolean isResubmission) {

        String periodLabel = timesheet.getPeriodStartDate() + " to " + timesheet.getPeriodEndDate();

        if (StringUtils.hasText(reportingManagerUserId)) {
            inAppNotificationService.createNotification(
                    actor.organizationId(),
                    reportingManagerUserId,
                    NotificationType.TIMESHEET_SUBMITTED,
                    isResubmission ? "Timesheet resubmitted" : "New timesheet submitted",
                    actor.loginId() + (isResubmission ? " resubmitted a timesheet for " : " submitted a timesheet for ")
                            + periodLabel + " (" + timesheet.getTotalHours() + "h) for your review.",
                    timesheet.getId(),
                    "/myhr/hr/timesheets?view=team&timesheetId=" + timesheet.getId());
        }

        // Notify active Company Admins so organization review queue reflects the submission
        userRepository.findByOrganizationIdAndRoleIn(actor.organizationId(), List.of(UserRole.COMPANY_ADMIN)).stream()
                .filter(admin -> admin.getAccountStatus() == AccountStatus.ACTIVE)
                .filter(admin -> !admin.getId().equals(reportingManagerUserId))
                .forEach(admin -> inAppNotificationService.createNotification(
                        actor.organizationId(),
                        admin.getId(),
                        NotificationType.TIMESHEET_SUBMITTED,
                        isResubmission ? "Timesheet resubmitted" : "New timesheet submitted",
                        actor.loginId() + (isResubmission ? " resubmitted a timesheet for " : " submitted a timesheet for ")
                                + periodLabel + " (" + timesheet.getTotalHours() + "h) for organization review.",
                        timesheet.getId(),
                        "/myhr/admin/timesheets?timesheetId=" + timesheet.getId()));

        inAppNotificationService.createNotification(
                actor.organizationId(),
                actor.userId(),
                NotificationType.TIMESHEET_SUBMITTED,
                isResubmission ? "Timesheet resubmitted" : "Timesheet submitted",
                "Your timesheet for " + periodLabel + " has been "
                        + (isResubmission ? "resubmitted" : "submitted") + " to your reporting manager.",
                timesheet.getId(),
                ownTimesheetHistoryUrl(actor.role(), timesheet.getId(), false));
    }

    /**
     * The recipient of a "your own timesheet ..." notification may be an EMPLOYEE
     * or an HR user (both can file timesheets); route to the persona-correct page
     * so the role guard does not bounce them back to the dashboard.
     */
    private String ownTimesheetHistoryUrl(UserRole recipientRole, String timesheetId, boolean rejected) {
        String base = recipientRole == UserRole.HR
                ? "/myhr/hr/timesheets?view=mine&mode=history"
                : "/myhr/employee/timesheets?mode=history";
        return base + (rejected ? "&rejected=true" : "") + "&timesheetId=" + timesheetId;
    }

    private UserRole roleOf(String userId) {
        return userRepository.findById(userId).map(User::getRole).orElse(UserRole.EMPLOYEE);
    }

    private void notifyTimesheetReview(
            MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor,
            Timesheet timesheet,
            TimesheetStatus status,
            String comment) {

        String periodLabel = timesheet.getPeriodStartDate() + " to " + timesheet.getPeriodEndDate();
        if (status == TimesheetStatus.APPROVED) {
            inAppNotificationService.createNotification(
                    timesheet.getOrganizationId(),
                    timesheet.getUserId(),
                    NotificationType.TIMESHEET_APPROVED,
                    "Timesheet approved",
                    "Your timesheet for " + periodLabel + " (" + timesheet.getTotalHours() + "h) has been approved by " + actor.loginId() + ".",
                    timesheet.getId(),
                    ownTimesheetHistoryUrl(roleOf(timesheet.getUserId()), timesheet.getId(), false));

            if (StringUtils.hasText(timesheet.getReportingManagerUserId()) && !actor.userId().equals(timesheet.getReportingManagerUserId())) {
                inAppNotificationService.createNotification(
                        timesheet.getOrganizationId(),
                        timesheet.getReportingManagerUserId(),
                        NotificationType.TIMESHEET_APPROVED,
                        "Timesheet approved by " + actor.loginId(),
                        "Timesheet for " + resolveEmployeeName(timesheet) + " (" + periodLabel + ") was approved by " + actor.loginId() + ".",
                        timesheet.getId(),
                        "/myhr/hr/timesheets?view=team&timesheetId=" + timesheet.getId());
            }
        } else if (status == TimesheetStatus.REJECTED) {
            String note = StringUtils.hasText(comment) ? comment.trim() : "No reason provided";
            inAppNotificationService.createNotification(
                    timesheet.getOrganizationId(),
                    timesheet.getUserId(),
                    NotificationType.TIMESHEET_REJECTED,
                    "Timesheet rejected",
                    "Your timesheet for " + periodLabel + " was rejected by " + actor.loginId() + ". Reason: " + note + ". Please revise and resubmit.",
                    timesheet.getId(),
                    ownTimesheetHistoryUrl(roleOf(timesheet.getUserId()), timesheet.getId(), true));

            if (StringUtils.hasText(timesheet.getReportingManagerUserId()) && !actor.userId().equals(timesheet.getReportingManagerUserId())) {
                inAppNotificationService.createNotification(
                        timesheet.getOrganizationId(),
                        timesheet.getReportingManagerUserId(),
                        NotificationType.TIMESHEET_REJECTED,
                        "Timesheet rejected by " + actor.loginId(),
                        "Timesheet for " + resolveEmployeeName(timesheet) + " (" + periodLabel + ") was rejected by " + actor.loginId() + ". Reason: " + note,
                        timesheet.getId(),
                        "/myhr/hr/timesheets?view=team&timesheetId=" + timesheet.getId());
            }
        }
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
