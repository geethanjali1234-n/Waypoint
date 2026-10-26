package com.waypoint.service;

import com.waypoint.enums.OtpPurpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class DevOrganizationRegistrationOtpLogger {

    private static final Logger logger = LoggerFactory.getLogger(DevOrganizationRegistrationOtpLogger.class);
    private final boolean enabled;

    public DevOrganizationRegistrationOtpLogger(
            @Value("${app.auth.organization-registration-dev-otp-enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public void output(OtpPurpose purpose, String otp, LocalDateTime expiresAt) {
        if (!enabled || purpose != OtpPurpose.ORGANIZATION_REGISTRATION) {
            return;
        }
        logger.info("WAYPOINT DEVELOPMENT OTP\nPurpose: {}\nOTP: {}\nExpires: {}", purpose, otp, expiresAt);
    }
}
