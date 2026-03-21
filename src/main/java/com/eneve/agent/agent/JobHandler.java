package com.eneve.agent.agent;

import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobType;

/**
 * Implemented by each job-type handler. The {@link AgentRunner} dispatches
 * incoming {@link JobRecord}s to the matching handler via {@link #jobType()}.
 */
public interface JobHandler {
    void handle(JobRecord job);
    JobType jobType();
}
