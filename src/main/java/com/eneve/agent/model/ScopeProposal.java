package com.eneve.agent.model;

import java.time.Instant;

/**
 * Mirrors a row from {@code scope_item_proposals}.
 * A proposal holds user-edited (or AI-generated) field values for a Jira issue.
 * It stays in the database as a DRAFT until the user explicitly clicks
 * "Accept &amp; Sync to Jira", which is the <em>only</em> operation that writes
 * back to Jira. All other operations (Save, Review, etc.) are read-only with
 * respect to Jira.
 */
public record ScopeProposal(
        String id,
        String scopeId,
        String issueKey,
        String issueType,
        String parentKey,
        String proposedSummary,
        String proposedDescription,
        String proposedCriteria,
        String proposedTechnical,
        String aiExplanation,
        String proposedLabel,
        String proposedPriority,
        /** DRAFT | ACCEPTED | REJECTED */
        String status,
        /** Jira key created/updated when accepted; null otherwise. */
        String jiraResultKey,
        Instant createdAt,
        Instant updatedAt,
        /** Display name of the user who last saved the proposal. */
        String updatedBy,
        /** Display name of the user who synced the proposal to Jira. */
        String syncedBy
) {}
