package com.waypoint.service;

import com.waypoint.dto.myhr.ParsedTimesheetResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ClaudeDocumentExtractionServiceTest {

    private static final String API_URL = "https://claude.test/v1/messages";

    private ClaudeDocumentExtractionService service(String apiKey, RestClient.Builder builder) {
        return new ClaudeDocumentExtractionService(builder, apiKey, API_URL, "claude-sonnet-5");
    }

    @Test
    void isNotConfiguredWithoutAnApiKey() {
        assertThat(service("", RestClient.builder()).isConfigured()).isFalse();
        assertThat(service("   ", RestClient.builder()).isConfigured()).isFalse();
        assertThat(service("sk-test-key", RestClient.builder()).isConfigured()).isTrue();
    }

    @Test
    void returnsEmptyWithoutMakingARequestWhenNotConfigured() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ClaudeDocumentExtractionService service = service("", builder);

        Optional<ParsedTimesheetResponse> result = service.extract("scan".getBytes(), "scan.pdf", "doc-1", "MDY");

        assertThat(result).isEmpty();
        server.verify();
    }

    @Test
    void returnsEmptyForAFileTypeVisionCannotRead() {
        ClaudeDocumentExtractionService service = service("sk-test-key", RestClient.builder());

        assertThat(service.extract("data".getBytes(), "timesheet.docx", "doc-1", "MDY")).isEmpty();
    }

    @Test
    void extractsEntriesFromAScannedPdfViaTheVisionApi() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ClaudeDocumentExtractionService service = service("sk-test-key", builder);

        String claudeReply = """
                {"content":[{"type":"text","text":"[{\\"date\\":\\"2026-09-01\\",\\"hours\\":8,\\"description\\":\\"Client work\\"},{\\"date\\":\\"2026-09-03\\",\\"hours\\":6.5,\\"description\\":\\"\\"}]"}]}
                """;

        server.expect(requestTo(API_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-api-key", "sk-test-key"))
                .andExpect(header("anthropic-version", "2023-06-01"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andRespond(withSuccess(claudeReply, MediaType.APPLICATION_JSON));

        Optional<ParsedTimesheetResponse> result = service.extract("scanned-bytes".getBytes(), "scan.pdf", "doc-1", "MDY");

        assertThat(result).isPresent();
        ParsedTimesheetResponse parsed = result.get();
        assertThat(parsed.periodStartDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(parsed.periodEndDate()).isEqualTo(LocalDate.of(2026, 9, 3));
        assertThat(parsed.extractionStatus()).isEqualTo("SUCCESS");
        assertThat(parsed.totalHours()).isEqualByComparingTo("14.50");
        // The gap day (Sep 2) is filled in as a zero-hour entry, not skipped.
        assertThat(parsed.entries()).hasSize(3);
        assertThat(parsed.entries().get(0).hoursWorked()).isEqualByComparingTo("8.00");
        assertThat(parsed.entries().get(1).hoursWorked()).isEqualByComparingTo("0.00");
        assertThat(parsed.entries().get(2).hoursWorked()).isEqualByComparingTo("6.50");
    }

    @Test
    void stripsMarkdownCodeFencesAroundTheJsonReply() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ClaudeDocumentExtractionService service = service("sk-test-key", builder);

        String fencedText = "```json\\n[{\\\"date\\\":\\\"2026-09-05\\\",\\\"hours\\\":8,\\\"description\\\":\\\"\\\"}]\\n```";
        String claudeReply = "{\"content\":[{\"type\":\"text\",\"text\":\"" + fencedText + "\"}]}";

        server.expect(requestTo(API_URL)).andRespond(withSuccess(claudeReply, MediaType.APPLICATION_JSON));

        Optional<ParsedTimesheetResponse> result = service.extract("bytes".getBytes(), "scan.png", "doc-2", "MDY");

        assertThat(result).isPresent();
        assertThat(result.get().entries()).singleElement()
                .satisfies(entry -> assertThat(entry.workDate()).isEqualTo(LocalDate.of(2026, 9, 5)));
    }

    @Test
    void returnsEmptyWhenTheModelReportsNothingReadable() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ClaudeDocumentExtractionService service = service("sk-test-key", builder);

        server.expect(requestTo(API_URL))
                .andRespond(withSuccess("{\"content\":[{\"type\":\"text\",\"text\":\"[]\"}]}", MediaType.APPLICATION_JSON));

        assertThat(service.extract("bytes".getBytes(), "scan.pdf", "doc-3", "MDY")).isEmpty();
    }

    @Test
    void returnsEmptyWhenTheModelRespondsWithUnparsableText() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ClaudeDocumentExtractionService service = service("sk-test-key", builder);

        server.expect(requestTo(API_URL))
                .andRespond(withSuccess("{\"content\":[{\"type\":\"text\",\"text\":\"Sorry, I could not read this.\"}]}", MediaType.APPLICATION_JSON));

        assertThat(service.extract("bytes".getBytes(), "scan.pdf", "doc-4", "MDY")).isEmpty();
    }

    @Test
    void returnsEmptyWhenTheApiCallFails() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ClaudeDocumentExtractionService service = service("sk-test-key", builder);

        server.expect(requestTo(API_URL)).andRespond(withServerError());

        assertThat(service.extract("bytes".getBytes(), "scan.pdf", "doc-5", "MDY")).isEmpty();
    }

    @Test
    void ignoresAFutureDatedEntryReportedByTheModel() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ClaudeDocumentExtractionService service = service("sk-test-key", builder);

        LocalDate future = LocalDate.now().plusYears(1);
        String reply = "{\"content\":[{\"type\":\"text\",\"text\":\"[{\\\"date\\\":\\\"" + future + "\\\",\\\"hours\\\":8,\\\"description\\\":\\\"\\\"}]\"}]}";
        server.expect(requestTo(API_URL)).andRespond(withSuccess(reply, MediaType.APPLICATION_JSON));

        assertThat(service.extract("bytes".getBytes(), "scan.pdf", "doc-6", "MDY")).isEmpty();
    }
}
