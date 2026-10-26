package com.waypoint.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailTemplateServiceTest {

    private final EmailTemplateService emailTemplateService = new EmailTemplateService();

    @Test
    void build_generatesBrandedEmailCompatibleHtml() {
        String html = emailTemplateService.build(EmailTemplateData.builder()
                .preheader("Application submitted")
                .title("Application submitted")
                .greeting("Hi Jordan,")
                .message("Your application was submitted successfully.")
                .highlightedCode("920477")
                .secondaryInformation("This is additional information.")
                .build());

        assertThat(html)
                .contains("<!doctype html>")
                .contains("role=\"presentation\"")
                .contains("background:#f8faf8")
                .contains("background:#ffffff")
                .contains("background:#0f4a3a")
                .contains("WAYPOINT")
                .contains("Application submitted")
                .contains("Hi Jordan,")
                .contains("920477")
                .contains("This is additional information.")
                .contains("© Waypoint")
                .doesNotContain("<script")
                .doesNotContain("<img")
                .doesNotContain("<style");
    }

    @Test
    void build_omitsOptionalSectionsWhenValuesAreAbsent() {
        String html = emailTemplateService.build(EmailTemplateData.builder()
                .title("Event updated")
                .message("The event details changed.")
                .build());

        assertThat(html)
                .doesNotContain("href=")
                .doesNotContain("Consolas")
                .doesNotContain("Event details")
                .contains("Event updated")
                .contains("The event details changed.");
    }

    @Test
    void build_rendersNonblankDetailRowsInSuppliedOrder() {
        String html = emailTemplateService.build(EmailTemplateData.builder()
                .title("Event registration confirmed")
                .message("You're registered.")
                .detailRows(List.of(
                        new EmailDetailRow("Event", "Career Fair"),
                        new EmailDetailRow("Ignored", "  "),
                        new EmailDetailRow("Date", "August 19, 2026")))
                .build());

        assertThat(html)
                .contains("Event details")
                .contains("Career Fair")
                .contains("August 19, 2026")
                .doesNotContain("Ignored");
        assertThat(html.indexOf("Career Fair")).isLessThan(html.indexOf("August 19, 2026"));
    }

    @Test
    void build_escapesDetailRowLabelsAndValues() {
        String html = emailTemplateService.build(EmailTemplateData.builder()
                .title("Event registration confirmed")
                .message("You're registered.")
                .detailRows(List.of(new EmailDetailRow(
                        "<b>Venue</b>", "Wipro & <script>Center</script>")))
                .build());

        assertThat(html)
                .contains("&lt;b&gt;Venue&lt;/b&gt;")
                .contains("Wipro &amp; &lt;script&gt;Center&lt;/script&gt;")
                .doesNotContain("<b>Venue</b>")
                .doesNotContain("<script>Center</script>");
    }

    @Test
    void build_escapesEveryDynamicTextValue() {
        String unsafe = "<script>alert('x') & more</script>";

        String html = emailTemplateService.build(EmailTemplateData.builder()
                .preheader(unsafe)
                .title(unsafe)
                .greeting(unsafe)
                .message(unsafe)
                .highlightedCode(unsafe)
                .secondaryInformation(unsafe)
                .footer(unsafe)
                .build());

        assertThat(html)
                .doesNotContain("<script>")
                .contains("&lt;script&gt;")
                .contains("&#39;x&#39;")
                .contains("&amp; more");
    }

    @Test
    void build_rendersValidatedButtonAndEscapesUrlAttribute() {
        String html = emailTemplateService.build(EmailTemplateData.builder()
                .title("Application submitted")
                .message("Your application was submitted.")
                .actionLabel("View application")
                .actionUrl("https://waypoint.example/applications?source=email&role=jobSeeker")
                .build());

        assertThat(html)
                .contains("View application")
                .contains("href=\"https://waypoint.example/applications?source=email&amp;role=jobSeeker\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/job-seeker/applications",
            "javascript:alert(1)",
            "data:text/html,bad",
            "https://user@example.com/path",
            "https://example.com/path with spaces",
            "https://"
    })
    void build_rejectsInvalidActionUrls(String actionUrl) {
        EmailTemplateData data = EmailTemplateData.builder()
                .title("Application submitted")
                .message("Your application was submitted.")
                .actionLabel("View application")
                .actionUrl(actionUrl)
                .build();

        assertThatThrownBy(() -> emailTemplateService.build(data))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void build_requiresActionLabelWhenUrlIsPresent() {
        EmailTemplateData data = EmailTemplateData.builder()
                .title("Application submitted")
                .message("Your application was submitted.")
                .actionUrl("https://waypoint.example/applications")
                .build();

        assertThatThrownBy(() -> emailTemplateService.build(data))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Action label is required when action URL is supplied");
    }
}
