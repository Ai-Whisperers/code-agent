package com.eneve.agent.model;

import java.util.List;

/**
 * Security summary for a single repository (or container image) within a product.
 *
 * <p>{@code containers} lists distinct container image names found in the issues for this repo.
 * <p>Counts are split by origin: software issues (SCA, SAST, dependency, open-source) vs
 * container image issues so callers can distinguish actionable code fixes from OS-layer rebuilds.
 */
public record RepoSecuritySummary(
        String repoSlug,
        List<String> containers,
        int criticalCount,
        int highCount,
        int softwareCriticalCount,
        int softwareHighCount,
        int containerCriticalCount,
        int containerHighCount,
        List<SecurityIssueRow> issues
) {}
