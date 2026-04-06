package com.eneve.agent.tools;

import java.util.Map;

import com.eneve.agent.agent.store.CommentStore;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.model.RepoCoordinates;
import com.eneve.agent.scm.GitPlatformService;
import com.eneve.agent.workspace.WorkspaceContext;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

/**
 * Tool executor for the comment-chat "resolve_comment" action.
 * Reads jobId and commentId from workspace metadata set by CommentChatService.
 */
@ApplicationScoped
public class ResolveCommentTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(ResolveCommentTool.class);

    @Inject CommentStore commentStore;
    @Inject JobStore jobStore;
    @Inject GitPlatformService gitPlatformService;

    @Override
    public String name() {
        return "resolve_comment";
    }

    @Override public boolean isReadOnly()    { return false; }
    @Override public boolean isDestructive() { return true;  }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String jobId = workspace != null ? workspace.getMetadata("jobId") : null;
        String commentIdStr = workspace != null ? workspace.getMetadata("commentId") : null;

        if (jobId == null || commentIdStr == null) {
            return "ERROR: Missing job context. Cannot resolve comment.";
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

        try {
            RepoCoordinates coords = resolveCoords(job);
            gitPlatformService.resolveComment(
                    coords.organization(), coords.project(), coords.repository(),
                    job.getPrId(), commentId);
        } catch (Exception e) {
            LOG.warnf("SCM resolveComment failed for comment %d: %s", commentId, e.getMessage());
        }

        commentStore.markResolved(commentId, "Review Agent (chat)");
        LOG.infof("Comment %d resolved via comment chat (jobId=%s)", commentId, jobId);

        return "resolved";
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
