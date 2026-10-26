package com.waypoint.dto.myhr;

import com.waypoint.enums.ProfileChangeField;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body for an EMPLOYEE/HR self-service profile change request. requestedValue
 * is typed Object (not String) because ADDRESS requests must carry a
 * structured {streetAddress, city, state, zipCode} map - the same shape
 * ProfileChangeRequestService.applyAddress already expects on approval.
 */
public record CreateProfileChangeRequestRequest(
        @NotNull ProfileChangeField fieldName,
        Object requestedValue,
        @Size(max = 1000) String requestReason) {
}
