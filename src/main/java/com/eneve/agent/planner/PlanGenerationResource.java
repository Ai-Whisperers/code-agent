package com.eneve.agent.planner;

import com.eneve.agent.jira.JiraService;
import com.eneve.agent.settings.SettingsService;
import com.eneve.agent.util.UrlUtils;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI-driven plan generation endpoints: create from spec, from Jira, quality improvement, and replan.
 */
@Path("/plans")
@RolesAllowed({"app_developer", "app_admin"})
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Execution Plans")
public class PlanGenerationResource {

    private static final Logger LOG = Logger.getLogger(PlanGenerationResource.class);

    @Inject PlannerService plannerService;
    @Inject PlanStore planStore;
    @Inject PlanTrackedJobStore trackedJobStore;
    @Inject JiraService jiraService;
    @Inject PlanOrchestratorService orchestratorService;
    @Inject SettingsService settings;
    @Inject PlanAuthHelper authHelper;

    @POST
    @Operation(operationId = "createPlan", summary = "Generate an execution plan from a specification",
            description = "Sends the specification to Claude, which decomposes it into an ordered set of phases and steps. "
                    + "The resulting plan is saved as DRAFT for human review.")
    @APIResponses({
            @APIResponse(responseCode = "201", description = "Plan created in DRAFT status"),
            @APIResponse(responseCode = "400", description = "Missing required fields"),
            @APIResponse(responseCode = "503", description = "Planner is disabled or AI call failed")
    })
    public Response create(
            @RequestBody(description = "Specification and repository context", required = true)
            CreatePlanRequest request) {

        if (!Boolean.parseBoolean(settings.get("planner.enabled", "true"))) {
            return Response.status(503).entity(Map.of("error", "Planner is disabled")).build();
        }
        if (request == null || request.repoUrl() == null || request.repoUrl().isBlank()) {
            return Response.status(400).entity(Map.of("error", "repoUrl is required")).build();
        }
        if (request.specText() == null || request.specText().isBlank()) {
            return Response.status(400).entity(Map.of("error", "specText is required")).build();
        }
        if (request.specText().length() > 10_000) {
            return Response.status(400).entity(Map.of("error", "specText must be 10,000 characters or fewer")).build();
        }

        String sourceType = request.sourceType() != null ? request.sourceType() : "FREE_TEXT";
        String targetBranch = request.targetBranch() != null ? request.targetBranch() : "main";

        LOG.infof("Creating plan: sourceType=%s, repoUrl=%s", sourceType, UrlUtils.stripCredentials(request.repoUrl()));

        ExecutionPlan draft = plannerService.generatePlan(
                request.specText(), request.repoUrl(), targetBranch, sourceType, request.sourceRef());

        if (draft == null) {
            return Response.status(503).entity(Map.of("error", "Plan generation failed")).build();
        }

        String author = authHelper.resolveDisplayName();
        ExecutionPlan plan = new ExecutionPlan(
                draft.planId(), draft.status(), draft.sourceType(), draft.sourceRef(),
                draft.repoUrl(), draft.targetBranch(), draft.title(), draft.planData(),
                draft.createdAt(), draft.updatedAt(), draft.approvedAt(), draft.summary(),
                draft.errorMessage(), draft.prUrl(), draft.conversationId(),
                draft.markdownContent(), draft.workspacePath(), false, author);

        planStore.create(plan);
        LOG.infof("Plan %s created with %d phase(s)", plan.planId(),
                plan.planData().phases() != null ? plan.planData().phases().size() : 0);

        return Response.status(201).entity(plan).build();
    }

    @GET
    @Path("/jira/search")
    @Operation(operationId = "searchJiraIssues", summary = "Search Jira issues by text",
            description = "Searches Jira issues whose summary matches the query.")
    @APIResponse(responseCode = "200", description = "List of matching Jira issues")
    public Response searchJira(
            @Parameter(description = "Text to search for in Jira issue summaries") @QueryParam("q") String q,
            @Parameter(description = "Maximum results to return (default 10, max 20)")
            @QueryParam("maxResults") @DefaultValue("10") int maxResults) {

        String jql = (q != null && !q.isBlank())
                ? "summary ~ \"" + q.replace("\"", "") + "\" ORDER BY updated DESC"
                : "ORDER BY updated DESC";
        int cap = Math.min(Math.max(1, maxResults), 20);

        var creds = JiraService.JiraCredentials.basic(
                jiraService.getBaseUrl(), jiraService.getUser(), jiraService.getApiToken());

        List<JiraSearchResult> results = jiraService.searchIssues(jql, cap, creds).stream()
                .map(i -> new JiraSearchResult(i.key(), i.summary(), i.status()))
                .toList();
        return Response.ok(results).build();
    }

    @POST
    @Path("/from-jira/{jiraKey}")
    @Operation(operationId = "createPlanFromJira", summary = "Generate an execution plan from a Jira ticket",
            description = "Fetches the Jira ticket summary and description, then sends them to Claude to decompose into an execution plan.")
    @APIResponses({
            @APIResponse(responseCode = "201", description = "Plan created in DRAFT status"),
            @APIResponse(responseCode = "400", description = "Missing required fields"),
            @APIResponse(responseCode = "404", description = "Jira ticket not found"),
            @APIResponse(responseCode = "503", description = "Planner is disabled or AI call failed")
    })
    public Response createFromJira(
            @Parameter(description = "Jira issue key (e.g. PROJ-123)", required = true) @PathParam("jiraKey") String jiraKey,
            @RequestBody(description = "Repository context", required = true) CreatePlanRequest request) {

        if (!Boolean.parseBoolean(settings.get("planner.enabled", "true"))) {
            return Response.status(503).entity(Map.of("error", "Planner is disabled")).build();
        }
        String ticketText = jiraService.fetchIssuePrompt(jiraKey);
        if (ticketText == null || ticketText.isBlank()) {
            return Response.status(404).entity(Map.of("error", "Could not fetch Jira ticket: " + jiraKey)).build();
        }

        String repoUrl = (request != null && request.repoUrl() != null) ? request.repoUrl() : "";
        String targetBranch = (request != null && request.targetBranch() != null) ? request.targetBranch() : "main";

        LOG.infof("Creating plan from Jira %s, repoUrl=%s", jiraKey, UrlUtils.stripCredentials(repoUrl));

        ExecutionPlan draft = plannerService.generatePlan(ticketText, repoUrl, targetBranch, "JIRA", jiraKey);
        if (draft == null) {
            return Response.status(503).entity(Map.of("error", "Plan generation failed")).build();
        }

        String author = authHelper.resolveDisplayName();
        ExecutionPlan plan = new ExecutionPlan(
                draft.planId(), draft.status(), draft.sourceType(), draft.sourceRef(),
                draft.repoUrl(), draft.targetBranch(), draft.title(), draft.planData(),
                draft.createdAt(), draft.updatedAt(), draft.approvedAt(), draft.summary(),
                draft.errorMessage(), draft.prUrl(), draft.conversationId(),
                draft.markdownContent(), draft.workspacePath(), false, author);

        planStore.create(plan);
        LOG.infof("Plan %s created from Jira %s with %d phase(s)", plan.planId(), jiraKey,
                plan.planData().phases() != null ? plan.planData().phases().size() : 0);

        return Response.status(201).entity(plan).build();
    }

    @POST
    @Path("/improve-quality")
    @Operation(operationId = "improveQuality", summary = "Start an iterative cyclomatic complexity improvement loop",
            description = "Creates an execution plan that measures cyclomatic complexity then iteratively refactors high-CC methods.")
    @APIResponses({
            @APIResponse(responseCode = "201", description = "Quality improvement plan created"),
            @APIResponse(responseCode = "400", description = "Missing required fields"),
            @APIResponse(responseCode = "503", description = "Planner is disabled")
    })
    public Response improveQuality(
            @RequestBody(description = "Repository and quality target parameters", required = true)
            ImproveQualityRequest request) {

        if (!Boolean.parseBoolean(settings.get("planner.enabled", "true"))) {
            return Response.status(503).entity(Map.of("error", "Planner is disabled")).build();
        }
        if (request == null || request.repoUrl() == null || request.repoUrl().isBlank()) {
            return Response.status(400).entity(Map.of("error", "repoUrl is required")).build();
        }
        if (request.branch() == null || request.branch().isBlank()) {
            return Response.status(400).entity(Map.of("error", "branch is required")).build();
        }

        int ccThreshold = request.ccThreshold() > 0 ? request.ccThreshold()
                : Integer.parseInt(settings.get("metrics.cc-threshold", "10"));
        int maxIterations = request.maxIterations() > 0 ? request.maxIterations()
                : Integer.parseInt(settings.get("metrics.max-iterations", "3"));
        String targetBranch = request.targetBranch() != null && !request.targetBranch().isBlank()
                ? request.targetBranch() : "main";

        String planId = "quality-" + UUID.randomUUID().toString().substring(0, 8);
        String title = "Quality Improvement: CC ≤ " + ccThreshold + " on " + request.branch();

        PlanStep metricsStep = new PlanStep("baseline-metrics", "METRICS",
                "Measure baseline cyclomatic complexity", null, "PENDING", null,
                Map.of("branch", request.branch(), "ccThreshold", String.valueOf(ccThreshold),
                        "maxIterations", String.valueOf(maxIterations)), null);

        PlanData planData = new PlanData(List.of(new PlanPhase(1, "Baseline Metrics", true, List.of(metricsStep))));
        String safeRepoUrl = UrlUtils.stripCredentials(request.repoUrl());

        ExecutionPlan plan = new ExecutionPlan(planId, PlanStatus.DRAFT.name(), "QUALITY", request.branch(),
                safeRepoUrl, targetBranch, title, planData,
                Instant.now(), Instant.now(), null, null, null, null, null, null, null,
                false, authHelper.resolveDisplayName());

        planStore.create(plan);
        LOG.infof("Quality improvement plan %s created for %s (CC threshold=%d, maxIter=%d)",
                planId, safeRepoUrl, ccThreshold, maxIterations);

        if (request.autoApprove()) {
            planStore.approve(planId);
            if (request.autoExecute()) {
                try {
                    orchestratorService.startExecution(planId);
                    LOG.infof("Quality improvement plan %s auto-started execution", planId);
                } catch (Exception e) {
                    LOG.warnf("Quality improvement plan %s approved but execution start failed: %s", planId, e.getMessage());
                }
            }
        }

        return planStore.find(planId)
                .map(p -> Response.status(201).entity(p).build())
                .orElse(Response.status(500).entity(Map.of("error", "Plan not found after creation")).build());
    }

    @POST
    @Path("/{planId}/replan")
    @Operation(operationId = "replanFromMarkdown", summary = "Regenerate plan structure from markdown content",
            description = "Parses the plan's current markdown content into a fresh set of phases and steps. "
                    + "Only allowed when the plan is in PAUSED status.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Plan regenerated and transitioned to APPROVED"),
            @APIResponse(responseCode = "400", description = "Plan is not PAUSED or has no markdown content"),
            @APIResponse(responseCode = "404", description = "Plan not found")
    })
    public Response replan(
            @Parameter(description = "Plan ID", required = true) @PathParam("planId") String planId) {

        var existing = planStore.find(planId);
        if (existing.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "Plan not found: " + planId)).build();
        }
        ExecutionPlan plan = existing.get();
        if (!PlanStatus.PAUSED.name().equals(plan.status())) {
            return Response.status(400).entity(Map.of("error", "Only PAUSED plans can be replanned (current: " + plan.status() + ")")).build();
        }
        String markdown = plan.markdownContent();
        if (markdown == null || markdown.isBlank()) {
            return Response.status(400).entity(Map.of("error", "Plan has no markdown content to parse")).build();
        }

        List<PlanStep> steps = new ArrayList<>();
        int order = 1;
        for (String line : markdown.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- [ ] ") || trimmed.startsWith("- [x] ") || trimmed.startsWith("- [X] ")) {
                String title = trimmed.substring(6).trim();
                steps.add(new PlanStep("step-" + order, "FIX", title, title, "PENDING", null, Map.of(), null));
                order++;
            }
        }
        if (steps.isEmpty()) {
            return Response.status(400).entity(Map.of("error", "No checklist items found in markdown content")).build();
        }

        PlanData newPlanData = new PlanData(List.of(new PlanPhase(1, "Replanned Steps", true, steps)));
        trackedJobStore.deleteByPlanId(planId);
        planStore.updatePlanData(planId, newPlanData);
        planStore.approve(planId);

        LOG.infof("Plan %s replanned from markdown with %d step(s); transitioned to APPROVED", planId, steps.size());
        return planStore.find(planId)
                .map(p -> Response.ok(p).build())
                .orElse(Response.status(404).entity(Map.of("error", "Plan not found after replan")).build());
    }

    // ─── Request/Response records ─────────────────────────────────────────────

    public record CreatePlanRequest(String repoUrl, String targetBranch, String sourceType,
                                    String sourceRef, String specText) {}

    public record JiraSearchResult(String key, String summary, String status) {}

    public record ImproveQualityRequest(
            @Schema(required = true, description = "Repository URL (HTTPS)", example = "https://bitbucket.org/workspace/repo.git")
            String repoUrl,
            @Schema(required = true, description = "Branch to analyse and improve", example = "main")
            String branch,
            @Schema(description = "Target branch for generated fix PRs (default: main)", example = "develop")
            String targetBranch,
            @Schema(description = "CC threshold — methods above this value will be refactored. Default: 10", example = "10")
            int ccThreshold,
            @Schema(description = "Maximum number of fix iterations. Default: 3", example = "3")
            int maxIterations,
            @Schema(description = "If true, auto-approve the plan after creation. Default: false", example = "false")
            boolean autoApprove,
            @Schema(description = "If true (and autoApprove is true), start execution immediately. Default: false", example = "false")
            boolean autoExecute
    ) {}
}
