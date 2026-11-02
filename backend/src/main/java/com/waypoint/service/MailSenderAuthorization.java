package com.waypoint.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which sender addresses the application will TRY to send as, on the operator's say-so.
 *
 * <p><strong>This is an operator assertion, not a verification.</strong> {@code MAIL_AUTHORIZED_SENDER_DOMAINS}
 * does not contact or check Gmail, SES, SendGrid or any other provider. Listing a domain here only tells the
 * application that the operator has already verified it with the provider (a verified domain with SPF/DKIM, a verified
 * single sender, or a Gmail "Send mail as" alias). If that is not true, the provider may silently rewrite the From
 * address (Gmail) or reject the message; a clear sender rejection is retried once from the global account (see
 * {@link JavaMailSenderEmailService}).</p>
 *
 * <p>The value is a comma-separated list. An entry containing {@code @} authorizes that exact address; any other entry
 * authorizes addresses at exactly that domain: subdomains are not included and wildcards ({@code *}) are not
 * supported. Entries that are neither a valid domain nor a valid address (a wildcard, a leading {@code @}, spaces,
 * a missing dot) can never match and are ignored with a startup warning. The list is empty by default, so no
 * organization address is used as From until the operator opts in.</p>
 */
@Slf4j
@Component
public class MailSenderAuthorization {

    private static final Pattern DOMAIN = Pattern.compile(
            "^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$");

    private final Set<String> entries;

    public MailSenderAuthorization(@Value("${waypoint.mail.authorized-sender-domains:}") String configured) {
        Set<String> accepted = new LinkedHashSet<>();
        if (StringUtils.hasText(configured)) {
            String[] raw = configured.split(",", -1);
            int ignored = 0;
            for (int i = 0; i < raw.length; i++) {
                String entry = raw[i].trim().toLowerCase(Locale.ROOT);
                if (entry.isEmpty()) continue;
                if (isValidEntry(entry)) {
                    accepted.add(entry);
                } else {
                    ignored++;
                    // Position only: the configured value itself is never logged.
                    log.warn("MAIL_AUTHORIZED_SENDER_DOMAINS entry #{} is not a valid domain or email address and is ignored "
                            + "(wildcards, a leading '@', spaces and bare names without a dot cannot match)", i + 1);
                }
            }
            log.info("Organization sender authorization loaded [validEntries={}, ignoredEntries={}]", accepted.size(), ignored);
        }
        this.entries = Set.copyOf(accepted);
    }

    /** No organization sender is authorized; every message uses the global SMTP account. */
    public static MailSenderAuthorization none() {
        return new MailSenderAuthorization("");
    }

    public boolean isConfigured() {
        return !entries.isEmpty();
    }

    /** @param address an already validated email address */
    public boolean authorizes(String address) {
        if (!StringUtils.hasText(address)) return false;
        String normalized = address.trim().toLowerCase(Locale.ROOT);
        int at = normalized.lastIndexOf('@');
        if (at < 1) return false;
        return entries.contains(normalized) || entries.contains(normalized.substring(at + 1));
    }

    private static boolean isValidEntry(String entry) {
        if (entry.contains("@")) return InAppNotificationEmailService.validEmail(entry) != null;
        return DOMAIN.matcher(entry).matches();
    }
}
