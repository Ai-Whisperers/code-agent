package com.eneve.agent.agent;

import com.eneve.agent.agent.service.PromptTemplateService;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.eneve.agent.settings.SettingsService;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.eneve.agent.linter.LinterFinding;
import com.eneve.agent.linter.LinterResult;
import com.eneve.agent.linter.LinterService;
import com.eneve.agent.linter.StaticAnalysisDiffReport;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.workspace.WorkspaceContext;

/**
 * Shared helpers for build validation, linter fix loops, self-review,
 * and static-analysis diff reporting.
 */
@ApplicationScoped
public class BuildAndLintHelper {

    private static final Logger LOG = Logger.getLogger(BuildAndLintHelper.class);

    @Inject BuildValidator buildValidator;
    @Inject LinterService linterService;
    @Inject ClaudeToolUseLoop toolUseLoop;
    @Inject PromptTemplateService promptTemplates;
    @Inject SettingsService settings;

    /**
     * Outcome of the linter fix loop.
     * When {@code canContinue} is {@code false}, {@code failureMessage} describes why
     * and the caller is responsible for calling the appropriate {@code fail*()} method.
     */
    public record LinterFixResult(boolean canContinue, List<LinterResult> finalResults, String failureMessage) {
        public LinterFixResult(boolean canContinue, List<LinterResult> finalResults) {
            this(canContinue, finalResults, null);
        }
    }

    /**
     * Runs build validation with automatic retries on failure.
     * On each failure the build error output is fed back to the agent so it can fix its own mistakes.
     *
     * @return {@code true} if validation eventually passes,
     *         {@code false} if all attempts are exhausted (caller must call fail())
     */
    public boolean runBuildWithRetry(WorkspaceContext workspace, JobRecord job) {
        String jobId = job.getJobId();
        String jobType = job.getJobType().name();
        int attempts = 0;
        while (true) {
            try {
                buildValidator.validate(workspace);
                if (attempts > 0) {
                    LOG.infof("Build validation passed on retry attempt %d", attempts);
                }
                return true;
            } catch (Exception e) {
                attempts++;
                String buildError = e.getMessage() != null ? e.getMessage() : "Unknown build error";
                if (attempts > maxBuildRetries()) {
                    LOG.warnf("Build validation failed after %d attempt(s), giving up: %s",
                            attempts, buildError.length() > 200 ? buildError.substring(0, 200) + "..." : buildError);
                    return false;
                }

                LOG.infof("Build validation failed (attempt %d/%d), feeding error back to agent: %s",
                        attempts, maxBuildRetries(),
                        buildError.length() > 200 ? buildError.substring(0, 200) + "..." : buildError);

                String jiraKey = job.getRequest() != null && job.getRequest().jiraKey() != null
                        ? job.getRequest().jiraKey() : "(unknown)";
                String branch = job.getFixBranchName() != null
                        ? job.getFixBranchName() : "(unknown)";
                String diff = captureAgentDiff(workspace);
                String retryPrompt = promptTemplates.resolve("build-retry", Map.of(
                        "BUILD_OUTPUT", buildError,
                        "ATTEMPT", String.valueOf(attempts),
                        "MAX_ATTEMPTS", String.valueOf(maxBuildRetries()),
                        "JIRA_KEY", jiraKey,
                        "BRANCH", branch,
                        "AGENT_DIFF", diff));

                try {
                    toolUseLoop.run(retryPrompt, workspace, 30, jobId, jobType,
                            job.getParentJobId(), job.getDepth());
                } catch (Exception agentEx) {
                    LOG.warnf("Agent fix loop error during build retry (attempt %d): %s", attempts, agentEx.getMessage());
                    return false;
                }
            }
        }
    }

    /**
     * Runs the post-change linter delta scan and fix loop.
     * When {@code canContinue} is {@code false} in the result, the failure message is set
     * and the caller is responsible for invoking the appropriate fail method.
     */
    public LinterFixResult runLinterFixLoop(WorkspaceContext workspace, List<LinterResult> baseline, JobRecord job) {
        if (!linterService.getConfig().isEnabled()) {
            return new LinterFixResult(true, Collections.emptyList());
        }
        int maxLintFixes = linterService.getConfig().getMaxFixIterations();
        List<LinterResult> lastResults = Collections.emptyList();
        for (int lintIter = 0; lintIter < maxLintFixes; lintIter++) {
            LOG.infof("Linter delta scan iteration %d/%d", lintIter + 1, maxLintFixes);
            List<LinterResult> current = linterService.runAll(workspace.getRoot());
            lastResults = current;
            List<LinterFinding> newIssues = linterService.findNewIssues(baseline, current);

            if (newIssues.isEmpty()) {
                LOG.info("No new linter issues introduced — linter gate passed");
                return new LinterFixResult(true, lastResults);
            }

            LOG.infof("Found %d new linter issues, asking Claude to fix (iteration %d/%d)",
                    newIssues.size(), lintIter + 1, maxLintFixes);

            if (lintIter < maxLintFixes - 1) {
                String fixPrompt = linterService.buildFixPrompt(newIssues);
                try {
                    toolUseLoop.run(fixPrompt, workspace, job.getJobId(), job.getJobType().name(),
                            job.getParentJobId(), job.getDepth());
                } catch (Exception e) {
                    LOG.warnf("Linter fix loop error (non-fatal): %s", e.getMessage());
                    return new LinterFixResult(true, lastResults);
                }
            } else {
                String issueList = linterService.buildFixPrompt(newIssues);
                LOG.warnf("Linter fix iterations exhausted with %d remaining issues", newIssues.size());
                if (linterService.getConfig().isFailOnNewIssues()) {
                    return new LinterFixResult(false, lastResults,
                            "New linter/SAST issues introduced and could not be auto-fixed:\n" + issueList);
                }
                LOG.warn("fail-on-new-issues is false — continuing despite new linter issues");
            }
        }
        return new LinterFixResult(true, lastResults);
    }

    /**
     * Runs a focused self-review pass: the agent reads its own diff against the original task,
     * checks for completeness, debug artefacts, unused imports, and edge cases, then fixes
     * anything it finds. This step is entirely non-fatal.
     */
    public void runSelfReview(WorkspaceContext workspace, JobRecord job, String originalTask) {
        if (!selfReviewEnabled()) {
            return;
        }

        String diff;
        try {
            diff = workspace.getWorkingDiff();
        } catch (Exception e) {
            LOG.warnf("Self-review: failed to get working diff (non-fatal): %s", e.getMessage());
            return;
        }

        if (diff == null || diff.isBlank()) {
            LOG.info("Self-review: no changes detected, skipping");
            return;
        }

        if (diff.length() > selfReviewMaxDiffChars()) {
            diff = diff.substring(0, selfReviewMaxDiffChars())
                    + "\n\n... [diff truncated at " + selfReviewMaxDiffChars() + " chars] ...";
        }

        String filesChanged = diff.lines()
                .filter(l -> l.startsWith("diff --git "))
                .map(l -> {
                    String[] parts = l.split(" ");
                    return parts.length >= 4 ? parts[3].replaceFirst("^b/", "") : l;
                })
                .collect(Collectors.joining(", "));

        String reviewPrompt = promptTemplates.resolve("self-review", Map.of(
                "ORIGINAL_TASK", originalTask != null ? originalTask : "(not available)",
                "DIFF", diff,
                "FILES_CHANGED", filesChanged));

        LOG.infof("Running self-review pass over %d changed file(s)...",
                diff.lines().filter(l -> l.startsWith("diff --git ")).count());
        try {
            toolUseLoop.run(reviewPrompt, workspace, selfReviewMaxIterations(),
                    job.getJobId(), job.getJobType().name(),
                    job.getParentJobId(), job.getDepth());
        } catch (Exception e) {
            LOG.warnf("Self-review loop error (non-fatal): %s", e.getMessage());
        }
        LOG.info("Self-review pass complete");
    }

    public List<LinterResult> runBaselineLinterScan(WorkspaceContext workspace) {
        if (!linterService.getConfig().isEnabled()) {
            return Collections.emptyList();
        }
        LOG.info("Running baseline linter scan...");
        List<LinterResult> baseline = linterService.runAll(workspace.getRoot());
        LOG.infof("Baseline linter scan complete: %s", linterService.formatSummary(baseline));
        return baseline;
    }

    /**
     * Builds a {@link StaticAnalysisDiffReport} from the baseline and final linter scans.
     * Returns {@code null} if linting is disabled or both scan lists are empty.
     */
    public StaticAnalysisDiffReport buildLinterDiffReport(
            WorkspaceContext workspace,
            List<LinterResult> baseline,
            List<LinterResult> finalResults) {

        if (!linterService.getConfig().isEnabled()) {
            return null;
        }
        if (baseline.isEmpty() && finalResults.isEmpty()) {
            return null;
        }

        Set<String> changedFiles = Collections.emptySet();
        try {
            changedFiles = workspace.getChangedFileNames();
        } catch (Exception e) {
            LOG.warnf("Could not retrieve changed file names for linter diff scoping: %s", e.getMessage());
        }

        StaticAnalysisDiffReport report = linterService.buildDiffReport(baseline, finalResults, changedFiles);
        LOG.infof("Static analysis diff: verdict=%s, newIssues=%d, resolvedIssues=%d",
                report.verdict(), report.newIssues().size(), report.resolvedIssues().size());
        return report;
    }

    private String captureAgentDiff(WorkspaceContext workspace) {
        try {
            String diff = workspace.getWorkingDiff();
            if (diff == null || diff.isBlank()) return "(no uncommitted changes detected)";
            final int MAX_DIFF = 4000;
            if (diff.length() > MAX_DIFF) {
                return diff.substring(0, MAX_DIFF) + "\n... [diff truncated at " + MAX_DIFF + " chars]";
            }
            return diff;
        } catch (Exception e) {
            LOG.warnf("Could not capture agent diff for build-retry prompt (non-fatal): %s", e.getMessage());
            return "(diff unavailable)";
        }
    }

    private int maxBuildRetries() {
        return Integer.parseInt(settings.get("run-fix.max-build-retries", "2"));
    }

    private boolean selfReviewEnabled() {
        return Boolean.parseBoolean(settings.get("run-fix.self-review.enabled", "true"));
    }

    private int selfReviewMaxIterations() {
        return Integer.parseInt(settings.get("run-fix.self-review.max-iterations", "15"));
    }

    private int selfReviewMaxDiffChars() {
        return Integer.parseInt(settings.get("run-fix.self-review.max-diff-chars", "30000"));
    }

    public static String buildLinterDiffSummaryLine(StaticAnalysisDiffReport report) {
        int newCount = report.newIssues().size();
        int resolvedCount = report.resolvedIssues().size();
        return switch (report.verdict()) {
            case PASS     -> "**Static Analysis:** PASS — no new issues introduced.";
            case IMPROVED -> "**Static Analysis:** IMPROVED — " + resolvedCount
                    + " issue(s) resolved, " + newCount + " new issue(s).";
            case DEGRADED -> "**Static Analysis:** DEGRADED — " + newCount
                    + " new issue(s) introduced, " + resolvedCount + " resolved.";
        };
    }
}
