package com.eneve.agent.audit;

import java.time.Instant;

/**
 * Immutable row projection for the {@code audit_log} table.
 */
public record AuditEntry(
        Long id,
        String actor,
        String category,
        String action,
        String resourceType,
        String resourceId,
        String detail,
        Instant occurredAt
) {}
