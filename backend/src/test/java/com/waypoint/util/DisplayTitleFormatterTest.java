package com.waypoint.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DisplayTitleFormatterTest {

    @Test
    void format_capitalizesEveryWhitespaceSeparatedWord() {
        assertThat(DisplayTitleFormatter.format("career fair workshop"))
                .isEqualTo("Career Fair Workshop");
    }

    @Test
    void format_capitalizesHyphenatedAndSlashSeparatedWords() {
        assertThat(DisplayTitleFormatter.format("full-stack hiring/event"))
                .isEqualTo("Full-Stack Hiring/Event");
    }

    @Test
    void format_preservesExistingAcronymCasing() {
        assertThat(DisplayTitleFormatter.format("QA engineer meetup"))
                .isEqualTo("QA Engineer Meetup");
    }

    @Test
    void format_handlesNullAndWhitespace() {
        assertThat(DisplayTitleFormatter.format(null)).isNull();
        assertThat(DisplayTitleFormatter.format("   ")).isEmpty();
    }
}
