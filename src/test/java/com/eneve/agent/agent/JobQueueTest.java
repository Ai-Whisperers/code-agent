package com.eneve.agent.agent;

import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobStatus;
import com.eneve.agent.model.JobType;
import com.eneve.agent.model.RunFixRequest;
import com.eneve.agent.settings.SettingsService;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link JobQueue} covering the changes introduced in the
 * roadmap-review scalability and job-priority work:
 * <ul>
 *   <li>Priority resolution and clamping to [1, 100]</li>
 *   <li>Concurrency settings clamped to minimum 1 per category</li>
 *   <li>Atomic queue-full check in {@code submit()}</li>
 *   <li>PENDING jobs included in startup recovery</li>
 *   <li>Cached {@code totalMaxConcurrency} field — no settings re-read</li>
 * </ul>
 *
 * <p>All CDI dependencies are mocked and injected via reflection; no Quarkus
 * container is required.
 */
class JobQueueTest {

    private AgentRunner agentRunner;
    private JobStore jobStore;
    private SettingsService settingsService;
    private Event<?> jobCompletedEvent;
    private JobQueue queue;

    @BeforeEach
    void setUp() throws Exception {
        agentRunner       = Mockito.mock(AgentRunner.class);
        jobStore          = Mockito.mock(JobStore.class);
        settingsService   = Mockito.mock(SettingsService.class);
        jobCompletedEvent = Mockito.mock(Event.class);

        // Dispatch calls a no-op by default; we mark the job COMPLETED so the
        // dispatch loop does not archive it via the "no terminal status" safety net.
        doAnswer(inv -> {
            JobRecord j = inv.getArgument(0);
            j.setStatus(JobStatus.SUCCESS);
            return null;
        }).when(agentRunner).dispatch(any());

        configureDefaultSettings();
        // Recovery returns empty lists by default — individual tests override as needed.
        when(jobStore.findByStatus(any())).thenReturn(List.of());

        queue = buildQueue();
        queue.onStart(new StartupEvent());
    }

    @AfterEach
    void tearDown() {
        queue.onStop(new ShutdownEvent());
    }

    // ─── resolvePriority: clamping ────────────────────────────────────────────

    @Test
    void resolvePriority_aboveMax_clampedTo100() {
        when(settingsService.get(eq("job.priority.fix"), isNull())).thenReturn("150");

        JobRecord job = fixJob("j-clamp-high");
        queue.submit(job);

        assertEquals(100, job.getPriority());
    }

    @Test
    void resolvePriority_belowMin_clampedTo1() {
        when(settingsService.get(eq("job.priority.fix"), isNull())).thenReturn("0");

        JobRecord job = fixJob("j-clamp-zero");
        queue.submit(job);

        assertEquals(1, job.getPriority());
    }

    @Test
    void resolvePriority_negativeValue_clampedTo1() {
        when(settingsService.get(eq("job.priority.fix"), isNull())).thenReturn("-99");

        JobRecord job = fixJob("j-clamp-neg");
        queue.submit(job);

        assertEquals(1, job.getPriority());
    }

    @Test
    void resolvePriority_invalidSetting_keepsDefaultPriority() {
        when(settingsService.get(eq("job.priority.fix"), isNull())).thenReturn("not-a-number");

        JobRecord job = fixJob("j-invalid");
        int expected  = JobType.FIX.defaultPriority();
        queue.submit(job);

        assertEquals(expected, job.getPriority());
    }

    @Test
    void resolvePriority_exactBoundaries_areNotClamped() {
        // Lower boundary
        when(settingsService.get(eq("job.priority.fix"), isNull())).thenReturn("1");
        JobRecord low = fixJob("j-bound-low");
        queue.submit(low);
        assertEquals(1, low.getPriority());

        // Upper boundary
        when(settingsService.get(eq("job.priority.fix"), isNull())).thenReturn("100");
        JobRecord high = fixJob("j-bound-high");
        queue.submit(high);
        assertEquals(100, high.getPriority());
    }

    @Test
    void resolvePriority_noSetting_keepsDefaultFromJobType() {
        // Default mock returns null for priority keys — no override should be applied.
        JobRecord job = fixJob("j-no-override");
        int expected  = JobType.FIX.defaultPriority();
        queue.submit(job);

        assertEquals(expected, job.getPriority());
    }

    // ─── onStart: concurrency validation ─────────────────────────────────────

    @Test
    void onStart_zeroConcurrencySettings_usesMinimumOnePerCategory() throws Exception {
        queue.onStop(new ShutdownEvent());

        when(settingsService.get(eq("job.concurrency.chat"),        any())).thenReturn("0");
        when(settingsService.get(eq("job.concurrency.interactive"), any())).thenReturn("0");
        when(settingsService.get(eq("job.concurrency.pr-work"),     any())).thenReturn("0");
        when(settingsService.get(eq("job.concurrency.background"),  any())).thenReturn("0");
        when(settingsService.get(eq("job.concurrency.roadmap"),     any())).thenReturn("0");

        JobQueue q2 = buildQueue();
        q2.onStart(new StartupEvent());

        // 5 categories × minimum 1 = at least 5
        assertTrue(q2.getMaxConcurrentJobs() >= 5,
                "totalMaxConcurrency must be >= 5 when all settings are zero");
        q2.onStop(new ShutdownEvent());
    }

    @Test
    void onStart_negativeConcurrencySettings_usesMinimumOnePerCategory() throws Exception {
        queue.onStop(new ShutdownEvent());

        when(settingsService.get(eq("job.concurrency.chat"),        any())).thenReturn("-5");
        when(settingsService.get(eq("job.concurrency.interactive"), any())).thenReturn("-1");
        when(settingsService.get(eq("job.concurrency.pr-work"),     any())).thenReturn("-3");
        when(settingsService.get(eq("job.concurrency.background"),  any())).thenReturn("-2");
        when(settingsService.get(eq("job.concurrency.roadmap"),     any())).thenReturn("-10");

        JobQueue q2 = buildQueue();
        q2.onStart(new StartupEvent());

        assertTrue(q2.getMaxConcurrentJobs() >= 5);
        q2.onStop(new ShutdownEvent());
    }

    // ─── submit: atomic queue-full check ─────────────────────────────────────

    @Test
    void submit_whenQueueFull_archivesAsFailed_doesNotCallUpdate() throws Exception {
        // maxQueueSize = 0 → any queue size (>= 0) triggers the full check
        injectField(queue, "maxQueueSize", 0);

        JobRecord job    = fixJob("full-job");
        boolean accepted = queue.submit(job);

        assertFalse(accepted);
        assertEquals(JobStatus.FAILED, job.getStatus());
        verify(jobStore).archive(job);
        // update() must never be called — the job must not briefly appear as QUEUED in DB
        verify(jobStore, never()).update(job);
    }

    @Test
    void submit_whenQueueNotFull_returnsTrueAndPersistsQueuedStatus() {
        // Capture the job status at the moment jobStore.update() is called.
        // Checking the status on the JobRecord after the fact is unreliable because
        // the dispatch loop runs concurrently and may change it to SUCCESS first.
        AtomicReference<JobStatus> statusAtUpdate = new AtomicReference<>();
        doAnswer(inv -> {
            JobRecord j = inv.getArgument(0);
            if ("normal-job".equals(j.getJobId())) {
                statusAtUpdate.compareAndSet(null, j.getStatus());
            }
            return null;
        }).when(jobStore).update(any(JobRecord.class));

        JobRecord job    = fixJob("normal-job");
        boolean accepted = queue.submit(job);

        assertTrue(accepted);
        assertEquals(JobStatus.QUEUED, statusAtUpdate.get(),
                "jobStore.update() must be called with QUEUED status");
    }

    @Test
    void submitReviewJob_whenCalled_persistsQueuedStatusAndReturnsTrue() {
        AtomicReference<JobStatus> statusAtUpdate = new AtomicReference<>();
        doAnswer(inv -> {
            JobRecord j = inv.getArgument(0);
            if ("review-job".equals(j.getJobId())) {
                statusAtUpdate.compareAndSet(null, j.getStatus());
            }
            return null;
        }).when(jobStore).update(any(JobRecord.class));

        JobRecord job = reviewJob("review-job");
        boolean added = queue.submitReviewJob(job);

        assertTrue(added);
        assertEquals(JobStatus.QUEUED, statusAtUpdate.get(),
                "jobStore.update() must be called with QUEUED status");
    }

    // ─── recoverInterruptedJobs: PENDING status ───────────────────────────────

    @Test
    void recoverInterruptedJobs_queriesPendingStatus() {
        // The recovery thread is started by onStart() in @BeforeEach.
        // Mockito's timeout() waits up to 500 ms for the async call.
        verify(jobStore, timeout(500).atLeastOnce()).findByStatus(JobStatus.PENDING);
    }

    @Test
    void recoverInterruptedJobs_pendingJobIsResetToQueued() throws Exception {
        queue.onStop(new ShutdownEvent());
        Mockito.clearInvocations(jobStore);

        JobRecord pendingJob = fixJob("pending-job");
        when(jobStore.findByStatus(JobStatus.RUNNING)).thenReturn(List.of());
        when(jobStore.findByStatus(JobStatus.QUEUED)).thenReturn(List.of());
        when(jobStore.findByStatus(JobStatus.PENDING)).thenReturn(List.of(pendingJob));

        JobQueue q2 = buildQueue();
        q2.onStart(new StartupEvent());

        // resetToQueued is called synchronously inside the recovery thread
        verify(jobStore, timeout(500)).resetToQueued(pendingJob);

        q2.onStop(new ShutdownEvent());
    }

    // ─── totalPermits: cached field ───────────────────────────────────────────

    @Test
    void getMaxConcurrentJobs_doesNotReReadSettingsAfterStartup() {
        // Clear all interactions recorded during onStart()
        Mockito.clearInvocations(settingsService);

        // Calling getMaxConcurrentJobs() and getRunningCount() multiple times
        queue.getMaxConcurrentJobs();
        queue.getMaxConcurrentJobs();
        queue.getRunningCount();
        queue.getAvailableSlots();

        // totalPermits() must use the cached field — no re-reads of concurrency settings
        verify(settingsService, never()).get(startsWith("job.concurrency."), any());
    }

    @Test
    void getMaxConcurrentJobs_returnsExpectedSumOfConcurrencySettings() {
        // Default settings: 2+2+2+2+2 = 10
        assertEquals(10, queue.getMaxConcurrentJobs());
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static JobRecord fixJob(String jobId) {
        return new JobRecord(jobId, new RunFixRequest(
                "https://example.com/repo.git", "agent/TEST-1", "TEST-1",
                "Fix something", null, null, null, null, null, null, null));
    }

    private static JobRecord reviewJob(String jobId) {
        return new com.eneve.agent.model.JobRecord(jobId,
                new com.eneve.agent.model.JiraReviewRequest("rm-1", "PROJ-1", "Epic"),
                JobType.REVIEW_EPIC);
    }

    private void configureDefaultSettings() {
        when(settingsService.get(eq("run-fix.max-queue-size"),            any())).thenReturn("5");
        when(settingsService.get(eq("roadmap.review.refill-batch-size"),  any())).thenReturn("10");
        when(settingsService.get(eq("job.concurrency.chat"),              any())).thenReturn("2");
        when(settingsService.get(eq("job.concurrency.interactive"),       any())).thenReturn("2");
        when(settingsService.get(eq("job.concurrency.pr-work"),           any())).thenReturn("2");
        when(settingsService.get(eq("job.concurrency.background"),        any())).thenReturn("2");
        when(settingsService.get(eq("job.concurrency.roadmap"),           any())).thenReturn("2");
        when(settingsService.get(startsWith("job.priority."), isNull())).thenReturn(null);
    }

    private JobQueue buildQueue() throws Exception {
        JobQueue q = new JobQueue();
        injectField(q, "agentRunner",       agentRunner);
        injectField(q, "jobStore",          jobStore);
        injectField(q, "settingsService",   settingsService);
        injectField(q, "jobCompletedEvent", jobCompletedEvent);
        return q;
    }

    private static void injectField(Object target, String fieldName, Object value) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        try {
            return clazz.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            if (clazz.getSuperclass() != null) {
                return findField(clazz.getSuperclass(), name);
            }
            throw e;
        }
    }
}
