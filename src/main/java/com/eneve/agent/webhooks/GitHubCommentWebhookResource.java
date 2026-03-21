package com.eneve.agent.webhooks;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.eneve.agent.agent.CommentContext;
import com.eneve.agent.agent.CommentFeedbackEntry;
import com.eneve.agent.agent.CommentFeedbackStore;
import com.eneve.agent.agent.CommentIntent;
import com.eneve.agent.agent.CommentStore;
import com.eneve.agent.agent.IntentClassifier;
import com.eneve.agent.agent.JobQueue;
import com.eneve.agent.agent.JobStore;
import com.eneve.agent.agent.MemoryEntry;
import com.eneve.agent.agent.MemoryStore;
import com.eneve.agent.agent.RepoSettingsStore;
import com.eneve.agent.model.GenerateTestsRequest;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobType;
import com.eneve.agent.model.ReplyCommentRequest;
import com.eneve.agent.scm.GitPlatformService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Handles incoming GitHub webhook notifications for pull request comment events.
 * <p>
 * Accepts two GitHub event types at the same endpoint:
 * <ul>
 *   <li>{@code pull_request_review_comment} — inline review comments on a PR diff</li>
 *   <li>{@code issue_comment} — general (non-inline) comments on a PR issue thread</li>
 * </ul>
 * When a developer replies to one of the agent's review comments, triggers a
 * REPLY job so the agent can respond conversationally in-thread.
 * <p>
 * For {@code pull_request_review_comment}, replies are identified via the
 * {@code in_reply_to_id} field. For {@code issue_comment}, the comment body
 * may reference the agent directly and is routed accordingly.
 */
@Path("/webhooks")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Webhooks", description = "Incoming webhook handlers for external integrations")
public class GitHubCommentWebhookResource {

    private static final Logger LOG = Logger.getLogger(GitHubCommentWebhookResource.class);

    @Inject JobQueue jobQueue;
    @Inject JobStore jobStore;
    @Inject CommentStore commentStore;
    @Inject CommentFeedbackStore feedbackStore;
    @Inject IntentClassifier intentClassifier;
    @Inject MemoryStore memoryStore;
    @Inject RepoSettingsStore repoSettingsStore;
    @Inject GitPlatformService platformService;

    @ConfigProperty(name = "github.agent.user", defaultValue = "")
    String agentUser;

    @ConfigProperty(name = "review.fp.auto-suppress-threshold", defaultValue = "3")
    int fpAutoSuppressThreshold;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @POST
    @Path("/github/pull-request-comment")
    @Operation(
            operationId = "githubPrCommentWebhook",
            summary = "Handle GitHub PR comment webhook events",
            description = "Receives GitHub webhook payloads for pull_request_review_comment and issue_comment events. "
                    + "When a developer replies to one of the agent's review comments, triggers an AI-powered "
                    + "conversational reply in the same thread."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Webhook processed"),
            @APIResponse(responseCode = "429", description = "Job queue is full")
    })
    public Response handleCommentWebhook(
            @HeaderParam("X-GitHub-Event") String eventHeader,
            String rawPayload) {
        try {
            JsonNode payload = objectMapper.readTree(rawPayload);
            String event = eventHeader != null ? eventHeader : "";
            LOG.infof("GitHub comment webhook received: %s", event);

            return switch (event.toLowerCase(Locale.ROOT)) {
                case "pull_request_review_comment" -> handleReviewComment(payload);
                case "issue_comment" -> handleIssueComment(payload);
                default -> ok("ignored", "Unsupported event: " + event);
            };

        } catch (Exception e) {
            LOG.errorf("GitHub comment webhook processing error: %s", e.getMessage());
            return Response.ok(Map.of("action", "error", "message", e.getMessage())).build();
        }
    }

    // ── Review comment (inline diff comment) ────────────────────────────

    private Response handleReviewComment(JsonNode payload) {
        String action = payload.path("action").asText("");
        if (!action.equals("created")) {
            return ok("ignored", "Unsupported review comment action: " + action);
        }

        JsonNode commentNode = payload.path("comment");
        long commentId = commentNode.path("id").asLong(0);
        String commentBody = commentNode.path("body").asText("").trim();
        long inReplyToId = commentNode.path("in_reply_to_id").asLong(0);
        String commentAuthor = commentNode.path("user").path("login").asText("");
        String filePath = commentNode.path("path").asText(null);
        int line = commentNode.path("line").asInt(0);

        JsonNode prNode = payload.path("pull_request");
        String prNumber = String.valueOf(prNode.path("number").asInt(0));

        JsonNode repoNode = payload.path("repository");
        String fullName = repoNode.path("full_name").asText("");
        String repoHtmlUrl = repoNode.path("html_url").asText("");
        String repoUrl = repoHtmlUrl.isBlank() ? "" : repoHtmlUrl + ".git";

        if (commentId == 0 || prNumber.equals("0") || fullName.isBlank()) {
            return ok("ignored", "Missing comment ID, PR number, or repository in payload");
        }

        String[] parts = fullName.split("/", 2);
        String org = parts.length == 2 ? parts[0] : fullName;
        String repo = parts.length == 2 ? parts[1] : fullName;

        if (!repoSettingsStore.isReviewEnabled(org, repo)) {
            return ok("skipped", "Review disabled for " + fullName);
        }

        if (!agentUser.isEmpty() && commentAuthor.equalsIgnoreCase(agentUser)) {
            LOG.debugf("Review comment webhook: ignoring comment %d by agent user '%s'", commentId, agentUser);
            return ok("ignored", "Comment is from the agent itself");
        }

        if (inReplyToId == 0) {
            return ok("ignored", "Not a reply — in_reply_to_id is absent or zero");
        }

        if (!commentStore.contains(inReplyToId)) {
            LOG.debugf("Review comment webhook: parent comment %d is not from the agent, ignoring", inReplyToId);
            return ok("ignored", "Parent comment is not from the agent");
        }

        LOG.infof("Review comment webhook: developer replied (comment %d) to agent comment %d on PR #%s (%s)",
                commentId, inReplyToId, prNumber, fullName);

        String lowerBody = commentBody.toLowerCase(Locale.ROOT);

        if (lowerBody.startsWith("/learn ")) {
            return handleLearnCommand(commentBody, org, repo, repoUrl, prNumber, inReplyToId, commentAuthor);
        }

        if (lowerBody.equals("/fp") || lowerBody.equals("/false-positive")
                || lowerBody.startsWith("/fp ") || lowerBody.startsWith("/false-positive ")) {
            return handleFalsePositiveCommand(org, repo, prNumber, inReplyToId, commentAuthor);
        }

        if (lowerBody.equals("/generate-tests")) {
            return handleGenerateTestsCommand(repoUrl, prNumber, inReplyToId, org, repo);
        }

        String originalFinding = commentStore.find(inReplyToId)
                .map(CommentContext::findingText).orElse(null);
        CommentIntent intent = intentClassifier.classify(commentBody, originalFinding);
        JobType jobType = (intent == CommentIntent.FIX) ? JobType.FIX_COMMENT : JobType.REPLY;

        LOG.infof("Review comment webhook: classified intent as %s for comment %d", jobType, commentId);

        return submitJob(repoUrl, prNumber, inReplyToId, commentBody, filePath, line, jobType);
    }

    // ── Issue comment (general PR comment) ──────────────────────────────

    private Response handleIssueComment(JsonNode payload) {
        String action = payload.path("action").asText("");
        if (!action.equals("created")) {
            return ok("ignored", "Unsupported issue comment action: " + action);
        }

        // Only handle comments on pull requests (issue_comment fires on both issues and PRs)
        JsonNode issuePrNode = payload.path("issue").path("pull_request");
        if (issuePrNode.isMissingNode()) {
            return ok("ignored", "issue_comment on a plain issue, not a PR");
        }

        JsonNode commentNode = payload.path("comment");
        long commentId = commentNode.path("id").asLong(0);
        String commentBody = commentNode.path("body").asText("").trim();
        String commentAuthor = commentNode.path("user").path("login").asText("");

        JsonNode issueNode = payload.path("issue");
        String prNumber = String.valueOf(issueNode.path("number").asInt(0));

        JsonNode repoNode = payload.path("repository");
        String fullName = repoNode.path("full_name").asText("");
        String repoHtmlUrl = repoNode.path("html_url").asText("");
        String repoUrl = repoHtmlUrl.isBlank() ? "" : repoHtmlUrl + ".git";

        if (commentId == 0 || prNumber.equals("0") || fullName.isBlank()) {
            return ok("ignored", "Missing comment ID, PR number, or repository in payload");
        }

        String[] parts = fullName.split("/", 2);
        String org = parts.length == 2 ? parts[0] : fullName;
        String repo = parts.length == 2 ? parts[1] : fullName;

        if (!repoSettingsStore.isReviewEnabled(org, repo)) {
            return ok("skipped", "Review disabled for " + fullName);
        }

        if (!agentUser.isEmpty() && commentAuthor.equalsIgnoreCase(agentUser)) {
            LOG.debugf("Issue comment webhook: ignoring comment %d by agent user '%s'", commentId, agentUser);
            return ok("ignored", "Comment is from the agent itself");
        }

        // For issue comments there is no in_reply_to_id; check if commentId itself
        // is tracked (the developer may reply on a general comment the agent posted)
        if (!commentStore.contains(commentId)) {
            LOG.debugf("Issue comment webhook: comment %d is not tracked by the agent, ignoring", commentId);
            return ok("ignored", "Comment is not a reply to an agent comment");
        }

        LOG.infof("Issue comment webhook: developer commented (comment %d) on PR #%s (%s)",
                commentId, prNumber, fullName);

        String lowerBody = commentBody.toLowerCase(Locale.ROOT);

        if (lowerBody.startsWith("/learn ")) {
            return handleLearnCommand(commentBody, org, repo, repoUrl, prNumber, commentId, commentAuthor);
        }

        if (lowerBody.equals("/fp") || lowerBody.equals("/false-positive")
                || lowerBody.startsWith("/fp ") || lowerBody.startsWith("/false-positive ")) {
            return handleFalsePositiveCommand(org, repo, prNumber, commentId, commentAuthor);
        }

        if (lowerBody.equals("/generate-tests")) {
            return handleGenerateTestsCommand(repoUrl, prNumber, commentId, org, repo);
        }

        String originalFinding = commentStore.find(commentId)
                .map(CommentContext::findingText).orElse(null);
        CommentIntent intent = intentClassifier.classify(commentBody, originalFinding);
        JobType jobType = (intent == CommentIntent.FIX) ? JobType.FIX_COMMENT : JobType.REPLY;

        LOG.infof("Issue comment webhook: classified intent as %s for comment %d", jobType, commentId);

        return submitJob(repoUrl, prNumber, commentId, commentBody, null, 0, jobType);
    }

    // ── Shared handlers ──────────────────────────────────────────────────

    private Response submitJob(String repoUrl, String prId,
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

    private Response handleGenerateTestsCommand(String repoUrl, String prId,
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

    private Response handleLearnCommand(String commentBody, String org, String repo,
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

    private Response handleFalsePositiveCommand(String org, String repo,
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

        commentStore.markResolved(parentCommentId);

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
        List<String> recurring = feedbackStore.findRecurringPatterns(org, repo, fpAutoSuppressThreshold);
        if (!recurring.contains(pattern)) return;

        String memoryText = "Do not flag findings matching this pattern — the team has repeatedly marked it as a false positive: " + pattern;
        if (!memoryStore.exists(org, repo, memoryText)) {
            MemoryEntry entry = MemoryEntry.explicit(org, repo, memoryText, "auto-suppress");
            memoryStore.save(entry);
            LOG.infof("Auto-suppressed recurring FP pattern for %s/%s: %s", org, repo, pattern);
        }
    }

    private static Response ok(String action, String reason) {
        return Response.ok(Map.of("action", action, "reason", reason)).build();
    }
}
