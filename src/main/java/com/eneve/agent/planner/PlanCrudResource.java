package com.eneve.agent.planner;

import com.eneve.agent.agent.store.AiCallStore;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CRUD endpoints for execution plan data: list, get, update phases/steps.
 */
@Path("/plans")
@RolesAllowed({"app_developer", "app_admin"})
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Execution Plans")
public class PlanCrudResource {

    @Inject PlanStore planStore;
    @Inject AiCallStore aiCallStore;
    @Inject PlanAuthHelper authHelper;

    @GET
    @Operation(operationId = "listPlans", summary = "List execution plans",
            description = "Returns all plans, optionally filtered by status or conversationId.")
    @APIResponse(responseCode = "200", description = "List of plans")
    public Response list(
            @Parameter(description = "Filter by status (optional)") @QueryParam("status") String status,
            @Parameter(description = "Filter by conversation ID (optional)") @QueryParam("conversationId") String conversationId,
            @Parameter(description = "Include archived plans (default false)") @QueryParam("includeArchived") @DefaultValue("false") boolean includeArchived) {

        List<ExecutionPlan> plans;
        if (conversationId != null && !conversationId.isBlank()) {
            plans = planStore.findByConversationId(conversationId);
        } else if (status != null && !status.isBlank()) {
            plans = planStore.listByStatus(status.toUpperCase());
        } else {
            plans = planStore.listAll(includeArchived);
        }
        return Response.ok(plans).build();
    }

    @GET
    @Path("/{planId}")
    @Operation(operationId = "getPlan", summary = "Get a plan by ID",
            description = "Returns the full execution plan including all phases and steps.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Execution plan"),
            @APIResponse(responseCode = "404", description = "Plan not found")
    })
    public Response get(
            @Parameter(description = "Plan ID", required = true) @PathParam("planId") String planId) {
        return planStore.find(planId)
                .map(p -> Response.ok(p).build())
                .orElse(Response.status(404).entity(Map.of("error", "Plan not found: " + planId)).build());
    }

    @GET
    @Path("/{planId}/conversation")
    @Operation(operationId = "getPlanConversation", summary = "Get the AI conversation log for a plan",
            description = "Returns the AI call records for the plan.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "AI call records"),
            @APIResponse(responseCode = "404", description = "No AI conversation found for this plan")
    })
    public Response getConversation(
            @Parameter(description = "Plan ID", required = true) @PathParam("planId") String planId) {
        var calls = aiCallStore.findByJobId(planId);
        if (calls.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "No AI conversation found for plan: " + planId)).build();
        }
        return Response.ok(calls).build();
    }

    @PUT
    @Path("/{planId}")
    @Operation(operationId = "replacePlan", summary = "Replace the full plan data",
            description = "Replaces all phases and steps in the plan. Only allowed when status is DRAFT.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Plan updated"),
            @APIResponse(responseCode = "404", description = "Plan not found"),
            @APIResponse(responseCode = "409", description = "Plan is not in DRAFT status")
    })
    public Response replace(
            @Parameter(description = "Plan ID", required = true) @PathParam("planId") String planId,
            @RequestBody(description = "New plan data", required = true) PlanData planData) {

        Optional<ExecutionPlan> existing = planStore.find(planId);
        if (existing.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "Plan not found: " + planId)).build();
        }
        if (!PlanStatus.DRAFT.name().equals(existing.get().status())) {
            return Response.status(409).entity(Map.of("error", "Plan is not in DRAFT status, cannot edit")).build();
        }

        planStore.updatePlanData(planId, planData);
        return planStore.find(planId)
                .map(p -> Response.ok(p).build())
                .orElse(Response.status(404).entity(Map.of("error", "Plan not found after update")).build());
    }

    @PATCH
    @Path("/{planId}/markdown")
    @Operation(operationId = "updateMarkdown", summary = "Update plan markdown content")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Markdown updated"),
            @APIResponse(responseCode = "404", description = "Plan not found")
    })
    public Response updateMarkdown(
            @Parameter(description = "Plan ID", required = true) @PathParam("planId") String planId,
            @RequestBody(description = "Markdown content", required = true) Map<String, String> body) {

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
    @Operation(operationId = "updateStep", summary = "Edit a single step",
            description = "Updates the title, prompt, jobType, and/or params of a step. Only allowed when status is DRAFT.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Step updated"),
            @APIResponse(responseCode = "404", description = "Plan or step not found"),
            @APIResponse(responseCode = "409", description = "Plan is not in DRAFT status")
    })
    public Response updateStep(
            @Parameter(description = "Plan ID", required = true) @PathParam("planId") String planId,
            @Parameter(description = "Step ID", required = true) @PathParam("stepId") String stepId,
            @RequestBody(description = "Step fields to update", required = true) UpdateStepRequest request) {

        Optional<ExecutionPlan> existing = planStore.find(planId);
        if (existing.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "Plan not found: " + planId)).build();
        }
        ExecutionPlan plan = existing.get();
        if (!PlanStatus.DRAFT.name().equals(plan.status())) {
            return Response.status(409).entity(Map.of("error", "Plan is not in DRAFT status, cannot edit")).build();
        }

        boolean found = false;
        List<PlanPhase> updatedPhases = new ArrayList<>();
        for (PlanPhase phase : plan.planData().phases()) {
            List<PlanStep> updatedSteps = new ArrayList<>();
            for (PlanStep step : phase.steps()) {
                if (step.stepId().equals(stepId)) {
                    found = true;
                    updatedSteps.add(step.withUpdates(request.title(), request.prompt(), request.jobType(), request.params()));
                } else {
                    updatedSteps.add(step);
                }
            }
            updatedPhases.add(new PlanPhase(phase.order(), phase.name(), phase.gateOnSuccess(), updatedSteps));
        }

        if (!found) {
            return Response.status(404).entity(Map.of("error", "Step not found: " + stepId + " in plan " + planId)).build();
        }

        planStore.updatePlanData(planId, new PlanData(updatedPhases));
        return planStore.find(planId)
                .map(p -> Response.ok(p).build())
                .orElse(Response.status(404).entity(Map.of("error", "Plan not found after update")).build());
    }

    @POST
    @Path("/{planId}/steps")
    @Operation(operationId = "addStep", summary = "Add a step to a phase",
            description = "Appends a new step to the specified phase. Only allowed when status is DRAFT.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Step added"),
            @APIResponse(responseCode = "400", description = "Missing required fields"),
            @APIResponse(responseCode = "404", description = "Plan or phase not found"),
            @APIResponse(responseCode = "409", description = "Plan is not in DRAFT status")
    })
    public Response addStep(
            @Parameter(description = "Plan ID", required = true) @PathParam("planId") String planId,
            @RequestBody(description = "Phase order and new step definition", required = true) AddStepRequest request) {

        if (request == null || request.step() == null) {
            return Response.status(400).entity(Map.of("error", "step is required")).build();
        }

        Optional<ExecutionPlan> existing = planStore.find(planId);
        if (existing.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "Plan not found: " + planId)).build();
        }
        ExecutionPlan plan = existing.get();
        if (!PlanStatus.DRAFT.name().equals(plan.status())) {
            return Response.status(409).entity(Map.of("error", "Plan is not in DRAFT status, cannot edit")).build();
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
            return Response.status(404).entity(Map.of("error", "Phase with order " + request.phaseOrder() + " not found in plan " + planId)).build();
        }

        planStore.updatePlanData(planId, new PlanData(updatedPhases));
        return planStore.find(planId)
                .map(p -> Response.ok(p).build())
                .orElse(Response.status(404).entity(Map.of("error", "Plan not found after update")).build());
    }

    @DELETE
    @Path("/{planId}/steps/{stepId}")
    @Operation(operationId = "removeStep", summary = "Remove a step from a plan",
            description = "Deletes the step with the given ID. Only allowed when status is DRAFT.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Step removed"),
            @APIResponse(responseCode = "404", description = "Plan or step not found"),
            @APIResponse(responseCode = "409", description = "Plan is not in DRAFT status")
    })
    public Response removeStep(
            @Parameter(description = "Plan ID", required = true) @PathParam("planId") String planId,
            @Parameter(description = "Step ID to remove", required = true) @PathParam("stepId") String stepId) {

        Optional<ExecutionPlan> existing = planStore.find(planId);
        if (existing.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "Plan not found: " + planId)).build();
        }
        ExecutionPlan plan = existing.get();
        if (!PlanStatus.DRAFT.name().equals(plan.status())) {
            return Response.status(409).entity(Map.of("error", "Plan is not in DRAFT status, cannot edit")).build();
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
            return Response.status(404).entity(Map.of("error", "Step not found: " + stepId + " in plan " + planId)).build();
        }

        planStore.updatePlanData(planId, new PlanData(updatedPhases));
        return Response.ok(Map.of("action", "removed", "stepId", stepId, "planId", planId)).build();
    }

    @POST
    @Path("/{planId}/phases")
    @Operation(operationId = "addPhase", summary = "Add a new phase to a plan",
            description = "Appends a new phase to the plan. Only allowed when status is DRAFT.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Phase added"),
            @APIResponse(responseCode = "400", description = "Missing required fields"),
            @APIResponse(responseCode = "404", description = "Plan not found"),
            @APIResponse(responseCode = "409", description = "Plan is not in DRAFT status")
    })
    public Response addPhase(
            @Parameter(description = "Plan ID", required = true) @PathParam("planId") String planId,
            @RequestBody(description = "Phase to add", required = true) PlanPhase phase) {

        if (phase == null) {
            return Response.status(400).entity(Map.of("error", "phase is required")).build();
        }

        Optional<ExecutionPlan> existing = planStore.find(planId);
        if (existing.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "Plan not found: " + planId)).build();
        }
        ExecutionPlan plan = existing.get();
        if (!PlanStatus.DRAFT.name().equals(plan.status())) {
            return Response.status(409).entity(Map.of("error", "Plan is not in DRAFT status, cannot edit")).build();
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
    @Operation(operationId = "removePhase", summary = "Remove a phase from a plan",
            description = "Deletes the phase with the given order number. Only allowed when status is DRAFT.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Phase removed"),
            @APIResponse(responseCode = "404", description = "Plan or phase not found"),
            @APIResponse(responseCode = "409", description = "Plan is not in DRAFT status")
    })
    public Response removePhase(
            @Parameter(description = "Plan ID", required = true) @PathParam("planId") String planId,
            @Parameter(description = "Phase order number", required = true) @PathParam("phaseOrder") int phaseOrder) {

        Optional<ExecutionPlan> existing = planStore.find(planId);
        if (existing.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "Plan not found: " + planId)).build();
        }
        ExecutionPlan plan = existing.get();
        if (!PlanStatus.DRAFT.name().equals(plan.status())) {
            return Response.status(409).entity(Map.of("error", "Plan is not in DRAFT status, cannot edit")).build();
        }

        long before = plan.planData().phases().size();
        List<PlanPhase> updatedPhases = plan.planData().phases().stream()
                .filter(p -> p.order() != phaseOrder)
                .toList();

        if (updatedPhases.size() == before) {
            return Response.status(404).entity(Map.of("error", "Phase with order " + phaseOrder + " not found in plan " + planId)).build();
        }

        planStore.updatePlanData(planId, new PlanData(updatedPhases));
        return Response.ok(Map.of("action", "removed", "phaseOrder", phaseOrder, "planId", planId)).build();
    }

    // ─── Request records ──────────────────────────────────────────────────────

    public record UpdateStepRequest(String title, String prompt, String jobType, Map<String, String> params) {}

    public record AddStepRequest(int phaseOrder, PlanStep step) {}
}
