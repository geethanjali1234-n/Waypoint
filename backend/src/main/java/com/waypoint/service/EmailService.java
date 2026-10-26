package com.waypoint.service;

/**
 * Reusable, provider-agnostic email sender. Callers pass fully-rendered
 * HTML — this service has no knowledge of notification types or templates.
 */
public interface EmailService {

    /** Message of the IllegalStateException thrown when an explicit send is attempted while delivery is disabled. */
    String DELIVERY_DISABLED_MESSAGE = "Email delivery is disabled";

    void sendHtmlEmail(String to, String subject, String htmlBody);

    /** As above, with a plain-text alternative for clients that do not render HTML. */
    default void sendHtmlEmail(String to, String subject, String htmlBody, String plainTextBody) {
        sendHtmlEmail(to, subject, htmlBody);
    }

    /**
     * Sends an HTML email with one file attached. Unlike {@link #sendHtmlEmail},
     * this is used for explicit, user-initiated sends, so it fails instead of
     * silently skipping when email delivery is disabled.
     */
    void sendHtmlEmailWithAttachment(String to, String subject, String htmlBody,
                                     String attachmentFileName, byte[] attachmentContent, String attachmentContentType);

    /** Same as {@link #sendHtmlEmailWithAttachment} but with several attachments; all or nothing. */
    void sendHtmlEmailWithAttachments(String to, String subject, String htmlBody, java.util.List<EmailAttachment> attachments);

    /** As above, with a sender display name that overrides the configured one (the address is unchanged). */
    void sendHtmlEmailWithAttachments(String to, String subject, String htmlBody, java.util.List<EmailAttachment> attachments,
                                      String fromDisplayName);
}
