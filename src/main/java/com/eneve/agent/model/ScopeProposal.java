package com.eneve.agent.model;

import java.time.Instant;

/**
 * Mirrors a row from {@code scope_item_proposals}.
 * A proposal is an AI-generated rewrite of a Jira issue that lives only in the
 * database until a user explicitly accepts it (which then pushes it to Jira).
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
        /** DRAFT | ACCEPTED | REJECTED */
        String status,
        /** Jira key created/updated when accepted; null otherwise. */
        String jiraResultKey,
        Instant createdAt,
        Instant updatedAt
) {}
