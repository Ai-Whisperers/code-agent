package com.eneve.agent.planner;

import com.eneve.agent.model.JobStatus;

/**
 * Fired asynchronously by {@link com.eneve.agent.agent.JobQueue} when any job finishes
 * (success or failure). {@link PlanOrchestratorService} observes this event to advance
 * plan execution without polling.
 */
public record JobCompletedEvent(
        String jobId,
        JobStatus status,
        String summary,
        String prUrl,
        String errorMessage
) {}
