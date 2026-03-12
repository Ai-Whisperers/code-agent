package com.eneve.agent.webhooks;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.eneve.agent.agent.JobQueue;
import com.eneve.agent.agent.JobStore;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobStatus;
import com.eneve.agent.model.ReviewPrRequest;
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
 * Handles incoming Bitbucket Cloud webhooks for pull request events.
 * When a PR is created (or updated), automatically triggers a code review job.
 */
@Path("/webhooks")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Webhooks", description = "Incoming webhook handlers for external integrations")
public class BitbucketWebhookResource {

    private static final Logger LOG = Logger.getLogger(BitbucketWebhookResource.class);
    private static final Pattern JIRA_KEY_PATTERN = Pattern.compile("([A-Z][A-Z0-9]+-\\d+)");

    @Inject JobQueue jobQueue;
    @Inject JobStore jobStore;

    @ConfigProperty(name = "review.webhook.skip-authors", defaultValue = "code-agent")
    String skipAuthors;

    @ConfigProperty(name = "review.webhook.require-title-keyword", defaultValue = "")
    String requireTitleKeyword;

    @ConfigProperty(name = "rules.repo.url", defaultValue = "")
    String defaultRulesRepoUrl;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @POST
    @Path("/bitbucket/pull-request")
    @Operation(
            operationId = "bitbucketPrWebhook",
            summary = "Handle Bitbucket Cloud PR webhook events",
            description = "Receives Bitbucket Cloud webhook payloads for pullrequest:created and pullrequest:updated events. "
                    + "Automatically triggers an AI code review job for the PR. "
                    + "Skips PRs authored by the agent itself (configurable via review.webhook.skip-authors). "
                    + "Optionally requires a keyword in the PR title (review.webhook.require-title-keyword)."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Webhook processed"),
            @APIResponse(responseCode = "429", description = "Job queue is full")
    })
    public Response handlePrWebhook(
            @HeaderParam("X-Event-Key") String eventKey,
            String rawPayload) {
        try {
            JsonNode payload = objectMapper.readTree(rawPayload);

            String event = eventKey != null ? eventKey : "";
            LOG.infof("Bitbucket webhook received: %s", event);

            if (!event.equals("pullrequest:created") && !event.equals("pullrequest:updated")) {
                return ok("ignored", "Unsupported event: " + event);
            }

            JsonNode pr = payload.path("pullrequest");
            JsonNode repo = payload.path("repository");

            String prId = pr.path("id").asText("");
            String prTitle = pr.path("title").asText("");
            String prAuthor = pr.path("author").path("display_name").asText("");
            String sourceBranch = pr.path("source").path("branch").path("name").asText("");
            String destBranch = pr.path("destination").path("branch").path("name").asText("");
            String repoFullName = repo.path("full_name").asText("");

            if (prId.isBlank() || repoFullName.isBlank()) {
                return ok("ignored", "Missing PR ID or repository in payload");
            }

            // Skip PRs authored by the agent itself
            if (shouldSkipAuthor(prAuthor)) {
                LOG.infof("Bitbucket webhook: skipping PR #%s by '%s' (matches skip-authors)", prId, prAuthor);
                return ok("skipped", "PR author '" + prAuthor + "' is in skip list");
            }

            // Optional title keyword filter
            if (!requireTitleKeyword.isBlank()
                    && !prTitle.toLowerCase().contains(requireTitleKeyword.toLowerCase())) {
                LOG.infof("Bitbucket webhook: skipping PR #%s — title does not contain '%s'",
                        prId, requireTitleKeyword);
                return ok("skipped", "PR title does not contain required keyword: " + requireTitleKeyword);
            }

            String repoUrl = "https://bitbucket.org/" + repoFullName + ".git";

            // Try to extract a JIRA key from the PR title
            String jiraKey = extractJiraKey(prTitle);

            LOG.infof("Bitbucket webhook: triggering review for PR #%s (%s -> %s) on %s",
                    prId, sourceBranch, destBranch, repoFullName);

            return submitReviewJob(repoUrl, prId, destBranch, jiraKey);

        } catch (Exception e) {
            LOG.errorf("Bitbucket webhook processing error: %s", e.getMessage());
            return Response.ok(Map.of("action", "error", "message", e.getMessage())).build();
        }
    }

    private Response submitReviewJob(String repoUrl, String prId, String targetBranch, String jiraKey) {
        ReviewPrRequest request = new ReviewPrRequest(
                repoUrl,
                prId,
                targetBranch,
                jiraKey,
                defaultRulesRepoUrl.isBlank() ? null : defaultRulesRepoUrl,
                null,
                null,
                null
        );

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

        LOG.infof("Bitbucket webhook triggered review job %s for PR #%s", jobId, prId);
        return Response.ok(Map.of(
                "action", "review_triggered",
                "jobId", jobId,
                "prId", prId,
                "jiraKey", jiraKey != null ? jiraKey : ""
        )).build();
    }

    private boolean shouldSkipAuthor(String author) {
        if (skipAuthors.isBlank() || author.isBlank()) return false;
        for (String skip : skipAuthors.split(",")) {
            if (skip.trim().equalsIgnoreCase(author)) return true;
        }
        return false;
    }

    private static String extractJiraKey(String title) {
        if (title == null || title.isBlank()) return null;
        Matcher matcher = JIRA_KEY_PATTERN.matcher(title);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static Response ok(String action, String reason) {
        return Response.ok(Map.of("action", action, "reason", reason)).build();
    }
}
