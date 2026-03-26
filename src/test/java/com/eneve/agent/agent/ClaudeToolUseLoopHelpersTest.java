package com.eneve.agent.agent;

import org.junit.jupiter.api.Test;

import static com.eneve.agent.agent.ClaudeToolUseLoop.MAX_TOOL_RESULT_CHARS;
import static com.eneve.agent.agent.ClaudeToolUseLoop.truncateResult;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@link ClaudeToolUseLoop#truncateResult} helper.
 *
 * Verifies that results short enough to fit in the history window are returned
 * unchanged, and that oversized results are capped at MAX_TOOL_RESULT_CHARS with
 * a clear notice appended.
 */
class ClaudeToolUseLoopHelpersTest {

    @Test
    void nullResult_returnedAsNull() {
        assertNull(truncateResult(null));
    }

    @Test
    void emptyResult_returnedUnchanged() {
        assertEquals("", truncateResult(""));
    }

    @Test
    void shortResult_returnedUnchanged() {
        String result = "some short tool output";
        assertSame(result, truncateResult(result));
    }

    @Test
    void exactlyAtLimit_returnedUnchanged() {
        String result = "x".repeat(MAX_TOOL_RESULT_CHARS);
        assertEquals(result, truncateResult(result));
        assertEquals(MAX_TOOL_RESULT_CHARS, truncateResult(result).length());
    }

    @Test
    void oneCharOverLimit_isTruncated() {
        String result = "x".repeat(MAX_TOOL_RESULT_CHARS + 1);
        String truncated = truncateResult(result);

        assertTrue(truncated.startsWith("x".repeat(MAX_TOOL_RESULT_CHARS)),
                "First MAX_TOOL_RESULT_CHARS chars must be preserved");
        assertTrue(truncated.contains("[truncated"),
                "Truncation notice must be appended");
    }

    @Test
    void truncationNoticeContainsExactCharCount() {
        int overflow = 500;
        String result = "x".repeat(MAX_TOOL_RESULT_CHARS + overflow);
        String truncated = truncateResult(result);

        assertTrue(truncated.contains("truncated " + overflow + " chars"),
                "Truncation notice must state the exact number of dropped chars");
    }

    @Test
    void truncatedResultStartsWithOriginalPrefix() {
        String prefix = "ERROR: something went wrong — ";
        String result = prefix + "y".repeat(MAX_TOOL_RESULT_CHARS);
        String truncated = truncateResult(result);

        assertTrue(truncated.startsWith(prefix),
                "Truncated result must preserve the original prefix");
    }

    @Test
    void largeResult_neverExceedsLimitPlusTruncationMessage() {
        String result = "a".repeat(MAX_TOOL_RESULT_CHARS * 3);
        String truncated = truncateResult(result);

        // MAX_TOOL_RESULT_CHARS content + a short truncation line (< 100 chars)
        assertTrue(truncated.length() < MAX_TOOL_RESULT_CHARS + 100,
                "Output length should be close to the limit, not grow unboundedly");
    }
}
