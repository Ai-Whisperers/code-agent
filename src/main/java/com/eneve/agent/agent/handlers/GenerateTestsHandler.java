package com.eneve.agent.agent.handlers;

import com.eneve.agent.agent.*;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.jira.JiraService;
import com.eneve.agent.model.*;
import com.eneve.agent.scm.GitPlatformService;
import com.eneve.agent.workspace.PlanWorkspaceManager;
import com.eneve.agent.workspace.WorkspaceContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class GenerateTestsHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(GenerateTestsHandler.class);

    @Inject ClaudeToolUseLoop toolUseLoop;
    @Inject AgentPromptBuilder promptBuilder;
    @Inject GitPlatformService platformService;
    @Inject CoverageReporter coverageReporter;
    @Inject BuildAndLintHelper buildAndLintHelper;
    @Inject GitWorkspaceHelper gitHelper;
    @Inject JobStore jobStore;
    @Inject JobLifecycleHelper lifecycle;
    @Inject JiraService jiraService;
    @Inject PlanWorkspaceManager planWorkspaceManager;

    @ConfigProperty(name = "generate-tests.max-loop-iterations", defaultValue = "500")
    int generateTestsMaxIterations;

    @ConfigProperty(name = "generate-tests.job-timeout-minutes", defaultValue = "60")
    long generateTestsTimeoutMinutes;

    @Override
    public JobType jobType() {
        return JobType.GENERATE_TESTS;
    }

    @Override
    public void handle(JobRecord job) {
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
                    try {
                        workspace.cloneAndCreateBranch(authUrl, request.targetBranchOrDefault(),
                                testBranch, generateTestsTimeoutMinutes);
                    } catch (Exception e2) {
                        lifecycle.failGenerateTests(job, "Clone/branch failed: " + e2.getMessage());
                        return;
                    }
                }
            }

            gitHelper.configureGitIfNeeded(workspace);

            CoverageReporter.CoverageSnapshot baselineCoverage = null;
            if (coverageReporter.isJacocoPresent(workspace)) {
                try {
                    LOG.info("GenerateTests: measuring baseline coverage...");
                    baselineCoverage = coverageReporter.measureCoverage(workspace);
                    if (baselineCoverage != null) {
                        LOG.infof("GenerateTests: baseline — lines %.1f%%, branches %.1f%%",
                                baselineCoverage.lineRate(), baselineCoverage.branchRate());
                    }
                } catch (Exception e) {
                    LOG.warnf("Baseline coverage measurement failed (non-fatal): %s", e.getMessage());
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

            if (!hasChanges) {
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
}
