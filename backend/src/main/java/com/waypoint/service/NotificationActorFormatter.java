package com.waypoint.service;

import com.waypoint.entity.EmployeeProfile;
import com.waypoint.entity.Organization;
import com.waypoint.entity.User;
import com.waypoint.enums.UserRole;
import com.waypoint.repository.EmployeeProfileRepository;
import com.waypoint.repository.OrganizationRepository;
import com.waypoint.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * The one place that decides how a person is named inside a notification: {@code Full Name (Designation)}, for example
 * {@code Vasavi Anna (Company Admin)}. The name comes from the person's employee profile (or, for a Company Admin
 * without a profile, the organization's admin name) and the designation from their account role, both read from the
 * database. A login or employee ID is never shown, and a missing value is left out rather than printed as "null".
 */
@Slf4j
public class NotificationActorFormatter {

    /** The actor of a notification that no person triggered (scheduled jobs and the like). */
    public static final String SYSTEM_ACTOR = "WayPoint System";
    private static final String UNKNOWN_PERSON = "A team member";

    private final UserRepository userRepository;
    private final EmployeeProfileRepository employeeProfileRepository;
    private final OrganizationRepository organizationRepository;

    /**
     * Built by each service from the repositories it already has, so no service constructor changes.
     * {@code organizationRepository} may be null; it is only the fallback name source for a Company Admin without a profile.
     */
    public NotificationActorFormatter(UserRepository userRepository, EmployeeProfileRepository employeeProfileRepository,
                                      OrganizationRepository organizationRepository) {
        this.userRepository = userRepository;
        this.employeeProfileRepository = employeeProfileRepository;
        this.organizationRepository = organizationRepository;
    }

    /** Describes the user, looked up by id. Never throws: a lookup problem degrades to whatever is known. */
    public String describe(String userId) {
        if (userId == null || userId.isBlank()) return UNKNOWN_PERSON;
        try {
            User user = userRepository.findById(userId).orElse(null);
            EmployeeProfile profile = employeeProfileRepository.findByUserId(userId).orElse(null);
            return describe(user, profile);
        } catch (RuntimeException failure) {
            log.warn("Notification actor could not be resolved [exceptionType={}]", failure.getClass().getSimpleName());
            return UNKNOWN_PERSON;
        }
    }

    /** Describes an already loaded user and profile (either may be null). */
    public String describe(User user, EmployeeProfile profile) {
        return format(fullName(user, profile), user == null ? null : user.getRole());
    }

    /** The person's real full name, or null. Never a login ID, employee ID or database ID. */
    public String fullName(User user, EmployeeProfile profile) {
        String name = profileName(profile);
        if (name == null && user != null && user.getRole() == UserRole.COMPANY_ADMIN) name = organizationAdminName(user.getOrganizationId());
        return name;
    }

    /** The actor for a notification raised by the system rather than by a person. */
    public String system() {
        return SYSTEM_ACTOR;
    }

    /** {@code Name (Designation)}; just the name or just the designation when the other is unknown. */
    public static String format(String name, UserRole role) {
        String cleanName = clean(name);
        String designation = designation(role);
        if (cleanName != null && designation != null) return cleanName + " (" + designation + ")";
        if (cleanName != null) return cleanName;
        if (designation != null) return designation;
        return UNKNOWN_PERSON;
    }

    /** The designation shown to people for an account role, or null when the role is unknown or not a staff role. */
    public static String designation(UserRole role) {
        if (role == null) return null;
        return switch (role) {
            case COMPANY_ADMIN -> "Company Admin";
            case HR -> "HR";
            case EMPLOYEE -> "Employee";
            case WAYPOINT_ADMIN -> "Super Admin";
            default -> null;
        };
    }

    private static String profileName(EmployeeProfile profile) {
        if (profile == null) return null;
        String first = clean(profile.getFirstName());
        String last = clean(profile.getLastName());
        if (first != null || last != null) return clean((first == null ? "" : first) + " " + (last == null ? "" : last));
        return clean(profile.getName());
    }

    private String organizationAdminName(String organizationId) {
        if (organizationId == null || organizationRepository == null) return null;
        try {
            return organizationRepository.findById(organizationId).map(Organization::getAdminName)
                    .map(NotificationActorFormatter::clean).orElse(null);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static String clean(String value) {
        if (value == null) return null;
        String text = value.replaceAll("[\\r\\n\\p{Cntrl}]+", " ").trim();
        return text.isEmpty() || "null".equalsIgnoreCase(text) || "undefined".equalsIgnoreCase(text) ? null : text;
    }
}
