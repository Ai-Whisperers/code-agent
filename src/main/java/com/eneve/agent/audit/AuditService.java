package com.eneve.agent.audit;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Request-scoped facade for audit logging.
 * Writes are dispatched fire-and-forget on a virtual thread so
 * audit persistence never blocks the originating HTTP request.
 *
 * <p>Usage in resources:
 * <pre>{@code
 *   auditService.log("JOBS", "JOB_SUBMITTED", "job", jobId, Map.of("jobType", "FIX"));
 * }</pre>
 */
@RequestScoped
public class AuditService {

    private static final Logger LOG = Logger.getLogger(AuditService.class);
    @Inject ObjectMapper mapper;

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    AuditStore auditStore;

    /**
     * Logs an auditable action asynchronously.
     *
     * @param category     broad grouping (e.g. {@code "JOBS"}, {@code "SETTINGS"})
     * @param action       specific action name (e.g. {@code "JOB_SUBMITTED"}, {@code "SETTING_CHANGED"})
     * @param resourceType type of the affected resource (e.g. {@code "job"}, {@code "setting"})
     * @param resourceId   identifier of the affected resource (nullable)
     * @param detail       additional context serialised as JSONB (nullable)
     */
    public void log(String category, String action, String resourceType,
                    String resourceId, Map<String, Object> detail) {
        String actor = resolveActor();
        String detailJson = toJson(detail);
        AuditEntry entry = new AuditEntry(null, actor, category, action,
                resourceType, resourceId, detailJson, Instant.now());
        Thread.ofVirtual().name("audit-write-" + category + "-" + action).start(() -> {
            try {
                auditStore.save(entry);
            } catch (Exception e) {
                LOG.warnf("Async audit write failed [%s/%s]: %s", category, action, e.getMessage());
            }
        });
    }

    /** Convenience overload for actions with no extra detail. */
    public void log(String category, String action, String resourceType, String resourceId) {
        log(category, action, resourceType, resourceId, null);
    }

    // ─── Private helpers ────────────────────────────────────────────────

    private String resolveActor() {
        if (securityIdentity == null || securityIdentity.isAnonymous()) {
            return "system";
        }
        return securityIdentity.getPrincipal().getName();
    }

    private String toJson(Map<String, Object> detail) {
        if (detail == null || detail.isEmpty()) return null;
        try {
            return mapper.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
