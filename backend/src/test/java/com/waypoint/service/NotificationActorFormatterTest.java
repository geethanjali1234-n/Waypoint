package com.waypoint.service;

import com.waypoint.entity.EmployeeProfile;
import com.waypoint.entity.Organization;
import com.waypoint.entity.User;
import com.waypoint.enums.UserRole;
import com.waypoint.repository.EmployeeProfileRepository;
import com.waypoint.repository.OrganizationRepository;
import com.waypoint.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationActorFormatterTest {

    private UserRepository users;
    private EmployeeProfileRepository profiles;
    private OrganizationRepository organizations;
    private NotificationActorFormatter formatter;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        profiles = mock(EmployeeProfileRepository.class);
        organizations = mock(OrganizationRepository.class);
        when(users.findById(anyString())).thenReturn(Optional.empty());
        when(profiles.findByUserId(anyString())).thenReturn(Optional.empty());
        when(organizations.findById(anyString())).thenReturn(Optional.empty());
        formatter = new NotificationActorFormatter(users, profiles, organizations);
    }

    private void person(String id, UserRole role, String first, String last) {
        when(users.findById(id)).thenReturn(Optional.of(User.builder().id(id).loginId("TARDU00" + id.length())
                .organizationId("org-1").role(role).build()));
        when(profiles.findByUserId(id)).thenReturn(Optional.of(EmployeeProfile.builder().userId(id)
                .employeeId("TARDU002").firstName(first).lastName(last).build()));
    }

    @Test
    void showsFullNameAndDesignationForEveryRole() {
        person("a", UserRole.COMPANY_ADMIN, "Vasavi", "Anna");
        person("h", UserRole.HR, "Vasavi", "Anna");
        person("e", UserRole.EMPLOYEE, "Mani Kumar", "Chinthakuntula");
        person("s", UserRole.WAYPOINT_ADMIN, "Sam", "Root");

        assertThat(formatter.describe("a")).isEqualTo("Vasavi Anna (Company Admin)");
        assertThat(formatter.describe("h")).isEqualTo("Vasavi Anna (HR)");
        assertThat(formatter.describe("e")).isEqualTo("Mani Kumar Chinthakuntula (Employee)");
        assertThat(formatter.describe("s")).isEqualTo("Sam Root (Super Admin)");
    }

    @Test
    void neverShowsEmployeeOrLoginIds() {
        person("e", UserRole.EMPLOYEE, "Mani", "Kumar");
        assertThat(formatter.describe("e")).doesNotContain("TARDU");
    }

    @Test
    void usesTheProfileDisplayNameWhenFirstAndLastNameAreMissing() {
        when(users.findById("e")).thenReturn(Optional.of(User.builder().id("e").role(UserRole.HR).build()));
        when(profiles.findByUserId("e")).thenReturn(Optional.of(EmployeeProfile.builder().userId("e").name("Pat Doe").build()));
        assertThat(formatter.describe("e")).isEqualTo("Pat Doe (HR)");
    }

    @Test
    void aCompanyAdminWithoutAProfileUsesTheOrganizationAdminName() {
        when(users.findById("a")).thenReturn(Optional.of(User.builder().id("a").loginId("TDADMIN001")
                .organizationId("org-1").role(UserRole.COMPANY_ADMIN).build()));
        when(organizations.findById("org-1")).thenReturn(Optional.of(Organization.builder().id("org-1").adminName("Vasavi Anna").build()));
        assertThat(formatter.describe("a")).isEqualTo("Vasavi Anna (Company Admin)");
    }

    @Test
    void missingValuesAreLeftOutRatherThanPrinted() {
        when(users.findById("e")).thenReturn(Optional.of(User.builder().id("e").loginId("TARDU009").role(UserRole.EMPLOYEE).build()));
        assertThat(formatter.describe("e")).isEqualTo("Employee");

        when(users.findById("n")).thenReturn(Optional.empty());
        when(profiles.findByUserId("n")).thenReturn(Optional.of(EmployeeProfile.builder().userId("n").firstName("Nia").lastName("null").build()));
        assertThat(formatter.describe("n")).isEqualTo("Nia");

        assertThat(formatter.describe("missing")).isEqualTo("A team member");
        assertThat(formatter.describe((String) null)).isEqualTo("A team member");
        assertThat(formatter.describe("e")).doesNotContain("null").doesNotContain("undefined").doesNotContain("TARDU");
    }

    @Test
    void aLookupFailureDegradesInsteadOfThrowing() {
        when(users.findById("boom")).thenThrow(new IllegalStateException("db down"));
        assertThat(formatter.describe("boom")).isEqualTo("A team member");
    }

    @Test
    void systemActionsUseTheWayPointSystemName() {
        assertThat(formatter.system()).isEqualTo("WayPoint System");
    }

    @Test
    void theSameActorReadsTheSameWhoeverReceivesIt() {
        person("h", UserRole.HR, "Vasavi", "Anna");
        assertThat(formatter.describe("h")).isEqualTo(formatter.describe("h"));
        assertThat(NotificationActorFormatter.format("Vasavi Anna", UserRole.HR)).isEqualTo("Vasavi Anna (HR)");
    }
}
