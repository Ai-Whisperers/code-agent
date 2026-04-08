package com.eneve.agent.scope;

import com.eneve.agent.jira.JiraService;
import com.eneve.agent.model.ScopeProposal;
import com.eneve.agent.model.ScopeRecord;

import java.util.List;

/**
 * Shared exception types and result records for the Scope feature.
 *
 * <p>These were previously nested inside {@code ScopeService} and are extracted
 * here so that the three focused service classes and their resource counterparts
 * can all import them from a single location.
 */
public final class ScopeExceptions {

    private ScopeExceptions() {}

    // ─── Exception types ─────────────────────────────────────────────────────

    public static final class ScopeNotFoundException extends RuntimeException {
        public ScopeNotFoundException(String id) { super("Scope not found: " + id); }
    }

    public static final class ItemOverriddenException extends RuntimeException {
        public ItemOverriddenException(String issueKey) {
            super("Item is overridden (ACCEPTED/REMOVED). Clear override to re-enable reviews: " + issueKey);
        }
    }

    public static final class ActiveJobExistsException extends RuntimeException {
        public ActiveJobExistsException(String issueKey) {
            super("A review job for this item is already active: " + issueKey);
        }
    }

    public static final class JiraIssueNotFoundException extends RuntimeException {
        public JiraIssueNotFoundException(String issueKey) {
            super("Jira issue not found in scope_items (sync first): " + issueKey);
        }
    }

    public static final class ProposalNotFoundException extends RuntimeException {
        public ProposalNotFoundException(String proposalId) {
            super("Proposal not found: " + proposalId);
        }
    }

    public static final class ImprovementGenerationException extends RuntimeException {
        public ImprovementGenerationException(String message) { super(message); }
    }

    // ─── Result records ───────────────────────────────────────────────────────

    public record CreateScopeResult(ScopeRecord scope, int itemsSynced) {}

    /**
     * @param jobsEnqueued  items queued for AI review this call
     * @param jobsSkipped   items skipped due to active job or override
     * @param jobsUnchanged items skipped because Jira was not modified since last review
     */
    public record ReviewAllResult(int jobsEnqueued, int jobsSkipped, int jobsUnchanged) {}

    /**
     * Result of {@code initProposal}: the existing/created DRAFT, Jira attachments,
     * and the Jira issue's own last-modified timestamp.
     */
    public record InitProposalResult(
            ScopeProposal proposal,
            List<JiraService.JiraAttachment> attachments,
            java.time.Instant jiraUpdatedAt) {}
}
