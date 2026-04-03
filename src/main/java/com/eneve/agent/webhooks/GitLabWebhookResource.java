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
 * Handles incoming GitLab webhook notifications for merge request events.
 * When an MR is opened or updated, automatically triggers a code review job.
 * When an MR is merged, evaluates automation hooks.
 */
@Path("/webhooks")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Webhooks", description = "Incoming webhook handlers for external integrations")
public class GitLabWebhookResource extends AbstractPrWebhookHandler {

    private static final Logger LOG = Logger.getLogger(GitLabWebhookResource.class);



    @POST
    @Path("/gitlab/merge-request")
    @Operation(
            operationId = "gitlabMrWebhook",
            summary = "Handle GitLab merge request webhook events",
            description = "Receives GitLab webhook payloads for Merge Request Hook events. "
                    + "Automatically triggers an AI code review job when an MR is opened or updated. "
                    + "Evaluates automation hooks when an MR is merged. "
                    + "Skips MRs authored by the agent itself (configurable via review.webhook.skip-authors)."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Webhook processed"),
            @APIResponse(responseCode = "429", description = "Job queue is full")
    })
    public Response handleMrWebhook(
            @HeaderParam("X-Gitlab-Event") String eventHeader,
            String rawPayload) {
        String event = eventHeader != null ? eventHeader : "";
        try {
            JsonNode payload = objectMapper.readTree(rawPayload);

            LOG.infof("GitLab webhook received: %s", event);

            if (!event.equalsIgnoreCase("Merge Request Hook")) {
                audit("gitlab", event, null, null, null, null, "ignored", List.of(), rawPayload);
                return ok("ignored", "Unsupported event: " + event);
            }

            JsonNode attrs = payload.path("object_attributes");
            String action = attrs.path("action").asText("");

            boolean isOpenOrUpdate = action.equals("open") || action.equals("update")
                    || action.equals("reopen");
            boolean isMerge = action.equals("merge");

            if (!isOpenOrUpdate && !isMerge) {
                audit("gitlab", event, null, null, null, null, "ignored", List.of(), rawPayload);
                return ok("ignored", "Unsupported MR action: " + action);
            }

            String mrIid = String.valueOf(attrs.path("iid").asInt(0));
            String mrTitle = attrs.path("title").asText("");
            String sourceBranch = attrs.path("source_branch").asText("");
            String targetBranch = attrs.path("target_branch").asText("");

            JsonNode authorNode = payload.path("user");
            String mrAuthor = authorNode.path("username").asText(
                    authorNode.path("name").asText(""));

            JsonNode projectNode = payload.path("project");
            String projectWebUrl = projectNode.path("web_url").asText("");
            String projectPath = projectNode.path("path_with_namespace").asText("");
            String repoUrl = projectWebUrl.isBlank() ? "" : projectWebUrl + ".git";

            if (mrIid.equals("0") || projectPath.isBlank()) {
                audit("gitlab", event, null, null, null, null, "ignored", List.of(), rawPayload);
                return ok("ignored", "Missing MR IID or project path in payload");
            }

            // Derive org (namespace) and repoSlug from path_with_namespace
            String[] pathParts = projectPath.split("/", 2);
            String namespace = pathParts.length == 2 ? pathParts[0] : projectPath;
            String repoSlug = pathParts.length == 2 ? pathParts[1] : projectPath;

            if (isMerge) {
                LOG.infof("GitLab webhook: MR !%s merged (%s -> %s) on %s — evaluating hooks and updating cache",
                        mrIid, sourceBranch, targetBranch, projectPath);

                // Legacy hook evaluation for backward compatibility
                HookEvalResult legacyResult = hookEvaluator.evaluate(namespace, repoSlug, repoUrl, "merge", targetBranch);

                // New generic hook evaluation with context
                var context = Map.of(
                        "prId", mrIid,
                        "sourceBranch", sourceBranch,
                        "targetBranch", targetBranch,
                        "prTitle", mrTitle,
                        "author", mrAuthor,
                        "platform", "gitlab",
                        "repoSlug", repoSlug
                );
                HookEvalResult newResult = hookEvaluator.evaluateByTrigger(
                        "scm.pr_merged", namespace, repoSlug, repoUrl, context);

                var totalJobIds = new ArrayList<>(legacyResult.jobIds());
                totalJobIds.addAll(newResult.jobIds());
                var allHookNames = new ArrayList<>(legacyResult.hookNames());
                allHookNames.addAll(newResult.hookNames());

                String mrUrl = attrs.path("url").asText("");
                String createdAt = attrs.path("created_at").asText("");
                String updatedAt = attrs.path("updated_at").asText("");

                return handleMergedPr("gitlab", namespace, repoSlug,
                        mrIid, mrUrl, mrTitle,
                        sourceBranch, targetBranch, mrAuthor,
                        createdAt, updatedAt, rawPayload,
                        "MERGED", totalJobIds, allHookNames);
            }

            if (!repoSettingsStore.isReviewEnabled(namespace, repoSlug)) {
                LOG.infof("GitLab webhook: skipping MR !%s — review disabled for %s", mrIid, projectPath);
                audit("gitlab", event, namespace, repoSlug, mrIid, mrAuthor, "skipped", List.of(), rawPayload);
                return ok("skipped", "Review disabled for " + projectPath);
            }

            if (shouldSkipAuthor(mrAuthor)) {
                LOG.infof("GitLab webhook: skipping MR !%s by '%s' (matches skip-authors)", mrIid, mrAuthor);
                audit("gitlab", event, namespace, repoSlug, mrIid, mrAuthor, "skipped", List.of(), rawPayload);
                return ok("skipped", "MR author '" + mrAuthor + "' is in skip list");
            }

            String jiraKey = extractJiraKey(mrTitle);
            String headCommitSha = attrs.path("last_commit").path("id").asText(null);
            if (headCommitSha != null && headCommitSha.isBlank()) headCommitSha = null;

            LOG.infof("GitLab webhook: triggering review for MR !%s (%s -> %s) on %s (head: %s)",
                    mrIid, sourceBranch, targetBranch, projectPath,
                    headCommitSha != null ? headCommitSha.substring(0, Math.min(8, headCommitSha.length())) : "unknown");

            // Evaluate hooks for MR opened/updated events
            String triggerType = action.equals("open") ? "scm.pr_created" : "scm.pr_updated";
            var context = Map.of(
                    "prId", mrIid,
                    "sourceBranch", sourceBranch,
                    "targetBranch", targetBranch,
                    "prTitle", mrTitle,
                    "author", mrAuthor,
                    "platform", "gitlab",
                    "repoSlug", repoSlug
            );
            HookEvalResult hookResult = hookEvaluator.evaluateByTrigger(
                    triggerType, namespace, repoSlug, repoUrl, context);

            if (!hookResult.isEmpty()) {
                LOG.infof("GitLab webhook: triggered %d hook jobs for %s", hookResult.size(), triggerType);
            }

            audit("gitlab", event, namespace, repoSlug, mrIid, mrAuthor,
                    "review_triggered", hookResult.hookNames(), rawPayload);
            return submitReviewJob(repoUrl, mrIid, targetBranch, jiraKey, headCommitSha, namespace, repoSlug, mrAuthor);

        } catch (Exception e) {
            LOG.errorf("GitLab webhook processing error: %s", e.getMessage());
            audit("gitlab", event, null, null, null, null, "error", List.of(), rawPayload);
            return Response.serverError().entity(Map.of("action", "error", "message", e.getMessage())).build();
        }
    }





}
