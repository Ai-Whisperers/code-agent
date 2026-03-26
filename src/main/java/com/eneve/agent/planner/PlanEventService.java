package com.eneve.agent.planner;

import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.ws.rs.sse.SseEventSink;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.inject.Inject;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class PlanEventService {

    private static final Logger LOG = Logger.getLogger(PlanEventService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, List<SseEventSink>> activeStreams = new ConcurrentHashMap<>();

    @Inject
    Sse sse;

    /**
     * Subscribe to plan events for a specific plan
     */
    public void subscribeToPlan(String planId, SseEventSink sink) {
        activeStreams.computeIfAbsent(planId, k -> new CopyOnWriteArrayList<>()).add(sink);
        LOG.infof("Client subscribed to plan events for plan: %s", planId);
    }

    /**
     * Unsubscribe from plan events
     */
    public void unsubscribeFromPlan(String planId, SseEventSink sink) {
        List<SseEventSink> sinks = activeStreams.get(planId);
        if (sinks != null) {
            sinks.remove(sink);
            if (sinks.isEmpty()) {
                activeStreams.remove(planId);
            }
        }
    }

    /**
     * Broadcast a plan progress event to all subscribers
     */
    public void broadcastPlanEvent(String planId, PlanProgressEvent event) {
        List<SseEventSink> sinks = activeStreams.get(planId);
        if (sinks == null || sinks.isEmpty()) {
            LOG.debugf("No active subscribers for plan: %s", planId);
            return;
        }

        try {
            String eventData = MAPPER.writeValueAsString(event);
            OutboundSseEvent sseEvent = sse.newEventBuilder()
                    .name(event.eventType())
                    .data(eventData)
                    .build();

            // Send to all active sinks, removing closed ones
            List<SseEventSink> closedSinks = new CopyOnWriteArrayList<>();
            for (SseEventSink sink : sinks) {
                if (sink.isClosed()) {
                    closedSinks.add(sink);
                } else {
                    try {
                        sink.send(sseEvent);
                    } catch (Exception e) {
                        LOG.warnf("Failed to send event to client for plan %s: %s", planId, e.getMessage());
                        closedSinks.add(sink);
                    }
                }
            }

            // Clean up closed sinks
            for (SseEventSink closedSink : closedSinks) {
                unsubscribeFromPlan(planId, closedSink);
            }

            LOG.infof("Broadcasted %s event to %d subscribers for plan: %s", 
                event.eventType(), sinks.size() - closedSinks.size(), planId);

        } catch (Exception e) {
            LOG.errorf("Failed to broadcast plan event for plan %s: %s", planId, e.getMessage());
        }
    }

    /**
     * Observe plan orchestrator events and broadcast them
     */
    public void onPlanOrchestratorEvent(@Observes PlanOrchestratorEvent event) {
        String planId = event.planId();
        PlanProgressEvent progressEvent = null;

        switch (event.eventType()) {
            case "STEP_STARTED":
                progressEvent = new PlanProgressEvent(
                    "step_started",
                    planId,
                    event.phaseOrder(),
                    event.stepId(),
                    event.stepTitle(),
                    PlanStatus.EXECUTING.name(),
                    System.currentTimeMillis(),
                    null
                );
                break;
            case "STEP_COMPLETED":
                progressEvent = new PlanProgressEvent(
                    "step_completed", 
                    planId,
                    event.phaseOrder(),
                    event.stepId(),
                    event.stepTitle(),
                    "COMPLETED",
                    System.currentTimeMillis(),
                    null
                );
                break;
            case "STEP_FAILED":
                progressEvent = new PlanProgressEvent(
                    "step_failed",
                    planId,
                    event.phaseOrder(), 
                    event.stepId(),
                    event.stepTitle(),
                    "FAILED",
                    System.currentTimeMillis(),
                    event.errorMessage()
                );
                break;
            case "PHASE_COMPLETED":
                progressEvent = new PlanProgressEvent(
                    "phase_completed",
                    planId,
                    event.phaseOrder(),
                    null,
                    event.phaseTitle(),
                    "COMPLETED", 
                    System.currentTimeMillis(),
                    null
                );
                break;
            case "PLAN_COMPLETED":
                progressEvent = new PlanProgressEvent(
                    "plan_completed",
                    planId,
                    null,
                    null,
                    null,
                    "COMPLETED",
                    System.currentTimeMillis(),
                    null
                );
                break;
        }

        if (progressEvent != null) {
            broadcastPlanEvent(planId, progressEvent);
        }
    }

    /**
     * Get count of active subscribers for a plan
     */
    public int getSubscriberCount(String planId) {
        List<SseEventSink> sinks = activeStreams.get(planId);
        return sinks != null ? sinks.size() : 0;
    }
}
