package com.waypoint.service.google;

import com.waypoint.config.GoogleOAuthProperties;
import com.waypoint.service.EmailAttachment;
import com.waypoint.service.EmailSenderIdentity;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Base64;
import java.util.List;
import java.util.Properties;

/** HTTPS Gmail transport for global WayPoint transactional email. It has no SMTP fallback. */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformGmailApiEmailService {
    private static final int MAX_RAW_MESSAGE_BYTES = 3_500_000;
    private final GoogleOAuthProperties properties;
    private final PlatformGoogleSenderService platformSender;
    private final GoogleOAuthClient googleClient;

    @Value("${waypoint.mail.from-name:Waypoint}") private String fromName;

    public static class Unavailable extends RuntimeException {
        private final String code;
        public Unavailable(String code) { super("Platform Gmail API unavailable: " + code); this.code = code; }
        public String code() { return code; }
    }

    public void send(String to, String subject, String html, String plain, List<EmailAttachment> attachments) {
        if (!properties.isPlatformSenderEnabled()) throw new Unavailable("FEATURE_DISABLED");
        MimeMessage message = message(to, subject, html, plain, attachments == null ? List.of() : attachments);
        String token;
        try { token = platformSender.accessToken(); }
        catch (PlatformGoogleSenderService.Unavailable unavailable) { throw new Unavailable(unavailable.code()); }
        try { googleClient.sendMessage(token, encode(message)); }
        catch (GoogleOAuthException failure) {
            // No recipient, subject, body, token, OTP, password or provider response is logged.
            log.warn("Platform Gmail API send failed [reason={}]", failure.errorCode());
            throw new Unavailable("GMAIL_" + failure.errorCode());
        }
    }

    private MimeMessage message(String to, String subject, String html, String plain, List<EmailAttachment> attachments) {
        if (!StringUtils.hasText(to) || !StringUtils.hasText(subject) || !StringUtils.hasText(html)) throw new Unavailable("INVALID_MESSAGE");
        try {
            MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
            MimeMessageHelper helper = new MimeMessageHelper(message, StringUtils.hasText(plain) || !attachments.isEmpty(), "UTF-8");
            helper.setTo(to); helper.setFrom(GoogleOAuthProperties.PLATFORM_SENDER_ADDRESS, fromName); helper.setSubject(subject);
            if (StringUtils.hasText(plain)) helper.setText(plain, html); else helper.setText(html, true);
            for (EmailAttachment attachment : attachments) helper.addAttachment(attachment.fileName(), new ByteArrayResource(attachment.content()), attachment.contentType());
            return message;
        } catch (MessagingException | UnsupportedEncodingException failure) { throw new Unavailable("MESSAGE_BUILD_FAILED"); }
    }

    private String encode(MimeMessage message) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); message.writeTo(bytes);
            if (bytes.size() > MAX_RAW_MESSAGE_BYTES) throw new Unavailable("MESSAGE_TOO_LARGE");
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        } catch (IOException | MessagingException failure) { throw new Unavailable("MESSAGE_BUILD_FAILED"); }
    }
}
