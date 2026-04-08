package com.eneve.agent.model;

import java.time.Instant;
import java.util.List;

/**
 * Mirrors the {@code scopes} table combined with the {@code scope_labels} join table.
 *
 * <p>The three {@code *Issuetype} fields store the Jira issue-type names used
 * when syncing this scope. They default to the global settings values but can
 * be overridden per-scope to support projects with non-standard naming
 * conventions (e.g. "User Story" instead of "Story").
 *
 * <p>{@code labels} is the ordered list of Jira labels from the {@code scope_labels}
 * join table. It replaces the legacy single {@code label} column.
 */
public record ScopeRecord(
        String id,
        String name,
        List<String> labels,
        String epicIssuetype,
        String featureIssuetype,
        String userstoryIssuetype,
        Instant createdAt,
        /** {@code "po"} for product/roadmap scopes, {@code "qa"} for QA test-plan scopes. */
        String scopeType,
        /** Optional Jira project key used to import ETR test cases (e.g. {@code "ETR"}). */
        String etrProjectKey
) {
    /** Convenience accessor — returns the first label, or empty string if none. */
    public String primaryLabel() {
        return labels != null && !labels.isEmpty() ? labels.get(0) : "";
    }
}
