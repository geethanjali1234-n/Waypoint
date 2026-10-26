package com.waypoint.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginRequest {

    @Email(message = "Email must be valid")
    private String email;

    private String loginId;

    @NotBlank(message = "Password is required")
    private String password;

    @AssertTrue(message = "Email or Employee ID is required")
    public boolean hasIdentifier() {
        return (email != null && !email.isBlank()) || (loginId != null && !loginId.isBlank());
    }
}
