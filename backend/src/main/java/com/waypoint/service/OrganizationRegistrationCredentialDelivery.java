package com.waypoint.service;

import com.waypoint.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Emails the initial Company Admin Login ID and temporary password to the
 * Admin email that was verified during organization registration. The caller
 * passes the address from the stored registration, never from a request.
 * The temporary password is never logged.
 */
@Component
public class OrganizationRegistrationCredentialDelivery {

    private static final Logger logger = LoggerFactory.getLogger(OrganizationRegistrationCredentialDelivery.class);
    static final String UNAVAILABLE_MESSAGE =
            "Organization registration email delivery is not configured. Please contact support";
    static final String FAILURE_MESSAGE =
            "We could not email your Company Admin sign-in details, so your account was not created. "
                    + "Please try verifying again";
    private static final String SUBJECT = "Your Waypoint Company Admin sign-in details";

    private final EmailService emailService;
    private final EmailTemplateService emailTemplateService;
    private final boolean mailEnabled;
    private final String frontendBaseUrl;

    public OrganizationRegistrationCredentialDelivery(
            EmailService emailService,
            EmailTemplateService emailTemplateService,
            @Value("${waypoint.mail.enabled:false}") boolean mailEnabled,
            @Value("${waypoint.app.frontend-base-url:http://localhost:5173}") String frontendBaseUrl) {
        this.emailService = emailService;
        this.emailTemplateService = emailTemplateService;
        this.mailEnabled = mailEnabled;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    /** EmailService silently skips sends while mail is disabled, so refuse registration up front instead. */
    public void requireAvailable() {
        if (!mailEnabled) {
            throw new BusinessException(UNAVAILABLE_MESSAGE);
        }
    }

    /** @throws BusinessException if the email could not be sent; the cause is logged without the password */
    public void deliver(String verifiedAdminEmail, String organizationName, String adminName,
                        String loginId, String temporaryPassword) {
        requireAvailable();
        try {
            String html = emailTemplateService.build(EmailTemplateData.builder()
                    .preheader("Your Waypoint Company Admin sign-in details")
                    .title("Your organization account is ready")
                    .greeting(adminName == null || adminName.isBlank() ? null : "Hello " + adminName + ",")
                    .message("Your organization has been registered on Waypoint. Use the sign-in details below.")
                    .detailRows(List.of(
                            new EmailDetailRow("Organization", organizationName),
                            new EmailDetailRow("Login ID", loginId)))
                    .highlightedCode(temporaryPassword)
                    .actionLabel("Sign in")
                    .actionUrl(frontendBaseUrl.replaceAll("/+$", "") + "/myhr/login")
                    .secondaryInformation("The highlighted value is your temporary password. "
                            + "You will be asked to choose a new password the first time you sign in.\n\n"
                            + "If you did not register this organization, please ignore this email.")
                    .build());
            emailService.sendHtmlEmail(verifiedAdminEmail, SUBJECT, html);
        } catch (RuntimeException exception) {
            // Log only the failure type and Login ID; never the password or message body.
            logger.error("Failed to email Company Admin credentials [loginId={}, cause={}]",
                    loginId, exception.getClass().getSimpleName());
            throw new BusinessException(FAILURE_MESSAGE);
        }
    }
}
