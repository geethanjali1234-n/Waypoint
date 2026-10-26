package com.waypoint.service;

import com.waypoint.dto.myhr.ChangeReportingManagerRequest;
import com.waypoint.dto.myhr.EligibleReportingManagerResponse;
import com.waypoint.dto.myhr.EmployeeDetailResponse;
import com.waypoint.dto.myhr.EmployeeDirectReportItem;
import com.waypoint.dto.myhr.EmployeeSummaryResponse;
import com.waypoint.dto.myhr.MyHrOwnProfileResponse;
import com.waypoint.dto.myhr.ReportingAssignmentHistoryItem;
import com.waypoint.dto.myhr.ReportingManagerRef;
import com.waypoint.entity.EmployeeProfile;
import com.waypoint.entity.Organization;
import com.waypoint.entity.OrganizationSettings;
import com.waypoint.entity.ReportingAssignment;
import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.AuditActionType;
import com.waypoint.enums.NotificationType;
import com.waypoint.enums.RelationshipType;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.repository.EmployeeProfileRepository;
import com.waypoint.repository.OrganizationRepository;
import com.waypoint.repository.OrganizationSettingsRepository;
import com.waypoint.repository.ReportingAssignmentRepository;
import com.waypoint.repository.UserRepository;
import com.waypoint.security.MyHrAuthenticatedUserService;
import com.waypoint.security.MyHrAuthenticatedUserService.AuthenticatedMyHrUser;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class EmployeeManagementService {

    private static final Logger log = LoggerFactory.getLogger(EmployeeManagementService.class);
    private static final Set<UserRole> MANAGER_ROLES = Set.of(UserRole.HR, UserRole.COMPANY_ADMIN);
    // Package-visible: the organization's Authorized Signature is the Company
    // Admin's own Profile signature (AuthorizedSignatureService).
    static final String USER_SIGNATURE_SUBDIRECTORY = "user-signatures";
    private static final Set<UserRole> SELF_SERVICE_ROLES =
            Set.of(UserRole.COMPANY_ADMIN, UserRole.EMPLOYEE, UserRole.HR);

    private final UserRepository userRepository;
    private final EmployeeProfileRepository employeeProfileRepository;
    private final OrganizationRepository organizationRepository;
    private final ReportingAssignmentRepository reportingAssignmentRepository;
    private final ReportingAssignmentService reportingAssignmentService;
    private final MyHrAuthenticatedUserService authenticatedUserService;
    private final AuditService auditService;
    private final InAppNotificationService inAppNotificationService;
    private final FileStorageService fileStorageService;
    private final OrganizationSettingsRepository organizationSettingsRepository;

    public List<EmployeeSummaryResponse> list(String actorUserId) {
        AuthenticatedMyHrUser actor = requireManager(actorUserId);

        List<User> people = actor.role() == UserRole.HR
                ? directReportUsers(actor)
                : userRepository.findByOrganizationIdAndRoleIn(
                        actor.organizationId(), List.of(UserRole.HR, UserRole.EMPLOYEE));

        Map<String, EmployeeProfile> profiles = profilesByUserId(userIds(people));

        Map<String, ReportingAssignment> activeByEmployee = activeAssignmentsFor(userIds(people));
        Map<String, String> managerNames = namesByUserId(activeByEmployee.values().stream()
                .map(ReportingAssignment::getReportingManagerUserId)
                .collect(Collectors.toSet()));

        return people.stream()
                .map(user -> {
                    EmployeeProfile profile = profiles.get(user.getId());
                    ReportingAssignment active = activeByEmployee.get(user.getId());
                    String managerName = active == null ? null
                            : managerNames.get(active.getReportingManagerUserId());
                    return new EmployeeSummaryResponse(
                            user.getId(),
                            profile == null ? null : profile.getEmployeeId(),
                            displayName(user, profile),
                            personType(user),
                            profile == null ? null : profile.getWorkEmail(),
                            profile == null ? user.getEmail() : profile.getPersonalEmail(),
                            profile == null ? null : profile.getDesignation(),
                            profile == null ? null : profile.getEmploymentType(),
                            profile == null ? null : profile.getStartDate(),
                            managerName,
                            user.getAccountStatus());
                })
                .sorted(Comparator.comparing(
                        EmployeeSummaryResponse::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }

    public EmployeeDetailResponse getDetail(String actorUserId, String employeeUserId) {
        AuthenticatedMyHrUser actor = requireManager(actorUserId);
        User target = assertCanManage(actor, employeeUserId);
        return buildDetail(target);
    }

    /**
     * Self-service equivalent of {@link #getDetail} for a user viewing their own
     * record - available to COMPANY_ADMIN, HR and EMPLOYEE alike. Company Admin
     * has no EmployeeProfile, so the response falls back to User + Organization
     * fields only (see {@link MyHrOwnProfileResponse}).
     */
    public MyHrOwnProfileResponse getOwnDetail(String actorUserId) {
        AuthenticatedMyHrUser actor = authenticatedUserService.require(actorUserId, SELF_SERVICE_ROLES, false);
        User target = userRepository.findById(actor.userId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found"));
        EmployeeProfile profile = employeeProfileRepository.findByUserId(target.getId()).orElse(null);
        Organization organization = organizationRepository.findById(actor.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));

        ReportingAssignment activeAssignment = reportingAssignmentRepository
                .findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                        target.getId(), RelationshipType.PRIMARY_REPORTING_MANAGER)
                .orElse(null);
        ReportingManagerRef reportingManager = null;
        if (activeAssignment != null) {
            String managerUserId = activeAssignment.getReportingManagerUserId();
            User managerUser = userRepository.findById(managerUserId).orElse(null);
            EmployeeProfile managerProfile = managerUser == null ? null
                    : employeeProfileRepository.findByUserId(managerUserId).orElse(null);
            reportingManager = new ReportingManagerRef(
                    managerUserId,
                    managerProfile == null ? null : managerProfile.getEmployeeId(),
                    managerUser == null ? null : displayName(managerUser, managerProfile),
                    null,
                    activeAssignment.getEffectiveFrom(),
                    null);
        }

        return new MyHrOwnProfileResponse(
                target.getId(), target.getLoginId(), profile == null ? null : profile.getEmployeeId(),
                target.getRole(), target.getAccountStatus(),
                profile == null ? null : profile.getFirstName(),
                profile == null ? null : profile.getLastName(),
                profile == null ? null : profile.getName(),
                target.getEmail(), profile == null ? null : profile.getPersonalEmail(),
                profile == null ? null : profile.getPhoneNumber(),
                profile == null ? null : profile.getDesignation(),
                profile == null ? null : profile.getEmploymentType(),
                profile == null ? null : profile.getStartDate(),
                profile == null ? null : profile.getStreetAddress(),
                profile == null ? null : profile.getCity(),
                profile == null ? null : profile.getState(),
                profile == null ? null : profile.getZipCode(),
                organization.getId(), organization.getOrganizationName(), organization.getOrganizationCode(),
                organization.getOrganizationEmail(),
                organizationAddress(organization), organization.getPhoneNumber(), organization.getWebsite(),
                organization.getEinNumber(), organization.getEVerifyNumber(),
                organization.getAdminName(), organization.getAdminEmail(), organization.getAdminPhoneNumber(),
                organizationSettingsRepository.findByOrganizationId(organization.getId())
                        .map(OrganizationSettings::getPayrollPeriod).orElse(null),
                organizationLogoUrl(organization),
                organizationSignatureUrl(organization),
                reportingManager,
                fileStorageService.findStoredPath(USER_SIGNATURE_SUBDIRECTORY, target.getId())
                        .map(path -> "/uploads/" + path).orElse(null));
    }

    /**
     * Uploads/replaces the authenticated user's own signature (My HR Profile,
     * any role). It is always stored for the caller - never a user id sent by
     * the client - through the same FileStorageService/GridFS storage as the
     * organization signature, one current file per user.
     */
    public MyHrOwnProfileResponse uploadOwnSignature(String actorUserId, MultipartFile file) {
        AuthenticatedMyHrUser actor = authenticatedUserService.require(actorUserId, SELF_SERVICE_ROLES, false);
        if (file == null || file.isEmpty()) throw new BusinessException("No file was provided");
        if (file.getSize() > OrganizationSettingsService.SIGNATURE_MAX_BYTES
                || !OrganizationSettingsService.SIGNATURE_CONTENT_TYPES.contains(file.getContentType()))
            throw new BusinessException("Use a PNG or JPEG signature no larger than 2 MB");
        fileStorageService.store(file, USER_SIGNATURE_SUBDIRECTORY, actor.userId());
        return getOwnDetail(actorUserId);
    }

    /** Removes the authenticated user's own signature, if they have one. */
    public MyHrOwnProfileResponse removeOwnSignature(String actorUserId) {
        AuthenticatedMyHrUser actor = authenticatedUserService.require(actorUserId, SELF_SERVICE_ROLES, false);
        fileStorageService.remove(USER_SIGNATURE_SUBDIRECTORY, actor.userId());
        return getOwnDetail(actorUserId);
    }

    /**
     * Single-line mailing address for display on generated documents (e.g. the
     * offer letter letterhead/footer), in US mailing style 'street, city,
     * state zip' - blank parts are skipped rather than left as stray commas.
     */
    private String organizationAddress(Organization organization) {
        String stateZip = Stream.of(organization.getState(), organization.getZipCode())
                .filter(part -> part != null && !part.isBlank())
                .reduce((a, b) -> a + " " + b)
                .orElse(null);
        return Stream.of(organization.getStreetAddress(), organization.getCity(), stateZip)
                .filter(part -> part != null && !part.isBlank())
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }

    /**
     * Logo uploaded via Organization Settings (see OrganizationSettingsService.
     * uploadLogo), reused here so generated documents such as the offer letter
     * can brand every page with it.
     */
    private String organizationLogoUrl(Organization organization) {
        String documentId = organization.getOrganizationLogoDocumentId();
        return documentId == null || documentId.isBlank() ? null : "/uploads/" + documentId;
    }

    /**
     * Organization Signature uploaded by a COMPANY_ADMIN via Organization
     * Settings (see OrganizationSettingsService.uploadSignature), reused here
     * so HR's offer letter preparation can apply it automatically without
     * ever letting HR upload or choose a signature of their own.
     */
    private String organizationSignatureUrl(Organization organization) {
        String documentId = organization.getOrganizationSignatureDocumentId();
        return documentId == null || documentId.isBlank() ? null : "/uploads/" + documentId;
    }

    public EmployeeDetailResponse changeReportingManager(
            String actorUserId, String employeeUserId, ChangeReportingManagerRequest request) {

        AuthenticatedMyHrUser actor = requireManager(actorUserId);
        User target = assertCanManage(actor, employeeUserId);
        String newManagerUserId = request.getNewReportingManagerUserId().trim();

        LocalDate today = LocalDate.now();
        LocalDate targetStartDate = employeeProfileRepository.findByUserId(target.getId())
                .map(EmployeeProfile::getStartDate)
                .orElse(null);
        LocalDate newManagerStartDate = employeeProfileRepository.findByUserId(newManagerUserId)
                .map(EmployeeProfile::getStartDate)
                .orElse(null);

        LocalDate earliestAllowed = today;
        if (targetStartDate != null && targetStartDate.plusDays(1).isAfter(earliestAllowed)) {
            earliestAllowed = targetStartDate.plusDays(1);
        }
        if (newManagerStartDate != null && newManagerStartDate.plusDays(1).isAfter(earliestAllowed)) {
            earliestAllowed = newManagerStartDate.plusDays(1);
        }

        LocalDate effectiveFrom = request.getEffectiveDate() == null ? earliestAllowed : request.getEffectiveDate();
        if (effectiveFrom.isBefore(today)) {
            throw new BusinessException("Effective date cannot be in the past");
        }
        if (targetStartDate != null && !effectiveFrom.isAfter(targetStartDate)) {
            throw new BusinessException("Effective date must be after the employee's start date");
        }
        if (newManagerStartDate != null && !effectiveFrom.isAfter(newManagerStartDate)) {
            throw new BusinessException("Effective date must be after the new reporting manager's start date");
        }

        String previousManagerUserId = reportingAssignmentRepository
                .findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                        target.getId(), RelationshipType.PRIMARY_REPORTING_MANAGER)
                .map(ReportingAssignment::getReportingManagerUserId)
                .orElse(null);
        boolean hasActiveAssignment = previousManagerUserId != null;

        if (hasActiveAssignment) {
            reportingAssignmentService.reassignPrimaryManager(
                    actor.organizationId(), target.getId(), newManagerUserId, actor.userId(), effectiveFrom);
        } else {
            LocalDateTime now = LocalDateTime.now();
            reportingAssignmentService.createActiveAssignment(ReportingAssignment.builder()
                    .organizationId(actor.organizationId())
                    .employeeUserId(target.getId())
                    .reportingManagerUserId(newManagerUserId)
                    .relationshipType(RelationshipType.PRIMARY_REPORTING_MANAGER)
                    .isActive(true)
                    .effectiveFrom(effectiveFrom)
                    .assignedByUserId(actor.userId())
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        }

        recordReportingManagerChange(actor, target, previousManagerUserId, newManagerUserId, effectiveFrom);
        // No meaningful transition (new == old) is normally rejected upstream by
        // ReportingAssignmentService itself, but guard here too so a no-op never
        // produces a notification regardless of that collaborator's behavior.
        if (previousManagerUserId == null || !previousManagerUserId.equals(newManagerUserId)) {
            notifyReportingManagerChange(actor, target, previousManagerUserId, newManagerUserId);
        }

        return buildDetail(target);
    }

    private void recordReportingManagerChange(
            AuthenticatedMyHrUser actor, User target, String previousManagerUserId,
            String newManagerUserId, LocalDate effectiveFrom) {
        Set<String> lookup = new HashSet<>();
        lookup.add(newManagerUserId);
        if (previousManagerUserId != null) {
            lookup.add(previousManagerUserId);
        }
        Map<String, String> names = namesByUserId(lookup);
        String targetName = displayName(target, employeeProfileRepository.findByUserId(target.getId()).orElse(null));

        auditService.record(actor,
                previousManagerUserId == null
                        ? AuditActionType.REPORTING_MANAGER_ASSIGNED
                        : AuditActionType.REPORTING_MANAGER_CHANGED,
                "USER", target.getId(), targetName,
                previousManagerUserId == null
                        ? targetName + " assigned to reporting manager " + names.get(newManagerUserId)
                        : targetName + " moved from " + names.get(previousManagerUserId)
                                + " to " + names.get(newManagerUserId),
                previousManagerUserId == null ? null : Map.of(
                        "reportingManagerUserId", previousManagerUserId,
                        "reportingManagerName", nullSafe(names.get(previousManagerUserId))),
                Map.of(
                        "reportingManagerUserId", newManagerUserId,
                        "reportingManagerName", nullSafe(names.get(newManagerUserId)),
                        "effectiveFrom", effectiveFrom.toString()));
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private String personaSegment(UserRole role) {
        if (role == UserRole.COMPANY_ADMIN) return "admin";
        if (role == UserRole.HR) return "hr";
        return "employee";
    }

    /** "Full Name (EMPLOYEE-ID)", falling back to just the identity when no employee id exists (e.g. Company Admin). */
    private String participantLabel(User user, EmployeeProfile profile) {
        String employeeId = profile == null ? null : profile.getEmployeeId();
        String identity = displayName(user, profile);
        return employeeId == null || employeeId.isBlank() ? identity : identity + " (" + employeeId + ")";
    }

    /**
     * Notifies the parties affected by a reporting-manager change: the new manager
     * always gains a direct report, the employee always learns who their manager is
     * now, and the previous manager (when one existed and is actually different from
     * the new manager) learns the employee moved away. A no-op change (new == old)
     * never reaches here - {@link ReportingAssignmentService#reassignPrimaryManager}
     * rejects it before this point.
     */
    private void notifyReportingManagerChange(AuthenticatedMyHrUser actor, User target,
            String previousManagerUserId, String newManagerUserId) {
        Set<String> lookupIds = new HashSet<>(Set.of(target.getId(), newManagerUserId));
        if (previousManagerUserId != null) {
            lookupIds.add(previousManagerUserId);
        }
        Map<String, User> users = userRepository.findByIdIn(lookupIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        Map<String, EmployeeProfile> profiles = profilesByUserId(lookupIds);

        User newManager = users.get(newManagerUserId);
        User targetUser = users.get(target.getId());
        String targetLabel = participantLabel(target, profiles.get(target.getId()));

        if (newManager != null && !newManagerUserId.equals(actor.userId())) {
            notifySafely(() -> inAppNotificationService.createNotification(actor.organizationId(), newManagerUserId,
                    NotificationType.EMPLOYEE_ASSIGNED,
                    previousManagerUserId == null ? "New employee assigned" : "Employee assigned to you",
                    targetLabel + " has been assigned to you as a direct report.",
                    target.getId(), "/myhr/" + personaSegment(newManager.getRole()) + "/employees"));
        }

        if (targetUser != null && newManager != null && !target.getId().equals(actor.userId())) {
            String newManagerLabel = participantLabel(newManager, profiles.get(newManagerUserId));
            notifySafely(() -> inAppNotificationService.createNotification(actor.organizationId(), target.getId(),
                    NotificationType.REPORTING_MANAGER_UPDATED, "Reporting manager updated",
                    "Your reporting manager has been "
                            + (previousManagerUserId == null ? "set to " : "changed to ") + newManagerLabel + ".",
                    target.getId(), "/myhr/" + personaSegment(targetUser.getRole()) + "/profile"));
        }

        if (previousManagerUserId != null && !previousManagerUserId.equals(newManagerUserId)
                && !previousManagerUserId.equals(actor.userId())) {
            User oldManager = users.get(previousManagerUserId);
            if (oldManager != null) {
                notifySafely(() -> inAppNotificationService.createNotification(actor.organizationId(), previousManagerUserId,
                        NotificationType.EMPLOYEE_REASSIGNED, "Employee reassigned",
                        targetLabel + " is no longer assigned to you as a direct report.",
                        target.getId(), "/myhr/" + personaSegment(oldManager.getRole()) + "/employees"));
            }
        }
    }

    /**
     * Notification creation is secondary to the business action it follows - a
     * transient failure here (e.g. a Mongo hiccup) must never make an otherwise
     * successful reporting-manager or account-status change look like it failed.
     */
    private void notifySafely(Runnable notification) {
        try {
            notification.run();
        } catch (RuntimeException failure) {
            log.warn("Employee management notification could not be created", failure);
        }
    }

    public EmployeeDetailResponse updateAccountStatus(
            String actorUserId, String employeeUserId, AccountStatus status, String reassignToUserId) {

        AuthenticatedMyHrUser actor = requireManager(actorUserId);

        if (status == null || status == AccountStatus.BLOCKED) {
            throw new BusinessException("Account status must be ACTIVE or INACTIVE");
        }
        if (actor.userId().equals(employeeUserId)) {
            throw new BusinessException("You cannot change your own account status");
        }

        User target = assertCanManage(actor, employeeUserId);
        AccountStatus previousStatus = target.getAccountStatus();
        if (previousStatus != status) {
            if (status == AccountStatus.INACTIVE && target.getRole() == UserRole.HR) {
                reassignDirectReportsBeforeDeactivation(actor, target, reassignToUserId);
            }

            target.setAccountStatus(status);
            target.setUpdatedAt(LocalDateTime.now());
            userRepository.save(target);

            String targetName = displayName(target, employeeProfileRepository.findByUserId(target.getId()).orElse(null));
            auditService.record(actor, AuditActionType.ACCOUNT_STATUS_CHANGED,
                    "USER", target.getId(), targetName,
                    targetName + " account " + (status == AccountStatus.ACTIVE ? "activated" : "deactivated"),
                    Map.of("accountStatus", String.valueOf(previousStatus)),
                    Map.of("accountStatus", status.name()));
            notifySafely(() -> inAppNotificationService.createNotification(actor.organizationId(), target.getId(),
                    NotificationType.ACCOUNT_STATUS_UPDATED, "Account status updated",
                    "Your Waypoint account has been "
                            + (status == AccountStatus.ACTIVE ? "activated" : "deactivated") + ".",
                    target.getId(), "/myhr/" + personaSegment(target.getRole()) + "/profile"));
        }
        return buildDetail(target);
    }

    /**
     * Before an HR is deactivated, any employees still actively reporting to them
     * must be handed off to another active HR or Company Admin so no one is left
     * pointing at an inactive manager. No-op when the HR has no active direct
     * reports.
     */
    private void reassignDirectReportsBeforeDeactivation(
            AuthenticatedMyHrUser actor, User departingHr, String reassignToUserId) {

        List<String> directReportIds = reportingAssignmentRepository
                .findByOrganizationIdAndReportingManagerUserIdAndRelationshipTypeAndIsActiveTrue(
                        actor.organizationId(), departingHr.getId(), RelationshipType.PRIMARY_REPORTING_MANAGER)
                .stream()
                .map(ReportingAssignment::getEmployeeUserId)
                .distinct()
                .toList();
        if (directReportIds.isEmpty()) {
            return;
        }
        if (reassignToUserId == null || reassignToUserId.isBlank()) {
            throw new BusinessException("Reassign this HR's employees to another manager before deactivating");
        }
        LocalDate effectiveFrom = LocalDate.now();
        LocalDate newManagerStartDate = employeeProfileRepository.findByUserId(reassignToUserId)
                .map(EmployeeProfile::getStartDate)
                .orElse(null);
        if (newManagerStartDate != null && !effectiveFrom.isAfter(newManagerStartDate)) {
            throw new BusinessException("Selected manager has not started yet; choose someone whose start date has already passed");
        }

        Map<String, User> employeesById = userRepository.findByIdIn(directReportIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        for (String employeeId : directReportIds) {
            reportingAssignmentService.reassignPrimaryManager(
                    actor.organizationId(), employeeId, reassignToUserId, actor.userId(), effectiveFrom);
            User employee = employeesById.get(employeeId);
            if (employee != null) {
                recordReportingManagerChange(actor, employee, departingHr.getId(), reassignToUserId, effectiveFrom);
                notifyReportingManagerChange(actor, employee, departingHr.getId(), reassignToUserId);
            }
        }
    }

    public List<EligibleReportingManagerResponse> eligibleReportingManagers(String actorUserId, String employeeUserId) {
        AuthenticatedMyHrUser actor = requireManager(actorUserId);

        // An HR user may only report to a Company Admin; an Employee may report to
        // either an HR or a Company Admin (see ReportingAssignmentService rules).
        List<UserRole> allowedRoles = List.of(UserRole.HR, UserRole.COMPANY_ADMIN);
        if (employeeUserId != null && !employeeUserId.isBlank()) {
            User target = userRepository.findById(employeeUserId)
                    .filter(user -> actor.organizationId().equals(user.getOrganizationId()))
                    .orElse(null);
            if (target != null && target.getRole() == UserRole.HR) {
                allowedRoles = List.of(UserRole.COMPANY_ADMIN);
            }
        }

        List<User> managers = userRepository.findByOrganizationIdAndRoleIn(
                actor.organizationId(), allowedRoles);
        Map<String, EmployeeProfile> profiles = profilesByUserId(userIds(managers));

        return managers.stream()
                .filter(user -> user.getAccountStatus() == AccountStatus.ACTIVE)
                .filter(user -> !user.getId().equals(employeeUserId))
                .filter(user -> hasStarted(profiles.get(user.getId())))
                .map(user -> new EligibleReportingManagerResponse(
                        user.getId(),
                        user.getLoginId(),
                        displayName(user, profiles.get(user.getId())),
                        roleLabel(user.getRole()),
                        profiles.get(user.getId()) == null ? null : profiles.get(user.getId()).getStartDate()))
                .sorted(Comparator.comparing(
                        EligibleReportingManagerResponse::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }

    /**
     * A manager whose employment start date hasn't arrived yet can't meaningfully
     * take over reporting employees. Users without a tracked start date (e.g. a
     * Company Admin, who has no {@link EmployeeProfile}) are always eligible.
     */
    private boolean hasStarted(EmployeeProfile profile) {
        return profile == null || profile.getStartDate() == null || !profile.getStartDate().isAfter(LocalDate.now());
    }

    private EmployeeDetailResponse buildDetail(User target) {
        EmployeeProfile profile = employeeProfileRepository.findByUserId(target.getId()).orElse(null);

        List<ReportingAssignment> history = reportingAssignmentRepository
                .findByEmployeeUserIdOrderByEffectiveFromDescCreatedAtDesc(target.getId());

        Set<String> managerIds = history.stream()
                .map(ReportingAssignment::getReportingManagerUserId)
                .collect(Collectors.toCollection(HashSet::new));
        ReportingAssignment active = history.stream()
                .filter(item -> Boolean.TRUE.equals(item.getIsActive()))
                .findFirst()
                .orElse(null);
        if (active != null && active.getAssignedByUserId() != null) {
            managerIds.add(active.getAssignedByUserId());
        }
        Map<String, String> names = namesByUserId(managerIds);

        ReportingManagerRef managerRef = null;
        if (active != null) {
            String managerUserId = active.getReportingManagerUserId();
            EmployeeProfile managerProfile = managerUserId == null ? null
                    : employeeProfileRepository.findByUserId(managerUserId).orElse(null);
            managerRef = new ReportingManagerRef(
                    managerUserId,
                    managerProfile == null ? null : managerProfile.getEmployeeId(),
                    names.get(managerUserId),
                    null,
                    active.getEffectiveFrom(),
                    active.getAssignedByUserId() == null ? null : names.get(active.getAssignedByUserId()));
        }

        List<ReportingAssignmentHistoryItem> historyItems = history.stream()
                .map(item -> new ReportingAssignmentHistoryItem(
                        names.get(item.getReportingManagerUserId()),
                        item.getEffectiveFrom(),
                        item.getEffectiveTo(),
                        Boolean.TRUE.equals(item.getIsActive())))
                .toList();

        List<EmployeeDirectReportItem> directReports = directReportsOf(target);

        return new EmployeeDetailResponse(
                target.getId(),
                profile == null ? null : profile.getEmployeeId(),
                displayName(target, profile),
                profile == null ? null : profile.getFirstName(),
                profile == null ? null : profile.getLastName(),
                personType(target),
                profile == null ? null : profile.getWorkEmail(),
                profile == null ? target.getEmail() : profile.getPersonalEmail(),
                profile == null ? null : profile.getPhoneNumber(),
                profile == null ? null : profile.getDesignation(),
                profile == null ? null : profile.getEmploymentType(),
                profile == null ? null : profile.getStartDate(),
                profile == null ? null : profile.getStreetAddress(),
                profile == null ? null : profile.getCity(),
                profile == null ? null : profile.getState(),
                profile == null ? null : profile.getZipCode(),
                target.getAccountStatus(),
                target.getCreatedAt(),
                managerRef,
                historyItems,
                directReports);
    }

    private List<EmployeeDirectReportItem> directReportsOf(User manager) {
        if (manager.getRole() != UserRole.HR && manager.getRole() != UserRole.COMPANY_ADMIN) {
            return List.of();
        }
        List<String> reportUserIds = reportingAssignmentRepository
                .findByOrganizationIdAndReportingManagerUserIdAndRelationshipTypeAndIsActiveTrue(
                        manager.getOrganizationId(), manager.getId(),
                        RelationshipType.PRIMARY_REPORTING_MANAGER)
                .stream()
                .map(ReportingAssignment::getEmployeeUserId)
                .distinct()
                .toList();
        if (reportUserIds.isEmpty()) {
            return List.of();
        }

        Map<String, EmployeeProfile> profiles = profilesByUserId(new LinkedHashSet<>(reportUserIds));
        return userRepository.findByIdIn(reportUserIds).stream()
                .map(user -> {
                    EmployeeProfile profile = profiles.get(user.getId());
                    return new EmployeeDirectReportItem(
                            user.getId(),
                            profile == null ? null : profile.getEmployeeId(),
                            displayName(user, profile),
                            profile == null ? null : profile.getDesignation(),
                            user.getAccountStatus());
                })
                .sorted(Comparator.comparing(
                        EmployeeDirectReportItem::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }

    private AuthenticatedMyHrUser requireManager(String actorUserId) {
        return authenticatedUserService.require(actorUserId, MANAGER_ROLES, false);
    }

    /**
     * Ensures the actor is allowed to view/manage the given employee and returns
     * the target {@link User}. A Company Admin may manage any HR or Employee in
     * the organization; an HR user may only manage their own active direct
     * reports.
     */
    private User assertCanManage(AuthenticatedMyHrUser actor, String employeeUserId) {
        User target = userRepository.findById(employeeUserId)
                .filter(user -> actor.organizationId().equals(user.getOrganizationId()))
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found"));

        if (actor.role() == UserRole.COMPANY_ADMIN) {
            if (target.getRole() != UserRole.HR && target.getRole() != UserRole.EMPLOYEE) {
                throw new ForbiddenException("You do not have access to this resource");
            }
            return target;
        }

        boolean isDirectReport = reportingAssignmentRepository
                .findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                        employeeUserId, RelationshipType.PRIMARY_REPORTING_MANAGER)
                .map(assignment -> actor.userId().equals(assignment.getReportingManagerUserId()))
                .orElse(false);
        if (!isDirectReport) {
            throw new ForbiddenException("You can only manage your own direct reports");
        }
        return target;
    }

    private List<User> directReportUsers(AuthenticatedMyHrUser actor) {
        List<String> employeeIds = reportingAssignmentRepository
                .findByOrganizationIdAndReportingManagerUserIdAndRelationshipTypeAndIsActiveTrue(
                        actor.organizationId(), actor.userId(), RelationshipType.PRIMARY_REPORTING_MANAGER)
                .stream()
                .map(ReportingAssignment::getEmployeeUserId)
                .distinct()
                .toList();
        return employeeIds.isEmpty() ? List.of() : userRepository.findByIdIn(employeeIds);
    }

    private Map<String, ReportingAssignment> activeAssignmentsFor(Set<String> employeeUserIds) {
        Map<String, ReportingAssignment> result = new HashMap<>();
        for (String employeeUserId : employeeUserIds) {
            reportingAssignmentRepository
                    .findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                            employeeUserId, RelationshipType.PRIMARY_REPORTING_MANAGER)
                    .ifPresent(assignment -> result.put(employeeUserId, assignment));
        }
        return result;
    }

    private Map<String, EmployeeProfile> profilesByUserId(Set<String> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return employeeProfileRepository.findByUserIdIn(userIds).stream()
                .collect(Collectors.toMap(EmployeeProfile::getUserId, Function.identity(), (a, b) -> a));
    }

    private Map<String, String> namesByUserId(Set<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        List<User> users = userRepository.findByIdIn(userIds);
        Map<String, EmployeeProfile> profiles = profilesByUserId(
                users.stream().map(User::getId).collect(Collectors.toSet()));
        Map<String, String> result = new HashMap<>();
        for (User user : users) {
            result.put(user.getId(), displayName(user, profiles.get(user.getId())));
        }
        return result;
    }

    private Set<String> userIds(List<User> users) {
        return users.stream().map(User::getId).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String displayName(User user, EmployeeProfile profile) {
        if (profile != null && profile.getName() != null && !profile.getName().isBlank()) {
            return profile.getName();
        }
        if (user.getLoginId() != null && !user.getLoginId().isBlank()) {
            return user.getLoginId();
        }
        return user.getEmail();
    }

    private String personType(User user) {
        return user.getRole() == UserRole.HR ? "HR" : "EMPLOYEE";
    }

    private String roleLabel(UserRole role) {
        return switch (role) {
            case HR -> "HR";
            case COMPANY_ADMIN -> "Company Admin";
            case EMPLOYEE -> "Employee";
            default -> role.name();
        };
    }
}
