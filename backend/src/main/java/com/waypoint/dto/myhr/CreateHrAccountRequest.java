package com.waypoint.dto.myhr;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateHrAccountRequest {
    @Valid @NotNull
    private EmployeeProfileFields profile;
}
