package com.waypoint.service;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Renders dependency-free, email-compatible HTML for Waypoint transactional
 * emails. Callers provide plain-text content and cannot inject HTML.
 */
@Service
public class EmailTemplateService {

    private static final String DEFAULT_FOOTER = "© Waypoint";

    public String build(EmailTemplateData data) {
        if (data == null) {
            throw new IllegalArgumentException("Email template data is required");
        }
        if (!StringUtils.hasText(data.getTitle())) {
            throw new IllegalArgumentException("Email title is required");
        }
        if (!StringUtils.hasText(data.getMessage())) {
            throw new IllegalArgumentException("Email message is required");
        }

        String actionUrl = validateAndNormalizeActionUrl(data.getActionUrl());
        if (actionUrl != null && !StringUtils.hasText(data.getActionLabel())) {
            throw new IllegalArgumentException("Action label is required when action URL is supplied");
        }

        StringBuilder content = new StringBuilder();
        appendOptionalParagraph(content, data.getGreeting(), "font-weight:600;margin:0 0 16px;");
        content.append("<p style=\"margin:0 0 20px;\">")
                .append(escapeText(data.getMessage()))
                .append("</p>");

        if (StringUtils.hasText(data.getHighlightedCode())) {
            content.append("""
                    <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" border="0" style="margin:4px 0 24px;">
                      <tr>
                        <td align="center" style="background:#f8faf8;border:1px solid #dce5df;border-radius:12px;padding:22px 16px;">
                          <span style="color:#0b3e31;font-family:Consolas,'Courier New',monospace;font-size:32px;font-weight:700;letter-spacing:8px;line-height:1.2;">%s</span>
                        </td>
                      </tr>
                    </table>
                    """.formatted(escapeText(data.getHighlightedCode())));
        }

        appendDetailRows(content, data.getDetailRows());

        if (actionUrl != null) {
            content.append("""
                    <table role="presentation" cellspacing="0" cellpadding="0" border="0" style="margin:4px 0 24px;">
                      <tr>
                        <td bgcolor="#0f4a3a" style="border-radius:8px;">
                          <a href="%s" style="background:#0f4a3a;border:1px solid #0f4a3a;border-radius:8px;color:#ffffff;display:inline-block;font-family:Arial,'Segoe UI',sans-serif;font-size:15px;font-weight:700;line-height:20px;padding:12px 22px;text-decoration:none;">%s</a>
                        </td>
                      </tr>
                    </table>
                    """.formatted(escapeAttribute(actionUrl), escapeText(data.getActionLabel())));
        }

        appendOptionalParagraph(content, data.getSecondaryInformation(),
                "color:#667085;font-size:14px;line-height:22px;margin:0;");

        String footer = StringUtils.hasText(data.getFooter()) ? data.getFooter() : DEFAULT_FOOTER;
        String preheader = StringUtils.hasText(data.getPreheader()) ? data.getPreheader() : data.getTitle();

        return """
                <!doctype html>
                <html lang="en">
                  <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>%s</title>
                  </head>
                  <body style="background:#f8faf8;margin:0;padding:0;word-spacing:normal;">
                    <div style="display:none;font-size:1px;color:#f8faf8;line-height:1px;max-height:0;max-width:0;opacity:0;overflow:hidden;">%s</div>
                    <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" border="0" bgcolor="#f8faf8" style="background:#f8faf8;width:100%%;">
                      <tr>
                        <td align="center" style="padding:32px 16px;">
                          <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" border="0" style="background:#ffffff;border:1px solid #dce5df;border-radius:14px;max-width:600px;width:100%%;">
                            <tr>
                              <td style="background:#0f4a3a;border-radius:14px 14px 0 0;padding:22px 32px;">
                                <div style="color:#ffffff;font-family:Arial,'Segoe UI',sans-serif;font-size:18px;font-weight:700;letter-spacing:2px;line-height:24px;">WAYPOINT</div>
                              </td>
                            </tr>
                            <tr>
                              <td style="color:#1f2933;font-family:Arial,'Segoe UI',sans-serif;font-size:16px;line-height:25px;padding:36px 32px 32px;">
                                <h1 style="color:#0b3e31;font-family:Arial,'Segoe UI',sans-serif;font-size:28px;line-height:35px;margin:0 0 20px;">%s</h1>
                                %s
                                <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" border="0" style="margin-top:30px;">
                                  <tr><td style="border-top:1px solid #dce5df;font-size:1px;line-height:1px;padding-top:22px;">&nbsp;</td></tr>
                                  <tr><td style="color:#667085;font-family:Arial,'Segoe UI',sans-serif;font-size:12px;line-height:18px;">%s</td></tr>
                                </table>
                              </td>
                            </tr>
                          </table>
                        </td>
                      </tr>
                    </table>
                  </body>
                </html>
                """.formatted(
                escapeText(data.getTitle()),
                escapeText(preheader),
                escapeText(data.getTitle()),
                content,
                escapeText(footer));
    }

    private void appendOptionalParagraph(StringBuilder html, String value, String style) {
        if (StringUtils.hasText(value)) {
            html.append("<p style=\"").append(style).append("\">")
                    .append(escapeText(value))
                    .append("</p>");
        }
    }

    private void appendDetailRows(StringBuilder html, java.util.List<EmailDetailRow> detailRows) {
        if (detailRows == null) {
            return;
        }

        java.util.List<EmailDetailRow> visibleRows = detailRows.stream()
                .filter(row -> row != null
                        && StringUtils.hasText(row.getLabel())
                        && StringUtils.hasText(row.getValue()))
                .toList();
        if (visibleRows.isEmpty()) {
            return;
        }

        html.append("""
                <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" style="background:#f8faf8;border:1px solid #dce5df;border-radius:10px;margin:4px 0 24px;width:100%;">
                  <tr>
                    <td style="padding:20px 20px 8px;">
                      <div style="color:#0b3e31;font-family:Arial,'Segoe UI',sans-serif;font-size:16px;font-weight:700;line-height:22px;">Event details</div>
                    </td>
                  </tr>
                """);
        for (EmailDetailRow row : visibleRows) {
            html.append("<tr><td style=\"padding:8px 20px 12px;\">")
                    .append("<div style=\"color:#667085;font-family:Arial,'Segoe UI',sans-serif;")
                    .append("font-size:12px;font-weight:700;letter-spacing:.4px;line-height:18px;")
                    .append("text-transform:uppercase;\">")
                    .append(escapeText(row.getLabel()))
                    .append("</div><div style=\"color:#1f2933;font-family:Arial,'Segoe UI',sans-serif;")
                    .append("font-size:15px;line-height:22px;\">")
                    .append(escapeText(row.getValue()))
                    .append("</div></td></tr>");
        }
        html.append("</table>");
    }

    private String validateAndNormalizeActionUrl(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        if (!value.equals(value.trim()) || value.chars().anyMatch(character ->
                Character.isISOControl(character) || Character.isWhitespace(character))) {
            throw new IllegalArgumentException("Action URL is invalid");
        }

        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("https") || scheme.equals("http"))
                    || !uri.isAbsolute()
                    || !StringUtils.hasText(uri.getHost())
                    || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("Action URL must be an absolute HTTP(S) URL");
            }
            return uri.toASCIIString();
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Action URL is invalid", exception);
        }
    }

    private String escapeText(String value) {
        if (value == null) {
            return "";
        }
        return escapeHtml(value).replace("\r\n", "\n").replace("\r", "\n").replace("\n", "<br>");
    }

    private String escapeAttribute(String value) {
        return escapeHtml(value);
    }

    private String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
