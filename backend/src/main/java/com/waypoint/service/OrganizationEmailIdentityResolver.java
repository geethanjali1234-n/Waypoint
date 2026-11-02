package com.waypoint.service;

import com.waypoint.entity.Organization;
import com.waypoint.repository.OrganizationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single place that turns an organization id into the {@link EmailSenderIdentity} used by every organization-bound
 * email (notifications, document delivery, offer letters, welcome credentials).
 *
 * <p>Organization Email is the organization's official email identity. Display name:
 * {@link OrganizationEmailBranding#senderDisplayName()} ("HR ITAP"). From address: the organization's Organization Email
 * from the database, but only when it is a valid address and {@link MailSenderAuthorization} says the SMTP provider may
 * send as it. Otherwise the message is sent from the global SMTP account and the reason is logged.</p>
 *
 * <p>Reply-To defaults to the same Organization Email, including when the From address falls back to the global SMTP
 * account, so replies reach the organization. An explicitly configured, valid {@code replyToEmail} overrides it. If
 * neither is valid there is no Reply-To header. Admin Email is only the administrator's own contact address (it is a
 * recipient for Company Admin notifications) and is never used as From or Reply-To.</p>
 *
 * <p> If the organization is unknown the global sender is used unchanged, so
 * delivery never fails because of identity lookup. Nothing is cached, so Organization Settings changes apply to the
 * next email.</p>
 */
@Service
@Slf4j
public class OrganizationEmailIdentityResolver {

    private final OrganizationRepository organizationRepository;
    private final MailSenderAuthorization senderAuthorization;
    /** Organizations already reported as "no authorized senders configured", so the INFO line is logged once each. */
    private final Set<String> reportedUnconfigured = ConcurrentHashMap.newKeySet();

    @Autowired
    public OrganizationEmailIdentityResolver(OrganizationRepository organizationRepository,
                                             MailSenderAuthorization senderAuthorization) {
        this.organizationRepository = organizationRepository;
        this.senderAuthorization = senderAuthorization;
    }

    /** Resolver that never authorizes an organization From address (display name and Reply-To only). */
    public OrganizationEmailIdentityResolver(OrganizationRepository organizationRepository) {
        this(organizationRepository, MailSenderAuthorization.none());
    }

    public EmailSenderIdentity forOrganization(String organizationId) {
        if (!StringUtils.hasText(organizationId)) return EmailSenderIdentity.none();
        try {
            return organizationRepository.findById(organizationId).map(this::forOrganization)
                    .orElseGet(() -> {
                        log.warn("Email identity fallback to global sender [reason=ORGANIZATION_NOT_FOUND]");
                        return EmailSenderIdentity.none();
                    });
        } catch (RuntimeException failure) {
            log.warn("Email identity fallback to global sender [reason=LOOKUP_FAILED, exceptionType={}]",
                    failure.getClass().getSimpleName());
            return EmailSenderIdentity.none();
        }
    }

    public EmailSenderIdentity forOrganization(Organization organization) {
        if (organization == null) return EmailSenderIdentity.none();
        String displayName = StringUtils.hasText(organization.getOrganizationName())
                ? OrganizationEmailBranding.from(organization).senderDisplayName() : null;
        return new EmailSenderIdentity(displayName, replyTo(organization), authorizedFromAddress(organization));
    }

    /**
     * A valid explicit {@code replyToEmail} if configured, else the valid Organization Email, else null (no Reply-To).
     * Admin Email is never considered.
     */
    private String replyTo(Organization organization) {
        if (StringUtils.hasText(organization.getReplyToEmail())) {
            String explicit = InAppNotificationEmailService.validEmail(organization.getReplyToEmail());
            if (explicit != null) return explicit;
            log.warn("Configured Reply-To ignored [reason=CONFIGURED_REPLY_TO_INVALID]; using the Organization Email if valid");
        }
        String organizationEmail = InAppNotificationEmailService.validEmail(organization.getOrganizationEmail());
        if (organizationEmail == null) log.warn("Email has no Reply-To [reason=ORGANIZATION_EMAIL_MISSING_OR_INVALID]");
        return organizationEmail;
    }

    /** The organization's own address when the provider may send as it; null means "send from the global account". */
    private String authorizedFromAddress(Organization organization) {
        String address = InAppNotificationEmailService.validEmail(organization.getOrganizationEmail());
        if (address == null) {
            log.warn("Email sender fallback to global account [reason=ORGANIZATION_EMAIL_MISSING_OR_INVALID]");
            return null;
        }
        if (!senderAuthorization.authorizes(address)) {
            if (senderAuthorization.isConfigured()) {
                log.warn("Email sender fallback to global account [reason=SENDER_NOT_AUTHORIZED_BY_PROVIDER_CONFIG]");
            } else if (reportedUnconfigured.add(String.valueOf(organization.getId()))) {
                // Once per organization per run: with nothing configured every email falls back, so per-email logs would flood.
                log.info("Organization emails use the global sender because no authorized sender domains are configured "
                        + "[reason=NO_AUTHORIZED_SENDERS_CONFIGURED, organizationId={}]; set MAIL_AUTHORIZED_SENDER_DOMAINS "
                        + "after the provider has verified the organization's sender", organization.getId());
            }
            return null;
        }
        return address;
    }
}
