package com.eneve.agent.agent;

import com.eneve.agent.settings.SettingsService;

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
    @Inject SettingsService settings;

    public record DiffStats(int filesChanged, int linesChanged) {}

    public void configureGitIfNeeded(WorkspaceContext workspace) throws Exception {
        String email = settings.get("git.author.email", "");
        if (!email.isBlank()) {
            workspace.configureAuthor(settings.get("git.author.name", "code-agent"), email);
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
