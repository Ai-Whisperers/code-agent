package com.eneve.agent.webhooks;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.eneve.agent.agent.model.CommentContext;
import com.eneve.agent.agent.model.CommentIntent;
import com.eneve.agent.agent.model.WebhookAuditEntry;
import com.eneve.agent.agent.store.WebhookAuditStore;
import com.eneve.agent.model.JobType;
import com.fasterxml.jackson.databind.JsonNode;

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
public class BitbucketCommentWebhookResource extends AbstractCommentWebhookHandler {

    private static final Logger LOG = Logger.getLogger(BitbucketCommentWebhookResource.class);

    @Inject WebhookAuditStore webhookAuditStore;

    @Override
    protected String agentUserSettingKey() { return "bitbucket.agent.user"; }

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

            // Guard: ignore comments from the agent itself (prevent infinite loops).
            // Use the API-resolved account username rather than the raw config value, because
            // Bitbucket Access Tokens use "x-token-auth" as the HTTP credential but the actual
            // Bitbucket account username is different — if we compared against "x-token-auth"
            // the filter would never match and every agent reply would re-trigger processing.
            String botUsername = platformService.getCurrentUserUsername();
            if (botUsername.isBlank()) botUsername = settings.get("bitbucket.user", "");
            if (commentAuthor.equals(botUsername)) {
                LOG.debugf("Comment webhook: ignoring comment %d by agent user '%s'", commentId, botUsername);
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
                return handleFalsePositiveCommand(workspace, repoSlug, prId,
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
            return Response.serverError().entity(Map.of("action", "error", "message", e.getMessage())).build();
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

}
