package com.eneve.agent.audit;

import java.time.Instant;

/**
 * Represents an audit event in the system.
 * Tracks user actions, system events, and security-related activities.
 */
public record AuditEvent(
        Long id,
        String eventType,
        String userId,
        String entityType,
        String entityId,
        String action,
        String details,
        String ipAddress,
        String userAgent,
        String sessionId,
        boolean success,
        String errorMessage,
        Instant timestamp
) {
    
    public AuditEvent(String eventType, String userId, String entityType, String entityId, 
                     String action, String details, String ipAddress, String userAgent, 
                     String sessionId, boolean success, String errorMessage) {
        this(null, eventType, userId, entityType, entityId, action, details, 
             ipAddress, userAgent, sessionId, success, errorMessage, Instant.now());
    }
    
    public AuditEvent(String eventType, String userId, String entityType, String entityId, 
                     String action, String details, boolean success) {
        this(eventType, userId, entityType, entityId, action, details, 
             null, null, null, success, null);
    }
}