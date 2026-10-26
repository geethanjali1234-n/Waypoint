package com.waypoint.service;

import com.waypoint.dto.myhr.CreateEmployeeAccountRequest;
import com.waypoint.dto.myhr.CreateHrAccountRequest;
import com.waypoint.dto.myhr.CreatedAccountResult;
import com.waypoint.dto.myhr.EmployeeProfileFields;
import com.waypoint.dto.myhr.ReportingManagerOption;
import com.waypoint.entity.EmployeeProfile;
import com.waypoint.entity.Organization;
import com.waypoint.entity.ReportingAssignment;
import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.AuditActionType;
import com.waypoint.enums.NotificationType;
import com.waypoint.enums.RelationshipType;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.repository.EmployeeProfileRepository;
import com.waypoint.repository.OrganizationRepository;
import com.waypoint.repository.UserRepository;
import com.waypoint.security.MyHrAuthenticatedUserService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class MyHrAccountService {

    private static final Logger log = LoggerFactory.getLogger(MyHrAccountService.class);

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final EmployeeProfileRepository employeeProfileRepository;
    private final OrganizationEmployeeIdService organizationEmployeeIdService;
    private final ReportingAssignmentService reportingAssignmentService;
    private final TemporaryPasswordGenerator temporaryPasswordGenerator;
    private final PasswordEncoder passwordEncoder;
    private final MyHrAuthenticatedUserService authenticatedUserService;
    private final MyHrCredentialDelivery credentialDelivery;
    private final AuditService auditService;
    private final InAppNotificationService inAppNotificationService;

    public CreatedAccountResult createHr(String actorUserId, CreateHrAccountRequest request) {
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor = authenticatedUserService.require(
                actorUserId, Set.of(UserRole.COMPANY_ADMIN), false);
        return createWorkforceAccount(actor, request.getProfile(), UserRole.HR, actor.userId());
    }

    public CreatedAccountResult createEmployee(String actorUserId, CreateEmployeeAccountRequest request) {
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor = authenticatedUserService.require(
                actorUserId, Set.of(UserRole.COMPANY_ADMIN, UserRole.HR), false);
        return createWorkforceAccount(
                actor, request.getProfile(), UserRole.EMPLOYEE, request.getReportingManagerUserId());
    }

    public List<ReportingManagerOption> getEligibleReportingManagers(String actorUserId) {
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor = authenticatedUserService.require(
                actorUserId, Set.of(UserRole.COMPANY_ADMIN, UserRole.HR), false);

        return userRepository.findByOrganizationIdAndRoleAndAccountStatus(
                        actor.organizationId(), UserRole.HR, AccountStatus.ACTIVE).stream()
                .map(user -> {
                    EmployeeProfile profile = employeeProfileRepository.findByUserId(user.getId()).orElse(null);
                    return new ReportingManagerOption(
                            user.getId(), user.getLoginId(),
                            profile == null ? null : profile.getFirstName(),
                            profile == null ? null : profile.getLastName());
                })
                .toList();
    }

    private CreatedAccountResult createWorkforceAccount(
            MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor,
            EmployeeProfileFields fields,
            UserRole role,
            String reportingManagerUserId) {

        Organization organization = organizationRepository.findById(actor.organizationId())
                .orElseThrow(() -> new BusinessException("Organization not found"));
        String email = fields.getPersonalEmail().trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException("Email already registered");
        }

        String loginId = organizationEmployeeIdService.generateNextLoginId(actor.organizationId(), role);
        if (userRepository.existsByLoginId(loginId)) {
            throw new BusinessException("Generated login ID already exists");
        }

        String temporaryPassword = temporaryPasswordGenerator.generate();
        LocalDateTime now = LocalDateTime.now();
        User user = userRepository.save(User.builder()
                .loginId(loginId)
                .email(email)
                .passwordHash(passwordEncoder.encode(temporaryPassword))
                .role(role)
                .organizationId(actor.organizationId())
                .mustChangePassword(true)
                .isEmailVerified(true)
                .accountStatus(AccountStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build());

        EmployeeProfile profile;
        try {
            profile = employeeProfileRepository.save(buildProfile(
                    fields, user, loginId, actor.userId(), now));
        } catch (RuntimeException exception) {
            userRepository.deleteById(user.getId());
            throw exception;
        }

        if (role == UserRole.EMPLOYEE || role == UserRole.HR) {
            try {
                reportingAssignmentService.createActiveAssignment(ReportingAssignment.builder()
                        .organizationId(actor.organizationId())
                        .employeeUserId(user.getId())
                        .reportingManagerUserId(reportingManagerUserId)
                        .relationshipType(RelationshipType.PRIMARY_REPORTING_MANAGER)
                        .isActive(true)
                        .effectiveFrom(LocalDate.now())
                        .assignedByUserId(actor.userId())
                        .createdAt(now)
                        .updatedAt(now)
                        .build());
            } catch (RuntimeException exception) {
                employeeProfileRepository.deleteById(profile.getId());
                userRepository.deleteById(user.getId());
                throw exception;
            }
        }

        recordAccountCreated(actor, user, profile, role, loginId, reportingManagerUserId);
        notifyNewDirectReport(actor, user, profile, reportingManagerUserId);

        // Sent only now that everything is saved, to the validated address stored on the new account.
        // A creation request that repeats an email is rejected above, so no second email can be sent.
        boolean emailDelivered = credentialDelivery.deliver(
                user.getEmail(), role, organization.getOrganizationName(), loginId, temporaryPassword);
        temporaryPassword = null;

        return new CreatedAccountResult(user.getId(), profile.getId(), loginId, profile.getEmployeeId(), email,
                role, actor.organizationId(), true, emailDelivered);
    }

    private void recordAccountCreated(
            MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor,
            User user,
            EmployeeProfile profile,
            UserRole role,
            String loginId,
            String reportingManagerUserId) {
        String personType = role == UserRole.HR ? "HR" : "employee";
        Map<String, Object> created = new LinkedHashMap<>();
        created.put("loginId", loginId);
        created.put("employeeId", profile.getEmployeeId());
        created.put("role", role.name());
        created.put("designation", profile.getDesignation());

        auditService.record(actor,
                role == UserRole.HR ? AuditActionType.HR_ACCOUNT_CREATED : AuditActionType.EMPLOYEE_ACCOUNT_CREATED,
                "USER", user.getId(), profile.getName(),
                "Created " + personType + " account " + loginId + " for " + profile.getName(),
                null, created);

        if (reportingManagerUserId != null && !reportingManagerUserId.isBlank()) {
            auditService.record(actor, AuditActionType.REPORTING_MANAGER_ASSIGNED,
                    "USER", user.getId(), profile.getName(),
                    profile.getName() + " assigned a primary reporting manager at account creation",
                    null, Map.of("reportingManagerName", managerDisplayName(reportingManagerUserId)));
        }
    }

    private String managerDisplayName(String managerUserId) {
        return employeeProfileRepository.findByUserId(managerUserId)
                .map(EmployeeProfile::getName)
                .filter(name -> name != null && !name.isBlank())
                .or(() -> userRepository.findById(managerUserId).map(User::getLoginId))
                .orElse(managerUserId);
    }

    private String personaSegment(UserRole role) {
        if (role == UserRole.COMPANY_ADMIN) return "admin";
        if (role == UserRole.HR) return "hr";
        return "employee";
    }

    /**
     * Notifies the newly assigned reporting manager. Skipped when the "manager" is
     * the actor themselves - this happens for {@link #createHr}, where the creating
     * Company Admin always becomes the new HR's manager, and telling someone about
     * their own action is a pointless self-notification.
     */
    private void notifyNewDirectReport(
            MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor,
            User user,
            EmployeeProfile profile,
            String reportingManagerUserId) {
        if (reportingManagerUserId == null || reportingManagerUserId.isBlank()
                || reportingManagerUserId.equals(actor.userId())) {
            return;
        }
        User manager = userRepository.findById(reportingManagerUserId).orElse(null);
        if (manager == null) {
            return;
        }
        String employeeId = profile.getEmployeeId();
        String employeeLabel = employeeId == null || employeeId.isBlank()
                ? profile.getName() : profile.getName() + " (" + employeeId + ")";
        notifySafely(() -> inAppNotificationService.createNotification(actor.organizationId(), manager.getId(),
                NotificationType.EMPLOYEE_ASSIGNED, "New employee assigned",
                employeeLabel + " has been assigned to you as a direct report.",
                user.getId(), "/myhr/" + personaSegment(manager.getRole()) + "/employees"));
    }

    /**
     * Notification creation is secondary to account creation itself - a transient
     * failure here must never make an otherwise successful new-hire creation look
     * like it failed to the actor.
     */
    private void notifySafely(Runnable notification) {
        try {
            notification.run();
        } catch (RuntimeException failure) {
            log.warn("New-hire assignment notification could not be created", failure);
        }
    }

    private EmployeeProfile buildProfile(
            EmployeeProfileFields fields, User user, String employeeId, String actorUserId, LocalDateTime now) {
        return EmployeeProfile.builder()
                .userId(user.getId())
                .organizationId(user.getOrganizationId())
                .employeeId(employeeId)
                .firstName(fields.getFirstName().trim())
                .lastName(fields.getLastName().trim())
                .name(fullName(fields))
                .personalEmail(user.getEmail())
                .workEmail(normalizeOptionalEmail(fields.getWorkEmail()))
                .phoneNumber(fields.getPhoneNumber())
                .designation(fields.getDesignation().trim())
                .employmentType(fields.getEmploymentType())
                .startDate(fields.getStartDate())
                .state(fields.getState())
                .city(fields.getCity())
                .streetAddress(fields.getStreetAddress())
                .zipCode(fields.getZipCode())
                .createdByUserId(actorUserId)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private String fullName(EmployeeProfileFields fields) {
        return fields.getFirstName().trim() + " " + fields.getLastName().trim();
    }

    private String normalizeOptionalEmail(String email) {
        return email == null || email.isBlank() ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
