package com.eneve.agent.agent;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobStatus;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * In-memory store for job records.
 * For production, consider persisting to a database.
 */
@ApplicationScoped
public class JobStore {

    private static final Set<JobStatus> ACTIVE_STATUSES = Set.of(
            JobStatus.PENDING, JobStatus.QUEUED, JobStatus.RUNNING, JobStatus.AWAITING_APPROVAL
    );

    private final Map<String, JobRecord> jobs = new ConcurrentHashMap<>();

    public void put(JobRecord job) {
        jobs.put(job.getJobId(), job);
    }

    public Optional<JobRecord> get(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    /**
     * Returns true if there is at least one active job (PENDING, QUEUED, RUNNING, or AWAITING_APPROVAL)
     * for the given JIRA key.
     */
    public boolean hasActiveJobForJiraKey(String jiraKey) {
        return jobs.values().stream()
                .anyMatch(j -> jiraKey.equals(j.getRequest().jiraKey())
                        && ACTIVE_STATUSES.contains(j.getStatus()));
    }
}
