package com.waypoint.service;

import com.waypoint.enums.UserRole;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Emails a new HR/Employee their Login ID and temporary password through the
 * existing {@link EmailService}. Returns whether the email was handed to SMTP
 * and never throws, so a mail problem cannot undo an already-saved account.
 * The password is never logged.
 */
@Component
@RequiredArgsConstructor
public class MyHrCredentialDelivery {

    private static final Logger logger = LoggerFactory.getLogger(MyHrCredentialDelivery.class);

    private final EmailService emailService;
    private final MyHrCredentialEmailTemplateService templates;

    @Value("${waypoint.mail.enabled:false}")
    private boolean mailEnabled;

    @Value("${waypoint.app.frontend-base-url:http://localhost:5173}")
    private String frontendBaseUrl;

    /** @param recipientEmail the validated email stored on the new account */
    public boolean deliver(String recipientEmail, UserRole role, String organizationName,
                           String loginId, String temporaryPassword) {
        if (!mailEnabled) {
            // EmailService silently skips sends while mail is disabled; do not report that as delivered.
            logger.warn("Welcome email not sent [loginId={}, reason=MAIL_DISABLED]", loginId);
            return false;
        }
        try {
            String signInUrl = frontendBaseUrl.replaceAll("/+$", "") + "/myhr/login";
            emailService.sendHtmlEmail(recipientEmail, MyHrCredentialEmailTemplateService.SUBJECT,
                    templates.html(role, organizationName, loginId, temporaryPassword, signInUrl),
                    templates.text(role, organizationName, loginId, temporaryPassword, signInUrl));
            return true;
        } catch (RuntimeException exception) {
            logger.error("Failed to email welcome credentials [loginId={}, cause={}]",
                    loginId, exception.getClass().getSimpleName());
            return false;
        }
    }
}
