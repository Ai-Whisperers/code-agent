package com.eneve.agent.agent.handlers;

import com.eneve.agent.agent.*;
import com.eneve.agent.agent.store.CodeMetricsStore;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.jira.JiraService;
import com.eneve.agent.linter.LinterResult;
import com.eneve.agent.linter.LinterService;
import com.eneve.agent.linter.StaticAnalysisDiffReport;
import com.eneve.agent.model.*;
import com.eneve.agent.scm.GitPlatformService;
import com.eneve.agent.workspace.PlanWorkspaceManager;
import com.eneve.agent.workspace.WorkspaceContext;
import com.eneve.agent.settings.SettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@ApplicationScoped
public class RunFixHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(RunFixHandler.class);

    @Inject ClaudeToolUseLoop toolUseLoop;
    @Inject AgentPromptBuilder promptBuilder;
    @Inject BuildAndLintHelper buildAndLintHelper;
    @Inject GitWorkspaceHelper gitHelper;
    @Inject GitPlatformService platformService;
    @Inject JobStore jobStore;
    @Inject JobLifecycleHelper lifecycle;
    @Inject JiraService jiraService;
    @Inject CodeMetricsStore codeMetricsStore;
    @Inject LinterService linterService;
    @Inject PlanWorkspaceManager planWorkspaceManager;
    @Inject SettingsService settings;

    @Override
    public JobType jobType() {
        return JobType.FIX;
    }

    @Override
    public void handle(JobRecord job) {
        long jobTimeoutMinutes = Long.parseLong(settings.get("run-fix.job-timeout-minutes", "30"));
        RunFixRequest request = job.getRequest();
        job.setStatus(JobStatus.RUNNING);
        jobStore.update(job);

        RepoCoordinates coords;
        try {
            coords = RepoCoordinates.parse(request.repoUrl());
        } catch (IllegalArgumentException e) {
            lifecycle.failFix(job, "Invalid repo URL: " + e.getMessage());
            return;
        }

        WorkspaceContext workspace;
        try {
            workspace = job.getPlanId() != null
                    ? planWorkspaceManager.acquire(job.getPlanId())
                    : WorkspaceContext.create(job.getJobId());
        } catch (Exception e) {
            lifecycle.failFix(job, "Failed to acquire workspace: " + e.getMessage());
            return;
        }

        try (WorkspaceContext ignored = workspace) {

            String authUrl = platformService.buildCloneUrl(coords.organization(), coords.project(), coords.repository());
            if (!workspace.hasClonedRepo()) {
                LOG.infof("Cloning %s/%s (branch: %s)", coords.organization(), coords.repository(),
                        request.branchName());
                try {
                    workspace.cloneRepo(authUrl, request.branchName(), jobTimeoutMinutes);
                } catch (Exception e) {
                    LOG.infof("Branch '%s' not found, trying clone from '%s' and create branch",
                            request.branchName(), request.targetBranchOrDefault());
                    try {
                        workspace.cloneAndCreateBranch(authUrl, request.targetBranchOrDefault(),
                                request.branchName(), jobTimeoutMinutes);
                    } catch (Exception e2) {
                        lifecycle.failFix(job, "Clone failed: " + e2.getMessage());
                        return;
                    }
                }
            } else {
                LOG.infof("Reusing existing workspace for plan %s (branch: %s)",
                        job.getPlanId(), request.branchName());
            }

            gitHelper.configureGitIfNeeded(workspace);

            // Quality-improvement FIX jobs carry a planId; use the focused CC-reduction prompt.
            if (request.planId() != null && !request.planId().isBlank()) {
                String qualityPrompt = buildQualityFixPrompt(request.planId());
                if (qualityPrompt != null) {
                    String summary;
                    try {
                        summary = toolUseLoop.run(qualityPrompt, workspace,
                                job.getJobId(), job.getJobType().name());
                    } catch (Exception e) {
                        lifecycle.failFix(job, "Agent loop error: " + e.getMessage());
                        return;
                    }
                    finishFixJob(job, request, workspace, summary);
                    return;
                }
                LOG.warnf("No CC snapshot for plan %s, falling back to generic fix prompt", request.planId());
            }

            // Linter baseline scan and prompt resolution are independent — run in parallel
            CompletableFuture<List<LinterResult>> linterFuture = CompletableFuture.supplyAsync(
                    () -> buildAndLintHelper.runBaselineLinterScan(workspace), AgentPools.PARALLEL);
            CompletableFuture<String> promptFuture = CompletableFuture.supplyAsync(
                    () -> resolvePrompt(request), AgentPools.PARALLEL);

            CompletableFuture.allOf(linterFuture, promptFuture).join();

            List<LinterResult> linterBaseline = linterFuture.join();
            String baselineSummary = linterBaseline.isEmpty()
                    ? "" : linterService.formatSummary(linterBaseline);

            String effectivePrompt = promptFuture.join();
            if (effectivePrompt == null) {
                lifecycle.failFix(job,
                        "No prompt provided and could not fetch JIRA issue description for "
                                + request.jiraKey());
                return;
            }

            lifecycle.safeJira(() -> jiraService.commentStarted(
                    request.jiraKey(), request.branchName()));

            String systemPrompt = promptBuilder.buildRunFixPrompt(
                    request, effectivePrompt, workspace, baselineSummary);

            String summary;
            try {
                summary = toolUseLoop.run(systemPrompt, workspace,
                        job.getJobId(), job.getJobType().name());
            } catch (Exception e) {
                lifecycle.failFix(job, "Agent loop error: " + e.getMessage());
                return;
            }

            BuildAndLintHelper.LinterFixResult linterFixResult =
                    buildAndLintHelper.runLinterFixLoop(workspace, linterBaseline, job);
            if (!linterFixResult.canContinue()) {
                lifecycle.failFix(job, linterFixResult.failureMessage());
                return;
            }

            if (!buildAndLintHelper.runBuildWithRetry(workspace, job)) {
                lifecycle.failFix(job, "Build validation failed after retry attempt(s)");
                return;
            }

            boolean hasChanges;
            try {
                hasChanges = workspace.commitAll(
                        "fix(" + request.jiraKey() + "): automated fix\n\n" + summary);
            } catch (Exception e) {
                lifecycle.failFix(job, "Commit failed: " + e.getMessage());
                return;
            }

            if (!hasChanges) {
                if (job.getPlanId() != null) {
                    // Plan step: the agent verified the task is already done — treat as success
                    // so the plan can continue to subsequent phases.
                    job.setStatus(JobStatus.SUCCESS);
                    job.setSummary("No changes required — task already complete.\n\n" + summary);
                    jobStore.archive(job);
                    LOG.infof("Plan job %s: no changes needed (task already complete), marking SUCCESS",
                            job.getJobId());
                    RunResult result = lifecycle.buildResult(job, true);
                    lifecycle.notifyResult(result, request.n8nWebhookUrl());
                } else {
                    lifecycle.failFix(job,
                            "Agent completed but made no file changes. Claude summary: " + summary);
                }
                return;
            }

            StaticAnalysisDiffReport linterDiffReport = buildAndLintHelper.buildLinterDiffReport(
                    workspace, linterBaseline, linterFixResult.finalResults());

            try {
                workspace.push(request.branchName(), jobTimeoutMinutes);
            } catch (Exception e) {
                lifecycle.failFix(job, "Push failed: " + e.getMessage());
                return;
            }

            GitWorkspaceHelper.DiffStats stats = gitHelper.countChanges(workspace);
            String violation = gitHelper.checkGuardrails(stats);
            if (violation != null) {
                lifecycle.failFix(job, violation);
                return;
            }
            job.setFilesChanged(stats.filesChanged());
            job.setLinesChanged(stats.linesChanged());

            if (request.shouldSkipPrCreation()) {
                // Intermediate plan step — changes pushed to shared branch, PR already exists.
                job.setStatus(JobStatus.SUCCESS);
                job.setSummary(summary);
                jobStore.archive(job);
                LOG.infof("Job %s completed (plan-managed, PR skipped). Branch: %s",
                        job.getJobId(), request.branchName());
                RunResult result = lifecycle.buildResult(job, true);
                lifecycle.notifyResult(result, request.n8nWebhookUrl());
            } else {
                String prUrl;
                String prId;
                try {
                    String title = request.jiraKey() + ": Automated fix";
                    String linterSummaryLine = linterDiffReport != null
                            ? "\n\n" + BuildAndLintHelper.buildLinterDiffSummaryLine(linterDiffReport)
                            : "";
                    String description = "**Automated PR created by Code Agent Runner**\n\n"
                            + "JIRA: " + request.jiraKey() + "\n\n" + summary + linterSummaryLine;
                    String[] prResult = platformService.createPullRequest(
                            coords.organization(), coords.project(), coords.repository(),
                            request.branchName(), request.targetBranchOrDefault(),
                            title, description);
                    prUrl = prResult[0];
                    prId = prResult[1];
                } catch (Exception e) {
                    lifecycle.failFix(job, "Create PR failed: " + e.getMessage());
                    return;
                }

                job.setStatus(JobStatus.AWAITING_APPROVAL);
                job.setSummary(summary);
                job.setPrUrl(prUrl);
                job.setPrId(prId);
                jobStore.update(job);
                lifecycle.auditLog("JOBS", "JOB_AWAITING_APPROVAL", "job", job.getJobId(),
                        java.util.Map.of("prUrl", prUrl, "prId", prId));

                if (linterDiffReport != null && linterService.getConfig().isReportOnPr()) {
                    final String capturedPrId = prId;
                    final StaticAnalysisDiffReport capturedReport = linterDiffReport;
                    CompletableFuture.runAsync(() -> lifecycle.safeComment(() ->
                            platformService.addPrComment(
                                    coords.organization(), coords.project(), coords.repository(),
                                    capturedPrId, capturedReport.formatMarkdown())),
                            AgentPools.PARALLEL);
                }

                lifecycle.safeJira(() -> jiraService.commentSuccess(request.jiraKey(), prUrl, summary));
                lifecycle.safeJira(() -> jiraService.transitionToInReview(request.jiraKey()));
                lifecycle.safeJira(() -> jiraService.addWorklog(request.jiraKey(), null));

                RunResult result = lifecycle.buildResult(job, true);
                lifecycle.notifyResult(result, request.n8nWebhookUrl());

                LOG.infof("Job %s completed successfully. PR: %s", job.getJobId(), prUrl);
            }

        } catch (Exception e) {
            lifecycle.failFix(job, "Unexpected error: " + e.getMessage());
        }
    }

    private String buildQualityFixPrompt(String planId) {
        List<CodeMetricsCalculator.CodeMetricsSnapshot> snapshots = codeMetricsStore.findByPlan(planId);
        if (snapshots.isEmpty()) {
            LOG.warnf("Quality-fix job for plan %s: no CC snapshot found", planId);
            return null;
        }
        int metricsMaxMethodsPerFix = Integer.parseInt(settings.get("metrics.max-methods-per-fix", "10"));
        CodeMetricsCalculator.CodeMetricsSnapshot latest = snapshots.get(snapshots.size() - 1);
        return promptBuilder.buildMetricsFixPrompt(latest, metricsMaxMethodsPerFix);
    }

    private void finishFixJob(JobRecord job, RunFixRequest request,
                              WorkspaceContext workspace, String summary) {
        long jobTimeoutMinutes = Long.parseLong(settings.get("run-fix.job-timeout-minutes", "30"));
        RepoCoordinates coords = RepoCoordinates.parse(request.repoUrl());
        if (!buildAndLintHelper.runBuildWithRetry(workspace, job)) {
            lifecycle.failFix(job, "Build validation failed after retry attempt(s)");
            return;
        }

        boolean hasChanges;
        try {
            hasChanges = workspace.commitAll("refactor: reduce cyclomatic complexity\n\n" + summary);
        } catch (Exception e) {
            lifecycle.failFix(job, "Commit failed: " + e.getMessage());
            return;
        }

        if (!hasChanges) {
            lifecycle.failFix(job,
                    "Agent completed but made no file changes. Claude summary: " + summary);
            return;
        }

        try {
            workspace.push(request.branchName(), jobTimeoutMinutes);
        } catch (Exception e) {
            lifecycle.failFix(job, "Push failed: " + e.getMessage());
            return;
        }

        GitWorkspaceHelper.DiffStats stats = gitHelper.countChanges(workspace);
        String violation = gitHelper.checkGuardrails(stats);
        if (violation != null) {
            lifecycle.failFix(job, violation);
            return;
        }
        job.setFilesChanged(stats.filesChanged());
        job.setLinesChanged(stats.linesChanged());

        if (request.shouldSkipPrCreation()) {
            job.setStatus(JobStatus.SUCCESS);
            job.setSummary(summary);
            jobStore.archive(job);
            LOG.infof("Quality-fix job %s completed (plan-managed, PR skipped). Branch: %s",
                    job.getJobId(), request.branchName());
            RunResult result = lifecycle.buildResult(job, true);
            lifecycle.notifyResult(result, request.n8nWebhookUrl());
        } else {
            String prUrl;
            String prId;
            try {
                String title = "refactor: reduce cyclomatic complexity";
                String description = "**Automated quality improvement by Code Agent**\n\n" + summary;
                String[] prResult = platformService.createPullRequest(
                        coords.organization(), coords.project(), coords.repository(),
                        request.branchName(), request.targetBranchOrDefault(),
                        title, description);
                prUrl = prResult[0];
                prId = prResult[1];
            } catch (Exception e) {
                lifecycle.failFix(job, "Create PR failed: " + e.getMessage());
                return;
            }

            job.setStatus(JobStatus.AWAITING_APPROVAL);
            job.setSummary(summary);
            job.setPrUrl(prUrl);
            job.setPrId(prId);
            jobStore.update(job);
            lifecycle.auditLog("JOBS", "JOB_AWAITING_APPROVAL", "job", job.getJobId(),
                    java.util.Map.of("prUrl", prUrl, "prId", prId));

            RunResult result = lifecycle.buildResult(job, true);
            lifecycle.notifyResult(result, request.n8nWebhookUrl());

            LOG.infof("Quality-fix job %s completed successfully. PR: %s", job.getJobId(), prUrl);
        }
    }

    private String resolvePrompt(RunFixRequest request) {
        String prompt = request.prompt();
        if (prompt != null && !prompt.isBlank()) {
            return prompt;
        }
        LOG.infof("No prompt provided, fetching JIRA issue %s for task description", request.jiraKey());
        try {
            prompt = jiraService.fetchIssuePrompt(request.jiraKey());
        } catch (Exception e) {
            LOG.warnf("Failed to fetch JIRA issue: %s", e.getMessage());
        }
        if (prompt != null && !prompt.isBlank()) {
            LOG.infof("Using JIRA description as prompt: %s",
                    prompt.length() > 200 ? prompt.substring(0, 200) + "..." : prompt);
            return prompt;
        }
        return null;
    }
}
