package com.eneve.agent.audit;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class AuditEventTest {

    @Test
    void constructorWithAllFieldsCreatesCorrectEvent() {
        Long id = 123L;
        String eventType = "LOGIN";
        String userId = "user123";
        String entityType = "user";
        String entityId = "user123";
        String action = "login";
        String details = "Successful login";
        String ipAddress = "192.168.1.1";
        String userAgent = "Mozilla/5.0";
        String sessionId = "sess123";
        boolean success = true;
        String errorMessage = null;
        Instant timestamp = Instant.now();

        AuditEvent event = new AuditEvent(id, eventType, userId, entityType, entityId, 
                                         action, details, ipAddress, userAgent, sessionId, 
                                         success, errorMessage, timestamp);

        assertEquals(id, event.id());
        assertEquals(eventType, event.eventType());
        assertEquals(userId, event.userId());
        assertEquals(entityType, event.entityType());
        assertEquals(entityId, event.entityId());
        assertEquals(action, event.action());
        assertEquals(details, event.details());
        assertEquals(ipAddress, event.ipAddress());
        assertEquals(userAgent, event.userAgent());
        assertEquals(sessionId, event.sessionId());
        assertTrue(event.success());
        assertNull(event.errorMessage());
        assertEquals(timestamp, event.timestamp());
    }

    @Test
    void constructorWithWebInfoCreatesEventWithCurrentTimestamp() {
        String eventType = "LOGIN";
        String userId = "user123";
        String entityType = "user";
        String entityId = "user123";
        String action = "login";
        String details = "Successful login";
        String ipAddress = "192.168.1.1";
        String userAgent = "Mozilla/5.0";
        String sessionId = "sess123";
        boolean success = true;
        String errorMessage = null;

        Instant beforeCreation = Instant.now();
        AuditEvent event = new AuditEvent(eventType, userId, entityType, entityId, 
                                         action, details, ipAddress, userAgent, 
                                         sessionId, success, errorMessage);
        Instant afterCreation = Instant.now();

        assertNull(event.id());
        assertEquals(eventType, event.eventType());
        assertEquals(userId, event.userId());
        assertEquals(entityType, event.entityType());
        assertEquals(entityId, event.entityId());
        assertEquals(action, event.action());
        assertEquals(details, event.details());
        assertEquals(ipAddress, event.ipAddress());
        assertEquals(userAgent, event.userAgent());
        assertEquals(sessionId, event.sessionId());
        assertTrue(event.success());
        assertNull(event.errorMessage());
        assertNotNull(event.timestamp());
        assertTrue(event.timestamp().isAfter(beforeCreation) || event.timestamp().equals(beforeCreation));
        assertTrue(event.timestamp().isBefore(afterCreation) || event.timestamp().equals(afterCreation));
    }

    @Test
    void constructorWithMinimalInfoCreatesEventWithNullOptionalFields() {
        String eventType = "DATA_ACCESS";
        String userId = "user456";
        String entityType = "file";
        String entityId = "file789";
        String action = "read";
        String details = "File accessed";
        boolean success = true;

        Instant beforeCreation = Instant.now();
        AuditEvent event = new AuditEvent(eventType, userId, entityType, entityId, 
                                         action, details, success);
        Instant afterCreation = Instant.now();

        assertNull(event.id());
        assertEquals(eventType, event.eventType());
        assertEquals(userId, event.userId());
        assertEquals(entityType, event.entityType());
        assertEquals(entityId, event.entityId());
        assertEquals(action, event.action());
        assertEquals(details, event.details());
        assertNull(event.ipAddress());
        assertNull(event.userAgent());
        assertNull(event.sessionId());
        assertTrue(event.success());
        assertNull(event.errorMessage());
        assertNotNull(event.timestamp());
        assertTrue(event.timestamp().isAfter(beforeCreation) || event.timestamp().equals(beforeCreation));
        assertTrue(event.timestamp().isBefore(afterCreation) || event.timestamp().equals(afterCreation));
    }

    @Test
    void constructorHandlesNullValues() {
        AuditEvent event = new AuditEvent(null, null, null, null, null, null, 
                                         null, null, null, false, "Error occurred");

        assertNull(event.id());
        assertNull(event.eventType());
        assertNull(event.userId());
        assertNull(event.entityType());
        assertNull(event.entityId());
        assertNull(event.action());
        assertNull(event.details());
        assertNull(event.ipAddress());
        assertNull(event.userAgent());
        assertNull(event.sessionId());
        assertFalse(event.success());
        assertEquals("Error occurred", event.errorMessage());
        assertNotNull(event.timestamp());
    }

    @Test
    void constructorWithFailureCase() {
        String eventType = "LOGIN";
        String userId = "user123";
        String entityType = "user";
        String entityId = "user123";
        String action = "login";
        String details = "Failed login attempt";
        boolean success = false;

        AuditEvent event = new AuditEvent(eventType, userId, entityType, entityId, 
                                         action, details, success);

        assertEquals(eventType, event.eventType());
        assertEquals(userId, event.userId());
        assertEquals(entityType, event.entityType());
        assertEquals(entityId, event.entityId());
        assertEquals(action, event.action());
        assertEquals(details, event.details());
        assertFalse(event.success());
    }

    @Test
    void eventEqualityAndHashCode() {
        Instant timestamp = Instant.now();
        AuditEvent event1 = new AuditEvent(1L, "LOGIN", "user123", "user", "user123", 
                                          "login", "Successful login", "192.168.1.1", 
                                          "Mozilla/5.0", "sess123", true, null, timestamp);
        AuditEvent event2 = new AuditEvent(1L, "LOGIN", "user123", "user", "user123", 
                                          "login", "Successful login", "192.168.1.1", 
                                          "Mozilla/5.0", "sess123", true, null, timestamp);
        AuditEvent event3 = new AuditEvent(2L, "LOGOUT", "user456", "user", "user456", 
                                          "logout", "Successful logout", "192.168.1.2", 
                                          "Chrome", "sess456", true, null, timestamp);

        assertEquals(event1, event2);
        assertEquals(event1.hashCode(), event2.hashCode());
        assertNotEquals(event1, event3);
        assertNotEquals(event1.hashCode(), event3.hashCode());
    }

    @Test
    void eventToString() {
        AuditEvent event = new AuditEvent(1L, "LOGIN", "user123", "user", "user123", 
                                         "login", "Successful login", "192.168.1.1", 
                                         "Mozilla/5.0", "sess123", true, null, Instant.now());

        String toString = event.toString();
        assertTrue(toString.contains("LOGIN"));
        assertTrue(toString.contains("user123"));
        assertTrue(toString.contains("login"));
        assertTrue(toString.contains("Successful login"));
        assertTrue(toString.contains("192.168.1.1"));
    }

    @Test
    void eventWithEmptyStrings() {
        AuditEvent event = new AuditEvent("", "", "", "", "", "", "", "", "", false, "");

        assertEquals("", event.eventType());
        assertEquals("", event.userId());
        assertEquals("", event.entityType());
        assertEquals("", event.entityId());
        assertEquals("", event.action());
        assertEquals("", event.details());
        assertEquals("", event.ipAddress());
        assertEquals("", event.userAgent());
        assertEquals("", event.sessionId());
        assertEquals("", event.errorMessage());
        assertFalse(event.success());
        assertNotNull(event.timestamp());
    }

    @Test
    void eventWithLongStrings() {
        String longString = "a".repeat(1000);
        AuditEvent event = new AuditEvent(longString, longString, longString, longString, 
                                         longString, longString, longString, longString, 
                                         longString, true, longString);

        assertEquals(longString, event.eventType());
        assertEquals(longString, event.userId());
        assertEquals(longString, event.entityType());
        assertEquals(longString, event.entityId());
        assertEquals(longString, event.action());
        assertEquals(longString, event.details());
        assertEquals(longString, event.ipAddress());
        assertEquals(longString, event.userAgent());
        assertEquals(longString, event.sessionId());
        assertEquals(longString, event.errorMessage());
        assertTrue(event.success());
    }

    @Test
    void eventWithSpecialCharacters() {
        String specialChars = "!@#$%^&*()_+-=[]{}|;':\",./<>?`~";
        AuditEvent event = new AuditEvent(specialChars, specialChars, specialChars, specialChars, 
                                         specialChars, specialChars, specialChars, specialChars, 
                                         specialChars, true, specialChars);

        assertEquals(specialChars, event.eventType());
        assertEquals(specialChars, event.userId());
        assertEquals(specialChars, event.entityType());
        assertEquals(specialChars, event.entityId());
        assertEquals(specialChars, event.action());
        assertEquals(specialChars, event.details());
        assertEquals(specialChars, event.ipAddress());
        assertEquals(specialChars, event.userAgent());
        assertEquals(specialChars, event.sessionId());
        assertEquals(specialChars, event.errorMessage());
    }
}