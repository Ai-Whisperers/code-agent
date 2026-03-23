package com.eneve.agent.webhooks;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.eneve.agent.agent.HookEvaluator;
import com.eneve.agent.scm.GitPlatformService;

import com.eneve.agent.agent.JobQueue;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.model.JobRecord;
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
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Handles incoming Azure DevOps Service Hook notifications for pull request events.
 * Supports {@code git.pullrequest.created} and {@code git.pullrequest.updated} event types.
 */
@Path("/webhooks")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Webhooks", description = "Incoming webhook handlers for external integrations")
public class AzureDevOpsWebhookResource {

    private static final Logger LOG = Logger.getLogger(AzureDevOpsWebhookResource.class);
    private static final Pattern JIRA_KEY_PATTERN = Pattern.compile("([A-Z][A-Z0-9]+-\\d+)");

    @Inject GitPlatformService platformService;
    @Inject JobQueue jobQueue;
    @Inject HookEvaluator hookEvaluator;
    @Inject JobStore jobStore;

    @ConfigProperty(name = "review.webhook.skip-authors", defaultValue = "code-agent")
    String skipAuthors;

    @ConfigProperty(name = "review.webhook.require-title-keyword", defaultValue = "")
    String requireTitleKeyword;

    @ConfigProperty(name = "rules.repo.url", defaultValue = "")
    String defaultRulesRepoUrl;

    @ConfigProperty(name = "azuredevops.base.url", defaultValue = "https://dev.azure.com")
    String adoBaseUrl;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @POST
    @Path("/azuredevops/pull-request")
    @Operation(
            operationId = "azureDevOpsPrWebhook",
            summary = "Handle Azure DevOps pull request webhook events",
            description = "Receives Azure DevOps Service Hook payloads for git.pullrequest.created "
                    + "and git.pullrequest.updated events. Automatically triggers an AI code review job."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Webhook processed"),
            @APIResponse(responseCode = "429", description = "Job queue is full")
    })
    public Response handlePrWebhook(String rawPayload) {
        try {
            JsonNode payload = objectMapper.readTree(rawPayload);

            String eventType = payload.path("eventType").asText("");
            LOG.infof("Azure DevOps webhook received: %s", eventType);

            if (!eventType.equals("git.pullrequest.created")
                    && !eventType.equals("git.pullrequest.updated")) {
                return ok("ignored", "Unsupported event: " + eventType);
            }

            JsonNode resource = payload.path("resource");
            String prId = String.valueOf(resource.path("pullRequestId").asInt(0));
            String prTitle = resource.path("title").asText("");
            String prAuthor = resource.path("createdBy").path("displayName").asText("");
            String sourceBranch = stripRefsHeads(resource.path("sourceRefName").asText(""));
            String destBranch = stripRefsHeads(resource.path("targetRefName").asText(""));

            JsonNode repoNode = resource.path("repository");
            String repoName = repoNode.path("name").asText("");
            String projectName = repoNode.path("project").path("name").asText("");

            JsonNode remoteUrl = repoNode.path("remoteUrl");
            String repoUrl = remoteUrl.isTextual() ? remoteUrl.asText("") : "";
            if (repoUrl.isBlank()) {
                String collectionUrl = payload.path("resourceContainers")
                        .path("collection").path("baseUrl").asText(adoBaseUrl);
                collectionUrl = collectionUrl.endsWith("/")
                        ? collectionUrl.substring(0, collectionUrl.length() - 1) : collectionUrl;
                repoUrl = collectionUrl + "/" + projectName + "/_git/" + repoName;
            }

            if (prId.equals("0") || repoName.isBlank()) {
                return ok("ignored", "Missing PR ID or repository in payload");
            }

            if (shouldSkipAuthor(prAuthor)) {
                LOG.infof("Azure DevOps webhook: skipping PR #%s by '%s' (matches skip-authors)", prId, prAuthor);
                return ok("skipped", "PR author '" + prAuthor + "' is in skip list");
            }

            if (!requireTitleKeyword.isBlank()
                    && !prTitle.toLowerCase().contains(requireTitleKeyword.toLowerCase())) {
                LOG.infof("Azure DevOps webhook: skipping PR #%s — title does not contain '%s'",
                        prId, requireTitleKeyword);
                return ok("skipped", "PR title does not contain required keyword: " + requireTitleKeyword);
            }

            String jiraKey = extractJiraKey(prTitle);
            String headCommitSha = resource.path("lastMergeSourceCommit").path("commitId").asText(null);
            if (headCommitSha != null && headCommitSha.isBlank()) headCommitSha = null;

            LOG.infof("Azure DevOps webhook: triggering review for PR #%s (%s -> %s) on %s/%s (head: %s)",
                    prId, sourceBranch, destBranch, projectName, repoName,
                    headCommitSha != null ? headCommitSha.substring(0, Math.min(8, headCommitSha.length())) : "unknown");

            // Evaluate hooks for PR created/updated/completed events
            String triggerType;
            switch (eventType) {
                case "git.pullrequest.created" -> triggerType = "scm.pr_created";
                case "git.pullrequest.updated" -> triggerType = "scm.pr_updated";
                case "git.pullrequest.merged" -> triggerType = "scm.pr_merged";
                default -> triggerType = "scm.pr_updated"; // fallback
            }
            
            var context = Map.of(
                    "prId", prId,
                    "sourceBranch", sourceBranch,
                    "targetBranch", destBranch,
                    "prTitle", prTitle,
                    "author", prAuthor,
                    "platform", "azuredevops",
                    "repoSlug", repoName
            );
            var hookJobIds = hookEvaluator.evaluateByTrigger(
                    triggerType, projectName, repoName, repoUrl, context);
            
            if (!hookJobIds.isEmpty()) {
                LOG.infof("Azure DevOps webhook: triggered %d hook jobs for %s", hookJobIds.size(), triggerType);
            }

            return submitReviewJob(repoUrl, prId, destBranch, jiraKey, headCommitSha);

        } catch (Exception e) {
            LOG.errorf("Azure DevOps webhook processing error: %s", e.getMessage());
            return Response.ok(Map.of("action", "error", "message", e.getMessage())).build();
        }
    }

    private Response submitReviewJob(String repoUrl, String prId, String targetBranch, String jiraKey,
                                     String headCommitSha) {
        ReviewPrRequest request = new ReviewPrRequest(
                repoUrl, prId, targetBranch, jiraKey,
                defaultRulesRepoUrl.isBlank() ? null : defaultRulesRepoUrl,
                null, null, null, headCommitSha);

        String jobId = UUID.randomUUID().toString();
        JobRecord job = new JobRecord(jobId, request);
        jobStore.put(job);

        if (!jobQueue.submit(job)) {
            return Response.status(429)
                    .entity(Map.of("action", "rejected", "reason", "Job queue is full"))
                    .build();
        }

        LOG.infof("Azure DevOps webhook triggered review job %s for PR #%s", jobId, prId);
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

    private static String stripRefsHeads(String refName) {
        if (refName != null && refName.startsWith("refs/heads/")) {
            return refName.substring("refs/heads/".length());
        }
        return refName != null ? refName : "";
    }

    private static Response ok(String action, String reason) {
        return Response.ok(Map.of("action", action, "reason", reason)).build();
    }
}
