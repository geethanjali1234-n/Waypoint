package com.waypoint.service;

import com.waypoint.entity.ReportingAssignment;
import com.waypoint.entity.User;
import com.waypoint.enums.RelationshipType;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.repository.ReportingAssignmentRepository;
import com.waypoint.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportingAssignmentServiceTest {

    @Mock
    private ReportingAssignmentRepository repository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private ReportingAssignmentService service;

    @Test
    void createsGenericSameOrganizationAssignment() {
        ReportingAssignment assignment = assignment();
        when(userRepository.findById("employee-1")).thenReturn(Optional.of(user("employee-1", "org-1", UserRole.EMPLOYEE)));
        when(userRepository.findById("manager-1")).thenReturn(Optional.of(user("manager-1", "org-1", UserRole.HR)));
        when(repository.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                "employee-1", RelationshipType.PRIMARY_REPORTING_MANAGER)).thenReturn(Optional.empty());
        when(repository.save(assignment)).thenReturn(assignment);

        ReportingAssignment saved = service.createActiveAssignment(assignment);

        assertThat(saved.getReportingManagerUserId()).isEqualTo("manager-1");
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void createsHrToCompanyAdminAssignment() {
        ReportingAssignment assignment = assignment();
        when(userRepository.findById("employee-1")).thenReturn(Optional.of(user("employee-1", "org-1", UserRole.HR)));
        when(userRepository.findById("manager-1"))
                .thenReturn(Optional.of(user("manager-1", "org-1", UserRole.COMPANY_ADMIN)));
        when(repository.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                "employee-1", RelationshipType.PRIMARY_REPORTING_MANAGER)).thenReturn(Optional.empty());
        when(repository.save(assignment)).thenReturn(assignment);

        assertThat(service.createActiveAssignment(assignment).getReportingManagerUserId()).isEqualTo("manager-1");
    }

    @Test
    void rejectsCrossOrganizationAssignment() {
        ReportingAssignment assignment = assignment();
        when(userRepository.findById("employee-1")).thenReturn(Optional.of(user("employee-1", "org-1", UserRole.EMPLOYEE)));
        when(userRepository.findById("manager-1")).thenReturn(Optional.of(user("manager-1", "org-2", UserRole.HR)));

        assertThatThrownBy(() -> service.createActiveAssignment(assignment))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("same organization");

        verify(repository, never()).save(any());
    }

    @Test
    void rejectsExistingActivePrimaryAssignment() {
        ReportingAssignment assignment = assignment();
        when(userRepository.findById("employee-1")).thenReturn(Optional.of(user("employee-1", "org-1", UserRole.EMPLOYEE)));
        when(userRepository.findById("manager-1")).thenReturn(Optional.of(user("manager-1", "org-1", UserRole.COMPANY_ADMIN)));
        when(repository.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                "employee-1", RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(Optional.of(assignment));

        assertThatThrownBy(() -> service.createActiveAssignment(assignment))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already has");
    }

    @Test
    void translatesConcurrentUniqueIndexViolationToBusinessError() {
        ReportingAssignment assignment = assignment();
        when(userRepository.findById("employee-1")).thenReturn(Optional.of(user("employee-1", "org-1", UserRole.EMPLOYEE)));
        when(userRepository.findById("manager-1")).thenReturn(Optional.of(user("manager-1", "org-1", UserRole.HR)));
        when(repository.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                "employee-1", RelationshipType.PRIMARY_REPORTING_MANAGER)).thenReturn(Optional.empty());
        when(repository.save(assignment)).thenThrow(new DuplicateKeyException("duplicate"));

        assertThatThrownBy(() -> service.createActiveAssignment(assignment))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already has");
    }

    private ReportingAssignment assignment() {
        return ReportingAssignment.builder()
                .organizationId("org-1")
                .employeeUserId("employee-1")
                .reportingManagerUserId("manager-1")
                .relationshipType(RelationshipType.PRIMARY_REPORTING_MANAGER)
                .isActive(true)
                .effectiveFrom(LocalDate.now())
                .assignedByUserId("admin-1")
                .build();
    }

    @Test
    void rejectsManagerWithIneligibleRole() {
        ReportingAssignment assignment = assignment();
        when(userRepository.findById("employee-1"))
                .thenReturn(Optional.of(user("employee-1", "org-1", UserRole.EMPLOYEE)));
        when(userRepository.findById("manager-1"))
                .thenReturn(Optional.of(user("manager-1", "org-1", UserRole.EMPLOYEE)));

        assertThatThrownBy(() -> service.createActiveAssignment(assignment))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("active HR or Company Admin");
    }

    private User user(String id, String organizationId, UserRole role) {
        return User.builder().id(id).organizationId(organizationId).role(role)
                .accountStatus(AccountStatus.ACTIVE).build();
    }

    @Test
    void reassignPrimaryManagerClosesCurrentAndCreatesNewActiveAssignment() {
        ReportingAssignment current = ReportingAssignment.builder()
                .id("assignment-1").organizationId("org-1").employeeUserId("employee-1")
                .reportingManagerUserId("manager-1").relationshipType(RelationshipType.PRIMARY_REPORTING_MANAGER)
                .isActive(true).effectiveFrom(LocalDate.now().minusDays(30)).build();
        when(repository.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                "employee-1", RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(Optional.of(current))
                .thenReturn(Optional.empty());
        when(userRepository.findById("employee-1"))
                .thenReturn(Optional.of(user("employee-1", "org-1", UserRole.EMPLOYEE)));
        when(userRepository.findById("manager-2"))
                .thenReturn(Optional.of(user("manager-2", "org-1", UserRole.HR)));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        LocalDate effectiveFrom = LocalDate.now();
        ReportingAssignment created = service.reassignPrimaryManager(
                "org-1", "employee-1", "manager-2", "actor-1", effectiveFrom);

        assertThat(current.getIsActive()).isFalse();
        assertThat(current.getEffectiveTo()).isEqualTo(effectiveFrom);
        assertThat(created.getReportingManagerUserId()).isEqualTo("manager-2");
        assertThat(created.getIsActive()).isTrue();
        assertThat(created.getAssignedByUserId()).isEqualTo("actor-1");
    }

    @Test
    void reassignPrimaryManagerRejectsWhenNoActiveAssignmentExists() {
        when(repository.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                "employee-1", RelationshipType.PRIMARY_REPORTING_MANAGER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reassignPrimaryManager(
                "org-1", "employee-1", "manager-2", "actor-1", LocalDate.now()))
                .isInstanceOf(com.waypoint.exception.ResourceNotFoundException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void reassignPrimaryManagerRejectsNoOpSameManager() {
        ReportingAssignment current = ReportingAssignment.builder()
                .id("assignment-1").organizationId("org-1").employeeUserId("employee-1")
                .reportingManagerUserId("manager-1").relationshipType(RelationshipType.PRIMARY_REPORTING_MANAGER)
                .isActive(true).build();
        when(repository.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                "employee-1", RelationshipType.PRIMARY_REPORTING_MANAGER)).thenReturn(Optional.of(current));

        assertThatThrownBy(() -> service.reassignPrimaryManager(
                "org-1", "employee-1", "manager-1", "actor-1", LocalDate.now()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already reports to");

        verify(repository, never()).save(any());
    }
}
