package com.eneve.agent.planner;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.eneve.agent.agent.AiCallStore;
import com.eneve.agent.jira.JiraService;
import com.eneve.agent.util.UrlUtils;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * REST endpoints for creating, reviewing, editing, and approving execution plans.
 * Plans start as DRAFT after AI generation, allow human editing, then transition
 * to APPROVED when the human is satisfied.
 */
@Path("/plans")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Execution Plans", description = "AI-powered execution plan generation with human review and approval")
public class PlanResource {

    private static final Logger LOG = Logger.getLogger(PlanResource.class);

    @Inject PlannerService plannerService;
    @Inject PlanStore planStore;
    @Inject JiraService jiraService;
    @Inject PlanOrchestratorService orchestratorService;
    @Inject AiCallStore aiCallStore;

    @ConfigProperty(name = "planner.enabled", defaultValue = "true")
    boolean plannerEnabled;

    @ConfigProperty(name = "metrics.cc-threshold", defaultValue = "10")
    int defaultCcThreshold;

    @ConfigProperty(name = "metrics.max-iterations", defaultValue = "3")
    int defaultMaxIterations;

    // ─── Create / Generate ──────────────────────────────────────────────

    @POST
    @Operation(
            operationId = "createPlan",
            summary = "Generate an execution plan from a specification",
            description = "Sends the specification to Claude, which decomposes it into an ordered "
                    + "set of phases and steps. The resulting plan is saved as DRAFT for human review."
    )
    @APIResponses({
            @APIResponse(responseCode = "201", description = "Plan created in DRAFT status"),
            @APIResponse(responseCode = "400", description = "Missing required fields"),
            @APIResponse(responseCode = "503", description = "Planner is disabled or AI call failed")
    })
    public Response create(
            @RequestBody(description = "Specification and repository context", required = true)
            CreatePlanRequest request) {

        if (!plannerEnabled) {
            return Response.status(503).entity(Map.of("error", "Planner is disabled")).build();
        }
        if (request == null || request.repoUrl() == null || request.repoUrl().isBlank()) {
            return Response.status(400).entity(Map.of("error", "repoUrl is required")).build();
        }
        if (request.specText() == null || request.specText().isBlank()) {
            return Response.status(400).entity(Map.of("error", "specText is required")).build();
        }

        String sourceType = request.sourceType() != null ? request.sourceType() : "FREE_TEXT";
        String targetBranch = request.targetBranch() != null ? request.targetBranch() : "main";

        LOG.infof("Creating plan: sourceType=%s, repoUrl=%s", sourceType, UrlUtils.stripCredentials(request.repoUrl()));

        ExecutionPlan plan = plannerService.generatePlan(
                request.specText(), request.repoUrl(), targetBranch, sourceType, request.sourceRef());

        if (plan == null) {
            return Response.status(503).entity(Map.of("error", "Plan generation failed")).build();
        }

        planStore.create(plan);
        LOG.infof("Plan %s created with %d phase(s)", plan.planId(),
                plan.planData().phases() != null ? plan.planData().phases().size() : 0);

        return Response.status(201).entity(plan).build();
    }

    @GET
    @Path("/jira/search")
    @Operation(
            operationId = "searchJiraIssues",
            summary = "Search Jira issues by text",
            description = "Searches Jira issues whose summary matches the query and returns key, summary, and status."
    )
    @APIResponse(responseCode = "200", description = "List of matching Jira issues")
    public Response searchJira(
            @Parameter(description = "Text to search for in Jira issue summaries")
            @QueryParam("q") String q,
            @Parameter(description = "Maximum results to return (default 10, max 20)")
            @QueryParam("maxResults") @jakarta.ws.rs.DefaultValue("10") int maxResults) {

        String jql = (q != null && !q.isBlank())
                ? "summary ~ \"" + q.replace("\"", "") + "\" ORDER BY updated DESC"
                : "ORDER BY updated DESC";
        int cap = Math.min(Math.max(1, maxResults), 20);

        // Use system Jira credentials for the search
        var creds = new JiraService.JiraCredentials(
                jiraService.getBaseUrl(),
                jiraService.getUser(),
                jiraService.getApiToken()
        );
        
        List<JiraSearchResult> results = jiraService.searchIssues(jql, cap, creds).stream()
                .map(i -> new JiraSearchResult(i.key(), i.summary(), i.status()))
                .toList();
        return Response.ok(results).build();
    }

    @POST
    @Path("/from-jira/{jiraKey}")
    @Operation(
            operationId = "createPlanFromJira",
            summary = "Generate an execution plan from a Jira ticket",
            description = "Fetches the Jira ticket summary and description, then sends them to Claude "
                    + "to decompose into an execution plan saved as DRAFT for human review."
    )
    @APIResponses({
            @APIResponse(responseCode = "201", description = "Plan created in DRAFT status"),
            @APIResponse(responseCode = "400", description = "Missing required fields"),
            @APIResponse(responseCode = "404", description = "Jira ticket not found"),
            @APIResponse(responseCode = "503", description = "Planner is disabled or AI call failed")
    })
    public Response createFromJira(
            @Parameter(description = "Jira issue key (e.g. PROJ-123)", required = true)
            @PathParam("jiraKey") String jiraKey,
            @RequestBody(description = "Repository context", required = true)
            CreatePlanRequest request) {

        if (!plannerEnabled) {
            return Response.status(503).entity(Map.of("error", "Planner is disabled")).build();
        }
        String ticketText = jiraService.fetchIssuePrompt(jiraKey);
        if (ticketText == null || ticketText.isBlank()) {
            return Response.status(404)
                    .entity(Map.of("error", "Could not fetch Jira ticket: " + jiraKey))
                    .build();
        }

        String repoUrl = (request != null && request.repoUrl() != null) ? request.repoUrl() : "";
        String targetBranch = (request != null && request.targetBranch() != null) ? request.targetBranch() : "main";

        LOG.infof("Creating plan from Jira %s, repoUrl=%s", jiraKey, UrlUtils.stripCredentials(repoUrl));

        ExecutionPlan plan = plannerService.generatePlan(
                ticketText, repoUrl, targetBranch, "JIRA", jiraKey);

        if (plan == null) {
            return Response.status(503).entity(Map.of("error", "Plan generation failed")).build();
        }

        planStore.create(plan);
        LOG.infof("Plan %s created from Jira %s with %d phase(s)", plan.planId(), jiraKey,
                plan.planData().phases() != null ? plan.planData().phases().size() : 0);

        return Response.status(201).entity(plan).build();
    }

    @POST
    @Path("/improve-quality")
    @Operation(
            operationId = "improveQuality",
            summary = "Start an iterative cyclomatic complexity improvement loop",
            description = "Creates an execution plan that measures cyclomatic complexity (METRICS phase), "
                    + "then iteratively refactors high-CC methods (FIX phases) until the configured threshold "
                    + "is met, max iterations are exhausted, or no further improvement is possible. "
                    + "The plan starts as DRAFT unless autoApprove is true."
    )
    @APIResponses({
            @APIResponse(responseCode = "201", description = "Quality improvement plan created"),
            @APIResponse(responseCode = "400", description = "Missing required fields"),
            @APIResponse(responseCode = "503", description = "Planner is disabled")
    })
    public Response improveQuality(
            @RequestBody(description = "Repository and quality target parameters", required = true)
            ImproveQualityRequest request) {

        if (!plannerEnabled) {
            return Response.status(503).entity(Map.of("error", "Planner is disabled")).build();
        }
        if (request == null || request.repoUrl() == null || request.repoUrl().isBlank()) {
            return Response.status(400).entity(Map.of("error", "repoUrl is required")).build();
        }
        if (request.branch() == null || request.branch().isBlank()) {
            return Response.status(400).entity(Map.of("error", "branch is required")).build();
        }

        int ccThreshold = request.ccThreshold() > 0 ? request.ccThreshold() : defaultCcThreshold;
        int maxIterations = request.maxIterations() > 0 ? request.maxIterations() : defaultMaxIterations;
        String targetBranch = request.targetBranch() != null && !request.targetBranch().isBlank()
                ? request.targetBranch() : "main";

        String planId = "quality-" + UUID.randomUUID().toString().substring(0, 8);
        String title = "Quality Improvement: CC ≤ " + ccThreshold + " on " + request.branch();

        // Phase 1: baseline METRICS
        PlanStep metricsStep = new PlanStep(
                "baseline-metrics", "METRICS",
                "Measure baseline cyclomatic complexity",
                null,
                "PENDING", null,
                Map.of(
                        "branch", request.branch(),
                        "ccThreshold", String.valueOf(ccThreshold),
                        "maxIterations", String.valueOf(maxIterations)),
                null);

        PlanPhase metricsPhase = new PlanPhase(1, "Baseline Metrics", true, List.of(metricsStep));
        PlanData planData = new PlanData(List.of(metricsPhase));

        String safeRepoUrl = UrlUtils.stripCredentials(request.repoUrl());

        ExecutionPlan plan = new ExecutionPlan(
                planId,
                PlanStatus.DRAFT.name(),
                "QUALITY",
                request.branch(),
                safeRepoUrl,
                targetBranch,
                title,
                planData,
                Instant.now(),
                Instant.now(),
                null,
                null,
                null,
                null,
                null, // conversationId
                null, // markdownContent  
                null  // workspacePath
        );

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
                    LOG.warnf("Quality improvement plan %s created and approved but execution start failed: %s",
                            planId, e.getMessage());
                }
            }
        }

        return planStore.find(planId)
                .map(p -> Response.status(201).entity(p).build())
                .orElse(Response.status(500).entity(Map.of("error", "Plan not found after creation")).build());
    }

    // ─── Read ───────────────────────────────────────────────────────────

    @GET
    @Operation(
            operationId = "listPlans",
            summary = "List execution plans",
            description = "Returns all plans, optionally filtered by status or conversationId."
    )
    @APIResponse(responseCode = "200", description = "List of plans")
    public Response list(
            @Parameter(description = "Filter by status (optional)")
            @QueryParam("status") String status,
            @Parameter(description = "Filter by conversation ID (optional)")
            @QueryParam("conversationId") String conversationId) {

        List<ExecutionPlan> plans;
        if (conversationId != null && !conversationId.isBlank()) {
            plans = planStore.findByConversationId(conversationId);
        } else if (status != null && !status.isBlank()) {
            plans = planStore.listByStatus(status.toUpperCase());
        } else {
            plans = planStore.listAll();
        }
        return Response.ok(plans).build();
    }

    @GET
    @Path("/{planId}")
    @Operation(
            operationId = "getPlan",
            summary = "Get a plan by ID",
            description = "Returns the full execution plan including all phases and steps."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Execution plan"),
            @APIResponse(responseCode = "404", description = "Plan not found")
    })
    public Response get(
            @Parameter(description = "Plan ID", required = true)
            @PathParam("planId") String planId) {

        return planStore.find(planId)
                .map(p -> Response.ok(p).build())
                .orElse(Response.status(404)
                        .entity(Map.of("error", "Plan not found: " + planId))
                        .build());
    }

    @GET
    @Path("/{planId}/conversation")
    @Operation(
            operationId = "getPlanConversation",
            summary = "Get the AI conversation log for a plan",
            description = "Returns the AI call records for the plan, including the full prompt sent to "
                    + "the model and the raw response received. Each entry also includes token usage and timing."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "AI call records with prompt and response text"),
            @APIResponse(responseCode = "404", description = "No AI conversation found for this plan")
    })
    public Response getConversation(
            @Parameter(description = "Plan ID", required = true)
            @PathParam("planId") String planId) {

        var calls = aiCallStore.findByJobId(planId);
        if (calls.isEmpty()) {
            return Response.status(404)
                    .entity(Map.of("error", "No AI conversation found for plan: " + planId))
                    .build();
        }
        return Response.ok(calls).build();
    }

    // ─── Edit (DRAFT only) ──────────────────────────────────────────────

    @PUT
    @Path("/{planId}")
    @Operation(
            operationId = "replacePlan",
            summary = "Replace the full plan data",
            description = "Replaces all phases and steps in the plan. Only allowed when status is DRAFT."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Plan updated"),
            @APIResponse(responseCode = "404", description = "Plan not found"),
            @APIResponse(responseCode = "409", description = "Plan is not in DRAFT status")
    })
    public Response replace(
            @Parameter(description = "Plan ID", required = true)
            @PathParam("planId") String planId,
            @RequestBody(description = "New plan data", required = true)
            PlanData planData) {

        Optional<ExecutionPlan> existing = planStore.find(planId);
        if (existing.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "Plan not found: " + planId)).build();
        }
        if (!PlanStatus.DRAFT.name().equals(existing.get().status())) {
            return Response.status(409)
                    .entity(Map.of("error", "Plan is not in DRAFT status, cannot edit"))
                    .build();
        }

        planStore.updatePlanData(planId, planData);
        return planStore.find(planId)
                .map(p -> Response.ok(p).build())
                .orElse(Response.status(404).entity(Map.of("error", "Plan not found after update")).build());
    }

    @PATCH
    @Path("/{planId}/markdown")
    @Operation(
            operationId = "updateMarkdown",
            summary = "Update plan markdown content",
            description = "Updates the markdown content of the plan."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Markdown updated"),
            @APIResponse(responseCode = "404", description = "Plan not found")
    })
    public Response updateMarkdown(
            @Parameter(description = "Plan ID", required = true)
            @PathParam("planId") String planId,
            @RequestBody(description = "Markdown content", required = true)
            Map<String, String> body) {

        Optional<ExecutionPlan> existing = planStore.find(planId);
        if (existing.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "Plan not found: " + planId)).build();
        }

        String markdownContent = body.get("markdownContent");
        if (markdownContent == null) {
            return Response.status(400).entity(Map.of("error", "markdownContent is required")).build();
        }

        planStore.updateMarkdownContent(planId, markdownContent);
        return planStore.find(planId)
                .map(p -> Response.ok(p).build())
                .orElse(Response.status(404).entity(Map.of("error", "Plan not found after update")).build());
    }

    @PATCH
    @Path("/{planId}/steps/{stepId}")
    @Operation(
            operationId = "updateStep",
            summary = "Edit a single step",
            description = "Updates the title, prompt, jobType, and/or params of a step. Only allowed when status is DRAFT."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Step updated"),
            @APIResponse(responseCode = "404", description = "Plan or step not found"),
            @APIResponse(responseCode = "409", description = "Plan is not in DRAFT status")
    })
    public Response updateStep(
            @Parameter(description = "Plan ID", required = true)
            @PathParam("planId") String planId,
            @Parameter(description = "Step ID", required = true)
            @PathParam("stepId") String stepId,
            @RequestBody(description = "Step fields to update", required = true)
            UpdateStepRequest request) {

        Optional<ExecutionPlan> existing = planStore.find(planId);
        if (existing.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "Plan not found: " + planId)).build();
        }
        ExecutionPlan plan = existing.get();
        if (!PlanStatus.DRAFT.name().equals(plan.status())) {
            return Response.status(409)
                    .entity(Map.of("error", "Plan is not in DRAFT status, cannot edit"))
                    .build();
        }

        boolean found = false;
        List<PlanPhase> updatedPhases = new ArrayList<>();
        for (PlanPhase phase : plan.planData().phases()) {
            List<PlanStep> updatedSteps = new ArrayList<>();
            for (PlanStep step : phase.steps()) {
                if (step.stepId().equals(stepId)) {
                    found = true;
                    updatedSteps.add(step.withUpdates(
                            request.title(), request.prompt(), request.jobType(), request.params()));
                } else {
                    updatedSteps.add(step);
                }
            }
            updatedPhases.add(new PlanPhase(phase.order(), phase.name(), phase.gateOnSuccess(), updatedSteps));
        }

        if (!found) {
            return Response.status(404)
                    .entity(Map.of("error", "Step not found: " + stepId + " in plan " + planId))
                    .build();
        }

        planStore.updatePlanData(planId, new PlanData(updatedPhases));
        return planStore.find(planId)
                .map(p -> Response.ok(p).build())
                .orElse(Response.status(404).entity(Map.of("error", "Plan not found after update")).build());
    }

    @POST
    @Path("/{planId}/steps")
    @Operation(
            operationId = "addStep",
            summary = "Add a step to a phase",
            description = "Appends a new step to the specified phase. Only allowed when status is DRAFT."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Step added"),
            @APIResponse(responseCode = "400", description = "Missing required fields"),
            @APIResponse(responseCode = "404", description = "Plan or phase not found"),
            @APIResponse(responseCode = "409", description = "Plan is not in DRAFT status")
    })
    public Response addStep(
            @Parameter(description = "Plan ID", required = true)
            @PathParam("planId") String planId,
            @RequestBody(description = "Phase order and new step definition", required = true)
            AddStepRequest request) {

        if (request == null || request.step() == null) {
            return Response.status(400).entity(Map.of("error", "step is required")).build();
        }

        Optional<ExecutionPlan> existing = planStore.find(planId);
        if (existing.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "Plan not found: " + planId)).build();
        }
        ExecutionPlan plan = existing.get();
        if (!PlanStatus.DRAFT.name().equals(plan.status())) {
            return Response.status(409)
                    .entity(Map.of("error", "Plan is not in DRAFT status, cannot edit"))
                    .build();
        }

        boolean phaseFound = false;
        List<PlanPhase> updatedPhases = new ArrayList<>();
        for (PlanPhase phase : plan.planData().phases()) {
            if (phase.order() == request.phaseOrder()) {
                phaseFound = true;
                List<PlanStep> updatedSteps = new ArrayList<>(phase.steps());
                updatedSteps.add(request.step());
                updatedPhases.add(new PlanPhase(phase.order(), phase.name(), phase.gateOnSuccess(), updatedSteps));
            } else {
                updatedPhases.add(phase);
            }
        }

        if (!phaseFound) {
            return Response.status(404)
                    .entity(Map.of("error", "Phase with order " + request.phaseOrder() + " not found in plan " + planId))
                    .build();
        }

        planStore.updatePlanData(planId, new PlanData(updatedPhases));
        return planStore.find(planId)
                .map(p -> Response.ok(p).build())
                .orElse(Response.status(404).entity(Map.of("error", "Plan not found after update")).build());
    }

    @DELETE
    @Path("/{planId}/steps/{stepId}")
    @Operation(
            operationId = "removeStep",
            summary = "Remove a step from a plan",
            description = "Deletes the step with the given ID from whichever phase contains it. Only allowed when status is DRAFT."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Step removed"),
            @APIResponse(responseCode = "404", description = "Plan or step not found"),
            @APIResponse(responseCode = "409", description = "Plan is not in DRAFT status")
    })
    public Response removeStep(
            @Parameter(description = "Plan ID", required = true)
            @PathParam("planId") String planId,
            @Parameter(description = "Step ID to remove", required = true)
            @PathParam("stepId") String stepId) {

        Optional<ExecutionPlan> existing = planStore.find(planId);
        if (existing.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "Plan not found: " + planId)).build();
        }
        ExecutionPlan plan = existing.get();
        if (!PlanStatus.DRAFT.name().equals(plan.status())) {
            return Response.status(409)
                    .entity(Map.of("error", "Plan is not in DRAFT status, cannot edit"))
                    .build();
        }

        boolean found = false;
        List<PlanPhase> updatedPhases = new ArrayList<>();
        for (PlanPhase phase : plan.planData().phases()) {
            List<PlanStep> updatedSteps = new ArrayList<>();
            for (PlanStep step : phase.steps()) {
                if (step.stepId().equals(stepId)) {
                    found = true;
                } else {
                    updatedSteps.add(step);
                }
            }
            updatedPhases.add(new PlanPhase(phase.order(), phase.name(), phase.gateOnSuccess(), updatedSteps));
        }

        if (!found) {
            return Response.status(404)
                    .entity(Map.of("error", "Step not found: " + stepId + " in plan " + planId))
                    .build();
        }

        planStore.updatePlanData(planId, new PlanData(updatedPhases));
        return Response.ok(Map.of("action", "removed", "stepId", stepId, "planId", planId)).build();
    }

    @POST
    @Path("/{planId}/phases")
    @Operation(
            operationId = "addPhase",
            summary = "Add a new phase to a plan",
            description = "Appends a new phase (with its steps) to the plan. Only allowed when status is DRAFT."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Phase added"),
            @APIResponse(responseCode = "400", description = "Missing required fields"),
            @APIResponse(responseCode = "404", description = "Plan not found"),
            @APIResponse(responseCode = "409", description = "Plan is not in DRAFT status")
    })
    public Response addPhase(
            @Parameter(description = "Plan ID", required = true)
            @PathParam("planId") String planId,
            @RequestBody(description = "Phase to add", required = true)
            PlanPhase phase) {

        if (phase == null) {
            return Response.status(400).entity(Map.of("error", "phase is required")).build();
        }

        Optional<ExecutionPlan> existing = planStore.find(planId);
        if (existing.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "Plan not found: " + planId)).build();
        }
        ExecutionPlan plan = existing.get();
        if (!PlanStatus.DRAFT.name().equals(plan.status())) {
            return Response.status(409)
                    .entity(Map.of("error", "Plan is not in DRAFT status, cannot edit"))
                    .build();
        }

        List<PlanPhase> updatedPhases = new ArrayList<>(plan.planData().phases());
        updatedPhases.add(phase);

        planStore.updatePlanData(planId, new PlanData(updatedPhases));
        return planStore.find(planId)
                .map(p -> Response.ok(p).build())
                .orElse(Response.status(404).entity(Map.of("error", "Plan not found after update")).build());
    }

    @DELETE
    @Path("/{planId}/phases/{phaseOrder}")
    @Operation(
            operationId = "removePhase",
            summary = "Remove a phase from a plan",
            description = "Deletes the phase with the given order number and all its steps. Only allowed when status is DRAFT."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Phase removed"),
            @APIResponse(responseCode = "404", description = "Plan or phase not found"),
            @APIResponse(responseCode = "409", description = "Plan is not in DRAFT status")
    })
    public Response removePhase(
            @Parameter(description = "Plan ID", required = true)
            @PathParam("planId") String planId,
            @Parameter(description = "Phase order number", required = true)
            @PathParam("phaseOrder") int phaseOrder) {

        Optional<ExecutionPlan> existing = planStore.find(planId);
        if (existing.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "Plan not found: " + planId)).build();
        }
        ExecutionPlan plan = existing.get();
        if (!PlanStatus.DRAFT.name().equals(plan.status())) {
            return Response.status(409)
                    .entity(Map.of("error", "Plan is not in DRAFT status, cannot edit"))
                    .build();
        }

        long before = plan.planData().phases().size();
        List<PlanPhase> updatedPhases = plan.planData().phases().stream()
                .filter(p -> p.order() != phaseOrder)
                .toList();

        if (updatedPhases.size() == before) {
            return Response.status(404)
                    .entity(Map.of("error", "Phase with order " + phaseOrder + " not found in plan " + planId))
                    .build();
        }

        planStore.updatePlanData(planId, new PlanData(updatedPhases));
        return Response.ok(Map.of("action", "removed", "phaseOrder", phaseOrder, "planId", planId)).build();
    }

    // ─── Lifecycle ──────────────────────────────────────────────────────

    @POST
    @Path("/{planId}/approve")
    @Operation(
            operationId = "approvePlan",
            summary = "Approve the plan for execution",
            description = "Transitions the plan from DRAFT to APPROVED. "
                    + "An approved plan is ready to be executed by the orchestrator."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Plan approved"),
            @APIResponse(responseCode = "404", description = "Plan not found"),
            @APIResponse(responseCode = "409", description = "Plan is not in DRAFT status")
    })
    public Response approve(
            @Parameter(description = "Plan ID", required = true)
            @PathParam("planId") String planId) {

        Optional<ExecutionPlan> existing = planStore.find(planId);
        if (existing.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "Plan not found: " + planId)).build();
        }
        if (!PlanStatus.DRAFT.name().equals(existing.get().status())) {
            return Response.status(409)
                    .entity(Map.of("error", "Only DRAFT plans can be approved"))
                    .build();
        }

        planStore.approve(planId);
        LOG.infof("Plan %s approved", planId);

        return planStore.find(planId)
                .map(p -> Response.ok(p).build())
                .orElse(Response.status(404).entity(Map.of("error", "Plan not found after approval")).build());
    }

    @POST
    @Path("/{planId}/execute")
    @Operation(
            operationId = "executePlan",
            summary = "Execute an approved plan",
            description = "Transitions the plan from APPROVED to EXECUTING and begins submitting its steps "
                    + "phase by phase to the job queue. Each step becomes a job tracked in the plan data."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Plan execution started, status is now EXECUTING"),
            @APIResponse(responseCode = "404", description = "Plan not found"),
            @APIResponse(responseCode = "409", description = "Plan is not in APPROVED status")
    })
    public Response execute(
            @Parameter(description = "Plan ID", required = true)
            @PathParam("planId") String planId) {

        Optional<ExecutionPlan> existing = planStore.find(planId);
        if (existing.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "Plan not found: " + planId)).build();
        }
        if (!PlanStatus.APPROVED.name().equals(existing.get().status())) {
            return Response.status(409)
                    .entity(Map.of("error", "Only APPROVED plans can be executed (current: " + existing.get().status() + ")"))
                    .build();
        }

        try {
            orchestratorService.startExecution(planId);
        } catch (Exception e) {
            LOG.errorf("Failed to start execution for plan %s: %s", planId, e.getMessage());
            return Response.status(500).entity(Map.of("error", e.getMessage())).build();
        }

        LOG.infof("Plan %s execution started", planId);

        return planStore.find(planId)
                .map(p -> Response.ok(p).build())
                .orElse(Response.status(404).entity(Map.of("error", "Plan not found after execution start")).build());
    }

    @POST
    @Path("/{planId}/approve-pr")
    @Operation(
            operationId = "approvePlanPr",
            summary = "Approve and merge the plan's pull request",
            description = "Merges the pull request created by the completed plan. "
                    + "Only available for COMPLETED plans that have a PR URL."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "PR merged successfully"),
            @APIResponse(responseCode = "404", description = "Plan not found"),
            @APIResponse(responseCode = "409", description = "Plan has no PR or is not in COMPLETED status"),
            @APIResponse(responseCode = "500", description = "Merge failed")
    })
    public Response approvePlanPr(
            @Parameter(description = "Plan ID", required = true)
            @PathParam("planId") String planId) {

        Optional<ExecutionPlan> planOpt = planStore.find(planId);
        if (planOpt.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "Plan not found: " + planId)).build();
        }

        ExecutionPlan plan = planOpt.get();
        if (!PlanStatus.COMPLETED.name().equals(plan.status())) {
            return Response.status(409)
                    .entity(Map.of("error", "Only COMPLETED plans can have their PRs approved (current: " + plan.status() + ")"))
                    .build();
        }

        if (plan.prUrl() == null || plan.prUrl().isBlank()) {
            return Response.status(409)
                    .entity(Map.of("error", "Plan has no pull request to approve"))
                    .build();
        }

        try {
            planStore.approvePlanPr(planId, plan.prUrl());
            LOG.infof("Plan %s PR approved and merged: %s", planId, plan.prUrl());
            return Response.ok(Map.of("status", "merged", "planId", planId, "prUrl", plan.prUrl())).build();
        } catch (Exception e) {
            LOG.errorf("Failed to approve PR for plan %s: %s", planId, e.getMessage());
            return Response.status(500)
                    .entity(Map.of("error", "Failed to merge PR: " + e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/{planId}/reject-pr")
    @Operation(
            operationId = "rejectPlanPr",
            summary = "Reject and decline the plan's pull request",
            description = "Declines the pull request created by the completed plan with an optional reason. "
                    + "Only available for COMPLETED plans that have a PR URL."
    )
    @RequestBody(
            description = "Optional rejection reason",
            content = @Content(schema = @Schema(implementation = RejectPrRequest.class))
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "PR declined"),
            @APIResponse(responseCode = "404", description = "Plan not found"),
            @APIResponse(responseCode = "409", description = "Plan has no PR or is not in COMPLETED status")
    })
    public Response rejectPlanPr(
            @Parameter(description = "Plan ID", required = true)
            @PathParam("planId") String planId,
            RejectPrRequest request) {

        Optional<ExecutionPlan> planOpt = planStore.find(planId);
        if (planOpt.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "Plan not found: " + planId)).build();
        }

        ExecutionPlan plan = planOpt.get();
        if (!PlanStatus.COMPLETED.name().equals(plan.status())) {
            return Response.status(409)
                    .entity(Map.of("error", "Only COMPLETED plans can have their PRs rejected (current: " + plan.status() + ")"))
                    .build();
        }

        if (plan.prUrl() == null || plan.prUrl().isBlank()) {
            return Response.status(409)
                    .entity(Map.of("error", "Plan has no pull request to reject"))
                    .build();
        }

        String reason = request != null ? request.reason() : null;
        try {
            planStore.rejectPlanPr(planId, plan.prUrl(), reason);
            LOG.infof("Plan %s PR rejected: %s (reason: %s)", planId, plan.prUrl(), reason);
            return Response.ok(Map.of("status", "rejected", "planId", planId, "prUrl", plan.prUrl())).build();
        } catch (Exception e) {
            LOG.errorf("Failed to reject PR for plan %s: %s", planId, e.getMessage());
            return Response.status(500)
                    .entity(Map.of("error", "Failed to decline PR: " + e.getMessage()))
                    .build();
        }
    }

    @DELETE
    @Path("/{planId}")
    @Operation(
            operationId = "deletePlan",
            summary = "Delete a plan",
            description = "Permanently removes the plan. Only DRAFT plans can be deleted."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Plan deleted"),
            @APIResponse(responseCode = "404", description = "Plan not found"),
            @APIResponse(responseCode = "409", description = "Plan is not in DRAFT status")
    })
    public Response delete(
            @Parameter(description = "Plan ID", required = true)
            @PathParam("planId") String planId) {

        Optional<ExecutionPlan> existing = planStore.find(planId);
        if (existing.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "Plan not found: " + planId)).build();
        }
        if (!PlanStatus.DRAFT.name().equals(existing.get().status())) {
            return Response.status(409)
                    .entity(Map.of("error", "Only DRAFT plans can be deleted"))
                    .build();
        }

        planStore.delete(planId);
        return Response.ok(Map.of("action", "deleted", "planId", planId)).build();
    }

    // ─── Request/Response records ────────────────────────────────────────

    public record CreatePlanRequest(
            String repoUrl,
            String targetBranch,
            String sourceType,
            String sourceRef,
            String specText
    ) {}

    public record UpdateStepRequest(
            String title,
            String prompt,
            String jobType,
            Map<String, String> params
    ) {}

    public record JiraSearchResult(String key, String summary, String status) {}

    public record AddStepRequest(
            int phaseOrder,
            PlanStep step
    ) {}

    public record ImproveQualityRequest(
            @org.eclipse.microprofile.openapi.annotations.media.Schema(
                    required = true,
                    description = "Repository URL (HTTPS)",
                    example = "https://bitbucket.org/workspace/repo.git")
            String repoUrl,

            @org.eclipse.microprofile.openapi.annotations.media.Schema(
                    required = true,
                    description = "Branch to analyse and improve",
                    example = "main")
            String branch,

            @org.eclipse.microprofile.openapi.annotations.media.Schema(
                    description = "Target branch for generated fix PRs (default: main)",
                    example = "develop")
            String targetBranch,

            @org.eclipse.microprofile.openapi.annotations.media.Schema(
                    description = "CC threshold — methods above this value will be refactored. Default: 10",
                    example = "10")
            int ccThreshold,

            @org.eclipse.microprofile.openapi.annotations.media.Schema(
                    description = "Maximum number of fix iterations. Default: 3",
                    example = "3")
            int maxIterations,

            @org.eclipse.microprofile.openapi.annotations.media.Schema(
                    description = "If true, auto-approve the plan after creation. Default: false",
                    example = "false")
            boolean autoApprove,

            @org.eclipse.microprofile.openapi.annotations.media.Schema(
                    description = "If true (and autoApprove is true), start execution immediately. Default: false",
                    example = "false")
            boolean autoExecute
    ) {}

    public record RejectPrRequest(String reason) {}
}
