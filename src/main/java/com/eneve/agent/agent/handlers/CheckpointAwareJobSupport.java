package com.eneve.agent.agent.handlers;

import com.anthropic.models.messages.MessageParam;
import com.eneve.agent.agent.model.JobCheckpoint;
import com.eneve.agent.agent.store.JobCheckpointStore;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.workspace.WorkspaceContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Collections;
import java.util.List;

/**
 * Injectable helper that restores a job's conversation and workspace state from a
 * previously saved checkpoint when the job is a restart of a failed job.
 *
 * <p>Inject this into the 8 checkpoint-aware handlers:
 * {@code RunFixHandler}, {@code FixPrHandler}, {@code FixCommentHandler},
 * {@code HookHandler}, {@code RewriteHandler}, {@code GenerateTestsHandler},
 * {@code GenerateDocsHandler}, {@code SelfAnalysisHandler}.
 */
@ApplicationScoped
public class CheckpointAwareJobSupport {

    private static final Logger LOG = Logger.getLogger(CheckpointAwareJobSupport.class);

    @Inject
    JobCheckpointStore checkpointStore;

    /**
     * If {@code job} is a restart job ({@code restartFromJobId} is set), loads the checkpoint
     * for the original job, restores the workspace to the checkpointed commit, and returns
     * the full message list so the handler can pass it to
     * {@link com.eneve.agent.agent.ClaudeToolUseLoop#resume}.
     *
     * <p>If the job is not a restart, returns an empty list (normal execution).
     *
     * @param job       the new restart job record
     * @param workspace the freshly-cloned workspace for this job
     * @param repoSlug  repository slug used to identify the subdirectory in multi-repo
     *                  workspaces; pass {@code null} or blank for single-repo workspaces
     * @return prior message list from the checkpoint, or an empty list for fresh jobs
     */
    public List<MessageParam> restoreCheckpointIfPresent(JobRecord job,
                                                          WorkspaceContext workspace,
                                                          String repoSlug) {
        String originJobId = job.getRestartFromJobId();
        if (originJobId == null || originJobId.isBlank()) {
            return Collections.emptyList();
        }

        JobCheckpoint checkpoint = checkpointStore.load(originJobId).orElse(null);
        if (checkpoint == null) {
            LOG.warnf("Restart job %s references origin %s but no checkpoint found — starting fresh",
                    job.getJobId(), originJobId);
            return Collections.emptyList();
        }

        String sha = checkpoint.gitCommitSha();
        if (sha != null && !sha.isBlank() && workspace != null) {
            try {
                if (repoSlug != null && !repoSlug.isBlank()) {
                    workspace.checkoutCommit(repoSlug, sha);
                } else {
                    workspace.checkoutCommit(sha);
                }
                LOG.infof("Restored workspace for restart job %s to checkpoint commit %s",
                        job.getJobId(), sha);
            } catch (Exception e) {
                LOG.warnf("Could not checkout checkpoint commit %s for restart job %s (non-fatal): %s",
                        sha, job.getJobId(), e.getMessage());
            }
        }

        LOG.infof("Loaded %d prior messages for restart job %s (origin %s, iteration %d)",
                checkpoint.messages().size(), job.getJobId(), originJobId, checkpoint.iteration());
        return checkpoint.messages();
    }

    /**
     * Convenience overload for single-repo workspaces (no sub-directory).
     */
    public List<MessageParam> restoreCheckpointIfPresent(JobRecord job, WorkspaceContext workspace) {
        return restoreCheckpointIfPresent(job, workspace, null);
    }

    /**
     * Computes the zero-based iteration number to pass as {@code startIteration} to
     * {@link com.eneve.agent.agent.ClaudeToolUseLoop#resume} based on the size of the
     * restored message list.
     *
     * <p>Formula: {@code (messages.size() - 1) / 2}.
     * A fresh conversation starts with 1 message (initial user message), then grows by
     * 2 per completed iteration (assistant response + tool results).
     */
    public static int startIteration(List<MessageParam> priorMessages) {
        if (priorMessages.isEmpty()) return 0;
        return Math.max(0, (priorMessages.size() - 1) / 2);
    }

    /**
     * Computes the remaining iteration cap for a restart job.
     *
     * @param originalCap          the full cap configured for this job type
     * @param startIteration       the iteration at which execution will resume
     * @param additionalIterations extra iterations granted by the user at restart time
     * @return number of iterations the restarted loop may execute
     */
    public static int remainingCap(int originalCap, int startIteration, int additionalIterations) {
        int remaining = originalCap - startIteration + additionalIterations;
        return Math.max(remaining, 1);
    }
}
