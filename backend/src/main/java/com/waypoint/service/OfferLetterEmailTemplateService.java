package com.waypoint.service;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pre-populated offer letter email (subject, plain-text message, attachment
 * name) and the shared professional plain-text-to-HTML rendering used for
 * employee document delivery.
 *
 * <p>HR edits the message as plain text, so the rendered HTML is built only
 * from escaped text: paragraphs, line breaks, {@code **bold**} markers, and
 * clickable email addresses / http(s) links. Callers cannot inject markup.
 * The shared {@link EmailTemplateService} renders Waypoint's branded card
 * layout with a single message paragraph, which does not fit a multi-paragraph
 * personal letter with a signature block, so it is not used here.</p>
 */
@Service
public class OfferLetterEmailTemplateService {

    private static final Pattern INLINE_TOKEN = Pattern.compile(
            "\\*\\*(?<bold>.+?)\\*\\*"
                    + "|(?<email>[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})"
                    + "|(?<url>https?://[^\\s<>\"]+)");
    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\n\\s*\\n");
    private static final String TRAILING_URL_PUNCTUATION = ".,;:!?)";
    private static final int MAX_FILE_NAME_STEM_LENGTH = 80;

    /** The sending address comes from MAIL_FROM_ADDRESS; this is its tenant-specific display name. */
    public String senderDisplayName(OrganizationEmailBranding branding) {
        return brand(branding).senderDisplayName();
    }

    /** The designation lives in the body, not the subject. */
    public String defaultSubject(OrganizationEmailBranding branding) {
        return "Congratulations! Offer with " + brand(branding).organizationName();
    }

    /** Both email addresses in the body are the organization's email; without one, they point to the HR team. */
    public String defaultMessage(String employeeName, String designation, OrganizationEmailBranding branding) {
        String name = StringUtils.hasText(employeeName) ? employeeName.trim() : "there";
        String position = StringUtils.hasText(designation) ? "as a **" + designation.trim() + "**" : "a position";
        OrganizationEmailBranding company = brand(branding);
        String email = company.organizationEmail();
        String timesheets = StringUtils.hasText(email) ? "to our email ID: " + email : "to our HR team";
        String enquiries = StringUtils.hasText(email) ? "to " + email : "to the HR team";
        return """
                Hi %s,

                Hope this email finds you well.

                We are pleased to offer you %s with our organization. Please find the attached Offer Letter and revert to us with your signature as an acceptance. Once you accept the offer, please send us the countersigned documents and fill in onboarding documents. Also please be informed that %s's payroll schedule is on a calendar-monthly basis.

                As you will be paid for client managers approved timesheets/hrs, we need your approved timesheets on monthly basis %s

                To set up your payroll, we need the attached forms to be filled in along with your signatures as required and the scanned copies along with supporting documents: Driver's License, SSN Card, Passport Copy, VISA Stamped Page, VOID check copy & Work authorization document (EAD/H1B with I94/CPT I20).

                If you need any verification letters or any enquiries please direct them %s.

                Please let us know if you have any questions.

                We wish you very best in your job!

                Thanks & Regards
                %s
                """.formatted(name, position, company.organizationName(), timesheets, enquiries, signatureBlock(company));
    }

    public String contactSentence(OrganizationEmailBranding branding) {
        OrganizationEmailBranding company = brand(branding);
        return StringUtils.hasText(company.organizationEmail())
                ? "If you have any questions, please contact us at " + company.organizationEmail() + "."
                : "If you have any questions, please contact the HR team.";
    }

    public String signatureBlock(OrganizationEmailBranding branding) {
        OrganizationEmailBranding company = brand(branding);
        // Starts on the line right under "Thanks & Regards"; the company details follow as their own block.
        StringBuilder signature = new StringBuilder("HR Team\n\n").append(company.organizationName());
        appendLine(signature, company.address());
        if (StringUtils.hasText(company.phoneNumber())) appendLine(signature, "Ph: " + company.phoneNumber());
        appendLine(signature, company.website());
        return signature.toString();
    }

    private OrganizationEmailBranding brand(OrganizationEmailBranding branding) {
        return branding == null ? OrganizationEmailBranding.generic() : branding;
    }

    private void appendLine(StringBuilder value, String line) {
        if (StringUtils.hasText(line)) value.append('\n').append(line);
    }

    /** e.g. "Venkata Naga Sai Vasavi Anna" -> "Venkata_Naga_Sai_Vasavi_Anna_Offer_Letter.pdf". */
    public String attachmentFileName(String employeeName) {
        String stem = employeeName == null ? "" : Normalizer.normalize(employeeName, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^A-Za-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (stem.length() > MAX_FILE_NAME_STEM_LENGTH) {
            stem = stem.substring(0, MAX_FILE_NAME_STEM_LENGTH).replaceAll("_+$", "");
        }
        return stem.isEmpty() ? "Offer_Letter.pdf" : stem + "_Offer_Letter.pdf";
    }

    /** Name the recipient sees in the email, e.g. "Offer Letter - Venkata Naga Sai Vasavi Anna.pdf". */
    public String emailAttachmentName(String employeeName) {
        String name = employeeName == null ? "" : Normalizer.normalize(employeeName, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^A-Za-z0-9 .'-]+", " ")
                .replaceAll("\\s+", " ").strip();
        if (name.length() > MAX_FILE_NAME_STEM_LENGTH) name = name.substring(0, MAX_FILE_NAME_STEM_LENGTH).strip();
        return name.isEmpty() ? "Offer Letter.pdf" : "Offer Letter - " + name + ".pdf";
    }

    public String renderHtml(String message) {
        String normalized = message == null ? "" : message.replace("\r\n", "\n").replace("\r", "\n").strip();
        StringBuilder body = new StringBuilder();
        for (String paragraph : PARAGRAPH_BREAK.split(normalized)) {
            if (paragraph.isBlank()) continue;
            String[] lines = paragraph.strip().split("\n");
            body.append("<p style=\"margin:0 0 13px;\">");
            for (int index = 0; index < lines.length; index++) {
                if (index > 0) body.append("<br>");
                body.append(renderInline(lines[index].strip()));
            }
            body.append("</p>");
        }
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0"></head>
                <body style="margin:0;padding:0;background-color:#ffffff;">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="width:100%%;background-color:#ffffff;">
                <tr><td align="left" style="padding:0;font-family:Arial,Helvetica,sans-serif;font-size:13px;line-height:1.4;color:#222222;text-align:left;">%s</td></tr>
                </table>
                </body>
                </html>
                """.formatted(body);
    }

    /** Bold segments keep their links clickable but cannot nest further bold markers. */
    private String renderInline(String text) {
        StringBuilder html = new StringBuilder();
        Matcher matcher = INLINE_TOKEN.matcher(text);
        int position = 0;
        while (matcher.find()) {
            html.append(escape(text.substring(position, matcher.start())));
            if (matcher.group("bold") != null) {
                html.append("<strong>").append(renderInline(matcher.group("bold").replace("**", ""))).append("</strong>");
                position = matcher.end();
            } else if (matcher.group("email") != null) {
                String email = matcher.group("email");
                html.append("<a href=\"mailto:").append(escape(email)).append("\" style=\"color:#0f4a3a;\">")
                        .append(escape(email)).append("</a>");
                position = matcher.end();
            } else {
                String url = trimTrailingPunctuation(matcher.group("url"));
                html.append("<a href=\"").append(escape(url)).append("\" style=\"color:#0f4a3a;\">")
                        .append(escape(url)).append("</a>");
                position = matcher.start() + url.length();
            }
        }
        html.append(escape(text.substring(position)));
        return html.toString();
    }

    private String trimTrailingPunctuation(String url) {
        int end = url.length();
        while (end > 0 && TRAILING_URL_PUNCTUATION.indexOf(url.charAt(end - 1)) >= 0) end--;
        return url.substring(0, end);
    }

    private String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
