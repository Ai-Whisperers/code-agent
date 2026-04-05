package com.eneve.agent.agent;

import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobStatus;
import com.eneve.agent.model.JobType;
import com.eneve.agent.model.QaTestCaseGenerationRequest;
import com.eneve.agent.model.QaTestPlanAnalysisRequest;
import com.eneve.agent.model.QaTestPlanConversionRequest;
import com.eneve.agent.planner.JobCompletedEvent;
import com.eneve.agent.settings.SettingsService;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.concurrent.*;

/**
 * Centralized job queue that accepts jobs immediately and processes them
 * up to per-category concurrency limits. Review jobs are backed entirely by
 * the database and refilled into the in-memory queue by a scheduled task,
 * allowing safe handling of large roadmaps (1000+ items) without dropping jobs.
 */
@ApplicationScoped
public class JobQueue {

    private static final Logger LOG = Logger.getLogger(JobQueue.class);

    @Inject AgentRunner agentRunner;
    @Inject JobStore jobStore;
    @Inject Event<JobCompletedEvent> jobCompletedEvent;
    @Inject SettingsService settingsService;

    private int maxQueueSize;
    private int refillBatchSize;
    private int totalMaxConcurrency;

    private PriorityBlockingQueue<JobRecord> pendingQueue;
    private ExecutorService executor;

    /** Per-category concurrency semaphores. */
    private Semaphore chatSemaphore;
    private Semaphore interactiveSemaphore;
    private Semaphore prWorkSemaphore;
    private Semaphore backgroundSemaphore;
    private Semaphore reviewSemaphore;

    /** Tracks job IDs currently in the pendingQueue to avoid re-adding duplicates. */
    private Set<String> dispatchedJobIds;

    private Thread dispatcherThread;
    private volatile boolean running = true;

    void onStart(@Observes StartupEvent event) {
        maxQueueSize    = Integer.parseInt(settingsService.get("run-fix.max-queue-size", "20"));
        refillBatchSize = Integer.parseInt(settingsService.get("roadmap.review.refill-batch-size", "10"));

        int chatConcurrency        = Math.max(1, Integer.parseInt(settingsService.get("job.concurrency.chat",        "10")));
        int interactiveConcurrency = Math.max(1, Integer.parseInt(settingsService.get("job.concurrency.interactive", "10")));
        int prWorkConcurrency      = Math.max(1, Integer.parseInt(settingsService.get("job.concurrency.pr-work",      "8")));
        int backgroundConcurrency  = Math.max(1, Integer.parseInt(settingsService.get("job.concurrency.background",   "5")));
        int reviewConcurrency      = Math.max(1, Integer.parseInt(settingsService.get("job.concurrency.roadmap",     "20")));

        chatSemaphore        = new Semaphore(chatConcurrency);
        interactiveSemaphore = new Semaphore(interactiveConcurrency);
        prWorkSemaphore      = new Semaphore(prWorkConcurrency);
        backgroundSemaphore  = new Semaphore(backgroundConcurrency);
        reviewSemaphore      = new Semaphore(reviewConcurrency);

        totalMaxConcurrency = chatConcurrency + interactiveConcurrency
                + prWorkConcurrency + backgroundConcurrency + reviewConcurrency;

        pendingQueue = new PriorityBlockingQueue<>(
                Math.max(maxQueueSize + reviewConcurrency, 128),
                Comparator.comparingInt((JobRecord j) -> -j.getPriority())
                          .thenComparing(JobRecord::getCreatedAt)
        );
        dispatchedJobIds = ConcurrentHashMap.newKeySet();
        executor = Executors.newFixedThreadPool(totalMaxConcurrency);

        dispatcherThread = new Thread(this::dispatchLoop, "job-queue-dispatcher");
        dispatcherThread.setDaemon(true);
        dispatcherThread.start();

        Thread recoveryThread = new Thread(this::recoverInterruptedJobs, "job-queue-recovery");
        recoveryThread.setDaemon(true);
        recoveryThread.start();

        LOG.infof("JobQueue started: chat=%d, interactive=%d, prWork=%d, background=%d, review=%d, maxQueue=%d",
                chatConcurrency, interactiveConcurrency, prWorkConcurrency, backgroundConcurrency,
                reviewConcurrency, maxQueueSize);
    }

    private void recoverInterruptedJobs() {
        List<JobRecord> interrupted = new ArrayList<>();
        interrupted.addAll(jobStore.findByStatus(JobStatus.RUNNING));
        interrupted.addAll(jobStore.findByStatus(JobStatus.QUEUED));
        interrupted.addAll(jobStore.findByStatus(JobStatus.PENDING));

        if (interrupted.isEmpty()) {
            return;
        }

        interrupted.sort(Comparator.comparing(JobRecord::getCreatedAt));
        int recovered = 0;
        for (JobRecord job : interrupted) {
            jobStore.resetToQueued(job);
            if (isReviewType(job.getJobType())) {
                if (dispatchedJobIds.add(job.getJobId())) {
                    pendingQueue.offer(job);
                    recovered++;
                }
            } else {
                if (pendingQueue.offer(job)) {
                    recovered++;
                } else {
                    LOG.warnf("Recovery: queue full, could not re-queue job %s", job.getJobId());
                }
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
     * Submit a job to the queue. Review jobs ({@code REVIEW_EPIC/FEATURE/USERSTORY}) are
     * routed to {@link #submitReviewJob(JobRecord)} automatically — they are never failed
     * when the queue is full; instead they remain QUEUED in the DB for the refill scheduler.
     * Non-review jobs are failed immediately if the queue is full.
     *
     * @return true if the job was accepted into the in-memory queue, false if deferred or failed
     */
    public boolean submit(JobRecord job) {
        if (isReviewType(job.getJobType())) {
            return submitReviewJob(job);
        }
        resolvePriority(job);
        synchronized (this) {
            if (pendingQueue.size() >= maxQueueSize) {
                job.setStatus(JobStatus.FAILED);
                job.setErrorMessage("Job queue is full");
                jobStore.archive(job);
                return false;
            }
            job.setStatus(JobStatus.QUEUED);
            pendingQueue.offer(job);
        }
        jobStore.update(job);
        logJobQueued(job);
        return true;
    }

    /**
     * Submit a roadmap review job. If there is capacity in the in-memory queue the job
     * is added immediately; otherwise it stays QUEUED in the DB and will be picked up
     * by the next {@link #refillReviewQueue()} tick. Never fails or archives the job.
     *
     * @return true if added to the in-memory queue right away, false if deferred
     */
    public boolean submitReviewJob(JobRecord job) {
        resolvePriority(job);
        job.setStatus(JobStatus.QUEUED);
        jobStore.update(job);
        if (dispatchedJobIds.add(job.getJobId())) {
            pendingQueue.offer(job);
            logJobQueued(job);
            return true;
        }
        return false;
    }

    /**
     * Periodically refills the in-memory queue with QUEUED review jobs from the database.
     * Runs every 10 seconds; also called directly after a review job completes.
     */
    @Scheduled(every = "10s")
    void refillReviewQueue() {
        if (!running || pendingQueue == null) return;
        doRefillReviewQueue();
    }

    private void doRefillReviewQueue() {
        List<JobRecord> candidates = jobStore.findQueuedReviewJobs(dispatchedJobIds, refillBatchSize);
        int added = 0;
        for (JobRecord job : candidates) {
            if (dispatchedJobIds.add(job.getJobId())) {
                pendingQueue.offer(job);
                added++;
            }
        }
        if (added > 0) {
            LOG.debugf("Refill: added %d review job(s) to queue (queue depth: %d)", added, pendingQueue.size());
        }
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
        if (chatSemaphore == null) return 0;
        int totalPermits = totalPermits();
        int available = chatSemaphore.availablePermits()
                + interactiveSemaphore.availablePermits()
                + prWorkSemaphore.availablePermits()
                + backgroundSemaphore.availablePermits()
                + reviewSemaphore.availablePermits();
        return totalPermits - available;
    }

    public int getAvailableSlots() {
        if (chatSemaphore == null) return 0;
        return chatSemaphore.availablePermits()
                + interactiveSemaphore.availablePermits()
                + prWorkSemaphore.availablePermits()
                + backgroundSemaphore.availablePermits()
                + reviewSemaphore.availablePermits();
    }

    public int getMaxConcurrentJobs() {
        return totalPermits();
    }

    public int getMaxQueueSize() {
        return maxQueueSize;
    }

    private int totalPermits() {
        return totalMaxConcurrency;
    }

    private void dispatchLoop() {
        while (running) {
            try {
                JobRecord job = pendingQueue.take();
                if (isReviewType(job.getJobType())) {
                    dispatchedJobIds.remove(job.getJobId());
                }
                Semaphore semaphore = semaphoreFor(job.getJobType());
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
                        if (job.getStatus() == null || job.getStatus() == JobStatus.QUEUED) {
                            LOG.warnf("Job %s completed without terminal status (%s) — forcing FAILED",
                                    job.getJobId(), job.getStatus());
                            job.setStatus(JobStatus.FAILED);
                            job.setErrorMessage("Job completed without setting a terminal status");
                            jobStore.archive(job);
                        }
                        jobCompletedEvent.fireAsync(new JobCompletedEvent(
                                job.getJobId(), job.getStatus(), job.getSummary(), job.getPrUrl(),
                                job.getErrorMessage()));
                        semaphore.release();
                        if (isReviewType(job.getJobType())) {
                            doRefillReviewQueue();
                        }
                    }
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private Semaphore semaphoreFor(JobType jobType) {
        return switch (jobType) {
            case CHAT                                                         -> chatSemaphore;
            case REPLY, FIX_COMMENT, HOOK                                     -> interactiveSemaphore;
            case REVIEW, FIX_PR, FIX, PROMOTE                                -> prWorkSemaphore;
            case METRICS, QUALITY_REPORT, SYNC_CONFLUENCE,
                 GENERATE_TESTS, GENERATE_DOCS, SELF_ANALYSIS,
                 GENERATE_ARCHITECTURE, GENERATE_CLOUD_ARCHITECTURE,
                 KNOWLEDGE_GRAPH, TECH_DEBT, REWRITE,
                 SERVICE_DESK_TRIAGE,
                 QA_TESTPLAN_ANALYSIS, QA_TESTPLAN_CONVERSION,
                 QA_TESTCASE_GENERATION                                         -> backgroundSemaphore;
            case REVIEW_EPIC, REVIEW_FEATURE, REVIEW_USERSTORY                -> reviewSemaphore;
        };
    }

    /**
     * Resolves the runtime priority for a job from {@code agent_settings}.
     * The key {@code job.priority.<type_lowercase>} overrides the compile-time default.
     * Falls back to {@link JobRecord#getPriority()} (which is already set to
     * {@link JobType#defaultPriority()} from the constructor) if no setting exists.
     */
    private void resolvePriority(JobRecord job) {
        String key = "job.priority." + job.getJobType().name().toLowerCase();
        String raw = settingsService.get(key, null);
        if (raw != null) {
            try {
                int parsed = Integer.parseInt(raw);
                job.setPriority(Math.max(1, Math.min(100, parsed)));
            } catch (NumberFormatException e) {
                LOG.warnf("Invalid priority setting for key %s: '%s' — using default %d", key, raw, job.getPriority());
            }
        }
    }

    /**
     * Cancel a PENDING or QUEUED job. Removes it from the in-memory queue, marks it
     * CANCELLED, and archives it to job_history.
     *
     * @return true if the job was found and successfully cancelled; false otherwise
     */
    public boolean cancelJob(String jobId) {
        Optional<JobRecord> opt = jobStore.get(jobId);
        if (opt.isEmpty()) return false;
        JobRecord job = opt.get();
        if (job.getStatus() != JobStatus.PENDING && job.getStatus() != JobStatus.QUEUED) {
            return false;
        }
        pendingQueue.removeIf(j -> j.getJobId().equals(jobId));
        dispatchedJobIds.remove(jobId);
        job.setStatus(JobStatus.CANCELLED);
        job.setErrorMessage("Cancelled by user");
        jobStore.archive(job);
        LOG.infof("Job %s (%s) cancelled by user", jobId, job.getJobType());
        return true;
    }

    /**
     * Rerun a FAILED or SUCCESS job by creating a new job record with a fresh UUID but
     * identical request payload. The new job is persisted and submitted to the queue.
     *
     * @return the new job ID, or {@code null} if the job type cannot be rerun
     */
    public String rerunJob(JobRecord original) {
        String newJobId = UUID.randomUUID().toString();
        JobRecord newJob = switch (original.getJobType()) {
            case FIX -> new JobRecord(newJobId, original.getRequest());
            case REVIEW -> new JobRecord(newJobId, original.getReviewRequest());
            case FIX_PR -> new JobRecord(newJobId, original.getFixPrRequest());
            case REPLY -> new JobRecord(newJobId, original.getReplyRequest(), JobType.REPLY);
            case FIX_COMMENT -> new JobRecord(newJobId, original.getReplyRequest(), JobType.FIX_COMMENT);
            case HOOK -> new JobRecord(newJobId, original.getHookRequest());
            case GENERATE_TESTS -> new JobRecord(newJobId, original.getGenerateTestsRequest());
            case GENERATE_DOCS -> new JobRecord(newJobId, original.getGenerateDocsRequest());
            case SYNC_CONFLUENCE -> new JobRecord(newJobId, original.getSyncConfluenceRequest());
            case METRICS -> new JobRecord(newJobId, original.getMetricsRequest());
            case QUALITY_REPORT -> new JobRecord(newJobId, original.getQualityReportRequest());
            case REVIEW_EPIC, REVIEW_FEATURE, REVIEW_USERSTORY ->
                    new JobRecord(newJobId, original.getJiraReviewRequest(), original.getJobType());
            case QA_TESTPLAN_ANALYSIS -> original.getPayload() instanceof QaTestPlanAnalysisRequest r
                    ? new JobRecord(newJobId, r) : null;
            case QA_TESTPLAN_CONVERSION -> original.getPayload() instanceof QaTestPlanConversionRequest r
                    ? new JobRecord(newJobId, r) : null;
            case QA_TESTCASE_GENERATION -> original.getPayload() instanceof QaTestCaseGenerationRequest r
                    ? new JobRecord(newJobId, r) : null;
            default -> null;
        };
        if (newJob == null) return null;
        newJob.setWorkspace(original.getWorkspace());
        newJob.setRepoSlug(original.getRepoSlug());
        newJob.setPriority(original.getPriority());
        jobStore.put(newJob);
        submit(newJob);
        LOG.infof("Job %s (%s) rerun as new job %s", original.getJobId(), original.getJobType(), newJobId);
        return newJobId;
    }

    private static boolean isReviewType(JobType jobType) {
        return jobType == JobType.REVIEW_EPIC
                || jobType == JobType.REVIEW_FEATURE
                || jobType == JobType.REVIEW_USERSTORY;
    }

    private void logJobQueued(JobRecord job) {
        String label = switch (job.getJobType()) {
            case REVIEW    -> "PR-review";
            case FIX_PR    -> "fix-PR-" + (job.getFixPrRequest() != null ? job.getFixPrRequest().prId() : "unknown");
            case REPLY     -> "reply-comment-" + (job.getReplyRequest() != null ? job.getReplyRequest().parentCommentId() : "unknown");
            case FIX_COMMENT -> "fix-comment-" + (job.getReplyRequest() != null ? job.getReplyRequest().parentCommentId() : "unknown");
            case HOOK      -> "hook-" + (job.getHookRequest() != null ? job.getHookRequest().hookName() : "unknown");
            case GENERATE_TESTS -> "generate-tests-" + (job.getGenerateTestsRequest() != null ? job.getGenerateTestsRequest().branchName() : "unknown");
            case GENERATE_DOCS  -> "generate-docs-" + (job.getGenerateDocsRequest() != null ? job.getGenerateDocsRequest().repoUrl() : "unknown");
            case METRICS   -> "metrics-" + (job.getMetricsRequest() != null ? job.getMetricsRequest().branch() : "unknown");
            case REVIEW_EPIC, REVIEW_FEATURE, REVIEW_USERSTORY ->
                    job.getJiraReviewRequest() != null ? job.getJiraReviewRequest().issueKey() : "unknown";
            default -> job.getRequest() != null ? job.getRequest().jiraKey() : "unknown";
        };
        LOG.infof("Job %s (%s) queued for %s [priority=%d, queue depth: %d]",
                job.getJobId(), job.getJobType(), label, job.getPriority(), pendingQueue.size());
    }
}
