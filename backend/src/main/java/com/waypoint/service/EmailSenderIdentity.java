package com.waypoint.service;

import org.springframework.util.StringUtils;

/**
 * The organization-specific part of an outgoing email.
 *
 * <p>{@code fromAddress} is set only by {@link OrganizationEmailIdentityResolver}, and only for an organization email
 * the SMTP provider is authorized to send as (see {@link MailSenderAuthorization}). When it is null the {@code From}
 * <em>address</em> is the global SMTP account ({@code MAIL_FROM_ADDRESS}): one SMTP login can only send as itself, and
 * providers such as Gmail rewrite any other sender. The organization is then identified by the {@code From} display
 * name and by {@code Reply-To}, which defaults to the Organization Email so replies still reach the organization. None
 * of these fields ever changes the {@code To} recipient.</p>
 *
 * @param displayName visible {@code From} name, or null to use the configured global name
 * @param replyTo     the organization's explicit reply address, else its Organization Email, or null to set no Reply-To
 *                    header (never taken from Admin Email)
 * @param fromAddress authorized organization address to send as, or null to send from the global SMTP account
 */
public record EmailSenderIdentity(String displayName, String replyTo, String fromAddress) {

    private static final EmailSenderIdentity NONE = new EmailSenderIdentity(null, null, null);

    /** Display name and Reply-To only; the From address stays the global SMTP account. */
    public EmailSenderIdentity(String displayName, String replyTo) {
        this(displayName, replyTo, null);
    }

    /** Global sender only: no organization display name, Reply-To or From address. */
    public static EmailSenderIdentity none() {
        return NONE;
    }

    public static EmailSenderIdentity ofDisplayName(String displayName) {
        return StringUtils.hasText(displayName) ? new EmailSenderIdentity(displayName, null, null) : NONE;
    }
}
