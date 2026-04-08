package com.eneve.agent.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JobStatusTest {

    @Test
    void enumHasExpectedValues() {
        JobStatus[] values = JobStatus.values();
        
        assertEquals(7, values.length);
        assertEquals(JobStatus.PENDING, values[0]);
        assertEquals(JobStatus.QUEUED, values[1]);
        assertEquals(JobStatus.RUNNING, values[2]);
        assertEquals(JobStatus.SUCCESS, values[3]);
        assertEquals(JobStatus.FAILED, values[4]);
        assertEquals(JobStatus.AWAITING_APPROVAL, values[5]);
        assertEquals(JobStatus.CANCELLED, values[6]);
    }

    @Test
    void enumValuesHaveCorrectNames() {
        assertEquals("PENDING", JobStatus.PENDING.name());
        assertEquals("QUEUED", JobStatus.QUEUED.name());
        assertEquals("RUNNING", JobStatus.RUNNING.name());
        assertEquals("SUCCESS", JobStatus.SUCCESS.name());
        assertEquals("FAILED", JobStatus.FAILED.name());
        assertEquals("AWAITING_APPROVAL", JobStatus.AWAITING_APPROVAL.name());
        assertEquals("CANCELLED", JobStatus.CANCELLED.name());
    }

    @Test
    void enumValueOfWorks() {
        assertEquals(JobStatus.PENDING, JobStatus.valueOf("PENDING"));
        assertEquals(JobStatus.QUEUED, JobStatus.valueOf("QUEUED"));
        assertEquals(JobStatus.RUNNING, JobStatus.valueOf("RUNNING"));
        assertEquals(JobStatus.SUCCESS, JobStatus.valueOf("SUCCESS"));
        assertEquals(JobStatus.FAILED, JobStatus.valueOf("FAILED"));
        assertEquals(JobStatus.AWAITING_APPROVAL, JobStatus.valueOf("AWAITING_APPROVAL"));
        assertEquals(JobStatus.CANCELLED, JobStatus.valueOf("CANCELLED"));
    }

    @Test
    void enumValueOfThrowsExceptionForInvalidValue() {
        assertThrows(IllegalArgumentException.class, () -> JobStatus.valueOf("INVALID"));
        assertThrows(IllegalArgumentException.class, () -> JobStatus.valueOf("pending")); // case sensitive
        assertThrows(IllegalArgumentException.class, () -> JobStatus.valueOf(""));
    }

    @Test
    void enumValueOfThrowsExceptionForNull() {
        assertThrows(NullPointerException.class, () -> JobStatus.valueOf(null));
    }

    @Test
    void enumOrdinals() {
        assertEquals(0, JobStatus.PENDING.ordinal());
        assertEquals(1, JobStatus.QUEUED.ordinal());
        assertEquals(2, JobStatus.RUNNING.ordinal());
        assertEquals(3, JobStatus.SUCCESS.ordinal());
        assertEquals(4, JobStatus.FAILED.ordinal());
        assertEquals(5, JobStatus.AWAITING_APPROVAL.ordinal());
        assertEquals(6, JobStatus.CANCELLED.ordinal());
    }

    @Test
    void enumEquality() {
        assertEquals(JobStatus.SUCCESS, JobStatus.SUCCESS);
        assertNotEquals(JobStatus.SUCCESS, JobStatus.FAILED);
        assertNotEquals(JobStatus.PENDING, JobStatus.RUNNING);
    }

    @Test
    void enumToString() {
        assertEquals("PENDING", JobStatus.PENDING.toString());
        assertEquals("SUCCESS", JobStatus.SUCCESS.toString());
        assertEquals("AWAITING_APPROVAL", JobStatus.AWAITING_APPROVAL.toString());
    }
}