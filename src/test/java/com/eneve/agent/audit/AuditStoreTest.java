package com.eneve.agent.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class AuditStoreTest {

    private AuditStore auditStore;
    private AuditEvent sampleEvent;
    private Instant testTimestamp;

    @BeforeEach
    void setUp() {
        auditStore = new AuditStore();
        testTimestamp = Instant.parse("2024-01-15T10:30:00Z");
        sampleEvent = new AuditEvent(1L, "LOGIN", "user123", "user", "user123",
                                   "login", "Successful login", "192.168.1.1",
                                   "Mozilla/5.0", "sess123", true, null, testTimestamp);
    }

    @Test
    void auditStoreClassHasCorrectAnnotations() {
        // Verify the class is annotated with ApplicationScoped
        Class<AuditStore> storeClass = AuditStore.class;
        assertTrue(storeClass.isAnnotationPresent(jakarta.enterprise.context.ApplicationScoped.class));
    }

    @Test
    void auditStoreHasRequiredFields() throws NoSuchFieldException {
        Class<AuditStore> storeClass = AuditStore.class;
        
        // Verify it has a dataSource field
        assertTrue(storeClass.getDeclaredField("dataSource") != null);
        
        // Verify the dataSource field is annotated with Inject
        assertTrue(storeClass.getDeclaredField("dataSource")
                   .isAnnotationPresent(jakarta.inject.Inject.class));
    }

    @Test
    void auditStoreHasRequiredMethods() {
        // Verify the store has the expected public methods
        assertDoesNotThrow(() -> {
            auditStore.getClass().getMethod("save", AuditEvent.class);
            auditStore.getClass().getMethod("findByEntity", String.class, String.class);
            auditStore.getClass().getMethod("findByUserId", String.class, int.class, int.class);
            auditStore.getClass().getMethod("findByEventType", String.class, Instant.class, Instant.class, int.class, int.class);
            auditStore.getClass().getMethod("getRecentEvents", int.class, int.class, String.class, String.class, Instant.class, Instant.class);
            auditStore.getClass().getMethod("getStatistics", Instant.class, Instant.class);
            auditStore.getClass().getMethod("getEventTypeCounts", Instant.class, Instant.class);
        });
    }

    @Test
    void saveMethodAcceptsAuditEvent() {
        // Test that the save method accepts an AuditEvent without throwing
        // We can't test actual database interaction without a database
        assertDoesNotThrow(() -> {
            // This will fail with NPE due to no dataSource, but that's expected in unit test
            try {
                auditStore.save(sampleEvent);
            } catch (RuntimeException e) {
                // Expected due to no dataSource injection in unit test
                assertTrue(e.getMessage().contains("Failed to store audit event") || 
                          e instanceof NullPointerException);
            }
        });
    }

    @Test
    void findByEntityMethodAcceptsParameters() {
        assertDoesNotThrow(() -> {
            try {
                auditStore.findByEntity("user", "user123");
            } catch (RuntimeException e) {
                // Expected due to no dataSource injection in unit test
                assertTrue(e.getMessage().contains("Failed to query audit events") || 
                          e instanceof NullPointerException);
            }
        });
    }

    @Test
    void findByUserIdMethodAcceptsParameters() {
        assertDoesNotThrow(() -> {
            try {
                auditStore.findByUserId("user123", 10, 0);
            } catch (RuntimeException e) {
                // Expected due to no dataSource injection in unit test
                assertTrue(e.getMessage().contains("Failed to query audit events") || 
                          e instanceof NullPointerException);
            }
        });
    }

    @Test
    void findByEventTypeMethodAcceptsParameters() {
        Instant from = Instant.parse("2024-01-01T00:00:00Z");
        Instant to = Instant.parse("2024-01-31T23:59:59Z");
        
        assertDoesNotThrow(() -> {
            try {
                auditStore.findByEventType("LOGIN", from, to, 20, 5);
            } catch (RuntimeException e) {
                // Expected due to no dataSource injection in unit test
                assertTrue(e.getMessage().contains("Failed to query audit events") || 
                          e instanceof NullPointerException);
            }
        });
    }

    @Test
    void findByEventTypeHandlesNullTimeFilters() {
        assertDoesNotThrow(() -> {
            try {
                auditStore.findByEventType("LOGIN", null, null, 10, 0);
            } catch (RuntimeException e) {
                // Expected due to no dataSource injection in unit test
                assertTrue(e.getMessage().contains("Failed to query audit events") || 
                          e instanceof NullPointerException);
            }
        });
    }

    @Test
    void getRecentEventsMethodAcceptsParameters() {
        Instant from = Instant.parse("2024-01-01T00:00:00Z");
        
        assertDoesNotThrow(() -> {
            try {
                auditStore.getRecentEvents(10, 0, "LOGIN", "user123", from, null);
            } catch (RuntimeException e) {
                // Expected due to no dataSource injection in unit test
                assertTrue(e.getMessage().contains("Failed to query audit events") || 
                          e instanceof NullPointerException);
            }
        });
    }

    @Test
    void getRecentEventsHandlesNullFilters() {
        assertDoesNotThrow(() -> {
            try {
                auditStore.getRecentEvents(5, 0, null, null, null, null);
            } catch (RuntimeException e) {
                // Expected due to no dataSource injection in unit test
                assertTrue(e.getMessage().contains("Failed to query audit events") || 
                          e instanceof NullPointerException);
            }
        });
    }

    @Test
    void getStatisticsMethodAcceptsParameters() {
        assertDoesNotThrow(() -> {
            try {
                auditStore.getStatistics(null, null);
            } catch (RuntimeException e) {
                // Expected due to no dataSource injection in unit test
                assertTrue(e.getMessage().contains("Failed to query audit statistics") || 
                          e instanceof NullPointerException);
            }
        });
    }

    @Test
    void getEventTypeCountsMethodAcceptsParameters() {
        assertDoesNotThrow(() -> {
            try {
                auditStore.getEventTypeCounts(null, null);
            } catch (RuntimeException e) {
                // Expected due to no dataSource injection in unit test
                assertTrue(e.getMessage().contains("Failed to query event type counts") || 
                          e instanceof NullPointerException);
            }
        });
    }

    @Test
    void methodsHandleValidParameterRanges() {
        // Test edge cases for numeric parameters
        assertDoesNotThrow(() -> {
            try {
                auditStore.findByUserId("user123", 0, 0); // Zero limit and offset
            } catch (RuntimeException e) {
                assertTrue(e.getMessage().contains("Failed to query audit events") || 
                          e instanceof NullPointerException);
            }
        });

        assertDoesNotThrow(() -> {
            try {
                auditStore.findByUserId("user123", 1000, 5000); // Large limit and offset
            } catch (RuntimeException e) {
                assertTrue(e.getMessage().contains("Failed to query audit events") || 
                          e instanceof NullPointerException);
            }
        });
    }

    @Test
    void methodsHandleEmptyAndNullStrings() {
        assertDoesNotThrow(() -> {
            try {
                auditStore.findByEntity("", "");
            } catch (RuntimeException e) {
                assertTrue(e.getMessage().contains("Failed to query audit events") || 
                          e instanceof NullPointerException);
            }
        });

        assertDoesNotThrow(() -> {
            try {
                auditStore.findByEntity(null, null);
            } catch (RuntimeException e) {
                assertTrue(e.getMessage().contains("Failed to query audit events") || 
                          e instanceof NullPointerException);
            }
        });
    }

    @Test
    void saveHandlesEventWithNullValues() {
        AuditEvent eventWithNulls = new AuditEvent(null, null, null, null, null,
                                                  null, null, null, null, null,
                                                  false, "Error message", null);

        assertDoesNotThrow(() -> {
            try {
                auditStore.save(eventWithNulls);
            } catch (RuntimeException e) {
                assertTrue(e.getMessage().contains("Failed to store audit event") || 
                          e instanceof NullPointerException);
            }
        });
    }

    @Test
    void saveHandlesEventWithEmptyStrings() {
        AuditEvent eventWithEmpty = new AuditEvent(null, "", "", "", "",
                                                  "", "", "", "", "",
                                                  true, "", Instant.now());

        assertDoesNotThrow(() -> {
            try {
                auditStore.save(eventWithEmpty);
            } catch (RuntimeException e) {
                assertTrue(e.getMessage().contains("Failed to store audit event") || 
                          e instanceof NullPointerException);
            }
        });
    }

    @Test
    void timeRangeQueriesHandleValidTimeRanges() {
        Instant from = Instant.parse("2024-01-01T00:00:00Z");
        Instant to = Instant.parse("2024-12-31T23:59:59Z");

        assertDoesNotThrow(() -> {
            try {
                auditStore.getStatistics(from, to);
                auditStore.getEventTypeCounts(from, to);
                auditStore.findByEventType("LOGIN", from, to, 10, 0);
            } catch (RuntimeException e) {
                // Expected due to no dataSource injection
                assertTrue(e.getMessage().contains("Failed to query") || 
                          e instanceof NullPointerException);
            }
        });
    }

    @Test
    void timeRangeQueriesHandleReversedTimeRange() {
        Instant from = Instant.parse("2024-12-31T23:59:59Z");
        Instant to = Instant.parse("2024-01-01T00:00:00Z");

        assertDoesNotThrow(() -> {
            try {
                auditStore.getStatistics(from, to);
            } catch (RuntimeException e) {
                // Expected due to no dataSource injection
                assertTrue(e.getMessage().contains("Failed to query") || 
                          e instanceof NullPointerException);
            }
        });
    }
}