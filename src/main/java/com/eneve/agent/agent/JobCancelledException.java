package com.eneve.agent.agent;

/**
 * Thrown by {@link ClaudeToolUseLoop} when a job is cancelled while running.
 * Propagates through handler catch blocks; {@link com.eneve.agent.agent.JobLifecycleHelper}
 * fail-handlers check for CANCELLED status and skip the FAILED override when this is caught.
 */
public class JobCancelledException extends RuntimeException {

    public JobCancelledException(String jobId) {
        super("Job " + jobId + " was cancelled");
    }
}
