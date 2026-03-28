package com.eneve.agent.agent.handlers;

import com.eneve.agent.agent.*;
import com.eneve.agent.agent.model.QualityReport;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.agent.store.QualityReportStore;
import com.eneve.agent.jira.JiraService;
import com.eneve.agent.model.*;
import com.eneve.agent.scm.GitPlatformService;
import com.eneve.agent.workspace.PlanWorkspaceManager;
import com.eneve.agent.workspace.WorkspaceContext;
import com.eneve.agent.settings.SettingsService;

import java.util.stream.Collectors;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class GenerateTestsHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(GenerateTestsHandler.class);

    @Inject ClaudeToolUseLoop toolUseLoop;
    @Inject AgentPromptBuilder promptBuilder;
    @Inject GitPlatformService platformService;
    @Inject CoverageReporter coverageReporter;
    @Inject JsCoverageReporter jsCoverageReporter;
    @Inject BuildAndLintHelper buildAndLintHelper;
    @Inject GitWorkspaceHelper gitHelper;
    @Inject JobStore jobStore;
    @Inject JobLifecycleHelper lifecycle;
    @Inject JiraService jiraService;
    @Inject PlanWorkspaceManager planWorkspaceManager;
    @Inject SettingsService settings;
    @Inject QualityReportStore qualityReportStore;

    @Override
    public JobType jobType() {
        return JobType.GENERATE_TESTS;
    }

    @Override
    public void handle(JobRecord job) {
        int generateTestsMaxIterations = Integer.parseInt(settings.get("generate-tests.max-loop-iterations", "500"));
        long generateTestsTimeoutMinutes = Long.parseLong(settings.get("generate-tests.job-timeout-minutes", "60"));
        GenerateTestsRequest request = job.getGenerateTestsRequest();
        job.setStatus(JobStatus.RUNNING);
        jobStore.update(job);

        RepoCoordinates coords;
        try {
            coords = RepoCoordinates.parse(request.repoUrl());
        } catch (IllegalArgumentException e) {
            lifecycle.failGenerateTests(job, "Invalid repo URL: " + e.getMessage());
            return;
        }

        WorkspaceContext workspace;
        try {
            workspace = job.getPlanId() != null
                    ? planWorkspaceManager.acquire(job.getPlanId())
                    : WorkspaceContext.create(job.getJobId());
        } catch (Exception e) {
            lifecycle.failGenerateTests(job, "Failed to acquire workspace: " + e.getMessage());
            return;
        }

        try (WorkspaceContext ignored = workspace) {

            String authUrl = platformService.buildCloneUrl(coords.organization(), coords.project(), coords.repository());
            String testBranch = request.branchName();
            if (workspace.hasClonedRepo()) {
                LOG.infof("GenerateTests: reusing existing workspace for plan %s (branch: %s)",
                        job.getPlanId(), testBranch);
            } else {
                LOG.infof("GenerateTests: cloning %s/%s (branch: %s)",
                        coords.organization(), coords.repository(), testBranch);
                try {
                    workspace.cloneRepo(authUrl, testBranch, generateTestsTimeoutMinutes);
                } catch (Exception e) {
                    String[] baseCandidates = buildBaseBranchCandidates(request.targetBranchOrDefault());
                    boolean cloned = false;
                    Exception lastError = e;
                    for (String base : baseCandidates) {
                        try {
                            workspace.cloneAndCreateBranch(authUrl, base, testBranch, generateTestsTimeoutMinutes);
                            LOG.infof("GenerateTests: created branch '%s' from base '%s'", testBranch, base);
                            cloned = true;
                            break;
                        } catch (Exception ex) {
                            LOG.infof("GenerateTests: base branch '%s' not available, trying next: %s", base, ex.getMessage());
                            lastError = ex;
                        }
                    }
                    if (!cloned) {
                        lifecycle.failGenerateTests(job, "Clone/branch failed: " + lastError.getMessage());
                        return;
                    }
                }
            }

            gitHelper.configureGitIfNeeded(workspace);

            // Capture initial HEAD so we can detect commits made by the agent.
            String initialHeadSha = null;
            try {
                initialHeadSha = workspace.getHeadSha();
            } catch (Exception e) {
                LOG.warnf("GenerateTests: could not capture initial HEAD SHA (non-fatal): %s", e.getMessage());
            }

            // Prefer stored quality-report coverage over live re-measurement (avoids a full test run).
            CoverageReporter.CoverageSnapshot baselineCoverage = loadStoredCoverage(
                    coords.organization(), coords.repository(), request.targetBranchOrDefault());

            if (baselineCoverage == null && coverageReporter.isJacocoPresent(workspace)) {
                try {
                    LOG.info("GenerateTests: no stored baseline — measuring Java coverage live...");
                    baselineCoverage = coverageReporter.measureCoverage(workspace);
                    if (baselineCoverage != null) {
                        LOG.infof("GenerateTests: Java baseline — lines %.1f%%, branches %.1f%%",
                                baselineCoverage.lineRate(), baselineCoverage.branchRate());
                    }
                } catch (Exception e) {
                    LOG.warnf("GenerateTests: baseline Java coverage measurement failed (non-fatal): %s",
                            e.getMessage());
                }
            }

            if (baselineCoverage == null && jsCoverageReporter.isApplicable(workspace)) {
                try {
                    LOG.info("GenerateTests: no stored baseline — measuring JS/TS coverage live...");
                    long coverageTimeout = Long.parseLong(
                            settings.get("generate-tests.coverage-timeout-minutes", "20"));
                    baselineCoverage = jsCoverageReporter.measureCoverage(workspace, coverageTimeout);
                    if (baselineCoverage != null) {
                        LOG.infof("GenerateTests: JS baseline — lines %.1f%%, branches %.1f%%",
                                baselineCoverage.lineRate(), baselineCoverage.branchRate());
                    }
                } catch (Exception e) {
                    LOG.warnf("GenerateTests: baseline JS/TS coverage measurement failed (non-fatal): %s",
                            e.getMessage());
                }
            }

            if (request.jiraKey() != null && !request.jiraKey().isBlank()) {
                lifecycle.safeJira(() -> jiraService.commentStarted(request.jiraKey(), testBranch));
            }

            String systemPrompt = promptBuilder.buildGenerateTestsPrompt(request, workspace, baselineCoverage);

            String summary;
            try {
                summary = toolUseLoop.run(systemPrompt, workspace,
                        generateTestsMaxIterations, job.getJobId(), job.getJobType().name());
            } catch (Exception e) {
                lifecycle.failGenerateTests(job, "Agent loop error: " + e.getMessage());
                return;
            }

            // Detect loop-limit: the summary contains a well-known sentinel phrase.
            boolean hitLoopLimit = summary != null && summary.contains("maximum iterations");

            if (hitLoopLimit) {
                LOG.warnf("GenerateTests: agent hit loop limit for job %s — attempting partial recovery",
                        job.getJobId());
                if (recoverPartialWork(job, workspace, coords, request,
                        testBranch, generateTestsTimeoutMinutes, initialHeadSha, summary)) {
                    return; // partial PR created — handler done
                }
                lifecycle.failGenerateTests(job,
                        "Agent hit loop limit and no committed work was found. "
                                + "Increase generate-tests.max-loop-iterations or reduce the number of target packages.");
                return;
            }

            CoverageReporter.CoverageSnapshot afterCoverage = null;
            if (coverageReporter.isJacocoPresent(workspace)) {
                try {
                    LOG.info("GenerateTests: measuring post-generation coverage...");
                    afterCoverage = coverageReporter.measureCoverage(workspace);
                    if (afterCoverage != null) {
                        LOG.infof("GenerateTests: after — lines %.1f%%, branches %.1f%%",
                                afterCoverage.lineRate(), afterCoverage.branchRate());
                    }
                } catch (Exception e) {
                    lifecycle.failGenerateTests(job,
                            "Build validation failed (generated tests did not pass): " + e.getMessage());
                    return;
                }
            } else if (jsCoverageReporter.isApplicable(workspace)) {
                try {
                    LOG.info("GenerateTests: measuring post-generation JS/TS coverage...");
                    long coverageTimeout = Long.parseLong(
                            settings.get("generate-tests.coverage-timeout-minutes", "20"));
                    afterCoverage = jsCoverageReporter.measureCoverage(workspace, coverageTimeout);
                    if (afterCoverage != null) {
                        LOG.infof("GenerateTests: JS after — lines %.1f%%, branches %.1f%%",
                                afterCoverage.lineRate(), afterCoverage.branchRate());
                    }
                } catch (Exception e) {
                    lifecycle.failGenerateTests(job,
                            "Build validation failed (generated tests did not pass): " + e.getMessage());
                    return;
                }
            } else {
                if (!buildAndLintHelper.runBuildWithRetry(workspace, job)) {
                    lifecycle.failGenerateTests(job,
                            "Build validation failed after retry attempt(s) (generated tests did not pass)");
                    return;
                }
            }

            String coverageSummary = "";
            if (afterCoverage != null) {
                coverageSummary = "\n\n" + afterCoverage.formatMarkdownComparison(baselineCoverage);
            }

            // Persist before/after coverage snapshots on the job for UI display.
            if (baselineCoverage != null || afterCoverage != null) {
                job.setCoverageData(new JobCoverageData(
                        toSection(baselineCoverage),
                        toSection(afterCoverage)));
            }

            boolean hasChanges;
            try {
                String commitMsg = "test: generate unit tests"
                        + (request.jiraKey() != null && !request.jiraKey().isBlank()
                                ? " for " + request.jiraKey() : "")
                        + "\n\n" + summary;
                hasChanges = workspace.commitAll(commitMsg);
            } catch (Exception e) {
                lifecycle.failGenerateTests(job, "Commit failed: " + e.getMessage());
                return;
            }

            // The agent may have already committed everything per-package; that is fine.
            boolean anyWork = hasChanges;
            if (!anyWork && initialHeadSha != null) {
                try { anyWork = workspace.hasCommitsSince(initialHeadSha); } catch (Exception ex) {
                    LOG.debugf("GenerateTests: hasCommitsSince check failed (non-fatal): %s", ex.getMessage());
                }
            }
            if (!anyWork) {
                lifecycle.failGenerateTests(job,
                        "Agent completed but made no file changes. Claude summary: " + summary);
                return;
            }

            try {
                workspace.push(testBranch, generateTestsTimeoutMinutes);
            } catch (Exception e) {
                lifecycle.failGenerateTests(job, "Push failed: " + e.getMessage());
                return;
            }

            GitWorkspaceHelper.DiffStats stats = gitHelper.countChanges(workspace);
            job.setFilesChanged(stats.filesChanged());
            job.setLinesChanged(stats.linesChanged());

            String prUrl;
            String prId;
            try {
                String title = "test: generate unit tests"
                        + (request.jiraKey() != null && !request.jiraKey().isBlank()
                                ? " (" + request.jiraKey() + ")" : "");
                String description = "**Automated unit test generation by Code Agent**\n\n"
                        + (request.jiraKey() != null && !request.jiraKey().isBlank()
                                ? "JIRA: " + request.jiraKey() + "\n\n" : "")
                        + summary
                        + coverageSummary;
                String[] prResult = platformService.createPullRequest(
                        coords.organization(), coords.project(), coords.repository(),
                        testBranch, request.targetBranchOrDefault(),
                        title, description);
                prUrl = prResult[0];
                prId = prResult[1];
            } catch (Exception e) {
                lifecycle.failGenerateTests(job, "Create PR failed: " + e.getMessage());
                return;
            }

            job.setStatus(JobStatus.AWAITING_APPROVAL);
            job.setSummary(summary);
            job.setPrUrl(prUrl);
            job.setPrId(prId);
            jobStore.update(job);

            if (request.jiraKey() != null && !request.jiraKey().isBlank()) {
                final String capturedPrUrl = prUrl;
                final String capturedSummary = summary;
                lifecycle.safeJira(() -> jiraService.commentSuccess(
                        request.jiraKey(), capturedPrUrl, capturedSummary));
            }

            RunResult result = lifecycle.buildGenerateTestsResult(job, true);
            lifecycle.notifyResult(result, request.n8nWebhookUrl());

            LOG.infof("GenerateTests job %s completed. PR: %s", job.getJobId(), prUrl);

        } catch (Exception e) {
            lifecycle.failGenerateTests(job, "Unexpected error: " + e.getMessage());
        }
    }

    /**
     * Called when the agent loop hit its iteration cap.
     * <p>
     * Strategy:
     * <ol>
     *   <li>Commit any uncommitted working-tree changes so nothing is lost.</li>
     *   <li>Check whether the agent made any commits at all since the job started.</li>
     *   <li>If yes → push the branch and open a PR marked as partial/incomplete so the
     *       developer can review and continue manually.</li>
     *   <li>If no → return {@code false} so the caller can mark the job as failed.</li>
     * </ol>
     *
     * @return {@code true} if partial work was pushed and a PR was opened (job set to
     *         {@link JobStatus#AWAITING_APPROVAL}); {@code false} if nothing was salvaged.
     */
    private boolean recoverPartialWork(
            JobRecord job, WorkspaceContext workspace,
            RepoCoordinates coords, GenerateTestsRequest request,
            String testBranch, long timeoutMinutes,
            String initialHeadSha, String agentSummary) {
        try {
            // Stage and commit anything the agent left uncommitted.
            boolean uncommittedSaved = workspace.commitAll(
                    "test: partial test generation — uncommitted work at loop limit");
            if (uncommittedSaved) {
                LOG.infof("GenerateTests recovery: committed uncommitted working-tree changes for job %s",
                        job.getJobId());
            }

            // Check whether there are any commits to push.
            boolean hasWork = false;
            if (initialHeadSha != null) {
                hasWork = workspace.hasCommitsSince(initialHeadSha);
            } else {
                // No initial SHA recorded — use the diff to detect changes.
                hasWork = uncommittedSaved;
            }

            if (!hasWork) {
                LOG.warnf("GenerateTests recovery: no commits found for job %s — nothing to push",
                        job.getJobId());
                return false;
            }

            workspace.push(testBranch, timeoutMinutes);
            LOG.infof("GenerateTests recovery: pushed partial branch %s for job %s", testBranch, job.getJobId());

            GitWorkspaceHelper.DiffStats stats = gitHelper.countChanges(workspace);
            job.setFilesChanged(stats.filesChanged());
            job.setLinesChanged(stats.linesChanged());

            String title = "[PARTIAL] test: generate unit tests"
                    + (request.jiraKey() != null && !request.jiraKey().isBlank()
                            ? " (" + request.jiraKey() + ")" : "");
            String description = "⚠️ **This PR contains partial work.**\n\n"
                    + "The agent reached its iteration limit before completing all target packages. "
                    + "Packages that were committed are included here. "
                    + "You can requeue a generate-tests job on the remaining packages to continue.\n\n"
                    + (request.jiraKey() != null && !request.jiraKey().isBlank()
                            ? "JIRA: " + request.jiraKey() + "\n\n" : "")
                    + "---\n\n**Agent log (partial):**\n" + agentSummary;

            String[] prResult = platformService.createPullRequest(
                    coords.organization(), coords.project(), coords.repository(),
                    testBranch, request.targetBranchOrDefault(),
                    title, description);

            job.setStatus(JobStatus.AWAITING_APPROVAL);
            job.setSummary("[PARTIAL] " + agentSummary);
            job.setPrUrl(prResult[0]);
            job.setPrId(prResult[1]);
            jobStore.update(job);
            LOG.infof("GenerateTests recovery: partial PR created at %s for job %s", prResult[0], job.getJobId());
            return true;
        } catch (Exception e) {
            LOG.errorf("GenerateTests recovery: failed to salvage partial work for job %s: %s",
                    job.getJobId(), e.getMessage());
            return false;
        }
    }

    /**
     * Converts a live {@link CoverageReporter.CoverageSnapshot} into the
     * {@link QualityReport.CoverageSection} shape used for persistent storage.
     * Returns {@code null} when the snapshot is {@code null}.
     */
    private static QualityReport.CoverageSection toSection(CoverageReporter.CoverageSnapshot s) {
        if (s == null) return null;
        List<QualityReport.PackageLineCoverage> pkgs = s.packages() == null ? List.of()
                : s.packages().stream()
                        .map(p -> new QualityReport.PackageLineCoverage(p.name(), p.linesCovered(), p.linesMissed()))
                        .collect(Collectors.toList());
        return new QualityReport.CoverageSection(
                s.lineRate(), s.branchRate(), s.methodRate(), s.classRate(),
                s.linesCovered(), s.linesMissed(),
                s.branchesCovered(), s.branchesMissed(),
                s.methodsCovered(), s.methodsMissed(),
                s.classesCovered(), s.classesMissed(),
                pkgs);
    }

    /**
     * Attempts to load a stored coverage baseline from the most recent quality report.
     * Returns {@code null} if no report with package-level coverage data is available.
     */
    private CoverageReporter.CoverageSnapshot loadStoredCoverage(
            String workspace, String repoSlug, String branch) {
        try {
            Optional<QualityReport> latest = qualityReportStore.findLatest(workspace, repoSlug, branch);
            if (latest.isEmpty()) return null;

            QualityReport.CoverageSection cs = latest.get().coverage();
            if (cs == null || cs.packages() == null || cs.packages().isEmpty()) return null;

            List<CoverageReporter.PackageCoverage> pkgs = cs.packages().stream()
                    .map(p -> new CoverageReporter.PackageCoverage(p.name(), p.linesCovered(), p.linesMissed()))
                    .toList();

            LOG.infof("GenerateTests: reusing stored coverage from quality report (lines %.1f%%, %d packages)",
                    cs.lineRate(), pkgs.size());

            return new CoverageReporter.CoverageSnapshot(
                    cs.linesCovered(), cs.linesMissed(),
                    cs.branchesCovered(), cs.branchesMissed(),
                    cs.methodsCovered(), cs.methodsMissed(),
                    cs.classesCovered(), cs.classesMissed(),
                    pkgs);
        } catch (Exception e) {
            LOG.warnf("GenerateTests: could not load stored coverage (non-fatal): %s", e.getMessage());
            return null;
        }
    }

    /**
     * Returns an ordered list of candidate base branches to try when creating a new branch.
     * The explicitly configured target branch is always first; well-known fallbacks follow
     * so that repos without a {@code develop} branch can still be cloned from {@code main}
     * or {@code master}.
     */
    private static String[] buildBaseBranchCandidates(String preferredBase) {
        List<String> candidates = new java.util.ArrayList<>();
        candidates.add(preferredBase);
        for (String fallback : new String[]{"main", "master", "develop"}) {
            if (!fallback.equals(preferredBase)) {
                candidates.add(fallback);
            }
        }
        return candidates.toArray(new String[0]);
    }
}
