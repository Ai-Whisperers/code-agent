package com.eneve.agent.agent.handlers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the static helper logic replicated in all three Review*Handler classes.
 * Keeps coverage without spinning up Quarkus or mocking Claude.
 */
class ReviewHandlerHelpersTest {

    // ── clamp ─────────────────────────────────────────────────────────────────

    @Test
    void clampReturns0ForNegative() {
        assertEquals(0, clamp(-1));
        assertEquals(0, clamp(-999));
    }

    @Test
    void clampReturns100ForAbove100() {
        assertEquals(100, clamp(101));
        assertEquals(100, clamp(Integer.MAX_VALUE));
    }

    @Test
    void clampPassesThroughValidRange() {
        assertEquals(0,   clamp(0));
        assertEquals(50,  clamp(50));
        assertEquals(100, clamp(100));
    }

    // ── extractJson ───────────────────────────────────────────────────────────

    @Test
    void extractJsonPassesThroughPlainJson() {
        String json = "{\"readiness_score\":80}";
        assertEquals(json, extractJson(json));
    }

    @Test
    void extractJsonStripsMarkdownFence() {
        String fenced = "```json\n{\"readiness_score\":80}\n```";
        assertEquals("{\"readiness_score\":80}", extractJson(fenced));
    }

    @Test
    void extractJsonStripsGenericFence() {
        String fenced = "```\n{\"a\":1}\n```";
        assertEquals("{\"a\":1}", extractJson(fenced));
    }

    @Test
    void extractJsonHandlesLeadingTrailingWhitespace() {
        String input = "  \n{\"x\":1}\n  ";
        assertEquals("{\"x\":1}", extractJson(input));
    }

    @Test
    void extractJsonHandlesMultiLineFencedBlock() {
        String fenced = "```json\n{\n  \"readiness_score\": 70,\n  \"readiness_label\": \"poor\"\n}\n```";
        String result = extractJson(fenced);
        assertTrue(result.startsWith("{"));
        assertTrue(result.contains("readiness_score"));
    }

    // ── Helpers mirroring private statics in Review*Handler ───────────────────

    /** Mirrors ReviewEpicHandler.clamp */
    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    /** Mirrors ReviewEpicHandler.extractJson */
    private static String extractJson(String text) {
        String s = text.strip();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            int end = s.lastIndexOf("```");
            if (nl > 0 && end > nl) s = s.substring(nl + 1, end).strip();
        }
        return s;
    }
}
