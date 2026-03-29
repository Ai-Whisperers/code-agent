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
 * Handles incoming GitHub webhook notifications for pull request events.
 * When a PR is opened or updated, automatically triggers a code review job.
 * When a PR is merged, evaluates automation hooks.
 */
@Path("/webhooks")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Webhooks", description = "Incoming webhook handlers for external integrations")
public class GitHubWebhookResource extends AbstractPrWebhookHandler {

    private static final Logger LOG = Logger.getLogger(GitHubWebhookResource.class);



    @POST
    @Path("/github/pull-request")
    @Operation(
            operationId = "githubPrWebhook",
            summary = "Handle GitHub pull request webhook events",
            description = "Receives GitHub webhook payloads for pull_request events. "
                    + "Automatically triggers an AI code review job when a PR is opened, synchronised, or reopened. "
                    + "Evaluates automation hooks when a PR is merged. "
                    + "Skips PRs authored by the agent itself (configurable via review.webhook.skip-authors)."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Webhook processed"),
            @APIResponse(responseCode = "429", description = "Job queue is full")
    })
    public Response handlePrWebhook(
            @HeaderParam("X-GitHub-Event") String eventHeader,
            String rawPayload) {
        String event = eventHeader != null ? eventHeader : "";
        try {
            JsonNode payload = objectMapper.readTree(rawPayload);

            LOG.infof("GitHub webhook received: %s", event);

            if (!event.equalsIgnoreCase("pull_request")) {
                audit("github", event, null, null, null, null, "ignored", List.of(), rawPayload);
                return ok("ignored", "Unsupported event: " + event);
            }

            String action = payload.path("action").asText("");

            boolean isOpenOrUpdate = action.equals("opened") || action.equals("synchronize")
                    || action.equals("reopened");
            boolean isMerge = action.equals("closed")
                    && payload.path("pull_request").path("merged").asBoolean(false);

            if (!isOpenOrUpdate && !isMerge) {
                audit("github", event, null, null, null, null, "ignored", List.of(), rawPayload);
                return ok("ignored", "Unsupported PR action: " + action);
            }

            JsonNode prNode = payload.path("pull_request");
            String prNumber = String.valueOf(prNode.path("number").asInt(0));
            String prTitle = prNode.path("title").asText("");
            String sourceBranch = prNode.path("head").path("ref").asText("");
            String targetBranch = prNode.path("base").path("ref").asText("");
            String headCommitSha = prNode.path("head").path("sha").asText(null);
            if (headCommitSha != null && headCommitSha.isBlank()) headCommitSha = null;

            String prAuthor = prNode.path("user").path("login").asText("");

            JsonNode repoNode = payload.path("repository");
            String fullName = repoNode.path("full_name").asText("");
            String repoHtmlUrl = repoNode.path("html_url").asText("");

            if (prNumber.equals("0") || fullName.isBlank()) {
                audit("github", event, null, null, null, null, "ignored", List.of(), rawPayload);
                return ok("ignored", "Missing PR number or repository full_name in payload");
            }

            String[] parts = fullName.split("/", 2);
            String org = parts.length == 2 ? parts[0] : fullName;
            String repo = parts.length == 2 ? parts[1] : fullName;
            String repoUrl = repoHtmlUrl.isBlank() ? "" : repoHtmlUrl + ".git";

            if (isMerge) {
                LOG.infof("GitHub webhook: PR #%s merged (%s -> %s) on %s — evaluating hooks",
                        prNumber, sourceBranch, targetBranch, fullName);

                // Legacy hook evaluation for backward compatibility
                HookEvalResult legacyResult = hookEvaluator.evaluate(org, repo, repoUrl, "merge", targetBranch);

                // New generic hook evaluation with context
                var context = Map.of(
                        "prId", prNumber,
                        "sourceBranch", sourceBranch,
                        "targetBranch", targetBranch,
                        "prTitle", prTitle,
                        "author", prAuthor,
                        "platform", "github",
                        "repoSlug", repo
                );
                HookEvalResult newResult = hookEvaluator.evaluateByTrigger(
                        "scm.pr_merged", org, repo, repoUrl, context);

                var totalJobIds = new ArrayList<>(legacyResult.jobIds());
                totalJobIds.addAll(newResult.jobIds());
                var allHookNames = new ArrayList<>(legacyResult.hookNames());
                allHookNames.addAll(newResult.hookNames());

                audit("github", event, org, repo, prNumber, prAuthor,
                        "hooks_evaluated", allHookNames, rawPayload);
                return Response.ok(Map.of(
                        "action", "hooks_evaluated",
                        "hooksTriggered", totalJobIds.size(),
                        "jobIds", totalJobIds
                )).build();
            }

            if (!repoSettingsStore.isReviewEnabled(org, repo)) {
                LOG.infof("GitHub webhook: skipping PR #%s — review disabled for %s", prNumber, fullName);
                audit("github", event, org, repo, prNumber, prAuthor, "skipped", List.of(), rawPayload);
                return ok("skipped", "Review disabled for " + fullName);
            }

            if (shouldSkipAuthor(prAuthor)) {
                LOG.infof("GitHub webhook: skipping PR #%s by '%s' (matches skip-authors)", prNumber, prAuthor);
                audit("github", event, org, repo, prNumber, prAuthor, "skipped", List.of(), rawPayload);
                return ok("skipped", "PR author '" + prAuthor + "' is in skip list");
            }

            String jiraKey = extractJiraKey(prTitle);

            LOG.infof("GitHub webhook: triggering review for PR #%s (%s -> %s) on %s (head: %s)",
                    prNumber, sourceBranch, targetBranch, fullName,
                    headCommitSha != null ? headCommitSha.substring(0, Math.min(8, headCommitSha.length())) : "unknown");

            // Evaluate hooks for PR created/updated events
            String triggerType = action.equals("opened") ? "scm.pr_created" : "scm.pr_updated";
            var context = Map.of(
                    "prId", prNumber,
                    "sourceBranch", sourceBranch,
                    "targetBranch", targetBranch,
                    "prTitle", prTitle,
                    "author", prAuthor,
                    "platform", "github",
                    "repoSlug", repo
            );
            HookEvalResult hookResult = hookEvaluator.evaluateByTrigger(
                    triggerType, org, repo, repoUrl, context);

            if (!hookResult.isEmpty()) {
                LOG.infof("GitHub webhook: triggered %d hook jobs for %s", hookResult.size(), triggerType);
            }

            audit("github", event, org, repo, prNumber, prAuthor,
                    "review_triggered", hookResult.hookNames(), rawPayload);
            return submitReviewJob(repoUrl, prNumber, targetBranch, jiraKey, headCommitSha, org, repo, prAuthor);

        } catch (Exception e) {
            LOG.errorf("GitHub webhook processing error: %s", e.getMessage());
            audit("github", event, null, null, null, null, "error", List.of(), rawPayload);
            return Response.ok(Map.of("action", "error", "message", e.getMessage())).build();
        }
    }





}
