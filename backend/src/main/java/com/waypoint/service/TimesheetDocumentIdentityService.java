package com.waypoint.service;

import com.waypoint.entity.EmployeeProfile;
import com.waypoint.entity.StoredDocument;
import com.waypoint.exception.BusinessException;
import com.waypoint.repository.EmployeeProfileRepository;
import com.waypoint.repository.StoredDocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Name-only screening; internal, vendor and client IDs are deliberately ignored.
 * A name match is not proof that a document is authentic. */
@Service
@RequiredArgsConstructor
public class TimesheetDocumentIdentityService {
    private static final Pattern NAME = field("employee\\s*name|employee's\\s*name");
    private static final Pattern GENERIC_NAME = field("name");
    private static final String UNVERIFIED_MESSAGE = "Employee name not verified. HR must check the name in this document before approval.";
    private final EmployeeProfileRepository profiles;
    private final StoredDocumentRepository documents;
    private final TimesheetDocumentParserService parser;
    private final Optional<DocumentService> storage;

    public Result validateStored(StoredDocument document) {
        var content = storage.orElseThrow(() -> new BusinessException(
                "Document verification is unavailable. Please try again before submitting."))
                .getContent(document.getId());
        return validate(document, content.content());
    }

    public Result validate(StoredDocument document, byte[] bytes) {
        var profile = profiles.findByUserId(document.getOwnerUserId())
                .filter(p -> document.getOrganizationId().equals(p.getOrganizationId())).orElse(null);
        var result = inspect(parser.extractIdentityText(bytes, document.getFileName()), profile);
        document.setIdentityValidationStatus(result.status());
        document.setIdentityValidationMessage(result.message());
        documents.save(document);
        if ("MISMATCH".equals(result.status())) {
            throw new BusinessException("This document's employee name does not match the employee profile for this timesheet. Upload the correct employee's document or contact HR to correct the profile.");
        }
        return result;
    }

    Result inspect(String text, EmployeeProfile profile) {
        if (profile == null || text == null || text.isBlank()) return unverified();
        var names = new ArrayList<>(values(NAME, text));
        if (names.isEmpty()) names.addAll(values(GENERIC_NAME, text));
        readNameColumns(text, names);
        var expectedNames = List.of(normalizeName(profile.getName()), normalizeName(
                safe(profile.getFirstName()) + " " + safe(profile.getLastName())))
                .stream().filter(n -> n.split(" ").length >= 2).toList();
        if (names.isEmpty() || expectedNames.isEmpty()) return unverified();
        boolean ambiguous = false;
        for (String name : names) {
            String normalized = normalizeName(name);
            var tokens = normalized.split(" ");
            if (tokens.length < 2 || Arrays.stream(tokens).anyMatch(t -> t.length() < 2)) {
                ambiguous = true;
            } else if (!expectedNames.contains(normalized)) {
                // Different spellings and middle names need HR review. Only clearly
                // disjoint full names are rejected automatically.
                boolean overlap = expectedNames.stream().anyMatch(expected ->
                        Arrays.stream(tokens).anyMatch(t -> List.of(expected.split(" ")).contains(t)));
                if (!overlap) return mismatch();
                ambiguous = true;
            }
        }
        return ambiguous ? unverified() : new Result("MATCHED", "Employee name matches the employee profile; HR should still review the document.");
    }

    private static Pattern field(String label) {
        return Pattern.compile("(?im)^\\h*(?:" + label + ")\\h*(?:[:=#,;|\\t]\\h*| {2,})([^\\r\\n]+)$");
    }

    /** CSV and spreadsheet extraction retain column boundaries. Read identity
        columns only when accompanied by a recognizable timesheet header. */
    private static void readNameColumns(String text, List<String> names) {
        int nameColumn = -1;
        char delimiter = ',';
        for (String line : text.split("\\R")) {
            char candidate = line.indexOf('\t') >= 0 ? '\t' : line.indexOf('|') >= 0 ? '|' : ',';
            var cells = cells(line, candidate);
            var headers = cells.stream().map(s -> s.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "")).toList();
            int nextName = -1;
            boolean idHeader = false;
            for (int i = 0; i < headers.size(); i++) {
                if (List.of("employeeid", "empid", "employeenumber", "empnumber", "employeeno", "empno").contains(headers.get(i))) idHeader = true;
                if (List.of("employeename", "name").contains(headers.get(i))) nextName = i;
            }
            boolean hoursHeader = headers.stream().anyMatch(h -> List.of("date", "workdate", "hours", "hoursworked", "totalhours").contains(h));
            if (nextName >= 0 && (hoursHeader || idHeader)) {
                nameColumn = nextName;
                delimiter = candidate;
                continue;
            }
            if (line.isBlank()) { nameColumn = -1; continue; }
            if (nameColumn < 0) continue;
            cells = cells(line, delimiter);
            if (cells.size() < 2 || cells.getFirst().matches("(?i)(?:grand\\s+)?total.*")) continue;
            if (nameColumn >= 0 && cells.size() > nameColumn && !cells.get(nameColumn).isBlank()) names.add(cells.get(nameColumn));
        }
    }

    private static List<String> cells(String line, char delimiter) {
        var result = new ArrayList<String>();
        var value = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') { value.append('"'); i++; }
                else quoted = !quoted;
            } else if (c == delimiter && !quoted) { result.add(value.toString().trim()); value.setLength(0); }
            else value.append(c);
        }
        result.add(value.toString().trim());
        return result;
    }

    private static List<String> values(Pattern pattern, String text) {
        return pattern.matcher(text).results().map(m -> m.group(1).split("[\\t|,;]", 2)[0].trim()
                        .replaceAll("^\"|\"$", ""))
                .filter(v -> !v.isBlank() && v.length() <= 100)
                // Column headings aren't employee identity values.
                .filter(v -> !v.matches("(?i).*(?:employee|emp\\.?\\s*id|date|hours|period|signature|designation|department|manager).*"))
                .toList();
    }

    private static String normalizeName(String name) {
        String normalized = Normalizer.normalize(safe(name), Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("[^\\p{L} ]", " ").trim();
        return Arrays.stream(normalized.split("\\s+")).sorted().reduce((a, b) -> a + " " + b).orElse("");
    }
    private static String safe(String value) { return value == null ? "" : value; }
    private static Result unverified() { return new Result("UNVERIFIED", UNVERIFIED_MESSAGE); }
    private static Result mismatch() { return new Result("MISMATCH", "Employee name does not match the employee profile."); }
    public record Result(String status, String message) {}
}
