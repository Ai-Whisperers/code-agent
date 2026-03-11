package com.eneve.agent.webhooks;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

import com.eneve.agent.agent.AgentRunner;
import com.eneve.agent.agent.JobStore;
import com.eneve.agent.aikido.AikidoIssueInfo;
import com.eneve.agent.aikido.AikidoService;
import com.eneve.agent.jira.JiraService;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobStatus;
import com.eneve.agent.model.RunFixRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
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
 * Handles incoming JIRA Cloud webhooks.
 * When a Bug is assigned to the configured agent user, automatically triggers a fix job.
 * Supports both Aikido-sourced issues and regular JIRA bugs.
 */
@Path("/webhooks")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Webhooks", description = "Incoming webhook handlers for external integrations")
public class JiraWebhookResource {

    private static final Logger LOG = Logger.getLogger(JiraWebhookResource.class);
    private static final int MAX_CONCURRENT_JOBS = 3;

    @Inject AgentRunner agentRunner;
    @Inject JobStore jobStore;
    @Inject JiraService jiraService;
    @Inject AikidoService aikidoService;

    @ConfigProperty(name = "jira.agent.assignee", defaultValue = "")
    String agentAssignee;

    @ConfigProperty(name = "jira.agent.issue-types", defaultValue = "Bug")
    String allowedIssueTypes;

    @ConfigProperty(name = "jira.agent.default-repo-url", defaultValue = "")
    String defaultRepoUrl;

    private final ExecutorService executor = Executors.newFixedThreadPool(MAX_CONCURRENT_JOBS);
    private final Semaphore semaphore = new Semaphore(MAX_CONCURRENT_JOBS);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @POST
    @Path("/jira")
    @Operation(
            operationId = "jiraWebhook",
            summary = "Handle JIRA Cloud webhook events",
            description = "Receives JIRA Cloud webhook payloads for issue_created and issue_updated events. "
                    + "When a matching issue (correct type + assigned to the agent user) is detected, "
                    + "automatically triggers a fix job. If Aikido is configured and the issue is Aikido-sourced, "
                    + "uses the enriched /aikido-fix flow. Otherwise falls back to /quick-fix behavior."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Webhook processed (job may or may not have been triggered)",
                    content = @Content(schema = @Schema(example = "{\"action\": \"job_triggered\", \"jobId\": \"...\", \"branch\": \"...\"}"))),
            @APIResponse(responseCode = "200", description = "Event ignored (not a matching issue or assignee change)"),
            @APIResponse(responseCode = "429", description = "Too many concurrent jobs")
    })
    public Response handleJiraWebhook(String rawPayload) {
        try {
            JsonNode payload = objectMapper.readTree(rawPayload);
            String event = payload.path("webhookEvent").asText("");
            LOG.infof("JIRA webhook received: %s", event);

            if (!event.equals("jira:issue_created") && !event.equals("jira:issue_updated")) {
                LOG.debugf("Ignoring JIRA webhook event: %s", event);
                return ok("ignored", "Unsupported event type: " + event);
            }

            JsonNode issue = payload.path("issue");
            String issueKey = issue.path("key").asText("");
            JsonNode fields = issue.path("fields");

            if (issueKey.isBlank()) {
                return ok("ignored", "No issue key in payload");
            }

            // Check issue type
            String issueType = fields.path("issuetype").path("name").asText("");
            if (!isAllowedIssueType(issueType)) {
                LOG.infof("JIRA webhook: ignoring %s (type: %s, allowed: %s)", issueKey, issueType, allowedIssueTypes);
                return ok("ignored", "Issue type '" + issueType + "' not in allowed list");
            }

            // Check assignee
            String assigneeDisplay = fields.path("assignee").path("displayName").asText("");
            String assigneeEmail = fields.path("assignee").path("emailAddress").asText("");
            String assigneeAccountId = fields.path("assignee").path("accountId").asText("");

            if (!isAgentAssignee(assigneeDisplay, assigneeEmail, assigneeAccountId)) {
                LOG.infof("JIRA webhook: ignoring %s (assignee: %s, not agent user)", issueKey, assigneeDisplay);
                return ok("ignored", "Not assigned to agent user");
            }

            // For issue_updated, verify the assignee actually changed (not just any field update)
            if (event.equals("jira:issue_updated") && !assigneeChangedInChangelog(payload)) {
                LOG.infof("JIRA webhook: ignoring %s update (assignee didn't change)", issueKey);
                return ok("ignored", "Assignee did not change in this update");
            }

            LOG.infof("JIRA webhook: %s assigned to agent (type: %s) — triggering fix", issueKey, issueType);

            // Try Aikido-enriched flow first, fall back to quick-fix
            return triggerFixJob(issueKey, fields);

        } catch (Exception e) {
            LOG.errorf("JIRA webhook processing error: %s", e.getMessage());
            return Response.ok(Map.of("action", "error", "message", e.getMessage())).build();
        }
    }

    private Response triggerFixJob(String issueKey, JsonNode fields) {
        String repoUrl = null;
        String prompt = null;
        String branchSuffix;

        // 1. Try Aikido-enriched flow
        if (aikidoService.isEnabled()) {
            LOG.infof("Attempting Aikido-enriched fix for %s", issueKey);
            Integer groupId = aikidoService.findIssueGroupByJiraKey(issueKey);

            if (groupId == null) {
                var candidateIds = jiraService.extractAikidoCandidateIds(issueKey);
                for (Integer candidateId : candidateIds) {
                    AikidoIssueInfo info = aikidoService.getIssueGroupDetail(candidateId);
                    if (info != null) {
                        groupId = candidateId;
                        break;
                    }
                }
            }

            if (groupId != null) {
                AikidoIssueInfo issueInfo = aikidoService.getIssueGroupDetail(groupId);
                if (issueInfo != null) {
                    prompt = issueInfo.toPromptSection();
                    if (issueInfo.repoUrl() != null && !issueInfo.repoUrl().isBlank()) {
                        repoUrl = issueInfo.repoUrl();
                    }
                    branchSuffix = slugify(issueInfo.packageName() + "-"
                            + (issueInfo.fixedVersion() != null ? issueInfo.fixedVersion() : "fix"));

                    LOG.infof("Aikido context resolved for %s: package=%s, cve=%s",
                            issueKey, issueInfo.packageName(), issueInfo.cveId());

                    return submitJob(issueKey, repoUrl, prompt, branchSuffix);
                }
            }
            LOG.infof("No Aikido issue found for %s, falling back to JIRA description", issueKey);
        }

        // 2. Fall back to JIRA-based flow (like /quick-fix)
        String summary = fields.path("summary").asText("");
        if (summary.isBlank()) {
            summary = jiraService.fetchIssueSummary(issueKey);
        }
        branchSuffix = summary != null ? slugify(summary) : "fix";

        return submitJob(issueKey, repoUrl, prompt, branchSuffix);
    }

    private Response submitJob(String issueKey, String repoUrl, String prompt, String branchSuffix) {
        if (repoUrl == null || repoUrl.isBlank()) {
            repoUrl = defaultRepoUrl;
        }
        if (repoUrl == null || repoUrl.isBlank()) {
            LOG.warnf("JIRA webhook: cannot trigger job for %s — no repo URL (set jira.agent.default-repo-url)", issueKey);
            return ok("skipped", "No repository URL available for " + issueKey);
        }

        if (!semaphore.tryAcquire()) {
            return Response.status(429)
                    .entity(Map.of("action", "rejected", "reason", "Too many concurrent jobs"))
                    .build();
        }

        String branchName = "agent/" + issueKey + "-" + branchSuffix;

        RunFixRequest fullRequest = new RunFixRequest(
                repoUrl, branchName, issueKey, prompt,
                "develop", null, null, null, null
        );

        String jobId = UUID.randomUUID().toString();
        JobRecord job = new JobRecord(jobId, fullRequest);
        jobStore.put(job);

        executor.submit(() -> {
            try {
                agentRunner.execute(job);
            } catch (Exception e) {
                LOG.errorf("Unhandled error in webhook-triggered job %s: %s", jobId, e.getMessage());
                job.setStatus(JobStatus.FAILED);
                job.setErrorMessage("Unhandled error: " + e.getMessage());
            } finally {
                semaphore.release();
            }
        });

        LOG.infof("JIRA webhook triggered job %s for %s (branch: %s)", jobId, issueKey, branchName);
        return Response.ok(Map.of(
                "action", "job_triggered",
                "jobId", jobId,
                "jiraKey", issueKey,
                "branch", branchName
        )).build();
    }

    private boolean isAllowedIssueType(String issueType) {
        if (allowedIssueTypes.isBlank()) return true;
        for (String allowed : allowedIssueTypes.split(",")) {
            if (allowed.trim().equalsIgnoreCase(issueType)) return true;
        }
        return false;
    }

    private boolean isAgentAssignee(String displayName, String email, String accountId) {
        if (agentAssignee.isBlank()) {
            LOG.warn("jira.agent.assignee not configured — webhook will not trigger jobs");
            return false;
        }
        return agentAssignee.equalsIgnoreCase(displayName)
                || agentAssignee.equalsIgnoreCase(email)
                || agentAssignee.equalsIgnoreCase(accountId);
    }

    private boolean assigneeChangedInChangelog(JsonNode payload) {
        JsonNode items = payload.path("changelog").path("items");
        if (!items.isArray()) return false;
        for (JsonNode item : items) {
            if ("assignee".equalsIgnoreCase(item.path("field").asText(""))) {
                return true;
            }
        }
        return false;
    }

    private static Response ok(String action, String reason) {
        return Response.ok(Map.of("action", action, "reason", reason)).build();
    }

    private static String slugify(String text) {
        if (text == null || text.isBlank()) return "fix";
        String slug = text.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        if (slug.length() > 60) {
            slug = slug.substring(0, 60).replaceAll("-$", "");
        }
        return slug;
    }
}
