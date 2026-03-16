package com.eneve.agent.agent.handlers;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.eneve.agent.agent.AgentPromptBuilder;
import com.eneve.agent.agent.BuildAndLintHelper;
import com.eneve.agent.agent.ClaudeToolUseLoop;
import com.eneve.agent.agent.CommentContext;
import com.eneve.agent.agent.CommentStore;
import com.eneve.agent.agent.GitWorkspaceHelper;
import com.eneve.agent.agent.JobHandler;
import com.eneve.agent.agent.JobLifecycleHelper;
import com.eneve.agent.agent.JobStore;
import com.eneve.agent.agent.LearningExtractor;
import com.eneve.agent.agent.ToolDefinitions;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobStatus;
import com.eneve.agent.model.JobType;
import com.eneve.agent.model.ReplyCommentRequest;
import com.eneve.agent.model.RepoCoordinates;
import com.eneve.agent.scm.GitPlatformService;
import com.eneve.agent.scm.ThreadComment;
import com.eneve.agent.workspace.WorkspaceContext;

@ApplicationScoped
public class FixCommentHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(FixCommentHandler.class);

    @Inject ClaudeToolUseLoop toolUseLoop;
    @Inject AgentPromptBuilder promptBuilder;
    @Inject GitPlatformService platformService;
    @Inject CommentStore commentStore;
    @Inject LearningExtractor learningExtractor;
    @Inject JobStore jobStore;
    @Inject BuildAndLintHelper buildAndLintHelper;
    @Inject GitWorkspaceHelper gitHelper;
    @Inject JobLifecycleHelper lifecycle;

    @ConfigProperty(name = "git.username")
    String gitUser;

    @ConfigProperty(name = "git.password")
    String gitPassword;

    @ConfigProperty(name = "run-fix.job-timeout-minutes", defaultValue = "30")
    long jobTimeoutMinutes;

    @Override
    public JobType jobType() {
        return JobType.FIX_COMMENT;
    }

    @Override
    public void handle(JobRecord job) {
        ReplyCommentRequest request = job.getReplyRequest();
        job.setStatus(JobStatus.RUNNING);
        job.setPrId(request.prId());
        jobStore.update(job);

        Optional<CommentContext> ctxOpt = commentStore.find(request.parentCommentId());
        if (ctxOpt.isEmpty()) {
            lifecycle.failFixComment(job, request,
                    "Original comment context not found for comment #" + request.parentCommentId());
            return;
        }
        CommentContext ctx = ctxOpt.get();

        RepoCoordinates coords;
        try {
            coords = RepoCoordinates.parse(request.repoUrl());
        } catch (IllegalArgumentException e) {
            lifecycle.failFixComment(job, request, "Invalid repo URL: " + e.getMessage());
            return;
        }

        try (WorkspaceContext workspace = WorkspaceContext.create(job.getJobId())) {

            Map<String, String> prInfo;
            try {
                prInfo = platformService.getPullRequestInfo(
                        coords.organization(), coords.project(), coords.repository(), request.prId());
            } catch (Exception e) {
                lifecycle.failFixComment(job, request, "Failed to fetch PR info: " + e.getMessage());
                return;
            }

            String sourceBranch = prInfo.get("sourceBranch");
            String authUrl = coords.httpsCloneUrl(gitUser, gitPassword);
            LOG.infof("FixComment: cloning %s/%s branch %s for comment fix on PR #%s",
                    coords.organization(), coords.repository(), sourceBranch, request.prId());
            try {
                workspace.cloneRepo(authUrl, sourceBranch, jobTimeoutMinutes);
            } catch (Exception e) {
                lifecycle.failFixComment(job, request, "Clone failed: " + e.getMessage());
                return;
            }

            gitHelper.configureGitIfNeeded(workspace);

            java.util.List<ThreadComment> thread;
            try {
                thread = platformService.getCommentThread(
                        coords.organization(), coords.project(), coords.repository(),
                        request.prId(), request.parentCommentId());
            } catch (Exception e) {
                LOG.warnf("Failed to fetch comment thread (non-fatal): %s", e.getMessage());
                thread = Collections.emptyList();
            }

            String systemPrompt = promptBuilder.buildFixCommentPrompt(ctx, thread, request.humanMessage());

            String summary;
            try {
                summary = toolUseLoop.run(systemPrompt, workspace,
                        ToolDefinitions.all(),
                        "A developer has requested that you implement the fix from your review comment. "
                                + "Read the relevant code, apply the fix, and run tests.",
                        job.getJobId(), job.getJobType().name());
            } catch (Exception e) {
                lifecycle.failFixComment(job, request, "Agent fix loop error: " + e.getMessage());
                return;
            }

            if (!buildAndLintHelper.runBuildWithRetry(workspace, job)) {
                lifecycle.failFixComment(job, request,
                        "Build validation failed after retry attempt(s)");
                return;
            }

            boolean hasChanges;
            try {
                String findingSummary = ctx.findingText();
                if (findingSummary != null && findingSummary.length() > 60) {
                    findingSummary = findingSummary.substring(0, 57) + "...";
                }
                String commitMsg = "fix: " + (ctx.filePath() != null ? ctx.filePath() : "review")
                        + (ctx.line() > 0 ? ":" + ctx.line() : "")
                        + " — " + (findingSummary != null ? findingSummary : "review comment fix");
                hasChanges = workspace.commitAll(commitMsg);
            } catch (Exception e) {
                lifecycle.failFixComment(job, request, "Commit failed: " + e.getMessage());
                return;
            }

            if (!hasChanges) {
                lifecycle.failFixComment(job, request,
                        "Agent completed but made no file changes. Claude summary: " + summary);
                return;
            }

            GitWorkspaceHelper.DiffStats stats = gitHelper.countChanges(workspace);
            String violation = gitHelper.checkGuardrails(stats);
            if (violation != null) {
                lifecycle.failFixComment(job, request, violation);
                return;
            }
            job.setFilesChanged(stats.filesChanged());
            job.setLinesChanged(stats.linesChanged());

            try {
                workspace.push(sourceBranch, jobTimeoutMinutes);
            } catch (Exception e) {
                lifecycle.failFixComment(job, request, "Push failed: " + e.getMessage());
                return;
            }

            String commitSha = null;
            try {
                commitSha = workspace.getHeadSha();
            } catch (Exception e) {
                LOG.warnf("Failed to get HEAD SHA (non-fatal): %s", e.getMessage());
            }

            String replyText = "Applied fix"
                    + (commitSha != null
                            ? " in commit `" + commitSha.substring(0, Math.min(8, commitSha.length())) + "`"
                            : "")
                    + ".\n\n" + summary;
            try {
                long replyCommentId = platformService.replyToComment(
                        coords.organization(), coords.project(), coords.repository(),
                        request.prId(), request.parentCommentId(), replyText);
                if (replyCommentId > 0) {
                    commentStore.save(replyCommentId, new CommentContext(
                            request.prId(), coords.organization(), coords.project(),
                            coords.repository(),
                            ctx.filePath(), ctx.line(), ctx.category(), ctx.severity(),
                            ctx.findingText(), ctx.reviewJobId()));
                }
            } catch (Exception e) {
                LOG.warnf("Failed to post fix confirmation reply (non-fatal): %s", e.getMessage());
            }

            job.setStatus(JobStatus.SUCCESS);
            job.setSummary(summary);
            jobStore.archive(job);
            lifecycle.notifyResult(lifecycle.buildFixCommentResult(job, true), null);
            LOG.infof("FixComment job %s completed for comment #%d on PR #%s",
                    job.getJobId(), request.parentCommentId(), request.prId());

        } catch (Exception e) {
            lifecycle.failFixComment(job, request, "Unexpected error: " + e.getMessage());
        }
    }
}
