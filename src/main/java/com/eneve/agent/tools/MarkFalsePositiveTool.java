package com.eneve.agent.tools;

import java.util.Map;

import com.eneve.agent.agent.model.CommentFeedbackEntry;
import com.eneve.agent.agent.store.CommentFeedbackStore;
import com.eneve.agent.agent.store.CommentStore;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.model.RepoCoordinates;
import com.eneve.agent.scm.GitPlatformService;
import com.eneve.agent.workspace.WorkspaceContext;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

/**
 * Tool executor for the comment-chat "mark_false_positive" action.
 * Records false-positive feedback, resolves the comment, and posts a reply to the PR thread.
 */
@ApplicationScoped
public class MarkFalsePositiveTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(MarkFalsePositiveTool.class);

    @Inject CommentStore commentStore;
    @Inject CommentFeedbackStore commentFeedbackStore;
    @Inject JobStore jobStore;
    @Inject GitPlatformService gitPlatformService;

    @Override
    public String name() {
        return "mark_false_positive";
    }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String jobId = workspace != null ? workspace.getMetadata("jobId") : null;
        String commentIdStr = workspace != null ? workspace.getMetadata("commentId") : null;

        if (jobId == null || commentIdStr == null) {
            return "ERROR: Missing job context. Cannot mark false positive.";
        }

        long commentId;
        try {
            commentId = Long.parseLong(commentIdStr);
        } catch (NumberFormatException e) {
            return "ERROR: Invalid commentId in context: " + commentIdStr;
        }

        var jobOpt = jobStore.get(jobId);
        if (jobOpt.isEmpty()) {
            return "ERROR: Job not found: " + jobId;
        }
        var job = jobOpt.get();

        var ctx = commentStore.find(commentId);
        String category = ctx.map(c -> c.category()).orElse(null);
        String findingText = ctx.map(c -> c.findingText()).orElse(null);
        String prId = job.getPrId() != null ? job.getPrId() : ctx.map(c -> c.prId()).orElse("");
        String wsOrg;
        String repoSlug;
        try {
            RepoCoordinates coords = resolveCoords(job);
            wsOrg = coords.organization();
            repoSlug = coords.repository();
        } catch (Exception e) {
            wsOrg = ctx.map(c -> c.organization()).orElse("");
            repoSlug = ctx.map(c -> c.repository()).orElse("");
        }

        CommentFeedbackEntry feedback = CommentFeedbackEntry.falsePositive(
                commentId, prId, wsOrg, repoSlug, category, findingText, "Review Agent (chat)");
        commentFeedbackStore.save(feedback);

        commentStore.markResolved(commentId, "Review Agent (chat)");

        try {
            RepoCoordinates coords = resolveCoords(job);
            gitPlatformService.resolveComment(coords.organization(), coords.project(), coords.repository(),
                    job.getPrId(), commentId);
            gitPlatformService.replyToComment(coords.organization(), coords.project(), coords.repository(),
                    job.getPrId(), commentId,
                    "This finding has been marked as a false positive and will be suppressed in future reviews.");
        } catch (Exception e) {
            LOG.warnf("SCM false-positive actions failed for comment %d: %s", commentId, e.getMessage());
        }

        LOG.infof("Comment %d marked as false positive via comment chat (jobId=%s)", commentId, jobId);
        return "false_positive_marked";
    }

    private static RepoCoordinates resolveCoords(com.eneve.agent.model.JobRecord job) {
        String repoUrl = null;
        if (job.getRequest() != null && job.getRequest().repoUrl() != null)
            repoUrl = job.getRequest().repoUrl();
        else if (job.getReviewRequest() != null && job.getReviewRequest().repoUrl() != null)
            repoUrl = job.getReviewRequest().repoUrl();
        else if (job.getFixPrRequest() != null && job.getFixPrRequest().repoUrl() != null)
            repoUrl = job.getFixPrRequest().repoUrl();
        else if (job.getHookRequest() != null && job.getHookRequest().repoUrl() != null)
            repoUrl = job.getHookRequest().repoUrl();

        if (repoUrl != null && !repoUrl.isBlank()) {
            return RepoCoordinates.parse(repoUrl);
        }
        if (job.getPrUrl() != null && !job.getPrUrl().isBlank()) {
            String prUrl = job.getPrUrl()
                    .replaceAll("/pull-requests/.*$", "")
                    .replaceAll("/pulls/.*$", "")
                    .replaceAll("/-/merge_requests/.*$", "");
            return RepoCoordinates.parse(prUrl);
        }
        throw new IllegalStateException("No repository URL available on job " + job.getJobId());
    }
}
