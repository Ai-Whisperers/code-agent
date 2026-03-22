package com.eneve.agent.webhooks;

import java.util.Map;
import java.util.UUID;

import com.eneve.agent.agent.model.CommentContext;
import com.eneve.agent.agent.model.CommentIntent;
import com.eneve.agent.agent.store.CommentStore;
import com.eneve.agent.agent.IntentClassifier;
import com.eneve.agent.agent.JobQueue;
import com.eneve.agent.agent.store.JobStore;
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
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Handles incoming Azure DevOps Service Hook notifications for pull request comment events.
 * Supports {@code ms.vss-code.git-pullrequest-comment-event} event type.
 */
@Path("/webhooks")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Webhooks", description = "Incoming webhook handlers for external integrations")
public class AzureDevOpsCommentWebhookResource {

    private static final Logger LOG = Logger.getLogger(AzureDevOpsCommentWebhookResource.class);

    @Inject JobQueue jobQueue;
    @Inject JobStore jobStore;
    @Inject CommentStore commentStore;
    @Inject IntentClassifier intentClassifier;

    @ConfigProperty(name = "azuredevops.agent.user", defaultValue = "")
    String agentUser;

    @ConfigProperty(name = "azuredevops.base.url", defaultValue = "https://dev.azure.com")
    String adoBaseUrl;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @POST
    @Path("/azuredevops/pull-request-comment")
    @Operation(
            operationId = "azureDevOpsPrCommentWebhook",
            summary = "Handle Azure DevOps PR comment webhook events",
            description = "Receives Azure DevOps Service Hook payloads for pull request comment events. "
                    + "When a developer replies to one of the agent's review comments, triggers an AI-powered "
                    + "conversational reply in the same thread."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Webhook processed"),
            @APIResponse(responseCode = "429", description = "Job queue is full")
    })
    public Response handleCommentWebhook(String rawPayload) {
        try {
            JsonNode payload = objectMapper.readTree(rawPayload);

            String eventType = payload.path("eventType").asText("");
            LOG.infof("Azure DevOps comment webhook received: %s", eventType);

            if (!eventType.equals("ms.vss-code.git-pullrequest-comment-event")) {
                return ok("ignored", "Unsupported event: " + eventType);
            }

            JsonNode resource = payload.path("resource");
            JsonNode comment = resource.path("comment");
            JsonNode pullRequest = resource.path("pullRequest");

            long commentId = comment.path("id").asLong(0);
            String commentText = comment.path("content").asText("").trim();
            String commentAuthor = comment.path("author").path("uniqueName").asText("");

            String prId = String.valueOf(pullRequest.path("pullRequestId").asInt(0));

            JsonNode repoNode = pullRequest.path("repository");
            String repoName = repoNode.path("name").asText("");
            String projectName = repoNode.path("project").path("name").asText("");
            String remoteUrl = repoNode.path("remoteUrl").asText("");
            if (remoteUrl.isBlank()) {
                String collectionUrl = payload.path("resourceContainers")
                        .path("collection").path("baseUrl").asText(adoBaseUrl);
                collectionUrl = collectionUrl.endsWith("/")
                        ? collectionUrl.substring(0, collectionUrl.length() - 1) : collectionUrl;
                remoteUrl = collectionUrl + "/" + projectName + "/_git/" + repoName;
            }

            if (commentId == 0 || prId.equals("0") || repoName.isBlank()) {
                return ok("ignored", "Missing comment ID, PR ID, or repository in payload");
            }

            if (!agentUser.isEmpty() && commentAuthor.equalsIgnoreCase(agentUser)) {
                LOG.debugf("Comment webhook: ignoring comment %d by agent user '%s'", commentId, agentUser);
                return ok("ignored", "Comment is from the agent itself");
            }

            long parentCommentId = comment.path("parentCommentId").asLong(0);
            if (parentCommentId == 0) {
                return ok("ignored", "Not a reply — no parent comment");
            }

            if (!commentStore.contains(parentCommentId)) {
                LOG.debugf("Comment webhook: parent comment %d is not from the agent, ignoring", parentCommentId);
                return ok("ignored", "Parent comment is not from the agent");
            }

            String filePath = null;
            int line = 0;
            JsonNode threadContext = resource.path("thread").path("threadContext");
            if (!threadContext.isMissingNode() && threadContext.has("filePath")) {
                filePath = threadContext.path("filePath").asText(null);
                line = threadContext.path("rightFileStart").path("line").asInt(0);
            }

            LOG.infof("Comment webhook: developer replied (comment %d) to agent comment %d on PR #%s (%s/%s)",
                    commentId, parentCommentId, prId, projectName, repoName);

            // Fast path: /generate-tests triggers a unit test generation job for this PR
            if (commentText.equalsIgnoreCase("/generate-tests")) {
                return handleGenerateTestsCommand(remoteUrl, prId, parentCommentId);
            }

            String originalFinding = commentStore.find(parentCommentId)
                    .map(CommentContext::findingText).orElse(null);
            CommentIntent intent = intentClassifier.classify(commentText, originalFinding);
            JobType jobType = (intent == CommentIntent.FIX) ? JobType.FIX_COMMENT : JobType.REPLY;

            LOG.infof("Comment webhook: classified intent as %s for comment %d", jobType, commentId);

            return submitJob(remoteUrl, prId, parentCommentId, commentText, filePath, line, jobType);

        } catch (Exception e) {
            LOG.errorf("Azure DevOps comment webhook processing error: %s", e.getMessage());
            return Response.ok(Map.of("action", "error", "message", e.getMessage())).build();
        }
    }

    private Response handleGenerateTestsCommand(String repoUrl, String prId,
                                                  long parentCommentId) {
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

        LOG.infof("/generate-tests triggered job %s for PR #%s", jobId, prId);

        return Response.ok(Map.of(
                "action", "generate_tests_triggered",
                "jobId", jobId,
                "branch", branchName,
                "prId", prId
        )).build();
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

    private static Response ok(String action, String reason) {
        return Response.ok(Map.of("action", action, "reason", reason)).build();
    }
}
