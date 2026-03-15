package com.eneve.agent.planner;

import java.util.Map;

/**
 * A single executable step within a plan phase.
 * Each step maps to a job type that can be submitted to the agent's job queue.
 */
public record PlanStep(
        String stepId,
        String jobType,
        String title,
        String prompt,
        String status,
        String jobId,
        Map<String, String> params
) {
    public PlanStep withStatus(String newStatus) {
        return new PlanStep(stepId, jobType, title, prompt, newStatus, jobId, params);
    }

    public PlanStep withJobId(String newJobId) {
        return new PlanStep(stepId, jobType, title, prompt, status, newJobId, params);
    }

    public PlanStep withUpdates(String newTitle, String newPrompt, String newJobType, Map<String, String> newParams) {
        return new PlanStep(
                stepId,
                newJobType != null ? newJobType : jobType,
                newTitle != null ? newTitle : title,
                newPrompt != null ? newPrompt : prompt,
                status,
                jobId,
                newParams != null ? newParams : params
        );
    }
}
