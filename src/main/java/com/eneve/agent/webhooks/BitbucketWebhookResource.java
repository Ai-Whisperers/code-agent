package com.eneve.agent.webhooks;

import java.util.ArrayList;
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

            boolean isCreateOrUpdate = event.equals("pullrequest:created") || event.equals("pullrequest:updated");
            boolean isFulfilled = event.equals("pullrequest:fulfilled");

            if (!isCreateOrUpdate && !isFulfilled) {
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

            String[] repoParts = repoFullName.split("/", 2);
            String repoUrl = "https://bitbucket.org/" + repoFullName + ".git";

            if (isFulfilled) {
                LOG.infof("Bitbucket webhook: PR #%s merged (%s -> %s) on %s — evaluating hooks",
                        prId, sourceBranch, destBranch, repoFullName);
                if (repoParts.length == 2) {
                    // Legacy hook evaluation for backward compatibility
                    var legacyJobIds = hookEvaluator.evaluate(
                            repoParts[0], repoParts[1], repoUrl, event, destBranch);
                    
                    // New generic hook evaluation with context
                    var context = Map.of(
                            "prId", prId,
                            "sourceBranch", sourceBranch,
                            "targetBranch", destBranch,
                            "prTitle", prTitle,
                            "author", prAuthor,
                            "platform", "bitbucket",
                            "repoSlug", repoParts[1]
                    );
                    var newJobIds = hookEvaluator.evaluateByTrigger(
                            "scm.pr_merged", repoParts[0], repoParts[1], repoUrl, context);
                    
                    var totalJobIds = new ArrayList<>(legacyJobIds);
                    totalJobIds.addAll(newJobIds);
                    
                    return Response.ok(Map.of(
                            "action", "hooks_evaluated",
                            "hooksTriggered", totalJobIds.size(),
                            "jobIds", totalJobIds
                    )).build();
                }
                return ok("ignored", "Could not parse workspace/repo from " + repoFullName);
            }

            if (repoParts.length == 2
                    && !repoSettingsStore.isReviewEnabled(repoParts[0], repoParts[1])) {
                LOG.infof("Bitbucket webhook: skipping PR #%s — review disabled for %s", prId, repoFullName);
                return ok("skipped", "Review disabled for " + repoFullName);
            }

            if (shouldSkipAuthor(prAuthor)) {
                LOG.infof("Bitbucket webhook: skipping PR #%s by '%s' (matches skip-authors)", prId, prAuthor);
                return ok("skipped", "PR author '" + prAuthor + "' is in skip list");
            }

            if (!requireTitleKeyword.isBlank()
                    && !prTitle.toLowerCase().contains(requireTitleKeyword.toLowerCase())) {
                LOG.infof("Bitbucket webhook: skipping PR #%s — title does not contain '%s'",
                        prId, requireTitleKeyword);
                return ok("skipped", "PR title does not contain required keyword: " + requireTitleKeyword);
            }

            String jiraKey = extractJiraKey(prTitle);
            String headCommitSha = pr.path("source").path("commit").path("hash").asText(null);
            if (headCommitSha != null && headCommitSha.isBlank()) headCommitSha = null;

            LOG.infof("Bitbucket webhook: triggering review for PR #%s (%s -> %s) on %s (head: %s)",
                    prId, sourceBranch, destBranch, repoFullName,
                    headCommitSha != null ? headCommitSha.substring(0, Math.min(8, headCommitSha.length())) : "unknown");

            // Evaluate hooks for PR created/updated events
            if (repoParts.length == 2) {
                String triggerType = event.equals("pullrequest:created") ? "scm.pr_created" : "scm.pr_updated";
                var context = Map.of(
                        "prId", prId,
                        "sourceBranch", sourceBranch,
                        "targetBranch", destBranch,
                        "prTitle", prTitle,
                        "author", prAuthor,
                        "platform", "bitbucket",
                        "repoSlug", repoParts[1]
                );
                var hookJobIds = hookEvaluator.evaluateByTrigger(
                        triggerType, repoParts[0], repoParts[1], repoUrl, context);
                
                if (!hookJobIds.isEmpty()) {
                    LOG.infof("Bitbucket webhook: triggered %d hook jobs for %s", hookJobIds.size(), triggerType);
                }
            }

            return submitReviewJob(repoUrl, prId, destBranch, jiraKey, headCommitSha);

        } catch (Exception e) {
            LOG.errorf("Bitbucket webhook processing error: %s", e.getMessage());
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
