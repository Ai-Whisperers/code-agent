package com.eneve.agent.planner;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.eneve.agent.jira.JiraService;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
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

        LOG.infof("Creating plan: sourceType=%s, repoUrl=%s", sourceType, request.repoUrl());

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
        if (request == null || request.repoUrl() == null || request.repoUrl().isBlank()) {
            return Response.status(400).entity(Map.of("error", "repoUrl is required")).build();
        }

        String ticketText = jiraService.fetchIssuePrompt(jiraKey);
        if (ticketText == null || ticketText.isBlank()) {
            return Response.status(404)
                    .entity(Map.of("error", "Could not fetch Jira ticket: " + jiraKey))
                    .build();
        }

        String targetBranch = request.targetBranch() != null ? request.targetBranch() : "main";

        LOG.infof("Creating plan from Jira %s, repoUrl=%s", jiraKey, request.repoUrl());

        ExecutionPlan plan = plannerService.generatePlan(
                ticketText, request.repoUrl(), targetBranch, "JIRA", jiraKey);

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

        ExecutionPlan plan = new ExecutionPlan(
                planId,
                PlanStatus.DRAFT.name(),
                "QUALITY",
                request.branch(),
                request.repoUrl(),
                targetBranch,
                title,
                planData,
                Instant.now(),
                Instant.now(),
                null,
                null,
                null);

        planStore.create(plan);
        LOG.infof("Quality improvement plan %s created for %s (CC threshold=%d, maxIter=%d)",
                planId, request.repoUrl(), ccThreshold, maxIterations);

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
            description = "Returns all plans, optionally filtered by status (DRAFT, APPROVED, EXECUTING, COMPLETED, FAILED)."
    )
    @APIResponse(responseCode = "200", description = "List of plans")
    public Response list(
            @Parameter(description = "Filter by status (optional)")
            @QueryParam("status") String status) {

        List<ExecutionPlan> plans = status != null && !status.isBlank()
                ? planStore.listByStatus(status.toUpperCase())
                : planStore.listAll();
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
}
