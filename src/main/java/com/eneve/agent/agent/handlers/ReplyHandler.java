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
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class ReplyHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(ReplyHandler.class);

    @Inject ClaudeToolUseLoop toolUseLoop;
    @Inject AgentPromptBuilder promptBuilder;
    @Inject GitPlatformRegistry platformRegistry;
    @Inject CommentStore commentStore;
    @Inject LearningExtractor learningExtractor;
    @Inject JobStore jobStore;
    @Inject JobLifecycleHelper lifecycle;
    @Inject SettingsService settings;

    @Override
    public JobType jobType() {
        return JobType.REPLY;
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
            lifecycle.failReply(job, "Original comment context not found for comment #" + request.parentCommentId());
            return;
        }
        CommentContext ctx = ctxOpt.get();

        RepoCoordinates coords;
        try {
            coords = RepoCoordinates.parse(request.repoUrl());
        } catch (IllegalArgumentException e) {
            lifecycle.failReply(job, "Invalid repo URL: " + e.getMessage());
            return;
        }

        final GitPlatformService platformService = platformRegistry.resolve(request.repoUrl());

        try (WorkspaceContext workspace = WorkspaceContext.create(job.getJobId())) {

            Map<String, String> prInfo;
            try {
                prInfo = platformService.getPullRequestInfo(
                        coords.organization(), coords.project(), coords.repository(), request.prId());
            } catch (Exception e) {
                lifecycle.failReply(job, "Failed to fetch PR info: " + e.getMessage());
                return;
            }

            String sourceBranch = prInfo.get("sourceBranch");
            String authUrl = platformService.buildCloneUrl(coords.organization(), coords.project(), coords.repository());
            LOG.infof("Reply: cloning %s/%s branch %s for comment thread on PR #%s",
                    coords.organization(), coords.repository(), sourceBranch, request.prId());
            try {
                workspace.cloneRepo(authUrl, sourceBranch, jobTimeoutMinutes);
            } catch (Exception e) {
                lifecycle.failReply(job, "Clone failed: " + e.getMessage());
                return;
            }

            java.util.List<ThreadComment> thread;
            try {
                thread = platformService.getCommentThread(
                        coords.organization(), coords.project(), coords.repository(),
                        request.prId(), request.parentCommentId());
            } catch (Exception e) {
                LOG.warnf("Failed to fetch comment thread (non-fatal): %s", e.getMessage());
                thread = Collections.emptyList();
            }

            String systemPrompt = promptBuilder.buildReplyPrompt(ctx, thread, request.humanMessage());

            String replyText;
            try {
                replyText = toolUseLoop.run(systemPrompt, workspace,
                        ToolDefinitions.readOnly(),
                        "A developer has replied to your review comment. "
                                + "Please read the conversation and respond helpfully.",
                        job.getJobId(), job.getJobType().name());
            } catch (Exception e) {
                lifecycle.failReply(job, "Agent reply loop error: " + e.getMessage());
                return;
            }

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
                lifecycle.failReply(job, "Failed to post reply: " + e.getMessage());
                return;
            }

            try {
                String developerUsername = thread.stream()
                        .filter(tc -> !tc.isAgent())
                        .reduce((first, second) -> second)
                        .map(ThreadComment::author)
                        .orElse(null);
                learningExtractor.extractAndStore(thread, ctx,
                        coords.organization(), coords.repository(), developerUsername);
            } catch (Exception e) {
                LOG.warnf("Learning extraction failed (non-fatal): %s", e.getMessage());
            }

            job.setStatus(JobStatus.SUCCESS);
            job.setSummary("Replied to comment thread on PR #" + request.prId());
            jobStore.archive(job);
            LOG.infof("Reply job %s completed for comment #%d on PR #%s",
                    job.getJobId(), request.parentCommentId(), request.prId());

        } catch (Exception e) {
            lifecycle.failReply(job, "Unexpected error: " + e.getMessage());
        }
    }
}
