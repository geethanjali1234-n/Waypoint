package com.waypoint.dto.myhr;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

public record DocumentRequestCreateRequest(
        @NotBlank String requestedForUserId,
        @NotBlank String category,
        @NotBlank String priority,
        @FutureOrPresent LocalDate requiredBy,
        @Size(max = 2000) String instructions,
        @NotEmpty @Size(max = 10) List<@NotBlank @Size(max = 240) String> documents) {
}
