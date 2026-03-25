package com.eneve.agent.agent.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link KnowledgeIndexerService#splitIntoChunks(String, int)}.
 *
 * The method is package-visible ({@code static}), so this test lives in the
 * same package.  No CDI container or database is required.
 */
class KnowledgeIndexerServiceChunksTest {

    // ── Single-chunk cases ─────────────────────────────────────────────────────

    @Test
    void shortTextReturnsSingleChunk() {
        List<String> chunks = KnowledgeIndexerService.splitIntoChunks("Hello world.", 2000);
        assertEquals(1, chunks.size());
        assertEquals("Hello world.", chunks.get(0));
    }

    @Test
    void textAtExactLimitReturnsSingleChunk() {
        String text = "A".repeat(2000);
        List<String> chunks = KnowledgeIndexerService.splitIntoChunks(text, 2000);
        assertEquals(1, chunks.size());
        assertEquals(text, chunks.get(0));
    }

    @Test
    void emptyStringReturnsSingleEmptyChunk() {
        List<String> chunks = KnowledgeIndexerService.splitIntoChunks("", 2000);
        assertEquals(1, chunks.size());
        assertEquals("", chunks.get(0));
    }

    // ── Paragraph-boundary splitting ──────────────────────────────────────────

    @Test
    void twoParagraphsThatExceedLimitAreEachInOwnChunk() {
        String p1 = "A".repeat(1200);
        String p2 = "B".repeat(1200);
        String text = p1 + "\n\n" + p2;

        List<String> chunks = KnowledgeIndexerService.splitIntoChunks(text, 2000);

        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).contains("A"));
        assertTrue(chunks.get(1).contains("B"));
    }

    @Test
    void severalSmallParagraphsAreBatchedIntoOneChunk() {
        // Four paragraphs of 100 chars each → should all fit in one 2000-char chunk
        String paragraph = "X".repeat(100);
        String text = paragraph + "\n\n" + paragraph + "\n\n" + paragraph + "\n\n" + paragraph;

        List<String> chunks = KnowledgeIndexerService.splitIntoChunks(text, 2000);

        assertEquals(1, chunks.size());
    }

    @Test
    void multipleBlankLinesAreTreatedAsParagraphSeparator() {
        String p1 = "First paragraph content here.";
        String p2 = "Second paragraph content here.";
        String text = p1 + "\n\n\n\n" + p2; // four blank lines still split on \n\n+

        List<String> chunks = KnowledgeIndexerService.splitIntoChunks(text, 20);

        // Each paragraph exceeds the 20-char limit so hard-split applies, but
        // we confirm both paragraphs' content appears across the chunks.
        String all = String.join(" ", chunks);
        assertTrue(all.contains("First paragraph"));
        assertTrue(all.contains("Second paragraph"));
    }

    @Test
    void paragraphsThatJustFitAreNotSplit() {
        // p1 is 800 chars; p2 is 800 chars; together (800 + 800 + 2) = 1602 chars < 2000 limit
        String p1 = "A".repeat(800);
        String p2 = "B".repeat(800);
        String text = p1 + "\n\n" + p2;

        List<String> chunks = KnowledgeIndexerService.splitIntoChunks(text, 2000);

        assertEquals(1, chunks.size(), "paragraphs fitting within limit should be merged");
    }

    // ── Hard-split (oversized single paragraph) ───────────────────────────────

    @Test
    void oversizedSingleParagraphIsHardSplitAtLimit() {
        String big = "Z".repeat(5000);

        List<String> chunks = KnowledgeIndexerService.splitIntoChunks(big, 2000);

        assertEquals(3, chunks.size());            // 2000 + 2000 + 1000
        assertEquals(2000, chunks.get(0).length());
        assertEquals(2000, chunks.get(1).length());
        assertEquals(1000, chunks.get(2).length());
    }

    @Test
    void hardSplitChunksContainCorrectContent() {
        String part1 = "A".repeat(2000);
        String part2 = "B".repeat(1500);
        String text = part1 + part2; // 3500 chars, no paragraph breaks

        List<String> chunks = KnowledgeIndexerService.splitIntoChunks(text, 2000);

        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).chars().allMatch(c -> c == 'A'),
                "first chunk must contain only 'A' characters");
        // second chunk is 1500 'B's
        assertTrue(chunks.get(1).chars().allMatch(c -> c == 'B'),
                "second chunk must contain only 'B' characters");
    }

    // ── Invariants ────────────────────────────────────────────────────────────

    @Test
    void allChunksAreWithinLimit() {
        // Deliberately mix small and large paragraphs
        String text =
                "Short.\n\n" +
                "X".repeat(500) + "\n\n" +
                "Y".repeat(3000) + "\n\n" +
                "Z".repeat(800);

        int limit = 2000;
        List<String> chunks = KnowledgeIndexerService.splitIntoChunks(text, limit);

        for (String chunk : chunks) {
            assertTrue(chunk.length() <= limit,
                    "chunk length " + chunk.length() + " exceeds limit " + limit);
        }
    }

    @Test
    void noChunkIsBlankWhenInputIsNonBlank() {
        String text = "Para one.\n\nPara two.\n\nPara three.";
        List<String> chunks = KnowledgeIndexerService.splitIntoChunks(text, 12);

        assertTrue(chunks.stream().noneMatch(String::isBlank),
                "no chunk should be blank when input contains content");
    }

    @Test
    void chunkContentCoversTotalInput() {
        String p1 = "Alpha";
        String p2 = "Beta";
        String p3 = "Gamma";
        String text = p1 + "\n\n" + p2 + "\n\n" + p3;

        List<String> chunks = KnowledgeIndexerService.splitIntoChunks(text, 7);
        String joined = String.join("", chunks);

        // Every word from the original text must appear somewhere in the joined output
        assertTrue(joined.contains(p1));
        assertTrue(joined.contains(p2));
        assertTrue(joined.contains(p3));
    }
}
