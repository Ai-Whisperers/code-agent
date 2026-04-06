package com.eneve.agent.agent.handlers;

import com.eneve.agent.agent.*;
import com.eneve.agent.agent.model.CommentContext;
import com.eneve.agent.agent.store.CommentStore;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.model.*;
import com.eneve.agent.scm.GitPlatformRegistry;
import com.eneve.agent.scm.GitPlatformService;
import com.eneve.agent.scm.ThreadComment;
import com.eneve.agent.workspace.WorkspaceContext;
import com.eneve.agent.settings.SettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class FixCommentHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(FixCommentHandler.class);

    @Inject ClaudeToolUseLoop toolUseLoop;
    @Inject AgentPromptBuilder promptBuilder;
    @Inject GitPlatformRegistry platformRegistry;
    @Inject CommentStore commentStore;
    @Inject LearningExtractor learningExtractor;
    @Inject JobStore jobStore;
    @Inject JobQueue jobQueue;
    @Inject BuildAndLintHelper buildAndLintHelper;
    @Inject GitWorkspaceHelper gitHelper;
    @Inject JobLifecycleHelper lifecycle;
    @Inject SettingsService settings;

    @Override
    public JobType jobType() {
        return JobType.FIX_COMMENT;
    }

    @Override
    public void handle(JobRecord job) {
        long jobTimeoutMinutes = Long.parseLong(settings.get("run-fix.job-timeout-minutes", "30"));
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

        final GitPlatformService platformService = platformRegistry.resolve(request.repoUrl());

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
            String authUrl = platformService.buildCloneUrl(coords.organization(), coords.project(), coords.repository());
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
                        job.getJobId(), job.getJobType().name(),
                        job.getParentJobId(), job.getDepth());
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

            maybeScheduleFollowUpReview(job, request, commitSha);

        } catch (Exception e) {
            lifecycle.failFixComment(job, request, "Unexpected error: " + e.getMessage());
        }
    }

    /**
     * After a successful fix, automatically submits an incremental REVIEW job for the same PR
     * so the bot can verify the fix actually addresses the original finding and hasn't introduced
     * regressions. Controlled by the {@code review.auto-review-after-fix} setting (default: true).
     */
    private void maybeScheduleFollowUpReview(JobRecord fixJob, ReplyCommentRequest request,
                                             String headCommitSha) {
        boolean autoReview = Boolean.parseBoolean(
                settings.get("review.auto-review-after-fix", "true"));
        if (!autoReview) {
            return;
        }

        String prId = request.prId();
        if (prId == null || prId.isBlank()) {
            return;
        }

        // Don't create a duplicate review if one is already queued/running for this PR
        List<JobRecord> prJobs = jobStore.findByPrId(prId);
        boolean alreadyPending = prJobs.stream()
                .filter(j -> j.getJobType() == JobType.REVIEW)
                .anyMatch(j -> j.getStatus() == JobStatus.RUNNING
                        || j.getStatus() == JobStatus.PENDING
                        || j.getStatus() == JobStatus.QUEUED);
        if (alreadyPending) {
            LOG.infof("FixComment follow-up: REVIEW already queued for PR #%s — skipping", prId);
            return;
        }

        try {
            ReviewPrRequest reviewRequest = new ReviewPrRequest(
                    request.repoUrl(),
                    prId,
                    null,   // targetBranch — resolved by ReviewHandler from platform
                    null,   // jiraKey
                    null,   // rulesRepoUrl
                    null,   // ruleNames
                    null,   // extraRules
                    null,   // n8nWebhookUrl
                    headCommitSha, // headCommitSha — the fix commit; used by ReviewHandler for duplicate skip check
                    null    // prAuthor
            );

            String reviewJobId = UUID.randomUUID().toString();
            JobRecord reviewJob = new JobRecord(reviewJobId, reviewRequest);
            reviewJob.setParentJobId(fixJob.getJobId());
            reviewJob.setDepth(fixJob.getDepth() + 1);
            jobStore.put(reviewJob);

            if (jobQueue.submit(reviewJob)) {
                LOG.infof("FixComment follow-up: queued REVIEW job %s for PR #%s after fix by job %s",
                        reviewJobId, prId, fixJob.getJobId());
            } else {
                LOG.warnf("FixComment follow-up: job queue full — could not queue REVIEW for PR #%s", prId);
                jobStore.archive(reviewJob); // clean up the record we just added
            }
        } catch (Exception e) {
            // Follow-up review failure must not affect the already-succeeded fix job
            LOG.warnf("FixComment follow-up: failed to schedule REVIEW for PR #%s: %s",
                    prId, e.getMessage());
        }
    }
}
