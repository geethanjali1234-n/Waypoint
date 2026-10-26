package com.waypoint.dto.myhr;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The document facts HR submits for Admin review (the file itself is uploaded alongside). The designation only applies to offer letters.
 * {@code authorizedSignature} is set for a generated PDF whose signature block names the company admin (the Organization Settings admin account): the PDF
 * is submitted unsigned, and the server applies the signatory's stored signature at that position when the signatory approves it.
 */
public record OfferLetterApprovalSubmitRequest(
        @NotBlank @Size(max = 160) String employeeName,
        @Size(max = 160) String designation,
        @Size(max = 40) String offerType,
        @Size(max = 40) String employmentCategory,
        @Size(max = 40) String joiningDate,
        @Size(max = 40) String issueDate,
        @Size(max = 80) String payRate,
        @Size(max = 200) String workLocation,
        @Size(max = 8000) String rolesAndResponsibilities,
        @Size(max = 100000) String formJson,
        @Valid I983SignaturePlacement authorizedSignature) {
}
