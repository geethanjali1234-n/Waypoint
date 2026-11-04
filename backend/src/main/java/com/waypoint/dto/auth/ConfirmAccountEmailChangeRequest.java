package com.waypoint.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** Only the code. The new address is read from the stored pending change, never from the request. */
@Data
public class ConfirmAccountEmailChangeRequest {

    @NotBlank(message = "Verification code is required")
    @Pattern(regexp = "^\\d{6}$", message = "Enter the 6-digit verification code")
    private String otp;
}
