package com.waypoint.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ChangeAccountEmailRequest {

    @NotBlank(message = "New email is required")
    @Email(message = "Enter a valid email address")
    private String newEmail;

    @NotBlank(message = "Current password is required")
    private String currentPassword;
}
