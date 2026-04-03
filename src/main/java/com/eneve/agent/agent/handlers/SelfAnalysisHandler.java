package com.eneve.agent.agent.handlers;

import com.eneve.agent.agent.BuildAndLintHelper;
import com.eneve.agent.agent.ClaudeToolUseLoop;
import com.eneve.agent.agent.GitWorkspaceHelper;
import com.eneve.agent.agent.JobHandler;
import com.eneve.agent.agent.JobLifecycleHelper;
import com.eneve.agent.agent.service.PromptTemplateService;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobStatus;
import com.eneve.agent.model.JobType;
import com.eneve.agent.model.RepoCoordinates;
import com.eneve.agent.model.SelfAnalysisRequest;
import com.eneve.agent.scm.GitPlatformRegistry;
import com.eneve.agent.scm.GitPlatformService;
import com.eneve.agent.settings.SettingsService;
import com.eneve.agent.tools.AwsCloudWatchLogsTool;
import com.eneve.agent.workspace.WorkspaceContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles {@link JobType#SELF_ANALYSIS} jobs.
 *
 * <p>Workflow:
 * <ol>
 *   <li>Clone the code-agent repository on the {@code develop} branch</li>
 *   <li>Pre-fetch the last hour of CloudWatch logs (non-fatal if unavailable)</li>
 *   <li>Run the agent loop with workspace + DB read + CloudWatch tools</li>
 *   <li>If the agent produced code changes: validate build, commit, push, create PR</li>
 * </ol>
 */
@ApplicationScoped
public class SelfAnalysisHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(SelfAnalysisHandler.class);
    private static final Pattern BRANCH_SUFFIX_PATTERN =
            Pattern.compile("(?m)^BRANCH_SUFFIX:\\s*([\\w\\-]+)\\s*$");

    @Inject ClaudeToolUseLoop toolUseLoop;
    @Inject BuildAndLintHelper buildAndLintHelper;
    @Inject GitWorkspaceHelper gitHelper;
    @Inject GitPlatformRegistry platformRegistry;
    @Inject JobStore jobStore;
    @Inject JobLifecycleHelper lifecycle;
    @Inject SettingsService settings;
    @Inject PromptTemplateService promptTemplates;
    @Inject AwsCloudWatchLogsTool cloudWatchLogsTool;

    @Override
    public JobType jobType() {
        return JobType.SELF_ANALYSIS;
    }

    @Override
    public void handle(JobRecord job) {
        long jobTimeoutMinutes = Long.parseLong(
                settings.get("self-analysis.job-timeout-minutes", "45"));

        SelfAnalysisRequest request = (SelfAnalysisRequest) job.getPayload();

        job.setStatus(JobStatus.RUNNING);
        jobStore.update(job);

        RepoCoordinates coords;
        try {
            coords = RepoCoordinates.parse(request.repoUrl());
        } catch (IllegalArgumentException e) {
            fail(job, "Invalid repo URL: " + e.getMessage());
            return;
        }

        GitPlatformService platformService = platformRegistry.resolve(request.repoUrl());
        String baseBranch = request.targetBranchOrDefault();

        WorkspaceContext workspace;
        try {
            workspace = WorkspaceContext.create(job.getJobId());
        } catch (Exception e) {
            fail(job, "Failed to create workspace: " + e.getMessage());
            return;
        }

        try (WorkspaceContext ignored = workspace) {

            String authUrl = platformService.buildCloneUrl(
                    coords.organization(), coords.project(), coords.repository());

            LOG.infof("Self-analysis job %s: cloning %s/%s (branch: %s)",
                    job.getJobId(), coords.organization(), coords.repository(), baseBranch);
            try {
                workspace.cloneRepo(authUrl, baseBranch, jobTimeoutMinutes);
            } catch (Exception e) {
                fail(job, "Clone failed: " + e.getMessage());
                return;
            }

            gitHelper.configureGitIfNeeded(workspace);

            // Pre-fetch CloudWatch logs (non-fatal)
            boolean hasCloudWatchLogs = prefetchCloudWatchLogs(workspace, request);

            // Build prompt
            String prompt = buildPrompt(request, hasCloudWatchLogs);
            if (prompt == null || prompt.isBlank()) {
                fail(job, "Failed to resolve self-analysis prompt template");
                return;
            }

            // Run agent loop
            String summary;
            try {
                summary = toolUseLoop.run(prompt, workspace, job.getJobId(), job.getJobType().name());
            } catch (Exception e) {
                fail(job, "Agent loop error: " + e.getMessage());
                return;
            }

            // Validate build (only if agent produced changes)
            if (!buildAndLintHelper.runBuildWithRetry(workspace, job)) {
                fail(job, "Build validation failed after self-analysis fix attempt(s)");
                return;
            }

            // Determine branch name from BRANCH_SUFFIX marker in summary
            String branchName = extractBranchName(summary, request.failedJobId());
            job.setFixBranchName(branchName);
            jobStore.update(job);

            // Create branch and commit
            try {
                workspace.createBranch(branchName);
            } catch (Exception e) {
                fail(job, "Create branch failed: " + e.getMessage());
                return;
            }

            String commitMessage = buildCommitMessage(request, summary, branchName);
            boolean committed;
            try {
                committed = workspace.commitAll(commitMessage);
            } catch (Exception e) {
                fail(job, "Commit failed: " + e.getMessage());
                return;
            }

            if (!committed) {
                job.setStatus(JobStatus.SUCCESS);
                job.setSummary("Self-analysis complete — no changes to commit.\n\n" + summary);
                jobStore.archive(job);
                return;
            }

            // Push
            try {
                workspace.push(branchName, jobTimeoutMinutes);
            } catch (Exception e) {
                fail(job, "Push failed: " + e.getMessage());
                return;
            }

            GitWorkspaceHelper.DiffStats stats = gitHelper.countChanges(workspace);
            String violation = gitHelper.checkGuardrails(stats);
            if (violation != null) {
                fail(job, violation);
                return;
            }
            job.setFilesChanged(stats.filesChanged());
            job.setLinesChanged(stats.linesChanged());

            // Create PR
            String prUrl;
            String prId;
            try {
                boolean hasJira = request.jiraProjectKey() != null
                        && !request.jiraProjectKey().isBlank();
                String title = hasJira
                        ? "[" + request.jiraProjectKey() + "] Self-analysis fix: failed job " + request.failedJobId()
                        : "Self-analysis fix: failed job " + request.failedJobId();
                String description = buildPrDescription(request, summary, hasJira);
                String[] prResult = platformService.createPullRequest(
                        coords.organization(), coords.project(), coords.repository(),
                        branchName, baseBranch, title, description);
                prUrl = prResult[0];
                prId = prResult[1];
            } catch (Exception e) {
                fail(job, "Create PR failed: " + e.getMessage());
                return;
            }

            job.setStatus(JobStatus.AWAITING_APPROVAL);
            job.setSummary(summary);
            job.setPrUrl(prUrl);
            job.setPrId(prId);
            jobStore.update(job);

            lifecycle.auditLog("JOBS", "JOB_AWAITING_APPROVAL", "job", job.getJobId(),
                    Map.of("prUrl", prUrl, "prId", prId));

            LOG.infof("Self-analysis job %s completed. Branch: %s  PR: %s",
                    job.getJobId(), branchName, prUrl);

        } catch (Exception e) {
            fail(job, "Unexpected error: " + e.getMessage());
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Fetches the last hour of CloudWatch logs and writes them to
     * {@code workspace/logs/cloudwatch.txt}. Returns {@code true} on success.
     * All exceptions are caught and logged as warnings — the job continues without
     * the log file if CloudWatch is unavailable or misconfigured.
     */
    private boolean prefetchCloudWatchLogs(WorkspaceContext workspace, SelfAnalysisRequest request) {
        if (isBlank(request.customerId())
                || isBlank(request.environmentName())
                || isBlank(request.logGroupName())) {
            LOG.debugf("CloudWatch pre-fetch skipped: customerId/environmentName/logGroupName not configured");
            return false;
        }
        try {
            Instant now = Instant.now();
            Map<String, Object> input = Map.of(
                    "customerId", request.customerId(),
                    "environmentName", request.environmentName(),
                    "action", "filter_events",
                    "logGroupName", request.logGroupName(),
                    "startTime", now.minus(1, ChronoUnit.HOURS).toString(),
                    "endTime", now.toString(),
                    "limit", 500
            );
            String logs = cloudWatchLogsTool.execute(workspace, input);
            if (logs.startsWith("ERROR:")) {
                LOG.warnf("CloudWatch pre-fetch returned error (non-fatal): %s", logs);
                return false;
            }
            Path logsDir = workspace.getRoot().resolve("logs");
            Files.createDirectories(logsDir);
            Files.writeString(logsDir.resolve("cloudwatch.txt"), logs);
            LOG.infof("CloudWatch logs written to workspace/logs/cloudwatch.txt (%d chars)", logs.length());
            return true;
        } catch (Exception e) {
            LOG.warnf("CloudWatch pre-fetch failed (non-fatal): %s", e.getMessage());
            return false;
        }
    }

    private String buildPrompt(SelfAnalysisRequest request, boolean hasCloudWatchLogs) {
        String jiraKey = request.jiraProjectKey() != null ? request.jiraProjectKey() : "";

        String template = promptTemplates.getTemplate("self-analysis");

        // Evaluate {{#if HAS_CLOUDWATCH_LOGS}} block
        if (hasCloudWatchLogs) {
            template = template
                    .replace("{{#if HAS_CLOUDWATCH_LOGS}}", "")
                    .replace("{{/if}}", "");
        } else {
            template = template.replaceAll(
                    "(?s)\\{\\{#if HAS_CLOUDWATCH_LOGS\\}\\}.*?\\{\\{/if\\}\\}\\s*", "");
        }

        // Evaluate {{#if JIRA_PROJECT_KEY}} block
        if (!jiraKey.isBlank()) {
            template = template
                    .replace("{{#if JIRA_PROJECT_KEY}}", "")
                    .replace("{{/if}}", "");
        } else {
            template = template.replaceAll(
                    "(?s)\\{\\{#if JIRA_PROJECT_KEY\\}\\}.*?\\{\\{/if\\}\\}\\s*", "");
        }

        return promptTemplates.resolveTemplate(template, Map.of(
                "FAILED_JOB_ID", request.failedJobId(),
                "JIRA_PROJECT_KEY", jiraKey,
                "HAS_CLOUDWATCH_LOGS", String.valueOf(hasCloudWatchLogs)
        ));
    }

    private String extractBranchName(String summary, String failedJobId) {
        if (summary != null) {
            Matcher m = BRANCH_SUFFIX_PATTERN.matcher(summary);
            if (m.find()) {
                String suffix = m.group(1).toLowerCase().replaceAll("[^a-z0-9\\-]", "-");
                return "agent/bugfix/" + suffix;
            }
        }
        // Fallback: use the failed job ID (truncated to keep branch name reasonable)
        String safeId = failedJobId.length() > 8 ? failedJobId.substring(0, 8) : failedJobId;
        return "agent/bugfix/" + safeId;
    }

    private String buildCommitMessage(SelfAnalysisRequest request, String summary, String branchName) {
        String scope = request.jiraProjectKey() != null && !request.jiraProjectKey().isBlank()
                ? request.jiraProjectKey()
                : "self-analysis";
        String shortSummary = summary != null && summary.length() > 200
                ? summary.substring(0, 200) + "..."
                : (summary != null ? summary : "");
        return "fix(" + scope + "): self-analysis fix for failed job " + request.failedJobId()
                + "\n\n" + shortSummary;
    }

    private String buildPrDescription(SelfAnalysisRequest request, String summary, boolean hasJira) {
        StringBuilder sb = new StringBuilder();
        sb.append("**Automated PR created by Self-Analysis Agent**\n\n");
        sb.append("This PR was automatically generated after job `")
                .append(request.failedJobId())
                .append("` failed.\n\n");
        if (hasJira) {
            sb.append("Jira project: ").append(request.jiraProjectKey()).append("\n\n");
        }
        if (summary != null && !summary.isBlank()) {
            sb.append("## Analysis Summary\n\n").append(summary);
        }
        return sb.toString();
    }

    private void fail(JobRecord job, String message) {
        LOG.errorf("Self-analysis job %s failed: %s", job.getJobId(), message);
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(message);
        jobStore.archive(job);
        lifecycle.auditLog("JOBS", "JOB_FAILED", "job", job.getJobId(),
                Map.of("errorMessage", message));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
