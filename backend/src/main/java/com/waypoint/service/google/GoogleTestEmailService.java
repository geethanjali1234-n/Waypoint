package com.waypoint.service.google;

import com.waypoint.config.GoogleOAuthProperties;
import com.waypoint.dto.myhr.GoogleTestEmailResponse;
import com.waypoint.entity.Organization;
import com.waypoint.enums.AuditActionType;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.repository.OrganizationRepository;
import com.waypoint.security.MyHrAuthenticatedUserService;
import com.waypoint.service.AuditService;
import com.waypoint.service.EmailSenderIdentity;
import com.waypoint.service.EmailTemplateData;
import com.waypoint.service.EmailTemplateService;
import com.waypoint.service.OrganizationEmailIdentityResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Sends one test email through an organization's connected Google account so a Company Admin can verify the real
 * connection.
 *
 * <p>Deliberately narrow:</p>
 * <ul>
 *   <li>Company Admin only; the organization is the authenticated admin's own.</li>
 *   <li>The recipient is never an input. It is always the caller's own organization's Organization Email (never
 *       {@code User.email} or the Admin Email setting), so the action cannot be used to send to anyone else, and the
 *       subject and body are fixed.</li>
 *   <li>Reply-To is always that same Organization Email; {@code Organization.replyToEmail} is deliberately ignored.</li>
 *   <li>It uses {@link GmailApiEmailService} directly and has no SMTP sender to fall back to: a failure is reported
 *       as a failure, because falling back would hide exactly the problem the test exists to find.</li>
 *   <li>Rate limited per organization.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleTestEmailService {

    static final String SUBJECT = "WayPoint test email";
    private static final Set<UserRole> MANAGE_ROLES = Set.of(UserRole.COMPANY_ADMIN);
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@,;<>]+@[^\\s@,;<>]+\\.[^\\s@,;<>]+$");

    private final GoogleOAuthProperties properties;
    private final MyHrAuthenticatedUserService authenticatedUserService;
    private final OrganizationRepository organizations;
    private final OrganizationEmailIdentityResolver identityResolver;
    private final GoogleSenderEligibility eligibility;
    private final GmailApiEmailService gmail;
    private final EmailTemplateService templates;
    private final GoogleRateLimits rateLimits;
    private final AuditService auditService;

    public GoogleTestEmailResponse sendTestEmail(String actorUserId) {
        var actor = authenticatedUserService.require(actorUserId, MANAGE_ROLES, false);
        if (!properties.isEnabled()) throw new BusinessException("Google email connection is not enabled");
        if (!rateLimits.allowTestEmail(actor.organizationId())) {
            throw new BusinessException("Too many test emails. Please wait a few minutes and try again.");
        }
        Organization organization = organizations.findById(actor.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
        String recipient = organizationEmail(organization);
        if (recipient == null) return failure("RECIPIENT_UNAVAILABLE", null, null);

        String problem = eligibility.diagnose(organization);
        if (problem != null) return failure(problem, recipient, null);
        EmailSenderIdentity resolved = identityResolver.forOrganization(organization);
        if (!resolved.usesGoogleSender()) return failure("NOT_ELIGIBLE", recipient, null);
        // Reply-To is pinned to the Organization Email for this test, whatever replyToEmail says.
        EmailSenderIdentity identity = new EmailSenderIdentity(resolved.displayName(), recipient, resolved.fromAddress(),
                resolved.gmailOrganizationId(), resolved.gmailSenderAddress());

        try {
            gmail.send(identity, recipient, SUBJECT, html(organization), null, List.of());
        } catch (GmailApiEmailService.Unavailable notSent) {
            log.warn("Google test email not sent [organizationId={}, reason={}]", organization.getId(), notSent.code());
            return failure(notSent.code(), recipient, identity.gmailSenderAddress());
        } catch (GmailApiEmailService.DeliveryUncertain uncertain) {
            log.warn("Google test email delivery uncertain [organizationId={}]", organization.getId());
            return failure(GoogleOAuthClient.NO_RESPONSE, recipient, identity.gmailSenderAddress());
        } catch (RuntimeException failure) {
            log.warn("Google test email failed [organizationId={}, exceptionType={}]", organization.getId(), failure.getClass().getSimpleName());
            return failure("SEND_FAILED", recipient, identity.gmailSenderAddress());
        }
        auditService.record(actor, AuditActionType.GOOGLE_EMAIL_TEST_SENT, "OrganizationGoogleConnection", organization.getId(),
                identity.gmailSenderAddress(), "Google email test sent", null, null);
        return new GoogleTestEmailResponse(true, "SENT", recipient, identity.gmailSenderAddress());
    }

    /** The organization's own Organization Email. Never an input, {@code User.email} or the Admin Email setting. */
    private static String organizationEmail(Organization organization) {
        if (!StringUtils.hasText(organization.getOrganizationEmail())) return null;
        String address = organization.getOrganizationEmail().trim();
        return address.length() <= 254 && EMAIL.matcher(address).matches() ? address : null;
    }

    private String html(Organization organization) {
        return templates.build(EmailTemplateData.builder()
                .preheader("Your Google email connection works")
                .title("Your Google email connection works")
                .message("This is a test message from WayPoint. It was sent through the Google account connected to "
                        + (StringUtils.hasText(organization.getOrganizationName()) ? organization.getOrganizationName() : "your organization")
                        + ". No action is needed.")
                .build());
    }

    private static GoogleTestEmailResponse failure(String code, String recipient, String sender) {
        return new GoogleTestEmailResponse(false, code, recipient, sender);
    }
}
