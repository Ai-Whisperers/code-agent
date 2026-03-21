package com.eneve.agent.planner;

import java.util.Map;

/**
 * A single executable step within a plan phase.
 * Each step maps to a job type that can be submitted to the agent's job queue.
 * {@code errorMessage} is populated when the step's job fails, so the UI can
 * display the reason for the failure without querying the job store separately.
 */
public record PlanStep(
        String stepId,
        String jobType,
        String title,
        String prompt,
        String status,
        String jobId,
        Map<String, String> params,
        String errorMessage
) {
    public PlanStep withStatus(String newStatus) {
        return new PlanStep(stepId, jobType, title, prompt, newStatus, jobId, params, errorMessage);
    }

    public PlanStep withJobId(String newJobId) {
        return new PlanStep(stepId, jobType, title, prompt, status, newJobId, params, errorMessage);
    }

    public PlanStep withErrorMessage(String newErrorMessage) {
        return new PlanStep(stepId, jobType, title, prompt, status, jobId, params, newErrorMessage);
    }

    public PlanStep withUpdates(String newTitle, String newPrompt, String newJobType, Map<String, String> newParams) {
        return new PlanStep(
                stepId,
                newJobType != null ? newJobType : jobType,
                newTitle != null ? newTitle : title,
                newPrompt != null ? newPrompt : prompt,
                status,
                jobId,
                newParams != null ? newParams : params,
                errorMessage
        );
    }
}
