package com.eneve.agent.planner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.eneve.agent.agent.CodeMetricsCalculator.CodeMetricsSnapshot;
import com.eneve.agent.agent.CodeMetricsStore;
import com.eneve.agent.agent.JobQueue;
import com.eneve.agent.model.GenerateDocsRequest;
import com.eneve.agent.model.GenerateTestsRequest;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobStatus;
import com.eneve.agent.model.MetricsJobRequest;
import com.eneve.agent.model.RepoCoordinates;
import com.eneve.agent.model.ReviewPrRequest;
import com.eneve.agent.model.RunFixRequest;
import com.eneve.agent.model.SyncConfluenceRequest;
import com.eneve.agent.scm.GitPlatformService;
import com.eneve.agent.workspace.PlanWorkspaceManager;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;

/**
 * Orchestrates the execution of approved execution plans.
 * Drives phase-by-phase execution by submitting steps as jobs to {@link JobQueue}
 * and advancing to the next phase when the current phase completes successfully.
 *
 * <p>Job completion is signalled via a CDI async event ({@link JobCompletedEvent})
 * fired by {@link JobQueue}, keeping the orchestrator fully decoupled from the
 * job execution machinery.
 *
 * <p>In-memory tracking: a {@code ConcurrentHashMap} maps each dispatched jobId to
 * the corresponding plan/step so completion events can be correlated quickly.
 * If the process restarts mid-execution, affected plans remain in EXECUTING status
 * and must be failed or re-executed manually.
 *
 * <p>Concurrency: per-plan locks ({@code planLocks}) ensure that concurrent
 * {@link JobCompletedEvent} callbacks for steps in the same phase cannot race to
 * double-submit the next phase or produce conflicting status updates.
 */
@ApplicationScoped
public class PlanOrchestratorService {

    private static final Logger LOG = Logger.getLogger(PlanOrchestratorService.class);

    /** Tracks jobs that were dispatched by this orchestrator: jobId -> tracking entry. */
    private final ConcurrentHashMap<String, TrackedStep> trackedJobs = new ConcurrentHashMap<>();

    /**
     * Per-plan mutex objects. {@code checkPhaseCompletion} and {@code submitPhase}
     * synchronize on the plan's lock to prevent concurrent callbacks from the same
     * phase double-submitting the next phase or racing on step status.
     */
    private final ConcurrentHashMap<String, Object> planLocks = new ConcurrentHashMap<>();

    /**
     * Holds the PR URL for a plan so that REVIEW steps (if present in the last phase)
     * can reference the correct PR. The PR is now created in {@link #markCompleted} after
     * all phases finish, so this map is only populated at plan completion and cleared by
     * {@link #cleanup}.
     */
    private final ConcurrentHashMap<String, String> planPrUrl = new ConcurrentHashMap<>();

    /**
     * Tracks the single shared branch name per plan. All FIX steps in a plan push to
     * this branch so that changes accumulate and only one PR is created.
     */
    private final ConcurrentHashMap<String, String> planBranchName = new ConcurrentHashMap<>();

    /**
     * Tracks how many quality-improvement iterations (FIX→METRICS cycles) have been
     * completed per quality-improvement plan. Used to enforce {@code maxIterations}.
     */
    private final ConcurrentHashMap<String, AtomicInteger> planIterationCount = new ConcurrentHashMap<>();

    @Inject PlanStore planStore;
    @Inject JobQueue jobQueue;
    @Inject CodeMetricsStore codeMetricsStore;
    @Inject PlanWorkspaceManager planWorkspaceManager;
    @Inject GitPlatformService platformService;
    @Inject Event<PlanCompletedEvent> planCompletedEvent;

    @ConfigProperty(name = "git.username")
    String gitUser;

    @ConfigProperty(name = "git.password")
    String gitPassword;

    @ConfigProperty(name = "metrics.cc-threshold", defaultValue = "10")
    int defaultCcThreshold;

    @ConfigProperty(name = "metrics.max-iterations", defaultValue = "3")
    int defaultMaxIterations;

    @ConfigProperty(name = "metrics.max-methods-per-fix", defaultValue = "20")
    int defaultMaxMethodsPerFix;

    // ─── Public API ─────────────────────────────────────────────────────────────

    /**
     * Begin executing an approved plan.
     * Transitions the plan to EXECUTING and submits the first phase immediately.
     *
     * @param planId the plan to execute
     * @throws IllegalArgumentException if the plan does not exist
     * @throws IllegalStateException    if the plan is not in APPROVED status
     */
    public void startExecution(String planId) {
        ExecutionPlan plan = planStore.find(planId)
                .orElseThrow(() -> new IllegalArgumentException("Plan not found: " + planId));

        if (!PlanStatus.APPROVED.name().equals(plan.status())
                && !PlanStatus.FAILED.name().equals(plan.status())) {
            throw new IllegalStateException(
                    "Plan " + planId + " must be APPROVED or FAILED to execute (current: " + plan.status() + ")");
        }

        LOG.infof("Orchestrator: starting execution of plan %s (%s)", planId, plan.title());

        // Ensure the plan lock exists before submitting so any concurrent events
        // from recovered jobs don't race ahead of the initial phase submission.
        planLocks.computeIfAbsent(planId, k -> new Object());

        int resumeFromOrder = findFirstIncompletePhaseOrder(plan.planData());
        if (resumeFromOrder >= 0 && PlanStatus.FAILED.name().equals(plan.status())) {
            // Partial re-execution: keep completed steps, reset only from the failed phase onward.
            LOG.infof("Orchestrator: resuming plan %s from phase order %d", planId, resumeFromOrder);
            PlanData partialReset = resetStepsFromPhase(plan.planData(), resumeFromOrder);
            planStore.updatePlanData(planId, partialReset);
            planStore.updateStatus(planId, PlanStatus.EXECUTING.name());

            // Restore in-memory state from previously completed steps.
            ExecutionPlan reloaded = planStore.find(planId)
                    .orElseThrow(() -> new IllegalStateException("Plan disappeared: " + planId));
            restoreInMemoryState(reloaded);

            ExecutionPlan executing = planStore.find(planId)
                    .orElseThrow(() -> new IllegalStateException("Plan disappeared after status update: " + planId));
            submitNextPhase(executing, resumeFromOrder - 1);
        } else {
            // Fresh execution: reset all steps.
            PlanData resetData = resetAllSteps(plan.planData());
            planStore.updatePlanData(planId, resetData);
            planStore.updateStatus(planId, PlanStatus.EXECUTING.name());

            ExecutionPlan executing = planStore.find(planId)
                    .orElseThrow(() -> new IllegalStateException("Plan disappeared after status update: " + planId));
            submitNextPhase(executing, -1);
        }
    }

    // ─── CDI event handler ───────────────────────────────────────────────────────

    /**
     * Called asynchronously whenever any job in the system completes.
     * Only jobs that were dispatched by this orchestrator are acted upon.
     */
    public void onJobCompleted(@ObservesAsync JobCompletedEvent event) {
        TrackedStep tracked = trackedJobs.remove(event.jobId());
        if (tracked == null) {
            return; // not an orchestrator-dispatched job
        }

        LOG.infof("Orchestrator: job %s for plan %s / step %s completed with %s",
                event.jobId(), tracked.planId(), tracked.stepId(), event.status());

        // AWAITING_APPROVAL means the agent completed successfully and created a PR
        // awaiting human merge — the step itself is done from the orchestrator's view.
        String stepStatus = isSuccess(event.status()) ? "SUCCESS" : "FAILED";
        String stepError = "FAILED".equals(stepStatus) ? event.errorMessage() : null;
        planStore.updateStepInPlan(tracked.planId(), tracked.stepId(), stepStatus, event.jobId(), stepError);

        // When a METRICS step succeeds, evaluate whether the quality loop should continue
        // or terminate. This must happen before checkPhaseCompletion advances the plan.
        if (isSuccess(event.status()) && tracked.isMetricsStep()) {
            synchronized (lockFor(tracked.planId())) {
                maybeAppendQualityIteration(tracked.planId());
            }
        }

        synchronized (lockFor(tracked.planId())) {
            checkPhaseCompletion(tracked.planId(), tracked.phaseOrder());
        }
    }

    // ─── Phase management ────────────────────────────────────────────────────────

    private void checkPhaseCompletion(String planId, int phaseOrder) {
        ExecutionPlan plan = planStore.find(planId).orElse(null);
        if (plan == null) {
            LOG.warnf("Orchestrator: plan %s not found during phase-completion check", planId);
            return;
        }

        // Guard: do nothing if plan already reached a terminal state
        // (e.g. failed mid-phase from a queue-full error or a prior concurrent call)
        if (!PlanStatus.EXECUTING.name().equals(plan.status())) {
            LOG.debugf("Orchestrator: plan %s is %s, skipping phase-completion check", planId, plan.status());
            return;
        }

        PlanPhase phase = findPhase(plan, phaseOrder);
        if (phase == null) {
            LOG.warnf("Orchestrator: phase %d not found in plan %s during completion check", phaseOrder, planId);
            return;
        }

        List<PlanStep> steps = phase.steps();

        boolean anyRunning = steps.stream().anyMatch(s -> "RUNNING".equals(s.status()) || "PENDING".equals(s.status()));
        if (anyRunning) {
            return; // phase still in progress
        }

        boolean anyFailed = steps.stream().anyMatch(s -> "FAILED".equals(s.status()));

        if (anyFailed && phase.gateOnSuccess()) {
            LOG.warnf("Orchestrator: gated phase %d of plan %s has failures — failing plan", phaseOrder, planId);
            PlanData skipped = skipRemainingPhases(plan.planData(), phaseOrder);
            planStore.updatePlanData(planId, skipped);
            planStore.updateStatusAndError(planId, PlanStatus.FAILED.name(),
                    "Phase \"" + phase.name() + "\" failed; subsequent phases skipped");
            planCompletedEvent.fireAsync(new PlanCompletedEvent(planId, PlanStatus.FAILED.name()));
            cleanup(planId);
            return;
        }

        // Phase complete — advance
        LOG.infof("Orchestrator: phase %d (%s) of plan %s complete", phaseOrder, phase.name(), planId);
        submitNextPhase(plan, phaseOrder);
    }

    /**
     * Finds and submits the phase that comes after {@code completedPhaseOrder}.
     * Pass {@code -1} as {@code completedPhaseOrder} to submit the very first phase.
     * If there is no next phase the plan is marked COMPLETED.
     */
    private void submitNextPhase(ExecutionPlan plan, int completedPhaseOrder) {
        List<PlanPhase> phases = plan.planData().phases();
        if (phases == null || phases.isEmpty()) {
            markCompleted(plan.planId());
            return;
        }

        List<PlanPhase> sorted = phases.stream()
                .sorted(Comparator.comparingInt(PlanPhase::order))
                .toList();

        PlanPhase next = null;
        for (PlanPhase p : sorted) {
            if (p.order() > completedPhaseOrder) {
                next = p;
                break;
            }
        }

        if (next == null) {
            markCompleted(plan.planId());
            return;
        }

        LOG.infof("Orchestrator: submitting phase %d (%s) of plan %s (%d step(s))",
                next.order(), next.name(), plan.planId(), next.steps().size());

        submitPhase(plan, next);
    }

    private void submitPhase(ExecutionPlan plan, PlanPhase phase) {
        if (phase.steps() == null || phase.steps().isEmpty()) {
            LOG.warnf("Orchestrator: phase %d of plan %s has no steps, skipping", phase.order(), plan.planId());
            submitNextPhase(plan, phase.order());
            return;
        }

        // Determine (or reuse) the single shared branch for all FIX steps in this plan.
        // We pick it from the first FIX step's branchName param, or auto-generate one.
        String sharedBranch = planBranchName.computeIfAbsent(plan.planId(), k ->
                phase.steps().stream()
                        .filter(s -> "FIX".equalsIgnoreCase(s.jobType()))
                        .findFirst()
                        .map(s -> param(s, "branchName",
                                "agent/plan/" + plan.planId().substring(0, 8)))
                        .orElse("agent/plan/" + plan.planId().substring(0, 8))
        );

        // Track job IDs submitted in this phase so we can remove them if a later
        // step fails to enqueue (partial-submit cleanup).
        List<String> submittedJobIds = new ArrayList<>();

        for (PlanStep step : phase.steps()) {
            PlanStep effectiveStep = step;
            if ("FIX".equalsIgnoreCase(step.jobType())) {
                Map<String, String> updatedParams = new java.util.LinkedHashMap<>(
                        step.params() != null ? step.params() : Map.of());
                // All FIX steps share one branch; no sourceBranch chaining needed.
                updatedParams.put("branchName", sharedBranch);
                updatedParams.remove("sourceBranch");
                // All plan FIX steps skip PR creation — the orchestrator creates the PR
                // in markCompleted() once every phase has finished.
                updatedParams.put("skipPrCreation", "true");
                effectiveStep = step.withUpdates(null, null, null, updatedParams);
            } else if ("GENERATE_DOCS".equalsIgnoreCase(step.jobType())
                    || "GENERATE_TESTS".equalsIgnoreCase(step.jobType())) {
                Map<String, String> updatedParams = new java.util.LinkedHashMap<>(
                        step.params() != null ? step.params() : Map.of());
                updatedParams.put("branchName", sharedBranch);
                if ("GENERATE_DOCS".equalsIgnoreCase(step.jobType())) {
                    updatedParams.put("commitDirect", "true");
                }
                effectiveStep = step.withUpdates(null, null, null, updatedParams);
            }

            JobRecord job = mapStepToJob(effectiveStep, plan);
            if (job == null) {
                LOG.warnf("Orchestrator: could not map step %s (jobType=%s) in plan %s — skipping step",
                        step.stepId(), step.jobType(), plan.planId());
                planStore.updateStepInPlan(plan.planId(), step.stepId(), "SKIPPED", null, null);
                continue;
            }

            // Tag every job with the plan ID so handlers can use the shared workspace.
            job.setPlanId(plan.planId());

            boolean accepted = jobQueue.submit(job);
            if (!accepted) {
                LOG.errorf("Orchestrator: job queue rejected job for step %s in plan %s", step.stepId(), plan.planId());
                // Remove tracking for jobs already submitted in this phase so their
                // completion events don't trigger phase-advancement on a FAILED plan.
                submittedJobIds.forEach(trackedJobs::remove);
                planStore.updateStepInPlan(plan.planId(), step.stepId(), "FAILED", job.getJobId(),
                        "Job queue full");
                planStore.updateStatusAndError(plan.planId(), PlanStatus.FAILED.name(),
                        "Job queue full when submitting step \"" + step.stepId() + "\"");
                planCompletedEvent.fireAsync(new PlanCompletedEvent(plan.planId(), PlanStatus.FAILED.name()));
                cleanup(plan.planId());
                return;
            }

            boolean isMetrics = "METRICS".equalsIgnoreCase(step.jobType());
            trackedJobs.put(job.getJobId(), new TrackedStep(plan.planId(), step.stepId(), phase.order(), isMetrics));
            submittedJobIds.add(job.getJobId());
            planStore.updateStepInPlan(plan.planId(), step.stepId(), "RUNNING", job.getJobId(), null);
            LOG.infof("Orchestrator: submitted job %s for step %s (%s) in plan %s (branch: %s)",
                    job.getJobId(), step.stepId(), step.jobType(), plan.planId(), sharedBranch);
        }
    }

    // ─── Quality loop ────────────────────────────────────────────────────────────

    /**
     * Called synchronously (inside the plan lock) after a METRICS step succeeds.
     * Reads the latest snapshot for the plan, evaluates exit conditions, and either
     * appends a new FIX + METRICS phase pair (loop continues) or leaves the plan
     * data unchanged (orchestrator will advance to whatever comes next, or complete).
     *
     * <p>Exit conditions (any one terminates the loop):
     * <ol>
     *   <li>Threshold met: all methods at or below CC threshold.</li>
     *   <li>Max iterations reached: the configured iteration cap has been hit.</li>
     *   <li>No improvement: average CC did not decrease since the previous snapshot.</li>
     * </ol>
     */
    private void maybeAppendQualityIteration(String planId) {
        ExecutionPlan plan = planStore.find(planId).orElse(null);
        if (plan == null || !PlanStatus.EXECUTING.name().equals(plan.status())) {
            return;
        }

        List<CodeMetricsSnapshot> snapshots = codeMetricsStore.findByPlan(planId);
        if (snapshots.isEmpty()) {
            LOG.warnf("Orchestrator: no snapshots found for plan %s after METRICS step", planId);
            return;
        }

        CodeMetricsSnapshot latest = snapshots.get(snapshots.size() - 1);
        int maxIterations = latest.threshold() > 0
                ? defaultMaxIterations : defaultMaxIterations; // use snapshot threshold

        // Determine effective limits from the snapshot metadata (threshold was baked in)
        int ccThreshold = latest.threshold();
        AtomicInteger iterCount = planIterationCount.computeIfAbsent(planId, k -> new AtomicInteger(0));

        if (latest.thresholdMet()) {
            LOG.infof("Orchestrator: quality threshold met for plan %s (0 methods above CC %d) — loop complete",
                    planId, ccThreshold);
            return;
        }

        if (iterCount.get() >= maxIterations) {
            LOG.infof("Orchestrator: max iterations (%d) reached for plan %s — stopping quality loop",
                    maxIterations, planId);
            return;
        }

        if (snapshots.size() >= 2) {
            CodeMetricsSnapshot previous = snapshots.get(snapshots.size() - 2);
            if (latest.avgComplexity() >= previous.avgComplexity()) {
                LOG.infof("Orchestrator: no improvement in avg CC for plan %s (%.2f → %.2f) — stopping loop",
                        planId, previous.avgComplexity(), latest.avgComplexity());
                return;
            }
        }

        // Append a new FIX phase + METRICS phase pair
        int iteration = iterCount.incrementAndGet();
        int nextOrder = plan.planData().phases().stream()
                .mapToInt(PlanPhase::order)
                .max()
                .orElse(0) + 1;

        String fixStepId = "quality-fix-iter-" + iteration;
        String metricsStepId = "quality-metrics-iter-" + iteration;

        // Extract connection info from the latest metrics snapshot for the fix prompt
        String metricsContext = latest.formatForPrompt(defaultMaxMethodsPerFix);

        // The new fix branch for this iteration
        String fixBranch = "agent/quality/" + planId.substring(0, 8) + "-iter-" + iteration;
        // Chain iterations: iter-N clones from iter-(N-1), iter-1 clones from the target branch
        String prevBranch = iteration == 1
                ? (plan.targetBranch() != null ? plan.targetBranch() : "main")
                : "agent/quality/" + planId.substring(0, 8) + "-iter-" + (iteration - 1);

        PlanStep fixStep = new PlanStep(
                fixStepId, "FIX",
                "Reduce cyclomatic complexity (iteration " + iteration + ")",
                metricsContext,
                "PENDING", null,
                Map.of(
                        "branchName", fixBranch,
                        "sourceBranch", prevBranch),
                null);

        PlanStep metricsStep = new PlanStep(
                metricsStepId, "METRICS",
                "Re-measure code metrics (iteration " + iteration + ")",
                null,
                "PENDING", null,
                Map.of(
                        "ccThreshold", String.valueOf(ccThreshold),
                        "maxIterations", String.valueOf(maxIterations),
                        "branch", fixBranch),
                null);

        PlanPhase fixPhase = new PlanPhase(nextOrder, "Quality Fix (iteration " + iteration + ")", true,
                List.of(fixStep));
        PlanPhase metricsPhase = new PlanPhase(nextOrder + 1, "Metrics Check (iteration " + iteration + ")", true,
                List.of(metricsStep));

        List<PlanPhase> updatedPhases = new ArrayList<>(plan.planData().phases());
        updatedPhases.add(fixPhase);
        updatedPhases.add(metricsPhase);

        planStore.updatePlanData(planId, new PlanData(updatedPhases));

        LOG.infof("Orchestrator: appended FIX+METRICS phase pair (iteration %d) for plan %s "
                + "(%d methods above CC %d, avg %.2f)",
                iteration, planId, latest.methodsAboveThreshold(), ccThreshold, latest.avgComplexity());
    }

    // ─── Step → Job mapping ──────────────────────────────────────────────────────

    private JobRecord mapStepToJob(PlanStep step, ExecutionPlan plan) {
        String jobId = "plan-job-" + UUID.randomUUID();
        String jobType = step.jobType() != null ? step.jobType().toUpperCase() : "FIX";

        return switch (jobType) {
            case "FIX" -> {
                String branchName = param(step, "branchName",
                        "agent/plan/" + plan.planId().substring(0, 8) + "-" + step.stepId());
                // All FIX steps in a plan use the plan's target branch as base (no chaining).
                // Quality FIX steps (from the metrics loop) still carry sourceBranch for the
                // iteration chain — respect that only for quality jobs identified by planId.
                String sourceBranch = param(step, "sourceBranch", null);
                boolean isQualityStep = sourceBranch != null;
                String effectiveTargetBranch = isQualityStep ? sourceBranch : plan.targetBranch();
                String qualityPlanId = isQualityStep ? plan.planId() : null;
                boolean skipPr = "true".equalsIgnoreCase(param(step, "skipPrCreation", null));
                yield new JobRecord(jobId, new RunFixRequest(
                        plan.repoUrl(),
                        branchName,
                        plan.sourceRef(),
                        nullIfBlank(step.prompt()),
                        effectiveTargetBranch,
                        null,  // n8nWebhookUrl
                        null,  // rulesRepoUrl
                        null,  // ruleNames
                        null,  // extraRules
                        qualityPlanId,
                        skipPr ? Boolean.TRUE : null
                ));
            }
            case "GENERATE_TESTS" -> {
                String branchName = param(step, "branchName",
                        "agent/plan/" + plan.planId().substring(0, 8) + "-" + step.stepId());
                String sourceFilesParam = param(step, "sourceFiles", null);
                List<String> targetFiles = sourceFilesParam != null
                        ? Arrays.asList(sourceFilesParam.split(","))
                        : null;
                yield new JobRecord(jobId, new GenerateTestsRequest(
                        plan.repoUrl(),
                        branchName,
                        plan.targetBranch(),
                        targetFiles,
                        plan.sourceRef(),
                        null,  // n8nWebhookUrl
                        null,  // rulesRepoUrl
                        null,  // ruleNames
                        nullIfBlank(step.prompt())
                ));
            }
            case "GENERATE_DOCS" -> {
                String branchName = param(step, "branchName",
                        "agent/plan/" + plan.planId().substring(0, 8) + "-" + step.stepId());
                boolean commitDirect = "true".equalsIgnoreCase(param(step, "commitDirect", null));
                yield new JobRecord(jobId, new GenerateDocsRequest(
                        plan.repoUrl(),
                        branchName,
                        plan.targetBranch(),
                        null,  // ruleNames
                        nullIfBlank(step.prompt()),
                        null,  // n8nWebhookUrl
                        commitDirect
                ));
            }
            case "SYNC_CONFLUENCE" -> {
                String branch = param(step, "branchName", plan.targetBranch() != null ? plan.targetBranch() : "main");
                String docsPath = param(step, "docsPath", "docs");
                yield new JobRecord(jobId, new SyncConfluenceRequest(
                        plan.repoUrl(),
                        branch,
                        docsPath,
                        null,  // confluenceSpaceKey — use repo settings
                        null   // confluenceParentPageId — use repo settings
                ));
            }
            case "REVIEW" -> {
                // prId can be given explicitly in params, or inferred from a prior FIX step's PR
                String prId = param(step, "prId", null);
                if (prId == null) {
                    String prUrl = planPrUrl.get(plan.planId());
                    if (prUrl != null) {
                        prId = extractPrIdFromUrl(prUrl);
                    }
                }
                if (prId == null) {
                    LOG.warnf("Orchestrator: REVIEW step %s in plan %s has no prId — cannot submit",
                            step.stepId(), plan.planId());
                    yield null;
                }
                yield new JobRecord(jobId, new ReviewPrRequest(
                        plan.repoUrl(),
                        prId,
                        plan.targetBranch(),
                        plan.sourceRef(),
                        null,  // rulesRepoUrl
                        null,  // ruleNames
                        nullIfBlank(step.prompt()),
                        null,  // n8nWebhookUrl
                        null   // headCommitSha
                ));
            }
            case "METRICS" -> {
                String branch = param(step, "branch",
                        plan.targetBranch() != null ? plan.targetBranch() : "main");
                String ccThresholdStr = param(step, "ccThreshold", String.valueOf(defaultCcThreshold));
                String maxIterStr = param(step, "maxIterations", String.valueOf(defaultMaxIterations));
                int ccThreshold;
                int maxIter;
                try {
                    ccThreshold = Integer.parseInt(ccThresholdStr);
                    maxIter = Integer.parseInt(maxIterStr);
                } catch (NumberFormatException e) {
                    ccThreshold = defaultCcThreshold;
                    maxIter = defaultMaxIterations;
                }
                yield new JobRecord(jobId, new MetricsJobRequest(
                        plan.repoUrl(),
                        branch,
                        null,  // workspace — derived from repoUrl in AgentRunner
                        null,  // repoSlug — derived from repoUrl in AgentRunner
                        ccThreshold,
                        maxIter,
                        plan.planId()
                ));
            }
            default -> {
                LOG.warnf("Orchestrator: unknown jobType '%s' for step %s", jobType, step.stepId());
                yield null;
            }
        };
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    /**
     * A job is considered successful from the orchestrator's perspective when it
     * finishes with SUCCESS (work done, changes committed) or AWAITING_APPROVAL
     * (work done, PR created, waiting for human merge). Both mean the step's
     * deliverable is complete.
     */
    private static boolean isSuccess(JobStatus status) {
        return status == JobStatus.SUCCESS || status == JobStatus.AWAITING_APPROVAL;
    }

    private static String param(PlanStep step, String key, String defaultValue) {
        if (step.params() == null) return defaultValue;
        String v = step.params().get(key);
        return (v != null && !v.isBlank()) ? v : defaultValue;
    }

    private static String nullIfBlank(String s) {
        return (s != null && !s.isBlank()) ? s : null;
    }

    private static PlanPhase findPhase(ExecutionPlan plan, int order) {
        if (plan.planData() == null || plan.planData().phases() == null) return null;
        return plan.planData().phases().stream()
                .filter(p -> p.order() == order)
                .findFirst()
                .orElse(null);
    }

    private static PlanStep findStep(ExecutionPlan plan, String stepId) {
        if (plan.planData() == null || plan.planData().phases() == null) return null;
        return plan.planData().phases().stream()
                .flatMap(p -> p.steps().stream())
                .filter(s -> stepId.equals(s.stepId()))
                .findFirst()
                .orElse(null);
    }

    private static PlanData resetAllSteps(PlanData data) {
        if (data == null || data.phases() == null) return data;
        List<PlanPhase> phases = new ArrayList<>();
        for (PlanPhase phase : data.phases()) {
            List<PlanStep> steps = phase.steps().stream()
                    .map(s -> s.withStatus("PENDING").withJobId(null))
                    .toList();
            phases.add(new PlanPhase(phase.order(), phase.name(), phase.gateOnSuccess(), steps));
        }
        return new PlanData(phases);
    }

    /**
     * Returns the {@code order} of the first phase that contains a FAILED or SKIPPED step,
     * or {@code -1} if all phases completed successfully (or there are no phases).
     */
    private static int findFirstIncompletePhaseOrder(PlanData data) {
        if (data == null || data.phases() == null) return -1;
        return data.phases().stream()
                .sorted(Comparator.comparingInt(PlanPhase::order))
                .filter(p -> p.steps() != null && p.steps().stream()
                        .anyMatch(s -> "FAILED".equals(s.status()) || "SKIPPED".equals(s.status())))
                .mapToInt(PlanPhase::order)
                .findFirst()
                .orElse(-1);
    }

    /**
     * Resets steps only in phases at or after {@code fromOrder}, leaving earlier phases unchanged.
     */
    private static PlanData resetStepsFromPhase(PlanData data, int fromOrder) {
        if (data == null || data.phases() == null) return data;
        List<PlanPhase> phases = new ArrayList<>();
        for (PlanPhase phase : data.phases()) {
            if (phase.order() < fromOrder) {
                phases.add(phase);
            } else {
                List<PlanStep> steps = phase.steps().stream()
                        .map(s -> s.withStatus("PENDING").withJobId(null))
                        .toList();
                phases.add(new PlanPhase(phase.order(), phase.name(), phase.gateOnSuccess(), steps));
            }
        }
        return new PlanData(phases);
    }

    /**
     * Rebuilds in-memory plan state ({@code planBranchName}, {@code planPrUrl}) from the
     * persisted step data so that a resumed plan uses the same branch and PR as before.
     */
    private void restoreInMemoryState(ExecutionPlan plan) {
        if (plan.planData() == null || plan.planData().phases() == null) return;
        plan.planData().phases().stream()
                .sorted(Comparator.comparingInt(PlanPhase::order))
                .flatMap(p -> p.steps().stream())
                .filter(s -> "FIX".equalsIgnoreCase(s.jobType()) && "SUCCESS".equals(s.status()))
                .forEach(s -> {
                    String branch = param(s, "branchName",
                            "agent/plan/" + plan.planId().substring(0, 8));
                    planBranchName.putIfAbsent(plan.planId(), branch);
                });
        // Restore PR URL from first completed FIX step that has a job record with a prUrl.
        // We rely on the step's jobId to look it up — best-effort; REVIEW steps will still
        // work even if this is not restored since they use planPrUrl only as a fallback.
        LOG.debugf("Orchestrator: restored in-memory state for plan %s (branch=%s)",
                plan.planId(), planBranchName.get(plan.planId()));
    }

    private static PlanData skipRemainingPhases(PlanData data, int fromPhaseOrder) {
        if (data == null || data.phases() == null) return data;
        List<PlanPhase> phases = new ArrayList<>();
        for (PlanPhase phase : data.phases()) {
            if (phase.order() <= fromPhaseOrder) {
                phases.add(phase);
            } else {
                List<PlanStep> skipped = phase.steps().stream()
                        .map(s -> s.withStatus("SKIPPED"))
                        .toList();
                phases.add(new PlanPhase(phase.order(), phase.name(), phase.gateOnSuccess(), skipped));
            }
        }
        return new PlanData(phases);
    }

    private void markCompleted(String planId) {
        LOG.infof("Orchestrator: plan %s completed successfully", planId);

        // Create the PR now that all phases have finished and the branch has all commits.
        String branch = planBranchName.get(planId);
        ExecutionPlan plan = planStore.find(planId).orElse(null);
        if (plan != null && branch != null) {
            try {
                RepoCoordinates coords = RepoCoordinates.parse(plan.repoUrl());
                String targetBranch = plan.targetBranch() != null ? plan.targetBranch() : "main";
                String title = plan.title() != null ? plan.title() : "Automated plan: " + planId;
                String description = buildPlanPrDescription(plan);
                String[] prResult = platformService.createPullRequest(
                        coords.organization(), coords.project(), coords.repository(),
                        branch, targetBranch, title, description);
                String prUrl = prResult[0];
                planPrUrl.put(planId, prUrl);
                planStore.updatePrUrl(planId, prUrl);
                LOG.infof("Orchestrator: plan %s PR created: %s", planId, prUrl);
            } catch (Exception e) {
                LOG.warnf("Orchestrator: plan %s PR creation failed (branch %s still has commits): %s",
                        planId, branch, e.getMessage());
            }
        }

        planStore.updateStatus(planId, PlanStatus.COMPLETED.name());
        planCompletedEvent.fireAsync(new PlanCompletedEvent(planId, PlanStatus.COMPLETED.name()));
        cleanup(planId);
    }

    /**
     * Builds a Markdown PR description summarising the plan's phases and steps.
     */
    private String buildPlanPrDescription(ExecutionPlan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("**Automated PR created by Code Agent**\n\n");

        if (plan.sourceRef() != null && !plan.sourceRef().isBlank()) {
            sb.append("Ref: ").append(plan.sourceRef()).append("\n\n");
        }

        if (plan.summary() != null && !plan.summary().isBlank()) {
            sb.append(plan.summary()).append("\n\n");
        }

        if (plan.planData() != null && plan.planData().phases() != null
                && !plan.planData().phases().isEmpty()) {
            sb.append("## Changes\n\n");
            List<PlanPhase> sorted = plan.planData().phases().stream()
                    .sorted(Comparator.comparingInt(PlanPhase::order))
                    .toList();
            for (PlanPhase phase : sorted) {
                sb.append("### ").append(phase.name()).append("\n\n");
                if (phase.steps() != null) {
                    for (PlanStep step : phase.steps()) {
                        if (step.title() != null && !step.title().isBlank()) {
                            sb.append("- ").append(step.title()).append("\n");
                        }
                    }
                }
                sb.append("\n");
            }
        }
        return sb.toString().trim();
    }

    /** Removes all in-memory state for a plan once it reaches a terminal status. */
    private void cleanup(String planId) {
        planPrUrl.remove(planId);
        planLocks.remove(planId);
        planIterationCount.remove(planId);
        planBranchName.remove(planId);
        planWorkspaceManager.release(planId);
    }

    private Object lockFor(String planId) {
        return planLocks.computeIfAbsent(planId, k -> new Object());
    }

    /**
     * Attempts to extract a numeric or slug PR id from the tail of a PR URL.
     * e.g. "https://bitbucket.org/org/repo/pull-requests/42" -> "42"
     */
    private static String extractPrIdFromUrl(String prUrl) {
        if (prUrl == null || prUrl.isBlank()) return null;
        String trimmed = prUrl.stripTrailing().replaceAll("/$", "");
        int slash = trimmed.lastIndexOf('/');
        return slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
    }

    // ─── Inner record ─────────────────────────────────────────────────────────────

    /** Maps a dispatched jobId back to the plan + step + phase it represents. */
    private record TrackedStep(String planId, String stepId, int phaseOrder, boolean isMetricsStep) {}
}
