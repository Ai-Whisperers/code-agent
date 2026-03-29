package com.eneve.agent.audit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Service layer for audit functionality.
 * Provides high-level operations for audit event management.
 */
@ApplicationScoped
public class AuditService {

    private static final Logger LOG = Logger.getLogger(AuditService.class);

    @Inject
    AuditStore auditStore;

    /**
     * Records an audit event.
     */
    public void recordEvent(AuditEvent event) {
        try {
            auditStore.save(event);
            LOG.debugf("Recorded audit event: %s by user %s on %s/%s", 
                      event.action(), event.userId(), event.entityType(), event.entityId());
        } catch (Exception e) {
            LOG.errorf("Failed to record audit event: %s", e.getMessage());
            // Don't rethrow - audit failures shouldn't break business logic
        }
    }

    /**
     * Records a successful action audit event.
     */
    public void recordSuccess(String eventType, String userId, String entityType, 
                             String entityId, String action, String details) {
        AuditEvent event = new AuditEvent(eventType, userId, entityType, entityId, 
                                         action, details, true);
        recordEvent(event);
    }

    /**
     * Records a failed action audit event.
     */
    public void recordFailure(String eventType, String userId, String entityType, 
                             String entityId, String action, String details, String errorMessage) {
        AuditEvent event = new AuditEvent(null, eventType, userId, entityType, entityId, 
                                         action, details, null, null, null, false, errorMessage, Instant.now());
        recordEvent(event);
    }

    /**
     * Records a system event (no specific user).
     */
    public void recordSystemEvent(AuditEventType eventType, String entityType, 
                                 String entityId, String action, String details) {
        AuditEvent event = new AuditEvent(eventType.name(), "system", entityType, entityId, 
                                         action, details, true);
        recordEvent(event);
    }

    /**
     * Records a security event with IP and user agent information.
     */
    public void recordSecurityEvent(AuditEventType eventType, String userId, String action, 
                                   String details, String ipAddress, String userAgent, 
                                   String sessionId, boolean success, String errorMessage) {
        AuditEvent event = new AuditEvent(null, eventType.name(), userId, "security", null, 
                                         action, details, ipAddress, userAgent, sessionId, 
                                         success, errorMessage, Instant.now());
        recordEvent(event);
    }

    /**
     * Gets audit trail for a specific entity.
     */
    public List<AuditEvent> getEntityAuditTrail(String entityType, String entityId) {
        try {
            return auditStore.findByEntity(entityType, entityId);
        } catch (Exception e) {
            LOG.errorf("Failed to get audit trail for %s/%s: %s", entityType, entityId, e.getMessage());
            throw e;
        }
    }

    /**
     * Gets user activity history.
     */
    public List<AuditEvent> getUserActivity(String userId, int limit, int offset) {
        try {
            return auditStore.findByUserId(userId, limit, offset);
        } catch (Exception e) {
            LOG.errorf("Failed to get user activity for %s: %s", userId, e.getMessage());
            throw e;
        }
    }

    /**
     * Gets events by type with time filtering.
     */
    public List<AuditEvent> getEventsByType(AuditEventType eventType, Instant from, 
                                           Instant to, int limit, int offset) {
        try {
            return auditStore.findByEventType(eventType.name(), from, to, limit, offset);
        } catch (Exception e) {
            LOG.errorf("Failed to get events by type %s: %s", eventType, e.getMessage());
            throw e;
        }
    }

    /**
     * Gets recent audit events with optional filtering.
     */
    public List<AuditEvent> getRecentEvents(int limit, int offset, AuditEventType eventType, 
                                          String userId, Instant from, Instant to) {
        try {
            String eventTypeName = eventType != null ? eventType.name() : null;
            return auditStore.getRecentEvents(limit, offset, eventTypeName, userId, from, to);
        } catch (Exception e) {
            LOG.errorf("Failed to get recent audit events: %s", e.getMessage());
            throw e;
        }
    }

    /**
     * Gets audit statistics for the given time period.
     */
    public Map<String, Object> getStatistics(Instant from, Instant to) {
        try {
            return auditStore.getStatistics(from, to);
        } catch (Exception e) {
            LOG.errorf("Failed to get audit statistics: %s", e.getMessage());
            throw e;
        }
    }

    /**
     * Gets event type counts for reporting and dashboards.
     */
    public Map<String, Long> getEventTypeCounts(Instant from, Instant to) {
        try {
            return auditStore.getEventTypeCounts(from, to);
        } catch (Exception e) {
            LOG.errorf("Failed to get event type counts: %s", e.getMessage());
            throw e;
        }
    }

    /**
     * Records a job lifecycle event.
     */
    public void recordJobEvent(AuditEventType eventType, String userId, String jobId, 
                              String action, String details) {
        recordSuccess(eventType.name(), userId, "job", jobId, action, details);
    }

    /**
     * Records a repository operation event.
     */
    public void recordRepoEvent(AuditEventType eventType, String userId, String repoId, 
                               String action, String details) {
        recordSuccess(eventType.name(), userId, "repository", repoId, action, details);
    }

    /**
     * Records an AI call event.
     */
    public void recordAiCallEvent(String userId, String jobId, String model, 
                                 String action, String details) {
        recordSuccess(AuditEventType.AI_CALL_MADE.name(), userId, "ai_call", jobId, action, 
                     String.format("Model: %s, %s", model, details));
    }

    /**
     * Records a tool execution event.
     */
    public void recordToolEvent(String userId, String jobId, String toolName, 
                               String action, String details, boolean success, String errorMessage) {
        if (success) {
            recordSuccess(AuditEventType.TOOL_EXECUTED.name(), userId, "tool", jobId, action, 
                         String.format("Tool: %s, %s", toolName, details));
        } else {
            recordFailure(AuditEventType.TOOL_EXECUTED.name(), userId, "tool", jobId, action,
                         String.format("Tool: %s, %s", toolName, details), errorMessage);
        }
    }

    /**
     * Records a configuration change event.
     */
    public void recordConfigChange(String userId, String configKey, String oldValue, 
                                  String newValue, String details) {
        String changeDetails = String.format("Config: %s, Old: %s, New: %s, %s", 
                                            configKey, oldValue, newValue, details);
        recordSuccess(AuditEventType.CONFIG_CHANGED.name(), userId, "configuration", 
                     configKey, "update", changeDetails);
    }

    /**
     * Records data access event for sensitive operations.
     */
    public void recordDataAccess(String userId, String entityType, String entityId, 
                                String action, String details) {
        recordSuccess(AuditEventType.DATA_ACCESSED.name(), userId, entityType, 
                     entityId, action, details);
    }
}