package com.eneve.agent.webhooks;

import java.util.Map;
import java.util.UUID;

import com.eneve.agent.agent.CommentStore;
import com.eneve.agent.agent.JobQueue;
import com.eneve.agent.agent.JobStore;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobStatus;
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

    @ConfigProperty(name = "bitbucket.user")
    String bbUser;

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
        try {
            JsonNode payload = objectMapper.readTree(rawPayload);

            String event = eventKey != null ? eventKey : "";
            LOG.infof("Bitbucket comment webhook received: %s", event);

            if (!event.equals("pullrequest:comment_created")) {
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
                return ok("ignored", "Missing comment ID, PR ID, or repository in payload");
            }

            // Guard: ignore comments from the agent itself (prevent infinite loops)
            if (commentAuthor.equals(bbUser)) {
                LOG.debugf("Comment webhook: ignoring comment %d by agent user '%s'", commentId, bbUser);
                return ok("ignored", "Comment is from the agent itself");
            }

            // Guard: only process replies (comments with a parent)
            JsonNode parentNode = comment.path("parent");
            if (parentNode.isMissingNode() || !parentNode.has("id")) {
                return ok("ignored", "Not a reply — no parent comment");
            }
            long parentCommentId = parentNode.path("id").asLong(0);
            if (parentCommentId == 0) {
                return ok("ignored", "Not a reply — parent ID is zero");
            }

            // Guard: only reply if the parent comment is one of ours
            if (!commentStore.contains(parentCommentId)) {
                LOG.debugf("Comment webhook: parent comment %d is not from the agent, ignoring", parentCommentId);
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

            // Parse workspace and repo slug from full_name
            String[] parts = repoFullName.split("/", 2);
            if (parts.length != 2) {
                return ok("ignored", "Could not parse workspace/repo from: " + repoFullName);
            }
            String workspace = parts[0];
            String repoSlug = parts[1];

            LOG.infof("Comment webhook: developer replied (comment %d) to agent comment %d on PR #%s (%s/%s)",
                    commentId, parentCommentId, prId, workspace, repoSlug);

            return submitReplyJob(workspace, repoSlug, prId, parentCommentId, commentText, filePath, line);

        } catch (Exception e) {
            LOG.errorf("Bitbucket comment webhook processing error: %s", e.getMessage());
            return Response.ok(Map.of("action", "error", "message", e.getMessage())).build();
        }
    }

    private Response submitReplyJob(String workspace, String repoSlug, String prId,
                                    long parentCommentId, String humanMessage,
                                    String filePath, int line) {
        ReplyCommentRequest request = new ReplyCommentRequest(
                workspace, repoSlug, prId, parentCommentId, humanMessage, filePath, line);

        String jobId = UUID.randomUUID().toString();
        JobRecord job = new JobRecord(jobId, request);
        jobStore.put(job);

        if (!jobQueue.submit(job)) {
            job.setStatus(JobStatus.FAILED);
            job.setErrorMessage("Job queue is full");
            return Response.status(429)
                    .entity(Map.of("action", "rejected", "reason", "Job queue is full"))
                    .build();
        }

        LOG.infof("Comment webhook triggered reply job %s for comment %d on PR #%s",
                jobId, parentCommentId, prId);
        return Response.ok(Map.of(
                "action", "reply_triggered",
                "jobId", jobId,
                "parentCommentId", parentCommentId,
                "prId", prId
        )).build();
    }

    private static Response ok(String action, String reason) {
        return Response.ok(Map.of("action", action, "reason", reason)).build();
    }
}
