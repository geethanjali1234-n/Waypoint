package com.waypoint.repository;

import com.waypoint.entity.Timesheet;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface TimesheetRepository extends MongoRepository<Timesheet, String> {

    List<Timesheet> findByOrganizationIdOrderByPeriodStartDateDesc(String organizationId);

    List<Timesheet> findByOrganizationIdAndUserIdOrderBySubmittedAtDesc(
            String organizationId, String userId);

    List<Timesheet> findByOrganizationIdAndReportingManagerUserIdOrderBySubmittedAtDesc(
            String organizationId, String reportingManagerUserId);

    List<Timesheet> findByOrganizationIdAndSubmittedAtBetweenOrderBySubmittedAtDesc(
            String organizationId, LocalDateTime submittedFrom, LocalDateTime submittedTo);

    List<Timesheet> findByOrganizationIdAndReportingManagerUserIdAndSubmittedAtBetweenOrderBySubmittedAtDesc(
            String organizationId, String reportingManagerUserId, LocalDateTime submittedFrom, LocalDateTime submittedTo);

    /** Timesheets whose work period overlaps [periodOnOrBefore's lower bound, periodOnOrAfter]. */
    List<Timesheet> findByOrganizationIdAndUserIdAndPeriodStartDateLessThanEqualAndPeriodEndDateGreaterThanEqualOrderByPeriodStartDateDesc(
            String organizationId, String userId, LocalDate periodStartDateOnOrBefore, LocalDate periodEndDateOnOrAfter);

    List<Timesheet> findByOrganizationIdAndUserIdInAndPeriodStartDateLessThanEqualAndPeriodEndDateGreaterThanEqual(
            String organizationId, Collection<String> userIds, LocalDate periodStartDateOnOrBefore, LocalDate periodEndDateOnOrAfter);
}
