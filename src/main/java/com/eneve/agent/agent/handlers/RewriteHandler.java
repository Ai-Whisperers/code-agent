package com.eneve.agent.agent.handlers;

import com.anthropic.models.messages.MessageParam;
import com.eneve.agent.agent.AgentPromptBuilder;
import com.eneve.agent.agent.ClaudeToolUseLoop;
import com.eneve.agent.agent.GitWorkspaceHelper;
import com.eneve.agent.agent.JobHandler;
import com.eneve.agent.agent.JobLifecycleHelper;
import com.eneve.agent.agent.ToolDefinitions;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobStatus;
import com.eneve.agent.model.JobType;
import com.eneve.agent.model.RepoCoordinates;
import com.eneve.agent.model.RewriteRequest;
import com.eneve.agent.model.RunResult;
import com.eneve.agent.scm.GitPlatformRegistry;
import com.eneve.agent.scm.GitPlatformService;
import com.eneve.agent.settings.SettingsService;
import com.eneve.agent.workspace.PlanWorkspaceManager;
import com.eneve.agent.workspace.WorkspaceContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Handles REWRITE jobs: clones a source repository (read-only) and a target repository
 * (read-write) into the same plan workspace, runs the Claude tool-use loop to port code
 * from {@code source/} into {@code target/}, then commits and pushes only the target.
 *
 * <p>Supports three rewrite modes driven by {@link RewriteRequest#rewriteModeOrDefault()}:
 * <ul>
 *   <li>{@code full_rewrite} — complete cross-language rewrite (e.g. PHP → C#)</li>
 *   <li>{@code framework_migration} — same language, different framework (e.g. Angular → React)</li>
 *   <li>{@code extraction} — extract a bounded context from a monolith into a standalone service</li>
 * </ul>
 */
@ApplicationScoped
public class RewriteHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(RewriteHandler.class);

    @Inject ClaudeToolUseLoop toolUseLoop;
    @Inject AgentPromptBuilder promptBuilder;
    @Inject GitWorkspaceHelper gitHelper;
    @Inject GitPlatformRegistry platformRegistry;
    @Inject JobStore jobStore;
    @Inject JobLifecycleHelper lifecycle;
    @Inject PlanWorkspaceManager planWorkspaceManager;
    @Inject SettingsService settings;
    @Inject CheckpointAwareJobSupport checkpointSupport;

    @Override
    public JobType jobType() {
        return JobType.REWRITE;
    }

    @Override
    public void handle(JobRecord job) {
        long jobTimeoutMinutes = Long.parseLong(settings.get("run-fix.job-timeout-minutes", "30"));
        RewriteRequest request = (RewriteRequest) job.getPayload();

        job.setStatus(JobStatus.RUNNING);
        if (job.getFixBranchName() == null && request.branchName() != null) {
            job.setFixBranchName(request.branchName());
        }
        jobStore.update(job);

        RepoCoordinates sourceCoords;
        RepoCoordinates targetCoords;
        try {
            sourceCoords = RepoCoordinates.parse(request.sourceRepoUrl());
            targetCoords = RepoCoordinates.parse(request.targetRepoUrl());
        } catch (IllegalArgumentException e) {
            lifecycle.failFix(job, "Invalid repo URL: " + e.getMessage());
            return;
        }

        GitPlatformService sourcePlatform = platformRegistry.resolve(request.sourceRepoUrl());
        GitPlatformService targetPlatform = platformRegistry.resolve(request.targetRepoUrl());

        String sourceAuthUrl = sourcePlatform.buildCloneUrl(
                sourceCoords.organization(), sourceCoords.project(), sourceCoords.repository());
        String targetAuthUrl = targetPlatform.buildCloneUrl(
                targetCoords.organization(), targetCoords.project(), targetCoords.repository());

        WorkspaceContext workspace;
        try {
            workspace = planWorkspaceManager.acquire(job.getPlanId());
        } catch (Exception e) {
            lifecycle.failFix(job, "Failed to acquire plan workspace: " + e.getMessage());
            return;
        }

        try (WorkspaceContext ignored = workspace) {

            // Clone source repo (shallow, read-only) — only on first step of the plan
            if (!workspace.hasClonedRepo("source")) {
                LOG.infof("Cloning source repo (shallow): %s/%s",
                        sourceCoords.organization(), sourceCoords.repository());
                try {
                    workspace.cloneRepoToSubdirShallow("source", sourceAuthUrl, "main", jobTimeoutMinutes);
                } catch (Exception e) {
                    lifecycle.failFix(job, "Failed to clone source repo: " + e.getMessage());
                    return;
                }
            }

            // Clone target repo — create branch if it doesn't exist yet
            if (!workspace.hasClonedRepo("target")) {
                LOG.infof("Cloning target repo: %s/%s (branch: %s)",
                        targetCoords.organization(), targetCoords.repository(), request.branchName());
                try {
                    workspace.cloneRepoToSubdir("target", targetAuthUrl, request.branchName(), jobTimeoutMinutes);
                } catch (Exception cloneEx) {
                    LOG.infof("Branch '%s' not found in target, cloning '%s' and creating branch",
                            request.branchName(), request.targetBranchOrDefault());
                    try {
                        // Clone base branch then create the feature branch in the subdir
                        workspace.cloneRepoToSubdir("target", targetAuthUrl,
                                request.targetBranchOrDefault(), jobTimeoutMinutes);
                        workspace.getRepoPath("target"); // ensure it's tracked
                        // Create the branch inside the target subdir
                        runGitInTargetSubdir(workspace, "checkout", "-b", request.branchName());
                    } catch (Exception e2) {
                        lifecycle.failFix(job, "Failed to clone target repo: " + e2.getMessage());
                        return;
                    }
                }
            }

            try {
                gitHelper.configureGitIfNeeded(workspace);
            } catch (Exception e) {
                LOG.warnf("Could not configure git author (non-fatal): %s", e.getMessage());
            }

            List<MessageParam> priorMessages = checkpointSupport.restoreCheckpointIfPresent(
                    job, workspace, "target");

            // Build system prompt scoped to the rewrite task
            String systemPrompt = promptBuilder.buildRewritePrompt(request, workspace);

            // Run the agent loop
            String initialUserMessage = request.prompt() != null ? request.prompt()
                    : "Complete the rewrite step described in the system prompt. "
                    + "Read from source/, write to target/.";
            String summary;
            try {
                summary = toolUseLoop.runOrResume(systemPrompt, workspace,
                        ToolDefinitions.all(), initialUserMessage,
                        priorMessages, job.getAdditionalIterations(),
                        job.getJobId(), JobType.REWRITE.name(),
                        job.getParentJobId(), job.getDepth());
            } catch (Exception e) {
                lifecycle.failFix(job, "Agent loop error: " + e.getMessage());
                return;
            }

            // Commit only the target subdir
            boolean hasChanges;
            String commitMessage = "feat: " + job.getSummary() + "\n\n" + summary;
            try {
                hasChanges = workspace.commitSubdir("target", commitMessage);
            } catch (Exception e) {
                lifecycle.failFix(job, "Commit failed: " + e.getMessage());
                return;
            }

            if (!hasChanges) {
                if (job.getPlanId() != null) {
                    job.setStatus(JobStatus.SUCCESS);
                    job.setSummary("No changes required — task already complete.\n\n" + summary);
                    jobStore.archive(job);
                    LOG.infof("Rewrite job %s: no changes needed, marking SUCCESS", job.getJobId());
                    RunResult result = lifecycle.buildResult(job, true);
                    lifecycle.notifyResult(result, null);
                } else {
                    lifecycle.failFix(job, "Agent completed but made no file changes. Summary: " + summary);
                }
                return;
            }

            // Guardrails check on target subdir only
            try {
                int filesChanged = workspace.countFilesChangedInSubdir("target");
                int linesChanged = workspace.countLinesChangedInSubdir("target");
                GitWorkspaceHelper.DiffStats stats = new GitWorkspaceHelper.DiffStats(filesChanged, linesChanged);
                String violation = gitHelper.checkGuardrails(stats);
                if (violation != null) {
                    lifecycle.failFix(job, violation);
                    return;
                }
                job.setFilesChanged(filesChanged);
                job.setLinesChanged(linesChanged);
            } catch (Exception e) {
                LOG.warnf("Could not compute diff stats for target subdir (non-fatal): %s", e.getMessage());
            }

            // Push target repo branch
            try {
                workspace.pushSubdir("target", request.branchName(), jobTimeoutMinutes);
            } catch (Exception e) {
                lifecycle.failFix(job, "Push failed: " + e.getMessage());
                return;
            }

            if (request.shouldSkipPrCreation()) {
                // Intermediate plan step — PR will be created by PlanOrchestratorService.markCompleted()
                job.setStatus(JobStatus.SUCCESS);
                job.setSummary(summary);
                jobStore.archive(job);
                LOG.infof("Rewrite job %s completed (plan-managed, PR skipped). Branch: %s",
                        job.getJobId(), request.branchName());
                RunResult result = lifecycle.buildResult(job, true);
                lifecycle.notifyResult(result, null);
            } else {
                // Standalone rewrite job — create PR on target repo
                String prUrl;
                String prId;
                try {
                    String title = "rewrite: " + (request.rewriteModeOrDefault().replace("_", " "))
                            + " (" + request.sourceLanguage() + " → " + request.targetLanguage() + ")";
                    String description = "**Automated rewrite by Code Agent**\n\n"
                            + "Mode: " + request.rewriteModeOrDefault() + "\n"
                            + "Source: " + request.sourceRepoUrl() + "\n\n"
                            + summary;
                    String[] prResult = targetPlatform.createPullRequest(
                            targetCoords.organization(), targetCoords.project(), targetCoords.repository(),
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
                lifecycle.notifyResult(result, null);
                LOG.infof("Rewrite job %s completed. PR: %s", job.getJobId(), prUrl);
            }

        } catch (Exception e) {
            lifecycle.failFix(job, "Unexpected error: " + e.getMessage());
        }
    }

    private void runGitInTargetSubdir(WorkspaceContext workspace, String... args) throws Exception {
        java.nio.file.Path targetDir = workspace.getRepoPath("target");
        if (targetDir == null) {
            throw new IllegalStateException("target subdir not cloned");
        }
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(targetDir.toFile())
                .redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes());
        boolean finished = proc.waitFor(1, java.util.concurrent.TimeUnit.MINUTES);
        if (!finished) {
            proc.destroyForcibly();
            throw new java.io.IOException("Git command timed out: " + String.join(" ", cmd));
        }
        if (proc.exitValue() != 0) {
            throw new java.io.IOException("Git command failed: " + output);
        }
    }
}
