package com.waypoint.dto.myhr;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDate;

@Data
public class ChangeReportingManagerRequest {

    @NotBlank
    private String newReportingManagerUserId;

    private LocalDate effectiveDate;

    private String reason;
}
