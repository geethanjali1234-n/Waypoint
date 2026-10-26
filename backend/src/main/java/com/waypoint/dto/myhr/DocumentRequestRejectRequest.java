package com.waypoint.dto.myhr;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DocumentRequestRejectRequest(@NotBlank @Size(max = 1000) String reason) {
}
