package com.eneve.agent.webhooks;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.eneve.agent.agent.model.CommentContext;
import com.eneve.agent.agent.model.CommentFeedbackEntry;
import com.eneve.agent.agent.store.CommentFeedbackStore;
import com.eneve.agent.agent.model.CommentIntent;
import com.eneve.agent.agent.store.CommentStore;
import com.eneve.agent.agent.IntentClassifier;
import com.eneve.agent.agent.JobQueue;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.agent.model.MemoryEntry;
import com.eneve.agent.agent.store.MemoryStore;
import com.eneve.agent.agent.store.RepoSettingsStore;
import com.eneve.agent.agent.model.WebhookAuditEntry;
import com.eneve.agent.agent.store.WebhookAuditStore;
import com.eneve.agent.scm.GitPlatformService;
import com.eneve.agent.model.GenerateTestsRequest;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobType;
import com.eneve.agent.model.ReplyCommentRequest;
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
 * Handles incoming Bitbucket Cloud webhooks for pull request comment events.
 * When a developer replies to one of the agent's review comments, triggers a
 * REPLY job so the agent can respond conversationally in-thread.
 */
@Path("/webhooks")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Webhooks", description = "Incoming webhook handlers for external integrations")
public class BitbucketCommentWebhookResource {

    private static final Logger LOG = Logger.getLogger(BitbucketCommentWebhookResource.class);

    @Inject JobQueue jobQueue;
    @Inject JobStore jobStore;
    @Inject CommentStore commentStore;
    @Inject CommentFeedbackStore feedbackStore;
    @Inject IntentClassifier intentClassifier;
    @Inject MemoryStore memoryStore;
    @Inject RepoSettingsStore repoSettingsStore;
    @Inject GitPlatformService platformService;
    @Inject WebhookAuditStore webhookAuditStore;

    @ConfigProperty(name = "bitbucket.user")
    String bbUser;

    @ConfigProperty(name = "review.fp.auto-suppress-threshold", defaultValue = "3")
    int fpAutoSuppressThreshold;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @POST
    @Path("/bitbucket/pull-request-comment")
    @Operation(
            operationId = "bitbucketPrCommentWebhook",
            summary = "Handle Bitbucket Cloud PR comment webhook events",
            description = "Receives Bitbucket Cloud webhook payloads for pullrequest:comment_created events. "
                    + "When a developer replies to one of the agent's review comments, triggers an AI-powered "
                    + "conversational reply in the same thread."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Webhook processed"),
            @APIResponse(responseCode = "429", description = "Job queue is full")
    })
    public Response handleCommentWebhook(
            @HeaderParam("X-Event-Key") String eventKey,
            String rawPayload) {
        String event = eventKey != null ? eventKey : "";
        try {
            JsonNode payload = objectMapper.readTree(rawPayload);

            LOG.infof("Bitbucket comment webhook received: %s", event);

            if (!event.equals("pullrequest:comment_created")) {
                audit("bitbucket", event, null, null, null, null, "ignored", rawPayload);
                return ok("ignored", "Unsupported event: " + event);
            }

            JsonNode comment = payload.path("comment");
            JsonNode pr = payload.path("pullrequest");
            JsonNode repo = payload.path("repository");

            long commentId = comment.path("id").asLong(0);
            String commentText = comment.path("content").path("raw").asText("").trim();
            String commentAuthor = comment.path("user").path("username").asText(
                    comment.path("user").path("nickname").asText(""));

            String prId = String.valueOf(pr.path("id").asLong(0));
            String repoFullName = repo.path("full_name").asText("");

            if (commentId == 0 || prId.equals("0") || repoFullName.isBlank()) {
                audit("bitbucket", event, null, null, null, null, "ignored", rawPayload);
                return ok("ignored", "Missing comment ID, PR ID, or repository in payload");
            }

            String[] repoParts = repoFullName.split("/", 2);
            String workspace = repoParts.length == 2 ? repoParts[0] : "";
            String repoSlug = repoParts.length == 2 ? repoParts[1] : "";

            if (repoParts.length == 2
                    && !repoSettingsStore.isReviewEnabled(workspace, repoSlug)) {
                LOG.infof("Comment webhook: skipping — review disabled for %s", repoFullName);
                audit("bitbucket", event, workspace, repoSlug, prId, commentAuthor, "skipped", rawPayload);
                return ok("skipped", "Review disabled for " + repoFullName);
            }

            // Guard: ignore comments from the agent itself (prevent infinite loops)
            if (commentAuthor.equals(bbUser)) {
                LOG.debugf("Comment webhook: ignoring comment %d by agent user '%s'", commentId, bbUser);
                audit("bitbucket", event, workspace, repoSlug, prId, commentAuthor, "ignored", rawPayload);
                return ok("ignored", "Comment is from the agent itself");
            }

            // Guard: only process replies (comments with a parent)
            JsonNode parentNode = comment.path("parent");
            if (parentNode.isMissingNode() || !parentNode.has("id")) {
                audit("bitbucket", event, workspace, repoSlug, prId, commentAuthor, "ignored", rawPayload);
                return ok("ignored", "Not a reply — no parent comment");
            }
            long parentCommentId = parentNode.path("id").asLong(0);
            if (parentCommentId == 0) {
                audit("bitbucket", event, workspace, repoSlug, prId, commentAuthor, "ignored", rawPayload);
                return ok("ignored", "Not a reply — parent ID is zero");
            }

            // Guard: only reply if the parent comment is one of ours
            if (!commentStore.contains(parentCommentId)) {
                LOG.debugf("Comment webhook: parent comment %d is not from the agent, ignoring", parentCommentId);
                audit("bitbucket", event, workspace, repoSlug, prId, commentAuthor, "ignored", rawPayload);
                return ok("ignored", "Parent comment is not from the agent");
            }

            // Extract inline context if available
            JsonNode inline = comment.path("inline");
            String filePath = null;
            int line = 0;
            if (!inline.isMissingNode() && inline.has("path")) {
                filePath = inline.path("path").asText(null);
                line = inline.path("to").asInt(inline.path("from").asInt(0));
            }

            String repoUrl = "https://bitbucket.org/" + repoFullName + ".git";

            LOG.infof("Comment webhook: developer replied (comment %d) to agent comment %d on PR #%s (%s)",
                    commentId, parentCommentId, prId, repoFullName);

            // Fast path: /learn command stores a team preference directly
            String lowerText = commentText.toLowerCase(Locale.ROOT);
            if (lowerText.startsWith("/learn ")) {
                if (repoParts.length != 2) {
                    audit("bitbucket", event, workspace, repoSlug, prId, commentAuthor, "ignored", rawPayload);
                    return ok("ignored", "Could not parse workspace/repo from: " + repoFullName);
                }
                audit("bitbucket", event, workspace, repoSlug, prId, commentAuthor, "learn_command", rawPayload);
                return handleLearnCommand(commentText, workspace, repoSlug, repoUrl, prId,
                        parentCommentId, commentAuthor);
            }

            // Fast path: /fp or /false-positive marks the finding as a false positive
            if (lowerText.equals("/fp") || lowerText.equals("/false-positive")
                    || lowerText.startsWith("/fp ") || lowerText.startsWith("/false-positive ")) {
                if (repoParts.length != 2) {
                    audit("bitbucket", event, workspace, repoSlug, prId, commentAuthor, "ignored", rawPayload);
                    return ok("ignored", "Could not parse workspace/repo from: " + repoFullName);
                }
                audit("bitbucket", event, workspace, repoSlug, prId, commentAuthor, "false_positive", rawPayload);
                return handleFalsePositiveCommand(workspace, repoSlug, repoUrl, prId,
                        parentCommentId, commentAuthor);
            }

            // Fast path: /generate-tests triggers a unit test generation job for this PR
            if (lowerText.equals("/generate-tests")) {
                if (repoParts.length != 2) {
                    audit("bitbucket", event, workspace, repoSlug, prId, commentAuthor, "ignored", rawPayload);
                    return ok("ignored", "Could not parse workspace/repo from: " + repoFullName);
                }
                audit("bitbucket", event, workspace, repoSlug, prId, commentAuthor, "generate_tests", rawPayload);
                return handleGenerateTestsCommand(repoUrl, prId, parentCommentId,
                        workspace, repoSlug);
            }

            // Classify intent: is this a fix request or a discussion?
            String originalFinding = commentStore.find(parentCommentId)
                    .map(CommentContext::findingText).orElse(null);
            CommentIntent intent = intentClassifier.classify(commentText, originalFinding);
            JobType jobType = (intent == CommentIntent.FIX) ? JobType.FIX_COMMENT : JobType.REPLY;

            LOG.infof("Comment webhook: classified intent as %s for comment %d", jobType, commentId);

            String auditAction = (jobType == JobType.FIX_COMMENT) ? "fix_triggered" : "reply_triggered";
            audit("bitbucket", event, workspace, repoSlug, prId, commentAuthor, auditAction, rawPayload);
            return submitJob(repoUrl, prId, parentCommentId, commentText,
                    filePath, line, jobType);

        } catch (Exception e) {
            LOG.errorf("Bitbucket comment webhook processing error: %s", e.getMessage());
            audit("bitbucket", event, null, null, null, null, "error", rawPayload);
            return Response.ok(Map.of("action", "error", "message", e.getMessage())).build();
        }
    }

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
                                                  String workspace, String repoSlug) {
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

        LOG.infof("/generate-tests triggered job %s for PR #%s (%s/%s)", jobId, prId, workspace, repoSlug);

        try {
            platformService.replyToComment(workspace, "", repoSlug, prId, parentCommentId,
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

    private Response handleLearnCommand(String commentText, String workspace, String repoSlug,
                                         String repoUrl, String prId, long parentCommentId,
                                         String author) {
        String learning = commentText.substring("/learn ".length()).trim();
        if (learning.isBlank()) {
            return ok("ignored", "/learn command with empty text");
        }

        if (memoryStore.exists(workspace, repoSlug, learning)) {
            LOG.debugf("Duplicate /learn ignored for %s/%s: %s", workspace, repoSlug, learning);
            return ok("duplicate", "This preference is already stored");
        }

        MemoryEntry entry = MemoryEntry.explicit(workspace, repoSlug, learning, author);
        memoryStore.save(entry);

        LOG.infof("/learn command from %s on %s/%s PR #%s: %s", author, workspace, repoSlug, prId, learning);

        try {
            platformService.replyToComment(workspace, "", repoSlug, prId, parentCommentId,
                    "Noted — I'll remember this for future reviews of this repository:\n\n> " + learning);
        } catch (Exception e) {
            LOG.warnf("Failed to post /learn confirmation reply (non-fatal): %s", e.getMessage());
        }

        return Response.ok(Map.of(
                "action", "learning_stored",
                "memory", learning,
                "workspace", workspace,
                "repoSlug", repoSlug
        )).build();
    }

    private Response handleFalsePositiveCommand(String workspace, String repoSlug,
                                                  String repoUrl, String prId,
                                                  long parentCommentId, String author) {
        CommentContext ctx = commentStore.find(parentCommentId).orElse(null);
        if (ctx == null) {
            LOG.warnf("/fp: could not find CommentContext for comment %d", parentCommentId);
            return ok("ignored", "Could not find original finding for comment " + parentCommentId);
        }

        CommentFeedbackEntry feedback = CommentFeedbackEntry.falsePositive(
                parentCommentId, prId, workspace, repoSlug,
                ctx.category(), ctx.findingText(), author);
        feedbackStore.save(feedback);

        commentStore.markResolved(parentCommentId);

        try {
            platformService.resolveComment(workspace, "", repoSlug, prId, parentCommentId);
        } catch (Exception e) {
            LOG.warnf("Failed to resolve comment %d on platform (non-fatal): %s", parentCommentId, e.getMessage());
        }

        try {
            platformService.replyToComment(workspace, "", repoSlug, prId, parentCommentId,
                    "Got it \u2014 marked as false positive. I'll be more careful about this pattern in future reviews.");
        } catch (Exception e) {
            LOG.warnf("Failed to post /fp confirmation reply (non-fatal): %s", e.getMessage());
        }

        checkAndAutoSuppress(workspace, repoSlug, feedback.pattern(), author);

        LOG.infof("/fp from %s on %s/%s PR #%s, comment %d: %s",
                author, workspace, repoSlug, prId, parentCommentId,
                ctx.findingText() != null ? ctx.findingText().substring(0, Math.min(80, ctx.findingText().length())) : "");

        return Response.ok(Map.of(
                "action", "false_positive_recorded",
                "commentId", parentCommentId,
                "workspace", workspace,
                "repoSlug", repoSlug
        )).build();
    }

    private void checkAndAutoSuppress(String workspace, String repoSlug, String pattern, String author) {
        if (pattern == null || pattern.isBlank()) return;
        List<String> recurring = feedbackStore.findRecurringPatterns(workspace, repoSlug, fpAutoSuppressThreshold);
        if (!recurring.contains(pattern)) return;

        String memoryText = "Do not flag findings matching this pattern — the team has repeatedly marked it as a false positive: " + pattern;
        if (!memoryStore.exists(workspace, repoSlug, memoryText)) {
            MemoryEntry entry = MemoryEntry.explicit(workspace, repoSlug, memoryText, "auto-suppress");
            memoryStore.save(entry);
            LOG.infof("Auto-suppressed recurring FP pattern for %s/%s: %s", workspace, repoSlug, pattern);
        }
    }

    private void audit(String platform, String eventType, String workspace, String repoSlug,
                        String prId, String author, String action, String payload) {
        try {
            webhookAuditStore.save(WebhookAuditEntry.create(
                    platform, eventType, workspace, repoSlug, prId, author, action, List.of(), payload));
        } catch (Exception e) {
            LOG.warnf("Failed to save webhook audit entry (non-fatal): %s", e.getMessage());
        }
    }

    private static Response ok(String action, String reason) {
        return Response.ok(Map.of("action", action, "reason", reason)).build();
    }
}
