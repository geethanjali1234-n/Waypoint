package com.waypoint.dto.myhr;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateEmployeeAccountRequest {
    @Valid @NotNull
    private EmployeeProfileFields profile;

    @NotBlank
    private String reportingManagerUserId;
}
