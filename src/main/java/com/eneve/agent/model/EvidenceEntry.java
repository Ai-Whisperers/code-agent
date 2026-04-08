package com.eneve.agent.model;

import java.time.Instant;

/**
 * A single entry in the immutable audit trail for a job, surfaced in the Evidence tab.
 */
public record EvidenceEntry(
        Instant timestamp,
        String actor,
        String action,
        String detail
) {}
