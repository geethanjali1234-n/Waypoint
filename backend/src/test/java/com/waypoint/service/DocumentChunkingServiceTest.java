package com.waypoint.service;

import com.waypoint.service.DocumentChunkingService.TextChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;

import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Synthetic text only. Most inputs are built from numbered words ("W00042"), so every chunk can be
 * mapped back to exact source positions to prove ordering, coverage, and overlap.
 */
class DocumentChunkingServiceTest {

    private static final Pattern WORD_ID = Pattern.compile("W(\\d{5})");

    // --- Empty and short input ------------------------------------------------------------------

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "\n\n\t ", "\r\n\r\n", "\f ​﻿"})
    void blankInputProducesNoChunks(String text) {
        assertThat(new DocumentChunkingService(1500, 200).chunk(text)).isEmpty();
    }

    @Test
    void shortDocumentIsASingleChunkAtIndexZero() {
        List<TextChunk> chunks = new DocumentChunkingService(1500, 200)
                .chunk("Leave Policy\n\nEmployees receive fifteen days of paid leave.");

        assertThat(chunks).containsExactly(
                new TextChunk(0, "Leave Policy\n\nEmployees receive fifteen days of paid leave."));
    }

    @Test
    void normalizesLineEndingsPageBreaksSpacingAndInvisibleCharacters() {
        String raw = "  Title\r\n\r\n\r\n  Line one   has extra   spaces.  \r\nLine\u0000 two​.\f"
                + "Next page\ttable\t\tcell Café menu­item.  ";

        List<TextChunk> chunks = new DocumentChunkingService(1500, 200).chunk(raw);

        assertThat(chunks).extracting(TextChunk::text).containsExactly(
                "Title\n\nLine one has extra spaces.\nLine two.\n\nNext page\ttable\tcell\n\nCafé menuitem.");
    }

    // --- Boundaries -----------------------------------------------------------------------------

    @Test
    void prefersParagraphBoundaries() {
        String text = numberedText(240, 8, 3, 0); // 10 paragraphs of 3 sentences
        Set<String> paragraphs = Set.of(text.split("\n\n"));

        List<TextChunk> chunks = new DocumentChunkingService(400, 0).chunk(text);

        assertThat(chunks).hasSizeGreaterThan(1);
        for (TextChunk chunk : chunks) {
            assertThat(paragraphs).as("chunk %d holds whole paragraphs", chunk.index())
                    .containsAll(List.of(chunk.text().split("\n\n")));
        }
    }

    @Test
    void longParagraphSplitsAtSentenceEndsAndOverlapStartsAtASentence() {
        int words = 320;
        List<TextChunk> chunks = new DocumentChunkingService(400, 80).chunk(numberedText(words, 8, 0, 0));

        assertContiguousCoverage(chunks, words, 400, 80);
        for (int i = 0; i < chunks.size(); i++) {
            if (i < chunks.size() - 1) {
                assertThat(lastId(chunks.get(i)) % 8).as("chunk %d ends at a sentence end", i).isEqualTo(7);
            }
            if (i > 0) {
                assertThat(firstId(chunks.get(i)) % 8).as("chunk %d starts at a sentence", i).isZero();
                assertThat(firstId(chunks.get(i))).as("chunk %d overlaps the previous chunk", i)
                        .isLessThanOrEqualTo(lastId(chunks.get(i - 1)));
            }
        }
    }

    @Test
    void pdfLineWrapsDoNotBecomeChunkBoundariesWhenSentenceEndsExist() {
        int words = 400;
        String pdfText = numberedText(words, 8, 0, 5); // hard line break after every fifth word

        List<TextChunk> chunks = new DocumentChunkingService(400, 80).chunk(pdfText);

        assertContiguousCoverage(chunks, words, 400, 80);
        for (TextChunk chunk : chunks.subList(0, chunks.size() - 1)) {
            assertThat(lastId(chunk) % 8).as("chunk %d ends at a sentence end", chunk.index()).isEqualTo(7);
        }
    }

    @Test
    void docxHeadingsStayWithTheParagraphThatFollows() {
        StringBuilder docx = new StringBuilder();
        for (int section = 1; section <= 12; section++) {
            docx.append("Section ").append(section).append(" Leave Rules\n")
                    .append("Employees in group ").append(section).append(" may request leave in advance. ")
                    .append("Managers approve requests within five business days.\n");
        }

        List<TextChunk> chunks = new DocumentChunkingService(300, 60).chunk(docx.toString());

        assertThat(chunks).hasSizeGreaterThan(1);
        for (TextChunk chunk : chunks.subList(0, chunks.size() - 1)) {
            String[] lines = chunk.text().split("\n");
            assertThat(lines[lines.length - 1]).as("chunk %d must not end on a heading", chunk.index())
                    .doesNotStartWith("Section ")
                    .endsWith(".");
        }
    }

    @Test
    void tableRowsAreNeverSplitAcrossChunks() {
        List<String> rows = IntStream.rangeClosed(1, 40)
                .mapToObj(i -> String.format("Row %03d\tEmployee E%03d\tAnnual leave\t%d days", i, i, i % 20))
                .toList();

        List<TextChunk> chunks = new DocumentChunkingService(300, 60).chunk(String.join("\n", rows));

        assertThat(chunks).hasSizeGreaterThan(1);
        for (TextChunk chunk : chunks) {
            assertThat(rows).as("chunk %d holds whole rows", chunk.index())
                    .containsAll(List.of(chunk.text().split("\n")));
        }
    }

    @Test
    void oversizedSentenceSplitsBetweenWords() {
        int words = 300; // one ~2,100-character sentence
        List<TextChunk> chunks = new DocumentChunkingService(400, 50).chunk(numberedText(words, words, 0, 0));

        assertContiguousCoverage(chunks, words, 400, 50);
        for (TextChunk chunk : chunks) {
            assertThat(chunk.text().split(" ")).as("chunk %d contains only whole words", chunk.index())
                    .allMatch(token -> token.matches("W\\d{5}\\.?"));
        }
    }

    // --- Unicode --------------------------------------------------------------------------------

    @Test
    void unbreakableTokenIsHardCutOnlyAtGraphemeBoundaries() {
        // Grapheme clusters: family ZWJ emoji (8 chars), US flag (4), q + combining dot (2), "x" (1).
        String unit = "👨‍👩‍👧" + "🇺🇸" + "q̇" + "x";
        Set<Integer> clusterStartsInUnit = Set.of(0, 8, 12, 14);
        String token = unit.repeat(200);

        List<TextChunk> chunks = new DocumentChunkingService(400, 100).chunk(token);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(String.join("", chunks.stream().map(TextChunk::text).toList())).isEqualTo(token);
        int offset = 0;
        for (TextChunk chunk : chunks) {
            assertThat(chunk.text().length()).isLessThanOrEqualTo(400);
            assertThat(clusterStartsInUnit).as("chunk %d starts on a grapheme boundary", chunk.index())
                    .contains(offset % unit.length());
            offset += chunk.text().length();
        }
    }

    @Test
    void multilingualAndEmojiWordsAreNeverSplit() {
        List<String> vocabulary = List.of("naïve", "café", "👩‍💻",
                "日本語", "Zoë", "🇨🇦", "résumé.");
        String text = String.join(" ", IntStream.range(0, 400).mapToObj(i -> vocabulary.get(i % vocabulary.size())).toList());

        List<TextChunk> chunks = new DocumentChunkingService(300, 60).chunk(text);

        assertThat(chunks).hasSizeGreaterThan(1);
        for (TextChunk chunk : chunks) {
            assertThat(vocabulary).as("chunk %d contains only whole words", chunk.index())
                    .containsAll(List.of(chunk.text().split(" ")));
        }
    }

    // --- Coverage, overlap, and tail handling ---------------------------------------------------

    @Test
    void zeroOverlapReproducesTheSourceWordSequenceExactly() {
        String text = numberedText(2_000, 11, 5, 7);

        List<TextChunk> chunks = new DocumentChunkingService(500, 0).chunk(text);

        List<String> rejoined = chunks.stream().flatMap(chunk -> Arrays.stream(chunk.text().split("\\s+"))).toList();
        assertThat(rejoined).containsExactly(text.split("\\s+"));
    }

    @ParameterizedTest
    @ValueSource(ints = {58, 59, 60, 62, 64, 68, 75, 116, 117, 118, 125})
    void finalChunkIsNeverMostlyRepeatedText(int words) {
        List<TextChunk> chunks = new DocumentChunkingService(400, 100).chunk(numberedText(words, 8, 0, 0));

        assertContiguousCoverage(chunks, words, 400, 100);
        if (chunks.size() > 1) {
            String finalText = chunks.get(chunks.size() - 1).text();
            int firstNewWord = lastId(chunks.get(chunks.size() - 2)) + 1;
            String newText = finalText.substring(finalText.indexOf(word(firstNewWord)));
            assertThat(newText.length()).as("new text in the final chunk").isGreaterThanOrEqualTo(400 / 5);
        }
    }

    @Test
    @Timeout(10)
    void longDocumentPreservesEveryWordInOrderWithinSizeLimits() {
        int words = 28_000; // ~200,000 characters with sentences, paragraphs, and PDF-style wraps
        List<TextChunk> chunks = new DocumentChunkingService(1500, 200).chunk(numberedText(words, 12, 4, 9));

        assertThat(chunks.size()).isGreaterThan(100);
        assertContiguousCoverage(chunks, words, 1500, 200);
    }

    @Test
    void assignsSequentialIndexesInSourceOrder() {
        List<TextChunk> chunks = new DocumentChunkingService(400, 80).chunk(numberedText(600, 8, 3, 0));

        assertThat(chunks).extracting(TextChunk::index)
                .containsExactlyElementsOf(IntStream.range(0, chunks.size()).boxed().toList());
        assertThat(chunks).extracting(DocumentChunkingServiceTest::firstId).isSorted().doesNotHaveDuplicates();
    }

    @Test
    void chunkingIsDeterministic() {
        String text = numberedText(3_000, 9, 4, 6);
        DocumentChunkingService service = new DocumentChunkingService(1500, 200);

        List<TextChunk> first = service.chunk(text);

        assertThat(service.chunk(text)).isEqualTo(first);
        assertThat(new DocumentChunkingService(1500, 200).chunk(text)).isEqualTo(first);
    }

    static Stream<String> pathologicalTexts() {
        String giant = "Z".repeat(950);
        return Stream.of(
                "W " + giant + " tail words here. " + giant,
                (giant + " ").repeat(5),
                "a\n\nb\n\nc ".repeat(300),
                "x ".repeat(2_000),
                ". ".repeat(1_500),
                "word\tcell\n".repeat(400),
                "😀".repeat(1_000));
    }

    @ParameterizedTest
    @MethodSource("pathologicalTexts")
    @Timeout(5)
    void pathologicalInputsTerminateWithBoundedChunksAndNoLostText(String text) {
        for (int overlap : new int[]{0, 50}) {
            List<TextChunk> chunks = new DocumentChunkingService(200, overlap).chunk(text);

            assertThat(chunks).isNotEmpty();
            assertThat(chunks.size()).as("every chunk adds new text").isLessThanOrEqualTo(text.length() / 50 + 2);
            for (int i = 0; i < chunks.size(); i++) {
                assertThat(chunks.get(i).index()).isEqualTo(i);
                assertThat(chunks.get(i).text()).isNotBlank().hasSizeLessThanOrEqualTo(200);
                assertThat(Character.isLowSurrogate(chunks.get(i).text().charAt(0))).isFalse();
            }
            if (overlap == 0) {
                String rejoined = String.join("", chunks.stream().map(TextChunk::text).toList());
                assertThat(rejoined.replaceAll("\\s+", "")).isEqualTo(text.replaceAll("\\s+", ""));
            }
        }
    }

    // --- Configuration --------------------------------------------------------------------------

    @Test
    void defaultsAreFifteenHundredCharactersWithTwoHundredOverlap() throws Exception {
        Parameter[] parameters = DocumentChunkingService.class.getConstructor(int.class, int.class).getParameters();

        assertThat(parameters[0].getAnnotation(Value.class).value())
                .isEqualTo("${waypoint.myhr.rag.chunking.max-chunk-chars:1500}");
        assertThat(parameters[1].getAnnotation(Value.class).value())
                .isEqualTo("${waypoint.myhr.rag.chunking.overlap-chars:200}");
    }

    @ParameterizedTest
    @CsvSource({"199, 0", "8001, 0", "1500, -1", "1500, 376", "200, 51"})
    void rejectsInvalidConfiguration(int maxChunkChars, int overlapChars) {
        assertThatThrownBy(() -> new DocumentChunkingService(maxChunkChars, overlapChars))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({"200, 0", "200, 50", "1500, 375", "8000, 2000"})
    void acceptsConfigurationAtTheLimits(int maxChunkChars, int overlapChars) {
        assertThatCode(() -> new DocumentChunkingService(maxChunkChars, overlapChars)).doesNotThrowAnyException();
    }

    // --- Helpers --------------------------------------------------------------------------------

    /**
     * Numbered words grouped into sentences ("W00000 ... W00007.") and optionally paragraphs
     * (blank lines) and PDF-style hard line wraps every {@code wrapEvery} words.
     */
    private static String numberedText(int words, int wordsPerSentence, int sentencesPerParagraph, int wrapEvery) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < words; i++) {
            if (i > 0) {
                boolean sentenceStart = i % wordsPerSentence == 0;
                boolean paragraphStart = sentenceStart && sentencesPerParagraph > 0
                        && (i / wordsPerSentence) % sentencesPerParagraph == 0;
                boolean wrap = wrapEvery > 0 && i % wrapEvery == 0;
                text.append(paragraphStart ? "\n\n" : wrap ? "\n" : " ");
            }
            text.append(word(i));
            if ((i + 1) % wordsPerSentence == 0 || i == words - 1) {
                text.append('.');
            }
        }
        return text.toString();
    }

    private static String word(int id) {
        return String.format("W%05d", id);
    }

    private static List<Integer> ids(TextChunk chunk) {
        List<Integer> ids = new ArrayList<>();
        Matcher matcher = WORD_ID.matcher(chunk.text());
        while (matcher.find()) {
            ids.add(Integer.parseInt(matcher.group(1)));
        }
        return ids;
    }

    private static int firstId(TextChunk chunk) {
        return ids(chunk).get(0);
    }

    private static int lastId(TextChunk chunk) {
        List<Integer> ids = ids(chunk);
        return ids.get(ids.size() - 1);
    }

    /**
     * Proves chunks are ordered, sized, gap-free, and each adds new words: every chunk is a
     * consecutive run of words that starts no later than one past the previous chunk's last word
     * and ends after it, with any repeated prefix no longer than the configured overlap.
     */
    private static void assertContiguousCoverage(List<TextChunk> chunks, int totalWords, int maxChars, int overlapChars) {
        assertThat(chunks).isNotEmpty();
        assertThat(firstId(chunks.get(0))).isZero();
        int previousLast = -1;
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            assertThat(chunk.index()).isEqualTo(i);
            assertThat(chunk.text()).isNotBlank().hasSizeLessThanOrEqualTo(maxChars);
            List<Integer> ids = ids(chunk);
            for (int k = 1; k < ids.size(); k++) {
                assertThat(ids.get(k)).as("chunk %d is a consecutive run of words", i).isEqualTo(ids.get(k - 1) + 1);
            }
            int first = ids.get(0);
            int last = ids.get(ids.size() - 1);
            assertThat(first).as("no gap before chunk %d", i).isLessThanOrEqualTo(previousLast + 1);
            assertThat(last).as("chunk %d adds new words", i).isGreaterThan(previousLast);
            if (first <= previousLast) {
                String repeated = chunk.text().substring(0,
                        chunk.text().indexOf(word(previousLast)) + word(previousLast).length());
                assertThat(repeated.length()).as("overlap of chunk %d", i).isLessThanOrEqualTo(overlapChars);
            }
            previousLast = last;
        }
        assertThat(previousLast).as("last word is preserved").isEqualTo(totalWords - 1);
    }
}
