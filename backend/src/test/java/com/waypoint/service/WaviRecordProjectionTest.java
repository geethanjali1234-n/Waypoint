package com.waypoint.service;

import com.waypoint.entity.DocumentRequest;
import com.waypoint.entity.ProfileChangeRequest;
import com.waypoint.entity.Timesheet;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.ProfileChangeField;
import com.waypoint.enums.ProfileChangeStatus;
import com.waypoint.enums.TimesheetStatus;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.repository.DocumentRequestRepository;
import com.waypoint.repository.ProfileChangeRequestRepository;
import com.waypoint.repository.TimesheetRepository;
import com.waypoint.security.MyHrAuthenticatedUserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WaviRecordProjectionTest {
    @Mock MyHrAuthenticatedUserService authentication;
    @Mock TimesheetRepository timesheets;
    @Mock ProfileChangeRequestRepository profiles;
    @Mock DocumentRequestRepository documents;
    @InjectMocks TimesheetService timesheetService;
    @InjectMocks ProfileChangeRequestService profileService;
    @InjectMocks DocumentRequestService documentService;

    private MyHrAuthenticatedUserService.AuthenticatedMyHrUser employee() {
        return new MyHrAuthenticatedUserService.AuthenticatedMyHrUser(
                "u1", "login", "user@example.invalid", UserRole.EMPLOYEE, "org1", AccountStatus.ACTIVE, false);
    }

    @Test void ownTimesheetPeriodsRejectRowsFromAnotherUserOrOrganization() {
        when(authentication.require(eq("u1"), anySet(), eq(false))).thenReturn(employee());
        var day = LocalDate.of(2026, 1, 1);
        when(timesheets.findByOrganizationIdAndUserIdAndPeriodStartDateLessThanEqualAndPeriodEndDateGreaterThanEqualOrderByPeriodStartDateDesc("org1", "u1", day, day))
                .thenReturn(List.of(
                        Timesheet.builder().organizationId("org1").userId("u1").periodStartDate(day).periodEndDate(day).status(TimesheetStatus.SUBMITTED).build(),
                        Timesheet.builder().organizationId("org2").userId("u1").periodStartDate(day).periodEndDate(day).build(),
                        Timesheet.builder().organizationId("org1").userId("u2").periodStartDate(day).periodEndDate(day).build()));
        assertThat(timesheetService.getOwnStatusesOverlapping("u1", day, day)).hasSize(1);
    }

    @Test void profileHistoryNeverInventsEffectiveTimeAndFiltersOtherUsers() {
        when(authentication.require(eq("u1"), anySet(), eq(false))).thenReturn(employee());
        var requested = LocalDateTime.of(2026, 1, 1, 10, 0);
        var processed = requested.plusDays(1);
        when(profiles.findByOrganizationIdAndRequesterUserIdOrderByRequestedAtDesc("org1", "u1"))
                .thenReturn(List.of(
                        ProfileChangeRequest.builder().organizationId("org1").requesterUserId("u1").fieldName(ProfileChangeField.NAME).status(ProfileChangeStatus.APPROVED).requestedAt(requested).processedAt(processed).build(),
                        ProfileChangeRequest.builder().organizationId("org2").requesterUserId("u1").fieldName(ProfileChangeField.NAME).requestedAt(requested.plusDays(5)).build()));
        var history = profileService.mostRecentOwnHistoryForField("u1", ProfileChangeField.NAME).orElseThrow();
        assertThat(history.processedAt()).isEqualTo(processed);
        assertThat(history.effectiveAt()).isNull();
    }

    @Test void documentHistoryOnlyContainsTheEmployeesOwnOrganizationRecords() {
        when(authentication.require(eq("u1"), anySet(), eq(false))).thenReturn(employee());
        when(documents.findByOrganizationIdOrderByRequestedAtDesc("org1")).thenReturn(List.of(
                DocumentRequest.builder().organizationId("org1").requestedByUserId("u1").build(),
                DocumentRequest.builder().organizationId("org2").requestedByUserId("u1").build(),
                DocumentRequest.builder().organizationId("org1").requestedByUserId("u2").requestedForUserId("u3").build()));
        assertThat(documentService.listOwnHistory("u1")).hasSize(1);
    }

    @Test void deniedActorCannotReadDocumentHistory() {
        when(authentication.require(eq("u1"), anySet(), eq(false))).thenThrow(new ForbiddenException("denied"));
        assertThatThrownBy(() -> documentService.listOwnHistory("u1")).isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(documents);
    }
}
