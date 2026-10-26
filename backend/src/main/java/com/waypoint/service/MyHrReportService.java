package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrEmployeeHoursReportResponse;
import com.waypoint.dto.myhr.MyHrReportEmployeeRow;
import com.waypoint.dto.myhr.MyHrReportManagerRow;
import com.waypoint.dto.myhr.MyHrReportSearchResult;
import com.waypoint.dto.myhr.MyHrTimesheetEntryRow;
import com.waypoint.dto.myhr.MyHrTimesheetReportAttentionItem;
import com.waypoint.dto.myhr.MyHrTimesheetReportResponse;
import com.waypoint.dto.myhr.MyHrTimesheetReportRow;
import com.waypoint.dto.myhr.MyHrTimesheetReportSummary;
import com.waypoint.dto.myhr.MyHrWorkforceReportResponse;
import com.waypoint.entity.EmployeeProfile;
import com.waypoint.entity.ReportingAssignment;
import com.waypoint.entity.Timesheet;
import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.RelationshipType;
import com.waypoint.enums.TimesheetStatus;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.repository.EmployeeProfileRepository;
import com.waypoint.repository.ReportingAssignmentRepository;
import com.waypoint.repository.TimesheetRepository;
import com.waypoint.repository.UserRepository;
import com.waypoint.security.MyHrAuthenticatedUserService.AuthenticatedMyHrUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/** Builds the My HR workforce report from live data only — no fabricated placeholders. */
@Service
@RequiredArgsConstructor
public class MyHrReportService {

    private static final RelationshipType PRIMARY_MANAGER = RelationshipType.PRIMARY_REPORTING_MANAGER;

    /** Hours only count once a manager has actually accepted them. */
    private static final Set<TimesheetStatus> HOUR_BEARING_STATUSES =
            Set.of(TimesheetStatus.SUBMITTED, TimesheetStatus.APPROVED);

    private final EmployeeProfileRepository employeeProfileRepository;
    private final TimesheetRepository timesheetRepository;
    private final ReportingAssignmentRepository reportingAssignmentRepository;
    private final UserRepository userRepository;
    private final MongoTemplate mongoTemplate;

    public MyHrWorkforceReportResponse getWorkforceReport(AuthenticatedMyHrUser actor, int periodDays) {
        String organizationId = actor.organizationId();
        boolean isOrgWide = actor.role() == UserRole.COMPANY_ADMIN;

        List<EmployeeProfile> orgProfiles = employeeProfileRepository.findByOrganizationId(organizationId);
        Map<String, EmployeeProfile> profileByUserId = orgProfiles.stream()
                .collect(Collectors.toMap(EmployeeProfile::getUserId, profile -> profile, (a, b) -> a));

        List<ReportingAssignment> activeAssignments = reportingAssignmentRepository
                .findByOrganizationIdAndRelationshipTypeAndIsActiveTrue(organizationId, PRIMARY_MANAGER);
        Map<String, String> managerUserIdByEmployeeUserId = activeAssignments.stream()
                .collect(Collectors.toMap(ReportingAssignment::getEmployeeUserId,
                        ReportingAssignment::getReportingManagerUserId, (a, b) -> a));

        List<EmployeeProfile> scopedProfiles = isOrgWide
                ? orgProfiles
                : orgProfiles.stream()
                        .filter(profile -> actor.userId().equals(managerUserIdByEmployeeUserId.get(profile.getUserId())))
                        .toList();

        Set<String> userIdsToLoad = new HashSet<>();
        scopedProfiles.forEach(profile -> userIdsToLoad.add(profile.getUserId()));
        activeAssignments.forEach(assignment -> userIdsToLoad.add(assignment.getReportingManagerUserId()));
        Map<String, User> userByUserId = userIdsToLoad.isEmpty()
                ? Map.of()
                : StreamSupport.stream(userRepository.findAllById(userIdsToLoad).spliterator(), false)
                        .collect(Collectors.toMap(User::getId, user -> user));

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime periodStart = now.minusDays(periodDays);

        List<Timesheet> periodTimesheets = isOrgWide
                ? timesheetRepository.findByOrganizationIdAndSubmittedAtBetweenOrderBySubmittedAtDesc(
                        organizationId, periodStart, now)
                : timesheetRepository.findByOrganizationIdAndReportingManagerUserIdAndSubmittedAtBetweenOrderBySubmittedAtDesc(
                        organizationId, actor.userId(), periodStart, now);
        Map<String, List<Timesheet>> timesheetsByUserId =
                periodTimesheets.stream().collect(Collectors.groupingBy(Timesheet::getUserId));

        List<MyHrReportEmployeeRow> employeeRows = scopedProfiles.stream()
                .map(profile -> toEmployeeRow(
                        profile,
                        userByUserId.get(profile.getUserId()),
                        managerUserIdByEmployeeUserId.get(profile.getUserId()),
                        profileByUserId,
                        userByUserId,
                        timesheetsByUserId.getOrDefault(profile.getUserId(), List.of())))
                .sorted(Comparator.comparing(
                        MyHrReportEmployeeRow::name, Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();

        long activeCount = employeeRows.stream()
                .filter(row -> row.accountStatus() == AccountStatus.ACTIVE)
                .count();
        long inactiveCount = employeeRows.size() - activeCount;

        List<MyHrReportManagerRow> managerSummary = isOrgWide
                ? buildManagerSummary(activeAssignments, profileByUserId, userByUserId)
                : List.of();

        return new MyHrWorkforceReportResponse(
                isOrgWide ? "ORGANIZATION" : "DIRECT_REPORTS",
                periodDays,
                employeeRows.size(),
                activeCount,
                inactiveCount,
                managerSummary.size(),
                employeeRows,
                managerSummary);
    }

    public MyHrEmployeeHoursReportResponse getEmployeeHoursReport(
            AuthenticatedMyHrUser actor, String employeeId, LocalDate startDate, LocalDate endDate) {

        if (endDate.isBefore(startDate)) {
            throw new BusinessException("End date must be on or after start date");
        }

        EmployeeProfile profile = employeeProfileRepository
                .findByOrganizationIdAndEmployeeId(actor.organizationId(), employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found"));

        if (actor.role() != UserRole.COMPANY_ADMIN) {
            boolean isOwnDirectReport = reportingAssignmentRepository
                    .findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(profile.getUserId(), PRIMARY_MANAGER)
                    .map(assignment -> actor.userId().equals(assignment.getReportingManagerUserId()))
                    .orElse(false);
            if (!isOwnDirectReport) {
                throw new ForbiddenException("You can only view timesheets for your direct reports");
            }
        }

        List<Timesheet> timesheets = timesheetRepository
                .findByOrganizationIdAndUserIdAndPeriodStartDateLessThanEqualAndPeriodEndDateGreaterThanEqualOrderByPeriodStartDateDesc(
                        actor.organizationId(), profile.getUserId(), endDate, startDate);

        BigDecimal totalHours = timesheets.stream()
                .filter(timesheet -> HOUR_BEARING_STATUSES.contains(timesheet.getStatus()))
                .map(Timesheet::getTotalHours)
                .filter(Objects::nonNull)
                .reduce(BigDecimal::add)
                .orElse(BigDecimal.ZERO);

        List<MyHrTimesheetEntryRow> timesheetRows = timesheets.stream()
                .map(timesheet -> new MyHrTimesheetEntryRow(
                        timesheet.getPeriodStartDate(),
                        timesheet.getPeriodEndDate(),
                        timesheet.getTotalHours(),
                        timesheet.getStatus(),
                        HOUR_BEARING_STATUSES.contains(timesheet.getStatus())))
                .toList();

        return new MyHrEmployeeHoursReportResponse(
                profile.getEmployeeId(), profile.getName(), startDate, endDate, totalHours, timesheetRows);
    }

    public List<MyHrReportSearchResult> searchEmployees(
            AuthenticatedMyHrUser actor,
            String query,
            boolean managerOnly,
            int limit) {

        Set<String> accessibleUserIds = accessibleEmployeeUserIds(actor);
        if (accessibleUserIds.isEmpty()) {
            return List.of();
        }

        Set<String> managerUserIds = Set.of();
        if (managerOnly) {
            managerUserIds = reportingAssignmentRepository
                    .findByOrganizationIdAndRelationshipTypeAndIsActiveTrue(actor.organizationId(), PRIMARY_MANAGER)
                    .stream()
                    .map(ReportingAssignment::getReportingManagerUserId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            accessibleUserIds = accessibleUserIds.stream()
                    .filter(managerUserIds::contains)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        if (accessibleUserIds.isEmpty()) {
            return List.of();
        }

        Criteria criteria = Criteria.where("organizationId").is(actor.organizationId())
                .and("userId").in(accessibleUserIds);
        if (StringUtils.hasText(query)) {
            String escaped = java.util.regex.Pattern.quote(query.trim());
            criteria = new Criteria().andOperator(criteria, new Criteria().orOperator(
                    Criteria.where("name").regex(escaped, "i"),
                    Criteria.where("firstName").regex(escaped, "i"),
                    Criteria.where("lastName").regex(escaped, "i"),
                    Criteria.where("employeeId").regex(escaped, "i")));
        }

        List<EmployeeProfile> profiles = mongoTemplate.find(
                Query.query(criteria).with(Sort.by(Sort.Direction.ASC, "name")).limit(limit),
                EmployeeProfile.class);
        Map<String, User> usersById = userRepository.findByIdIn(
                        profiles.stream().map(EmployeeProfile::getUserId).toList())
                .stream()
                .collect(Collectors.toMap(User::getId, user -> user));
        Map<String, String> managerNameByEmployee = managerNameByEmployee(actor.organizationId());

        return profiles.stream()
                .map(profile -> new MyHrReportSearchResult(
                        profile.getUserId(),
                        profile.getEmployeeId(),
                        profile.getName(),
                        Optional.ofNullable(usersById.get(profile.getUserId())).map(User::getRole).orElse(null),
                        profile.getDesignation(),
                        managerNameByEmployee.get(profile.getUserId())))
                .toList();
    }

    public MyHrTimesheetReportResponse getTimesheetReport(
            AuthenticatedMyHrUser actor,
            String scope,
            String scopeId,
            LocalDate startDate,
            LocalDate endDate,
            String search,
            String status,
            String issue,
            String sort,
            int page,
            int size) {

        if (startDate == null || endDate == null || endDate.isBefore(startDate)) {
            throw new BusinessException("Valid report start and end dates are required");
        }

        ScopeResolution scopeResolution = resolveScope(actor, scope, scopeId);
        Criteria baseCriteria = Criteria.where("organizationId").is(actor.organizationId())
                .and("periodStartDate").lte(endDate)
                .and("periodEndDate").gte(startDate)
                .and("entries.workDate").gte(startDate).lte(endDate);
        if (!scopeResolution.userIds().isEmpty()) {
            baseCriteria = baseCriteria.and("userId").in(scopeResolution.userIds());
        } else if (!scopeResolution.unboundedOrganization()) {
            return emptyTimesheetReport(page, size, actor);
        }

        TimesheetStatus parsedStatus = parseStatus(status);
        if (parsedStatus != null) {
            baseCriteria = baseCriteria.and("status").is(parsedStatus);
        }

        Set<String> searchedUserIds = matchingUserIds(actor.organizationId(), scopeResolution, search);
        if (StringUtils.hasText(search) && searchedUserIds.isEmpty()) {
            return emptyTimesheetReport(page, size, actor);
        }
        if (!searchedUserIds.isEmpty()) {
            baseCriteria = baseCriteria.and("userId").in(searchedUserIds);
        }

        Criteria issueCriteria = issueCriteria(issue);
        Criteria effectiveCriteria = issueCriteria == null
                ? baseCriteria
                : new Criteria().andOperator(baseCriteria, issueCriteria);

        Query countQuery = Query.query(effectiveCriteria);
        long total = mongoTemplate.count(countQuery, Timesheet.class);

        Query pageQuery = Query.query(effectiveCriteria)
                .with(toSort(sort))
                .skip((long) page * size)
                .limit(size);
        List<Timesheet> timesheets = mongoTemplate.find(pageQuery, Timesheet.class);
        List<MyHrTimesheetReportRow> rows = toReportRows(actor.organizationId(), timesheets, startDate, endDate);
        MyHrTimesheetReportSummary summary = summarize(actor.organizationId(), baseCriteria, startDate, endDate);
        List<MyHrTimesheetReportAttentionItem> attention = attentionItems(baseCriteria);

        int totalPages = size <= 0 ? 0 : (int) Math.ceil((double) total / size);
        return new MyHrTimesheetReportResponse(
                rows,
                page,
                size,
                total,
                totalPages,
                summary,
                attention,
                supportedScopes(actor),
                List.of("DEPARTMENT", "TEAM"));
    }

    private MyHrReportEmployeeRow toEmployeeRow(
            EmployeeProfile profile,
            User user,
            String managerUserId,
            Map<String, EmployeeProfile> profileByUserId,
            Map<String, User> userByUserId,
            List<Timesheet> timesheetsInPeriod) {

        String reportingManagerName = managerUserId == null
                ? null
                : resolveDisplayName(managerUserId, profileByUserId, userByUserId);

        // Timesheets arrive sorted most-recent-first, so the first entry is the
        // current state of the world — never prefer an older "good news" status.
        TimesheetStatus timesheetStatus = timesheetsInPeriod.isEmpty()
                ? null
                : timesheetsInPeriod.get(0).getStatus();

        BigDecimal totalHours = timesheetsInPeriod.stream()
                .filter(timesheet -> HOUR_BEARING_STATUSES.contains(timesheet.getStatus()))
                .map(Timesheet::getTotalHours)
                .filter(Objects::nonNull)
                .reduce(BigDecimal::add)
                .orElse(null);

        return new MyHrReportEmployeeRow(
                profile.getEmployeeId(),
                profile.getName(),
                profile.getDesignation(),
                profile.getEmploymentType(),
                user != null ? user.getAccountStatus() : null,
                reportingManagerName,
                totalHours,
                timesheetStatus);
    }

    private MyHrTimesheetReportResponse emptyTimesheetReport(int page, int size, AuthenticatedMyHrUser actor) {
        return new MyHrTimesheetReportResponse(
                List.of(),
                page,
                size,
                0,
                0,
                new MyHrTimesheetReportSummary(0, 0, BigDecimal.ZERO, 0, 0, 0, 0, 0),
                List.of(),
                supportedScopes(actor),
                List.of("DEPARTMENT", "TEAM"));
    }

    private Set<String> accessibleEmployeeUserIds(AuthenticatedMyHrUser actor) {
        if (actor.role() == UserRole.COMPANY_ADMIN) {
            return employeeProfileRepository.findByOrganizationId(actor.organizationId())
                    .stream()
                    .map(EmployeeProfile::getUserId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        Set<String> ids = new LinkedHashSet<>();
        employeeProfileRepository.findByUserId(actor.userId())
                .filter(profile -> actor.organizationId().equals(profile.getOrganizationId()))
                .map(EmployeeProfile::getUserId)
                .ifPresent(ids::add);
        reportingAssignmentRepository
                .findByOrganizationIdAndReportingManagerUserIdAndRelationshipTypeAndIsActiveTrue(
                        actor.organizationId(), actor.userId(), PRIMARY_MANAGER)
                .stream()
                .map(ReportingAssignment::getEmployeeUserId)
                .filter(Objects::nonNull)
                .forEach(ids::add);
        return ids;
    }

    private ScopeResolution resolveScope(AuthenticatedMyHrUser actor, String rawScope, String scopeId) {
        String scope = StringUtils.hasText(rawScope) ? rawScope.trim().toUpperCase() : "ACCESSIBLE";
        if ("ORGANIZATION".equals(scope)) {
            if (actor.role() != UserRole.COMPANY_ADMIN) {
                throw new ForbiddenException("Organization-wide reports are available to Company Admin users only");
            }
            return new ScopeResolution(Set.of(), true);
        }
        if ("REPORTING_MANAGER".equals(scope)) {
            String managerUserId = StringUtils.hasText(scopeId) ? scopeId.trim() : actor.userId();
            if (actor.role() != UserRole.COMPANY_ADMIN && !actor.userId().equals(managerUserId)) {
                throw new ForbiddenException("You can only report on your own reporting scope");
            }
            Set<String> directReports = reportingAssignmentRepository
                    .findByOrganizationIdAndReportingManagerUserIdAndRelationshipTypeAndIsActiveTrue(
                            actor.organizationId(), managerUserId, PRIMARY_MANAGER)
                    .stream()
                    .map(ReportingAssignment::getEmployeeUserId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            employeeProfileRepository.findByUserId(managerUserId)
                    .filter(profile -> actor.organizationId().equals(profile.getOrganizationId()))
                    .map(EmployeeProfile::getUserId)
                    .ifPresent(directReports::add);
            return new ScopeResolution(directReports, false);
        }
        if ("EMPLOYEE".equals(scope)) {
            if (!StringUtils.hasText(scopeId)) {
                throw new BusinessException("Select an employee for individual employee reports");
            }
            EmployeeProfile profile = employeeProfileRepository.findByUserId(scopeId.trim())
                    .filter(candidate -> actor.organizationId().equals(candidate.getOrganizationId()))
                    .orElseGet(() -> employeeProfileRepository
                            .findByOrganizationIdAndEmployeeId(actor.organizationId(), scopeId.trim())
                            .orElseThrow(() -> new ResourceNotFoundException("Employee not found")));
            if (actor.role() != UserRole.COMPANY_ADMIN && !accessibleEmployeeUserIds(actor).contains(profile.getUserId())) {
                throw new ForbiddenException("You can only report on yourself or your direct reports");
            }
            return new ScopeResolution(Set.of(profile.getUserId()), false);
        }
        return new ScopeResolution(accessibleEmployeeUserIds(actor), false);
    }

    private Set<String> matchingUserIds(String organizationId, ScopeResolution scope, String search) {
        if (!StringUtils.hasText(search)) {
            return Set.of();
        }
        Criteria criteria = Criteria.where("organizationId").is(organizationId);
        if (!scope.userIds().isEmpty()) {
            criteria = criteria.and("userId").in(scope.userIds());
        }
        String escaped = java.util.regex.Pattern.quote(search.trim());
        criteria = new Criteria().andOperator(criteria, new Criteria().orOperator(
                Criteria.where("name").regex(escaped, "i"),
                Criteria.where("firstName").regex(escaped, "i"),
                Criteria.where("lastName").regex(escaped, "i"),
                Criteria.where("employeeId").regex(escaped, "i")));
        return mongoTemplate.find(Query.query(criteria), EmployeeProfile.class)
                .stream()
                .map(EmployeeProfile::getUserId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private TimesheetStatus parseStatus(String value) {
        if (!StringUtils.hasText(value) || "ALL".equalsIgnoreCase(value)) {
            return null;
        }
        try {
            return TimesheetStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("Unsupported timesheet status filter");
        }
    }

    private Criteria issueCriteria(String issue) {
        if (!StringUtils.hasText(issue) || "ALL".equalsIgnoreCase(issue)) {
            return null;
        }
        String normalized = issue.trim().toUpperCase();
        if ("REJECTED".equals(normalized)) {
            return Criteria.where("status").is(TimesheetStatus.REJECTED);
        }
        if ("SUBMITTED_CORRECTION".equals(normalized)) {
            return Criteria.where("changeReason").ne(null);
        }
        if ("ZERO_RECORDED_HOURS".equals(normalized)) {
            return Criteria.where("totalHours").is(BigDecimal.ZERO);
        }
        if ("PENDING_APPROVAL_3_DAYS".equals(normalized)) {
            return new Criteria().andOperator(
                    Criteria.where("status").is(TimesheetStatus.SUBMITTED),
                    Criteria.where("submittedAt").lte(LocalDateTime.now().minusDays(3)));
        }
        throw new BusinessException("Unsupported attention filter");
    }

    private Sort toSort(String sort) {
        String normalized = StringUtils.hasText(sort) ? sort.trim().toUpperCase() : "PERIOD_DESC";
        return switch (normalized) {
            case "PERIOD_ASC" -> Sort.by(Sort.Direction.ASC, "periodStartDate");
            case "SUBMITTED_DESC" -> Sort.by(Sort.Direction.DESC, "submittedAt");
            case "SUBMITTED_ASC" -> Sort.by(Sort.Direction.ASC, "submittedAt");
            case "EMPLOYEE_NAME" -> Sort.by(Sort.Direction.ASC, "userId");
            case "HOURS_DESC" -> Sort.by(Sort.Direction.DESC, "totalHours");
            case "HOURS_ASC" -> Sort.by(Sort.Direction.ASC, "totalHours");
            default -> Sort.by(Sort.Direction.DESC, "periodStartDate").and(Sort.by(Sort.Direction.DESC, "submittedAt"));
        };
    }

    private List<MyHrTimesheetReportRow> toReportRows(String organizationId, List<Timesheet> timesheets, LocalDate reportStart, LocalDate reportEnd) {
        Set<String> employeeUserIds = timesheets.stream()
                .map(Timesheet::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> managerUserIds = timesheets.stream()
                .map(Timesheet::getReportingManagerUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> allUserIds = new LinkedHashSet<>();
        allUserIds.addAll(employeeUserIds);
        allUserIds.addAll(managerUserIds);

        Map<String, EmployeeProfile> profileByUserId = allUserIds.isEmpty()
                ? Map.of()
                : employeeProfileRepository.findByUserIdIn(allUserIds).stream()
                        .filter(profile -> organizationId.equals(profile.getOrganizationId()))
                        .collect(Collectors.toMap(EmployeeProfile::getUserId, profile -> profile, (a, b) -> a));
        Map<String, User> userByUserId = allUserIds.isEmpty()
                ? Map.of()
                : userRepository.findByIdIn(allUserIds).stream()
                        .collect(Collectors.toMap(User::getId, user -> user, (a, b) -> a));

        return timesheets.stream()
                .map(timesheet -> {
                    EmployeeProfile profile = profileByUserId.get(timesheet.getUserId());
                    User employeeUser = userByUserId.get(timesheet.getUserId());
                    String managerName = timesheet.getReportingManagerUserId() == null
                            ? null
                            : resolveDisplayName(timesheet.getReportingManagerUserId(), profileByUserId, userByUserId);
                    return new MyHrTimesheetReportRow(
                            timesheet.getId(),
                            timesheet.getUserId(),
                            profile != null ? profile.getEmployeeId() : null,
                            profile != null ? profile.getName() : null,
                            employeeUser != null ? employeeUser.getRole() : null,
                            profile != null ? profile.getDesignation() : null,
                            timesheet.getReportingManagerUserId(),
                            managerName,
                            reportStart,
                            reportEnd,
                            hoursInsideReportRange(timesheet, reportStart, reportEnd),
                            timesheet.getStatus(),
                            detectIssues(timesheet),
                            timesheet.getSubmittedAt(),
                            timesheet.getReviewedAt(),
                            timesheet.getReviewComment(),
                            timesheet.getChangeReason());
                })
                .toList();
    }

    private BigDecimal hoursInsideReportRange(Timesheet timesheet, LocalDate reportStart, LocalDate reportEnd) {
        if (timesheet == null || timesheet.getEntries() == null || reportStart == null || reportEnd == null) {
            return BigDecimal.ZERO;
        }
        return timesheet.getEntries().stream()
                .filter(entry -> entry.getWorkDate() != null
                        && !entry.getWorkDate().isBefore(reportStart)
                        && !entry.getWorkDate().isAfter(reportEnd))
                .map(entry -> entry.getHoursWorked() == null ? BigDecimal.ZERO : entry.getHoursWorked())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<String> detectIssues(Timesheet timesheet) {
        List<String> issues = new ArrayList<>();
        if (timesheet.getStatus() == TimesheetStatus.REJECTED) {
            issues.add("REJECTED");
        }
        if (StringUtils.hasText(timesheet.getChangeReason())) {
            issues.add("SUBMITTED_CORRECTION");
        }
        if (timesheet.getTotalHours() != null && timesheet.getTotalHours().compareTo(BigDecimal.ZERO) == 0) {
            issues.add("ZERO_RECORDED_HOURS");
        }
        if (timesheet.getStatus() == TimesheetStatus.SUBMITTED
                && timesheet.getSubmittedAt() != null
                && !timesheet.getSubmittedAt().isAfter(LocalDateTime.now().minusDays(3))) {
            issues.add("PENDING_APPROVAL_3_DAYS");
        }
        return issues;
    }

    private MyHrTimesheetReportSummary summarize(String organizationId, Criteria baseCriteria, LocalDate reportStart, LocalDate reportEnd) {
        List<String> employees = mongoTemplate.findDistinct(
                Query.query(baseCriteria), "userId", Timesheet.class, String.class);
        AggregationResults<org.bson.Document> totals = mongoTemplate.aggregate(
                Aggregation.newAggregation(
                        Aggregation.match(baseCriteria),
                        Aggregation.unwind("entries"),
                        Aggregation.match(Criteria.where("entries.workDate").gte(reportStart).lte(reportEnd)),
                        Aggregation.group().sum("entries.hoursWorked").as("hours")),
                "timesheets",
                org.bson.Document.class);
        BigDecimal hours = Optional.ofNullable(totals.getUniqueMappedResult())
                .map(document -> document.get("hours"))
                .map(value -> value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString()))
                .orElse(BigDecimal.ZERO);
        long timesheets = mongoTemplate.count(Query.query(baseCriteria), Timesheet.class);
        long submitted = mongoTemplate.count(Query.query(new Criteria().andOperator(baseCriteria, Criteria.where("status").is(TimesheetStatus.SUBMITTED))), Timesheet.class);
        long approved = mongoTemplate.count(Query.query(new Criteria().andOperator(baseCriteria, Criteria.where("status").is(TimesheetStatus.APPROVED))), Timesheet.class);
        long rejected = mongoTemplate.count(Query.query(new Criteria().andOperator(baseCriteria, Criteria.where("status").is(TimesheetStatus.REJECTED))), Timesheet.class);
        long draft = mongoTemplate.count(Query.query(new Criteria().andOperator(baseCriteria, Criteria.where("status").is(TimesheetStatus.DRAFT))), Timesheet.class);
        long exceptions = mongoTemplate.count(Query.query(new Criteria().andOperator(baseCriteria, new Criteria().orOperator(
                Criteria.where("status").is(TimesheetStatus.REJECTED),
                Criteria.where("changeReason").ne(null),
                Criteria.where("totalHours").is(BigDecimal.ZERO),
                new Criteria().andOperator(
                        Criteria.where("status").is(TimesheetStatus.SUBMITTED),
                        Criteria.where("submittedAt").lte(LocalDateTime.now().minusDays(3)))))), Timesheet.class);
        return new MyHrTimesheetReportSummary(
                employees.size(),
                timesheets,
                hours,
                submitted,
                approved,
                rejected,
                draft,
                exceptions);
    }

    private List<MyHrTimesheetReportAttentionItem> attentionItems(Criteria baseCriteria) {
        return List.of(
                attentionItem(baseCriteria, "REJECTED", "Rejected"),
                attentionItem(baseCriteria, "SUBMITTED_CORRECTION", "Submitted corrections"),
                attentionItem(baseCriteria, "PENDING_APPROVAL_3_DAYS", "Pending approval > 3 days"),
                attentionItem(baseCriteria, "ZERO_RECORDED_HOURS", "Zero recorded hours"));
    }

    private MyHrTimesheetReportAttentionItem attentionItem(Criteria baseCriteria, String issue, String label) {
        Criteria criteria = new Criteria().andOperator(baseCriteria, Objects.requireNonNull(issueCriteria(issue)));
        return new MyHrTimesheetReportAttentionItem(issue, label, mongoTemplate.count(Query.query(criteria), Timesheet.class));
    }

    private Map<String, String> managerNameByEmployee(String organizationId) {
        List<ReportingAssignment> assignments = reportingAssignmentRepository
                .findByOrganizationIdAndRelationshipTypeAndIsActiveTrue(organizationId, PRIMARY_MANAGER);
        Set<String> managerIds = assignments.stream()
                .map(ReportingAssignment::getReportingManagerUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, EmployeeProfile> profileByUserId = managerIds.isEmpty()
                ? Map.of()
                : employeeProfileRepository.findByUserIdIn(managerIds).stream()
                        .collect(Collectors.toMap(EmployeeProfile::getUserId, profile -> profile, (a, b) -> a));
        Map<String, User> userByUserId = managerIds.isEmpty()
                ? Map.of()
                : userRepository.findByIdIn(managerIds).stream()
                        .collect(Collectors.toMap(User::getId, user -> user, (a, b) -> a));
        return assignments.stream()
                .collect(Collectors.toMap(
                        ReportingAssignment::getEmployeeUserId,
                        assignment -> resolveDisplayName(assignment.getReportingManagerUserId(), profileByUserId, userByUserId),
                        (a, b) -> a));
    }

    private List<String> supportedScopes(AuthenticatedMyHrUser actor) {
        if (actor.role() == UserRole.COMPANY_ADMIN) {
            return List.of("ORGANIZATION", "REPORTING_MANAGER", "EMPLOYEE");
        }
        return List.of("ACCESSIBLE", "REPORTING_MANAGER", "EMPLOYEE");
    }

    private record ScopeResolution(Set<String> userIds, boolean unboundedOrganization) {
    }

    private List<MyHrReportManagerRow> buildManagerSummary(
            List<ReportingAssignment> activeAssignments,
            Map<String, EmployeeProfile> profileByUserId,
            Map<String, User> userByUserId) {

        Map<String, Long> directReportCountByManagerUserId = activeAssignments.stream()
                .collect(Collectors.groupingBy(ReportingAssignment::getReportingManagerUserId, Collectors.counting()));

        return directReportCountByManagerUserId.entrySet().stream()
                .map(entry -> new MyHrReportManagerRow(
                        resolveDisplayName(entry.getKey(), profileByUserId, userByUserId),
                        entry.getValue()))
                .sorted(Comparator.comparingLong(MyHrReportManagerRow::directReportCount).reversed())
                .toList();
    }

    /**
     * Prefers the person's profile name; falls back to their login id or email
     * when they have no EmployeeProfile (e.g. a Company Admin acting as a
     * reporting manager) so they never show up as an unlabeled blank row.
     */
    private String resolveDisplayName(
            String userId, Map<String, EmployeeProfile> profileByUserId, Map<String, User> userByUserId) {

        EmployeeProfile profile = profileByUserId.get(userId);
        if (profile != null && profile.getName() != null && !profile.getName().isBlank()) {
            return profile.getName();
        }

        User user = userByUserId.get(userId);
        if (user == null) {
            return null;
        }
        return user.getLoginId() != null && !user.getLoginId().isBlank() ? user.getLoginId() : user.getEmail();
    }
}
