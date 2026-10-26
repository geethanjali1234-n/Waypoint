package com.waypoint.service;

import com.waypoint.dto.myhr.ParsedTimesheetResponse;
import com.waypoint.dto.myhr.TimesheetEntryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Vision-based fallback for reading a timesheet document when the local, deterministic
 * parser (regex/table matching plus local Tesseract OCR) can't read it - most commonly a
 * scanned PDF or photographed image on a machine without Tesseract installed. Disabled
 * unless an API key is configured; never invents data the model didn't actually report.
 */
@Service
public class ClaudeDocumentExtractionService {
    private static final Logger log = LoggerFactory.getLogger(ClaudeDocumentExtractionService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_TOKENS = 4096;
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final RestClient restClient;
    private final String apiKey;
    private final String apiUrl;
    private final String model;

    public ClaudeDocumentExtractionService(
            RestClient.Builder restClientBuilder,
            @Value("${waypoint.myhr.timesheet-document-ai.api-key:}") String apiKey,
            @Value("${waypoint.myhr.timesheet-document-ai.api-url:https://api.anthropic.com/v1/messages}") String apiUrl,
            @Value("${waypoint.myhr.timesheet-document-ai.model:claude-sonnet-5}") String model) {
        this.restClient = restClientBuilder.build();
        this.apiKey = apiKey;
        this.apiUrl = apiUrl;
        this.model = model;
    }

    public boolean isConfigured() {
        return StringUtils.hasText(apiKey);
    }

    public Optional<ParsedTimesheetResponse> extract(byte[] bytes, String fileName, String documentId, String dateOrder) {
        if (!isConfigured() || bytes == null || bytes.length == 0) return Optional.empty();
        String mediaType = mediaTypeFor(fileName);
        if (mediaType == null) return Optional.empty();

        try {
            String replyText = requestExtraction(bytes, mediaType, dateOrder);
            return replyText == null ? Optional.empty() : toResponse(replyText, fileName, documentId);
        } catch (RestClientException exception) {
            log.warn("Claude document extraction request failed for {}", fileName, exception);
            return Optional.empty();
        } catch (Exception exception) {
            log.warn("Claude document extraction response could not be read for {}", fileName, exception);
            return Optional.empty();
        }
    }

    private String mediaTypeFor(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        return null;
    }

    private String requestExtraction(byte[] bytes, String mediaType, String dateOrder) {
        String base64 = Base64.getEncoder().encodeToString(bytes);
        String blockType = mediaType.equals("application/pdf") ? "document" : "image";

        var fileBlock = JSON.createObjectNode();
        fileBlock.put("type", blockType);
        var source = fileBlock.putObject("source");
        source.put("type", "base64");
        source.put("media_type", mediaType);
        source.put("data", base64);

        var textBlock = JSON.createObjectNode();
        textBlock.put("type", "text");
        textBlock.put("text", prompt(dateOrder));

        var content = JSON.createArrayNode();
        content.add(fileBlock);
        content.add(textBlock);

        var message = JSON.createObjectNode();
        message.put("role", "user");
        message.set("content", content);

        var messages = JSON.createArrayNode();
        messages.add(message);

        var requestBody = JSON.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("max_tokens", MAX_TOKENS);
        requestBody.set("messages", messages);

        JsonNode response = restClient.post()
                .uri(apiUrl)
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(JsonNode.class);

        if (response == null) return null;
        JsonNode contentArray = response.path("content");
        if (!contentArray.isArray() || contentArray.isEmpty()) return null;
        for (JsonNode block : contentArray) {
            if ("text".equals(block.path("type").asString())) return block.path("text").asString();
        }
        return null;
    }

    private String prompt(String dateOrder) {
        String order = "DMY".equals(dateOrder) ? "day/month/year" : "month/day/year";
        return """
                You are reading an employee timesheet document. Extract every day that shows \
                recorded work hours. Respond with ONLY a JSON array, no markdown code fences, \
                no commentary, in exactly this shape:
                [{"date":"YYYY-MM-DD","hours":8,"description":""}]

                Rules:
                - Only include a day if the document clearly shows worked hours for it.
                - Never invent, estimate, or guess a date or hour value that is not clearly printed or written in the document.
                - Interpret any two-part numeric date in the document as """ + order + """
                 order.
                - "hours" must be a plain decimal number (e.g. 8 or 7.5), not a time range or clock time.
                - "description" is optional free text for that row if present, otherwise an empty string.
                - If you cannot reliably read any dates and hours from this document at all, respond with exactly: []
                """;
    }

    private Optional<ParsedTimesheetResponse> toResponse(String replyText, String fileName, String documentId) {
        String cleaned = replyText.strip();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("```\\s*$", "").strip();
        }
        JsonNode parsed;
        try {
            parsed = JSON.readTree(cleaned);
        } catch (Exception exception) {
            return Optional.empty();
        }
        if (!parsed.isArray() || parsed.isEmpty()) return Optional.empty();

        LocalDate today = LocalDate.now();
        TreeMap<LocalDate, TimesheetEntryResponse> byDate = new TreeMap<>();
        for (JsonNode row : parsed) {
            LocalDate date = parseDate(row.path("date").asString(null));
            BigDecimal hours = parseHours(row.path("hours"));
            if (date == null || hours == null || date.isAfter(today)) continue;
            if (hours.compareTo(BigDecimal.ZERO) < 0 || hours.compareTo(BigDecimal.valueOf(24)) > 0) continue;
            String description = row.path("description").asString("");
            byDate.put(date, new TimesheetEntryResponse(
                    date, date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.US),
                    hours.setScale(2, RoundingMode.HALF_UP), description == null ? "" : description));
        }
        if (byDate.isEmpty()) return Optional.empty();

        LocalDate start = byDate.firstKey();
        LocalDate end = byDate.lastKey();
        if (ChronoUnit.DAYS.between(start, end) >= 31) return Optional.empty();

        List<TimesheetEntryResponse> entries = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (LocalDate current = start; !current.isAfter(end); current = current.plusDays(1)) {
            TimesheetEntryResponse entry = byDate.get(current);
            if (entry == null) {
                entries.add(new TimesheetEntryResponse(current, current.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.US),
                        BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), ""));
            } else {
                entries.add(entry);
                total = total.add(entry.hoursWorked());
            }
        }

        String message = "Extracted " + byDate.size() + " day(s) using AI document reading. "
                + "Verify every date and hour against the attached document before submitting.";
        return Optional.of(new ParsedTimesheetResponse(documentId, fileName, start, end,
                total.setScale(2, RoundingMode.HALF_UP), entries, "SUCCESS", message));
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.strip());
        } catch (Exception exception) {
            return null;
        }
    }

    private BigDecimal parseHours(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        try {
            if (node.isNumber()) return node.decimalValue();
            return new BigDecimal(node.asString().strip());
        } catch (Exception exception) {
            return null;
        }
    }
}
