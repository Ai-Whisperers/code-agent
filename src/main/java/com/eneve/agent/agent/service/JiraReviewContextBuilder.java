package com.eneve.agent.agent.service;

import com.eneve.agent.jira.JiraService;
import com.eneve.agent.jira.JiraService.JiraIssueDetail;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Assembles enriched markdown context blocks from Jira issue data for injection
 * into AI review prompts. Uses system (application-level) Jira credentials — no
 * user-linked accounts required.
 *
 * <p>Truncation is applied at each level to keep prompt size manageable:
 * <ul>
 *   <li>Issue description: up to 2 000 chars</li>
 *   <li>Each comment: up to 300 chars</li>
 *   <li>Max 10 comments per issue</li>
 *   <li>Parent description: up to 1 500 chars</li>
 *   <li>Grandparent description: up to 1 000 chars</li>
 * </ul>
 */
@ApplicationScoped
public class JiraReviewContextBuilder {

    private static final Logger LOG = Logger.getLogger(JiraReviewContextBuilder.class);

    private static final int DESC_MAX = 2000;
    private static final int COMMENT_MAX = 300;
    private static final int COMMENTS_COUNT = 10;
    private static final int PARENT_DESC_MAX = 1500;
    private static final int GRANDPARENT_DESC_MAX = 1000;

    @Inject
    JiraService jiraService;

    /**
     * Build context for a Jira Epic review.
     *
     * @param issueKey the epic key (e.g. "PROJ-42")
     * @return markdown string to be injected as {jira_context}
     */
    public String buildEpicContext(String issueKey) {
        return buildEpicContext(issueKey, -1);
    }

    /**
     * Build context for a Jira Epic review, including the number of child features
     * currently stored in the roadmap. When {@code featureCount == 0} the context
     * block includes an explicit warning that the epic has no features and therefore
     * cannot be considered delivery-ready.
     *
     * @param issueKey     the epic key (e.g. "PROJ-42")
     * @param featureCount number of child features in the roadmap, or {@code -1} if unknown
     * @return markdown string to be injected as {jira_context}
     */
    public String buildEpicContext(String issueKey, int featureCount) {
        JiraIssueDetail detail = jiraService.fetchIssueDetail(issueKey);
        if (detail == null) {
            LOG.warnf("JiraReviewContextBuilder: could not fetch detail for epic %s", issueKey);
            return "No Jira data available for " + issueKey + ".";
        }
        StringBuilder sb = new StringBuilder(buildIssueBlock("Epic", detail, DESC_MAX));
        if (featureCount >= 0) {
            sb.append(buildChildCountSection("Feature", "features", featureCount));
        }
        return sb.toString();
    }

    /**
     * Build context for a Jira Feature review. Includes the parent epic description.
     *
     * @param issueKey      the feature key
     * @param parentEpicKey the parent epic key (may be null if unknown)
     * @return markdown string to be injected as {jira_context}
     */
    public String buildFeatureContext(String issueKey, String parentEpicKey) {
        return buildFeatureContext(issueKey, parentEpicKey, -1);
    }

    /**
     * Build context for a Jira Feature review, including the number of child user stories
     * stored in the roadmap. When {@code storyCount == 0} the context block includes an
     * explicit warning that the feature has no user stories and therefore cannot be
     * considered delivery-ready.
     *
     * @param issueKey      the feature key
     * @param parentEpicKey the parent epic key (may be null if unknown)
     * @param storyCount    number of child user stories in the roadmap, or {@code -1} if unknown
     * @return markdown string to be injected as {jira_context}
     */
    public String buildFeatureContext(String issueKey, String parentEpicKey, int storyCount) {
        JiraIssueDetail detail = jiraService.fetchIssueDetail(issueKey);
        if (detail == null) {
            LOG.warnf("JiraReviewContextBuilder: could not fetch detail for feature %s", issueKey);
            return "No Jira data available for " + issueKey + ".";
        }
        StringBuilder sb = new StringBuilder(buildIssueBlock("Feature", detail, DESC_MAX));

        if (parentEpicKey != null && !parentEpicKey.isBlank()) {
            JiraIssueDetail epic = jiraService.fetchIssueDetail(parentEpicKey);
            if (epic != null) {
                sb.append("\n\n## Parent Epic Context: ").append(epic.key())
                  .append(" — ").append(epic.summary()).append("\n");
                sb.append(truncate(epic.description(), PARENT_DESC_MAX));
            }
        }
        if (storyCount >= 0) {
            sb.append(buildChildCountSection("User Story", "user stories", storyCount));
        }
        return sb.toString();
    }

    /**
     * Build context for a Jira User Story review. Includes parent feature description
     * and grandparent epic description.
     *
     * @param issueKey          the user story key
     * @param parentFeatureKey  the parent feature key (may be null)
     * @param grandparentEpicKey the grandparent epic key (may be null)
     * @return markdown string to be injected as {jira_context}
     */
    public String buildUserStoryContext(String issueKey, String parentFeatureKey, String grandparentEpicKey) {
        JiraIssueDetail detail = jiraService.fetchIssueDetail(issueKey);
        if (detail == null) {
            LOG.warnf("JiraReviewContextBuilder: could not fetch detail for story %s", issueKey);
            return "No Jira data available for " + issueKey + ".";
        }
        StringBuilder sb = new StringBuilder(buildIssueBlock("User Story", detail, DESC_MAX));

        if (parentFeatureKey != null && !parentFeatureKey.isBlank()) {
            JiraIssueDetail feature = jiraService.fetchIssueDetail(parentFeatureKey);
            if (feature != null) {
                sb.append("\n\n## Parent Feature Context: ").append(feature.key())
                  .append(" — ").append(feature.summary()).append("\n");
                sb.append(truncate(feature.description(), PARENT_DESC_MAX));
            }
        }

        if (grandparentEpicKey != null && !grandparentEpicKey.isBlank()) {
            JiraIssueDetail epic = jiraService.fetchIssueDetail(grandparentEpicKey);
            if (epic != null) {
                sb.append("\n\n## Grandparent Epic Context: ").append(epic.key())
                  .append(" — ").append(epic.summary()).append("\n");
                sb.append(truncate(epic.description(), GRANDPARENT_DESC_MAX));
            }
        }
        return sb.toString();
    }

    // ─── Private helpers ──────────────────────────────────────────────────

    private String buildIssueBlock(String issueTypeName, JiraIssueDetail detail, int descMax) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Jira ").append(issueTypeName).append(": ")
          .append(detail.key()).append(" — ").append(detail.summary()).append("\n");
        sb.append("**Status:** ").append(nullToEmpty(detail.status())).append("  \n");
        sb.append("**Reporter:** ").append(nullToEmpty(detail.reporter())).append("  \n");
        sb.append("**Assignee:** ").append(nullToEmpty(detail.assignee())).append("  \n");
        if (detail.labels() != null && !detail.labels().isEmpty()) {
            sb.append("**Labels:** ").append(String.join(", ", detail.labels())).append("  \n");
        }
        sb.append("\n### Description\n");
        String desc = detail.description();
        if (desc == null || desc.isBlank()) {
            sb.append("_No description provided._\n");
        } else {
            sb.append(truncate(desc, descMax)).append("\n");
        }

        List<String> comments = detail.comments();
        if (comments != null && !comments.isEmpty()) {
            sb.append("\n### Comments\n");
            int count = Math.min(comments.size(), COMMENTS_COUNT);
            for (int i = 0; i < count; i++) {
                sb.append("- ").append(truncate(comments.get(i), COMMENT_MAX)).append("\n");
            }
            if (comments.size() > COMMENTS_COUNT) {
                sb.append("_(").append(comments.size() - COMMENTS_COUNT).append(" more comments not shown)_\n");
            }
        }

        if (detail.attachments() != null && !detail.attachments().isEmpty()) {
            sb.append("\n### Attachments\n");
            for (var att : detail.attachments()) {
                sb.append("- ").append(att.filename())
                  .append(" (").append(att.mimeType()).append(", ").append(att.size()).append(" bytes)\n");
            }
        }
        return sb.toString();
    }

    private static String buildChildCountSection(String singularLabel, String pluralLabel, int count) {
        if (count == 0) {
            return "\n\n## Structural Constraint\n"
                    + "**" + singularLabel + " count in roadmap: 0**  \n"
                    + "> WARNING: This item has NO child " + pluralLabel + " in the roadmap. "
                    + "It has not been decomposed. The readiness_score MUST NOT exceed 30 "
                    + "and the decomposition_readiness score MUST NOT exceed 10.\n";
        }
        return "\n\n## Structural Information\n"
                + "**" + singularLabel + " count in roadmap: " + count + "**\n";
    }

    private static String truncate(String text, int max) {
        if (text == null || text.isEmpty()) return "";
        return text.length() <= max ? text : text.substring(0, max) + " ...[truncated]";
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }
}
