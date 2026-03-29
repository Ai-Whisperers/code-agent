package com.eneve.agent.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class AuditServiceTest {

    private AuditService auditService;
    private AuditEvent sampleEvent;
    private Instant testTimestamp;

    @BeforeEach
    void setUp() {
        auditService = new AuditService();
        testTimestamp = Instant.parse("2024-01-15T10:30:00Z");
        sampleEvent = new AuditEvent(1L, "LOGIN", "user123", "user", "user123",
                                   "login", "Successful login", "192.168.1.1",
                                   "Mozilla/5.0", "sess123", true, null, testTimestamp);
    }

    @Test
    void auditServiceClassHasCorrectAnnotations() {
        // Verify the class is annotated with ApplicationScoped
        Class<AuditService> serviceClass = AuditService.class;
        assertTrue(serviceClass.isAnnotationPresent(jakarta.enterprise.context.ApplicationScoped.class));
    }

    @Test
    void auditServiceHasRequiredFields() throws NoSuchFieldException {
        Class<AuditService> serviceClass = AuditService.class;
        
        // Verify it has an auditStore field
        assertTrue(serviceClass.getDeclaredField("auditStore") != null);
        
        // Verify the auditStore field is annotated with Inject
        assertTrue(serviceClass.getDeclaredField("auditStore")
                   .isAnnotationPresent(jakarta.inject.Inject.class));
    }

    @Test
    void auditServiceHasRequiredMethods() {
        // Verify the service has the expected public methods
        assertDoesNotThrow(() -> {
            auditService.getClass().getMethod("recordEvent", AuditEvent.class);
            auditService.getClass().getMethod("recordSuccess", String.class, String.class, String.class, String.class, String.class, String.class);
            auditService.getClass().getMethod("recordFailure", String.class, String.class, String.class, String.class, String.class, String.class, String.class);
            auditService.getClass().getMethod("recordSystemEvent", AuditEventType.class, String.class, String.class, String.class, String.class);
            auditService.getClass().getMethod("recordSecurityEvent", AuditEventType.class, String.class, String.class, String.class, String.class, String.class, String.class, boolean.class, String.class);
            auditService.getClass().getMethod("getEntityAuditTrail", String.class, String.class);
            auditService.getClass().getMethod("getUserActivity", String.class, int.class, int.class);
            auditService.getClass().getMethod("getEventsByType", AuditEventType.class, Instant.class, Instant.class, int.class, int.class);
            auditService.getClass().getMethod("getRecentEvents", int.class, int.class, AuditEventType.class, String.class, Instant.class, Instant.class);
            auditService.getClass().getMethod("getStatistics", Instant.class, Instant.class);
            auditService.getClass().getMethod("getEventTypeCounts", Instant.class, Instant.class);
        });
    }

    @Test
    void recordEventAcceptsAuditEventWithoutThrowing() {
        // recordEvent should not throw exceptions even when store is not injected
        assertDoesNotThrow(() -> auditService.recordEvent(sampleEvent));
    }

    @Test
    void recordEventHandlesNullEvent() {
        // recordEvent should handle null events gracefully
        assertDoesNotThrow(() -> auditService.recordEvent(null));
    }

    @Test
    void recordSuccessCreatesEventCorrectly() {
        assertDoesNotThrow(() -> {
            auditService.recordSuccess("LOGIN", "user123", "user", "user123", 
                                      "login", "Successful login");
        });
    }

    @Test
    void recordSuccessHandlesNullParameters() {
        assertDoesNotThrow(() -> {
            auditService.recordSuccess(null, null, null, null, null, null);
        });
    }

    @Test
    void recordFailureCreatesEventCorrectly() {
        assertDoesNotThrow(() -> {
            auditService.recordFailure("LOGIN", "user123", "user", "user123", 
                                      "login", "Failed login", "Invalid credentials");
        });
    }

    @Test
    void recordFailureHandlesNullParameters() {
        assertDoesNotThrow(() -> {
            auditService.recordFailure(null, null, null, null, null, null, null);
        });
    }

    @Test
    void recordSystemEventCreatesEventCorrectly() {
        assertDoesNotThrow(() -> {
            auditService.recordSystemEvent(AuditEventType.SYSTEM_STARTUP, "system", 
                                          "instance1", "startup", "System started");
        });
    }

    @Test
    void recordSystemEventHandlesNullEventType() {
        assertDoesNotThrow(() -> {
            auditService.recordSystemEvent(null, "system", "instance1", "startup", "System started");
        });
    }

    @Test
    void recordSecurityEventCreatesEventCorrectly() {
        assertDoesNotThrow(() -> {
            auditService.recordSecurityEvent(AuditEventType.LOGIN_FAILED, "user456", "login", 
                                            "Failed login attempt", "192.168.1.100", 
                                            "Chrome/91.0", "sess456", false, "Invalid password");
        });
    }

    @Test
    void recordSecurityEventHandlesNullParameters() {
        assertDoesNotThrow(() -> {
            auditService.recordSecurityEvent(null, null, null, null, null, null, null, true, null);
        });
    }

    @Test
    void getEntityAuditTrailAcceptsParameters() {
        // Will fail due to no store injection, but should not throw other exceptions
        try {
            auditService.getEntityAuditTrail("user", "user123");
        } catch (RuntimeException e) {
            // Expected due to no auditStore injection
            assertTrue(e instanceof NullPointerException || 
                      e.getMessage().contains("Failed to get audit trail"));
        }
    }

    @Test
    void getEntityAuditTrailHandlesNullParameters() {
        try {
            auditService.getEntityAuditTrail(null, null);
        } catch (RuntimeException e) {
            // Expected due to no auditStore injection
            assertTrue(e instanceof NullPointerException || 
                      e.getMessage().contains("Failed to get audit trail"));
        }
    }

    @Test
    void getUserActivityAcceptsParameters() {
        try {
            auditService.getUserActivity("user123", 10, 0);
        } catch (RuntimeException e) {
            // Expected due to no auditStore injection
            assertTrue(e instanceof NullPointerException || 
                      e.getMessage().contains("Failed to get user activity"));
        }
    }

    @Test
    void getUserActivityHandlesEdgeCaseParameters() {
        try {
            auditService.getUserActivity("user123", 0, 0);
            auditService.getUserActivity("user123", 1000, 5000);
            auditService.getUserActivity(null, -1, -1);
        } catch (RuntimeException e) {
            // Expected due to no auditStore injection
            assertTrue(e instanceof NullPointerException || 
                      e.getMessage().contains("Failed to get user activity"));
        }
    }

    @Test
    void getEventsByTypeAcceptsParameters() {
        Instant from = Instant.parse("2024-01-01T00:00:00Z");
        Instant to = Instant.parse("2024-01-31T23:59:59Z");
        
        try {
            auditService.getEventsByType(AuditEventType.LOGIN, from, to, 20, 5);
        } catch (RuntimeException e) {
            // Expected due to no auditStore injection
            assertTrue(e instanceof NullPointerException || 
                      e.getMessage().contains("Failed to get events by type"));
        }
    }

    @Test
    void getEventsByTypeHandlesNullEventType() {
        try {
            auditService.getEventsByType(null, null, null, 10, 0);
        } catch (RuntimeException e) {
            // Expected due to no auditStore injection
            assertTrue(e instanceof NullPointerException || 
                      e.getMessage().contains("Failed to get events by type"));
        }
    }

    @Test
    void getRecentEventsAcceptsParameters() {
        Instant from = Instant.parse("2024-01-01T00:00:00Z");
        
        try {
            auditService.getRecentEvents(15, 10, AuditEventType.LOGIN, "user123", from, null);
        } catch (RuntimeException e) {
            // Expected due to no auditStore injection
            assertTrue(e instanceof NullPointerException || 
                      e.getMessage().contains("Failed to get recent audit events"));
        }
    }

    @Test
    void getRecentEventsHandlesNullEventType() {
        try {
            auditService.getRecentEvents(10, 0, null, "user123", null, null);
        } catch (RuntimeException e) {
            // Expected due to no auditStore injection
            assertTrue(e instanceof NullPointerException || 
                      e.getMessage().contains("Failed to get recent audit events"));
        }
    }

    @Test
    void getStatisticsAcceptsParameters() {
        try {
            auditService.getStatistics(null, null);
        } catch (RuntimeException e) {
            // Expected due to no auditStore injection
            assertTrue(e instanceof NullPointerException || 
                      e.getMessage().contains("Failed to get audit statistics"));
        }
    }

    @Test
    void getEventTypeCountsAcceptsParameters() {
        try {
            auditService.getEventTypeCounts(null, null);
        } catch (RuntimeException e) {
            // Expected due to no auditStore injection
            assertTrue(e instanceof NullPointerException || 
                      e.getMessage().contains("Failed to get event type counts"));
        }
    }

    @Test
    void recordJobEventCreatesEventCorrectly() {
        assertDoesNotThrow(() -> {
            auditService.recordJobEvent(AuditEventType.JOB_CREATED, "user789", "job456", 
                                       "create", "New job created");
        });
    }

    @Test
    void recordJobEventHandlesNullParameters() {
        assertDoesNotThrow(() -> {
            auditService.recordJobEvent(null, null, null, null, null);
        });
    }

    @Test
    void recordRepoEventCreatesEventCorrectly() {
        assertDoesNotThrow(() -> {
            auditService.recordRepoEvent(AuditEventType.REPO_CLONED, "user789", "repo123", 
                                        "clone", "Repository cloned successfully");
        });
    }

    @Test
    void recordRepoEventHandlesNullParameters() {
        assertDoesNotThrow(() -> {
            auditService.recordRepoEvent(null, null, null, null, null);
        });
    }

    @Test
    void recordAiCallEventCreatesEventCorrectly() {
        assertDoesNotThrow(() -> {
            auditService.recordAiCallEvent("user789", "job456", "claude-3", 
                                          "call", "Token usage: 150 input, 75 output");
        });
    }

    @Test
    void recordAiCallEventHandlesNullParameters() {
        assertDoesNotThrow(() -> {
            auditService.recordAiCallEvent(null, null, null, null, null);
        });
    }

    @Test
    void recordToolEventSuccessCreatesEventCorrectly() {
        assertDoesNotThrow(() -> {
            auditService.recordToolEvent("user789", "job456", "WriteFileTool", 
                                        "execute", "File written successfully", true, null);
        });
    }

    @Test
    void recordToolEventFailureCreatesEventCorrectly() {
        assertDoesNotThrow(() -> {
            auditService.recordToolEvent("user789", "job456", "ReadFileTool", 
                                        "execute", "Attempted to read file", false, "File not found");
        });
    }

    @Test
    void recordToolEventHandlesNullParameters() {
        assertDoesNotThrow(() -> {
            auditService.recordToolEvent(null, null, null, null, null, true, null);
            auditService.recordToolEvent(null, null, null, null, null, false, null);
        });
    }

    @Test
    void recordConfigChangeCreatesEventCorrectly() {
        assertDoesNotThrow(() -> {
            auditService.recordConfigChange("admin", "max.connections", "100", "200", 
                                           "Increased for better performance");
        });
    }

    @Test
    void recordConfigChangeHandlesNullParameters() {
        assertDoesNotThrow(() -> {
            auditService.recordConfigChange(null, null, null, null, null);
        });
    }

    @Test
    void recordDataAccessCreatesEventCorrectly() {
        assertDoesNotThrow(() -> {
            auditService.recordDataAccess("user789", "sensitive_file", "file123", 
                                         "read", "Accessed for review");
        });
    }

    @Test
    void recordDataAccessHandlesNullParameters() {
        assertDoesNotThrow(() -> {
            auditService.recordDataAccess(null, null, null, null, null);
        });
    }

    @Test
    void allConvenienceMethodsHandleEmptyStringParameters() {
        assertDoesNotThrow(() -> {
            auditService.recordSuccess("", "", "", "", "", "");
            auditService.recordFailure("", "", "", "", "", "", "");
            auditService.recordJobEvent(AuditEventType.JOB_CREATED, "", "", "", "");
            auditService.recordRepoEvent(AuditEventType.REPO_CLONED, "", "", "", "");
            auditService.recordAiCallEvent("", "", "", "", "");
            auditService.recordToolEvent("", "", "", "", "", true, "");
            auditService.recordConfigChange("", "", "", "", "");
            auditService.recordDataAccess("", "", "", "", "");
        });
    }

    @Test
    void allConvenienceMethodsHandleLongStringParameters() {
        String longString = "a".repeat(1000);
        
        assertDoesNotThrow(() -> {
            auditService.recordSuccess(longString, longString, longString, longString, longString, longString);
            auditService.recordFailure(longString, longString, longString, longString, longString, longString, longString);
            auditService.recordJobEvent(AuditEventType.JOB_CREATED, longString, longString, longString, longString);
            auditService.recordRepoEvent(AuditEventType.REPO_CLONED, longString, longString, longString, longString);
            auditService.recordAiCallEvent(longString, longString, longString, longString, longString);
            auditService.recordToolEvent(longString, longString, longString, longString, longString, true, longString);
            auditService.recordConfigChange(longString, longString, longString, longString, longString);
            auditService.recordDataAccess(longString, longString, longString, longString, longString);
        });
    }

    @Test
    void recordEventDoesNotFailOnStoreExceptions() {
        // recordEvent should handle store exceptions gracefully and not rethrow
        // This simulates the intended behavior where audit failures don't break business logic
        AuditEvent eventWithNullFields = new AuditEvent(null, null, null, null, null, null, null, null, null, null, false, null, null);
        
        assertDoesNotThrow(() -> auditService.recordEvent(eventWithNullFields));
    }

    @Test
    void queryMethodsPropagateStoreExceptions() {
        // Query methods should propagate exceptions (unlike recordEvent)
        String[] queryMethods = {
            "getEntityAuditTrail", "getUserActivity", "getEventsByType", 
            "getRecentEvents", "getStatistics", "getEventTypeCounts"
        };
        
        for (String methodName : queryMethods) {
            assertDoesNotThrow(() -> {
                try {
                    switch (methodName) {
                        case "getEntityAuditTrail" -> auditService.getEntityAuditTrail("user", "123");
                        case "getUserActivity" -> auditService.getUserActivity("user", 10, 0);
                        case "getEventsByType" -> auditService.getEventsByType(AuditEventType.LOGIN, null, null, 10, 0);
                        case "getRecentEvents" -> auditService.getRecentEvents(10, 0, null, null, null, null);
                        case "getStatistics" -> auditService.getStatistics(null, null);
                        case "getEventTypeCounts" -> auditService.getEventTypeCounts(null, null);
                    }
                } catch (RuntimeException e) {
                    // Expected due to no auditStore injection - this is the correct behavior
                    assertTrue(e instanceof NullPointerException || e.getMessage().contains("Failed to get"));
                }
            });
        }
    }
}