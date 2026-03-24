package com.eneve.agent.webhooks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.eneve.agent.agent.HookEvaluator;
import com.eneve.agent.agent.JobQueue;
import com.eneve.agent.agent.model.HookEvalResult;
import com.eneve.agent.agent.model.WebhookAuditEntry;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.agent.store.RepoSettingsStore;
import com.eneve.agent.agent.store.WebhookAuditStore;
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
 * Handles incoming GitHub webhook notifications for pull request events.
 * When a PR is opened or updated, automatically triggers a code review job.
 * When a PR is merged, evaluates automation hooks.
 */
@Path("/webhooks")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Webhooks", description = "Incoming webhook handlers for external integrations")
public class GitHubWebhookResource {

    private static final Logger LOG = Logger.getLogger(GitHubWebhookResource.class);
    private static final Pattern JIRA_KEY_PATTERN = Pattern.compile("([A-Z][A-Z0-9]+-\\d+)");

    @Inject JobQueue jobQueue;
    @Inject JobStore jobStore;
    @Inject RepoSettingsStore repoSettingsStore;
    @Inject HookEvaluator hookEvaluator;
    @Inject WebhookAuditStore webhookAuditStore;

    @ConfigProperty(name = "review.webhook.skip-authors", defaultValue = "code-agent")
    String skipAuthors;

    @ConfigProperty(name = "review.webhook.require-title-keyword", defaultValue = "")
    String requireTitleKeyword;

    @ConfigProperty(name = "rules.repo.url", defaultValue = "")
    String defaultRulesRepoUrl;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @POST
    @Path("/github/pull-request")
    @Operation(
            operationId = "githubPrWebhook",
            summary = "Handle GitHub pull request webhook events",
            description = "Receives GitHub webhook payloads for pull_request events. "
                    + "Automatically triggers an AI code review job when a PR is opened, synchronised, or reopened. "
                    + "Evaluates automation hooks when a PR is merged. "
                    + "Skips PRs authored by the agent itself (configurable via review.webhook.skip-authors). "
                    + "Optionally requires a keyword in the PR title (review.webhook.require-title-keyword)."
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

            if (!requireTitleKeyword.isBlank()
                    && !prTitle.toLowerCase().contains(requireTitleKeyword.toLowerCase())) {
                LOG.infof("GitHub webhook: skipping PR #%s — title does not contain '%s'",
                        prNumber, requireTitleKeyword);
                audit("github", event, org, repo, prNumber, prAuthor, "skipped", List.of(), rawPayload);
                return ok("skipped", "PR title does not contain required keyword: " + requireTitleKeyword);
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
            return submitReviewJob(repoUrl, prNumber, targetBranch, jiraKey, headCommitSha);

        } catch (Exception e) {
            LOG.errorf("GitHub webhook processing error: %s", e.getMessage());
            audit("github", event, null, null, null, null, "error", List.of(), rawPayload);
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

        LOG.infof("GitHub webhook triggered review job %s for PR #%s", jobId, prId);
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

    private void audit(String platform, String eventType, String workspace, String repoSlug,
                        String prId, String author, String action,
                        List<String> hooksExecuted, String payload) {
        try {
            webhookAuditStore.save(WebhookAuditEntry.create(
                    platform, eventType, workspace, repoSlug, prId, author, action, hooksExecuted, payload));
        } catch (Exception e) {
            LOG.warnf("Failed to save webhook audit entry (non-fatal): %s", e.getMessage());
        }
    }

    private static Response ok(String action, String reason) {
        return Response.ok(Map.of("action", action, "reason", reason)).build();
    }
}
