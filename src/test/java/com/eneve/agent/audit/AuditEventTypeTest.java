package com.eneve.agent.audit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuditEventTypeTest {

    @Test
    void enumHasExpectedValues() {
        AuditEventType[] values = AuditEventType.values();
        
        // Verify we have at least the main categories of events
        assertTrue(values.length >= 25);
        
        // Verify specific important events exist
        assertTrue(containsValue(values, AuditEventType.LOGIN));
        assertTrue(containsValue(values, AuditEventType.LOGOUT));
        assertTrue(containsValue(values, AuditEventType.LOGIN_FAILED));
        assertTrue(containsValue(values, AuditEventType.PERMISSION_DENIED));
        assertTrue(containsValue(values, AuditEventType.JOB_CREATED));
        assertTrue(containsValue(values, AuditEventType.JOB_STARTED));
        assertTrue(containsValue(values, AuditEventType.JOB_COMPLETED));
        assertTrue(containsValue(values, AuditEventType.JOB_FAILED));
        assertTrue(containsValue(values, AuditEventType.AI_CALL_MADE));
        assertTrue(containsValue(values, AuditEventType.TOOL_EXECUTED));
        assertTrue(containsValue(values, AuditEventType.SECURITY_VIOLATION));
        assertTrue(containsValue(values, AuditEventType.WEBHOOK_RECEIVED));
        assertTrue(containsValue(values, AuditEventType.API_KEY_USED));
    }

    @Test
    void authenticationEventsHaveCorrectNames() {
        assertEquals("LOGIN", AuditEventType.LOGIN.name());
        assertEquals("LOGOUT", AuditEventType.LOGOUT.name());
        assertEquals("LOGIN_FAILED", AuditEventType.LOGIN_FAILED.name());
        assertEquals("PERMISSION_DENIED", AuditEventType.PERMISSION_DENIED.name());
    }

    @Test
    void jobEventsHaveCorrectNames() {
        assertEquals("JOB_CREATED", AuditEventType.JOB_CREATED.name());
        assertEquals("JOB_STARTED", AuditEventType.JOB_STARTED.name());
        assertEquals("JOB_COMPLETED", AuditEventType.JOB_COMPLETED.name());
        assertEquals("JOB_FAILED", AuditEventType.JOB_FAILED.name());
        assertEquals("JOB_CANCELLED", AuditEventType.JOB_CANCELLED.name());
    }

    @Test
    void repositoryEventsHaveCorrectNames() {
        assertEquals("REPO_CLONED", AuditEventType.REPO_CLONED.name());
        assertEquals("REPO_UPDATED", AuditEventType.REPO_UPDATED.name());
        assertEquals("PR_CREATED", AuditEventType.PR_CREATED.name());
        assertEquals("PR_REVIEWED", AuditEventType.PR_REVIEWED.name());
        assertEquals("PR_MERGED", AuditEventType.PR_MERGED.name());
    }

    @Test
    void systemEventsHaveCorrectNames() {
        assertEquals("CONFIG_CHANGED", AuditEventType.CONFIG_CHANGED.name());
        assertEquals("SYSTEM_STARTUP", AuditEventType.SYSTEM_STARTUP.name());
        assertEquals("SYSTEM_SHUTDOWN", AuditEventType.SYSTEM_SHUTDOWN.name());
    }

    @Test
    void aiAndToolEventsHaveCorrectNames() {
        assertEquals("AI_CALL_MADE", AuditEventType.AI_CALL_MADE.name());
        assertEquals("TOOL_EXECUTED", AuditEventType.TOOL_EXECUTED.name());
    }

    @Test
    void dataEventsHaveCorrectNames() {
        assertEquals("DATA_CREATED", AuditEventType.DATA_CREATED.name());
        assertEquals("DATA_UPDATED", AuditEventType.DATA_UPDATED.name());
        assertEquals("DATA_DELETED", AuditEventType.DATA_DELETED.name());
        assertEquals("DATA_ACCESSED", AuditEventType.DATA_ACCESSED.name());
    }

    @Test
    void securityEventsHaveCorrectNames() {
        assertEquals("SECURITY_VIOLATION", AuditEventType.SECURITY_VIOLATION.name());
        assertEquals("WEBHOOK_RECEIVED", AuditEventType.WEBHOOK_RECEIVED.name());
        assertEquals("API_KEY_USED", AuditEventType.API_KEY_USED.name());
    }

    @Test
    void descriptionsAreNotNullOrEmpty() {
        for (AuditEventType eventType : AuditEventType.values()) {
            assertNotNull(eventType.getDescription());
            assertFalse(eventType.getDescription().isEmpty());
            assertFalse(eventType.getDescription().isBlank());
        }
    }

    @Test
    void descriptionsContainMeaningfulText() {
        assertEquals("Authentication - User login", AuditEventType.LOGIN.getDescription());
        assertEquals("Authentication - User logout", AuditEventType.LOGOUT.getDescription());
        assertEquals("Authentication - Failed login attempt", AuditEventType.LOGIN_FAILED.getDescription());
        assertEquals("Job - Job created", AuditEventType.JOB_CREATED.getDescription());
        assertEquals("AI - AI call made", AuditEventType.AI_CALL_MADE.getDescription());
        assertEquals("Tools - Tool executed", AuditEventType.TOOL_EXECUTED.getDescription());
        assertEquals("Security - Security violation", AuditEventType.SECURITY_VIOLATION.getDescription());
    }

    @Test
    void toStringIncludesNameAndDescription() {
        String loginToString = AuditEventType.LOGIN.toString();
        assertTrue(loginToString.contains("LOGIN"));
        assertTrue(loginToString.contains("Authentication - User login"));
        
        String jobCreatedToString = AuditEventType.JOB_CREATED.toString();
        assertTrue(jobCreatedToString.contains("JOB_CREATED"));
        assertTrue(jobCreatedToString.contains("Job - Job created"));
    }

    @Test
    void enumValueOfWorks() {
        assertEquals(AuditEventType.LOGIN, AuditEventType.valueOf("LOGIN"));
        assertEquals(AuditEventType.JOB_CREATED, AuditEventType.valueOf("JOB_CREATED"));
        assertEquals(AuditEventType.AI_CALL_MADE, AuditEventType.valueOf("AI_CALL_MADE"));
        assertEquals(AuditEventType.SECURITY_VIOLATION, AuditEventType.valueOf("SECURITY_VIOLATION"));
    }

    @Test
    void enumValueOfThrowsExceptionForInvalidValue() {
        assertThrows(IllegalArgumentException.class, () -> AuditEventType.valueOf("INVALID"));
        assertThrows(IllegalArgumentException.class, () -> AuditEventType.valueOf("login")); // case sensitive
        assertThrows(IllegalArgumentException.class, () -> AuditEventType.valueOf(""));
    }

    @Test
    void enumValueOfThrowsExceptionForNull() {
        assertThrows(NullPointerException.class, () -> AuditEventType.valueOf(null));
    }

    @Test
    void enumEquality() {
        assertEquals(AuditEventType.LOGIN, AuditEventType.LOGIN);
        assertEquals(AuditEventType.JOB_CREATED, AuditEventType.JOB_CREATED);
        assertNotEquals(AuditEventType.LOGIN, AuditEventType.LOGOUT);
        assertNotEquals(AuditEventType.JOB_CREATED, AuditEventType.JOB_FAILED);
    }

    @Test
    void enumOrdinals() {
        // Test that ordinals are consistent (the first few auth events)
        assertEquals(0, AuditEventType.LOGIN.ordinal());
        assertEquals(1, AuditEventType.LOGOUT.ordinal());
        assertEquals(2, AuditEventType.LOGIN_FAILED.ordinal());
        assertEquals(3, AuditEventType.PERMISSION_DENIED.ordinal());
        
        // Verify all enum values have unique ordinals
        AuditEventType[] values = AuditEventType.values();
        for (int i = 0; i < values.length; i++) {
            assertEquals(i, values[i].ordinal());
        }
    }

    @Test
    void enumCoverage() {
        // Verify we have events for all major categories
        boolean hasAuthEvents = false;
        boolean hasJobEvents = false;
        boolean hasRepoEvents = false;
        boolean hasSystemEvents = false;
        boolean hasAiEvents = false;
        boolean hasDataEvents = false;
        boolean hasSecurityEvents = false;

        for (AuditEventType eventType : AuditEventType.values()) {
            String name = eventType.name();
            if (name.startsWith("LOGIN") || name.startsWith("LOGOUT") || name.contains("PERMISSION")) {
                hasAuthEvents = true;
            }
            if (name.startsWith("JOB_")) {
                hasJobEvents = true;
            }
            if (name.startsWith("REPO_") || name.startsWith("PR_")) {
                hasRepoEvents = true;
            }
            if (name.startsWith("SYSTEM_") || name.startsWith("CONFIG_")) {
                hasSystemEvents = true;
            }
            if (name.contains("AI_") || name.contains("TOOL_")) {
                hasAiEvents = true;
            }
            if (name.startsWith("DATA_")) {
                hasDataEvents = true;
            }
            if (name.contains("SECURITY") || name.contains("WEBHOOK") || name.contains("API_KEY")) {
                hasSecurityEvents = true;
            }
        }

        assertTrue(hasAuthEvents, "Should have authentication events");
        assertTrue(hasJobEvents, "Should have job events");
        assertTrue(hasRepoEvents, "Should have repository events");
        assertTrue(hasSystemEvents, "Should have system events");
        assertTrue(hasAiEvents, "Should have AI/tool events");
        assertTrue(hasDataEvents, "Should have data events");
        assertTrue(hasSecurityEvents, "Should have security events");
    }

    @Test
    void descriptionsFollowPattern() {
        for (AuditEventType eventType : AuditEventType.values()) {
            String description = eventType.getDescription();
            
            // All descriptions should have a category prefix followed by " - "
            assertTrue(description.contains(" - "), 
                      "Description should contain ' - ' separator: " + description);
            
            String[] parts = description.split(" - ", 2);
            assertEquals(2, parts.length, "Description should have exactly one ' - ' separator");
            
            String category = parts[0];
            String action = parts[1];
            
            assertFalse(category.isEmpty(), "Category part should not be empty");
            assertFalse(action.isEmpty(), "Action part should not be empty");
        }
    }

    private boolean containsValue(AuditEventType[] values, AuditEventType target) {
        for (AuditEventType value : values) {
            if (value == target) {
                return true;
            }
        }
        return false;
    }
}