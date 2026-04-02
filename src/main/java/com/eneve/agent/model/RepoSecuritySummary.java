package com.eneve.agent.model;

import java.util.List;

/**
 * Security summary for a single repository (or container image) within a product.
 *
 * <p>{@code containers} lists distinct container image names found in the issues for this repo.
 */
public record RepoSecuritySummary(
        String repoSlug,
        List<String> containers,
        int criticalCount,
        int highCount,
        List<SecurityIssueRow> issues
) {}
