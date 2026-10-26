package com.waypoint.service;

import com.waypoint.entity.ReportingAssignment;
import com.waypoint.entity.User;
import com.waypoint.enums.RelationshipType;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.repository.ReportingAssignmentRepository;
import com.waypoint.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ReportingAssignmentService {

    private final ReportingAssignmentRepository reportingAssignmentRepository;
    private final UserRepository userRepository;

    /**
     * Creates the initial active reporting relationship. Reassignment and
     * authorization workflows are intentionally deferred to a later phase.
     */
    public ReportingAssignment createActiveAssignment(ReportingAssignment assignment) {
        if (assignment == null
                || assignment.getRelationshipType() != RelationshipType.PRIMARY_REPORTING_MANAGER
                || !Boolean.TRUE.equals(assignment.getIsActive())) {
            throw new BusinessException("An active primary reporting-manager assignment is required");
        }

        User employee = findUser(assignment.getEmployeeUserId());
        User manager = findUser(assignment.getReportingManagerUserId());
        String organizationId = assignment.getOrganizationId();

        if (organizationId == null
                || !organizationId.equals(employee.getOrganizationId())
                || !organizationId.equals(manager.getOrganizationId())) {
            throw new BusinessException("Employee and Reporting Manager must belong to the same organization");
        }

        if (employee.getRole() != UserRole.EMPLOYEE && employee.getRole() != UserRole.HR) {
            throw new BusinessException("Reporting assignments can only be created for Employee or HR users");
        }
        boolean eligibleManager = employee.getRole() == UserRole.EMPLOYEE
                ? manager.getRole() == UserRole.HR || manager.getRole() == UserRole.COMPANY_ADMIN
                : manager.getRole() == UserRole.COMPANY_ADMIN;
        if (!eligibleManager) {
            throw new BusinessException("Reporting Manager must be an active HR or Company Admin user");
        }
        if (manager.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new BusinessException("Reporting Manager must be an active HR or Company Admin user");
        }

        if (reportingAssignmentRepository
                .findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                        assignment.getEmployeeUserId(), assignment.getRelationshipType())
                .isPresent()) {
            throw new BusinessException("Employee already has an active primary Reporting Manager");
        }

        LocalDateTime now = LocalDateTime.now();
        assignment.setCreatedAt(assignment.getCreatedAt() == null ? now : assignment.getCreatedAt());
        assignment.setUpdatedAt(now);

        try {
            return reportingAssignmentRepository.save(assignment);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException("Employee already has an active primary Reporting Manager");
        }
    }

    /**
     * Moves an employee's active primary reporting manager to a new manager.
     * The current active assignment is closed (isActive=false, effectiveTo set)
     * and a fresh active assignment is created through
     * {@link #createActiveAssignment(ReportingAssignment)} so every role,
     * organization, and eligibility rule is re-applied. If creating the new
     * assignment fails the previous assignment is restored to active.
     */
    public ReportingAssignment reassignPrimaryManager(
            String organizationId,
            String employeeUserId,
            String newManagerUserId,
            String actorUserId,
            LocalDate effectiveFrom) {

        ReportingAssignment current = reportingAssignmentRepository
                .findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                        employeeUserId, RelationshipType.PRIMARY_REPORTING_MANAGER)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Employee does not have an active primary Reporting Manager"));

        if (current.getReportingManagerUserId().equals(newManagerUserId)) {
            throw new BusinessException("Employee already reports to this Reporting Manager");
        }

        LocalDate closeDate = effectiveFrom == null ? LocalDate.now() : effectiveFrom;
        LocalDateTime now = LocalDateTime.now();

        current.setIsActive(false);
        current.setEffectiveTo(closeDate);
        current.setUpdatedAt(now);
        reportingAssignmentRepository.save(current);

        try {
            return createActiveAssignment(ReportingAssignment.builder()
                    .organizationId(organizationId)
                    .employeeUserId(employeeUserId)
                    .reportingManagerUserId(newManagerUserId)
                    .relationshipType(RelationshipType.PRIMARY_REPORTING_MANAGER)
                    .isActive(true)
                    .effectiveFrom(closeDate)
                    .assignedByUserId(actorUserId)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        } catch (RuntimeException exception) {
            current.setIsActive(true);
            current.setEffectiveTo(null);
            current.setUpdatedAt(LocalDateTime.now());
            reportingAssignmentRepository.save(current);
            throw exception;
        }
    }

    private User findUser(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }
}
