package com.waypoint.service.google;

import com.waypoint.config.GoogleOAuthProperties;
import com.waypoint.entity.Organization;
import com.waypoint.entity.OrganizationGoogleConnection;
import com.waypoint.enums.GoogleConnectionStatus;
import com.waypoint.repository.OrganizationGoogleConnectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * Decides, for one organization, whether its mail may be offered to the Gmail API: the platform feature is on, the
 * organization has a CONNECTED Google account holding a stored token, and that account's domain still equals the
 * Organization Email's domain. Looks at that organization's connection only (by its own id). Returns the connected
 * account's address, which becomes the From address on that path.
 *
 * <p>This is a quick pre-check used while resolving the sender identity. {@link GmailApiEmailService} repeats every
 * check immediately before sending, so a stale answer can never cause a send from the wrong account.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleSenderEligibility {

    private final GoogleOAuthProperties properties;
    private final OrganizationGoogleConnectionRepository connections;

    /** Never throws: any problem means "not eligible", and the message goes by the existing SMTP path. */
    public Optional<String> eligibleSender(Organization organization) {
        if (!properties.isEnabled() || organization == null || !StringUtils.hasText(organization.getId())) {
            return Optional.empty();
        }
        try {
            OrganizationGoogleConnection connection = connections.findByOrganizationId(organization.getId()).orElse(null);
            if (connection == null || connection.getStatus() == GoogleConnectionStatus.DISCONNECTED) return Optional.empty();
            String problem = problemWith(organization, connection);
            if (problem != null) {
                log.warn("Google sender not used [organizationId={}, reason={}]", organization.getId(), problem);
                return Optional.empty();
            }
            return Optional.of(connection.getGoogleAccountEmail().trim().toLowerCase(java.util.Locale.ROOT));
        } catch (RuntimeException failure) {
            log.warn("Google sender lookup failed [organizationId={}, exceptionType={}]", organization.getId(),
                    failure.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * Why Google cannot be used for this organization right now, as a short code, or null when it can. Used by the test
     * email to report an accurate reason; it reads only this organization's own connection.
     */
    public String diagnose(Organization organization) {
        if (!properties.isEnabled()) return "FEATURE_DISABLED";
        if (organization == null || !StringUtils.hasText(organization.getId())) return "ORGANIZATION_NOT_FOUND";
        OrganizationGoogleConnection connection = connections.findByOrganizationId(organization.getId()).orElse(null);
        if (connection == null) return "NOT_CONNECTED";
        if (connection.getStatus() == GoogleConnectionStatus.DISCONNECTED) return "NOT_CONNECTED";
        return problemWith(organization, connection);
    }

    /** A short reason code when the connection must not be used for this organization, or null when it may be. */
    static String problemWith(Organization organization, OrganizationGoogleConnection connection) {
        if (!organization.getId().equals(connection.getOrganizationId())) return "ORGANIZATION_MISMATCH";
        if (connection.getStatus() != GoogleConnectionStatus.CONNECTED) return "CONNECTION_" + connection.getStatus();
        if (!StringUtils.hasText(connection.getRefreshTokenCiphertext())) return "NO_STORED_TOKEN";
        if (!StringUtils.hasText(connection.getGoogleAccountEmail())) return "NO_ACCOUNT_EMAIL";
        String organizationDomain = GoogleConnectionService.domainOf(organization.getOrganizationEmail());
        String accountDomain = GoogleConnectionService.domainOf(connection.getGoogleAccountEmail());
        if (organizationDomain == null || !organizationDomain.equals(accountDomain)) return "DOMAIN_MISMATCH";
        if (!GoogleConnectionService.accountMatchesOrganizationEmail(organization.getOrganizationEmail(), connection.getGoogleAccountEmail())) {
            return "ACCOUNT_EMAIL_MISMATCH";
        }
        return null;
    }
}
