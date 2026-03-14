package com.eneve.agent.agent;

import java.time.Instant;
import java.util.List;

/**
 * Per-repository configuration stored in the {@code repo_settings} table.
 * Controls whether automated reviews are enabled, which shared rules to load,
 * and an optional custom review prompt template.
 */
public record RepoSettings(
        Long id,
        String workspace,
        String repoSlug,
        boolean reviewEnabled,
        boolean vectorEnabled,
        boolean docsEnabled,
        List<String> ruleNames,
        String reviewPrompt,
        List<String> disabledHooks,
        String confluenceSpaceKey,
        String confluenceParentPageId,
        Instant createdAt,
        Instant updatedAt
) {

    public static RepoSettings defaults(String workspace, String repoSlug) {
        return new RepoSettings(null, workspace, repoSlug, true, false, true,
                List.of(), null, List.of(), null, null, Instant.now(), Instant.now());
    }
}
