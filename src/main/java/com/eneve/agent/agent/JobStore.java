package com.eneve.agent.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * In-memory store for job records, with a file-backed ledger of processed JIRA keys
 * that survives restarts. Prevents duplicate jobs for the same JIRA issue.
 */
@ApplicationScoped
public class JobStore {

    private static final Logger LOG = Logger.getLogger(JobStore.class);

    private static final Set<JobStatus> ACTIVE_STATUSES = Set.of(
            JobStatus.PENDING, JobStatus.QUEUED, JobStatus.RUNNING, JobStatus.AWAITING_APPROVAL
    );

    @ConfigProperty(name = "run-fix.processed-keys-file", defaultValue = "processed-jira-keys.json")
    String processedKeysFile;

    private final Map<String, JobRecord> jobs = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Persistent ledger: JIRA key → timestamp of when it was first queued.
     * Survives restarts so sync-jira won't re-queue issues that were already handled.
     */
    private final Map<String, String> processedKeys = new ConcurrentHashMap<>();

    @PostConstruct
    void loadProcessedKeys() {
        Path path = Path.of(processedKeysFile);
        if (Files.exists(path)) {
            try {
                Map<String, String> loaded = objectMapper.readValue(
                        path.toFile(), new TypeReference<Map<String, String>>() {});
                processedKeys.putAll(loaded);
                LOG.infof("Loaded %d processed JIRA keys from %s", processedKeys.size(), processedKeysFile);
            } catch (IOException e) {
                LOG.warnf("Failed to load processed keys from %s: %s", processedKeysFile, e.getMessage());
            }
        }
    }

    private void saveProcessedKeys() {
        try {
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(Path.of(processedKeysFile).toFile(), processedKeys);
        } catch (IOException e) {
            LOG.warnf("Failed to save processed keys to %s: %s", processedKeysFile, e.getMessage());
        }
    }

    public void put(JobRecord job) {
        jobs.put(job.getJobId(), job);
    }

    /**
     * Record a JIRA key as processed and persist to disk.
     * Called when a job is created for an issue via sync-jira.
     */
    public void markJiraKeyProcessed(String jiraKey) {
        processedKeys.put(jiraKey, Instant.now().toString());
        saveProcessedKeys();
    }

    /**
     * Remove a JIRA key from the processed ledger, allowing it to be re-queued.
     * Useful for manual retry.
     */
    public void clearJiraKey(String jiraKey) {
        processedKeys.remove(jiraKey);
        saveProcessedKeys();
        LOG.infof("Cleared processed key: %s (can be re-queued)", jiraKey);
    }

    public Set<String> getProcessedKeys() {
        return Set.copyOf(processedKeys.keySet());
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
                .anyMatch(j -> jiraKey.equals(getJiraKey(j))
                        && ACTIVE_STATUSES.contains(j.getStatus()));
    }

    /**
     * Returns true if this JIRA key has ever been processed (any status, or in the
     * persistent ledger). Used by sync-jira to prevent re-queueing issues that
     * already have a PR awaiting human approval.
     */
    public boolean hasEverBeenProcessed(String jiraKey) {
        if (processedKeys.containsKey(jiraKey)) return true;
        return jobs.values().stream()
                .anyMatch(j -> jiraKey.equals(getJiraKey(j)));
    }

    private static String getJiraKey(JobRecord job) {
        if (job.getRequest() != null) return job.getRequest().jiraKey();
        if (job.getReviewRequest() != null) return job.getReviewRequest().jiraKey();
        return null;
    }
}
