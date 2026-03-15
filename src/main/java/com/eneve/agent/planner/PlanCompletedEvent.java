package com.eneve.agent.planner;

/**
 * Fired asynchronously by {@link PlanOrchestratorService} when a plan reaches
 * a terminal state (COMPLETED or FAILED). Observers can react to plan completion
 * without polling the {@link PlanStore}.
 */
public record PlanCompletedEvent(
        String planId,
        String status   // "COMPLETED" or "FAILED"
) {}
