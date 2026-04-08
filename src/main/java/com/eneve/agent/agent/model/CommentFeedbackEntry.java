package com.eneve.agent.agent.model;

import java.time.Instant;

/**
 * A piece of developer feedback on a single agent review finding.
 * Stored in {@code comment_feedback} and used to measure false-positive
 * rate and drive auto-suppression of recurring noise patterns.
 */
public record CommentFeedbackEntry(
        Long id,
        long commentId,
        String prId,
        String workspace,
        String repoSlug,
        String feedback,
        String category,
        String pattern,
        String createdBy,
        Instant createdAt
) {

    /** Normalise a finding description into a short, comparable pattern key. */
    public static String normalisePattern(String category, String findingText) {
        if (findingText == null) return "";
        String text = findingText.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ").trim();
        String prefix = (category != null ? category.toLowerCase(java.util.Locale.ROOT) + ": " : "");
        String trimmed = prefix + text;
        return trimmed.length() > 120 ? trimmed.substring(0, 120) : trimmed;
    }

    public static CommentFeedbackEntry falsePositive(long commentId, String prId,
                                                     String workspace, String repoSlug,
                                                     String category, String findingText,
                                                     String createdBy) {
        return new CommentFeedbackEntry(null, commentId, prId, workspace, repoSlug,
                "false_positive", category,
                normalisePattern(category, findingText),
                createdBy, Instant.now());
    }
}
