package com.eneve.agent.audit;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AuditEntry} — the immutable audit-log row projection.
 */
class AuditEventTest {

    @Test
    void auditEntryStoresAllFields() {
        Instant now = Instant.parse("2024-01-15T10:30:00Z");

        AuditEntry entry = new AuditEntry(1L, "user123", "JOBS", "JOB_SUBMITTED",
                "job", "abc-123", "{\"jobType\":\"FIX\"}", now);

        assertEquals(1L, entry.id());
        assertEquals("user123", entry.actor());
        assertEquals("JOBS", entry.category());
        assertEquals("JOB_SUBMITTED", entry.action());
        assertEquals("job", entry.resourceType());
        assertEquals("abc-123", entry.resourceId());
        assertEquals("{\"jobType\":\"FIX\"}", entry.detail());
        assertEquals(now, entry.occurredAt());
    }

    @Test
    void auditEntryAllowsNullOptionalFields() {
        AuditEntry entry = new AuditEntry(null, "system", "SETTINGS", "SETTING_CHANGED",
                null, null, null, null);

        assertNull(entry.id());
        assertEquals("system", entry.actor());
        assertEquals("SETTINGS", entry.category());
        assertEquals("SETTING_CHANGED", entry.action());
        assertNull(entry.resourceType());
        assertNull(entry.resourceId());
        assertNull(entry.detail());
        assertNull(entry.occurredAt());
    }

    @Test
    void auditEntryEqualityBasedOnAllFields() {
        Instant now = Instant.parse("2024-01-15T10:30:00Z");
        AuditEntry e1 = new AuditEntry(1L, "actor", "CAT", "ACT", "rt", "rid", "{}", now);
        AuditEntry e2 = new AuditEntry(1L, "actor", "CAT", "ACT", "rt", "rid", "{}", now);
        AuditEntry e3 = new AuditEntry(2L, "actor", "CAT", "ACT", "rt", "rid", "{}", now);

        assertEquals(e1, e2);
        assertEquals(e1.hashCode(), e2.hashCode());
        assertNotEquals(e1, e3);
    }

    @Test
    void auditEntryToStringContainsKeyFields() {
        AuditEntry entry = new AuditEntry(1L, "user123", "JOBS", "JOB_SUBMITTED",
                "job", "xyz", null, null);

        String toString = entry.toString();
        assertTrue(toString.contains("JOBS"));
        assertTrue(toString.contains("JOB_SUBMITTED"));
        assertTrue(toString.contains("user123"));
    }

    @Test
    void auditEntryHandlesEmptyStrings() {
        AuditEntry entry = new AuditEntry(null, "", "", "", "", "", "", null);

        assertEquals("", entry.actor());
        assertEquals("", entry.category());
        assertEquals("", entry.action());
        assertEquals("", entry.resourceType());
        assertEquals("", entry.resourceId());
        assertEquals("", entry.detail());
    }
}
