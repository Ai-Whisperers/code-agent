package com.eneve.agent.model;

/**
 * Represents a single open pull request / merge request fetched directly from the SCM.
 * The {@code jobId} field is populated on a best-effort basis when an agent job
 * is linked to this PR.
 * The {@code soc2} flag is {@code true} when the linked agent job is SOC II–applicable
 * (i.e. its Jira issue type matches the configured bug-issue-types).
 */
public record OpenPrEntry(
        String workspace,
        String repoSlug,
        String prId,
        String prUrl,
        String title,
        String sourceBranch,
        String targetBranch,
        String author,
        String createdOn,
        String updatedOn,
        String jobId,
        String status,
        boolean soc2
) {}
