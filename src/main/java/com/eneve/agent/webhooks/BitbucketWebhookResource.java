package com.eneve.agent.webhooks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.eneve.agent.agent.model.HookEvalResult;
import com.eneve.agent.model.OpenPrEntry;
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
            boolean isRejected   = event.equals("pullrequest:rejected");

            if (!isCreateOrUpdate && !isFulfilled && !isRejected) {
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
            String prUrl = pr.path("links").path("html").path("href").asText("");
            String createdOn = pr.path("created_on").asText("");
            String updatedOn = pr.path("updated_on").asText("");

            if (prId.isBlank() || repoFullName.isBlank()) {
                audit("bitbucket", event, null, null, null, null, "ignored", List.of(), rawPayload);
                return ok("ignored", "Missing PR ID or repository in payload");
            }

            String[] repoParts = repoFullName.split("/", 2);
            String workspace = repoParts.length == 2 ? repoParts[0] : "";
            String repoSlug = repoParts.length == 2 ? repoParts[1] : "";
            String repoUrl = "https://bitbucket.org/" + repoFullName + ".git";

            if (isFulfilled || isRejected) {
                String cacheStatus = isFulfilled ? "MERGED" : "DECLINED";

                if (repoParts.length != 2) {
                    audit("bitbucket", event, null, null, prId, prAuthor, "ignored", List.of(), rawPayload);
                    return ok("ignored", "Could not parse workspace/repo from " + repoFullName);
                }

                LOG.infof("Bitbucket webhook: PR #%s %s (%s -> %s) on %s — updating cache and cancelling jobs",
                        prId, cacheStatus.toLowerCase(), sourceBranch, destBranch, repoFullName);

                List<String> hookJobIds = new ArrayList<>();
                List<String> hookNames = new ArrayList<>();

                if (isFulfilled) {
                    // Legacy hook evaluation for backward compatibility
                    HookEvalResult legacyResult = hookEvaluator.evaluate(
                            workspace, repoSlug, repoUrl, event, destBranch);

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

                    hookJobIds.addAll(legacyResult.jobIds());
                    hookJobIds.addAll(newResult.jobIds());
                    hookNames.addAll(legacyResult.hookNames());
                    hookNames.addAll(newResult.hookNames());
                }

                return handleMergedPr("bitbucket", workspace, repoSlug,
                        prId, prUrl, prTitle,
                        sourceBranch, destBranch, prAuthor,
                        createdOn, updatedOn, rawPayload,
                        cacheStatus, hookJobIds, hookNames);
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

            if (repoParts.length == 2) {
                try {
                    prCacheStore.upsert(new OpenPrEntry(
                            workspace, repoSlug, prId, prUrl, prTitle,
                            sourceBranch, destBranch, prAuthor,
                            createdOn, updatedOn, null, "OPEN", false));
                } catch (Exception e) {
                    LOG.warnf("Bitbucket webhook: failed to upsert PR cache for %s/%s#%s: %s",
                            workspace, repoSlug, prId, e.getMessage());
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

