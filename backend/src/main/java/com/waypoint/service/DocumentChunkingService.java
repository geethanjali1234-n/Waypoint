package com.waypoint.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.text.BreakIterator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Splits extracted document text into ordered, overlapping chunks for the MyHR RAG pipeline.
 *
 * <p>Text in, text out: document metadata and access control are attached by the caller when it
 * maps chunks to KnowledgeChunk, never here. Output is deterministic for a given input and
 * configuration, so an unchanged document produces identical chunks when it is re-indexed.
 *
 * <p>Split points are ranked paragraph &gt; sentence &gt; line &gt; word, with a grapheme-safe hard
 * cut only when a single token is longer than the window. Single newlines rank below sentence ends
 * because extracted PDF text wraps lines mid-sentence; DOCX paragraphs and table rows also end in
 * single newlines, so they still beat plain word boundaries.
 */
@Service
public class DocumentChunkingService {

    static final int MIN_MAX_CHUNK_CHARS = 200;
    static final int MAX_MAX_CHUNK_CHARS = 8000;

    private static final int WORD = 1;
    private static final int LINE = 2;
    private static final int SENTENCE = 3;
    private static final int PARAGRAPH = 4;

    private static final String SENTENCE_TERMINATORS = ".!?…。！？";
    private static final String CLOSING_PUNCTUATION = ")]}\"'”’»";

    private static final Pattern LINE_BREAKS = Pattern.compile("\\r\\n|[\\r\\u000B\\u0085\\u2028]");
    private static final Pattern PAGE_BREAKS = Pattern.compile("[\\f\\u2029]");
    // Control characters other than newline/tab, plus soft hyphens, zero-width spaces, and BOMs.
    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cc}&&[^\\n\\t]]|[\\u00AD\\u200B\\uFEFF]");
    private static final Pattern SPACES = Pattern.compile("\\p{Zs}+");
    private static final Pattern TAB_RUNS = Pattern.compile("[ \\t]*\\t[ \\t]*");
    private static final Pattern SPACE_AROUND_NEWLINES = Pattern.compile("[ \\t]*\\n[ \\t]*");
    private static final Pattern EXTRA_BLANK_LINES = Pattern.compile("\\n{3,}");

    private final int maxChunkChars;
    private final int overlapChars;
    // A final chunk must contribute at least this much new text; otherwise the previous split moves
    // earlier. With overlap <= max/4 this keeps the rebalanced final chunk under maxChunkChars.
    private final int minFinalNewChars;

    public DocumentChunkingService(
            @Value("${waypoint.myhr.rag.chunking.max-chunk-chars:1500}") int maxChunkChars,
            @Value("${waypoint.myhr.rag.chunking.overlap-chars:200}") int overlapChars) {
        if (maxChunkChars < MIN_MAX_CHUNK_CHARS || maxChunkChars > MAX_MAX_CHUNK_CHARS) {
            throw new IllegalArgumentException("Max chunk size must be between "
                    + MIN_MAX_CHUNK_CHARS + " and " + MAX_MAX_CHUNK_CHARS + " characters");
        }
        if (overlapChars < 0 || overlapChars > maxChunkChars / 4) {
            throw new IllegalArgumentException("Chunk overlap must be between 0 and a quarter of the max chunk size");
        }
        this.maxChunkChars = maxChunkChars;
        this.overlapChars = overlapChars;
        this.minFinalNewChars = maxChunkChars / 5;
    }

    /** Returns chunks in document order with indexes 0..n-1; blank or null input yields no chunks. */
    public List<TextChunk> chunk(String text) {
        if (text == null) {
            return List.of();
        }
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return List.of();
        }

        List<TextChunk> chunks = new ArrayList<>();
        int length = normalized.length();
        int start = 0;
        int previousEnd = 0;
        while (length - start > maxChunkChars) {
            int end = chooseEnd(normalized, start, previousEnd);
            chunks.add(new TextChunk(chunks.size(), normalized.substring(start, end).strip()));
            int nextStart = chooseNextStart(normalized, start, end);
            if (nextStart <= start || end <= previousEnd) {
                throw new IllegalStateException("Chunking made no progress");
            }
            previousEnd = end;
            start = nextStart;
        }
        chunks.add(new TextChunk(chunks.size(), normalized.substring(start)));
        return chunks;
    }

    private int chooseEnd(String text, int start, int previousEnd) {
        int limit = start + maxChunkChars;
        int floor = start + maxChunkChars / 2;
        int end = bestSplit(text, floor, limit, text.length(), WORD);
        if (end < 0) {
            // One token fills the back half of the window: end before it while words stay intact.
            end = bestSplit(text, Math.max(start, previousEnd) + 1, floor - 1, text.length(), WORD);
        }
        if (end < 0) {
            return hardCut(text, Math.max(start, previousEnd), limit);
        }
        if (text.length() - contentStart(text, end) < minFinalNewChars) {
            // Splitting here would leave a final chunk that is mostly overlap, so split earlier,
            // but never trade a sentence-or-better boundary for a weaker one.
            int minStrength = Math.min(boundaryStrength(text, end), SENTENCE);
            int balanced = bestSplit(text, floor, limit, text.length() - minFinalNewChars, minStrength);
            if (balanced >= 0) {
                end = balanced;
            }
        }
        return end;
    }

    /**
     * Strongest split in [from, to] of at least minStrength whose following content starts no later
     * than maxContentStart, preferring the latest on ties; -1 when there is none.
     */
    private int bestSplit(String text, int from, int to, int maxContentStart, int minStrength) {
        int best = -1;
        int bestStrength = minStrength - 1;
        for (int i = to; i >= from; i--) {
            if (!isSplit(text, i) || contentStart(text, i) > maxContentStart) {
                continue;
            }
            int strength = boundaryStrength(text, i);
            if (strength > bestStrength) {
                best = i;
                bestStrength = strength;
                if (strength == PARAGRAPH) {
                    break;
                }
            }
        }
        return best;
    }

    /** Last grapheme-cluster boundary at or before limit that still moves past minimumEnd. */
    private static int hardCut(String text, int minimumEnd, int limit) {
        BreakIterator graphemes = BreakIterator.getCharacterInstance(Locale.ROOT);
        graphemes.setText(text);
        int cut = graphemes.isBoundary(limit) ? limit : graphemes.preceding(limit);
        if (cut == BreakIterator.DONE || cut <= minimumEnd) {
            // A single grapheme cluster longer than the window: settle for a code point boundary.
            boolean splitsSurrogatePair = Character.isHighSurrogate(text.charAt(limit - 1))
                    && Character.isLowSurrogate(text.charAt(limit));
            cut = splitsSurrogatePair ? limit - 1 : limit;
        }
        return cut;
    }

    /**
     * Where the next chunk begins: the strongest token start within the last overlapChars of the
     * previous chunk (earliest on ties), or the first token after it when there is none.
     */
    private int chooseNextStart(String text, int start, int end) {
        int best = -1;
        int bestStrength = 0;
        for (int i = Math.max(start + 1, end - overlapChars); i < end; i++) {
            if (isTokenStart(text, i)) {
                int strength = boundaryStrength(text, runStart(text, i));
                if (strength > bestStrength) {
                    best = i;
                    bestStrength = strength;
                }
            }
        }
        return best >= 0 ? best : contentStart(text, end);
    }

    /** Strength of the whitespace run that begins at splitIndex and follows a token. */
    private static int boundaryStrength(String text, int splitIndex) {
        int newlines = 0;
        int next = splitIndex;
        while (next < text.length() && isSpace(text.charAt(next))) {
            if (text.charAt(next) == '\n') {
                newlines++;
            }
            next++;
        }
        if (newlines >= 2) {
            return PARAGRAPH;
        }
        // "e.g. the" is not a sentence end; a following lowercase word means the sentence continues.
        if (endsSentence(text, splitIndex)
                && (next == text.length() || !Character.isLowerCase(text.codePointAt(next)))) {
            return SENTENCE;
        }
        return newlines == 1 ? LINE : WORD;
    }

    private static boolean endsSentence(String text, int splitIndex) {
        int index = splitIndex - 1;
        while (index > 0 && splitIndex - index <= 3 && CLOSING_PUNCTUATION.indexOf(text.charAt(index)) >= 0) {
            index--;
        }
        return SENTENCE_TERMINATORS.indexOf(text.charAt(index)) >= 0;
    }

    private static boolean isSplit(String text, int index) {
        return index > 0 && index < text.length()
                && isSpace(text.charAt(index)) && !isSpace(text.charAt(index - 1));
    }

    private static boolean isTokenStart(String text, int index) {
        return index > 0 && !isSpace(text.charAt(index)) && isSpace(text.charAt(index - 1));
    }

    private static int runStart(String text, int tokenStart) {
        int index = tokenStart;
        while (index > 0 && isSpace(text.charAt(index - 1))) {
            index--;
        }
        return index;
    }

    private static int contentStart(String text, int index) {
        while (index < text.length() && isSpace(text.charAt(index))) {
            index++;
        }
        return index;
    }

    // After normalization these are the only whitespace characters left.
    private static boolean isSpace(char c) {
        return c == ' ' || c == '\n' || c == '\t';
    }

    private static String normalize(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFC);
        normalized = LINE_BREAKS.matcher(normalized).replaceAll("\n");
        normalized = PAGE_BREAKS.matcher(normalized).replaceAll("\n\n");
        normalized = INVISIBLE.matcher(normalized).replaceAll("");
        normalized = SPACES.matcher(normalized).replaceAll(" ");
        normalized = TAB_RUNS.matcher(normalized).replaceAll("\t");
        normalized = SPACE_AROUND_NEWLINES.matcher(normalized).replaceAll("\n");
        normalized = EXTRA_BLANK_LINES.matcher(normalized).replaceAll("\n\n");
        return normalized.strip();
    }

    /** One chunk of normalized document text; index is its 0-based position in the document. */
    public record TextChunk(int index, String text) {
    }
}
