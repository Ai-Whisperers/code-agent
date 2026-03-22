package com.eneve.agent.webhooks;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.eneve.agent.agent.HookEvaluator;
import com.eneve.agent.agent.JobQueue;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.agent.store.RepoSettingsStore;
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
public class GitLabWebhookResource {

    private static final Logger LOG = Logger.getLogger(GitLabWebhookResource.class);
    private static final Pattern JIRA_KEY_PATTERN = Pattern.compile("([A-Z][A-Z0-9]+-\\d+)");

    @Inject JobQueue jobQueue;
    @Inject JobStore jobStore;
    @Inject RepoSettingsStore repoSettingsStore;
    @Inject HookEvaluator hookEvaluator;

    @ConfigProperty(name = "review.webhook.skip-authors", defaultValue = "code-agent")
    String skipAuthors;

    @ConfigProperty(name = "review.webhook.require-title-keyword", defaultValue = "")
    String requireTitleKeyword;

    @ConfigProperty(name = "rules.repo.url", defaultValue = "")
    String defaultRulesRepoUrl;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @POST
    @Path("/gitlab/merge-request")
    @Operation(
            operationId = "gitlabMrWebhook",
            summary = "Handle GitLab merge request webhook events",
            description = "Receives GitLab webhook payloads for Merge Request Hook events. "
                    + "Automatically triggers an AI code review job when an MR is opened or updated. "
                    + "Evaluates automation hooks when an MR is merged. "
                    + "Skips MRs authored by the agent itself (configurable via review.webhook.skip-authors). "
                    + "Optionally requires a keyword in the MR title (review.webhook.require-title-keyword)."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Webhook processed"),
            @APIResponse(responseCode = "429", description = "Job queue is full")
    })
    public Response handleMrWebhook(
            @HeaderParam("X-Gitlab-Event") String eventHeader,
            String rawPayload) {
        try {
            JsonNode payload = objectMapper.readTree(rawPayload);

            String event = eventHeader != null ? eventHeader : "";
            LOG.infof("GitLab webhook received: %s", event);

            if (!event.equalsIgnoreCase("Merge Request Hook")) {
                return ok("ignored", "Unsupported event: " + event);
            }

            JsonNode attrs = payload.path("object_attributes");
            String action = attrs.path("action").asText("");

            boolean isOpenOrUpdate = action.equals("open") || action.equals("update")
                    || action.equals("reopen");
            boolean isMerge = action.equals("merge");

            if (!isOpenOrUpdate && !isMerge) {
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
                return ok("ignored", "Missing MR IID or project path in payload");
            }

            // Derive org (namespace) and repoSlug from path_with_namespace
            String[] pathParts = projectPath.split("/", 2);
            String namespace = pathParts.length == 2 ? pathParts[0] : projectPath;
            String repoSlug = pathParts.length == 2 ? pathParts[1] : projectPath;

            if (isMerge) {
                LOG.infof("GitLab webhook: MR !%s merged (%s -> %s) on %s — evaluating hooks",
                        mrIid, sourceBranch, targetBranch, projectPath);
                var jobIds = hookEvaluator.evaluate(namespace, repoSlug, repoUrl, "merge", targetBranch);
                return Response.ok(Map.of(
                        "action", "hooks_evaluated",
                        "hooksTriggered", jobIds.size(),
                        "jobIds", jobIds
                )).build();
            }

            if (!repoSettingsStore.isReviewEnabled(namespace, repoSlug)) {
                LOG.infof("GitLab webhook: skipping MR !%s — review disabled for %s", mrIid, projectPath);
                return ok("skipped", "Review disabled for " + projectPath);
            }

            if (shouldSkipAuthor(mrAuthor)) {
                LOG.infof("GitLab webhook: skipping MR !%s by '%s' (matches skip-authors)", mrIid, mrAuthor);
                return ok("skipped", "MR author '" + mrAuthor + "' is in skip list");
            }

            if (!requireTitleKeyword.isBlank()
                    && !mrTitle.toLowerCase().contains(requireTitleKeyword.toLowerCase())) {
                LOG.infof("GitLab webhook: skipping MR !%s — title does not contain '%s'",
                        mrIid, requireTitleKeyword);
                return ok("skipped", "MR title does not contain required keyword: " + requireTitleKeyword);
            }

            String jiraKey = extractJiraKey(mrTitle);
            String headCommitSha = attrs.path("last_commit").path("id").asText(null);
            if (headCommitSha != null && headCommitSha.isBlank()) headCommitSha = null;

            LOG.infof("GitLab webhook: triggering review for MR !%s (%s -> %s) on %s (head: %s)",
                    mrIid, sourceBranch, targetBranch, projectPath,
                    headCommitSha != null ? headCommitSha.substring(0, Math.min(8, headCommitSha.length())) : "unknown");

            return submitReviewJob(repoUrl, mrIid, targetBranch, jiraKey, headCommitSha);

        } catch (Exception e) {
            LOG.errorf("GitLab webhook processing error: %s", e.getMessage());
            return Response.ok(Map.of("action", "error", "message", e.getMessage())).build();
        }
    }

    private Response submitReviewJob(String repoUrl, String prId, String targetBranch, String jiraKey,
                                     String headCommitSha) {
        ReviewPrRequest request = new ReviewPrRequest(
                repoUrl,
                prId,
                targetBranch,
                jiraKey,
                defaultRulesRepoUrl.isBlank() ? null : defaultRulesRepoUrl,
                null,
                null,
                null,
                headCommitSha
        );

        String jobId = UUID.randomUUID().toString();
        JobRecord job = new JobRecord(jobId, request);
        jobStore.put(job);

        if (!jobQueue.submit(job)) {
            return Response.status(429)
                    .entity(Map.of("action", "rejected", "reason", "Job queue is full"))
                    .build();
        }

        LOG.infof("GitLab webhook triggered review job %s for MR !%s", jobId, prId);
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
