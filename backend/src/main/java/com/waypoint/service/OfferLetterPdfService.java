package com.waypoint.service;

import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import com.waypoint.entity.OfferLetterRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

/** Fixed approved-template structure. Replace clause constants when the approved source is supplied. */
@Service
public class OfferLetterPdfService {
    private static final Logger log = LoggerFactory.getLogger(OfferLetterPdfService.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMMM d, uuuu");
    private static final Color GREEN = new Color(0x2F, 0x6F, 0x57);
    private static final String LOGO_RESOURCE = "/branding/tardus-logo.png";
    private static final String FOOTER_TEXT = "TARDUS INC. | 4425 W AIRPORT FREEWAY, SUITE # 530 | IRVING, TX 75062 | 972-666-8217 | WWW.TARDUSINC.COM";

    public byte[] render(OfferLetterRecord o) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); Document d = new Document(PageSize.LETTER, 56, 56, 70, 60);
        try { PdfWriter writer = PdfWriter.getInstance(d, out); writer.setPageEvent(new BrandingPageEvent()); d.open();
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

    /** Repeats the TARDUS logo header and company-address footer on every page, regardless of page count. */
    private static class BrandingPageEvent extends PdfPageEventHelper {
        private Image logo;
        private static final Font FOOTER_FONT = FontFactory.getFont(FontFactory.HELVETICA, 8.5f, Font.NORMAL, Color.DARK_GRAY);

        BrandingPageEvent() {
            try (InputStream is = OfferLetterPdfService.class.getResourceAsStream(LOGO_RESOURCE)) {
                if (is == null) { log.warn("TARDUS logo resource not found at {}", LOGO_RESOURCE); return; }
                logo = Image.getInstance(is.readAllBytes());
                logo.scaleToFit(160f, 34f);
            } catch (Exception e) { log.warn("TARDUS logo could not be loaded for offer letter PDF", e); logo = null; }
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

                ColumnText.showTextAligned(cb, Element.ALIGN_CENTER, new Phrase(FOOTER_TEXT, FOOTER_FONT), pageWidth / 2f, lineY - 16f, 0);
            } catch (Exception e) { log.warn("Offer letter header/footer could not be drawn", e); }
        }
    }
}
