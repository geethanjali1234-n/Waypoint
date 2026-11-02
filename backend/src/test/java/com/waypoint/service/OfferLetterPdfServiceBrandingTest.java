package com.waypoint.service;

import com.waypoint.entity.OfferLetterRecord;
import com.waypoint.entity.Organization;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.repository.OrganizationRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The offer letter header/footer branding comes from the letter's own organization record. */
class OfferLetterPdfServiceBrandingTest {
    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final FileStorageService storage = mock(FileStorageService.class);
    private final OfferLetterPdfService pdf = new OfferLetterPdfService(organizations, storage);

    private OfferLetterRecord letter(String organizationId) {
        return OfferLetterRecord.builder().organizationId(organizationId).organizationName("Stale Name")
                .previewText("Dear Jane,\n\nWelcome aboard.").build();
    }

    private String text(byte[] bytes) throws Exception {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(document);
        }
    }

    @Test
    void footerShowsTheLettersOwnOrganization_notTardus() throws Exception {
        when(organizations.findById("org-itap")).thenReturn(Optional.of(Organization.builder().id("org-itap")
                .organizationName("ITAP Inc").streetAddress("1 Main St").city("Austin").state("Texas").zipCode("78701")
                .phoneNumber("512-555-0100").website("www.itap.example").build()));

        String text = text(pdf.render(letter("org-itap")));

        assertThat(text).contains("ITAP INC | 1 MAIN ST, AUSTIN, TEXAS 78701 | 512-555-0100 | WWW.ITAP.EXAMPLE");
        assertThat(text.toUpperCase()).doesNotContain("TARDUS").doesNotContain("AIRPORT FREEWAY");
    }

    @Test
    void anotherOrganizationGetsItsOwnFooter() throws Exception {
        when(organizations.findById("org-other")).thenReturn(Optional.of(Organization.builder().id("org-other")
                .organizationName("Other Co").phoneNumber("111-222-3333").build()));

        assertThat(text(pdf.render(letter("org-other")))).contains("OTHER CO | 111-222-3333");
    }

    @Test
    void uploadedOrganizationLogoIsLoadedFromThatOrganization() throws Exception {
        when(organizations.findById("org-itap")).thenReturn(Optional.of(Organization.builder().id("org-itap")
                .organizationName("ITAP Inc").organizationLogoDocumentId("organization-logos/org-itap/logo.png").build()));
        when(storage.load(anyString())).thenThrow(new ResourceNotFoundException("File is not available"));

        // An unreadable logo must not break the letter.
        assertThat(text(pdf.render(letter("org-itap")))).contains("ITAP INC");
        verify(storage).load("organization-logos/org-itap/logo.png");
    }

    @Test
    void missingOrganizationFallsBackToTheRecordFieldsWithoutAnyTardusText() throws Exception {
        when(organizations.findById(anyString())).thenReturn(Optional.empty());

        String text = text(pdf.render(letter("org-gone")));

        assertThat(text).contains("STALE NAME");
        assertThat(text.toUpperCase()).doesNotContain("TARDUS");
    }

    @Test
    void generatedLetterWithoutOrganizationDataStillRenders() throws Exception {
        assertThat(pdf.render(OfferLetterRecord.builder().previewText("Dear Jane").build())).isNotEmpty();
    }

    /** Glyph boxes of everything drawn in the footer band (below the rule at 40pt, i.e. within 60pt of the page bottom). */
    private List<TextPosition> footerGlyphs(byte[] bytes) throws Exception {
        List<TextPosition> glyphs = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(bytes)) {
            new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) throws IOException {
                    for (TextPosition position : positions)
                        if (position.getPageHeight() - position.getYDirAdj() < 60f) glyphs.add(position);
                    super.writeString(text, positions);
                }
            }.getText(document);
        }
        return glyphs;
    }

    private long footerLines(List<TextPosition> glyphs) {
        TreeSet<Long> baselines = new TreeSet<>();
        glyphs.forEach(glyph -> baselines.add(Math.round((double) glyph.getYDirAdj())));
        return baselines.size();
    }

    private void assertOnPage(List<TextPosition> glyphs) {
        assertThat(glyphs).isNotEmpty().allSatisfy(glyph -> {
            assertThat(glyph.getXDirAdj()).as("left edge").isGreaterThanOrEqualTo(55.5f);
            assertThat(glyph.getXDirAdj() + glyph.getWidthDirAdj()).as("right edge").isLessThanOrEqualTo(556.5f);
            assertThat(glyph.getPageHeight() - glyph.getYDirAdj()).as("above the bottom edge").isGreaterThan(0f);
        });
    }

    @Test
    void shortFooterStaysOneCenteredLine_whereTheFixedFooterWas() throws Exception {
        when(organizations.findById("org-tardus")).thenReturn(Optional.of(Organization.builder().id("org-tardus")
                .organizationName("Tardus Inc").streetAddress("4425 W Airport Fwy STE 530").city("Irving").state("TX").zipCode("75062")
                .phoneNumber("972-666-8217").website("www.tardusinc.com").build()));

        List<TextPosition> glyphs = footerGlyphs(pdf.render(letter("org-tardus")));

        assertOnPage(glyphs);
        assertThat(footerLines(glyphs)).isEqualTo(1);
        // Baseline ~24pt above the bottom edge, as before.
        assertThat(glyphs.get(0).getPageHeight() - glyphs.get(0).getYDirAdj()).isBetween(22f, 26f);
        double left = glyphs.get(0).getXDirAdj(), right = glyphs.get(glyphs.size() - 1).getXDirAdj() + glyphs.get(glyphs.size() - 1).getWidthDirAdj();
        assertThat((left + right) / 2).as("centered").isBetween(305d, 307d);
        // Still the original 8.5pt Helvetica (PDFBox only reports whole-point sizes, so compare the line's width).
        String expected = "TARDUS INC | 4425 W AIRPORT FWY STE 530, IRVING, TX 75062 | 972-666-8217 | WWW.TARDUSINC.COM";
        double width8_5 = com.lowagie.text.pdf.BaseFont.createFont(com.lowagie.text.pdf.BaseFont.HELVETICA,
                com.lowagie.text.pdf.BaseFont.WINANSI, false).getWidthPoint(expected, 8.5f);
        assertThat(right - left).as("line width at 8.5pt").isBetween(width8_5 - 1.5, width8_5 + 1.5);
    }

    @Test
    void longNameAndAddressWrapOntoMoreLinesInsideThePageMargins() throws Exception {
        when(organizations.findById("org-long")).thenReturn(Optional.of(Organization.builder().id("org-long")
                .organizationName("International Technology and Applied Products Corporation of North America")
                .streetAddress("1200 North Congress Avenue Suite 400 Building C").city("Austin").state("Texas").zipCode("78701-1234")
                .phoneNumber("+1 (512) 555-0100 ext. 2200").website("www.international-technology-applied-products.example").build()));

        List<TextPosition> glyphs = footerGlyphs(pdf.render(letter("org-long")));

        assertOnPage(glyphs);
        assertThat(footerLines(glyphs)).isGreaterThan(1).isLessThanOrEqualTo(4);
        assertThat(glyphs.get(0).getPageHeight() - glyphs.get(glyphs.size() - 1).getYDirAdj()).as("last line").isGreaterThan(0f);
    }

    @Test
    void anUnbreakableVeryLongWebsiteIsShrunkOrClippedButNeverRunsOffThePage() throws Exception {
        when(organizations.findById("org-wide")).thenReturn(Optional.of(Organization.builder().id("org-wide")
                .organizationName("Wide Co").website("www." + "a".repeat(180) + ".example").build()));

        assertOnPage(footerGlyphs(pdf.render(letter("org-wide"))));
    }

    @Test
    void longFooterNeverTouchesTheBodyText() throws Exception {
        when(organizations.findById("org-long")).thenReturn(Optional.of(Organization.builder().id("org-long")
                .organizationName("International Technology and Applied Products Corporation of North America")
                .streetAddress("1200 North Congress Avenue Suite 400 Building C").city("Austin").state("Texas").zipCode("78701-1234")
                .phoneNumber("+1 (512) 555-0100 ext. 2200").website("www.international-technology-applied-products.example").build()));

        List<TextPosition> glyphs = footerGlyphs(pdf.render(letter("org-long")));
        float topOfFooter = glyphs.stream().map(g -> g.getPageHeight() - g.getYDirAdj() + g.getHeightDir()).max(Float::compare).orElseThrow();

        assertThat(topOfFooter).as("footer stays under the rule at 40pt").isLessThanOrEqualTo(40f);
    }
}
