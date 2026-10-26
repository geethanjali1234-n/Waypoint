package com.waypoint.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OfferLetterEmailTemplateServiceTest {

    private final OfferLetterEmailTemplateService templates = new OfferLetterEmailTemplateService();
    private final OrganizationEmailBranding itap = new OrganizationEmailBranding("ITAP", "hr@itap.example",
            "+1-312-555-0100", "100 Main Street, Chicago, IL, 60601", "https://itap.example");

    @Test
    void subjectAndSenderUseTheRequestOrganization() {
        assertThat(templates.defaultSubject(itap)).isEqualTo("Congratulations! Offer with ITAP");
        assertThat(templates.senderDisplayName(itap)).isEqualTo("HR ITAP");
    }

    @Test
    void messageIsPopulatedWithEmployeeNameAndDesignation() {
        String message = templates.defaultMessage("Priya Sharma", "QA Engineer", itap);

        assertThat(message).startsWith("Hi Priya Sharma,\n\nHope this email finds you well.");
        assertThat(message).contains("We are pleased to offer you as a **QA Engineer** with our organization.",
                "Also please be informed that ITAP's payroll schedule is on a calendar-monthly basis.",
                "we need your approved timesheets on monthly basis to our email ID: hr@itap.example",
                "If you need any verification letters or any enquiries please direct them to hr@itap.example.",
                "We wish you very best in your job!");
        assertThat(message).contains("100 Main Street, Chicago, IL, 60601", "Ph: +1-312-555-0100",
                "https://itap.example", "Thanks & Regards").doesNotContain("Tardus", "tardusinc");
    }

    @Test
    void messageFallsBackWhenDesignationAndOrganizationEmailAreMissing() {
        OrganizationEmailBranding noEmail = new OrganizationEmailBranding("ITAP", null, null, null, null);
        String message = templates.defaultMessage("Priya Sharma", " ", noEmail);

        assertThat(message).contains("We are pleased to offer you a position with our organization.",
                "timesheets on monthly basis to our HR team",
                "enquiries please direct them to the HR team.").doesNotContain("null", "email ID:");
    }

    @Test
    void renderedHtmlHasParagraphsBoldAndClickableLinks() {
        String html = templates.renderHtml(templates.defaultMessage("Priya Sharma", "QA Engineer", itap));

        assertThat(html).contains("<p style=\"margin:0 0 13px;\">Hi Priya Sharma,</p>");
        assertThat(html).contains("offer you as a <strong>QA Engineer</strong> with our organization.");
        assertThat(html).contains("<a href=\"mailto:hr@itap.example\" style=\"color:#0f4a3a;\">hr@itap.example</a>.");
        assertThat(html).contains("<a href=\"https://itap.example\"");
        assertThat(html).contains("<p style=\"margin:0 0 13px;\">Thanks &amp; Regards<br>HR Team</p><p style=\"margin:0 0 13px;\">ITAP<br>100 Main Street, Chicago, IL, 60601");
        assertThat(html).contains("100 Main Street, Chicago, IL, 60601");
        assertThat(html).contains("Thanks &amp; Regards");
    }

    @Test
    void htmlUsesAResponsiveLeftAlignedBusinessEmailLayoutAndNoMarkdownSyntax() {
        String html = templates.renderHtml(templates.defaultMessage("Priya Sharma", "QA Engineer", itap));

        assertThat(html).contains("width:100%", "padding:0;", "font-size:13px", "text-align:left", "line-height:1.4",
                "background-color:#ffffff", "margin:0 0 13px;");
        assertThat(html).doesNotContain("text-align:justify", "text-align:center", "max-width", "margin:0 auto");
        assertThat(html).doesNotContain("**");
        assertThat(html).contains("<strong>QA Engineer</strong>").doesNotContain("<strong>HR Team</strong>", "<strong>ITAP</strong>");
        assertThat(html).contains("mailto:hr@itap.example", "href=\"https://itap.example\"");
    }

    @Test
    void editedMessageCannotInjectMarkup() {
        String html = templates.renderHtml("Hello <script>alert('x')</script>\n\n<a href=\"javascript:x\">click</a> **<b>bold</b>**");

        assertThat(html).doesNotContain("<script>", "<a href=\"javascript", "<b>");
        assertThat(html).contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;");
        assertThat(html).contains("<strong>&lt;b&gt;bold&lt;/b&gt;</strong>");
    }

    @Test
    void attachmentFileNameIsEmployeeSpecificAndSanitized() {
        assertThat(templates.attachmentFileName("Venkata Naga Sai Vasavi Anna"))
                .isEqualTo("Venkata_Naga_Sai_Vasavi_Anna_Offer_Letter.pdf");
        assertThat(templates.attachmentFileName("  José O'Brien/../x  ")).isEqualTo("Jose_O_Brien_x_Offer_Letter.pdf");
        assertThat(templates.attachmentFileName(null)).isEqualTo("Offer_Letter.pdf");
        assertThat(templates.attachmentFileName("***")).isEqualTo("Offer_Letter.pdf");
        assertThat(templates.attachmentFileName("A".repeat(200))).hasSize(80 + "_Offer_Letter.pdf".length());
    }

    @Test
    void signatureMatchesTheLetterheadLayoutWithATwoLineAddress() {
        OrganizationEmailBranding tardus = OrganizationEmailBranding.from(com.waypoint.entity.Organization.builder()
                .organizationName("Tardus Inc").phoneNumber("+1-(945)-234-7245").website("https://www.tardusinc.com/")
                .streetAddress("4425 W Airport Fwy STE 530").city("Irving").state("Texas").zipCode("75062-5939").build());

        assertThat(templates.signatureBlock(tardus)).isEqualTo("""
                HR Team

                Tardus Inc
                4425 W Airport Fwy STE 530
                Irving, Texas 75062-5939
                Ph: +1-(945)-234-7245
                https://www.tardusinc.com/""");
        assertThat(templates.defaultMessage("Priya Sharma", "QA Engineer", tardus))
                .contains("We wish you very best in your job!\n\nThanks & Regards\nHR Team\n\nTardus Inc\n");
    }
}
