package com.waypoint.dto.myhr;

import com.waypoint.enums.AccountStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateEmployeeAccountStatusRequest {

    @NotNull
    private AccountStatus status;

    private String reassignToUserId;
}
