package com.eneve.agent.webhooks;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.eneve.agent.agent.HookEvaluator;
import com.eneve.agent.agent.JobQueue;
import com.eneve.agent.agent.model.WebhookAuditEntry;
import com.eneve.agent.agent.service.KnowledgeReindexQueue;
import com.eneve.agent.agent.store.CustomerRegistryStore;
import com.eneve.agent.agent.store.IntegrationFilterStore;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.agent.store.WebhookAuditStore;
import com.eneve.agent.aikido.AikidoIssueInfo;
import com.eneve.agent.aikido.AikidoService;
import com.eneve.agent.jira.JiraService;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.RepoCoordinates;
import com.eneve.agent.model.RunFixRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.eneve.agent.settings.SettingsService;
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
 * When any issue is assigned to the configured agent user, automatically triggers a fix job.
 * Supports both Aikido-sourced issues and regular JIRA issues.
 */
@Path("/webhooks")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Webhooks", description = "Incoming webhook handlers for external integrations")
public class JiraWebhookResource {

    private static final Logger LOG = Logger.getLogger(JiraWebhookResource.class);

    @Inject JobQueue jobQueue;
    @Inject JobStore jobStore;
    @Inject JiraService jiraService;
    @Inject AikidoService aikidoService;
    @Inject HookEvaluator hookEvaluator;
    @Inject SettingsService settings;
    @Inject KnowledgeReindexQueue reindexQueue;
    @Inject CustomerRegistryStore registryStore;
    @Inject WebhookAuditStore webhookAuditStore;
    @Inject IntegrationFilterStore integrationFilterStore;

    private String agentAssignee()  { return settings.get("jira.agent.assignee", ""); }
    private String defaultRepoUrl() { return settings.get("jira.agent.default-repo-url", ""); }

    @Inject ObjectMapper objectMapper;

    @POST
    @Path("/jira")
    @Operation(
            operationId = "jiraWebhook",
            summary = "Handle JIRA Cloud webhook events",
            description = "Receives JIRA Cloud webhook payloads for issue_created and issue_updated events. "
                    + "When an issue is assigned to the configured agent user, "
                    + "automatically triggers a fix job. If Aikido is configured and the issue is Aikido-sourced, "
                    + "uses the enriched /aikido-fix flow. Otherwise falls back to /quick-fix behavior."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Webhook processed — job triggered, or event ignored (not a matching issue or assignee change)",
                    content = @Content(schema = @Schema(example = "{\"action\": \"job_triggered\", \"jobId\": \"...\", \"branch\": \"...\"}"))),
            @APIResponse(responseCode = "429", description = "Job queue is full")
    })
    public Response handleJiraWebhook(String rawPayload) {
        String issueKey = "";
        String event = "";
        String action = "error";
        try {
            JsonNode payload = objectMapper.readTree(rawPayload);
            event = payload.path("webhookEvent").asText("");
            LOG.infof("JIRA webhook received: %s", event);

            if (!event.equals("jira:issue_created") && !event.equals("jira:issue_updated")) {
                LOG.debugf("Ignoring JIRA webhook event: %s", event);
                action = "ignored";
                audit("jira", event, "", "", action, "", rawPayload);
                return ok(action, "Unsupported event type: " + event);
            }

            JsonNode issue = payload.path("issue");
            issueKey = issue.path("key").asText("");
            JsonNode fields = issue.path("fields");

            if (issueKey.isBlank()) {
                action = "ignored";
                audit("jira", event, "", "", action, "", rawPayload);
                return ok(action, "No issue key in payload");
            }

            String projectKey = fields.path("project").path("key").asText("");
            String reporter = fields.path("reporter").path("displayName").asText("");

            // Check integration filters — project must be enabled and webhook must be enabled
            if (!projectKey.isBlank() && !integrationFilterStore.isEnabled("jira", projectKey)) {
                LOG.infof("JIRA webhook: ignoring event for %s — project %s is disabled", issueKey, projectKey);
                action = "ignored";
                audit("jira", event, projectKey, issueKey, action, reporter, rawPayload);
                return ok(action, "Project disabled: " + projectKey);
            }
            if (!projectKey.isBlank() && !integrationFilterStore.isWebhookEnabled("jira", projectKey)) {
                LOG.infof("JIRA webhook: ignoring event for %s — webhooks disabled for project %s", issueKey, projectKey);
                action = "ignored";
                audit("jira", event, projectKey, issueKey, action, reporter, rawPayload);
                return ok(action, "Webhook disabled for project: " + projectKey);
            }

            // Always reindex tracked issues, regardless of assignee
            triggerKnowledgeReindex(issueKey, fields);

            // Check assignee — fix-job and hooks only trigger for the agent user
            String assigneeDisplay = fields.path("assignee").path("displayName").asText("");
            String assigneeEmail = fields.path("assignee").path("emailAddress").asText("");
            String assigneeAccountId = fields.path("assignee").path("accountId").asText("");

            if (!isAgentAssignee(assigneeDisplay, assigneeEmail, assigneeAccountId)) {
                LOG.infof("JIRA webhook: ignoring fix job for %s (assignee: %s, not agent user)", issueKey, assigneeDisplay);
                action = "reindex_only";
                audit("jira", event, projectKey, issueKey, action, reporter, rawPayload);
                return ok("ignored", "Not assigned to agent user");
            }

            // For issue_updated, verify the assignee actually changed (not just any field update)
            if (event.equals("jira:issue_updated") && !assigneeChangedInChangelog(payload)) {
                LOG.infof("JIRA webhook: ignoring fix job for %s update (assignee didn't change)", issueKey);
                action = "reindex_only";
                audit("jira", event, projectKey, issueKey, action, reporter, rawPayload);
                return ok("ignored", "Assignee did not change in this update");
            }

            LOG.infof("JIRA webhook: %s assigned to agent — triggering fix", issueKey);

            // Try Aikido-enriched flow first, fall back to quick-fix
            Response response = triggerFixJob(issueKey, fields);

            // Additionally evaluate hooks for Jira events
            evaluateJiraHooks(event, issueKey, fields);

            action = "job_triggered";
            audit("jira", event, projectKey, issueKey, action, reporter, rawPayload);
            return response;

        } catch (Exception e) {
            LOG.errorf("JIRA webhook processing error: %s", e.getMessage());
            audit("jira", event, "", issueKey, "error", "", rawPayload);
            return Response.serverError().entity(Map.of("action", "error", "message", e.getMessage())).build();
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
            repoUrl = defaultRepoUrl();
        }
        if (repoUrl == null || repoUrl.isBlank()) {
            LOG.warnf("JIRA webhook: cannot trigger job for %s — no repo URL (set jira.agent.default-repo-url)", issueKey);
            return ok("skipped", "No repository URL available for " + issueKey);
        }

        String branchName = "agent/" + issueKey + "-" + branchSuffix;

        RunFixRequest fullRequest = new RunFixRequest(
                repoUrl, branchName, issueKey, prompt,
                "develop", null, null, null, null, null, null
        );

        String jobId = UUID.randomUUID().toString();
        JobRecord job = new JobRecord(jobId, fullRequest);
        jobStore.put(job);

        if (!jobQueue.submit(job)) {
            return Response.status(429)
                    .entity(Map.of("action", "rejected", "reason", "Job queue is full"))
                    .build();
        }

        LOG.infof("JIRA webhook triggered job %s for %s (branch: %s)", jobId, issueKey, branchName);
        return Response.ok(Map.of(
                "action", "job_triggered",
                "jobId", jobId,
                "jiraKey", issueKey,
                "branch", branchName
        )).build();
    }

    private boolean isAgentAssignee(String displayName, String email, String accountId) {
        if (agentAssignee().isBlank()) {
            LOG.warn("jira.agent.assignee not configured — webhook will not trigger jobs");
            return false;
        }
        return agentAssignee().equalsIgnoreCase(displayName)
                || agentAssignee().equalsIgnoreCase(email)
                || agentAssignee().equalsIgnoreCase(accountId);
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

    private void evaluateJiraHooks(String event, String issueKey, JsonNode fields) {
        try {
            // Extract fields for context
            String summary = fields.path("summary").asText("");
            String projectKey = fields.path("project").path("key").asText("");
            String issueType = fields.path("issuetype").path("name").asText("");
            String assigneeDisplay = fields.path("assignee").path("displayName").asText("");
            String priority = fields.path("priority").path("name").asText("");
            
            // Determine trigger type
            String triggerType;
            if (event.equals("jira:issue_created")) {
                triggerType = "jira.issue_created";
            } else if (event.equals("jira:issue_updated")) {
                triggerType = "jira.issue_updated";
            } else {
                triggerType = "jira.issue_assigned"; // fallback for assignee changes
            }
            
            // Build context map
            var context = Map.of(
                    "issueKey", issueKey,
                    "summary", summary,
                    "projectKey", projectKey,
                    "issueType", issueType,
                    "assignee", assigneeDisplay,
                    "priority", priority
            );
            
            // Parse workspace/repo from default repo URL if available
            String workspace = "unknown";
            String repoSlug = "unknown";
            if (defaultRepoUrl() != null && !defaultRepoUrl().isBlank()) {
                try {
                    RepoCoordinates coords = RepoCoordinates.parse(defaultRepoUrl());
                    workspace = coords.organization();
                    repoSlug = coords.repository();
                } catch (IllegalArgumentException e) {
                    LOG.debugf("Could not parse default repo URL for hook evaluation: %s", e.getMessage());
                }
            }
            
            // Evaluate hooks
            var hookJobIds = hookEvaluator.evaluateByTrigger(
                    triggerType, workspace, repoSlug, defaultRepoUrl(), context);
            
            if (!hookJobIds.isEmpty()) {
                LOG.infof("JIRA webhook: triggered %d hook jobs for %s", hookJobIds.size(), triggerType);
            }
            
        } catch (Exception e) {
            LOG.warnf("Failed to evaluate Jira hooks for %s: %s", issueKey, e.getMessage());
        }
    }

    private void audit(String platform, String eventType, String workspace,
                       String issueKey, String action, String author, String rawPayload) {
        try {
            webhookAuditStore.save(WebhookAuditEntry.create(
                    platform, eventType, workspace, issueKey, null, author, action, List.of(), rawPayload));
        } catch (Exception e) {
            LOG.warnf("Failed to save Jira webhook audit entry (non-fatal): %s", e.getMessage());
        }
    }

    private void triggerKnowledgeReindex(String issueKey, JsonNode fields) {
        try {
            String projectKey = fields.path("project").path("key").asText("");
            if (!projectKey.isBlank() && registryStore.findByJiraProject(projectKey).isPresent()) {
                boolean accepted = reindexQueue.submitJiraIssue(issueKey);
                LOG.debugf("JIRA webhook: knowledge reindex for %s %s",
                        issueKey, accepted ? "queued" : "skipped (duplicate or queue full)");
            }
        } catch (Exception e) {
            LOG.warnf("Failed to trigger knowledge reindex for Jira issue %s: %s", issueKey, e.getMessage());
        }
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
