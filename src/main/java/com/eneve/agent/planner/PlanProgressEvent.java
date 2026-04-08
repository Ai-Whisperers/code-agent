package com.eneve.agent.planner;

/**
 * Event sent to frontend clients via SSE for real-time plan progress updates
 */
public record PlanProgressEvent(
    String eventType,        // "step_started", "step_completed", "step_failed", "phase_completed", "plan_completed"
    String planId,
    Integer phaseOrder,      // null for plan-level events
    String stepId,           // null for phase/plan-level events
    String title,            // step/phase title, null for plan-level events
    String status,           // "EXECUTING", "COMPLETED", "FAILED"
    Long timestamp,          // when the event occurred
    String errorMessage      // only for failed events
) {}
