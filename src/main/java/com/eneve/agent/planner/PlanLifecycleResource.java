package com.eneve.agent.planner;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.Optional;

/**
 * Plan lifecycle endpoints: approve, execute, pause, resume, cancel, archive, delete, and PR operations.
 */
@Path("/plans")
@RolesAllowed({"app_developer", "app_admin"})
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Execution Plans")
public class PlanLifecycleResource {

    private static final Logger LOG = Logger.getLogger(PlanLifecycleResource.class);

    @Inject PlanStore planStore;
    @Inject PlanOrchestratorService orchestratorService;
    @Inject PlanAuthHelper authHelper;

    @POST
    @Path("/{planId}/approve")
    @Operation(operationId = "approvePlan", summary = "Approve the plan for execution",
            description = "Transitions the plan from DRAFT to APPROVED.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Plan approved"),
            @APIResponse(responseCode = "404", description = "Plan not found"),
            @APIResponse(responseCode = "409", description = "Plan is not in DRAFT status")
    })
    public Response approve(
            @Parameter(description = "Plan ID", required = true) @PathParam("planId") String planId) {

        Optional<ExecutionPlan> existing = planStore.find(planId);
        if (existing.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "Plan not found: " + planId)).build();
        }
        if (!PlanStatus.DRAFT.name().equals(existing.get().status())) {
            return Response.status(409).entity(Map.of("error", "Only DRAFT plans can be approved")).build();
        }

        planStore.approve(planId);
        LOG.infof("Plan %s approved", planId);

        return planStore.find(planId)
                .map(p -> Response.ok(p).build())
                .orElse(Response.status(404).entity(Map.of("error", "Plan not found after approval")).build());
    }

    @POST
    @Path("/{planId}/execute")
    @Operation(operationId = "executePlan", summary = "Execute an approved plan",
            description = "Transitions the plan from APPROVED to EXECUTING and begins submitting steps to the job queue.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Plan execution started"),
            @APIResponse(responseCode = "404", description = "Plan not found"),
            @APIResponse(responseCode = "409", description = "Plan is not in APPROVED status")
    })
    public Response execute(
            @Parameter(description = "Plan ID", required = true) @PathParam("planId") String planId) {

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
    @Path("/{planId}/pause")
    @Operation(operationId = "pausePlanExecution", summary = "Pause plan execution",
            description = "Pause the execution of a running plan.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Plan paused successfully"),
            @APIResponse(responseCode = "404", description = "Plan not found"),
            @APIResponse(responseCode = "400", description = "Plan is not currently executing")
    })
    public Response pauseExecution(
            @Parameter(description = "Plan ID", required = true) @PathParam("planId") String planId) {

        Optional<ExecutionPlan> planOpt = planStore.find(planId);
        if (planOpt.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "Plan not found")).build();
        }
        if (!PlanStatus.EXECUTING.name().equals(planOpt.get().status())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Plan is not currently executing")).build();
        }

        try {
            orchestratorService.pausePlan(planId);
            return Response.ok(Map.of("message", "Plan execution paused")).build();
        } catch (Exception e) {
            LOG.errorf("Failed to pause plan %s: %s", planId, e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to pause plan")).build();
        }
    }

    @POST
    @Path("/{planId}/resume")
    @Operation(operationId = "resumePlanExecution", summary = "Resume plan execution",
            description = "Resume execution of a paused plan.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Plan resumed successfully"),
            @APIResponse(responseCode = "404", description = "Plan not found"),
            @APIResponse(responseCode = "400", description = "Plan is not currently paused")
    })
    public Response resumeExecution(
            @Parameter(description = "Plan ID", required = true) @PathParam("planId") String planId) {

        Optional<ExecutionPlan> planOpt = planStore.find(planId);
        if (planOpt.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "Plan not found")).build();
        }
        if (!PlanStatus.PAUSED.name().equals(planOpt.get().status())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Plan is not currently paused")).build();
        }

        try {
            orchestratorService.resumePlan(planId);
            return Response.ok(Map.of("message", "Plan execution resumed")).build();
        } catch (Exception e) {
            LOG.errorf("Failed to resume plan %s: %s", planId, e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to resume plan")).build();
        }
    }

    @POST
    @Path("/{planId}/cancel")
    @Operation(operationId = "cancelPlanExecution", summary = "Cancel plan execution",
            description = "Cancel the execution of a running or paused plan.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Plan cancelled successfully"),
            @APIResponse(responseCode = "404", description = "Plan not found"),
            @APIResponse(responseCode = "400", description = "Plan is not in a cancellable state")
    })
    public Response cancelExecution(
            @Parameter(description = "Plan ID", required = true) @PathParam("planId") String planId) {

        Optional<ExecutionPlan> planOpt = planStore.find(planId);
        if (planOpt.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "Plan not found")).build();
        }
        ExecutionPlan plan = planOpt.get();
        if (!PlanStatus.EXECUTING.name().equals(plan.status()) && !PlanStatus.PAUSED.name().equals(plan.status())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Plan is not currently executing or paused")).build();
        }

        try {
            orchestratorService.cancelPlan(planId);
            return Response.ok(Map.of("message", "Plan execution cancelled")).build();
        } catch (Exception e) {
            LOG.errorf("Failed to cancel plan %s: %s", planId, e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to cancel plan")).build();
        }
    }

    @POST
    @Path("/{planId}/approve-pr")
    @Operation(operationId = "approvePlanPr", summary = "Approve and merge the plan's pull request",
            description = "Merges the pull request created by the completed plan. Only available for COMPLETED plans that have a PR URL.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "PR merged successfully"),
            @APIResponse(responseCode = "404", description = "Plan not found"),
            @APIResponse(responseCode = "409", description = "Plan has no PR or is not in COMPLETED status"),
            @APIResponse(responseCode = "500", description = "Merge failed")
    })
    public Response approvePlanPr(
            @Parameter(description = "Plan ID", required = true) @PathParam("planId") String planId) {

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
            return Response.status(409).entity(Map.of("error", "Plan has no pull request to approve")).build();
        }

        try {
            planStore.approvePlanPr(planId, plan.prUrl());
            LOG.infof("Plan %s PR approved and merged: %s", planId, plan.prUrl());
            return Response.ok(Map.of("status", "merged", "planId", planId, "prUrl", plan.prUrl())).build();
        } catch (Exception e) {
            LOG.errorf("Failed to approve PR for plan %s: %s", planId, e.getMessage());
            return Response.status(500).entity(Map.of("error", "Failed to merge PR: " + e.getMessage())).build();
        }
    }

    @POST
    @Path("/{planId}/reject-pr")
    @Operation(operationId = "rejectPlanPr", summary = "Reject and decline the plan's pull request",
            description = "Declines the pull request created by the completed plan. Only available for COMPLETED plans that have a PR URL.")
    @org.eclipse.microprofile.openapi.annotations.parameters.RequestBody(
            description = "Optional rejection reason",
            content = @Content(schema = @Schema(implementation = RejectPrRequest.class))
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "PR declined"),
            @APIResponse(responseCode = "404", description = "Plan not found"),
            @APIResponse(responseCode = "409", description = "Plan has no PR or is not in COMPLETED status")
    })
    public Response rejectPlanPr(
            @Parameter(description = "Plan ID", required = true) @PathParam("planId") String planId,
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
            return Response.status(409).entity(Map.of("error", "Plan has no pull request to reject")).build();
        }

        String reason = request != null ? request.reason() : null;
        try {
            planStore.rejectPlanPr(planId, plan.prUrl(), reason);
            LOG.infof("Plan %s PR rejected: %s (reason: %s)", planId, plan.prUrl(), reason);
            return Response.ok(Map.of("status", "rejected", "planId", planId, "prUrl", plan.prUrl())).build();
        } catch (Exception e) {
            LOG.errorf("Failed to reject PR for plan %s: %s", planId, e.getMessage());
            return Response.status(500).entity(Map.of("error", "Failed to decline PR: " + e.getMessage())).build();
        }
    }

    @POST
    @Path("/{planId}/archive")
    @Operation(operationId = "archivePlan", summary = "Archive a plan",
            description = "Marks the plan as archived. Only the plan creator or an admin can archive a plan.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Plan archived"),
            @APIResponse(responseCode = "400", description = "Plan is currently executing"),
            @APIResponse(responseCode = "403", description = "Not authorized to archive this plan"),
            @APIResponse(responseCode = "404", description = "Plan not found")
    })
    public Response archive(
            @Parameter(description = "Plan ID", required = true) @PathParam("planId") String planId) {

        Optional<ExecutionPlan> existing = planStore.find(planId);
        if (existing.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "Plan not found: " + planId)).build();
        }
        ExecutionPlan plan = existing.get();
        if (PlanStatus.EXECUTING.name().equals(plan.status()) || PlanStatus.PAUSED.name().equals(plan.status())) {
            return Response.status(400)
                    .entity(Map.of("error", "Cannot archive a plan that is currently executing or paused")).build();
        }
        if (!authHelper.isCreatorOrAdmin(plan)) {
            return Response.status(403).entity(Map.of("error", "Not authorized to archive this plan")).build();
        }

        planStore.archive(planId);
        LOG.infof("Plan %s archived by %s", planId, authHelper.resolveDisplayName());
        return Response.ok(Map.of("action", "archived", "planId", planId)).build();
    }

    @DELETE
    @Path("/{planId}")
    @Operation(operationId = "deletePlan", summary = "Delete a plan",
            description = "Permanently removes the plan. Only DRAFT, FAILED, CANCELLED, or archived plans can be deleted.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Plan deleted"),
            @APIResponse(responseCode = "403", description = "Not authorized to delete this plan"),
            @APIResponse(responseCode = "404", description = "Plan not found"),
            @APIResponse(responseCode = "409", description = "Plan cannot be deleted in its current status")
    })
    public Response delete(
            @Parameter(description = "Plan ID", required = true) @PathParam("planId") String planId) {

        Optional<ExecutionPlan> existing = planStore.find(planId);
        if (existing.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "Plan not found: " + planId)).build();
        }
        ExecutionPlan plan = existing.get();
        String status = plan.status();
        if (!PlanStatus.DRAFT.name().equals(status)
                && !PlanStatus.FAILED.name().equals(status)
                && !PlanStatus.CANCELLED.name().equals(status)
                && !plan.archived()) {
            return Response.status(409)
                    .entity(Map.of("error",
                            "Only DRAFT, FAILED, CANCELLED, or archived plans can be deleted (current: " + status + ")"))
                    .build();
        }
        if (!authHelper.isCreatorOrAdmin(plan)) {
            return Response.status(403).entity(Map.of("error", "Not authorized to delete this plan")).build();
        }

        planStore.delete(planId);
        LOG.infof("Plan %s deleted by %s", planId, authHelper.resolveDisplayName());
        return Response.ok(Map.of("action", "deleted", "planId", planId)).build();
    }

    // ─── Request records ──────────────────────────────────────────────────────

    public record RejectPrRequest(String reason) {}
}
