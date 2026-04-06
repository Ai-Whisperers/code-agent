package com.eneve.agent.agent;

import com.eneve.agent.agent.model.AiCallRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class AiCallRecordTest {

    @Test
    void constructorCreatesCorrectRecord() {
        Long id = 1L;
        String jobId = "job-123";
        String jobType = "REVIEW";
        String model = "claude-3-sonnet-20240229";
        Integer iteration = 1;
        long inputTokens = 1000L;
        long outputTokens = 500L;
        long cacheCreationInputTokens = 100L;
        long cacheReadInputTokens = 50L;
        String stopReason = "end_turn";
        String toolNames = "read_file,write_file";
        long durationMs = 2500L;
        boolean isError = false;
        String errorMessage = null;
        Instant createdAt = Instant.now();

        AiCallRecord record = new AiCallRecord(id, jobId, jobType, model, iteration,
                inputTokens, outputTokens, cacheCreationInputTokens, cacheReadInputTokens,
                stopReason, toolNames, durationMs, isError, errorMessage, createdAt,
                null, null, null, 0);

        assertEquals(id, record.id());
        assertEquals(jobId, record.jobId());
        assertEquals(jobType, record.jobType());
        assertEquals(model, record.model());
        assertEquals(iteration, record.iteration());
        assertEquals(inputTokens, record.inputTokens());
        assertEquals(outputTokens, record.outputTokens());
        assertEquals(cacheCreationInputTokens, record.cacheCreationInputTokens());
        assertEquals(cacheReadInputTokens, record.cacheReadInputTokens());
        assertEquals(stopReason, record.stopReason());
        assertEquals(toolNames, record.toolNames());
        assertEquals(durationMs, record.durationMs());
        assertEquals(isError, record.isError());
        assertEquals(errorMessage, record.errorMessage());
        assertEquals(createdAt, record.createdAt());
    }

    @Test
    void constructorWithNullValues() {
        AiCallRecord record = new AiCallRecord(null, null, null, null, null,
                0L, 0L, 0L, 0L, null, null, 0L, false, null, null,
                null, null, null, 0);

        assertNull(record.id());
        assertNull(record.jobId());
        assertNull(record.jobType());
        assertNull(record.model());
        assertNull(record.iteration());
        assertEquals(0L, record.inputTokens());
        assertEquals(0L, record.outputTokens());
        assertEquals(0L, record.cacheCreationInputTokens());
        assertEquals(0L, record.cacheReadInputTokens());
        assertNull(record.stopReason());
        assertNull(record.toolNames());
        assertEquals(0L, record.durationMs());
        assertFalse(record.isError());
        assertNull(record.errorMessage());
        assertNull(record.createdAt());
    }

    @Test
    void constructorWithErrorDetails() {
        AiCallRecord record = new AiCallRecord(1L, "job-123", "FIX", "claude-3", 1,
                100L, 0L, 0L, 0L, "error", null, 1000L, true,
                "API rate limit exceeded", Instant.now(), null, null, null, 0);

        assertTrue(record.isError());
        assertEquals("API rate limit exceeded", record.errorMessage());
        assertEquals("error", record.stopReason());
        assertEquals(0L, record.outputTokens());
    }

    @Test
    void constructorWithCacheTokens() {
        AiCallRecord record = new AiCallRecord(1L, "job-123", "REVIEW", "claude-3", 2,
                1500L, 800L, 200L, 300L, "end_turn", "list_files", 3000L,
                false, null, Instant.now(), null, null, null, 0);

        assertEquals(200L, record.cacheCreationInputTokens());
        assertEquals(300L, record.cacheReadInputTokens());
        assertEquals(1500L, record.inputTokens());
        assertEquals(800L, record.outputTokens());
    }

    @Test
    void constructorWithMultipleTools() {
        AiCallRecord record = new AiCallRecord(1L, "job-123", "FIX_COMMENT", "claude-3", 3,
                2000L, 1200L, 0L, 0L, "tool_use", "read_file,write_file,run_command",
                5000L, false, null, Instant.now(), null, null, null, 0);

        assertEquals("read_file,write_file,run_command", record.toolNames());
        assertEquals("tool_use", record.stopReason());
    }

    @Test
    void constructorWithHighTokenCounts() {
        long highInputTokens = 100000L;
        long highOutputTokens = 50000L;

        AiCallRecord record = new AiCallRecord(1L, "job-123", "REVIEW", "claude-3", 1,
                highInputTokens, highOutputTokens, 0L, 0L, "max_tokens", null,
                10000L, false, null, Instant.now(), null, null, null, 0);

        assertEquals(highInputTokens, record.inputTokens());
        assertEquals(highOutputTokens, record.outputTokens());
    }

    @Test
    void constructorWithLongDuration() {
        long longDuration = 60000L; // 1 minute

        AiCallRecord record = new AiCallRecord(1L, "job-123", "FIX", "claude-3", 5,
                1000L, 500L, 0L, 0L, "end_turn", null, longDuration,
                false, null, Instant.now(), null, null, null, 0);

        assertEquals(longDuration, record.durationMs());
    }

    @Test
    void constructorWithZeroIteration() {
        AiCallRecord record = new AiCallRecord(1L, "job-123", "REPLY", "claude-3", 0,
                500L, 250L, 0L, 0L, "end_turn", null, 1500L,
                false, null, Instant.now(), null, null, null, 0);

        assertEquals(0, record.iteration());
    }

    @Test
    void constructorWithEmptyStrings() {
        AiCallRecord record = new AiCallRecord(1L, "", "", "", 1,
                100L, 50L, 0L, 0L, "", "", 1000L, false, "", Instant.now(),
                null, null, null, 0);

        assertEquals("", record.jobId());
        assertEquals("", record.jobType());
        assertEquals("", record.model());
        assertEquals("", record.stopReason());
        assertEquals("", record.toolNames());
        assertEquals("", record.errorMessage());
    }

    @Test
    void recordEquality() {
        Instant now = Instant.now();
        AiCallRecord record1 = new AiCallRecord(1L, "job-123", "REVIEW", "claude-3", 1,
                1000L, 500L, 100L, 50L, "end_turn", "read_file", 2500L,
                false, null, now, null, null, null, 0);
        AiCallRecord record2 = new AiCallRecord(1L, "job-123", "REVIEW", "claude-3", 1,
                1000L, 500L, 100L, 50L, "end_turn", "read_file", 2500L,
                false, null, now, null, null, null, 0);
        AiCallRecord record3 = new AiCallRecord(2L, "job-123", "REVIEW", "claude-3", 1,
                1000L, 500L, 100L, 50L, "end_turn", "read_file", 2500L,
                false, null, now, null, null, null, 0);

        assertEquals(record1, record2);
        assertNotEquals(record1, record3);
        assertEquals(record1.hashCode(), record2.hashCode());
    }

    @Test
    void recordToString() {
        AiCallRecord record = new AiCallRecord(1L, "job-123", "REVIEW", "claude-3", 1,
                1000L, 500L, 100L, 50L, "end_turn", "read_file", 2500L,
                false, null, Instant.now(), null, null, null, 0);

        String toString = record.toString();
        assertTrue(toString.contains("job-123"));
        assertTrue(toString.contains("REVIEW"));
        assertTrue(toString.contains("claude-3"));
        assertTrue(toString.contains("inputTokens=1000"));
        assertTrue(toString.contains("outputTokens=500"));
    }

    @Test
    void booleanFieldsHandleBothValues() {
        AiCallRecord errorRecord = new AiCallRecord(1L, "job-123", "FIX", "claude-3", 1,
                100L, 0L, 0L, 0L, "error", null, 1000L, true, "Error message",
                Instant.now(), null, null, null, 0);

        AiCallRecord successRecord = new AiCallRecord(2L, "job-456", "REVIEW", "claude-3", 1,
                1000L, 500L, 0L, 0L, "end_turn", "read_file", 2500L, false, null,
                Instant.now(), null, null, null, 0);

        assertTrue(errorRecord.isError());
        assertFalse(successRecord.isError());
    }
}
