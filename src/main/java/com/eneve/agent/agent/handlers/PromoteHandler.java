package com.eneve.agent.agent.handlers;

import com.eneve.agent.agent.JobHandler;
import com.eneve.agent.agent.JobLifecycleHelper;
import com.eneve.agent.agent.JobQueue;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.agent.store.RepoSettingsStore;
import com.eneve.agent.jira.JiraService;
import com.eneve.agent.model.*;
import com.eneve.agent.notifications.TeamsNotifier;
import com.eneve.agent.scm.GitPlatformRegistry;
import com.eneve.agent.scm.GitPlatformService;
import com.eneve.agent.workspace.WorkspaceContext;
import com.eneve.agent.agent.GitWorkspaceHelper;
import com.eneve.agent.Soc2Policy;
import com.eneve.agent.settings.SettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Handles {@link JobType#PROMOTE} jobs.
 *
 * <p>The promotion flow:
 * <ol>
 *   <li>Fetch the commits from the original fix PR via the SCM API.</li>
 *   <li>Clone the repository, check out {@code main} (or the configured production branch),
 *       create a new {@code promote/{jiraKey}} branch.</li>
 *   <li>Cherry-pick each fix commit onto the promotion branch.</li>
 *   <li>Push and raise a PR: {@code promote/{jiraKey}} → {@code main}.</li>
 *   <li>Add JIRA comment, send Teams notification, and optionally auto-submit a REVIEW job.</li>
 * </ol>
 */
@ApplicationScoped
public class PromoteHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(PromoteHandler.class);

    @Inject GitPlatformRegistry platformRegistry;
    @Inject JobStore jobStore;
    @Inject JobQueue jobQueue;
    @Inject JobLifecycleHelper lifecycle;
    @Inject JiraService jiraService;
    @Inject TeamsNotifier teamsNotifier;
    @Inject RepoSettingsStore repoSettingsStore;
    @Inject GitWorkspaceHelper gitHelper;
    @Inject SettingsService settings;
    @Inject Soc2Policy soc2Policy;

    @Override
    public JobType jobType() {
        return JobType.PROMOTE;
    }

    @Override
    public void handle(JobRecord job) {
        long timeoutMinutes = Long.parseLong(settings.get("run-fix.job-timeout-minutes", "30"));
        String productionBranch = soc2Policy.productionBranch();

        PromoteRequest request = job.getPromoteRequest();
        if (request == null) {
            failPromote(job, "PromoteRequest is null — cannot proceed");
            return;
        }

        job.setStatus(JobStatus.RUNNING);
        jobStore.update(job);

        RepoCoordinates coords;
        try {
            coords = RepoCoordinates.parse(request.repoUrl());
        } catch (IllegalArgumentException e) {
            failPromote(job, "Invalid repo URL: " + e.getMessage());
            return;
        }

        final GitPlatformService platformService = platformRegistry.resolve(request.repoUrl());

        // ── 1. Fetch commit SHAs from the original PR ─────────────────────
        List<String> commitShas;
        try {
            commitShas = fetchCommitShas(coords, request.originalPrId(), request.fixBranchName(),
                    request.repoUrl(), timeoutMinutes, platformService);
            if (commitShas.isEmpty()) {
                failPromote(job, "No commits found for fix branch " + request.fixBranchName()
                        + " (prId=" + request.originalPrId() + ")");
                return;
            }
        } catch (Exception e) {
            failPromote(job, "Failed to fetch commits: " + e.getMessage());
            return;
        }

        LOG.infof("Promote job %s: cherry-picking %d commit(s) for %s", job.getJobId(),
                commitShas.size(), request.jiraKey());

        // ── 2. Clone → create promote branch → cherry-pick ───────────────
        WorkspaceContext workspace;
        try {
            workspace = WorkspaceContext.create(job.getJobId());
        } catch (Exception e) {
            failPromote(job, "Failed to create workspace: " + e.getMessage());
            return;
        }
        try (WorkspaceContext ignored = workspace) {
            String authUrl = platformService.buildCloneUrl(
                    coords.organization(), coords.project(), coords.repository());

            // Clone on production branch (e.g. main)
            try {
                workspace.cloneAndCreateBranch(authUrl, productionBranch,
                        request.promoteBranchName(), timeoutMinutes);
            } catch (Exception e) {
                failPromote(job, "Clone/branch creation failed: " + e.getMessage());
                return;
            }

            gitHelper.configureGitIfNeeded(workspace);

            // Cherry-pick the fix commits
            try {
                workspace.cherryPick(commitShas, timeoutMinutes);
            } catch (Exception e) {
                failPromote(job, "Cherry-pick failed: " + e.getMessage()
                        + ". Resolve conflicts manually and re-run promotion.");
                return;
            }

            // Push promotion branch
            try {
                workspace.push(request.promoteBranchName(), timeoutMinutes);
            } catch (Exception e) {
                failPromote(job, "Push failed: " + e.getMessage());
                return;
            }

            // ── 3. Raise PR: promote/{jiraKey} → main ────────────────────
            String prUrl;
            String prId;
            try {
                String title = request.jiraKey() + ": Promote security fix to " + productionBranch;
                String body = "**SOC2 Security Fix Promotion (cherry-pick)**\n\n"
                        + "JIRA: " + request.jiraKey() + "\n"
                        + "Source branch: `" + request.fixBranchName() + "`\n"
                        + "Cherry-picked commits: " + commitShas.size() + "\n\n"
                        + "This PR promotes the exact fix commits to `" + productionBranch + "` "
                        + "without including unrelated changes from `develop`.";
                String[] prResult = platformService.createPullRequest(
                        coords.organization(), coords.project(), coords.repository(),
                        request.promoteBranchName(), productionBranch,
                        title, body);
                prUrl = prResult[0];
                prId = prResult[1];
            } catch (Exception e) {
                failPromote(job, "Create promotion PR failed: " + e.getMessage());
                return;
            }

            job.setStatus(JobStatus.AWAITING_APPROVAL);
            job.setSummary("Promotion PR raised: " + request.promoteBranchName() + " → " + productionBranch);
            job.setPrUrl(prUrl);
            job.setPrId(prId);
            jobStore.update(job);
            lifecycle.auditLog("JOBS", "JOB_AWAITING_APPROVAL", "job", job.getJobId(),
                    java.util.Map.of("prUrl", prUrl, "prId", prId,
                                     "promotionBranch", request.promoteBranchName()));

            // ── 4. JIRA comment ───────────────────────────────────────────
            lifecycle.safeJira(() -> jiraService.addComment(request.jiraKey(),
                    "Promotion PR raised for " + productionBranch + ": " + prUrl
                    + ". Cherry-picks " + commitShas.size() + " fix commit(s). "
                    + "Awaiting final review and approval."));

            // ── 5. Teams notification ─────────────────────────────────────
            teamsNotifier.sendNotification(new RunResult(
                    job.getJobId(), "PROMOTE", "AWAITING_APPROVAL",
                    request.jiraKey(), request.repoUrl(), request.promoteBranchName(),
                    prUrl,
                    "Promotion PR ready: " + request.promoteBranchName() + " → " + productionBranch
                    + "\nJIRA: " + request.jiraKey(),
                    null, 0, 0));

            // ── 6. Conditionally auto-submit REVIEW job ───────────────────
            maybeSubmitReviewJob(job, request, coords, prId, productionBranch);

            LOG.infof("Promote job %s completed. PR: %s", job.getJobId(), prUrl);

        } catch (Exception e) {
            failPromote(job, "Unexpected error: " + e.getMessage());
        }
    }

    private List<String> fetchCommitShas(RepoCoordinates coords, String prId, String fixBranchName,
                                          String repoUrl, long timeoutMinutes,
                                          GitPlatformService platformService) throws Exception {
        // Primary: use SCM API to get commits from the original PR
        if (prId != null && !prId.isBlank()) {
            List<PrCommitEntry> commits = platformService.getPrCommits(
                    coords.organization(), coords.project(), coords.repository(), prId);
            if (!commits.isEmpty()) {
                return commits.stream().map(PrCommitEntry::sha).collect(Collectors.toList());
            }
        }
        // Fallback: if no prId or empty result, we can't determine commits safely
        throw new IllegalStateException("Could not retrieve commits for PR " + prId
                + " on branch " + fixBranchName);
    }

    private void maybeSubmitReviewJob(JobRecord promoteJob, PromoteRequest request,
                                       RepoCoordinates coords, String prId,
                                       String productionBranch) {
        try {
            boolean reviewEnabled = repoSettingsStore
                    .find(coords.organization(), coords.repository())
                    .map(rs -> rs.reviewEnabled())
                    .orElse(true);

            if (reviewEnabled) {
                LOG.infof("Promote job %s: reviewEnabled=true — skipping auto-REVIEW (hook handles it)",
                        promoteJob.getJobId());
                return;
            }

            ReviewPrRequest reviewRequest = new ReviewPrRequest(
                    request.repoUrl(), prId, productionBranch,
                    request.jiraKey(), null, null, null, null, null, null);

            String reviewJobId = UUID.randomUUID().toString();
            JobRecord reviewJob = new JobRecord(reviewJobId, reviewRequest);
            reviewJob.setAikidoIssueId(request.aikidoIssueId());
            jobStore.put(reviewJob);

            if (jobQueue.submit(reviewJob)) {
                LOG.infof("Promote job %s: auto-submitted REVIEW job %s for promotion PR %s",
                        promoteJob.getJobId(), reviewJobId, prId);
                lifecycle.auditLog("JOBS", "AUTO_REVIEW_SUBMITTED", "job", promoteJob.getJobId(),
                        java.util.Map.of("reviewJobId", reviewJobId, "prId", prId));
            } else {
                LOG.warnf("Promote job %s: could not submit auto-REVIEW — queue full", promoteJob.getJobId());
            }
        } catch (Exception e) {
            LOG.warnf("Promote job %s: failed to submit auto-REVIEW (non-fatal): %s",
                    promoteJob.getJobId(), e.getMessage());
        }
    }

    private void failPromote(JobRecord job, String message) {
        LOG.errorf("Promote job %s failed: %s", job.getJobId(), message);
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(message);
        jobStore.archive(job);
        lifecycle.auditLog("JOBS", "JOB_FAILED", "job", job.getJobId(),
                java.util.Map.of("errorMessage", message));

        PromoteRequest request = job.getPromoteRequest();
        if (request != null && request.jiraKey() != null) {
            lifecycle.safeJira(() -> jiraService.addComment(request.jiraKey(),
                    "Promotion job " + job.getJobId() + " failed: " + message));
        }

        teamsNotifier.sendNotification(new RunResult(
                job.getJobId(), "PROMOTE", "FAILED",
                request != null ? request.jiraKey() : null,
                request != null ? request.repoUrl() : null,
                null, null, null, message, 0, 0));
    }
}
