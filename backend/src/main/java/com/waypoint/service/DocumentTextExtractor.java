package com.waypoint.service;

import com.waypoint.exception.DocumentExtractionException;
import com.waypoint.exception.DocumentExtractionException.Reason;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFSDT;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Turns an uploaded PDF, DOCX, XLSX, TXT or Markdown document into plain text for the MyHR RAG
 * pipeline.
 *
 * <p>Bytes in, text out: this class cannot look up or fetch stored documents, so the caller alone
 * decides which documents are eligible for the knowledge base. Output keeps document order and is
 * shaped for DocumentChunkingService: PDF pages and spreadsheet sheets are separated by form feeds,
 * lines end in single newlines, table cells are tab-separated, and a blank line marks a section
 * boundary such as a heading or a table.
 *
 * <p>The size, page, row, column and text limits mirror TimesheetDocumentParserService. Unlike that
 * parser, nothing fails silently: a document that cannot be used raises a
 * DocumentExtractionException with a reason. No OCR, formula evaluation, macro or external process
 * is ever run, and hidden sheets, rows and columns, DOCX headers, footers and comments, and PDF
 * form fields and metadata are not read.
 */
@Service
public class DocumentTextExtractor {

    static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    static final int MAX_PDF_PAGES = 100;
    static final int MAX_TEXT_CHARS = 1_000_000;
    // 0-based index of a sheet's last row, so a sheet may hold up to 10,001 rows.
    static final int MAX_LAST_ROW_INDEX = 10_000;
    static final int MAX_ROW_CELLS = 100;
    static final int MAX_ARCHIVE_ENTRIES = 1_000;
    static final long MAX_ARCHIVE_INFLATED_BYTES = 50L * 1024 * 1024;

    private static final byte[] PDF_SIGNATURE = {'%', 'P', 'D', 'F', '-'};
    private static final byte[] ZIP_SIGNATURE = {'P', 'K', 3, 4};
    private static final String DOCX_MAIN_PART = "word/document.xml";
    private static final String XLSX_MAIN_PART = "xl/workbook.xml";
    private static final Pattern CELL_BREAKS = Pattern.compile("[\\t\\r\\n]+");
    private static final Pattern HIDDEN_HTML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern INSTRUCTION_LIKE_CONTENT = Pattern.compile(
            "(?i)\\b(?:ignore|disregard|override)\\s+(?:all\\s+)?(?:previous|prior|above)\\s+instructions?\\b"
                    + "|\\b(?:system|developer)\\s+prompt\\b"
                    + "|\\b(?:reveal|expose)\\s+(?:the\\s+)?(?:system|developer)\\s+(?:prompt|instructions?)\\b");
    private static final double DOCX_XLSX_MIN_FONT_SIZE = 4.0;
    private static final double PDF_MIN_FONT_SIZE = 3.0;
    private static final Rgb WHITE = new Rgb(255, 255, 255);

    /** Returns the document's text, or throws DocumentExtractionException when it cannot be used. */
    public ExtractedText extract(byte[] content, String fileName) {
        if (content == null || content.length == 0) {
            throw new DocumentExtractionException(Reason.EMPTY);
        }
        if (content.length > MAX_FILE_BYTES) {
            throw new DocumentExtractionException(Reason.TOO_LARGE);
        }
        DocumentFormat format = formatOf(fileName);
        if (!hasSignature(content, format)) {
            throw new DocumentExtractionException(Reason.FORMAT_MISMATCH);
        }

        ExtractedText extracted;
        try {
            extracted = switch (format) {
                case PDF -> extractPdf(content);
                case DOCX -> asExtractedText(format, extractDocx(content));
                case XLSX -> asExtractedText(format, extractXlsx(content));
                case TEXT -> asExtractedText(format, extractPlainText(content));
            };
        } catch (DocumentExtractionException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            // Parser messages can quote document content, so only the reason is reported.
            throw new DocumentExtractionException(Reason.MALFORMED);
        }
        if (extracted.text().isBlank()) {
            throw new DocumentExtractionException(Reason.NO_TEXT);
        }
        return extracted;
    }

    private static ExtractedText asExtractedText(DocumentFormat format, ExtractionResult result) {
        return new ExtractedText(format, result.text(), 0, 0, result.report());
    }

    private static DocumentFormat formatOf(String fileName) {
        String name = fileName == null ? "" : fileName.strip().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1);
        return switch (extension) {
            case "pdf" -> DocumentFormat.PDF;
            case "docx" -> DocumentFormat.DOCX;
            case "xlsx" -> DocumentFormat.XLSX;
            case "txt", "md" -> DocumentFormat.TEXT;
            default -> throw new DocumentExtractionException(Reason.UNSUPPORTED_FORMAT);
        };
    }

    private static boolean hasSignature(byte[] content, DocumentFormat format) {
        return switch (format) {
            case PDF -> startsWith(content, PDF_SIGNATURE);
            case DOCX, XLSX -> startsWith(content, ZIP_SIGNATURE);
            // Text has no signature; a NUL byte means binary content (or UTF-16, which is not accepted).
            case TEXT -> !containsNul(content);
        };
    }

    private static ExtractedText extractPdf(byte[] content) throws IOException {
        try (PDDocument document = Loader.loadPDF(content, "", null, null, IOUtils.createMemoryOnlyStreamCache())) {
            if (document.isEncrypted()) {
                throw new DocumentExtractionException(Reason.ENCRYPTED);
            }
            int pageCount = document.getNumberOfPages();
            if (pageCount > MAX_PDF_PAGES) {
                throw new DocumentExtractionException(Reason.TOO_MANY_PAGES);
            }
            InspectionReportBuilder report = new InspectionReportBuilder();
            PDFTextStripper stripper = new InspectingPdfTextStripper(report);
            stripper.setLineSeparator("\n");
            stripper.setPageStart("");
            stripper.setPageEnd("");
            BoundedText text = new BoundedText();
            int pagesWithoutText = 0;
            // One page at a time, so pages without a text layer (usually scans) can be counted.
            for (int page = 1; page <= pageCount; page++) {
                BoundedText pageText = new BoundedText();
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                stripper.writeText(document, pageText);
                String value = pageText.toString().strip();
                if (value.isEmpty()) {
                    pagesWithoutText++;
                    continue;
                }
                if (!text.isEmpty()) {
                    text.add("\f");
                }
                text.add(value);
            }
            report.pagesRequiringOcrReview = pagesWithoutText;
            String extracted = text.toString();
            return new ExtractedText(DocumentFormat.PDF, extracted, pageCount, pagesWithoutText, report.build(extracted));
        } catch (InvalidPasswordException passwordRequired) {
            throw new DocumentExtractionException(Reason.ENCRYPTED);
        }
    }

    private static ExtractionResult extractDocx(byte[] content) throws IOException {
        inspectArchive(content, DOCX_MAIN_PART);
        BoundedText text = new BoundedText();
        InspectionReportBuilder report = new InspectionReportBuilder();
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content))) {
            // Body only: headers, footers and comments live in other parts and are never read.
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    appendParagraph(text, paragraph, report);
                } else if (element instanceof XWPFTable table) {
                    appendTable(text, table, report);
                } else if (element instanceof XWPFSDT contentControl) {
                    appendLine(text, contentControl.getContent().getText());
                }
            }
        }
        // Trailing only: a leading tab can be a table's first, empty cell.
        String extracted = text.toString().stripTrailing();
        return new ExtractionResult(extracted, report.build(extracted));
    }

    private static void appendParagraph(BoundedText text, XWPFParagraph paragraph, InspectionReportBuilder report) {
        String line = paragraphText(paragraph, report);
        if (line.isBlank()) {
            return;
        }
        String style = paragraph.getStyle();
        if (style != null && (style.startsWith("Heading") || style.equals("Title"))) {
            text.blankLine();
        }
        appendLine(text, line);
    }

    private static String paragraphText(XWPFParagraph paragraph, InspectionReportBuilder report) {
        StringBuilder line = new StringBuilder();
        for (XWPFRun run : paragraph.getRuns()) {
            String runText = run.text();
            if (run.isVanish()) {
                if (!runText.isBlank()) {
                    report.hiddenDocxRunsExcluded++;
                }
                continue;
            }
            inspectDocxRun(run, runText, report);
            line.append(runText);
        }
        return line.toString();
    }

    private static void inspectDocxRun(XWPFRun run, String text, InspectionReportBuilder report) {
        if (text.isBlank()) {
            return;
        }
        Double fontSize = run.getFontSizeAsDouble();
        if (fontSize != null && fontSize > 0 && fontSize < DOCX_XLSX_MIN_FONT_SIZE) {
            report.suspiciousTextFragments++;
        }
        if (isNearWhite(hexColor(run.getColor())) && !hasDarkRunBackground(run)) {
            report.suspiciousTextFragments++;
        }
    }

    private static boolean hasDarkRunBackground(XWPFRun run) {
        if (!run.getCTR().isSetRPr() || run.getCTR().getRPr().sizeOfShdArray() == 0) {
            return false;
        }
        Object fill = run.getCTR().getRPr().getShdArray(0).getFill();
        return isDark(hexColor(fill));
    }

    private static void appendTable(BoundedText text, XWPFTable table, InspectionReportBuilder report) {
        text.blankLine();
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                cells.add(cellText(cell, report));
            }
            appendRow(text, cells);
        }
        text.blankLine();
    }

    private static String cellText(XWPFTableCell cell, InspectionReportBuilder report) {
        StringBuilder value = new StringBuilder();
        for (IBodyElement element : cell.getBodyElements()) {
            if (element instanceof XWPFParagraph paragraph) {
                if (!value.isEmpty()) {
                    value.append('\n');
                }
                value.append(paragraphText(paragraph, report));
            }
        }
        return value.toString();
    }

    private static ExtractionResult extractXlsx(byte[] content) throws IOException {
        inspectArchive(content, XLSX_MAIN_PART);
        BoundedText text = new BoundedText();
        InspectionReportBuilder report = new InspectionReportBuilder();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            DataFormatter formatter = new DataFormatter(Locale.US);
            // Cached results only: formulas are never evaluated, so external references are never followed.
            formatter.setUseCachedValuesForFormulaCells(true);
            for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
                XSSFSheet sheet = workbook.getSheetAt(index);
                boolean visible = workbook.getSheetVisibility(index) == SheetVisibility.VISIBLE;
                String sheetText = sheetText(sheet, visible, formatter, report);
                if (sheetText.isEmpty()) {
                    continue;
                }
                if (!text.isEmpty()) {
                    text.add("\f");
                }
                text.add(sheet.getSheetName()).add("\n").add(sheetText.stripTrailing());
            }
        }
        String extracted = text.toString().stripTrailing();
        return new ExtractionResult(extracted, report.build(extracted));
    }

    /** Row and column limits apply to every sheet, as in timesheet parsing, but hidden content is never read. */
    private static String sheetText(XSSFSheet sheet, boolean visible, DataFormatter formatter, InspectionReportBuilder report) {
        if (sheet.getLastRowNum() > MAX_LAST_ROW_INDEX) {
            throw new DocumentExtractionException(Reason.TOO_MANY_ROWS);
        }
        boolean[] hiddenColumns = new boolean[MAX_ROW_CELLS];
        for (int column = 0; visible && column < MAX_ROW_CELLS; column++) {
            hiddenColumns[column] = sheet.isColumnHidden(column);
        }
        BoundedText text = new BoundedText();
        for (Row row : sheet) {
            if (row.getLastCellNum() > MAX_ROW_CELLS) {
                throw new DocumentExtractionException(Reason.TOO_MANY_COLUMNS);
            }
            if (!visible || row.getZeroHeight()) {
                continue;
            }
            List<String> cells = new ArrayList<>();
            for (int column = 0; column < row.getLastCellNum(); column++) {
                if (!hiddenColumns[column]) {
                    Cell cell = row.getCell(column);
                    String value = cell == null ? "" : formatter.formatCellValue(cell);
                    if (cell != null) {
                        inspectXlsxCell(cell, value, report);
                    }
                    cells.add(value);
                }
            }
            appendRow(text, cells);
        }
        return text.toString();
    }

    private static void inspectXlsxCell(Cell cell, String text, InspectionReportBuilder report) {
        if (text.isBlank() || !(cell.getCellStyle() instanceof XSSFCellStyle style)) {
            return;
        }
        XSSFFont font = style.getFont();
        if (font.getFontHeightInPoints() > 0 && font.getFontHeightInPoints() < DOCX_XLSX_MIN_FONT_SIZE) {
            report.suspiciousTextFragments++;
        }
        Rgb fontColor = colorOf(font.getXSSFColor(), font.getColor());
        Rgb background = cellBackground(style);
        if (fontColor != null && background != null
                && (colorsMatch(fontColor, background) || (isNearWhite(fontColor) && isNearWhite(background)))) {
            report.suspiciousTextFragments++;
        }
    }

    private static Rgb cellBackground(XSSFCellStyle style) {
        if (style.getFillPattern() != FillPatternType.SOLID_FOREGROUND) {
            return WHITE;
        }
        return colorOf(style.getFillForegroundColorColor(), style.getFillForegroundColor());
    }

    private static ExtractionResult extractPlainText(byte[] content) throws CharacterCodingException {
        String decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content))
                .toString();
        String text = decoded.startsWith("﻿") ? decoded.substring(1) : decoded;
        InspectionReportBuilder report = new InspectionReportBuilder();
        String withoutComments = removeHiddenComments(text, report);
        String withoutTagCharacters = removeTagCharacters(withoutComments, report);
        report.instructionLikeContentMatches = countInstructionLikeContent(withoutTagCharacters);
        String extracted = new BoundedText().add(withoutTagCharacters).toString().strip();
        return new ExtractionResult(extracted, report.build(extracted));
    }

    private static String removeHiddenComments(String text, InspectionReportBuilder report) {
        var matcher = HIDDEN_HTML_COMMENT.matcher(text);
        StringBuffer visible = new StringBuffer();
        while (matcher.find()) {
            report.hiddenCommentBlocks++;
            matcher.appendReplacement(visible, "");
        }
        matcher.appendTail(visible);
        return visible.toString();
    }

    private static String removeTagCharacters(String text, InspectionReportBuilder report) {
        StringBuilder visible = new StringBuilder(text.length());
        text.codePoints().forEach(codePoint -> {
            if (isUnicodeTagCharacter(codePoint)) {
                report.unicodeTagCharactersRemoved++;
                report.invisibleUnicodeCharacters++;
            } else {
                if (Character.getType(codePoint) == Character.FORMAT) {
                    report.invisibleUnicodeCharacters++;
                }
                visible.appendCodePoint(codePoint);
            }
        });
        return visible.toString();
    }

    private static int countInstructionLikeContent(String text) {
        var matcher = INSTRUCTION_LIKE_CONTENT.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static boolean isUnicodeTagCharacter(int codePoint) {
        return codePoint >= 0xE0000 && codePoint <= 0xE007F;
    }

    private static Rgb hexColor(String value) {
        if (value == null || !value.matches("(?i)[0-9a-f]{6}")) {
            return null;
        }
        return new Rgb(
                Integer.parseInt(value.substring(0, 2), 16),
                Integer.parseInt(value.substring(2, 4), 16),
                Integer.parseInt(value.substring(4, 6), 16));
    }

    private static Rgb hexColor(Object value) {
        if (value instanceof byte[] rgb && rgb.length >= 3) {
            int offset = rgb.length - 3;
            return new Rgb(Byte.toUnsignedInt(rgb[offset]), Byte.toUnsignedInt(rgb[offset + 1]),
                    Byte.toUnsignedInt(rgb[offset + 2]));
        }
        return value == null ? null : hexColor(value.toString());
    }

    private static Rgb colorOf(XSSFColor color, short indexedColor) {
        if (color != null) {
            byte[] rgb = color.getRGB();
            if (rgb != null && rgb.length >= 3) {
                int offset = rgb.length - 3;
                return new Rgb(Byte.toUnsignedInt(rgb[offset]), Byte.toUnsignedInt(rgb[offset + 1]),
                        Byte.toUnsignedInt(rgb[offset + 2]));
            }
        }
        if (indexedColor == IndexedColors.WHITE.getIndex()) {
            return WHITE;
        }
        if (indexedColor == IndexedColors.BLACK.getIndex()) {
            return new Rgb(0, 0, 0);
        }
        return null;
    }

    private static Rgb pdfColor(PDColor color) {
        if (color == null || color.isPattern()) {
            return null;
        }
        try {
            int rgb = color.toRGB();
            return new Rgb((rgb >>> 16) & 0xff, (rgb >>> 8) & 0xff, rgb & 0xff);
        } catch (IOException unsupportedColor) {
            // Keep extracting text when a color space cannot be reduced to RGB; it remains reviewable.
            return null;
        }
    }

    private static boolean isNearWhite(Rgb color) {
        return color != null && color.red() >= 245 && color.green() >= 245 && color.blue() >= 245;
    }

    private static boolean isDark(Rgb color) {
        return color != null && (color.red() * 0.2126 + color.green() * 0.7152 + color.blue() * 0.0722) < 90;
    }

    private static boolean colorsMatch(Rgb first, Rgb second) {
        return Math.abs(first.red() - second.red()) <= 12
                && Math.abs(first.green() - second.green()) <= 12
                && Math.abs(first.blue() - second.blue()) <= 12;
    }

    private static final class InspectingPdfTextStripper extends PDFTextStripper {
        private final InspectionReportBuilder report;

        private InspectingPdfTextStripper(InspectionReportBuilder report) throws IOException {
            this.report = report;
        }

        @Override
        protected void processTextPosition(TextPosition text) {
            if (!text.getUnicode().isBlank()) {
                RenderingMode renderingMode = getGraphicsState().getTextState().getRenderingMode();
                if (renderingMode != null && !renderingMode.isFill() && !renderingMode.isStroke()) {
                    report.suspiciousTextFragments++;
                }
                if (text.getFontSizeInPt() > 0 && text.getFontSizeInPt() < PDF_MIN_FONT_SIZE) {
                    report.suspiciousTextFragments++;
                }
                if (isNearWhite(pdfColor(getGraphicsState().getNonStrokingColor()))) {
                    report.suspiciousTextFragments++;
                }
            }
            super.processTextPosition(text);
        }
    }

    /**
     * Walks the whole archive before POI opens it, counting the bytes each entry really inflates to
     * instead of trusting declared sizes. POI's own zip-bomb settings are JVM-wide and shared with
     * timesheet parsing, so they stay at their defaults rather than being tightened here.
     */
    private static void inspectArchive(byte[] content, String mainPart) throws IOException {
        int entries = 0;
        long inflatedBytes = 0;
        boolean hasMainPart = false;
        byte[] buffer = new byte[8192];
        try (ZipInputStream archive = new ZipInputStream(new ByteArrayInputStream(content))) {
            for (ZipEntry entry = archive.getNextEntry(); entry != null; entry = archive.getNextEntry()) {
                if (++entries > MAX_ARCHIVE_ENTRIES) {
                    throw new DocumentExtractionException(Reason.ARCHIVE_LIMIT_EXCEEDED);
                }
                hasMainPart |= entry.getName().equalsIgnoreCase(mainPart);
                for (int read = archive.read(buffer); read != -1; read = archive.read(buffer)) {
                    inflatedBytes += read;
                    if (inflatedBytes > MAX_ARCHIVE_INFLATED_BYTES) {
                        throw new DocumentExtractionException(Reason.ARCHIVE_LIMIT_EXCEEDED);
                    }
                }
            }
        }
        if (entries == 0) {
            throw new DocumentExtractionException(Reason.MALFORMED);
        }
        if (!hasMainPart) {
            throw new DocumentExtractionException(Reason.FORMAT_MISMATCH);
        }
    }

    private static void appendLine(BoundedText text, String line) {
        if (!line.isBlank()) {
            text.add(line.strip()).add("\n");
        }
    }

    /** One tab-separated line per row; trailing empty cells are dropped and empty rows are skipped. */
    private static void appendRow(BoundedText text, List<String> cells) {
        int last = cells.size() - 1;
        while (last >= 0 && cells.get(last).isBlank()) {
            last--;
        }
        if (last < 0) {
            return;
        }
        for (int index = 0; index <= last; index++) {
            if (index > 0) {
                text.add("\t");
            }
            text.add(CELL_BREAKS.matcher(cells.get(index)).replaceAll(" ").strip());
        }
        text.add("\n");
    }

    private static boolean startsWith(byte[] content, byte[] prefix) {
        return content.length >= prefix.length
                && Arrays.equals(content, 0, prefix.length, prefix, 0, prefix.length);
    }

    private static boolean containsNul(byte[] content) {
        for (byte value : content) {
            if (value == 0) {
                return true;
            }
        }
        return false;
    }

    public enum DocumentFormat {
        PDF, DOCX, XLSX, TEXT
    }

    /**
     * Extracted text in document order. pageCount and pagesWithoutText are reported for PDFs only
     * (0 otherwise); pages without text are usually scans, which are not OCR'd, so a non-zero
     * pagesWithoutText means the extracted text is incomplete. inspection is informational in this
     * phase; KnowledgeApprovalService records it and decides whether workflow review is required.
     */
    public record ExtractedText(DocumentFormat format, String text, int pageCount, int pagesWithoutText,
                                ContentInspection inspection) {
        public ExtractedText(DocumentFormat format, String text, int pageCount, int pagesWithoutText) {
            this(format, text, pageCount, pagesWithoutText, ContentInspection.none());
        }
    }

    /**
     * Extraction-only findings. Callers decide workflow state; this type never stores source text.
     */
    public record ContentInspection(
            int hiddenDocxRunsExcluded,
            int suspiciousTextFragments,
            int invisibleUnicodeCharacters,
            int unicodeTagCharactersRemoved,
            int hiddenCommentBlocks,
            int instructionLikeContentMatches,
            int pagesRequiringOcrReview) {

        /** Increment only when the persisted representation of these findings changes. */
        public static final int FORMAT_VERSION = 1;
        private static final int MAX_REVIEWABLE_PASSAGES = 32;

        public static ContentInspection none() {
            return new ContentInspection(0, 0, 0, 0, 0, 0, 0);
        }

        public boolean requiresReview() {
            return hiddenDocxRunsExcluded > 0 || suspiciousTextFragments > 0 || invisibleUnicodeCharacters > 0
                    || hiddenCommentBlocks > 0 || instructionLikeContentMatches > 0 || pagesRequiringOcrReview > 0;
        }

        /**
         * Builds a bounded, deterministic preview model from the exact extracted text. Text already
         * excluded by Phase 1A is represented without its content, so it can never be reintroduced.
         * The formatting scanners cannot preserve run offsets after DOCX/PDF/XLSX normalization;
         * their only safe selectable range is therefore the complete normalized text.
         */
        public List<ReviewablePassage> reviewablePassages(String extractedText) {
            String text = extractedText == null ? "" : extractedText;
            List<ReviewablePassage> passages = new ArrayList<>();
            if (hiddenDocxRunsExcluded > 0) {
                passages.add(new ReviewablePassage("hidden-docx-runs", FindingCategory.HIDDEN_TEXT,
                        "DOCX hidden runs", -1, -1, true, true, null));
            }
            if (suspiciousTextFragments > 0 && !text.isEmpty()) {
                passages.add(new ReviewablePassage("suspicious-normalized-text", FindingCategory.SUSPICIOUS_FORMATTING,
                        "normalized extracted text", 0, text.length(), false, true, text));
            }
            if (invisibleUnicodeCharacters > 0) {
                passages.add(new ReviewablePassage("removed-invisible-unicode", FindingCategory.INVISIBLE_UNICODE,
                        "plain-text normalization", -1, -1, true, true, null));
            }
            if (hiddenCommentBlocks > 0) {
                passages.add(new ReviewablePassage("removed-hidden-comments", FindingCategory.HIDDEN_COMMENT,
                        "plain-text comments", -1, -1, true, true, null));
            }
            var matcher = INSTRUCTION_LIKE_CONTENT.matcher(text);
            int index = 0;
            while (matcher.find() && passages.size() < MAX_REVIEWABLE_PASSAGES) {
                passages.add(new ReviewablePassage("instruction-" + index++, FindingCategory.INSTRUCTION_LIKE,
                        "normalized extracted text", matcher.start(), matcher.end(), false, true, matcher.group()));
            }
            if (pagesRequiringOcrReview > 0 && passages.size() < MAX_REVIEWABLE_PASSAGES) {
                passages.add(new ReviewablePassage("ocr-missing-pages", FindingCategory.OCR_REVIEW,
                        "PDF pages without a text layer", -1, -1, true, true, null));
            }
            return List.copyOf(passages);
        }

        /** A stable, text-free representation for approval and review binding. */
        public String canonicalForm() {
            return FORMAT_VERSION + "|" + hiddenDocxRunsExcluded + "|" + suspiciousTextFragments + "|"
                    + invisibleUnicodeCharacters + "|" + unicodeTagCharactersRemoved + "|"
                    + hiddenCommentBlocks + "|" + instructionLikeContentMatches + "|" + pagesRequiringOcrReview;
        }
    }

    public enum FindingCategory {
        HIDDEN_TEXT, SUSPICIOUS_FORMATTING, INVISIBLE_UNICODE, HIDDEN_COMMENT, INSTRUCTION_LIKE, OCR_REVIEW
    }

    /** Text is present only for a range already included in the normalized output. */
    public record ReviewablePassage(String id, FindingCategory category, String sourceLocation,
                                    int startOffset, int endOffset, boolean excludedFromIndex,
                                    boolean requiresReview, String text) {
    }

    private record ExtractionResult(String text, ContentInspection report) {
    }

    private record Rgb(int red, int green, int blue) {
    }

    private static final class InspectionReportBuilder {
        private int hiddenDocxRunsExcluded;
        private int suspiciousTextFragments;
        private int invisibleUnicodeCharacters;
        private int unicodeTagCharactersRemoved;
        private int hiddenCommentBlocks;
        private int instructionLikeContentMatches;
        private int pagesRequiringOcrReview;

        /**
         * Instruction-like wording is counted on the final extracted text of every format, so the
         * count always describes exactly the text that is previewed and indexed.
         */
        private ContentInspection build(String extractedText) {
            int instructionLike = Math.max(instructionLikeContentMatches,
                    countInstructionLikeContent(extractedText == null ? "" : extractedText));
            return new ContentInspection(hiddenDocxRunsExcluded, suspiciousTextFragments, invisibleUnicodeCharacters,
                    unicodeTagCharactersRemoved, hiddenCommentBlocks, instructionLike,
                    pagesRequiringOcrReview);
        }
    }

    /** Collects text and fails as soon as it would pass MAX_TEXT_CHARS, so oversized text is never buffered. */
    private static final class BoundedText extends Writer {
        private final StringBuilder text = new StringBuilder();

        @Override
        public void write(char[] chars, int offset, int length) {
            reserve(length);
            text.append(chars, offset, length);
        }

        @Override
        public void write(String value, int offset, int length) {
            reserve(length);
            text.append(value, offset, offset + length);
        }

        BoundedText add(String value) {
            write(value, 0, value.length());
            return this;
        }

        boolean isEmpty() {
            return text.isEmpty();
        }

        /** Ends the current section with a blank line, unless there is no text yet or it already ends with one. */
        void blankLine() {
            int length = text.length();
            if (length > 0 && !(length >= 2 && text.charAt(length - 1) == '\n' && text.charAt(length - 2) == '\n')) {
                add("\n");
            }
        }

        private void reserve(int length) {
            if (text.length() + length > MAX_TEXT_CHARS) {
                throw new DocumentExtractionException(Reason.TEXT_LIMIT_EXCEEDED);
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }
}
