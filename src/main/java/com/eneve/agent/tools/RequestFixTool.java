package com.eneve.agent.tools;

import java.util.Map;
import java.util.UUID;

import com.eneve.agent.agent.JobQueue;
import com.eneve.agent.agent.store.CommentStore;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobType;
import com.eneve.agent.model.ReplyCommentRequest;
import com.eneve.agent.model.RepoCoordinates;
import com.eneve.agent.workspace.WorkspaceContext;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

/**
 * Tool executor for the comment-chat "request_fix" action.
 * Queues a FIX_COMMENT job for the current review comment.
 */
@ApplicationScoped
public class RequestFixTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(RequestFixTool.class);

    @Inject JobStore jobStore;
    @Inject JobQueue jobQueue;
    @Inject CommentStore commentStore;

    @Override
    public String name() {
        return "request_fix";
    }

    @Override public boolean isReadOnly()    { return false; }
    @Override public boolean isDestructive() { return true;  }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String jobId = workspace != null ? workspace.getMetadata("jobId") : null;
        String commentIdStr = workspace != null ? workspace.getMetadata("commentId") : null;

        if (jobId == null || commentIdStr == null) {
            return "ERROR: Missing job context. Cannot start fix.";
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

        if (job.getPrId() == null || job.getPrId().isBlank()) {
            return "ERROR: Job has no associated pull request.";
        }

        String repoUrl;
        String filePath = "";
        int line = 0;
        try {
            RepoCoordinates coords = resolveCoords(job);
            repoUrl = buildRepoUrl(coords, job);
        } catch (Exception e) {
            return "ERROR: Cannot resolve repository: " + e.getMessage();
        }

        var ctx = commentStore.find(commentId);
        if (ctx.isPresent()) {
            filePath = ctx.get().filePath() != null ? ctx.get().filePath() : "";
            line = ctx.get().line();
        }

        ReplyCommentRequest replyRequest = new ReplyCommentRequest(
                repoUrl, job.getPrId(), commentId, "Please fix this issue.", filePath, line);

        String fixCommentJobId = UUID.randomUUID().toString();
        JobRecord fixCommentJob = new JobRecord(fixCommentJobId, replyRequest, JobType.FIX_COMMENT);
        jobStore.put(fixCommentJob);

        if (!jobQueue.submit(fixCommentJob)) {
            return "ERROR: Job queue is full, please try again later.";
        }

        LOG.infof("FIX_COMMENT job %s queued via comment chat (jobId=%s, commentId=%d)",
                fixCommentJobId, jobId, commentId);

        return "fix_started:" + fixCommentJobId;
    }

    private static RepoCoordinates resolveCoords(JobRecord job) {
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

    private static String buildRepoUrl(RepoCoordinates coords, JobRecord job) {
        if (job.getRequest() != null && job.getRequest().repoUrl() != null)
            return job.getRequest().repoUrl();
        if (job.getReviewRequest() != null && job.getReviewRequest().repoUrl() != null)
            return job.getReviewRequest().repoUrl();
        return "https://bitbucket.org/" + coords.organization() + "/" + coords.repository();
    }
}
