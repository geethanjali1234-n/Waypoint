package com.waypoint.service;

import static org.mockito.Mockito.lenient;
import com.waypoint.dto.myhr.CreateProfileChangeRequestRequest;
import com.waypoint.dto.myhr.ProfileChangeRequestDetailResponse;
import com.waypoint.dto.myhr.ProfileChangeRequestResponse;
import com.waypoint.dto.myhr.ReportingManagerSummary;
import com.waypoint.entity.EmployeeProfile;
import com.waypoint.entity.ProfileChangeRequest;
import com.waypoint.entity.ReportingAssignment;
import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.AuditActionType;
import com.waypoint.enums.EmploymentType;
import com.waypoint.enums.NotificationType;
import com.waypoint.enums.ProfileChangeField;
import com.waypoint.enums.ProfileChangeStatus;
import com.waypoint.enums.RelationshipType;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.repository.EmployeeProfileRepository;
import com.waypoint.repository.ProfileChangeRequestRepository;
import com.waypoint.repository.ReportingAssignmentRepository;
import com.waypoint.repository.UserRepository;
import com.waypoint.security.MyHrAuthenticatedUserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfileChangeRequestServiceTest {

    @Mock ProfileChangeRequestRepository profileChangeRequestRepository;
    @Mock EmployeeProfileRepository employeeProfileRepository;
    @Mock UserRepository userRepository;
    @Mock ReportingAssignmentRepository reportingAssignmentRepository;
    @Mock MyHrAuthenticatedUserService authenticatedUserService;
    @Mock AuditService auditService;
    @Mock InAppNotificationService inAppNotificationService;
    @Mock EmailService emailService;

    private ProfileChangeRequestService service() {
        return new ProfileChangeRequestService(
                profileChangeRequestRepository, employeeProfileRepository, userRepository,
                reportingAssignmentRepository, authenticatedUserService, auditService,
                TestOrchestrators.create(inAppNotificationService, emailService, new EmailTemplateService(),
                        userRepository, employeeProfileRepository));
    }

    /** A fully populated profile so every unrelated property has a distinct, checkable value. */
    private EmployeeProfile fullyPopulatedProfile() {
        return EmployeeProfile.builder()
                .id("profile-1")
                .userId("employee-1")
                .organizationId("organization-1")
                .employeeId("TD002")
                .name("Jordan Lee")
                .firstName("Jordan")
                .lastName("Lee")
                .personalEmail("jordan.personal@example.com")
                .workEmail("jordan@example.com")
                .phoneNumber("(555) 010-1000")
                .designation("Software Engineer")
                .hourlyRate(new BigDecimal("42.50"))
                .employmentType(EmploymentType.FULL_TIME)
                .startDate(LocalDate.of(2026, 3, 8))
                .state("IL")
                .city("Chicago")
                .streetAddress("200 Lake Street")
                .zipCode("60602")
                .profileDocumentId("document-1")
                .createdByUserId("admin-1")
                .createdAt(LocalDateTime.of(2026, 1, 1, 9, 0))
                .updatedAt(LocalDateTime.of(2026, 1, 1, 9, 0))
                .build();
    }

    private MyHrAuthenticatedUserService.AuthenticatedMyHrUser adminActor() {
        return new MyHrAuthenticatedUserService.AuthenticatedMyHrUser(
                "admin-1", "TDADMIN", "admin@tardus.test", UserRole.COMPANY_ADMIN,
                "organization-1", AccountStatus.ACTIVE, false);
    }

    private MyHrAuthenticatedUserService.AuthenticatedMyHrUser employeeActor() {
        return new MyHrAuthenticatedUserService.AuthenticatedMyHrUser(
                "employee-1", "TD002", "jordan.personal@example.com", UserRole.EMPLOYEE,
                "organization-1", AccountStatus.ACTIVE, false);
    }

    private MyHrAuthenticatedUserService.AuthenticatedMyHrUser hrActor() {
        return new MyHrAuthenticatedUserService.AuthenticatedMyHrUser(
                "hr-1", "TD001", "hr@tardus.test", UserRole.HR,
                "organization-1", AccountStatus.ACTIVE, false);
    }

    @Test
    void employeeSubmittingARequestCapturesCurrentValueFromTheirOwnProfileAndTheirActiveManager() {
        EmployeeProfile profile = fullyPopulatedProfile();
        CreateProfileChangeRequestRequest createRequest =
                new CreateProfileChangeRequestRequest(ProfileChangeField.PERSONAL_EMAIL, "new.email@example.com", "Changed banks.");

        when(authenticatedUserService.require(
                "employee-1", Set.of(UserRole.EMPLOYEE, UserRole.HR), false))
                .thenReturn(employeeActor());
        when(employeeProfileRepository.findByUserId("employee-1")).thenReturn(Optional.of(profile));
        when(reportingAssignmentRepository.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                "employee-1", RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(Optional.of(ReportingAssignment.builder().organizationId("organization-1")
                        .reportingManagerUserId("hr-1").build()));
        when(userRepository.findById("hr-1")).thenReturn(Optional.of(User.builder().id("hr-1")
                .organizationId("organization-1").role(UserRole.HR).accountStatus(AccountStatus.ACTIVE).build()));
        when(profileChangeRequestRepository.save(any(ProfileChangeRequest.class)))
                .thenAnswer(invocation -> {
                    ProfileChangeRequest request = invocation.getArgument(0);
                    request.setId("request-1");
                    return request;
                });

        service().create("employee-1", createRequest);

        ArgumentCaptor<ProfileChangeRequest> saved = ArgumentCaptor.forClass(ProfileChangeRequest.class);
        verify(profileChangeRequestRepository).save(saved.capture());
        ProfileChangeRequest request = saved.getValue();

        assertThat(request.getOrganizationId()).isEqualTo("organization-1");
        assertThat(request.getRequesterUserId()).isEqualTo("employee-1");
        assertThat(request.getProfileId()).isEqualTo("profile-1");
        assertThat(request.getAssignedToUserId()).isEqualTo("hr-1");
        assertThat(request.getFieldName()).isEqualTo(ProfileChangeField.PERSONAL_EMAIL);
        // existingValue comes from the employee's own current profile, never the client.
        assertThat(request.getExistingValue()).isEqualTo("jordan.personal@example.com");
        assertThat(request.getRequestedValue()).isEqualTo("new.email@example.com");
        assertThat(request.getRequestReason()).isEqualTo("Changed banks.");
        assertThat(request.getStatus()).isEqualTo(ProfileChangeStatus.PENDING);
    }

    @Test
    void employeesCannotRequestADesignationChange() {
        when(authenticatedUserService.require(
                "employee-1", Set.of(UserRole.EMPLOYEE, UserRole.HR), false))
                .thenReturn(employeeActor());
        when(employeeProfileRepository.findByUserId("employee-1")).thenReturn(Optional.of(fullyPopulatedProfile()));

        CreateProfileChangeRequestRequest createRequest =
                new CreateProfileChangeRequestRequest(ProfileChangeField.DESIGNATION, "Senior Engineer", null);

        assertThatThrownBy(() -> service().create("employee-1", createRequest))
                .isInstanceOf(BusinessException.class);

        verify(profileChangeRequestRepository, never()).save(any());
    }

    @Test
    void hrCannotRequestADesignationChangeForThemselves() {
        EmployeeProfile hrProfile = EmployeeProfile.builder()
                .id("profile-hr-1").userId("hr-1").organizationId("organization-1")
                .employeeId("TD001").name("Riley Chen").designation("HR Manager").build();
        CreateProfileChangeRequestRequest createRequest =
                new CreateProfileChangeRequestRequest(ProfileChangeField.DESIGNATION, "Senior HR Manager", null);

        when(authenticatedUserService.require(
                "hr-1", Set.of(UserRole.EMPLOYEE, UserRole.HR), false))
                .thenReturn(hrActor());
        when(employeeProfileRepository.findByUserId("hr-1")).thenReturn(Optional.of(hrProfile));
        assertThatThrownBy(() -> service().create("hr-1", createRequest))
                .isInstanceOf(BusinessException.class);
        verify(profileChangeRequestRepository, never()).save(any());
    }

    @Test
    void companyAdminCannotSubmitASelfServiceProfileChangeRequest() {
        when(authenticatedUserService.require(
                "admin-1", Set.of(UserRole.EMPLOYEE, UserRole.HR), false))
                .thenThrow(new ForbiddenException("You do not have access to this resource"));

        CreateProfileChangeRequestRequest createRequest =
                new CreateProfileChangeRequestRequest(ProfileChangeField.PHONE_NUMBER, "(555) 010-9999", null);

        assertThatThrownBy(() -> service().create("admin-1", createRequest))
                .isInstanceOf(ForbiddenException.class);

        verify(profileChangeRequestRepository, never()).save(any());
    }

    @Test
    void requestCreationFailsWhenNoActiveReportingManagerExists() {
        when(authenticatedUserService.require(
                "employee-1", Set.of(UserRole.EMPLOYEE, UserRole.HR), false))
                .thenReturn(employeeActor());
        when(employeeProfileRepository.findByUserId("employee-1")).thenReturn(Optional.of(fullyPopulatedProfile()));
        when(reportingAssignmentRepository.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                "employee-1", RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(Optional.empty());

        CreateProfileChangeRequestRequest createRequest =
                new CreateProfileChangeRequestRequest(ProfileChangeField.PHONE_NUMBER, "(555) 010-9999", null);

        assertThatThrownBy(() -> service().create("employee-1", createRequest))
                .isInstanceOf(BusinessException.class);
        verify(profileChangeRequestRepository, never()).save(any());
    }

    @Test
    void requestCreationFailsWhenTheAssignedManagerIsInactive() {
        when(authenticatedUserService.require(
                "employee-1", Set.of(UserRole.EMPLOYEE, UserRole.HR), false))
                .thenReturn(employeeActor());
        when(employeeProfileRepository.findByUserId("employee-1")).thenReturn(Optional.of(fullyPopulatedProfile()));
        when(reportingAssignmentRepository.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                "employee-1", RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(Optional.of(ReportingAssignment.builder().organizationId("organization-1")
                        .reportingManagerUserId("hr-1").build()));
        when(userRepository.findById("hr-1")).thenReturn(Optional.of(User.builder().id("hr-1")
                .organizationId("organization-1").role(UserRole.HR).accountStatus(AccountStatus.INACTIVE).build()));

        CreateProfileChangeRequestRequest createRequest =
                new CreateProfileChangeRequestRequest(ProfileChangeField.PHONE_NUMBER, "(555) 010-9999", null);

        assertThatThrownBy(() -> service().create("employee-1", createRequest))
                .isInstanceOf(BusinessException.class);
        verify(profileChangeRequestRepository, never()).save(any());
    }

    @Test
    void requestCreationFailsWhenTheAssignedManagerIsInAnotherOrganization() {
        when(authenticatedUserService.require(
                "employee-1", Set.of(UserRole.EMPLOYEE, UserRole.HR), false))
                .thenReturn(employeeActor());
        when(employeeProfileRepository.findByUserId("employee-1")).thenReturn(Optional.of(fullyPopulatedProfile()));
        when(reportingAssignmentRepository.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                "employee-1", RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(Optional.of(ReportingAssignment.builder().organizationId("organization-2")
                        .reportingManagerUserId("hr-1").build()));

        CreateProfileChangeRequestRequest createRequest =
                new CreateProfileChangeRequestRequest(ProfileChangeField.PHONE_NUMBER, "(555) 010-9999", null);

        assertThatThrownBy(() -> service().create("employee-1", createRequest))
                .isInstanceOf(BusinessException.class);
        verify(profileChangeRequestRepository, never()).save(any());
        verify(userRepository, never()).findById(any());
    }

    @Test
    void requestCreationFailsWhenTheResolvedReviewerHasAnInvalidRole() {
        when(authenticatedUserService.require(
                "employee-1", Set.of(UserRole.EMPLOYEE, UserRole.HR), false))
                .thenReturn(employeeActor());
        when(employeeProfileRepository.findByUserId("employee-1")).thenReturn(Optional.of(fullyPopulatedProfile()));
        when(reportingAssignmentRepository.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                "employee-1", RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(Optional.of(ReportingAssignment.builder().organizationId("organization-1")
                        .reportingManagerUserId("peer-1").build()));
        when(userRepository.findById("peer-1")).thenReturn(Optional.of(User.builder().id("peer-1")
                .organizationId("organization-1").role(UserRole.EMPLOYEE).accountStatus(AccountStatus.ACTIVE).build()));

        CreateProfileChangeRequestRequest createRequest =
                new CreateProfileChangeRequestRequest(ProfileChangeField.PHONE_NUMBER, "(555) 010-9999", null);

        assertThatThrownBy(() -> service().create("employee-1", createRequest))
                .isInstanceOf(BusinessException.class);
        verify(profileChangeRequestRepository, never()).save(any());
    }

    @Test
    void requestCreationFailsWhenTheEmployeeIsAssignedAsTheirOwnManager() {
        when(authenticatedUserService.require(
                "employee-1", Set.of(UserRole.EMPLOYEE, UserRole.HR), false))
                .thenReturn(employeeActor());
        when(employeeProfileRepository.findByUserId("employee-1")).thenReturn(Optional.of(fullyPopulatedProfile()));
        when(reportingAssignmentRepository.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                "employee-1", RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(Optional.of(ReportingAssignment.builder().organizationId("organization-1")
                        .reportingManagerUserId("employee-1").build()));

        CreateProfileChangeRequestRequest createRequest =
                new CreateProfileChangeRequestRequest(ProfileChangeField.PHONE_NUMBER, "(555) 010-9999", null);

        assertThatThrownBy(() -> service().create("employee-1", createRequest))
                .isInstanceOf(BusinessException.class);
        verify(profileChangeRequestRepository, never()).save(any());
        verify(userRepository, never()).findById(any());
    }

    @Test
    void createFailsWhenTheRequesterHasNoEmployeeProfile() {
        when(authenticatedUserService.require(
                "employee-1", Set.of(UserRole.EMPLOYEE, UserRole.HR), false))
                .thenReturn(employeeActor());
        when(employeeProfileRepository.findByUserId("employee-1")).thenReturn(Optional.empty());

        CreateProfileChangeRequestRequest createRequest =
                new CreateProfileChangeRequestRequest(ProfileChangeField.PHONE_NUMBER, "(555) 010-9999", null);

        assertThatThrownBy(() -> service().create("employee-1", createRequest))
                .isInstanceOf(BusinessException.class);

        verify(profileChangeRequestRepository, never()).save(any());
    }

    // Field validation happens before reviewer resolution in create(), so a
    // validation-failure test never reaches the reporting-manager lookups -
    // only the actor/profile stubs are needed there.
    private void stubValidReviewer() {
        when(authenticatedUserService.require(
                "employee-1", Set.of(UserRole.EMPLOYEE, UserRole.HR), false))
                .thenReturn(employeeActor());
        when(employeeProfileRepository.findByUserId("employee-1")).thenReturn(Optional.of(fullyPopulatedProfile()));
    }

    private void stubValidReviewerResolution() {
        when(reportingAssignmentRepository.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                "employee-1", RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(Optional.of(ReportingAssignment.builder().organizationId("organization-1")
                        .reportingManagerUserId("hr-1").build()));
        when(userRepository.findById("hr-1")).thenReturn(Optional.of(User.builder().id("hr-1")
                .organizationId("organization-1").role(UserRole.HR).accountStatus(AccountStatus.ACTIVE).build()));
    }

    @Test
    void nameRequestRejectsAMissingLastName() {
        stubValidReviewer();
        CreateProfileChangeRequestRequest createRequest = new CreateProfileChangeRequestRequest(
                ProfileChangeField.NAME, Map.of("firstName", "Jordan"), null);

        assertThatThrownBy(() -> service().create("employee-1", createRequest))
                .isInstanceOf(BusinessException.class);
        verify(profileChangeRequestRepository, never()).save(any());
    }

    @Test
    void nameRequestRejectsAnExtraUnknownKey() {
        stubValidReviewer();
        CreateProfileChangeRequestRequest createRequest = new CreateProfileChangeRequestRequest(
                ProfileChangeField.NAME, Map.of("firstName", "Jordan", "lastName", "Lee", "middleName", "R"), null);

        assertThatThrownBy(() -> service().create("employee-1", createRequest))
                .isInstanceOf(BusinessException.class);
        verify(profileChangeRequestRepository, never()).save(any());
    }

    @Test
    void nameRequestRejectsABlankFirstName() {
        stubValidReviewer();
        CreateProfileChangeRequestRequest createRequest = new CreateProfileChangeRequestRequest(
                ProfileChangeField.NAME, Map.of("firstName", "   ", "lastName", "Lee"), null);

        assertThatThrownBy(() -> service().create("employee-1", createRequest))
                .isInstanceOf(BusinessException.class);
        verify(profileChangeRequestRepository, never()).save(any());
    }

    @Test
    void nameRequestSucceedsWithTrimmedFirstAndLastName() {
        stubValidReviewer();
        stubValidReviewerResolution();
        when(profileChangeRequestRepository.save(any(ProfileChangeRequest.class)))
                .thenAnswer(invocation -> {
                    ProfileChangeRequest request = invocation.getArgument(0);
                    request.setId("request-1");
                    return request;
                });
        CreateProfileChangeRequestRequest createRequest = new CreateProfileChangeRequestRequest(
                ProfileChangeField.NAME, Map.of("firstName", "  Jordan  ", "lastName", "  Lee  "), null);

        service().create("employee-1", createRequest);

        ArgumentCaptor<ProfileChangeRequest> saved = ArgumentCaptor.forClass(ProfileChangeRequest.class);
        verify(profileChangeRequestRepository).save(saved.capture());
        assertThat(saved.getValue().getRequestedValue()).isEqualTo(Map.of("firstName", "Jordan", "lastName", "Lee"));
    }

    @Test
    void personalEmailRequestRejectsAnInvalidFormat() {
        stubValidReviewer();
        CreateProfileChangeRequestRequest createRequest = new CreateProfileChangeRequestRequest(
                ProfileChangeField.PERSONAL_EMAIL, "not-an-email", null);

        assertThatThrownBy(() -> service().create("employee-1", createRequest))
                .isInstanceOf(BusinessException.class);
        verify(profileChangeRequestRepository, never()).save(any());
    }

    @Test
    void personalEmailRequestRejectsAnArbitraryObjectInsteadOfAString() {
        stubValidReviewer();
        CreateProfileChangeRequestRequest createRequest = new CreateProfileChangeRequestRequest(
                ProfileChangeField.PERSONAL_EMAIL, Map.of("value", "new.email@example.com"), null);

        assertThatThrownBy(() -> service().create("employee-1", createRequest))
                .isInstanceOf(BusinessException.class);
        verify(profileChangeRequestRepository, never()).save(any());
    }

    @Test
    void phoneNumberRequestRejectsAnInvalidFormat() {
        stubValidReviewer();
        CreateProfileChangeRequestRequest createRequest = new CreateProfileChangeRequestRequest(
                ProfileChangeField.PHONE_NUMBER, "not-a-phone-number!!", null);

        assertThatThrownBy(() -> service().create("employee-1", createRequest))
                .isInstanceOf(BusinessException.class);
        verify(profileChangeRequestRepository, never()).save(any());
    }

    @Test
    void phoneNumberRequestRejectsAnArbitraryObjectInsteadOfAString() {
        stubValidReviewer();
        CreateProfileChangeRequestRequest createRequest = new CreateProfileChangeRequestRequest(
                ProfileChangeField.PHONE_NUMBER, List.of("555", "010", "9999"), null);

        assertThatThrownBy(() -> service().create("employee-1", createRequest))
                .isInstanceOf(BusinessException.class);
        verify(profileChangeRequestRepository, never()).save(any());
    }

    @Test
    void addressRequestRejectsAnUnknownKey() {
        stubValidReviewer();
        CreateProfileChangeRequestRequest createRequest = new CreateProfileChangeRequestRequest(
                ProfileChangeField.ADDRESS, Map.of("streetAddress", "500 Riverside Drive", "country", "USA"), null);

        assertThatThrownBy(() -> service().create("employee-1", createRequest))
                .isInstanceOf(BusinessException.class);
        verify(profileChangeRequestRepository, never()).save(any());
    }

    @Test
    void addressRequestRejectsAnEmptyMap() {
        stubValidReviewer();
        CreateProfileChangeRequestRequest createRequest =
                new CreateProfileChangeRequestRequest(ProfileChangeField.ADDRESS, Map.of(), null);

        assertThatThrownBy(() -> service().create("employee-1", createRequest))
                .isInstanceOf(BusinessException.class);
        verify(profileChangeRequestRepository, never()).save(any());
    }

    @Test
    void addressRequestAcceptsAPartialUpdateOfOnlyAllowedKeys() {
        stubValidReviewer();
        stubValidReviewerResolution();
        when(profileChangeRequestRepository.save(any(ProfileChangeRequest.class)))
                .thenAnswer(invocation -> {
                    ProfileChangeRequest request = invocation.getArgument(0);
                    request.setId("request-1");
                    return request;
                });
        CreateProfileChangeRequestRequest createRequest = new CreateProfileChangeRequestRequest(
                ProfileChangeField.ADDRESS, Map.of("zipCode", "60603"), null);

        service().create("employee-1", createRequest);

        ArgumentCaptor<ProfileChangeRequest> saved = ArgumentCaptor.forClass(ProfileChangeRequest.class);
        verify(profileChangeRequestRepository).save(saved.capture());
        assertThat(saved.getValue().getRequestedValue()).isEqualTo(Map.of("zipCode", "60603"));
    }

    @Test
    void requestReasonLongerThanOneThousandCharactersIsRejected() {
        stubValidReviewer();
        String tooLong = "x".repeat(1001);
        CreateProfileChangeRequestRequest createRequest =
                new CreateProfileChangeRequestRequest(ProfileChangeField.PHONE_NUMBER, "(555) 010-9999", tooLong);

        assertThatThrownBy(() -> service().create("employee-1", createRequest))
                .isInstanceOf(BusinessException.class);
        verify(profileChangeRequestRepository, never()).save(any());
    }

    @Test
    void createRejectsASecondPendingRequestForTheSameField() {
        when(authenticatedUserService.require(
                "employee-1", Set.of(UserRole.EMPLOYEE, UserRole.HR), false))
                .thenReturn(employeeActor());
        when(employeeProfileRepository.findByUserId("employee-1")).thenReturn(Optional.of(fullyPopulatedProfile()));
        when(profileChangeRequestRepository.existsByOrganizationIdAndRequesterUserIdAndFieldNameAndStatus(
                "organization-1", "employee-1", ProfileChangeField.PHONE_NUMBER, ProfileChangeStatus.PENDING))
                .thenReturn(true);

        CreateProfileChangeRequestRequest createRequest =
                new CreateProfileChangeRequestRequest(ProfileChangeField.PHONE_NUMBER, "(555) 010-9999", null);

        assertThatThrownBy(() -> service().create("employee-1", createRequest))
                .isInstanceOf(BusinessException.class);
        verify(profileChangeRequestRepository, never()).save(any());
        // The reviewer-resolution path is never reached once the duplicate check fails.
        verify(reportingAssignmentRepository, never()).findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(any(), any());
    }

    @Test
    void createAllowsASimultaneousPendingRequestForADifferentField() {
        when(authenticatedUserService.require(
                "employee-1", Set.of(UserRole.EMPLOYEE, UserRole.HR), false))
                .thenReturn(employeeActor());
        when(employeeProfileRepository.findByUserId("employee-1")).thenReturn(Optional.of(fullyPopulatedProfile()));
        when(profileChangeRequestRepository.existsByOrganizationIdAndRequesterUserIdAndFieldNameAndStatus(
                "organization-1", "employee-1", ProfileChangeField.PERSONAL_EMAIL, ProfileChangeStatus.PENDING))
                .thenReturn(false);
        when(reportingAssignmentRepository.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                "employee-1", RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(Optional.of(ReportingAssignment.builder().organizationId("organization-1")
                        .reportingManagerUserId("hr-1").build()));
        when(userRepository.findById("hr-1")).thenReturn(Optional.of(User.builder().id("hr-1")
                .organizationId("organization-1").role(UserRole.HR).accountStatus(AccountStatus.ACTIVE).build()));
        when(profileChangeRequestRepository.save(any(ProfileChangeRequest.class)))
                .thenAnswer(invocation -> {
                    ProfileChangeRequest request = invocation.getArgument(0);
                    request.setId("request-2");
                    return request;
                });

        CreateProfileChangeRequestRequest createRequest =
                new CreateProfileChangeRequestRequest(ProfileChangeField.PERSONAL_EMAIL, "new.email@example.com", null);

        service().create("employee-1", createRequest);

        verify(profileChangeRequestRepository).save(any(ProfileChangeRequest.class));
    }

    @Test
    void createSucceedsForAFieldAfterThePriorRequestReachedATerminalState() {
        // existsBy...Status(..., PENDING) only ever matches PENDING rows, so an
        // APPROVED/REJECTED prior request for the same field never blocks a new one.
        when(authenticatedUserService.require(
                "employee-1", Set.of(UserRole.EMPLOYEE, UserRole.HR), false))
                .thenReturn(employeeActor());
        when(employeeProfileRepository.findByUserId("employee-1")).thenReturn(Optional.of(fullyPopulatedProfile()));
        when(profileChangeRequestRepository.existsByOrganizationIdAndRequesterUserIdAndFieldNameAndStatus(
                "organization-1", "employee-1", ProfileChangeField.PHONE_NUMBER, ProfileChangeStatus.PENDING))
                .thenReturn(false);
        when(reportingAssignmentRepository.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                "employee-1", RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(Optional.of(ReportingAssignment.builder().organizationId("organization-1")
                        .reportingManagerUserId("hr-1").build()));
        when(userRepository.findById("hr-1")).thenReturn(Optional.of(User.builder().id("hr-1")
                .organizationId("organization-1").role(UserRole.HR).accountStatus(AccountStatus.ACTIVE).build()));
        when(profileChangeRequestRepository.save(any(ProfileChangeRequest.class)))
                .thenAnswer(invocation -> {
                    ProfileChangeRequest request = invocation.getArgument(0);
                    request.setId("request-3");
                    return request;
                });

        CreateProfileChangeRequestRequest createRequest =
                new CreateProfileChangeRequestRequest(ProfileChangeField.PHONE_NUMBER, "(555) 010-9999", null);

        service().create("employee-1", createRequest);

        verify(profileChangeRequestRepository).save(any(ProfileChangeRequest.class));
    }

    @Test
    void createEmitsProfileChangeRequestedAuditWithoutThePersonalValues() {
        when(authenticatedUserService.require(
                "employee-1", Set.of(UserRole.EMPLOYEE, UserRole.HR), false))
                .thenReturn(employeeActor());
        when(employeeProfileRepository.findByUserId("employee-1")).thenReturn(Optional.of(fullyPopulatedProfile()));
        when(reportingAssignmentRepository.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                "employee-1", RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(Optional.of(ReportingAssignment.builder().organizationId("organization-1")
                        .reportingManagerUserId("hr-1").build()));
        when(userRepository.findById("hr-1")).thenReturn(Optional.of(User.builder().id("hr-1")
                .organizationId("organization-1").role(UserRole.HR).accountStatus(AccountStatus.ACTIVE).build()));
        when(profileChangeRequestRepository.save(any(ProfileChangeRequest.class)))
                .thenAnswer(invocation -> {
                    ProfileChangeRequest request = invocation.getArgument(0);
                    request.setId("request-1");
                    return request;
                });

        CreateProfileChangeRequestRequest createRequest = new CreateProfileChangeRequestRequest(
                ProfileChangeField.PERSONAL_EMAIL, "new.email@example.com", "Changed banks.");

        service().create("employee-1", createRequest);

        ArgumentCaptor<AuditActionType> actionCaptor = ArgumentCaptor.forClass(AuditActionType.class);
        ArgumentCaptor<Object> previousValueCaptor = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> updatedValueCaptor = ArgumentCaptor.forClass(Object.class);
        verify(auditService).record(any(), actionCaptor.capture(), eq("PROFILE_CHANGE_REQUEST"), eq("request-1"),
                any(), any(), previousValueCaptor.capture(), updatedValueCaptor.capture());
        assertThat(actionCaptor.getValue()).isEqualTo(AuditActionType.PROFILE_CHANGE_REQUESTED);
        @SuppressWarnings("unchecked")
        Map<String, Object> previousValue = (Map<String, Object>) previousValueCaptor.getValue();
        assertThat(previousValue).containsEntry("fieldName", "PERSONAL_EMAIL");
        @SuppressWarnings("unchecked")
        Map<String, Object> updatedValue = (Map<String, Object>) updatedValueCaptor.getValue();
        assertThat(updatedValue).containsEntry("fieldName", "PERSONAL_EMAIL")
                .containsEntry("status", "PENDING")
                .containsEntry("value", "new.email@example.com");
    }

    @Test
    void listMineReturnsOnlyTheRequestersOwnRequestsWithRealDisplayInformation() {
        ProfileChangeRequest request = ProfileChangeRequest.builder()
                .id("request-1")
                .organizationId("organization-1")
                .requesterUserId("employee-1")
                .assignedToUserId("hr-1")
                .fieldName(ProfileChangeField.PHONE_NUMBER)
                .existingValue("(555) 010-1000")
                .requestedValue("(555) 010-9999")
                .status(ProfileChangeStatus.PENDING)
                .requestedAt(LocalDateTime.of(2026, 8, 25, 13, 10))
                .build();
        EmployeeProfile requesterProfile = fullyPopulatedProfile();
        EmployeeProfile managerProfile = EmployeeProfile.builder()
                .userId("hr-1").employeeId("TD001").name("Riley Chen").designation("HR Manager").build();

        when(authenticatedUserService.require(
                "employee-1", Set.of(UserRole.EMPLOYEE, UserRole.HR), false))
                .thenReturn(employeeActor());
        when(profileChangeRequestRepository.findByOrganizationIdAndRequesterUserIdOrderByRequestedAtDesc(
                "organization-1", "employee-1")).thenReturn(List.of(request));
        when(employeeProfileRepository.findByUserIdIn(any())).thenReturn(List.of(requesterProfile, managerProfile));

        List<ProfileChangeRequestResponse> results = service().listMine("employee-1");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).employeeId()).isEqualTo("TD002");
        assertThat(results.get(0).employeeName()).isEqualTo("Jordan Lee");
        assertThat(results.get(0).reportingManager().employeeId()).isEqualTo("TD001");
        assertThat(results.get(0).reportingManager().name()).isEqualTo("Riley Chen");
    }

    @Test
    void approvingOneFieldLeavesEveryUnrelatedEmployeeProfilePropertyUnchanged() {
        EmployeeProfile profile = fullyPopulatedProfile();
        ProfileChangeRequest request = ProfileChangeRequest.builder()
                .id("request-1")
                .organizationId("organization-1")
                .requesterUserId("employee-1")
                .assignedToUserId("admin-1")
                .fieldName(ProfileChangeField.PHONE_NUMBER)
                .existingValue("(555) 010-1000")
                .requestedValue("(555) 010-9999")
                .status(ProfileChangeStatus.PENDING)
                .requestedAt(LocalDateTime.of(2026, 8, 25, 13, 10))
                .build();

        when(authenticatedUserService.require(
                "admin-1", Set.of(UserRole.COMPANY_ADMIN, UserRole.HR), false))
                .thenReturn(adminActor());
        when(profileChangeRequestRepository.findById("request-1")).thenReturn(Optional.of(request));
        when(employeeProfileRepository.findByUserId("employee-1")).thenReturn(Optional.of(profile));
        when(employeeProfileRepository.save(any(EmployeeProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(profileChangeRequestRepository.save(any(ProfileChangeRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service().approve("admin-1", "request-1", "Verified with employee.");

        ArgumentCaptor<EmployeeProfile> savedProfile = ArgumentCaptor.forClass(EmployeeProfile.class);
        verify(employeeProfileRepository).save(savedProfile.capture());
        EmployeeProfile saved = savedProfile.getValue();

        // Only the requested property changed.
        assertThat(saved.getPhoneNumber()).isEqualTo("+15550109999");

        // Every other EmployeeProfile property is exactly what it was before approval -
        // never nulled, blanked, or overwritten with the requested value.
        assertThat(saved.getId()).isEqualTo("profile-1");
        assertThat(saved.getUserId()).isEqualTo("employee-1");
        assertThat(saved.getOrganizationId()).isEqualTo("organization-1");
        assertThat(saved.getEmployeeId()).isEqualTo("TD002");
        assertThat(saved.getName()).isEqualTo("Jordan Lee");
        assertThat(saved.getFirstName()).isEqualTo("Jordan");
        assertThat(saved.getLastName()).isEqualTo("Lee");
        assertThat(saved.getPersonalEmail()).isEqualTo("jordan.personal@example.com");
        assertThat(saved.getWorkEmail()).isEqualTo("jordan@example.com");
        assertThat(saved.getDesignation()).isEqualTo("Software Engineer");
        assertThat(saved.getHourlyRate()).isEqualByComparingTo("42.50");
        assertThat(saved.getEmploymentType()).isEqualTo(EmploymentType.FULL_TIME);
        assertThat(saved.getStartDate()).isEqualTo(LocalDate.of(2026, 3, 8));
        assertThat(saved.getState()).isEqualTo("IL");
        assertThat(saved.getCity()).isEqualTo("Chicago");
        assertThat(saved.getStreetAddress()).isEqualTo("200 Lake Street");
        assertThat(saved.getZipCode()).isEqualTo("60602");
        assertThat(saved.getProfileDocumentId()).isEqualTo("document-1");
        assertThat(saved.getCreatedByUserId()).isEqualTo("admin-1");
        assertThat(saved.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 1, 1, 9, 0));

        // The request itself is what got fully decided.
        ArgumentCaptor<ProfileChangeRequest> savedRequest = ArgumentCaptor.forClass(ProfileChangeRequest.class);
        verify(profileChangeRequestRepository).save(savedRequest.capture());
        assertThat(savedRequest.getValue().getStatus()).isEqualTo(ProfileChangeStatus.APPROVED);
        assertThat(savedRequest.getValue().getProcessedByUserId()).isEqualTo("admin-1");
        assertThat(savedRequest.getValue().getProcessingComment()).isEqualTo("Verified with employee.");
    }

    @Test
    void approvingAnAddressRequestOnlyChangesTheSuppliedAddressFields() {
        EmployeeProfile profile = fullyPopulatedProfile();
        ProfileChangeRequest request = ProfileChangeRequest.builder()
                .id("request-2")
                .organizationId("organization-1")
                .requesterUserId("employee-1")
                .fieldName(ProfileChangeField.ADDRESS)
                .existingValue(Map.of("streetAddress", "200 Lake Street", "city", "Chicago",
                        "state", "IL", "zipCode", "60602"))
                // Only the street is being changed - city/state/zip are intentionally omitted.
                .requestedValue(Map.of("streetAddress", "500 Riverside Drive"))
                .status(ProfileChangeStatus.PENDING)
                .requestedAt(LocalDateTime.of(2026, 8, 25, 13, 10))
                .build();

        when(authenticatedUserService.require(
                "admin-1", Set.of(UserRole.COMPANY_ADMIN, UserRole.HR), false))
                .thenReturn(adminActor());
        when(profileChangeRequestRepository.findById("request-2")).thenReturn(Optional.of(request));
        when(employeeProfileRepository.findByUserId("employee-1")).thenReturn(Optional.of(profile));
        when(employeeProfileRepository.save(any(EmployeeProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(profileChangeRequestRepository.save(any(ProfileChangeRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service().approve("admin-1", "request-2", null);

        ArgumentCaptor<EmployeeProfile> savedProfile = ArgumentCaptor.forClass(EmployeeProfile.class);
        verify(employeeProfileRepository).save(savedProfile.capture());
        EmployeeProfile saved = savedProfile.getValue();

        assertThat(saved.getStreetAddress()).isEqualTo("500 Riverside Drive");
        // city/state/zip were not part of the request and must be untouched.
        assertThat(saved.getCity()).isEqualTo("Chicago");
        assertThat(saved.getState()).isEqualTo("IL");
        assertThat(saved.getZipCode()).isEqualTo("60602");
        // A wholly unrelated property is also untouched.
        assertThat(saved.getDesignation()).isEqualTo("Software Engineer");
    }

    @Test
    void approvalRejectsAndDoesNotModifyTheProfileWhenTheBaselineHasChangedSinceSubmission() {
        // Profile was already changed to a different phone number after the
        // request was submitted - existingValue no longer matches reality.
        EmployeeProfile profile = fullyPopulatedProfile();
        profile.setPhoneNumber("(555) 999-0000");
        ProfileChangeRequest request = ProfileChangeRequest.builder()
                .id("request-1")
                .organizationId("organization-1")
                .requesterUserId("employee-1")
                .assignedToUserId("admin-1")
                .fieldName(ProfileChangeField.PHONE_NUMBER)
                .existingValue("(555) 010-1000")
                .requestedValue("(555) 010-9999")
                .status(ProfileChangeStatus.PENDING)
                .build();

        when(authenticatedUserService.require(
                "admin-1", Set.of(UserRole.COMPANY_ADMIN, UserRole.HR), false))
                .thenReturn(adminActor());
        when(profileChangeRequestRepository.findById("request-1")).thenReturn(Optional.of(request));
        when(employeeProfileRepository.findByUserId("employee-1")).thenReturn(Optional.of(profile));

        assertThatThrownBy(() -> service().approve("admin-1", "request-1", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("changed after this request was submitted");

        // Neither the profile nor the request was ever saved - and the request
        // status was never advanced to APPROVED.
        verify(employeeProfileRepository, never()).save(any());
        verify(profileChangeRequestRepository, never()).save(any());
        assertThat(request.getStatus()).isEqualTo(ProfileChangeStatus.PENDING);
        verify(auditService, never()).record(any(), eq(AuditActionType.PROFILE_CHANGE_APPROVED), any(), any(), any(), any(), any(), any());
    }

    @Test
    void approvalSucceedsWhenTheBaselineIsUnchangedSinceSubmission() {
        EmployeeProfile profile = fullyPopulatedProfile();
        ProfileChangeRequest request = ProfileChangeRequest.builder()
                .id("request-1")
                .organizationId("organization-1")
                .requesterUserId("employee-1")
                .assignedToUserId("admin-1")
                .fieldName(ProfileChangeField.PHONE_NUMBER)
                .existingValue("(555) 010-1000")
                .requestedValue("(555) 010-9999")
                .status(ProfileChangeStatus.PENDING)
                .build();

        when(authenticatedUserService.require(
                "admin-1", Set.of(UserRole.COMPANY_ADMIN, UserRole.HR), false))
                .thenReturn(adminActor());
        when(profileChangeRequestRepository.findById("request-1")).thenReturn(Optional.of(request));
        when(employeeProfileRepository.findByUserId("employee-1")).thenReturn(Optional.of(profile));
        when(employeeProfileRepository.save(any(EmployeeProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(profileChangeRequestRepository.save(any(ProfileChangeRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ProfileChangeRequest result = service().approve("admin-1", "request-1", null);

        assertThat(result.getStatus()).isEqualTo(ProfileChangeStatus.APPROVED);
        verify(employeeProfileRepository).save(any(EmployeeProfile.class));
    }

    @Test
    void approvalRestoresThePriorProfileValueWhenSavingTheApprovedRequestFails() {
        EmployeeProfile profile = fullyPopulatedProfile();
        ProfileChangeRequest request = ProfileChangeRequest.builder()
                .id("request-1")
                .organizationId("organization-1")
                .requesterUserId("employee-1")
                .assignedToUserId("admin-1")
                .fieldName(ProfileChangeField.PHONE_NUMBER)
                .existingValue("(555) 010-1000")
                .requestedValue("(555) 010-9999")
                .status(ProfileChangeStatus.PENDING)
                .build();

        when(authenticatedUserService.require(
                "admin-1", Set.of(UserRole.COMPANY_ADMIN, UserRole.HR), false))
                .thenReturn(adminActor());
        when(profileChangeRequestRepository.findById("request-1")).thenReturn(Optional.of(request));
        when(employeeProfileRepository.findByUserId("employee-1")).thenReturn(Optional.of(profile));
        when(employeeProfileRepository.save(any(EmployeeProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(profileChangeRequestRepository.save(any(ProfileChangeRequest.class)))
                .thenThrow(new RuntimeException("mongo write conflict"));

        assertThatThrownBy(() -> service().approve("admin-1", "request-1", "Verified"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("mongo write conflict");

        // The profile was saved twice: once with the approved value, once to
        // restore the original value after the request save failed.
        ArgumentCaptor<EmployeeProfile> profileCaptor = ArgumentCaptor.forClass(EmployeeProfile.class);
        verify(employeeProfileRepository, org.mockito.Mockito.times(2)).save(profileCaptor.capture());
        EmployeeProfile restored = profileCaptor.getAllValues().get(1);
        assertThat(restored.getPhoneNumber()).isEqualTo("(555) 010-1000");

        // The failed save was never followed by an approval audit event.
        verify(auditService, never()).record(any(), eq(AuditActionType.PROFILE_CHANGE_APPROVED), any(), any(), any(), any(), any(), any());
    }

    @Test
    void approvalPropagatesTheOriginalFailureEvenWhenProfileRestorationAlsoFails() {
        EmployeeProfile profile = fullyPopulatedProfile();
        ProfileChangeRequest request = ProfileChangeRequest.builder()
                .id("request-1")
                .organizationId("organization-1")
                .requesterUserId("employee-1")
                .assignedToUserId("admin-1")
                .fieldName(ProfileChangeField.PHONE_NUMBER)
                .existingValue("(555) 010-1000")
                .requestedValue("(555) 010-9999")
                .status(ProfileChangeStatus.PENDING)
                .build();

        when(authenticatedUserService.require(
                "admin-1", Set.of(UserRole.COMPANY_ADMIN, UserRole.HR), false))
                .thenReturn(adminActor());
        when(profileChangeRequestRepository.findById("request-1")).thenReturn(Optional.of(request));
        when(employeeProfileRepository.findByUserId("employee-1")).thenReturn(Optional.of(profile));
        when(employeeProfileRepository.save(any(EmployeeProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0))
                .thenThrow(new RuntimeException("restoration also failed"));
        when(profileChangeRequestRepository.save(any(ProfileChangeRequest.class)))
                .thenThrow(new RuntimeException("mongo write conflict"));

        // The original failure is what propagates - the compensation failure is
        // logged/suppressed, never masking the real cause.
        assertThatThrownBy(() -> service().approve("admin-1", "request-1", "Verified"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("mongo write conflict");
    }

    @Test
    void approvalEmitsProfileChangeApprovedAuditOnlyAfterBothSavesSucceed() {
        EmployeeProfile profile = fullyPopulatedProfile();
        ProfileChangeRequest request = ProfileChangeRequest.builder()
                .id("request-1")
                .organizationId("organization-1")
                .requesterUserId("employee-1")
                .assignedToUserId("admin-1")
                .fieldName(ProfileChangeField.PHONE_NUMBER)
                .existingValue("(555) 010-1000")
                .requestedValue("(555) 010-9999")
                .status(ProfileChangeStatus.PENDING)
                .build();

        when(authenticatedUserService.require(
                "admin-1", Set.of(UserRole.COMPANY_ADMIN, UserRole.HR), false))
                .thenReturn(adminActor());
        when(profileChangeRequestRepository.findById("request-1")).thenReturn(Optional.of(request));
        when(employeeProfileRepository.findByUserId("employee-1")).thenReturn(Optional.of(profile));
        when(employeeProfileRepository.save(any(EmployeeProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(profileChangeRequestRepository.save(any(ProfileChangeRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service().approve("admin-1", "request-1", "Verified with employee.");

        ArgumentCaptor<AuditActionType> actionCaptor = ArgumentCaptor.forClass(AuditActionType.class);
        ArgumentCaptor<Object> metadataCaptor = ArgumentCaptor.forClass(Object.class);
        verify(auditService).record(any(), actionCaptor.capture(), eq("PROFILE_CHANGE_REQUEST"), eq("request-1"),
                any(), any(), any(), metadataCaptor.capture());
        assertThat(actionCaptor.getValue()).isEqualTo(AuditActionType.PROFILE_CHANGE_APPROVED);
        @SuppressWarnings("unchecked")
        Map<String, Object> updatedValue = (Map<String, Object>) metadataCaptor.getValue();
        assertThat(updatedValue).containsEntry("fieldName", "PHONE_NUMBER")
                .containsEntry("status", "APPROVED")
                .containsEntry("value", "(555) 010-9999");
        // PROFILE_CHANGED is never emitted alongside PROFILE_CHANGE_APPROVED for this operation.
        verify(auditService, never()).record(any(), eq(AuditActionType.PROFILE_CHANGED), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectingARequestNeverLoadsOrSavesEmployeeProfile() {
        ProfileChangeRequest request = ProfileChangeRequest.builder()
                .id("request-3")
                .organizationId("organization-1")
                .requesterUserId("employee-1")
                .fieldName(ProfileChangeField.DESIGNATION)
                .existingValue("Designer")
                .requestedValue("Senior Designer")
                .status(ProfileChangeStatus.PENDING)
                .requestedAt(LocalDateTime.of(2026, 8, 25, 13, 10))
                .build();

        when(authenticatedUserService.require(
                "admin-1", Set.of(UserRole.COMPANY_ADMIN, UserRole.HR), false))
                .thenReturn(adminActor());
        when(profileChangeRequestRepository.findById("request-3")).thenReturn(Optional.of(request));
        when(profileChangeRequestRepository.save(any(ProfileChangeRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ProfileChangeRequest result = service().reject("admin-1", "request-3", "Needs verification.");

        assertThat(result.getStatus()).isEqualTo(ProfileChangeStatus.REJECTED);
        assertThat(result.getProcessedByUserId()).isEqualTo("admin-1");
        assertThat(result.getProcessingComment()).isEqualTo("Needs verification.");

        verify(employeeProfileRepository, never()).findByUserId(any());
        verify(employeeProfileRepository, never()).save(any());
    }

    @Test
    void rejectionEmitsProfileChangeRejectedAudit() {
        ProfileChangeRequest request = ProfileChangeRequest.builder()
                .id("request-3")
                .organizationId("organization-1")
                .requesterUserId("employee-1")
                .fieldName(ProfileChangeField.PHONE_NUMBER)
                .status(ProfileChangeStatus.PENDING)
                .build();

        when(authenticatedUserService.require(
                "admin-1", Set.of(UserRole.COMPANY_ADMIN, UserRole.HR), false))
                .thenReturn(adminActor());
        when(profileChangeRequestRepository.findById("request-3")).thenReturn(Optional.of(request));
        when(profileChangeRequestRepository.save(any(ProfileChangeRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service().reject("admin-1", "request-3", "Needs verification.");

        ArgumentCaptor<AuditActionType> actionCaptor = ArgumentCaptor.forClass(AuditActionType.class);
        verify(auditService).record(any(), actionCaptor.capture(), eq("PROFILE_CHANGE_REQUEST"), eq("request-3"),
                any(), any(), any(), any());
        assertThat(actionCaptor.getValue()).isEqualTo(AuditActionType.PROFILE_CHANGE_REJECTED);
    }

    @Test
    void processingCommentIsTrimmedOnRejection() {
        ProfileChangeRequest request = ProfileChangeRequest.builder()
                .id("request-3")
                .organizationId("organization-1")
                .requesterUserId("employee-1")
                .fieldName(ProfileChangeField.PHONE_NUMBER)
                .status(ProfileChangeStatus.PENDING)
                .build();

        when(authenticatedUserService.require(
                "admin-1", Set.of(UserRole.COMPANY_ADMIN, UserRole.HR), false))
                .thenReturn(adminActor());
        when(profileChangeRequestRepository.findById("request-3")).thenReturn(Optional.of(request));
        when(profileChangeRequestRepository.save(any(ProfileChangeRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ProfileChangeRequest result = service().reject("admin-1", "request-3", "   Needs verification.   ");

        assertThat(result.getProcessingComment()).isEqualTo("Needs verification.");
    }

    @Test
    void processingCommentLongerThanOneThousandCharactersIsRejected() {
        ProfileChangeRequest request = ProfileChangeRequest.builder()
                .id("request-3")
                .organizationId("organization-1")
                .requesterUserId("employee-1")
                .fieldName(ProfileChangeField.PHONE_NUMBER)
                .status(ProfileChangeStatus.PENDING)
                .build();

        when(authenticatedUserService.require(
                "admin-1", Set.of(UserRole.COMPANY_ADMIN, UserRole.HR), false))
                .thenReturn(adminActor());
        when(profileChangeRequestRepository.findById("request-3")).thenReturn(Optional.of(request));

        assertThatThrownBy(() -> service().reject("admin-1", "request-3", "x".repeat(1001)))
                .isInstanceOf(BusinessException.class);
        verify(profileChangeRequestRepository, never()).save(any());
    }

    @Test
    void approvingAPersonalEmailChangeNeverModifiesUserEmail() {
        EmployeeProfile profile = fullyPopulatedProfile();
        ProfileChangeRequest request = ProfileChangeRequest.builder()
                .id("request-1")
                .organizationId("organization-1")
                .requesterUserId("employee-1")
                .assignedToUserId("admin-1")
                .fieldName(ProfileChangeField.PERSONAL_EMAIL)
                .existingValue("jordan.personal@example.com")
                .requestedValue("new.email@example.com")
                .status(ProfileChangeStatus.PENDING)
                .build();

        when(authenticatedUserService.require(
                "admin-1", Set.of(UserRole.COMPANY_ADMIN, UserRole.HR), false))
                .thenReturn(adminActor());
        when(profileChangeRequestRepository.findById("request-1")).thenReturn(Optional.of(request));
        when(employeeProfileRepository.findByUserId("employee-1")).thenReturn(Optional.of(profile));
        when(employeeProfileRepository.save(any(EmployeeProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(profileChangeRequestRepository.save(any(ProfileChangeRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service().approve("admin-1", "request-1", null);

        ArgumentCaptor<EmployeeProfile> savedProfile = ArgumentCaptor.forClass(EmployeeProfile.class);
        verify(employeeProfileRepository).save(savedProfile.capture());
        assertThat(savedProfile.getValue().getPersonalEmail()).isEqualTo("new.email@example.com");
        // PERSONAL_EMAIL changes only ever touch EmployeeProfile - User.email
        // (authentication/password-recovery) is never modified. A read-only
        // userRepository.findById lookup for the approval-notification's
        // persona routing is expected and unrelated to this invariant.
        verify(userRepository, never()).save(any());
    }

    @Test
    void hrCanApproveARequestAssignedToThem() {
        EmployeeProfile profile = fullyPopulatedProfile();
        ProfileChangeRequest request = ProfileChangeRequest.builder()
                .id("request-1")
                .organizationId("organization-1")
                .requesterUserId("employee-1")
                .assignedToUserId("hr-1")
                .fieldName(ProfileChangeField.PHONE_NUMBER)
                .existingValue("(555) 010-1000")
                .requestedValue("(555) 010-9999")
                .status(ProfileChangeStatus.PENDING)
                .build();

        when(authenticatedUserService.require(
                "hr-1", Set.of(UserRole.COMPANY_ADMIN, UserRole.HR), false))
                .thenReturn(hrActor());
        when(profileChangeRequestRepository.findById("request-1")).thenReturn(Optional.of(request));
        when(employeeProfileRepository.findByUserId("employee-1")).thenReturn(Optional.of(profile));
        when(employeeProfileRepository.save(any(EmployeeProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(profileChangeRequestRepository.save(any(ProfileChangeRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ProfileChangeRequest result = service().approve("hr-1", "request-1", "Approved by HR");

        assertThat(result.getStatus()).isEqualTo(ProfileChangeStatus.APPROVED);
        assertThat(result.getProcessedByUserId()).isEqualTo("hr-1");
        verify(employeeProfileRepository).save(any(EmployeeProfile.class));
    }

    @Test
    void hrCannotApproveARequestAssignedToSomeoneElse() {
        ProfileChangeRequest request = ProfileChangeRequest.builder()
                .id("request-1")
                .organizationId("organization-1")
                .requesterUserId("employee-1")
                .assignedToUserId("hr-2")
                .fieldName(ProfileChangeField.PHONE_NUMBER)
                .status(ProfileChangeStatus.PENDING)
                .build();

        when(authenticatedUserService.require(
                "hr-1", Set.of(UserRole.COMPANY_ADMIN, UserRole.HR), false))
                .thenReturn(hrActor());
        when(profileChangeRequestRepository.findById("request-1")).thenReturn(Optional.of(request));

        assertThatThrownBy(() -> service().approve("hr-1", "request-1", "Approved by HR"))
                .isInstanceOf(ForbiddenException.class);

        verify(employeeProfileRepository, never()).findByUserId(any());
        verify(employeeProfileRepository, never()).save(any());
        verify(profileChangeRequestRepository, never()).save(any());
    }

    @Test
    void hrCannotRejectARequestAssignedToSomeoneElse() {
        ProfileChangeRequest request = ProfileChangeRequest.builder()
                .id("request-1")
                .organizationId("organization-1")
                .requesterUserId("employee-1")
                .assignedToUserId("hr-2")
                .fieldName(ProfileChangeField.PHONE_NUMBER)
                .status(ProfileChangeStatus.PENDING)
                .build();

        when(authenticatedUserService.require(
                "hr-1", Set.of(UserRole.COMPANY_ADMIN, UserRole.HR), false))
                .thenReturn(hrActor());
        when(profileChangeRequestRepository.findById("request-1")).thenReturn(Optional.of(request));

        assertThatThrownBy(() -> service().reject("hr-1", "request-1", "Rejected by HR"))
                .isInstanceOf(ForbiddenException.class);

        verify(profileChangeRequestRepository, never()).save(any());
    }

    @Test
    void companyAdminReceivesOrganizationScopedRequestsOrderedByRequestedAtDescending() {
        ProfileChangeRequest newer = ProfileChangeRequest.builder()
                .id("request-newer")
                .organizationId("organization-1")
                .requesterUserId("employee-1")
                .profileId("profile-1")
                .assignedToUserId("admin-1")
                .fieldName(ProfileChangeField.PHONE_NUMBER)
                .existingValue("(555) 010-1000")
                .requestedValue("(555) 010-9999")
                .requestReason("Number changed")
                .status(ProfileChangeStatus.PENDING)
                .requestedAt(LocalDateTime.of(2026, 8, 25, 13, 10))
                .createdAt(LocalDateTime.of(2026, 8, 25, 13, 10))
                .updatedAt(LocalDateTime.of(2026, 8, 25, 13, 10))
                .build();
        ProfileChangeRequest older = ProfileChangeRequest.builder()
                .id("request-older")
                .organizationId("organization-1")
                .requesterUserId("employee-2")
                .fieldName(ProfileChangeField.DESIGNATION)
                .existingValue("Designer")
                .requestedValue("Senior Designer")
                .status(ProfileChangeStatus.APPROVED)
                .processedByUserId("admin-1")
                .processingComment("Confirmed")
                .requestedAt(LocalDateTime.of(2026, 8, 20, 9, 0))
                .processedAt(LocalDateTime.of(2026, 8, 21, 9, 0))
                .build();

        // Repository is a derived query (...OrderByRequestedAtDesc) - the mock
        // simply returns results in the order that query is contracted to
        // produce, which the service must pass through unchanged.
        when(authenticatedUserService.require("admin-1", Set.of(UserRole.COMPANY_ADMIN, UserRole.HR), false))
                .thenReturn(adminActor());
        when(profileChangeRequestRepository.findByOrganizationIdOrderByRequestedAtDesc("organization-1"))
                .thenReturn(List.of(newer, older));
        // Neither assignee has a profile in this fixture, so the User fallback batch is queried too.
        when(employeeProfileRepository.findByUserIdIn(any())).thenReturn(List.of());
        when(userRepository.findAllById(any())).thenReturn(List.of());

        List<ProfileChangeRequestResponse> result = service().listForAdmin("admin-1");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).id()).isEqualTo("request-newer");
        assertThat(result.get(0).fieldName()).isEqualTo(ProfileChangeField.PHONE_NUMBER);
        assertThat(result.get(0).requestedValue()).isEqualTo("(555) 010-9999");
        assertThat(result.get(0).status()).isEqualTo(ProfileChangeStatus.PENDING);
        assertThat(result.get(1).id()).isEqualTo("request-older");
        assertThat(result.get(1).status()).isEqualTo(ProfileChangeStatus.APPROVED);
        assertThat(result.get(1).processedByUserId()).isEqualTo("admin-1");
        assertThat(result.get(1).processingComment()).isEqualTo("Confirmed");

        verify(profileChangeRequestRepository).findByOrganizationIdOrderByRequestedAtDesc("organization-1");
    }

    @Test
    void hrOnlyReceivesRequestsAssignedToThem() {
        ProfileChangeRequest assignedToHr = ProfileChangeRequest.builder()
                .id("request-hr")
                .organizationId("organization-1")
                .requesterUserId("employee-1")
                .assignedToUserId("hr-1")
                .fieldName(ProfileChangeField.PHONE_NUMBER)
                .status(ProfileChangeStatus.PENDING)
                .requestedAt(LocalDateTime.of(2026, 8, 25, 13, 10))
                .build();

        when(authenticatedUserService.require(
                "hr-1", Set.of(UserRole.COMPANY_ADMIN, UserRole.HR), false))
                .thenReturn(hrActor());
        when(profileChangeRequestRepository.findByOrganizationIdAndAssignedToUserIdOrderByRequestedAtDesc(
                "organization-1", "hr-1")).thenReturn(List.of(assignedToHr));
        when(employeeProfileRepository.findByUserIdIn(any())).thenReturn(List.of());
        when(userRepository.findAllById(any())).thenReturn(List.of());

        List<ProfileChangeRequestResponse> result = service().listForAdmin("hr-1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo("request-hr");
        verify(profileChangeRequestRepository)
                .findByOrganizationIdAndAssignedToUserIdOrderByRequestedAtDesc("organization-1", "hr-1");
        verify(profileChangeRequestRepository, never()).findByOrganizationIdOrderByRequestedAtDesc(any());
    }

    @Test
    void listForAdminOnlyQueriesTheActorsOwnOrganization() {
        when(authenticatedUserService.require("admin-1", Set.of(UserRole.COMPANY_ADMIN, UserRole.HR), false))
                .thenReturn(adminActor());
        when(profileChangeRequestRepository.findByOrganizationIdOrderByRequestedAtDesc(any()))
                .thenReturn(List.of());

        service().listForAdmin("admin-1");

        ArgumentCaptor<String> organizationIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(profileChangeRequestRepository)
                .findByOrganizationIdOrderByRequestedAtDesc(organizationIdCaptor.capture());
        // Exactly one query was made, and it used the authenticated actor's own
        // organization - proving another organization's requests can never be
        // returned through this path.
        assertThat(organizationIdCaptor.getValue()).isEqualTo("organization-1");
    }

    private EmployeeProfile hrManagerProfile() {
        return EmployeeProfile.builder()
                .userId("hr-1")
                .organizationId("organization-1")
                .employeeId("TD001")
                .name("Alex Morgan")
                .build();
    }

    @Test
    void adminDetailReturnsRealRequesterAndManagerInformationForAPendingRequest() {
        EmployeeProfile requesterProfile = fullyPopulatedProfile();
        EmployeeProfile managerProfile = hrManagerProfile();
        ProfileChangeRequest request = ProfileChangeRequest.builder()
                .id("request-1")
                .organizationId("organization-1")
                .requesterUserId("employee-1")
                .assignedToUserId("hr-1")
                .fieldName(ProfileChangeField.PHONE_NUMBER)
                .existingValue("(555) 010-1000")
                .requestedValue("(555) 010-9999")
                .requestReason("Number changed")
                .status(ProfileChangeStatus.PENDING)
                .requestedAt(LocalDateTime.of(2026, 8, 25, 13, 10))
                .build();

        when(authenticatedUserService.require("admin-1", Set.of(UserRole.COMPANY_ADMIN, UserRole.HR), false))
                .thenReturn(adminActor());
        when(profileChangeRequestRepository.findById("request-1")).thenReturn(Optional.of(request));
        when(employeeProfileRepository.findByUserId("employee-1")).thenReturn(Optional.of(requesterProfile));
        when(employeeProfileRepository.findByUserId("hr-1")).thenReturn(Optional.of(managerProfile));

        ProfileChangeRequestDetailResponse detail = service().getDetailForAdmin("admin-1", "request-1");

        assertThat(detail.id()).isEqualTo("request-1");
        assertThat(detail.fieldName()).isEqualTo(ProfileChangeField.PHONE_NUMBER);
        assertThat(detail.existingValue()).isEqualTo("(555) 010-1000");
        assertThat(detail.requestedValue()).isEqualTo("(555) 010-9999");
        assertThat(detail.requestReason()).isEqualTo("Number changed");
        assertThat(detail.status()).isEqualTo(ProfileChangeStatus.PENDING);

        assertThat(detail.requester().userId()).isEqualTo("employee-1");
        assertThat(detail.requester().employeeId()).isEqualTo("TD002");
        assertThat(detail.requester().name()).isEqualTo("Jordan Lee");
        assertThat(detail.requester().role()).isNull();

        assertThat(detail.reportingManager().userId()).isEqualTo("hr-1");
        assertThat(detail.reportingManager().employeeId()).isEqualTo("TD001");
        assertThat(detail.reportingManager().name()).isEqualTo("Alex Morgan");
        assertThat(detail.reportingManager().loginId()).isNull();
        assertThat(detail.reportingManager().email()).isNull();

        // The manager resolved from EmployeeProfile - the User fallback must never be queried.
        verify(userRepository, never()).findById(any());
    }

    @Test
    void adminDetailWorksForAnApprovedRequest() {
        EmployeeProfile requesterProfile = fullyPopulatedProfile();
        ProfileChangeRequest request = ProfileChangeRequest.builder()
                .id("request-2")
                .organizationId("organization-1")
                .requesterUserId("employee-1")
                .assignedToUserId("hr-1")
                .fieldName(ProfileChangeField.DESIGNATION)
                .existingValue("Designer")
                .requestedValue("Senior Designer")
                .status(ProfileChangeStatus.APPROVED)
                .processedByUserId("admin-1")
                .processingComment("Confirmed")
                .requestedAt(LocalDateTime.of(2026, 8, 20, 9, 0))
                .processedAt(LocalDateTime.of(2026, 8, 21, 9, 0))
                .build();

        when(authenticatedUserService.require("admin-1", Set.of(UserRole.COMPANY_ADMIN, UserRole.HR), false))
                .thenReturn(adminActor());
        when(profileChangeRequestRepository.findById("request-2")).thenReturn(Optional.of(request));
        when(employeeProfileRepository.findByUserId("employee-1")).thenReturn(Optional.of(requesterProfile));
        when(employeeProfileRepository.findByUserId("hr-1")).thenReturn(Optional.of(hrManagerProfile()));

        ProfileChangeRequestDetailResponse detail = service().getDetailForAdmin("admin-1", "request-2");

        assertThat(detail.status()).isEqualTo(ProfileChangeStatus.APPROVED);
        assertThat(detail.processedByUserId()).isEqualTo("admin-1");
        assertThat(detail.processingComment()).isEqualTo("Confirmed");
        assertThat(detail.processedAt()).isEqualTo(LocalDateTime.of(2026, 8, 21, 9, 0));
    }

    @Test
    void adminDetailWorksForARejectedRequest() {
        EmployeeProfile requesterProfile = fullyPopulatedProfile();
        ProfileChangeRequest request = ProfileChangeRequest.builder()
                .id("request-3")
                .organizationId("organization-1")
                .requesterUserId("employee-1")
                .assignedToUserId("hr-1")
                .fieldName(ProfileChangeField.PERSONAL_EMAIL)
                .existingValue("old@example.com")
                .requestedValue("new@example.com")
                .status(ProfileChangeStatus.REJECTED)
                .processedByUserId("admin-1")
                .processingComment("Please verify identity.")
                .requestedAt(LocalDateTime.of(2026, 8, 20, 9, 0))
                .processedAt(LocalDateTime.of(2026, 8, 21, 9, 0))
                .build();

        when(authenticatedUserService.require("admin-1", Set.of(UserRole.COMPANY_ADMIN, UserRole.HR), false))
                .thenReturn(adminActor());
        when(profileChangeRequestRepository.findById("request-3")).thenReturn(Optional.of(request));
        when(employeeProfileRepository.findByUserId("employee-1")).thenReturn(Optional.of(requesterProfile));
        when(employeeProfileRepository.findByUserId("hr-1")).thenReturn(Optional.of(hrManagerProfile()));

        ProfileChangeRequestDetailResponse detail = service().getDetailForAdmin("admin-1", "request-3");

        assertThat(detail.status()).isEqualTo(ProfileChangeStatus.REJECTED);
        assertThat(detail.processingComment()).isEqualTo("Please verify identity.");
    }

    @Test
    void crossOrganizationDetailIsRejected() {
        ProfileChangeRequest request = ProfileChangeRequest.builder()
                .id("request-4")
                .organizationId("organization-2")
                .requesterUserId("employee-1")
                .fieldName(ProfileChangeField.DESIGNATION)
                .status(ProfileChangeStatus.PENDING)
                .build();

        when(authenticatedUserService.require("admin-1", Set.of(UserRole.COMPANY_ADMIN, UserRole.HR), false))
                .thenReturn(adminActor());
        when(profileChangeRequestRepository.findById("request-4")).thenReturn(Optional.of(request));

        // Cross-org access uses safe not-found semantics, never a Forbidden leak
        // that would confirm the request exists in another organization.
        assertThatThrownBy(() -> service().getDetailForAdmin("admin-1", "request-4"))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(employeeProfileRepository, never()).findByUserId(any());
        verify(userRepository, never()).findById(any());
    }

    @Test
    void hrCanAccessDetailForARequestAssignedToThem() {
        EmployeeProfile requesterProfile = fullyPopulatedProfile();
        ProfileChangeRequest request = ProfileChangeRequest.builder()
                .id("request-1")
                .organizationId("organization-1")
                .requesterUserId("employee-1")
                .assignedToUserId("hr-1")
                .fieldName(ProfileChangeField.PHONE_NUMBER)
                .existingValue("(555) 010-1000")
                .requestedValue("(555) 010-9999")
                .status(ProfileChangeStatus.PENDING)
                .build();

        when(authenticatedUserService.require(
                "hr-1", Set.of(UserRole.COMPANY_ADMIN, UserRole.HR), false))
                .thenReturn(hrActor());
        when(profileChangeRequestRepository.findById("request-1")).thenReturn(Optional.of(request));
        when(employeeProfileRepository.findByUserId("employee-1")).thenReturn(Optional.of(requesterProfile));
        when(employeeProfileRepository.findByUserId("hr-1")).thenReturn(Optional.of(hrManagerProfile()));

        ProfileChangeRequestDetailResponse detail = service().getDetailForAdmin("hr-1", "request-1");

        assertThat(detail.id()).isEqualTo("request-1");
        assertThat(detail.requester().employeeId()).isEqualTo("TD002");
    }

    @Test
    void hrCannotAccessDetailForARequestAssignedToSomeoneElse() {
        ProfileChangeRequest request = ProfileChangeRequest.builder()
                .id("request-1")
                .organizationId("organization-1")
                .requesterUserId("employee-1")
                .assignedToUserId("hr-2")
                .fieldName(ProfileChangeField.PHONE_NUMBER)
                .status(ProfileChangeStatus.PENDING)
                .build();

        when(authenticatedUserService.require(
                "hr-1", Set.of(UserRole.COMPANY_ADMIN, UserRole.HR), false))
                .thenReturn(hrActor());
        when(profileChangeRequestRepository.findById("request-1")).thenReturn(Optional.of(request));

        assertThatThrownBy(() -> service().getDetailForAdmin("hr-1", "request-1"))
                .isInstanceOf(ForbiddenException.class);

        verify(employeeProfileRepository, never()).findByUserId(any());
    }

    @Test
    void companyAdminManagerFallsBackToUserWhenNoEmployeeProfileExists() {
        EmployeeProfile requesterProfile = fullyPopulatedProfile();
        ProfileChangeRequest request = ProfileChangeRequest.builder()
                .id("request-5")
                .organizationId("organization-1")
                .requesterUserId("employee-1")
                .assignedToUserId("admin-2")
                .fieldName(ProfileChangeField.NAME)
                .status(ProfileChangeStatus.PENDING)
                .build();
        User adminUser = User.builder()
                .id("admin-2")
                .loginId("TDADMIN2")
                .email("admin2@tardus.test")
                .role(UserRole.COMPANY_ADMIN)
                .organizationId("organization-1")
                .build();

        when(authenticatedUserService.require("admin-1", Set.of(UserRole.COMPANY_ADMIN, UserRole.HR), false))
                .thenReturn(adminActor());
        when(profileChangeRequestRepository.findById("request-5")).thenReturn(Optional.of(request));
        when(employeeProfileRepository.findByUserId("employee-1")).thenReturn(Optional.of(requesterProfile));
        // COMPANY_ADMIN never gets an EmployeeProfile - this must come back empty.
        when(employeeProfileRepository.findByUserId("admin-2")).thenReturn(Optional.empty());
        when(userRepository.findById("admin-2")).thenReturn(Optional.of(adminUser));

        ProfileChangeRequestDetailResponse detail = service().getDetailForAdmin("admin-1", "request-5");

        assertThat(detail.reportingManager().userId()).isEqualTo("admin-2");
        assertThat(detail.reportingManager().loginId()).isEqualTo("TDADMIN2");
        assertThat(detail.reportingManager().email()).isEqualTo("admin2@tardus.test");
        // No name exists anywhere in the data for this case - none is invented.
        assertThat(detail.reportingManager().employeeId()).isNull();
        assertThat(detail.reportingManager().name()).isNull();
    }

    @Test
    void missingRequesterProfileFailsSafely() {
        ProfileChangeRequest request = ProfileChangeRequest.builder()
                .id("request-6")
                .organizationId("organization-1")
                .requesterUserId("employee-orphaned")
                .fieldName(ProfileChangeField.DESIGNATION)
                .status(ProfileChangeStatus.PENDING)
                .build();

        when(authenticatedUserService.require("admin-1", Set.of(UserRole.COMPANY_ADMIN, UserRole.HR), false))
                .thenReturn(adminActor());
        when(profileChangeRequestRepository.findById("request-6")).thenReturn(Optional.of(request));
        when(employeeProfileRepository.findByUserId("employee-orphaned")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getDetailForAdmin("admin-1", "request-6"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void missingRequestReturnsNotFound() {
        when(authenticatedUserService.require("admin-1", Set.of(UserRole.COMPANY_ADMIN, UserRole.HR), false))
                .thenReturn(adminActor());
        when(profileChangeRequestRepository.findById("missing-request")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getDetailForAdmin("admin-1", "missing-request"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listForAdminReturnsRealEmployeeAndManagerDisplayInformationResolvedInABatch() {
        ProfileChangeRequest request = ProfileChangeRequest.builder()
                .id("request-1")
                .organizationId("organization-1")
                .requesterUserId("employee-1")
                .assignedToUserId("hr-1")
                .fieldName(ProfileChangeField.PHONE_NUMBER)
                .status(ProfileChangeStatus.PENDING)
                .requestedAt(LocalDateTime.of(2026, 8, 25, 13, 10))
                .build();
        EmployeeProfile requesterProfile = fullyPopulatedProfile();
        EmployeeProfile managerProfile = hrManagerProfile();
        User requesterUser = User.builder()
                .id("employee-1").role(UserRole.EMPLOYEE).organizationId("organization-1").build();

        when(authenticatedUserService.require("admin-1", Set.of(UserRole.COMPANY_ADMIN, UserRole.HR), false))
                .thenReturn(adminActor());
        when(profileChangeRequestRepository.findByOrganizationIdOrderByRequestedAtDesc("organization-1"))
                .thenReturn(List.of(request));
        when(employeeProfileRepository.findByUserIdIn(Set.of("employee-1", "hr-1")))
                .thenReturn(List.of(requesterProfile, managerProfile));
        // The assignee (hr-1) has an EmployeeProfile, so only the requester is
        // looked up in the User batch - purely to resolve requesterRole.
        when(userRepository.findAllById(Set.of("employee-1"))).thenReturn(List.of(requesterUser));

        List<ProfileChangeRequestResponse> result = service().listForAdmin("admin-1");

        assertThat(result).hasSize(1);
        ProfileChangeRequestResponse response = result.get(0);
        // Real information, not a raw MongoDB id.
        assertThat(response.employeeId()).isEqualTo("TD002");
        assertThat(response.employeeName()).isEqualTo("Jordan Lee");
        assertThat(response.reportingManager().employeeId()).isEqualTo("TD001");
        assertThat(response.reportingManager().name()).isEqualTo("Alex Morgan");
        assertThat(response.requesterRole()).isEqualTo("EMPLOYEE");

        verify(userRepository).findAllById(Set.of("employee-1"));
    }

    @Test
    void listForAdminManagerFallsBackToUserWhenNoEmployeeProfileExists() {
        ProfileChangeRequest request = ProfileChangeRequest.builder()
                .id("request-1")
                .organizationId("organization-1")
                .requesterUserId("employee-1")
                .assignedToUserId("admin-2")
                .fieldName(ProfileChangeField.DESIGNATION)
                .status(ProfileChangeStatus.PENDING)
                .requestedAt(LocalDateTime.of(2026, 8, 25, 13, 10))
                .build();
        EmployeeProfile requesterProfile = fullyPopulatedProfile();
        User requesterUser = User.builder()
                .id("employee-1").role(UserRole.EMPLOYEE).organizationId("organization-1").build();
        User adminUser = User.builder()
                .id("admin-2").loginId("TDADMIN2").email("admin2@tardus.test")
                .role(UserRole.COMPANY_ADMIN).organizationId("organization-1").build();

        when(authenticatedUserService.require("admin-1", Set.of(UserRole.COMPANY_ADMIN, UserRole.HR), false))
                .thenReturn(adminActor());
        when(profileChangeRequestRepository.findByOrganizationIdOrderByRequestedAtDesc("organization-1"))
                .thenReturn(List.of(request));
        // Only the requester has a profile - admin-2 (COMPANY_ADMIN) never gets one.
        when(employeeProfileRepository.findByUserIdIn(Set.of("employee-1", "admin-2")))
                .thenReturn(List.of(requesterProfile));
        // The User batch now covers the requester (for requesterRole) plus the
        // profile-less assignee (for the manager fallback).
        when(userRepository.findAllById(Set.of("employee-1", "admin-2")))
                .thenReturn(List.of(requesterUser, adminUser));

        List<ProfileChangeRequestResponse> result = service().listForAdmin("admin-1");

        assertThat(result.get(0).requesterRole()).isEqualTo("EMPLOYEE");
        ReportingManagerSummary manager = result.get(0).reportingManager();
        assertThat(manager.userId()).isEqualTo("admin-2");
        assertThat(manager.loginId()).isEqualTo("TDADMIN2");
        assertThat(manager.email()).isEqualTo("admin2@tardus.test");
        // No name exists anywhere in the data for this case - none is invented.
        assertThat(manager.employeeId()).isNull();
        assertThat(manager.name()).isNull();
    }

    @Test
    void listForAdminResolvesRequesterRoleFromTheRequesterUser() {
        ProfileChangeRequest employeeRequest = ProfileChangeRequest.builder()
                .id("request-emp").organizationId("organization-1")
                .requesterUserId("employee-1").assignedToUserId("hr-1")
                .fieldName(ProfileChangeField.PHONE_NUMBER).status(ProfileChangeStatus.PENDING)
                .requestedAt(LocalDateTime.of(2026, 8, 25, 13, 10)).build();
        ProfileChangeRequest hrRequest = ProfileChangeRequest.builder()
                .id("request-hr").organizationId("organization-1")
                .requesterUserId("hr-9").assignedToUserId("hr-1")
                .fieldName(ProfileChangeField.DESIGNATION).status(ProfileChangeStatus.PENDING)
                .requestedAt(LocalDateTime.of(2026, 8, 24, 9, 0)).build();
        User employeeUser = User.builder()
                .id("employee-1").role(UserRole.EMPLOYEE).organizationId("organization-1").build();
        User hrUser = User.builder()
                .id("hr-9").role(UserRole.HR).organizationId("organization-1").build();

        when(authenticatedUserService.require("admin-1", Set.of(UserRole.COMPANY_ADMIN, UserRole.HR), false))
                .thenReturn(adminActor());
        when(profileChangeRequestRepository.findByOrganizationIdOrderByRequestedAtDesc("organization-1"))
                .thenReturn(List.of(employeeRequest, hrRequest));
        when(employeeProfileRepository.findByUserIdIn(any()))
                .thenReturn(List.of(hrManagerProfile()));
        when(userRepository.findAllById(any())).thenReturn(List.of(employeeUser, hrUser));

        List<ProfileChangeRequestResponse> result = service().listForAdmin("admin-1");

        assertThat(result).extracting(ProfileChangeRequestResponse::id, ProfileChangeRequestResponse::requesterRole)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("request-emp", "EMPLOYEE"),
                        org.assertj.core.api.Assertions.tuple("request-hr", "HR"));
    }

    @Test
    void cannotApproveOrRejectAnAlreadyProcessedRequest() {
        ProfileChangeRequest alreadyApproved = ProfileChangeRequest.builder()
                .id("request-7")
                .organizationId("organization-1")
                .requesterUserId("employee-1")
                .fieldName(ProfileChangeField.DESIGNATION)
                .status(ProfileChangeStatus.APPROVED)
                .build();

        when(authenticatedUserService.require("admin-1", Set.of(UserRole.COMPANY_ADMIN, UserRole.HR), false))
                .thenReturn(adminActor());
        when(profileChangeRequestRepository.findById("request-7")).thenReturn(Optional.of(alreadyApproved));

        assertThatThrownBy(() -> service().approve("admin-1", "request-7", "Too late"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service().reject("admin-1", "request-7", "Too late"))
                .isInstanceOf(BusinessException.class);

        verify(employeeProfileRepository, never()).findByUserId(any());
        verify(employeeProfileRepository, never()).save(any());
        verify(profileChangeRequestRepository, never()).save(any());
    }

    @Test
    void createNotifiesTheCurrentReviewerAndCompanyAdmin() {
        stubValidReviewer();
        stubValidReviewerResolution();
        when(profileChangeRequestRepository.save(any(ProfileChangeRequest.class)))
                .thenAnswer(invocation -> {
                    ProfileChangeRequest request = invocation.getArgument(0);
                    request.setId("request-1");
                    return request;
                });
        when(userRepository.findByOrganizationIdAndRoleIn("organization-1", List.of(UserRole.COMPANY_ADMIN)))
                .thenReturn(List.of(User.builder().id("admin-1").organizationId("organization-1")
                        .role(UserRole.COMPANY_ADMIN).accountStatus(AccountStatus.ACTIVE).build()));

        CreateProfileChangeRequestRequest createRequest =
                new CreateProfileChangeRequestRequest(ProfileChangeField.PHONE_NUMBER, "(555) 010-9999", null);
        service().create("employee-1", createRequest);

        // Both the reviewer and the Company Admin land directly in the assigned
        // review queue for this exact request - not the generic two-card
        // Profile Change Requests landing page - and each uses their own
        // persona-correct route segment (hr vs admin).
        verify(inAppNotificationService).createInAppOnlyNotification(eq("organization-1"), eq("hr-1"),
                eq(NotificationType.PROFILE_CHANGE_SUBMITTED), eq("Profile change request submitted"),
                contains("Jordan Lee (TD002) submitted a profile change request for Phone Number"),
                eq("request-1"), eq("/myhr/hr/profile-change-requests?view=assigned&requestId=request-1"));
        verify(inAppNotificationService).createInAppOnlyNotification(eq("organization-1"), eq("admin-1"),
                eq(NotificationType.PROFILE_CHANGE_SUBMITTED), eq("Profile change request submitted"),
                contains("Jordan Lee (TD002)"), eq("request-1"),
                eq("/myhr/admin/profile-change-requests?view=assigned&requestId=request-1"));
        // The Company Admin lookup never crosses into another organization.
        verify(userRepository).findByOrganizationIdAndRoleIn("organization-1", List.of(UserRole.COMPANY_ADMIN));
    }

    @Test
    void createDoesNotDoubleNotifyWhenTheReviewerIsAlreadyTheCompanyAdmin() {
        when(authenticatedUserService.require(
                "employee-1", Set.of(UserRole.EMPLOYEE, UserRole.HR), false))
                .thenReturn(employeeActor());
        when(employeeProfileRepository.findByUserId("employee-1")).thenReturn(Optional.of(fullyPopulatedProfile()));
        when(reportingAssignmentRepository.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                "employee-1", RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(Optional.of(ReportingAssignment.builder().organizationId("organization-1")
                        .reportingManagerUserId("admin-1").build()));
        User admin = User.builder().id("admin-1").organizationId("organization-1")
                .role(UserRole.COMPANY_ADMIN).accountStatus(AccountStatus.ACTIVE).build();
        when(userRepository.findById("admin-1")).thenReturn(Optional.of(admin));
        when(userRepository.findByOrganizationIdAndRoleIn("organization-1", List.of(UserRole.COMPANY_ADMIN)))
                .thenReturn(List.of(admin));
        when(profileChangeRequestRepository.save(any(ProfileChangeRequest.class)))
                .thenAnswer(invocation -> {
                    ProfileChangeRequest request = invocation.getArgument(0);
                    request.setId("request-1");
                    return request;
                });

        CreateProfileChangeRequestRequest createRequest =
                new CreateProfileChangeRequestRequest(ProfileChangeField.PHONE_NUMBER, "(555) 010-9999", null);
        service().create("employee-1", createRequest);

        verify(inAppNotificationService, times(1)).createInAppOnlyNotification(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void submissionFailureSendsNoNotification() {
        when(authenticatedUserService.require(
                "employee-1", Set.of(UserRole.EMPLOYEE, UserRole.HR), false))
                .thenReturn(employeeActor());
        when(employeeProfileRepository.findByUserId("employee-1")).thenReturn(Optional.of(fullyPopulatedProfile()));
        when(profileChangeRequestRepository.existsByOrganizationIdAndRequesterUserIdAndFieldNameAndStatus(
                "organization-1", "employee-1", ProfileChangeField.PHONE_NUMBER, ProfileChangeStatus.PENDING))
                .thenReturn(true);

        CreateProfileChangeRequestRequest createRequest =
                new CreateProfileChangeRequestRequest(ProfileChangeField.PHONE_NUMBER, "(555) 010-9999", null);

        assertThatThrownBy(() -> service().create("employee-1", createRequest)).isInstanceOf(BusinessException.class);

        verifyNoInteractions(inAppNotificationService);
    }

    @Test
    void approvalNotifiesTheEmployeeWithTheChangedFieldDetails() {
        EmployeeProfile profile = fullyPopulatedProfile();
        ProfileChangeRequest request = ProfileChangeRequest.builder()
                .id("request-1")
                .organizationId("organization-1")
                .requesterUserId("employee-1")
                .assignedToUserId("admin-1")
                .fieldName(ProfileChangeField.PHONE_NUMBER)
                .existingValue("(555) 010-1000")
                .requestedValue("(555) 010-9999")
                .status(ProfileChangeStatus.PENDING)
                .build();

        when(authenticatedUserService.require(
                "admin-1", Set.of(UserRole.COMPANY_ADMIN, UserRole.HR), false))
                .thenReturn(adminActor());
        when(profileChangeRequestRepository.findById("request-1")).thenReturn(Optional.of(request));
        when(employeeProfileRepository.findByUserId("employee-1")).thenReturn(Optional.of(profile));
        when(employeeProfileRepository.save(any(EmployeeProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(profileChangeRequestRepository.save(any(ProfileChangeRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.findById("employee-1")).thenReturn(Optional.of(
                User.builder().id("employee-1").organizationId("organization-1").role(UserRole.EMPLOYEE).build()));

        service().approve("admin-1", "request-1", "Verified with employee.");

        // The employee's own request context, with no ?view= card-menu param
        // (their own history has no card menu to skip past) - just the
        // requestId so the specific decided request opens directly.
        verify(inAppNotificationService).createInAppOnlyNotification(eq("organization-1"), eq("employee-1"),
                eq(NotificationType.PROFILE_CHANGE_APPROVED), eq("Profile change request approved"),
                eq("Your profile change request was approved. Phone Number was updated from "
                        + "\"(555) 010-1000\" to \"(555) 010-9999\"."),
                eq("request-1"), eq("/myhr/employee/profile-change-requests?requestId=request-1"));
    }

    @Test
    void approvalFailureSendsNoNotification() {
        EmployeeProfile profile = fullyPopulatedProfile();
        profile.setPhoneNumber("(555) 999-0000");
        ProfileChangeRequest request = ProfileChangeRequest.builder()
                .id("request-1")
                .organizationId("organization-1")
                .requesterUserId("employee-1")
                .assignedToUserId("admin-1")
                .fieldName(ProfileChangeField.PHONE_NUMBER)
                .existingValue("(555) 010-1000")
                .requestedValue("(555) 010-9999")
                .status(ProfileChangeStatus.PENDING)
                .build();

        when(authenticatedUserService.require(
                "admin-1", Set.of(UserRole.COMPANY_ADMIN, UserRole.HR), false))
                .thenReturn(adminActor());
        when(profileChangeRequestRepository.findById("request-1")).thenReturn(Optional.of(request));
        when(employeeProfileRepository.findByUserId("employee-1")).thenReturn(Optional.of(profile));

        assertThatThrownBy(() -> service().approve("admin-1", "request-1", null))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(inAppNotificationService);
    }

    @Test
    void rejectionNotifiesTheEmployeeWithTheReason() {
        ProfileChangeRequest request = ProfileChangeRequest.builder()
                .id("request-3")
                .organizationId("organization-1")
                .requesterUserId("employee-1")
                .fieldName(ProfileChangeField.PHONE_NUMBER)
                .status(ProfileChangeStatus.PENDING)
                .build();

        when(authenticatedUserService.require(
                "admin-1", Set.of(UserRole.COMPANY_ADMIN, UserRole.HR), false))
                .thenReturn(adminActor());
        when(profileChangeRequestRepository.findById("request-3")).thenReturn(Optional.of(request));
        when(profileChangeRequestRepository.save(any(ProfileChangeRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.findById("employee-1")).thenReturn(Optional.of(
                User.builder().id("employee-1").organizationId("organization-1").role(UserRole.EMPLOYEE).build()));

        service().reject("admin-1", "request-3", "Needs verification.");

        verify(inAppNotificationService).createInAppOnlyNotification(eq("organization-1"), eq("employee-1"),
                eq(NotificationType.PROFILE_CHANGE_REJECTED), eq("Profile change request rejected"),
                eq("Your profile change request was rejected. Reason: Needs verification."),
                eq("request-3"), eq("/myhr/employee/profile-change-requests?requestId=request-3"));
    }

    @Test
    void aNotificationFailureNeverBreaksAnOtherwiseSuccessfulSubmission() {
        // Regression test: notification creation is secondary to the business
        // action - a transient failure there must never surface as a failure of
        // the profile change request submission itself.
        stubValidReviewer();
        stubValidReviewerResolution();
        when(profileChangeRequestRepository.save(any(ProfileChangeRequest.class)))
                .thenAnswer(invocation -> {
                    ProfileChangeRequest request = invocation.getArgument(0);
                    request.setId("request-1");
                    return request;
                });
        doThrow(new IllegalArgumentException("boom")).when(inAppNotificationService)
                .createInAppOnlyNotification(any(), any(), any(), any(), any(), any(), any());

        CreateProfileChangeRequestRequest createRequest =
                new CreateProfileChangeRequestRequest(ProfileChangeField.PHONE_NUMBER, "(555) 010-9999", null);

        ProfileChangeRequestResponse response = service().create("employee-1", createRequest);

        assertThat(response.id()).isEqualTo("request-1");
    }

    @Test
    void rejectionWithoutACommentOmitsTheReasonSuffix() {
        ProfileChangeRequest request = ProfileChangeRequest.builder()
                .id("request-3")
                .organizationId("organization-1")
                .requesterUserId("employee-1")
                .fieldName(ProfileChangeField.PHONE_NUMBER)
                .status(ProfileChangeStatus.PENDING)
                .build();

        when(authenticatedUserService.require(
                "admin-1", Set.of(UserRole.COMPANY_ADMIN, UserRole.HR), false))
                .thenReturn(adminActor());
        when(profileChangeRequestRepository.findById("request-3")).thenReturn(Optional.of(request));
        when(profileChangeRequestRepository.save(any(ProfileChangeRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service().reject("admin-1", "request-3", null);

        verify(inAppNotificationService).createInAppOnlyNotification(eq("organization-1"), eq("employee-1"),
                eq(NotificationType.PROFILE_CHANGE_REJECTED), eq("Profile change request rejected"),
                eq("Your profile change request was rejected."), eq("request-3"), anyString());
    }

    // ---- email notifications (important events only; recipient: personalEmail, then account email) ----

    private User activeUser(String id, UserRole role, String accountEmail) {
        return User.builder().id(id).organizationId("organization-1").role(role).email(accountEmail)
                .accountStatus(AccountStatus.ACTIVE).build();
    }

    private void stubSubmission(User reviewer, User admin) {
        stubValidReviewer();
        when(reportingAssignmentRepository.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                "employee-1", RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(Optional.of(ReportingAssignment.builder().organizationId("organization-1")
                        .reportingManagerUserId("hr-1").build()));
        lenient().when(userRepository.findById("hr-1")).thenReturn(Optional.of(reviewer));
        lenient().when(userRepository.findById("admin-1")).thenReturn(Optional.of(admin));
        when(userRepository.findByOrganizationIdAndRoleIn("organization-1", List.of(UserRole.COMPANY_ADMIN)))
                .thenReturn(List.of(admin));
        when(profileChangeRequestRepository.save(any(ProfileChangeRequest.class)))
                .thenAnswer(invocation -> {
                    ProfileChangeRequest request = invocation.getArgument(0);
                    request.setId("request-1");
                    return request;
                });
    }

    private CreateProfileChangeRequestRequest phoneRequest() {
        return new CreateProfileChangeRequestRequest(ProfileChangeField.PHONE_NUMBER, "(555) 010-9999", null);
    }

    @Test
    void submissionEmailsTheReviewerAtPersonalEmailAndTheAdminAtAccountEmail() {
        stubSubmission(activeUser("hr-1", UserRole.HR, "hr-login@example.com"),
                activeUser("admin-1", UserRole.COMPANY_ADMIN, "admin-login@example.com"));
        lenient().when(employeeProfileRepository.findByUserId("hr-1")).thenReturn(Optional.of(EmployeeProfile.builder()
                .userId("hr-1").organizationId("organization-1").personalEmail("hr-personal@example.com")
                .workEmail("hr-work@example.com").build()));

        service().create("employee-1", phoneRequest());

        verify(inAppNotificationService, times(2)).createInAppOnlyNotification(eq("organization-1"), anyString(),
                eq(NotificationType.PROFILE_CHANGE_SUBMITTED), anyString(), anyString(), eq("request-1"), anyString());
        verify(emailService).sendHtmlEmail(eq("hr-personal@example.com"), eq("Profile change request submitted"), anyString());
        verify(emailService).sendHtmlEmail(eq("admin-login@example.com"), eq("Profile change request submitted"), anyString());
        verify(emailService, never()).sendHtmlEmail(eq("hr-work@example.com"), anyString(), anyString());
    }

    @Test
    void submissionEmailFailureNeverFailsTheSubmissionAndKeepsTheInAppNotifications() {
        stubSubmission(activeUser("hr-1", UserRole.HR, "hr-login@example.com"),
                activeUser("admin-1", UserRole.COMPANY_ADMIN, null));
        doThrow(new IllegalStateException("smtp down")).when(emailService).sendHtmlEmail(anyString(), anyString(), anyString());

        ProfileChangeRequestResponse response = service().create("employee-1", phoneRequest());

        assertThat(response.id()).isEqualTo("request-1");
        verify(inAppNotificationService, times(2)).createInAppOnlyNotification(eq("organization-1"), anyString(),
                eq(NotificationType.PROFILE_CHANGE_SUBMITTED), anyString(), anyString(), eq("request-1"), anyString());
        // the admin has neither a personal nor an account email: no email attempt for them
        verify(emailService, times(1)).sendHtmlEmail(anyString(), anyString(), anyString());
    }

    private ProfileChangeRequest pendingPhoneRequest(String id) {
        return ProfileChangeRequest.builder().id(id).organizationId("organization-1").requesterUserId("employee-1")
                .assignedToUserId("admin-1").fieldName(ProfileChangeField.PHONE_NUMBER)
                .existingValue("(555) 010-1000").requestedValue("(555) 010-9999").status(ProfileChangeStatus.PENDING).build();
    }

    private void stubDecision(ProfileChangeRequest request, User requester, EmployeeProfile requesterProfile) {
        when(authenticatedUserService.require("admin-1", Set.of(UserRole.COMPANY_ADMIN, UserRole.HR), false))
                .thenReturn(adminActor());
        when(profileChangeRequestRepository.findById(request.getId())).thenReturn(Optional.of(request));
        when(employeeProfileRepository.findByUserId("employee-1")).thenReturn(Optional.ofNullable(requesterProfile));
        lenient().when(employeeProfileRepository.save(any(EmployeeProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(profileChangeRequestRepository.save(any(ProfileChangeRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.findById("employee-1")).thenReturn(Optional.of(requester));
    }

    @Test
    void approvalEmailsTheRequesterAtPersonalEmail() {
        stubDecision(pendingPhoneRequest("request-1"), activeUser("employee-1", UserRole.EMPLOYEE, "login@example.com"),
                fullyPopulatedProfile());

        service().approve("admin-1", "request-1", "ok");

        verify(inAppNotificationService).createInAppOnlyNotification(eq("organization-1"), eq("employee-1"),
                eq(NotificationType.PROFILE_CHANGE_APPROVED), anyString(), anyString(), eq("request-1"), anyString());
        verify(emailService).sendHtmlEmail(eq("jordan.personal@example.com"), eq("Profile change request approved"), anyString());
    }

    @Test
    void rejectionEmailFallsBackToTheAccountEmailWhenThereIsNoPersonalEmail() {
        EmployeeProfile noPersonal = fullyPopulatedProfile();
        noPersonal.setPersonalEmail(null);
        stubDecision(pendingPhoneRequest("request-3"), activeUser("employee-1", UserRole.EMPLOYEE, "login@example.com"), noPersonal);

        service().reject("admin-1", "request-3", "Needs verification.");

        verify(inAppNotificationService).createInAppOnlyNotification(eq("organization-1"), eq("employee-1"),
                eq(NotificationType.PROFILE_CHANGE_REJECTED), anyString(), anyString(), eq("request-3"), anyString());
        verify(emailService).sendHtmlEmail(eq("login@example.com"), eq("Profile change request rejected"), anyString());
    }

    @Test
    void rejectionWithNoEmailAddressStillNotifiesInAppAndSendsNoEmail() {
        EmployeeProfile noPersonal = fullyPopulatedProfile();
        noPersonal.setPersonalEmail(null);
        stubDecision(pendingPhoneRequest("request-3"), activeUser("employee-1", UserRole.EMPLOYEE, null), noPersonal);

        service().reject("admin-1", "request-3", "Needs verification.");

        verify(inAppNotificationService).createInAppOnlyNotification(eq("organization-1"), eq("employee-1"),
                eq(NotificationType.PROFILE_CHANGE_REJECTED), anyString(), anyString(), eq("request-3"), anyString());
        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString());
    }

    @Test
    void approvalEmailFailureNeverFailsTheApproval() {
        stubDecision(pendingPhoneRequest("request-1"), activeUser("employee-1", UserRole.EMPLOYEE, "login@example.com"),
                fullyPopulatedProfile());
        doThrow(new IllegalStateException("smtp down")).when(emailService).sendHtmlEmail(anyString(), anyString(), anyString());

        var response = service().approve("admin-1", "request-1", "ok");

        assertThat(response.getId()).isEqualTo("request-1");
        verify(inAppNotificationService).createInAppOnlyNotification(eq("organization-1"), eq("employee-1"),
                eq(NotificationType.PROFILE_CHANGE_APPROVED), anyString(), anyString(), eq("request-1"), anyString());
    }
}
