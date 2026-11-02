package com.waypoint.service;

import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import com.waypoint.entity.Organization;
import com.waypoint.entity.OfferLetterRecord;
import com.waypoint.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Fixed approved-template structure. Replace clause constants when the approved source is supplied.
 * The page header logo and footer come from the letter's own organization (Organization Settings), never from another tenant.
 */
@Service
@RequiredArgsConstructor
public class OfferLetterPdfService {
    private static final Logger log = LoggerFactory.getLogger(OfferLetterPdfService.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMMM d, uuuu");
    private static final Color GREEN = new Color(0x2F, 0x6F, 0x57);
    /** Bundled letterhead logo kept only for the provisioned Tardus organization, which predates uploaded logos. */
    private static final String TARDUS_LOGO_RESOURCE = "/branding/tardus-logo.png";

    private final OrganizationRepository organizations;
    private final FileStorageService fileStorage;

    public byte[] render(OfferLetterRecord o) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); Document d = new Document(PageSize.LETTER, 56, 56, 70, 60);
        try { PdfWriter writer = PdfWriter.getInstance(d, out); writer.setPageEvent(brandingFor(o)); d.open();
            if (!or(o.getPreviewText()).isBlank()) {
                for (String section : o.getPreviewText().split("\\n\\n")) d.add(p(section, 10, Font.NORMAL, Color.BLACK));
                d.close(); return out.toByteArray();
            }
            d.add(p(o.getOrganizationName(), 19, Font.BOLD, GREEN)); d.add(p(or(o.getOrganizationAddress()), 9, Font.NORMAL, Color.DARK_GRAY));
            d.add(space()); d.add(p("OFFER LETTER", 15, Font.BOLD, Color.BLACK)); d.add(p("Date: " + o.getOfferDate().format(DATE), 10, Font.NORMAL, Color.DARK_GRAY)); d.add(space());
            d.add(p(o.getEmployeeName(), 11, Font.NORMAL, Color.BLACK)); d.add(p(or(o.getEmployeeAddress()), 10, Font.NORMAL, Color.BLACK)); d.add(space());
            d.add(p("Dear " + o.getEmployeeName() + ",", 11, Font.NORMAL, Color.BLACK));
            d.add(p("We are pleased to offer you the position of " + o.getDesignation() + " with " + o.getOrganizationName() + ".", 11, Font.NORMAL, Color.BLACK));
            if ("UNPAID".equals(o.getOfferType())) {
                d.add(p("This is an unpaid internship. Your internship begins " + date(o.getStartDate()) +
                        (o.getEndDate() == null ? "." : " and is expected to conclude " + date(o.getEndDate()) + ".") + " " + field("Schedule", o.getInternshipSchedule()), 11, Font.NORMAL, Color.BLACK));
            } else {
                d.add(p("Your employment category is " + title(o.getEmploymentCategory()) + ". Compensation will be " + title(o.getCompensationType()) + " at " + o.getCompensationAmount() + ", paid " + title(o.getPayFrequency()) + ".", 11, Font.NORMAL, Color.BLACK));
                d.add(p(field("Timesheet requirement", o.getTimesheetRequirement()) + " " + field("Benefits eligibility", o.getBenefitsEligibility()), 11, Font.NORMAL, Color.BLACK));
            }
            d.add(p("Your primary work location is " + o.getWorkLocation() + ". " + field("Reporting manager", o.getReportingManager()), 11, Font.NORMAL, Color.BLACK));
            d.add(p("This offer is subject to the Company’s standard employment terms, confidentiality obligations, policies, and applicable law. Please sign below to acknowledge your acceptance of these terms.", 11, Font.NORMAL, Color.BLACK));
            d.add(space()); d.add(p("Sincerely,", 11, Font.NORMAL, Color.BLACK)); d.add(p(o.getIssuerName() + "\n" + o.getIssuerDesignation(), 11, Font.NORMAL, Color.BLACK)); d.add(space());
            d.add(p("Employee acceptance", 11, Font.BOLD, Color.BLACK)); d.add(p("Signature: ______________________________    Date: __________________", 11, Font.NORMAL, Color.BLACK)); d.close();
        } catch (Exception e) { throw new IllegalStateException("Offer letter PDF could not be generated", e); }
        return out.toByteArray();
    }
    private Paragraph p(String s, float size, int style, Color color) { Paragraph x = new Paragraph(s, FontFactory.getFont(FontFactory.HELVETICA, size, style, color)); x.setLeading(size * 1.45f); x.setSpacingAfter(9); return x; }
    private Paragraph space() { Paragraph x = new Paragraph(" "); x.setSpacingAfter(6); return x; }
    private String date(java.time.LocalDate d) { return d.format(DATE); } private String or(String s) { return s == null || s.isBlank() ? "" : s; }
    private String title(String s) { return s == null ? "" : Arrays.stream(s.replace('_', ' ').toLowerCase().split(" ")).map(w -> w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1)).collect(java.util.stream.Collectors.joining(" ")); }
    private String field(String label, String value) { return value == null || value.isBlank() ? "" : label + ": " + value + "."; }

    /** Header logo and footer line for this letter's organization; either may be absent. */
    private BrandingPageEvent brandingFor(OfferLetterRecord o) {
        Organization organization = o.getOrganizationId() == null ? null
                : organizations.findById(o.getOrganizationId()).orElse(null);
        return new BrandingPageEvent(logoFor(organization), footerFor(o, organization));
    }

    private String footerFor(OfferLetterRecord o, Organization organization) {
        String name = organization == null ? o.getOrganizationName() : organization.getOrganizationName();
        OrganizationEmailBranding branding = organization == null ? null : OrganizationEmailBranding.from(organization);
        String address = branding != null ? branding.address() : o.getOrganizationAddress();
        return Stream.of(name, address == null ? null : address.replaceAll("\\s*\\R\\s*", ", "),
                        branding == null ? null : branding.phoneNumber(), branding == null ? null : branding.website())
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.joining(" | "));
    }

    private Image logoFor(Organization organization) {
        byte[] bytes = null;
        if (organization != null && organization.getOrganizationLogoDocumentId() != null
                && !organization.getOrganizationLogoDocumentId().isBlank()) {
            try { bytes = fileStorage.load(organization.getOrganizationLogoDocumentId()).content(); }
            catch (RuntimeException e) { log.warn("Organization logo unavailable for offer letter PDF [exceptionType={}]", e.getClass().getSimpleName()); }
        }
        if (bytes == null && organization != null
                && TardusOrganizationProvisioningService.ORGANIZATION_NAME.equalsIgnoreCase(organization.getOrganizationName())) {
            try (InputStream is = OfferLetterPdfService.class.getResourceAsStream(TARDUS_LOGO_RESOURCE)) {
                if (is != null) bytes = is.readAllBytes();
            } catch (Exception e) { log.warn("Bundled logo could not be read for offer letter PDF [exceptionType={}]", e.getClass().getSimpleName()); }
        }
        if (bytes == null) return null;
        try { Image logo = Image.getInstance(bytes); logo.scaleToFit(160f, 34f); return logo; }
        catch (Exception e) { log.warn("Logo could not be used for offer letter PDF [exceptionType={}]", e.getClass().getSimpleName()); return null; }
    }

    /** Repeats the organization logo header and company-address footer on every page, regardless of page count. */
    private static class BrandingPageEvent extends PdfPageEventHelper {
        private final Image logo;
        private final String footer;
        /** Normal size first (a short footer is one centered line, as before); smaller sizes only if it will not fit. */
        private static final float[] FOOTER_SIZES = {8.5f, 7.5f, 6.5f, 5.5f};
        /** Footer area below the rule: the bottom page margin is 60pt and the rule sits at 40pt. */
        private static final float FOOTER_BOTTOM = 6f;

        BrandingPageEvent(Image logo, String footer) {
            this.logo = logo;
            this.footer = footer;
        }

        /**
         * Centers the footer between the margins and wraps it at spaces inside the area under the rule, so long names,
         * addresses and websites stay on the page. A single line sits where the former fixed footer was drawn.
         */
        private static void drawFooter(PdfContentByte cb, String text, float left, float right, float ruleY) {
            float top = ruleY - 6f;
            try {
                for (int i = 0; i < FOOTER_SIZES.length; i++) {
                    boolean smallest = i == FOOTER_SIZES.length - 1;
                    // Measure first; draw at the first size whose wrapped text fits (the smallest is always drawn, clipped to the area).
                    if (smallest || !ColumnText.hasMoreText(footerColumn(cb, text, FOOTER_SIZES[i], left, right, top).go(true))) {
                        footerColumn(cb, text, FOOTER_SIZES[i], left, right, top).go();
                        return;
                    }
                }
            } catch (DocumentException e) { throw new IllegalStateException("Offer letter footer could not be drawn", e); }
        }

        private static ColumnText footerColumn(PdfContentByte cb, String text, float size, float left, float right, float top) {
            ColumnText column = new ColumnText(cb);
            column.setSimpleColumn(left, FOOTER_BOTTOM, right, top, size * 1.2f, Element.ALIGN_CENTER);
            column.addText(new Phrase(text, FontFactory.getFont(FontFactory.HELVETICA, size, Font.NORMAL, Color.DARK_GRAY)));
            return column;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            try {
                PdfContentByte cb = writer.getDirectContent();
                float pageWidth = document.getPageSize().getWidth();
                float pageHeight = document.getPageSize().getHeight();
                float left = document.leftMargin();
                float right = document.rightMargin();

                if (logo != null) {
                    Image headerLogo = Image.getInstance(logo);
                    headerLogo.setAbsolutePosition(left, pageHeight - 18f - headerLogo.getScaledHeight());
                    cb.addImage(headerLogo);
                }

                float lineY = 40f;
                cb.saveState();
                cb.setLineWidth(0.5f);
                cb.setColorStroke(Color.LIGHT_GRAY);
                cb.moveTo(left, lineY);
                cb.lineTo(pageWidth - right, lineY);
                cb.stroke();
                cb.restoreState();

                if (footer != null && !footer.isBlank()) drawFooter(cb, footer, left, pageWidth - right, lineY);
            } catch (Exception e) { log.warn("Offer letter header/footer could not be drawn", e); }
        }
    }
}
