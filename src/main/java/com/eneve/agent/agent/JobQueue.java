package com.eneve.agent.agent;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobStatus;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Centralized job queue that accepts jobs immediately and processes them
 * up to the configured concurrency limit. Excess jobs wait in a bounded
 * FIFO queue instead of being rejected.
 */
@ApplicationScoped
public class JobQueue {

    private static final Logger LOG = Logger.getLogger(JobQueue.class);

    @Inject AgentRunner agentRunner;

    @ConfigProperty(name = "run-fix.max-concurrent-jobs", defaultValue = "3")
    int maxConcurrentJobs;

    @ConfigProperty(name = "run-fix.max-queue-size", defaultValue = "20")
    int maxQueueSize;

    private BlockingQueue<JobRecord> pendingQueue;
    private ExecutorService executor;
    private Semaphore semaphore;
    private Thread dispatcherThread;
    private volatile boolean running = true;

    void onStart(@Observes StartupEvent event) {
        pendingQueue = new LinkedBlockingQueue<>(maxQueueSize);
        semaphore = new Semaphore(maxConcurrentJobs);
        executor = Executors.newFixedThreadPool(maxConcurrentJobs);

        dispatcherThread = new Thread(this::dispatchLoop, "job-queue-dispatcher");
        dispatcherThread.setDaemon(true);
        dispatcherThread.start();

        LOG.infof("JobQueue started: maxConcurrent=%d, maxQueue=%d", maxConcurrentJobs, maxQueueSize);
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
     */
    public boolean submit(JobRecord job) {
        job.setStatus(JobStatus.QUEUED);
        if (!pendingQueue.offer(job)) {
            return false;
        }
        String label = switch (job.getJobType()) {
            case REVIEW -> "PR-review";
            case FIX_PR -> "fix-PR-" + job.getFixPrRequest().prId();
            case REPLY -> "reply-comment-" + job.getReplyRequest().parentCommentId();
            case FIX_COMMENT -> "fix-comment-" + job.getReplyRequest().parentCommentId();
            case HOOK -> "hook-" + (job.getHookRequest() != null ? job.getHookRequest().hookName() : "unknown");
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
                        switch (job.getJobType()) {
                            case REVIEW -> agentRunner.executeReview(job);
                            case FIX_PR -> agentRunner.executeFixPr(job);
                            case REPLY -> agentRunner.executeReply(job);
                            case FIX_COMMENT -> agentRunner.executeFixComment(job);
                            case HOOK -> agentRunner.executeHook(job);
                            default -> agentRunner.execute(job);
                        }
                    } catch (Exception e) {
                        LOG.errorf("Unhandled error in job %s: %s", job.getJobId(), e.getMessage());
                        job.setStatus(JobStatus.FAILED);
                        job.setErrorMessage("Unhandled error: " + e.getMessage());
                    } finally {
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
