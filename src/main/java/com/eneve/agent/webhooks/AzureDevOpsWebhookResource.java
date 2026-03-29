package com.eneve.agent.webhooks;

import java.util.List;
import java.util.Map;

import com.eneve.agent.agent.model.HookEvalResult;
import com.eneve.agent.scm.GitPlatformService;

import com.fasterxml.jackson.databind.JsonNode;

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
public class AzureDevOpsWebhookResource extends AbstractPrWebhookHandler {

    private static final Logger LOG = Logger.getLogger(AzureDevOpsWebhookResource.class);

    @Inject GitPlatformService platformService;


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
        String eventType = "";
        try {
            JsonNode payload = objectMapper.readTree(rawPayload);

            eventType = payload.path("eventType").asText("");
            LOG.infof("Azure DevOps webhook received: %s", eventType);

            if (!eventType.equals("git.pullrequest.created")
                    && !eventType.equals("git.pullrequest.updated")) {
                audit("azuredevops", eventType, null, null, null, null, "ignored", List.of(), rawPayload);
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
                String adoBaseUrl = settingsService.get("azuredevops.base.url", "https://dev.azure.com");
                String collectionUrl = payload.path("resourceContainers")
                        .path("collection").path("baseUrl").asText(adoBaseUrl);
                collectionUrl = collectionUrl.endsWith("/")
                        ? collectionUrl.substring(0, collectionUrl.length() - 1) : collectionUrl;
                repoUrl = collectionUrl + "/" + projectName + "/_git/" + repoName;
            }

            if (prId.equals("0") || repoName.isBlank()) {
                audit("azuredevops", eventType, null, null, null, null, "ignored", List.of(), rawPayload);
                return ok("ignored", "Missing PR ID or repository in payload");
            }

            if (shouldSkipAuthor(prAuthor)) {
                LOG.infof("Azure DevOps webhook: skipping PR #%s by '%s' (matches skip-authors)", prId, prAuthor);
                audit("azuredevops", eventType, projectName, repoName, prId, prAuthor, "skipped", List.of(), rawPayload);
                return ok("skipped", "PR author '" + prAuthor + "' is in skip list");
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
            HookEvalResult hookResult = hookEvaluator.evaluateByTrigger(
                    triggerType, projectName, repoName, repoUrl, context);

            if (!hookResult.isEmpty()) {
                LOG.infof("Azure DevOps webhook: triggered %d hook jobs for %s", hookResult.size(), triggerType);
            }

            audit("azuredevops", eventType, projectName, repoName, prId, prAuthor,
                    "review_triggered", hookResult.hookNames(), rawPayload);
            return submitReviewJob(repoUrl, prId, destBranch, jiraKey, headCommitSha, projectName, repoName, prAuthor);

        } catch (Exception e) {
            LOG.errorf("Azure DevOps webhook processing error: %s", e.getMessage());
            audit("azuredevops", eventType, null, null, null, null, "error", List.of(), rawPayload);
            return Response.ok(Map.of("action", "error", "message", e.getMessage())).build();
        }
    }




    private static String stripRefsHeads(String refName) {
        if (refName != null && refName.startsWith("refs/heads/")) {
            return refName.substring("refs/heads/".length());
        }
        return refName != null ? refName : "";
    }


}
