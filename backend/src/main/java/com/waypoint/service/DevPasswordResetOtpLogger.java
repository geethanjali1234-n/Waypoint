package com.waypoint.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class DevPasswordResetOtpLogger {

    private static final Logger logger = LoggerFactory.getLogger(DevPasswordResetOtpLogger.class);
    private final boolean enabled;

    public DevPasswordResetOtpLogger(@Value("${waypoint.myhr.dev-credentials.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public void output(String email, String otp, LocalDateTime expiresAt) {
        if (!enabled) {
            return;
        }
        logger.info("MY HR DEV PASSWORD RESET OTP\nEmail: {}\nOTP: {}\nExpires: {}", email, otp, expiresAt);
    }
}
