package com.eneve.agent.agent;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.eneve.agent.model.JobRecord;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * In-memory store for job records.
 * For production, consider persisting to a database.
 */
@ApplicationScoped
public class JobStore {

    private final Map<String, JobRecord> jobs = new ConcurrentHashMap<>();

    public void put(JobRecord job) {
        jobs.put(job.getJobId(), job);
    }

    public Optional<JobRecord> get(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }
}
