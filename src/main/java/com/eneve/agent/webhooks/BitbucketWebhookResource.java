package com.eneve.agent.webhooks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.eneve.agent.agent.model.HookEvalResult;
import com.fasterxml.jackson.databind.JsonNode;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

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
public class BitbucketWebhookResource extends AbstractPrWebhookHandler {

    private static final Logger LOG = Logger.getLogger(BitbucketWebhookResource.class);



    @POST
    @Path("/bitbucket/pull-request")
    @Operation(
            operationId = "bitbucketPrWebhook",
            summary = "Handle Bitbucket Cloud PR webhook events",
            description = "Receives Bitbucket Cloud webhook payloads for pullrequest:created and pullrequest:updated events. "
                    + "Automatically triggers an AI code review job for the PR. "
                    + "Skips PRs authored by the agent itself (configurable via review.webhook.skip-authors)."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Webhook processed"),
            @APIResponse(responseCode = "429", description = "Job queue is full")
    })
    public Response handlePrWebhook(
            @HeaderParam("X-Event-Key") String eventKey,
            String rawPayload) {
        String event = eventKey != null ? eventKey : "";
        try {
            JsonNode payload = objectMapper.readTree(rawPayload);

            LOG.infof("Bitbucket webhook received: %s", event);

            boolean isCreateOrUpdate = event.equals("pullrequest:created") || event.equals("pullrequest:updated");
            boolean isFulfilled = event.equals("pullrequest:fulfilled");

            if (!isCreateOrUpdate && !isFulfilled) {
                audit("bitbucket", event, null, null, null, null, "ignored", List.of(), rawPayload);
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
                audit("bitbucket", event, null, null, null, null, "ignored", List.of(), rawPayload);
                return ok("ignored", "Missing PR ID or repository in payload");
            }

            String[] repoParts = repoFullName.split("/", 2);
            String workspace = repoParts.length == 2 ? repoParts[0] : "";
            String repoSlug = repoParts.length == 2 ? repoParts[1] : "";
            String repoUrl = "https://bitbucket.org/" + repoFullName + ".git";

            if (isFulfilled) {
                LOG.infof("Bitbucket webhook: PR #%s merged (%s -> %s) on %s — evaluating hooks",
                        prId, sourceBranch, destBranch, repoFullName);
                if (repoParts.length == 2) {
                    // Legacy hook evaluation for backward compatibility
                    HookEvalResult legacyResult = hookEvaluator.evaluate(
                            workspace, repoSlug, repoUrl, event, destBranch);

                    // New generic hook evaluation with context
                    var context = Map.of(
                            "prId", prId,
                            "sourceBranch", sourceBranch,
                            "targetBranch", destBranch,
                            "prTitle", prTitle,
                            "author", prAuthor,
                            "platform", "bitbucket",
                            "repoSlug", repoSlug
                    );
                    HookEvalResult newResult = hookEvaluator.evaluateByTrigger(
                            "scm.pr_merged", workspace, repoSlug, repoUrl, context);

                    var totalJobIds = new ArrayList<>(legacyResult.jobIds());
                    totalJobIds.addAll(newResult.jobIds());
                    var allHookNames = new ArrayList<>(legacyResult.hookNames());
                    allHookNames.addAll(newResult.hookNames());

                    audit("bitbucket", event, workspace, repoSlug, prId, prAuthor,
                            "hooks_evaluated", allHookNames, rawPayload);
                    return Response.ok(Map.of(
                            "action", "hooks_evaluated",
                            "hooksTriggered", totalJobIds.size(),
                            "jobIds", totalJobIds
                    )).build();
                }
                audit("bitbucket", event, null, null, prId, prAuthor, "ignored", List.of(), rawPayload);
                return ok("ignored", "Could not parse workspace/repo from " + repoFullName);
            }

            if (repoParts.length == 2
                    && !repoSettingsStore.isReviewEnabled(workspace, repoSlug)) {
                LOG.infof("Bitbucket webhook: skipping PR #%s — review disabled for %s", prId, repoFullName);
                audit("bitbucket", event, workspace, repoSlug, prId, prAuthor, "skipped", List.of(), rawPayload);
                return ok("skipped", "Review disabled for " + repoFullName);
            }

            if (shouldSkipAuthor(prAuthor)) {
                LOG.infof("Bitbucket webhook: skipping PR #%s by '%s' (matches skip-authors)", prId, prAuthor);
                audit("bitbucket", event, workspace, repoSlug, prId, prAuthor, "skipped", List.of(), rawPayload);
                return ok("skipped", "PR author '" + prAuthor + "' is in skip list");
            }

            String jiraKey = extractJiraKey(prTitle);
            String headCommitSha = pr.path("source").path("commit").path("hash").asText(null);
            if (headCommitSha != null && headCommitSha.isBlank()) headCommitSha = null;

            LOG.infof("Bitbucket webhook: triggering review for PR #%s (%s -> %s) on %s (head: %s)",
                    prId, sourceBranch, destBranch, repoFullName,
                    headCommitSha != null ? headCommitSha.substring(0, Math.min(8, headCommitSha.length())) : "unknown");

            // Evaluate hooks for PR created/updated events
            HookEvalResult hookResult = HookEvalResult.empty();
            if (repoParts.length == 2) {
                String triggerType = event.equals("pullrequest:created") ? "scm.pr_created" : "scm.pr_updated";
                var context = Map.of(
                        "prId", prId,
                        "sourceBranch", sourceBranch,
                        "targetBranch", destBranch,
                        "prTitle", prTitle,
                        "author", prAuthor,
                        "platform", "bitbucket",
                        "repoSlug", repoSlug
                );
                hookResult = hookEvaluator.evaluateByTrigger(triggerType, workspace, repoSlug, repoUrl, context);

                if (!hookResult.isEmpty()) {
                    LOG.infof("Bitbucket webhook: triggered %d hook jobs for %s", hookResult.size(), triggerType);
                }
            }

            audit("bitbucket", event, workspace, repoSlug, prId, prAuthor,
                    "review_triggered", hookResult.hookNames(), rawPayload);
            return submitReviewJob(repoUrl, prId, destBranch, jiraKey, headCommitSha, workspace, repoSlug, prAuthor);

        } catch (Exception e) {
            LOG.errorf("Bitbucket webhook processing error: %s", e.getMessage());
            audit("bitbucket", event, null, null, null, null, "error", List.of(), rawPayload);
            return Response.ok(Map.of("action", "error", "message", e.getMessage())).build();
        }
    }





}
