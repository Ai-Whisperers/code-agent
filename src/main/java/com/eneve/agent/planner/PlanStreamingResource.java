package com.eneve.agent.planner;

import com.eneve.agent.agent.ClaudeToolUseLoop;
import com.eneve.agent.agent.ToolDefinitions;
import com.eneve.agent.agent.model.ChatEvent;
import com.eneve.agent.workspace.WorkspaceContext;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestStreamElementType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SSE streaming endpoints for plan implementation and real-time event subscriptions.
 */
@Path("/plans")
@RolesAllowed({"app_developer", "app_admin"})
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Execution Plans")
public class PlanStreamingResource {

    private static final Logger LOG = Logger.getLogger(PlanStreamingResource.class);

    @Inject PlanStore planStore;
    @Inject PlanEventService planEventService;
    @Inject ClaudeToolUseLoop toolLoop;
    @Inject PlanAuthHelper authHelper;

    @POST
    @Path("/{planId}/implement")
    @Blocking
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    @Operation(operationId = "implementPlan", summary = "Implement a markdown plan via AI agent",
            description = "Parses the plan's markdown checklist into tasks, then runs the AI tool-use loop "
                    + "on each task. Accepts DRAFT or APPROVED plans. Streams SSE ChatEvent objects.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "SSE stream of ChatEvent JSON objects"),
            @APIResponse(responseCode = "404", description = "Plan not found"),
            @APIResponse(responseCode = "409", description = "Plan is already executing or completed")
    })
    public Multi<ChatEvent> implement(
            @Parameter(description = "Plan ID", required = true) @PathParam("planId") String planId) {

        Optional<ExecutionPlan> existing = planStore.find(planId);
        if (existing.isEmpty()) {
            throw new WebApplicationException(
                    Response.status(404).entity(Map.of("error", "Plan not found: " + planId)).build());
        }

        ExecutionPlan plan = existing.get();
        String currentStatus = plan.status();
        if (PlanStatus.EXECUTING.name().equals(currentStatus) || PlanStatus.COMPLETED.name().equals(currentStatus)) {
            throw new WebApplicationException(
                    Response.status(409).entity(Map.of("error", "Plan is already " + currentStatus)).build());
        }

        String userId = authHelper.resolveUserId();

        return Multi.createFrom().<ChatEvent>emitter(emitter -> {
            planStore.updateStatus(planId, PlanStatus.EXECUTING.name());
            emitter.emit(new ChatEvent.PlanStart(planId, plan.title()));

            WorkspaceContext workspace = null;
            try {
                String markdownContent = plan.markdownContent();
                if (markdownContent == null || markdownContent.isBlank()) {
                    emitter.emit(new ChatEvent.Error("Plan has no markdown content to implement"));
                    planStore.updateStatus(planId, PlanStatus.FAILED.name());
                    emitter.complete();
                    return;
                }

                List<String> tasks = extractTasks(markdownContent);
                if (tasks.isEmpty()) {
                    tasks = List.of(markdownContent);
                }

                workspace = WorkspaceContext.create(planId);
                workspace.putMetadata("planId", planId);
                workspace.setUserId(userId);

                String systemPrompt = "You are an implementation agent executing a specific task from a plan. "
                        + "Use the available tools to complete the task. "
                        + "When done, call plan_read to see the current plan, "
                        + "then call plan_update to mark this task as completed (change '- [ ]' to '- [x]') "
                        + "and add a brief result note.";

                for (int i = 0; i < tasks.size(); i++) {
                    String task = tasks.get(i);
                    LOG.infof("Plan %s: executing task %d/%d: %s", planId, i + 1, tasks.size(),
                            task.length() > 80 ? task.substring(0, 77) + "..." : task);
                    emitter.emit(new ChatEvent.TextDelta(
                            "\n---\n**Task " + (i + 1) + "/" + tasks.size() + ":** " + task + "\n\n"));
                    toolLoop.runStreaming(systemPrompt, workspace, ToolDefinitions.planExecution(),
                            task, planId, "PLAN_IMPL", 50, emitter::emit);
                }

                planStore.updateStatus(planId, PlanStatus.COMPLETED.name());
                emitter.emit(new ChatEvent.Done(planId));

            } catch (Exception e) {
                LOG.errorf("Plan implementation failed for %s: %s", planId, e.getMessage());
                planStore.updateStatus(planId, PlanStatus.FAILED.name());
                emitter.emit(new ChatEvent.Error("Implementation failed: " + e.getMessage()));
            } finally {
                if (workspace != null) {
                    workspace.forceClose();
                }
                emitter.complete();
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @GET
    @Path("/{planId}/events")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @Operation(operationId = "streamPlanEvents", summary = "Stream real-time plan execution events",
            description = "Subscribe to Server-Sent Events for plan execution progress updates.")
    @APIResponse(responseCode = "200", description = "Event stream started")
    public void streamEvents(
            @Parameter(description = "Plan ID", required = true) @PathParam("planId") String planId,
            @Context SseEventSink sink,
            @Context Sse sse) {

        LOG.infof("Client subscribing to events for plan: %s", planId);

        if (planStore.find(planId).isEmpty()) {
            sink.close();
            return;
        }

        planEventService.subscribeToPlan(planId, sink);

        try {
            sink.send(sse.newEventBuilder()
                    .name("connected")
                    .data("Connected to plan events for " + planId)
                    .build());
        } catch (Exception e) {
            LOG.warnf("Failed to send initial event for plan %s: %s", planId, e.getMessage());
        }
    }

    private List<String> extractTasks(String markdownContent) {
        List<String> tasks = new ArrayList<>();
        for (String line : markdownContent.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- [ ] ") || trimmed.startsWith("- [x] ") || trimmed.startsWith("- [X] ")) {
                tasks.add(trimmed.substring(6).trim());
            }
        }
        return tasks;
    }
}
