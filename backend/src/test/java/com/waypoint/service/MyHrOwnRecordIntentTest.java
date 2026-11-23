package com.waypoint.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared intent case table. The frontend's waviIntents.test.js checks the same file, so the backend
 * route and the navigation shortcut cannot drift apart.
 */
class MyHrOwnRecordIntentTest {
    private static final Path CASES = locate("frontend/src/test/fixtures/waviIntentCases.json");

    static Stream<Arguments> cases() throws Exception {
        JsonNode root = new ObjectMapper().readTree(Files.readString(CASES));
        List<Arguments> cases = new ArrayList<>();
        for (JsonNode item : root.get("cases")) {
            cases.add(Arguments.of(item.get("question").asString(), text(item, "kind"), text(item, "subject"),
                    text(item, "aspect"), text(item, "field")));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void classifiesTheSharedCase(String question, String kind, String subject, String aspect, String field) {
        MyHrOwnRecordIntent.Intent intent = MyHrOwnRecordIntent.classify(question);
        if (kind == null) {
            assertThat(intent).as(question).isNull();
            return;
        }
        assertThat(intent).as(question).isNotNull();
        assertThat(intent.kind().name()).as(question).isEqualTo(kind);
        assertThat(intent.subject().name()).as(question).isEqualTo(subject);
        assertThat(intent.aspect().name()).as(question).isEqualTo(aspect);
        assertThat(intent.field() == null ? null : intent.field().name()).as(question).isEqualTo(field);
    }

    @Test
    void theSharedTableIsPresentAndSubstantial() throws Exception {
        assertThat(cases().count()).isGreaterThanOrEqualTo(60);
    }

    @Test
    void politeAndAddressingPrefixesNormalizeToTheSameQuestion() {
        String plain = MyHrOwnRecordIntent.normalize("What is the status of my profile change request?");
        for (String polite : List.of("Can you please tell me what is the status of my profile change request?",
                "Hey Wavi, what is the status of my profile change request?",
                "Please tell me what is the status of my profile change request",
                "Could you check: what is the status of my profile change request?".replace(":", ""),
                "What’s the status of my profile change request?".replace("What’s", "What is"))) {
            assertThat(MyHrOwnRecordIntent.normalize(polite)).as(polite).isEqualTo(plain);
        }
    }

    private static String text(JsonNode item, String name) {
        JsonNode value = item.get(name);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static Path locate(String relative) {
        // Maven runs tests from backend/; an IDE may run them from the repository root.
        for (Path candidate : List.of(Path.of("..").resolve(relative), Path.of(relative))) {
            if (Files.exists(candidate)) return candidate;
        }
        throw new IllegalStateException("Shared Wavi intent cases not found: " + relative);
    }
}
