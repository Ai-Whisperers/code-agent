package com.eneve.agent.agent;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.eneve.agent.tools.GuardrailConfig;
import com.eneve.agent.workspace.WorkspaceContext;

/**
 * Shared workspace helpers: git author configuration, diff-stat counting,
 * guardrail checks, and branch-name slugification.
 */
@ApplicationScoped
public class GitWorkspaceHelper {

    @Inject GuardrailConfig guardrails;

    @ConfigProperty(name = "git.author.name", defaultValue = "code-agent")
    String gitAuthorName;

    @ConfigProperty(name = "git.author.email", defaultValue = "")
    String gitAuthorEmail;

    public record DiffStats(int filesChanged, int linesChanged) {}

    public void configureGitIfNeeded(WorkspaceContext workspace) throws Exception {
        if (!gitAuthorEmail.isBlank()) {
            workspace.configureAuthor(gitAuthorName, gitAuthorEmail);
        }
    }

    public DiffStats countChanges(WorkspaceContext workspace) {
        try {
            return new DiffStats(workspace.countFilesChanged(), workspace.countLinesChanged());
        } catch (Exception e) {
            return new DiffStats(0, 0);
        }
    }

    /**
     * Returns a human-readable violation message when the diff exceeds guardrail limits,
     * or {@code null} if the diff is within bounds.
     */
    public String checkGuardrails(DiffStats stats) {
        if (stats.filesChanged() > guardrails.getMaxFilesChanged()) {
            return "Too many files changed: " + stats.filesChanged()
                    + " (max: " + guardrails.getMaxFilesChanged() + ")";
        }
        if (stats.linesChanged() > guardrails.getMaxLinesChanged()) {
            return "Too many lines changed: " + stats.linesChanged()
                    + " (max: " + guardrails.getMaxLinesChanged() + ")";
        }
        return null;
    }

    public static String slugify(String text) {
        if (text == null || text.isBlank()) return "fix";
        String slug = text.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        if (slug.length() > 60) {
            slug = slug.substring(0, 60).replaceAll("-$", "");
        }
        return slug;
    }
}
