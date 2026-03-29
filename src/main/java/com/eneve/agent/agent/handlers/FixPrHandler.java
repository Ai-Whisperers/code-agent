package com.eneve.agent.agent.handlers;

import com.eneve.agent.agent.*;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.jira.JiraService;
import com.eneve.agent.model.*;
import com.eneve.agent.rules.CursorRulesLoader;
import com.eneve.agent.scm.GitPlatformRegistry;
import com.eneve.agent.scm.GitPlatformService;
import com.eneve.agent.workspace.WorkspaceContext;
import com.eneve.agent.settings.SettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@ApplicationScoped
public class FixPrHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(FixPrHandler.class);

    @Inject ClaudeToolUseLoop toolUseLoop;
    @Inject AgentPromptBuilder promptBuilder;
    @Inject CursorRulesLoader rulesLoader;
    @Inject BuildAndLintHelper buildAndLintHelper;
    @Inject GitWorkspaceHelper gitHelper;
    @Inject GitPlatformRegistry platformRegistry;
    @Inject JobStore jobStore;
    @Inject JobLifecycleHelper lifecycle;
    @Inject JiraService jiraService;
    @Inject SettingsService settings;

    @Override
    public JobType jobType() {
        return JobType.FIX_PR;
    }

    @Override
    public void handle(JobRecord job) {
        long jobTimeoutMinutes = Long.parseLong(settings.get("run-fix.job-timeout-minutes", "30"));
        FixPrRequest request = job.getFixPrRequest();
        job.setStatus(JobStatus.RUNNING);
        jobStore.update(job);

        RepoCoordinates coords;
        try {
            coords = RepoCoordinates.parse(request.repoUrl());
        } catch (IllegalArgumentException e) {
            lifecycle.failFixPr(job, "Invalid repo URL: " + e.getMessage());
            return;
        }

        final GitPlatformService platformService = platformRegistry.resolve(request.repoUrl());

        try (WorkspaceContext workspace = WorkspaceContext.create(job.getJobId())) {

            // Phase 1: launch PR comments and shared rules loading in parallel
            CompletableFuture<List<String>> commentsFuture = CompletableFuture.supplyAsync(
                    () -> platformService.getPullRequestComments(
                            coords.organization(), coords.project(), coords.repository(), request.prId()),
                    AgentPools.PARALLEL);

            String resolvedRulesRepoUrl = (request.rulesRepoUrl() != null && !request.rulesRepoUrl().isBlank())
                    ? request.rulesRepoUrl() : settings.get("rules.repo.url", "");
            List<String> ruleNames = request.ruleNames() != null ? request.ruleNames() : Collections.emptyList();
            CompletableFuture<List<String>> sharedRulesFuture = CompletableFuture.supplyAsync(
                    () -> rulesLoader.loadFromRulesRepo(resolvedRulesRepoUrl, ruleNames),
                    AgentPools.PARALLEL);

            Map<String, String> prInfo;
            try {
                prInfo = platformService.getPullRequestInfo(
                        coords.organization(), coords.project(), coords.repository(), request.prId());
            } catch (Exception e) {
                commentsFuture.cancel(true);
                sharedRulesFuture.cancel(true);
                lifecycle.failFixPr(job, "Failed to fetch PR info: " + e.getMessage());
                return;
            }

            String sourceBranch = prInfo.get("sourceBranch");
            String targetBranch = prInfo.get("destinationBranch");
            String prTitle = prInfo.get("title");
            job.setPrId(request.prId());

            String authUrl = platformService.buildCloneUrl(coords.organization(), coords.project(), coords.repository());
            LOG.infof("Fix-PR: cloning %s/%s branch %s for PR #%s",
                    coords.organization(), coords.repository(), sourceBranch, request.prId());
            try {
                workspace.cloneRepo(authUrl, sourceBranch, jobTimeoutMinutes);
            } catch (Exception e) {
                commentsFuture.cancel(true);
                sharedRulesFuture.cancel(true);
                lifecycle.failFixPr(job, "Clone failed: " + e.getMessage());
                return;
            }

            gitHelper.configureGitIfNeeded(workspace);

            // Phase 2: fetchBranch+diff and target-repo rules loading overlap
            CompletableFuture<String> diffFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    workspace.fetchBranch(targetBranch, jobTimeoutMinutes);
                    return workspace.getDiff(targetBranch);
                } catch (Exception e) {
                    LOG.warnf("Failed to compute diff for context (non-fatal): %s", e.getMessage());
                    return "";
                }
            }, AgentPools.PARALLEL);

            CompletableFuture<List<String>> repoRulesFuture = CompletableFuture.supplyAsync(
                    () -> rulesLoader.loadFromTargetRepo(workspace.getRoot()),
                    AgentPools.PARALLEL);

            List<String> reviewComments;
            try {
                reviewComments = commentsFuture.join();
            } catch (Exception e) {
                lifecycle.failFixPr(job, "Failed to fetch PR comments: " + e.getMessage());
                return;
            }

            if (reviewComments.isEmpty()) {
                lifecycle.failFixPr(job,
                        "No review comments found on PR #" + request.prId() + ". Nothing to fix.");
                return;
            }

            String diff = diffFuture.join();
            int maxDiffChars = 40_000;
            if (diff.length() > maxDiffChars) {
                diff = diff.substring(0, maxDiffChars);
            }

            List<String> sharedRules = sharedRulesFuture.join();
            List<String> repoRules = repoRulesFuture.join();

            if (request.jiraKey() != null && !request.jiraKey().isBlank()) {
                lifecycle.safeJira(() -> jiraService.commentStarted(request.jiraKey(),
                        "Auto-fixing review comments on PR #" + request.prId()));
            }

            String systemPrompt = promptBuilder.buildFixPrPrompt(
                    request, prTitle, sourceBranch, targetBranch, diff, reviewComments,
                    sharedRules, repoRules);

            String summary;
            try {
                summary = toolUseLoop.run(systemPrompt, workspace,
                        job.getJobId(), job.getJobType().name());
            } catch (Exception e) {
                lifecycle.failFixPr(job, "Agent loop error: " + e.getMessage());
                return;
            }

            if (!buildAndLintHelper.runBuildWithRetry(workspace, job)) {
                lifecycle.failFixPr(job, "Build validation failed after retry attempt(s)");
                return;
            }

            String fixBranch = "agent/fix-pr-" + request.prId() + "-"
                    + GitWorkspaceHelper.slugify(prTitle != null ? prTitle : "review-fixes");
            boolean hasChanges;
            try {
                workspace.createBranch(fixBranch);
                hasChanges = workspace.commitAll(
                        "fix(PR-" + request.prId() + "): address review comments\n\n" + summary);
            } catch (Exception e) {
                lifecycle.failFixPr(job, "Commit failed: " + e.getMessage());
                return;
            }

            if (!hasChanges) {
                lifecycle.failFixPr(job,
                        "Agent completed but made no file changes. Claude summary: " + summary);
                return;
            }

            GitWorkspaceHelper.DiffStats stats = gitHelper.countChanges(workspace);
            String violation = gitHelper.checkGuardrails(stats);
            if (violation != null) {
                lifecycle.failFixPr(job, violation);
                return;
            }
            job.setFilesChanged(stats.filesChanged());
            job.setLinesChanged(stats.linesChanged());

            try {
                workspace.push(fixBranch, jobTimeoutMinutes);
            } catch (Exception e) {
                lifecycle.failFixPr(job, "Push failed: " + e.getMessage());
                return;
            }

            String prUrl;
            String newPrId;
            try {
                String title = "Fix review comments on PR #" + request.prId();
                String description = "**Automated fix for review comments on PR #" + request.prId() + "**\n\n"
                        + "Original PR: *" + (prTitle != null ? prTitle : "") + "*\n\n"
                        + "## Review comments addressed\n"
                        + String.join("\n", reviewComments.stream()
                                .map(c -> "- " + c)
                                .limit(20)
                                .toList())
                        + "\n\n## Agent summary\n" + summary;
                String[] prResult = platformService.createPullRequest(
                        coords.organization(), coords.project(), coords.repository(),
                        fixBranch, sourceBranch,
                        title, description);
                prUrl = prResult[0];
                newPrId = prResult[1];
            } catch (Exception e) {
                lifecycle.failFixPr(job, "Create PR failed: " + e.getMessage());
                return;
            }

            job.setStatus(JobStatus.AWAITING_APPROVAL);
            job.setSummary(summary);
            job.setPrUrl(prUrl);
            job.setPrId(newPrId);
            jobStore.update(job);

            RunResult result = lifecycle.buildFixPrResult(job, true);

            final String capturedPrUrl = prUrl;
            final String capturedSummary = summary;
            CompletableFuture.runAsync(() -> lifecycle.safeComment(() -> platformService.addPrComment(
                    coords.organization(), coords.project(), coords.repository(), request.prId(),
                    "Code Agent has created a fix PR for the review comments: " + capturedPrUrl)),
                    AgentPools.PARALLEL);

            if (request.jiraKey() != null && !request.jiraKey().isBlank()) {
                CompletableFuture.runAsync(() -> {
                    lifecycle.safeJira(() -> jiraService.commentSuccess(
                            request.jiraKey(), capturedPrUrl, capturedSummary));
                    lifecycle.safeJira(() -> jiraService.addWorklog(request.jiraKey(), null));
                }, AgentPools.PARALLEL);
            }

            CompletableFuture.runAsync(
                    () -> lifecycle.notifyResult(result, request.n8nWebhookUrl()),
                    AgentPools.PARALLEL);

            LOG.infof("Fix-PR job %s completed. Fix PR: %s", job.getJobId(), prUrl);

        } catch (Exception e) {
            lifecycle.failFixPr(job, "Unexpected error: " + e.getMessage());
        }
    }
}
