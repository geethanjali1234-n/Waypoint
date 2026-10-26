package com.waypoint.service;

import com.waypoint.enums.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Builds the role-specific My HR welcome email (HTML via the shared template,
 * plus a plain-text alternative) for a newly created HR or Employee account.
 */
@Service
@RequiredArgsConstructor
public class MyHrCredentialEmailTemplateService {

    public static final String SUBJECT = "Your Waypoint account is ready";

    private final EmailTemplateService emailTemplateService;

    public String html(UserRole role, String organizationName, String loginId,
                       String temporaryPassword, String signInUrl) {
        return emailTemplateService.build(EmailTemplateData.builder()
                .preheader("Your Waypoint " + roleLabel(role) + " account is ready")
                .title("Welcome to Waypoint")
                .greeting("Hello,")
                .message(intro(role, organizationName) + "\n\nLogin ID: " + loginId + "\nRole: " + roleLabel(role)
                        + "\n\nYour temporary password is shown below.")
                .highlightedCode(temporaryPassword)
                .actionLabel("Sign in to Waypoint")
                .actionUrl(signInUrl)
                .secondaryInformation(instructions())
                .build());
    }

    public String text(UserRole role, String organizationName, String loginId,
                       String temporaryPassword, String signInUrl) {
        return "Welcome to Waypoint\n\n" + intro(role, organizationName)
                + "\n\nLogin ID: " + loginId
                + "\nRole: " + roleLabel(role)
                + "\nTemporary password: " + temporaryPassword
                + "\n\nSign in: " + signInUrl
                + "\n\n" + instructions() + "\n";
    }

    private String intro(UserRole role, String organizationName) {
        String organization = organizationName == null || organizationName.isBlank() ? "Your organization" : organizationName;
        return role == UserRole.HR
                ? organization + " has created your Waypoint HR account. You can sign in to manage your organization's workforce."
                : organization + " has created your Waypoint Employee account. You can sign in to view your profile and HR services.";
    }

    private String instructions() {
        return "Sign in with your Login ID and the temporary password. You will be asked to choose a new password "
                + "the first time you sign in.\n\nDo not share these credentials. Waypoint will never ask for your password.";
    }

    private String roleLabel(UserRole role) {
        return role == UserRole.HR ? "HR" : "Employee";
    }
}
