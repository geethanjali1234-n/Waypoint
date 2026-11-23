package com.waypoint.service;

import com.waypoint.exception.DocumentExtractionException;
import com.waypoint.exception.DocumentExtractionException.Reason;
import com.waypoint.service.DocumentChunkingService.TextChunk;
import com.waypoint.service.DocumentTextExtractor.DocumentFormat;
import com.waypoint.service.DocumentTextExtractor.ExtractedText;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtBlock;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static com.waypoint.service.DocumentTextExtractor.MAX_ARCHIVE_ENTRIES;
import static com.waypoint.service.DocumentTextExtractor.MAX_ARCHIVE_INFLATED_BYTES;
import static com.waypoint.service.DocumentTextExtractor.MAX_FILE_BYTES;
import static com.waypoint.service.DocumentTextExtractor.MAX_LAST_ROW_INDEX;
import static com.waypoint.service.DocumentTextExtractor.MAX_PDF_PAGES;
import static com.waypoint.service.DocumentTextExtractor.MAX_ROW_CELLS;
import static com.waypoint.service.DocumentTextExtractor.MAX_TEXT_CHARS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Synthetic documents only: every PDF, DOCX, XLSX and ZIP is generated in memory by the test, so
 * no real company or employee document is read. CANARY stands in for private content that must
 * never surface in an error.
 */
class DocumentTextExtractorTest {

    private static final String CANARY = "CANARY-SSN-078-05-1120";

    private final DocumentTextExtractor extractor = new DocumentTextExtractor();

    // --- PDF ------------------------------------------------------------------------------------

    @Test
    void extractsPdfPagesInOrderSeparatedByPageBreaks() throws Exception {
        ExtractedText extracted = extractor.extract(
                pdf("Leave Policy\nAnnual leave accrues monthly.", "Sick Leave\nNotify your manager."), "policy.pdf");

        assertThat(extracted.format()).isEqualTo(DocumentFormat.PDF);
        assertThat(extracted.text())
                .isEqualTo("Leave Policy\nAnnual leave accrues monthly.\fSick Leave\nNotify your manager.");
        assertThat(extracted.pageCount()).isEqualTo(2);
        assertThat(extracted.pagesWithoutText()).isZero();
        assertThat(extracted.inspection()).isEqualTo(DocumentTextExtractor.ContentInspection.none());
    }

    @Test
    void acceptsPartiallyScannedPdfAndReportsPagesWithoutText() throws Exception {
        byte[] content;
        try (PDDocument document = new PDDocument()) {
            addTextPage(document, "Page one");
            addScannedPage(document);
            addTextPage(document, null);
            addTextPage(document, "Page four");
            content = save(document);
        }

        ExtractedText extracted = extractor.extract(content, "handbook.pdf");

        assertThat(extracted.text()).isEqualTo("Page one\fPage four");
        assertThat(extracted.pageCount()).isEqualTo(4);
        assertThat(extracted.pagesWithoutText()).isEqualTo(2);
        assertThat(extracted.inspection().pagesRequiringOcrReview()).isEqualTo(2);
        assertThat(extracted.inspection().requiresReview()).isTrue();
    }

    @Test
    @Timeout(30)
    void acceptsPdfAtThePageLimit() throws Exception {
        String[] pages = new String[MAX_PDF_PAGES];
        pages[0] = "Only this page has text";

        ExtractedText extracted = extractor.extract(pdf(pages), "long.pdf");

        assertThat(extracted.text()).isEqualTo("Only this page has text");
        assertThat(extracted.pageCount()).isEqualTo(MAX_PDF_PAGES);
        assertThat(extracted.pagesWithoutText()).isEqualTo(MAX_PDF_PAGES - 1);
    }

    @Test
    @Timeout(30)
    void rejectsPdfOverThePageLimit() throws Exception {
        String[] pages = new String[MAX_PDF_PAGES + 1];
        pages[0] = "Text";

        assertFailure(pdf(pages), "long.pdf", Reason.TOO_MANY_PAGES);
    }

    @Test
    void rejectsPasswordProtectedPdf() throws Exception {
        assertFailure(encryptedPdf("owner-secret", "user-secret"), "payslip.pdf", Reason.ENCRYPTED);
    }

    @Test
    void rejectsEncryptedPdfEvenWhenItOpensWithoutAPassword() throws Exception {
        assertFailure(encryptedPdf("owner-secret", ""), "payslip.pdf", Reason.ENCRYPTED);
    }

    @Test
    @Timeout(60)
    void rejectsPdfWhoseTextExceedsTheLimit() throws Exception {
        String line = "policy ".repeat(30);
        String page = (line + "\n").repeat(250);
        // Extra pages cover whitespace the stripper may drop at line ends.
        int pageCount = MAX_TEXT_CHARS / page.length() + 3;
        byte[] content;
        try (PDDocument document = new PDDocument()) {
            for (int index = 0; index < pageCount; index++) {
                addTextPage(document, page, 2);
            }
            content = save(document);
        }

        assertThat(content.length).isLessThan(MAX_FILE_BYTES);
        assertFailure(content, "dense.pdf", Reason.TEXT_LIMIT_EXCEEDED);
    }

    // --- DOCX -----------------------------------------------------------------------------------

    @Test
    void extractsDocxParagraphsTablesAndHeadingsInDocumentOrder() throws Exception {
        byte[] content = docx(document -> {
            paragraph(document, "Title", "Leave Policy");
            paragraph(document, null, "Employees accrue leave monthly.");
            XWPFTable table = document.createTable(3, 3);
            table.getRow(0).getCell(0).setText("Type");
            table.getRow(0).getCell(1).setText("Days");
            table.getRow(1).getCell(0).setText("Annual");
            table.getRow(1).getCell(1).setText("20");
            paragraph(document, "Heading1", "Carry-over");
            paragraph(document, null, "Up to five days carry over.");
            paragraph(document, null, "   ");
        });

        ExtractedText extracted = extractor.extract(content, "leave-policy.docx");

        assertThat(extracted.format()).isEqualTo(DocumentFormat.DOCX);
        assertThat(extracted.text()).isEqualTo("Leave Policy\nEmployees accrue leave monthly.\n\n"
                + "Type\tDays\nAnnual\t20\n\n"
                + "Carry-over\nUp to five days carry over.");
        assertThat(extracted.pageCount()).isZero();
        assertThat(extracted.pagesWithoutText()).isZero();
    }

    @Test
    void keepsEmptyLeadingTableCellsSoColumnsStayAligned() throws Exception {
        byte[] content = docx(document -> {
            XWPFTable table = document.createTable(2, 2);
            table.getRow(0).getCell(1).setText("Days");
            table.getRow(1).getCell(0).setText("Annual");
            table.getRow(1).getCell(1).setText("20");
        });

        assertThat(extractor.extract(content, "table.docx").text()).isEqualTo("\tDays\nAnnual\t20");
    }

    @Test
    void readsDocxContentControlsInOrder() throws Exception {
        byte[] content = docx(document -> {
            paragraph(document, null, "Before the control.");
            CTSdtBlock control = document.getDocument().getBody().addNewSdt();
            control.addNewSdtContent().addNewP().addNewR().addNewT().setStringValue("Inside the control.");
        });

        String text = extractor.extract(content, "form.docx").text();

        assertThat(text).contains("Before the control.", "Inside the control.");
        assertThat(text.indexOf("Before the control.")).isLessThan(text.indexOf("Inside the control."));
    }

    @Test
    void ignoresDocxHeadersAndFooters() throws Exception {
        byte[] content = docx(document -> {
            document.createHeader(HeaderFooterType.DEFAULT).createParagraph().createRun().setText("Header " + CANARY);
            document.createFooter(HeaderFooterType.DEFAULT).createParagraph().createRun().setText("Footer " + CANARY);
            paragraph(document, null, "Body text.");
        });

        assertThat(extractor.extract(content, "letter.docx").text()).isEqualTo("Body text.");
    }

    @Test
    void rejectsDocxWhoseTextExceedsTheLimit() throws Exception {
        // Incompressible text, so POI's own zip-bomb ratio check is not what rejects it.
        Random letters = new Random(42);
        byte[] content = docx(document -> {
            for (int index = 0; index <= MAX_TEXT_CHARS / 1_000; index++) {
                StringBuilder body = new StringBuilder(1_000);
                while (body.length() < 1_000) {
                    body.append((char) ('a' + letters.nextInt(26)));
                }
                paragraph(document, null, body.toString());
            }
        });

        assertThat(content.length).isLessThan(MAX_FILE_BYTES);
        assertFailure(content, "huge.docx", Reason.TEXT_LIMIT_EXCEEDED);
    }

    // --- XLSX -----------------------------------------------------------------------------------

    @Test
    void extractsVisibleSheetsInOrderWithDisplayedAndCachedFormulaValues() throws Exception {
        byte[] content = xlsx(workbook -> {
            XSSFSheet holidays = workbook.createSheet("Holidays");
            row(holidays, 0, "Date", "Holiday");
            XSSFRow christmas = holidays.createRow(1);
            XSSFCell date = christmas.createCell(0);
            date.setCellValue(LocalDate.of(2026, 12, 25));
            XSSFCellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));
            date.setCellStyle(dateStyle);
            christmas.createCell(1).setCellValue("Christmas Day");
            holidays.createRow(3).createCell(0).setCellValue("");
            XSSFRow floating = holidays.createRow(4);
            floating.createCell(0).setCellValue("Floating days");
            floating.createCell(1).setCellValue(2.5);
            XSSFRow total = holidays.createRow(5);
            total.createCell(0).setCellValue("Total days");
            total.createCell(1).setCellFormula("B5+1");
            workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
            row(workbook.createSheet("Contacts"), 0, "HR helpdesk", "ext 4100");
        });

        ExtractedText extracted = extractor.extract(content, "holidays.xlsx");

        assertThat(extracted.format()).isEqualTo(DocumentFormat.XLSX);
        assertThat(extracted.text()).isEqualTo("Holidays\nDate\tHoliday\n2026-12-25\tChristmas Day\n"
                + "Floating days\t2.5\nTotal days\t3.5\fContacts\nHR helpdesk\text 4100");
        assertThat(extracted.text()).doesNotContain("B5+1");
    }

    @Test
    void skipsHiddenSheetsRowsAndColumns() throws Exception {
        byte[] content = xlsx(workbook -> {
            XSSFSheet visible = workbook.createSheet("Visible");
            row(visible, 0, "Shown", "Hidden column " + CANARY, "Also shown");
            visible.setColumnHidden(1, true);
            row(visible, 1, "Hidden row " + CANARY).setZeroHeight(true);
            row(workbook.createSheet("Hidden"), 0, "Hidden sheet " + CANARY);
            row(workbook.createSheet("VeryHidden"), 0, "Very hidden sheet " + CANARY);
            workbook.setSheetVisibility(1, SheetVisibility.HIDDEN);
            workbook.setSheetVisibility(2, SheetVisibility.VERY_HIDDEN);
        });

        assertThat(extractor.extract(content, "rates.xlsx").text()).isEqualTo("Visible\nShown\tAlso shown");
    }

    @Test
    void acceptsSpreadsheetAtTheRowAndColumnLimits() throws Exception {
        byte[] content = xlsx(workbook -> {
            XSSFSheet sheet = workbook.createSheet("Limits");
            sheet.createRow(0).createCell(MAX_ROW_CELLS - 1).setCellValue("Last column");
            sheet.createRow(MAX_LAST_ROW_INDEX).createCell(0).setCellValue("Last row");
        });

        assertThat(extractor.extract(content, "limits.xlsx").text())
                .isEqualTo("Limits\n" + "\t".repeat(MAX_ROW_CELLS - 1) + "Last column\nLast row");
    }

    @Test
    void rejectsSpreadsheetOverTheRowLimit() throws Exception {
        byte[] content = xlsx(workbook ->
                workbook.createSheet("Rows").createRow(MAX_LAST_ROW_INDEX + 1).createCell(0).setCellValue("Too far"));

        assertFailure(content, "rows.xlsx", Reason.TOO_MANY_ROWS);
    }

    @Test
    void rejectsSpreadsheetOverTheColumnLimit() throws Exception {
        byte[] content = xlsx(workbook ->
                workbook.createSheet("Columns").createRow(0).createCell(MAX_ROW_CELLS).setCellValue("Too wide"));

        assertFailure(content, "columns.xlsx", Reason.TOO_MANY_COLUMNS);
    }

    @Test
    void appliesSpreadsheetLimitsToHiddenSheetsToo() throws Exception {
        byte[] content = xlsx(workbook -> {
            row(workbook.createSheet("Visible"), 0, "Shown");
            workbook.createSheet("Hidden").createRow(MAX_LAST_ROW_INDEX + 1).createCell(0).setCellValue("Too far");
            workbook.setSheetVisibility(1, SheetVisibility.HIDDEN);
        });

        assertFailure(content, "hidden.xlsx", Reason.TOO_MANY_ROWS);
    }

    // --- Plain text -----------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"notes.txt", "README.md"})
    void extractsUtf8TextWithoutByteOrderMark(String fileName) {
        byte[] content = "﻿# Café policy\n\n日本語 guidance applies.\n".getBytes(StandardCharsets.UTF_8);

        ExtractedText extracted = extractor.extract(content, fileName);

        assertThat(extracted.format()).isEqualTo(DocumentFormat.TEXT);
        assertThat(extracted.text()).isEqualTo("# Café policy\n\n日本語 guidance applies.");
    }

    @Test
    void acceptsTextAtTheCharacterLimit() {
        byte[] content = "a".repeat(MAX_TEXT_CHARS).getBytes(StandardCharsets.UTF_8);

        assertThat(extractor.extract(content, "limit.txt").text()).hasSize(MAX_TEXT_CHARS);
    }

    @Test
    void rejectsTextOneCharacterOverTheLimit() {
        byte[] content = "a".repeat(MAX_TEXT_CHARS + 1).getBytes(StandardCharsets.UTF_8);

        assertFailure(content, "limit.txt", Reason.TEXT_LIMIT_EXCEEDED);
    }

    @Test
    void rejectsInvalidUtf8() {
        byte[] prefix = (CANARY + " ").getBytes(StandardCharsets.UTF_8);
        byte[] content = new byte[prefix.length + 2];
        System.arraycopy(prefix, 0, content, 0, prefix.length);
        content[prefix.length] = (byte) 0xC3;
        content[prefix.length + 1] = (byte) 0x28;

        assertFailure(content, "notes.txt", Reason.MALFORMED);
    }

    @Test
    void rejectsBinaryAndUtf16ContentNamedAsText() {
        assertFailure(new byte[]{'M', 'Z', 0, 0, 1, 2}, "notes.txt", Reason.FORMAT_MISMATCH);
        assertFailure("Policy".getBytes(StandardCharsets.UTF_16), "notes.md", Reason.FORMAT_MISMATCH);
    }

    // --- Shared validation ----------------------------------------------------------------------

    @ParameterizedTest
    @NullAndEmptySource
    void rejectsMissingContent(byte[] content) {
        assertFailure(content, "policy.pdf", Reason.EMPTY);
    }

    @Test
    void checksFileSizeBeforeAnythingElse() {
        assertFailure(new byte[MAX_FILE_BYTES], "policy.pdf", Reason.FORMAT_MISMATCH);
        assertFailure(new byte[MAX_FILE_BYTES + 1], "policy.pdf", Reason.TOO_LARGE);
        assertFailure(new byte[MAX_FILE_BYTES + 1], "policy.exe", Reason.TOO_LARGE);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  ", "policy", "pdf", "policy.", "policy.xls", "policy.doc", "policy.docm",
            "policy.xlsm", "policy.csv", "policy.tsv", "scan.png", "scan.jpg", "deck.pptx", "page.html",
            "policy.rtf", "policy.pdf.exe", "policy.pdf/"})
    void rejectsUnsupportedFileNamesEvenWithValidContent(String fileName) throws Exception {
        assertFailure(pdf("Valid PDF text"), fileName, Reason.UNSUPPORTED_FORMAT);
    }

    @ParameterizedTest
    @MethodSource("supportedFilesWithMixedCaseNames")
    void matchesExtensionsCaseInsensitively(byte[] content, String fileName, DocumentFormat format, String text) {
        ExtractedText extracted = extractor.extract(content, fileName);

        assertThat(extracted.format()).isEqualTo(format);
        assertThat(extracted.text()).isEqualTo(text);
    }

    static Stream<Arguments> supportedFilesWithMixedCaseNames() throws Exception {
        byte[] text = bytes("Hello");
        return Stream.of(
                Arguments.of(pdf("Hello"), "POLICY.PDF", DocumentFormat.PDF, "Hello"),
                Arguments.of(docx(document -> paragraph(document, null, "Hello")), "Handbook.DOCX", DocumentFormat.DOCX, "Hello"),
                Arguments.of(xlsx(workbook -> row(workbook.createSheet("Rates"), 0, "Hello")), "Rates.XLSX", DocumentFormat.XLSX, "Rates\nHello"),
                Arguments.of(text, "NOTES.TXT", DocumentFormat.TEXT, "Hello"),
                Arguments.of(text, " Guide.Md ", DocumentFormat.TEXT, "Hello"));
    }

    @ParameterizedTest
    @MethodSource("contentThatDoesNotMatchItsExtension")
    void rejectsContentThatDoesNotMatchItsExtension(byte[] content, String fileName) {
        assertFailure(content, fileName, Reason.FORMAT_MISMATCH);
    }

    static Stream<Arguments> contentThatDoesNotMatchItsExtension() throws Exception {
        byte[] pdf = pdf("PDF text");
        byte[] docx = docx(document -> paragraph(document, null, "DOCX text"));
        byte[] xlsx = xlsx(workbook -> row(workbook.createSheet("Sheet"), 0, "XLSX text"));
        byte[] legacyOffice = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
        return Stream.of(
                Arguments.of(pdf, "policy.docx"),
                Arguments.of(pdf, "policy.xlsx"),
                Arguments.of(docx, "policy.pdf"),
                Arguments.of(docx, "policy.xlsx"),
                Arguments.of(xlsx, "policy.docx"),
                Arguments.of("Plain text".getBytes(StandardCharsets.UTF_8), "policy.pdf"),
                Arguments.of("Plain text".getBytes(StandardCharsets.UTF_8), "policy.docx"),
                Arguments.of(legacyOffice, "encrypted-or-legacy.docx"),
                Arguments.of(legacyOffice, "encrypted-or-legacy.xlsx"),
                Arguments.of(zip(Map.of("[Content_Types].xml", bytes("<Types/>"))), "no-main-part.docx"));
    }

    @ParameterizedTest
    @MethodSource("documentsWithoutText")
    void rejectsDocumentsWithoutText(byte[] content, String fileName) {
        assertFailure(content, fileName, Reason.NO_TEXT);
    }

    static Stream<Arguments> documentsWithoutText() throws Exception {
        byte[] scannedOnly;
        try (PDDocument document = new PDDocument()) {
            addScannedPage(document);
            addScannedPage(document);
            scannedOnly = save(document);
        }
        byte[] noPages;
        try (PDDocument document = new PDDocument()) {
            noPages = save(document);
        }
        return Stream.of(
                Arguments.of(scannedOnly, "scan.pdf"),
                Arguments.of(pdf(null, null), "blank.pdf"),
                Arguments.of(noPages, "no-pages.pdf"),
                Arguments.of(docx(document -> {
                    paragraph(document, null, " ");
                    document.createTable(2, 2);
                }), "empty.docx"),
                Arguments.of(xlsx(workbook -> row(workbook.createSheet("Blank"), 0, "", " ")), "empty.xlsx"),
                Arguments.of(" \n\t\r\n".getBytes(StandardCharsets.UTF_8), "empty.txt"),
                Arguments.of("﻿".getBytes(StandardCharsets.UTF_8), "bom-only.md"));
    }

    // --- Archive safety -------------------------------------------------------------------------

    @Test
    void acceptsArchiveAtTheEntryLimitAndRejectsOneEntryMore() throws Exception {
        // The fake parts pass the archive check and are then rejected by POI as malformed.
        assertFailure(zip(entries(MAX_ARCHIVE_ENTRIES)), "parts.docx", Reason.MALFORMED);
        assertFailure(zip(entries(MAX_ARCHIVE_ENTRIES + 1)), "parts.docx", Reason.ARCHIVE_LIMIT_EXCEEDED);
    }

    @Test
    @Timeout(30)
    void rejectsZipBombBeforePoiOpensIt() throws Exception {
        byte[] atLimit = zipOfZeros("word/document.xml", MAX_ARCHIVE_INFLATED_BYTES);
        byte[] overLimit = zipOfZeros("word/document.xml", MAX_ARCHIVE_INFLATED_BYTES + 1);

        assertThat(overLimit.length).isLessThan(MAX_FILE_BYTES);
        assertFailure(atLimit, "bomb.docx", Reason.MALFORMED);
        assertFailure(overLimit, "bomb.docx", Reason.ARCHIVE_LIMIT_EXCEEDED);
    }

    @Test
    @Timeout(30)
    void rejectsZipBombSpreadAcrossEntries() throws Exception {
        long half = MAX_ARCHIVE_INFLATED_BYTES / 2 + 1;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            writeZeros(zip, "xl/workbook.xml", half);
            writeZeros(zip, "xl/media/padding.bin", half);
        }

        assertFailure(out.toByteArray(), "bomb.xlsx", Reason.ARCHIVE_LIMIT_EXCEEDED);
    }

    @Test
    void rejectsCorruptArchive() {
        byte[] content = new byte[96];
        content[0] = 'P';
        content[1] = 'K';
        content[2] = 3;
        content[3] = 4;
        Arrays.fill(content, 4, 40, (byte) 0xFF);
        byte[] canary = bytes(CANARY);
        System.arraycopy(canary, 0, content, 40, canary.length);

        assertFailure(content, "corrupt.docx", Reason.MALFORMED);
    }

    @ParameterizedTest
    @MethodSource("officeFilesWithBrokenXml")
    void rejectsOfficeFilesWithMalformedXml(byte[] content, String fileName) {
        assertFailure(content, fileName, Reason.MALFORMED);
    }

    static Stream<Arguments> officeFilesWithBrokenXml() throws Exception {
        byte[] docx = docx(document -> paragraph(document, null, "Body"));
        byte[] xlsx = xlsx(workbook -> row(workbook.createSheet("Sheet1"), 0, "Cell"));
        String broken = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:body><w:p><w:r><w:t>" + CANARY;
        return Stream.of(
                Arguments.of(replaceEntry(docx, "word/document.xml", bytes(broken)), "broken.docx"),
                Arguments.of(replaceEntry(xlsx, "xl/worksheets/sheet1.xml", bytes("<worksheet>" + CANARY)), "broken.xlsx"));
    }

    // --- Privacy-safe errors --------------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("malformedDocumentsContainingPrivateText")
    void failuresNeverExposeDocumentContent(byte[] content, String fileName) {
        assertThatThrownBy(() -> extractor.extract(content, fileName))
                .isInstanceOfSatisfying(DocumentExtractionException.class, failure -> {
                    assertThat(failure.getMessage()).doesNotContain(CANARY);
                    assertThat(failure.getCause()).isNull();
                    assertThat(failure.getSuppressed()).isEmpty();
                    assertThat(failure.toString()).doesNotContain(CANARY);
                });
    }

    static Stream<Arguments> malformedDocumentsContainingPrivateText() throws Exception {
        byte[] docx = docx(document -> paragraph(document, null, "Body"));
        return Stream.of(
                Arguments.of(bytes("%PDF-1.7\n" + CANARY + "\n%%EOF"), "garbage.pdf"),
                Arguments.of(encryptedPdf("owner", "user"), "locked.pdf"),
                Arguments.of(replaceEntry(docx, "word/document.xml", bytes("<w:document>" + CANARY)), "broken.docx"),
                Arguments.of(bytes("PK\u0003\u0004" + CANARY), "truncated.xlsx"),
                Arguments.of(bytes(CANARY + "\u0000"), "binary.txt"));
    }

    @Test
    void everyReasonHasAFixedGenericMessage() {
        for (Reason reason : Reason.values()) {
            DocumentExtractionException failure = new DocumentExtractionException(reason);
            assertThat(failure.getReason()).isEqualTo(reason);
            assertThat(failure.getMessage()).isNotBlank();
        }
    }

    // --- Integration with chunking and determinism ----------------------------------------------

    @Test
    void docxOutputChunksAtSentenceBoundariesWithDocumentChunkingService() throws Exception {
        byte[] content = docx(document -> {
            paragraph(document, "Title", "Remote Work Policy");
            for (int section = 1; section <= 4; section++) {
                paragraph(document, "Heading1", "Section " + section);
                for (int part = 1; part <= 3; part++) {
                    StringBuilder body = new StringBuilder();
                    for (int rule = 1; rule <= 6; rule++) {
                        body.append("Rule S").append(section).append("-P").append(part).append("-R").append(rule)
                                .append(" applies to every remote employee. ");
                    }
                    paragraph(document, null, body.toString());
                }
            }
        });

        List<TextChunk> chunks = new DocumentChunkingService(1500, 200)
                .chunk(extractor.extract(content, "remote-work.docx").text());

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).extracting(TextChunk::index).containsExactlyElementsOf(IntStream.range(0, chunks.size()).boxed().toList());
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.text().length()).isLessThanOrEqualTo(1500));
        assertThat(chunks.getFirst().text()).startsWith("Remote Work Policy\n\nSection 1\nRule S1-P1-R1 ");
        assertThat(chunks.subList(0, chunks.size() - 1)).allSatisfy(chunk -> assertThat(chunk.text()).endsWith("."));
        String allChunks = chunks.stream().map(TextChunk::text).collect(Collectors.joining("\n"));
        for (int section = 1; section <= 4; section++) {
            for (int part = 1; part <= 3; part++) {
                for (int rule = 1; rule <= 6; rule++) {
                    assertThat(allChunks).contains("Rule S" + section + "-P" + part + "-R" + rule + " ");
                }
            }
        }
    }

    @Test
    void pdfPageBreaksBecomeParagraphBreaksWhenChunked() throws Exception {
        String text = extractor.extract(pdf("Leave Policy", "Sick Leave"), "policy.pdf").text();

        assertThat(new DocumentChunkingService(1500, 200).chunk(text))
                .containsExactly(new TextChunk(0, "Leave Policy\n\nSick Leave"));
    }

    @Test
    void spreadsheetCellsStayTabSeparatedWhenChunked() throws Exception {
        byte[] content = xlsx(workbook -> {
            XSSFSheet sheet = workbook.createSheet("Holidays");
            row(sheet, 0, "Date", "Holiday");
            row(sheet, 1, "2026-12-25", "Christmas Day");
        });

        String text = extractor.extract(content, "holidays.xlsx").text();

        assertThat(new DocumentChunkingService(1500, 200).chunk(text))
                .containsExactly(new TextChunk(0, "Holidays\nDate\tHoliday\n2026-12-25\tChristmas Day"));
    }

    @Test
    void extractionIsDeterministic() throws Exception {
        byte[] pdf = pdf("Page one", "Page two");
        byte[] docx = docx(document -> paragraph(document, null, "Body"));
        byte[] xlsx = xlsx(workbook -> row(workbook.createSheet("Sheet"), 0, "A", "B"));

        assertThat(extractor.extract(pdf, "a.pdf")).isEqualTo(extractor.extract(pdf, "a.pdf"));
        assertThat(extractor.extract(docx, "a.docx")).isEqualTo(extractor.extract(docx, "a.docx"));
        assertThat(extractor.extract(xlsx, "a.xlsx")).isEqualTo(extractor.extract(xlsx, "a.xlsx"));
    }

    @Test
    void holdsNoStateOrCollaboratorsThatCouldFetchDocuments() {
        assertThat(DocumentTextExtractor.class.getDeclaredFields())
                .allSatisfy(field -> assertThat(Modifier.isStatic(field.getModifiers())).isTrue());
        assertThat(DocumentTextExtractor.class.getDeclaredConstructors())
                .allSatisfy(constructor -> assertThat(constructor.getParameterCount()).isZero());
    }

    // --- Helpers --------------------------------------------------------------------------------

    private void assertFailure(byte[] content, String fileName, Reason reason) {
        assertThatThrownBy(() -> extractor.extract(content, fileName))
                .isInstanceOfSatisfying(DocumentExtractionException.class, failure -> {
                    assertThat(failure.getReason()).isEqualTo(reason);
                    assertThat(failure.getMessage()).isEqualTo(new DocumentExtractionException(reason).getMessage());
                    assertThat(failure.getCause()).isNull();
                });
    }

    @FunctionalInterface
    private interface Builder<T> {
        void build(T target) throws Exception;
    }

    /** One PDF page per argument; null is a page with no content stream. */
    private static byte[] pdf(String... pages) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (String page : pages) {
                addTextPage(document, page);
            }
            return save(document);
        }
    }

    private static void addTextPage(PDDocument document, String text) throws IOException {
        addTextPage(document, text, 12);
    }

    private static void addTextPage(PDDocument document, String text, float fontSize) throws IOException {
        PDPage page = new PDPage();
        document.addPage(page);
        if (text == null) {
            return;
        }
        try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
            stream.beginText();
            stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), fontSize);
            stream.setLeading(fontSize * 1.2f);
            stream.newLineAtOffset(50, 720);
            for (String line : text.split("\n")) {
                stream.showText(line);
                stream.newLine();
            }
            stream.endText();
        }
    }

    /** A page holding only an image, as a scanner produces: it has content but no text layer. */
    private static void addScannedPage(PDDocument document) throws IOException {
        PDPage page = new PDPage();
        document.addPage(page);
        PDImageXObject scan = LosslessFactory.createFromImage(document, new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB));
        try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
            stream.drawImage(scan, 50, 600, 200, 100);
        }
    }

    private static byte[] encryptedPdf(String ownerPassword, String userPassword) throws IOException {
        try (PDDocument document = new PDDocument()) {
            addTextPage(document, "Confidential " + CANARY);
            StandardProtectionPolicy policy = new StandardProtectionPolicy(ownerPassword, userPassword, new AccessPermission());
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
            return save(document);
        }
    }

    private static byte[] save(PDDocument document) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        document.save(out);
        return out.toByteArray();
    }

    private static byte[] docx(Builder<XWPFDocument> builder) throws Exception {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            builder.build(document);
            document.write(out);
            return out.toByteArray();
        }
    }

    private static void paragraph(XWPFDocument document, String style, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        if (style != null) {
            paragraph.setStyle(style);
        }
        paragraph.createRun().setText(text);
    }

    private static byte[] xlsx(Builder<XSSFWorkbook> builder) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            builder.build(workbook);
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private static XSSFRow row(XSSFSheet sheet, int index, String... values) {
        XSSFRow row = sheet.createRow(index);
        for (int column = 0; column < values.length; column++) {
            row.createCell(column).setCellValue(values[column]);
        }
        return row;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, byte[]> entries(int count) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("word/document.xml", bytes("<document/>"));
        for (int index = 1; index < count; index++) {
            entries.put("word/part" + index + ".xml", bytes("<part/>"));
        }
        return entries;
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static byte[] zipOfZeros(String entryName, long size) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            writeZeros(zip, entryName, size);
        }
        return out.toByteArray();
    }

    private static void writeZeros(ZipOutputStream zip, String entryName, long size) throws IOException {
        zip.putNextEntry(new ZipEntry(entryName));
        byte[] block = new byte[1 << 20];
        for (long remaining = size; remaining > 0; remaining -= block.length) {
            zip.write(block, 0, (int) Math.min(block.length, remaining));
        }
        zip.closeEntry();
    }

    /** Rewrites one part of a generated Office file, leaving every other entry as POI wrote it. */
    private static byte[] replaceEntry(byte[] archive, String entryName, byte[] content) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), entry.getName().equals(entryName) ? content : zip.readAllBytes());
            }
        }
        assertThat(entries).containsKey(entryName);
        return zip(entries);
    }
}
