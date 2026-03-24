package com.eneve.agent.agent;

import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobStatus;
import com.eneve.agent.planner.JobCompletedEvent;
import com.eneve.agent.settings.SettingsService;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.*;

/**
 * Centralized job queue that accepts jobs immediately and processes them
 * up to the configured concurrency limit. Excess jobs wait in a bounded
 * FIFO queue instead of being rejected.
 */
@ApplicationScoped
public class JobQueue {

    private static final Logger LOG = Logger.getLogger(JobQueue.class);

    @Inject AgentRunner agentRunner;
    @Inject JobStore jobStore;
    @Inject Event<JobCompletedEvent> jobCompletedEvent;
    @Inject SettingsService settingsService;

    private int maxConcurrentJobs;
    private int maxQueueSize;

    private PriorityBlockingQueue<JobRecord> pendingQueue;
    private ExecutorService executor;
    private Semaphore semaphore;
    private Thread dispatcherThread;
    private volatile boolean running = true;

    void onStart(@Observes StartupEvent event) {
        maxConcurrentJobs = Integer.parseInt(settingsService.get("run-fix.max-concurrent-jobs", "3"));
        maxQueueSize = Integer.parseInt(settingsService.get("run-fix.max-queue-size", "20"));

        pendingQueue = new PriorityBlockingQueue<>(
                maxQueueSize,
                Comparator.comparingInt((JobRecord j) -> j.getJobType().priority())
                          .thenComparing(JobRecord::getCreatedAt)
        );
        semaphore = new Semaphore(maxConcurrentJobs);
        executor = Executors.newFixedThreadPool(maxConcurrentJobs);

        dispatcherThread = new Thread(this::dispatchLoop, "job-queue-dispatcher");
        dispatcherThread.setDaemon(true);
        dispatcherThread.start();

        Thread recoveryThread = new Thread(this::recoverInterruptedJobs, "job-queue-recovery");
        recoveryThread.setDaemon(true);
        recoveryThread.start();

        LOG.infof("JobQueue started: maxConcurrent=%d, maxQueue=%d", maxConcurrentJobs, maxQueueSize);
    }

    /**
     * On startup, recover jobs that were QUEUED or RUNNING when the process last shut down.
     * RUNNING jobs are reset to QUEUED since their execution was interrupted.
     */
    private void recoverInterruptedJobs() {
        List<JobRecord> interrupted = new java.util.ArrayList<>();
        interrupted.addAll(jobStore.findByStatus(JobStatus.RUNNING));
        interrupted.addAll(jobStore.findByStatus(JobStatus.QUEUED));

        if (interrupted.isEmpty()) {
            return;
        }

        interrupted.sort(Comparator.comparing(JobRecord::getCreatedAt));
        int recovered = 0;
        for (JobRecord job : interrupted) {
            jobStore.resetToQueued(job);
            if (pendingQueue.offer(job)) {
                recovered++;
            } else {
                LOG.warnf("Recovery: queue full, could not re-queue job %s", job.getJobId());
            }
        }
        LOG.infof("Startup recovery: re-queued %d interrupted job(s)", recovered);
    }

    void onStop(@Observes ShutdownEvent event) {
        running = false;
        dispatcherThread.interrupt();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.MINUTES)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Submit a job to the queue. Returns true if accepted, false if the queue is full.
     * When the queue is full, the job status is set to FAILED and persisted.
     */
    public boolean submit(JobRecord job) {
        job.setStatus(JobStatus.QUEUED);
        if (pendingQueue.size() >= maxQueueSize) {
            job.setStatus(JobStatus.FAILED);
            job.setErrorMessage("Job queue is full");
            jobStore.archive(job);
            return false;
        }
        pendingQueue.offer(job);
        String label = switch (job.getJobType()) {
            case REVIEW -> "PR-review";
            case FIX_PR -> "fix-PR-" + job.getFixPrRequest().prId();
            case REPLY -> "reply-comment-" + job.getReplyRequest().parentCommentId();
            case FIX_COMMENT -> "fix-comment-" + job.getReplyRequest().parentCommentId();
            case HOOK -> "hook-" + (job.getHookRequest() != null ? job.getHookRequest().hookName() : "unknown");
            case GENERATE_TESTS -> "generate-tests-" + (job.getGenerateTestsRequest() != null ? job.getGenerateTestsRequest().branchName() : "unknown");
            case GENERATE_DOCS -> "generate-docs-" + (job.getGenerateDocsRequest() != null ? job.getGenerateDocsRequest().repoUrl() : "unknown");
            case METRICS -> "metrics-" + (job.getMetricsRequest() != null ? job.getMetricsRequest().branch() : "unknown");
            default -> job.getRequest() != null ? job.getRequest().jiraKey() : "unknown";
        };
        LOG.infof("Job %s (%s) queued for %s (queue depth: %d)", job.getJobId(),
                job.getJobType(), label, pendingQueue.size());
        return true;
    }

    /**
     * Position of a job in the pending queue (1-based), or 0 if not queued.
     */
    public int getQueuePosition(String jobId) {
        int pos = 1;
        for (JobRecord queued : pendingQueue) {
            if (queued.getJobId().equals(jobId)) return pos;
            pos++;
        }
        return 0;
    }

    public int getQueueDepth() {
        return pendingQueue == null ? 0 : pendingQueue.size();
    }

    public int getRunningCount() {
        return maxConcurrentJobs - (semaphore == null ? maxConcurrentJobs : semaphore.availablePermits());
    }

    public int getAvailableSlots() {
        return semaphore == null ? 0 : semaphore.availablePermits();
    }

    public int getMaxConcurrentJobs() {
        return maxConcurrentJobs;
    }

    public int getMaxQueueSize() {
        return maxQueueSize;
    }

    private void dispatchLoop() {
        while (running) {
            try {
                JobRecord job = pendingQueue.take();
                semaphore.acquire();
                executor.submit(() -> {
                    try {
                        agentRunner.dispatch(job);
                    } catch (Exception e) {
                        LOG.errorf("Unhandled error in job %s: %s", job.getJobId(), e.getMessage());
                        job.setStatus(JobStatus.FAILED);
                        job.setErrorMessage("Unhandled error: " + e.getMessage());
                        jobStore.archive(job);
                    } finally {
                        jobCompletedEvent.fireAsync(new JobCompletedEvent(
                                job.getJobId(), job.getStatus(), job.getSummary(), job.getPrUrl(),
                                job.getErrorMessage()));
                        semaphore.release();
                    }
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
