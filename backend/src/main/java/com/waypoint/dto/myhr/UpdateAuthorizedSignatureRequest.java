package com.waypoint.dto.myhr;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** Body for PUT /api/myhr/organization-settings/authorized-signature. */
public record UpdateAuthorizedSignatureRequest(
        @NotBlank(message = "Authorized Signatory Name is required")
        @Size(max = 160, message = "Authorized Signatory Name must be 160 characters or fewer")
        String signatoryName,

        @NotBlank(message = "Authorized Signatory Title is required")
        @Size(max = 160, message = "Authorized Signatory Title must be 160 characters or fewer")
        String signatoryTitle,

        @NotNull(message = "Effective Date is required")
        LocalDate effectiveDate,

        boolean active) {
}
