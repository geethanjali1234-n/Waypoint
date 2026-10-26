package com.waypoint.dto.myhr;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record EmployeeDocumentRequestCreateRequest(
        @NotBlank @Size(max = 80) String documentType,
        @NotBlank @Size(max = 240) String documentTitle,
        @NotBlank @Size(max = 2000) String reason,
        @FutureOrPresent LocalDate requiredBy) {
}
