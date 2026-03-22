package com.eneve.agent.planner;

/**
 * Event fired by PlanOrchestratorService when plan execution progresses
 * This event is observed by PlanEventService to broadcast progress to SSE clients
 */
public record PlanOrchestratorEvent(
    String eventType,        // "STEP_STARTED", "STEP_COMPLETED", "STEP_FAILED", "PHASE_COMPLETED", "PLAN_COMPLETED"
    String planId,
    Integer phaseOrder,      // null for plan-level events
    String stepId,           // null for phase/plan-level events
    String stepTitle,        // step title, null for phase/plan-level events  
    String phaseTitle,       // phase title, null for step/plan-level events
    String errorMessage      // only for failed events
) {}
