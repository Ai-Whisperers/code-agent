package com.eneve.agent.webhooks;

import com.eneve.agent.agent.IntentClassifier;
import com.eneve.agent.agent.JobQueue;
import com.eneve.agent.agent.model.CommentContext;
import com.eneve.agent.agent.model.CommentFeedbackEntry;
import com.eneve.agent.agent.model.MemoryEntry;
import com.eneve.agent.agent.store.CommentFeedbackStore;
import com.eneve.agent.agent.store.CommentStore;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.agent.store.MemoryStore;
import com.eneve.agent.agent.store.RepoSettingsStore;
import com.eneve.agent.model.GenerateTestsRequest;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobType;
import com.eneve.agent.model.ReplyCommentRequest;
import com.eneve.agent.scm.GitPlatformService;
import com.eneve.agent.settings.SettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shared logic for the three full-featured PR comment webhook resources
 * (GitHub, GitLab, Bitbucket).
 *
 * <p>Each concrete subclass handles only its platform-specific payload parsing
 * and the {@code agentUser()} setting key; all command handlers and job-submission
 * logic live here.
 *
 * <p>The Azure DevOps comment webhook is intentionally excluded because it has a
 * significantly smaller feature set (no {@code /learn}, {@code /fp}, or
 * {@code /generate-tests} support).
 */
public abstract class AbstractCommentWebhookHandler {

    private static final Logger LOG = Logger.getLogger(AbstractCommentWebhookHandler.class);

    @Inject protected JobQueue jobQueue;
    @Inject protected JobStore jobStore;
    @Inject protected CommentStore commentStore;
    @Inject protected CommentFeedbackStore feedbackStore;
    @Inject protected IntentClassifier intentClassifier;
    @Inject protected MemoryStore memoryStore;
    @Inject protected RepoSettingsStore repoSettingsStore;
    @Inject protected GitPlatformService platformService;
    @Inject protected SettingsService settings;
    @Inject protected ObjectMapper objectMapper;

    // ─── Abstract contract ────────────────────────────────────────────────

    /**
     * Settings key whose value holds the agent bot username for this platform,
     * e.g. {@code "github.agent.user"} or {@code "gitlab.agent.user"}.
     */
    protected abstract String agentUserSettingKey();

    // ─── Shared helpers ───────────────────────────────────────────────────

    protected String agentUser() {
        return settings.get(agentUserSettingKey(), "");
    }

    protected Response submitJob(String repoUrl, String prId,
                                 long parentCommentId, String humanMessage,
                                 String filePath, int line, JobType jobType) {
        ReplyCommentRequest request = new ReplyCommentRequest(
                repoUrl, prId, parentCommentId, humanMessage, filePath, line);

        String jobId = UUID.randomUUID().toString();
        JobRecord job = new JobRecord(jobId, request, jobType);
        jobStore.put(job);

        if (!jobQueue.submit(job)) {
            return Response.status(429)
                    .entity(Map.of("action", "rejected", "reason", "Job queue is full"))
                    .build();
        }

        String action = (jobType == JobType.FIX_COMMENT) ? "fix_triggered" : "reply_triggered";
        LOG.infof("Comment webhook triggered %s job %s for comment %d on PR #%s",
                jobType, jobId, parentCommentId, prId);
        return Response.ok(Map.of(
                "action", action,
                "jobId", jobId,
                "jobType", jobType.name(),
                "parentCommentId", parentCommentId,
                "prId", prId
        )).build();
    }

    protected Response handleGenerateTestsCommand(String repoUrl, String prId,
                                                  long parentCommentId,
                                                  String org, String repo) {
        String branchName = "agent/tests/pr-" + prId;
        GenerateTestsRequest request = new GenerateTestsRequest(
                repoUrl, branchName, null, null, null, null, null, null, null);

        String jobId = UUID.randomUUID().toString();
        JobRecord job = new JobRecord(jobId, request);
        jobStore.put(job);

        if (!jobQueue.submit(job)) {
            return Response.status(429)
                    .entity(Map.of("action", "rejected", "reason", "Job queue is full"))
                    .build();
        }

        LOG.infof("/generate-tests triggered job %s for PR #%s (%s/%s)", jobId, prId, org, repo);

        try {
            platformService.replyToComment(org, "", repo, prId, parentCommentId,
                    "Generating unit tests — a pull request with the generated tests will be created shortly. Job ID: `" + jobId + "`");
        } catch (Exception e) {
            LOG.warnf("Failed to post /generate-tests confirmation reply (non-fatal): %s", e.getMessage());
        }

        return Response.ok(Map.of(
                "action", "generate_tests_triggered",
                "jobId", jobId,
                "branch", branchName,
                "prId", prId
        )).build();
    }

    protected Response handleLearnCommand(String commentBody, String org, String repo,
                                          String repoUrl, String prId, long parentCommentId,
                                          String author) {
        String learning = commentBody.substring("/learn ".length()).trim();
        if (learning.isBlank()) {
            return ok("ignored", "/learn command with empty text");
        }

        if (memoryStore.exists(org, repo, learning)) {
            LOG.debugf("Duplicate /learn ignored for %s/%s: %s", org, repo, learning);
            return ok("duplicate", "This preference is already stored");
        }

        MemoryEntry entry = MemoryEntry.explicit(org, repo, learning, author);
        memoryStore.save(entry);

        LOG.infof("/learn command from %s on %s/%s PR #%s: %s", author, org, repo, prId, learning);

        try {
            platformService.replyToComment(org, "", repo, prId, parentCommentId,
                    "Noted — I'll remember this for future reviews of this repository:\n\n> " + learning);
        } catch (Exception e) {
            LOG.warnf("Failed to post /learn confirmation reply (non-fatal): %s", e.getMessage());
        }

        return Response.ok(Map.of(
                "action", "learning_stored",
                "memory", learning,
                "org", org,
                "repo", repo
        )).build();
    }

    protected Response handleFalsePositiveCommand(String org, String repo,
                                                   String prId, long parentCommentId,
                                                   String author) {
        CommentContext ctx = commentStore.find(parentCommentId).orElse(null);
        if (ctx == null) {
            LOG.warnf("/fp: could not find CommentContext for comment %d", parentCommentId);
            return ok("ignored", "Could not find original finding for comment " + parentCommentId);
        }

        CommentFeedbackEntry feedback = CommentFeedbackEntry.falsePositive(
                parentCommentId, prId, org, repo,
                ctx.category(), ctx.findingText(), author);
        feedbackStore.save(feedback);

        commentStore.markResolved(parentCommentId, author);

        try {
            platformService.resolveComment(org, "", repo, prId, parentCommentId);
        } catch (Exception e) {
            LOG.warnf("Failed to resolve comment %d on platform (non-fatal): %s", parentCommentId, e.getMessage());
        }

        try {
            platformService.replyToComment(org, "", repo, prId, parentCommentId,
                    "Got it \u2014 marked as false positive. I'll be more careful about this pattern in future reviews.");
        } catch (Exception e) {
            LOG.warnf("Failed to post /fp confirmation reply (non-fatal): %s", e.getMessage());
        }

        checkAndAutoSuppress(org, repo, feedback.pattern(), author);

        LOG.infof("/fp from %s on %s/%s PR #%s, comment %d: %s",
                author, org, repo, prId, parentCommentId,
                ctx.findingText() != null ? ctx.findingText().substring(0, Math.min(80, ctx.findingText().length())) : "");

        return Response.ok(Map.of(
                "action", "false_positive_recorded",
                "commentId", parentCommentId,
                "org", org,
                "repo", repo
        )).build();
    }

    private void checkAndAutoSuppress(String org, String repo, String pattern, String author) {
        if (pattern == null || pattern.isBlank()) return;
        List<String> recurring = feedbackStore.findRecurringPatterns(
                org, repo, Integer.parseInt(settings.get("review.fp.auto-suppress-threshold", "3")));
        if (!recurring.contains(pattern)) return;

        String memoryText = "Do not flag findings matching this pattern — the team has repeatedly marked it as a false positive: " + pattern;
        if (!memoryStore.exists(org, repo, memoryText)) {
            MemoryEntry entry = MemoryEntry.explicit(org, repo, memoryText, "auto-suppress");
            memoryStore.save(entry);
            LOG.infof("Auto-suppressed recurring FP pattern for %s/%s: %s", org, repo, pattern);
        }
    }

    protected static Response ok(String action, String reason) {
        return Response.ok(Map.of("action", action, "reason", reason)).build();
    }
}
