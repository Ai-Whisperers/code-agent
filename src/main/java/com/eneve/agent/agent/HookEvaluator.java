package com.eneve.agent.agent;

import com.eneve.agent.agent.model.AutomationHook;
import com.eneve.agent.agent.model.HookEvalResult;
import com.eneve.agent.agent.model.QualityReport;
import com.eneve.agent.agent.model.TriggerType;
import com.eneve.agent.agent.store.HookStore;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.agent.store.RepoSettingsStore;
import com.eneve.agent.model.HookJobRequest;
import com.eneve.agent.model.JiraReviewRequest;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobType;
import com.eneve.agent.model.RepoCoordinates;
import com.eneve.agent.model.ServiceDeskTriageRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Evaluates automation hooks against incoming events and submits matching
 * hook jobs to the queue. Checks per-repo overrides before firing.
 */
@ApplicationScoped
public class HookEvaluator {

    private static final Logger LOG = Logger.getLogger(HookEvaluator.class);

    @Inject
    HookStore hookStore;
    @Inject
    RepoSettingsStore repoSettingsStore;
    @Inject JobQueue jobQueue;
    @Inject
    JobStore jobStore;

    /**
     * Evaluates all hooks matching the given PR event and destination branch.
     * Returns a {@link HookEvalResult} containing the submitted job IDs and the executed hook names.
     */
    public HookEvalResult evaluate(String workspace, String repoSlug,
                                   String repoUrl, String prEvent,
                                   String destBranch) {

        List<AutomationHook> hooks = hookStore.findByTrigger("pr_event", prEvent);
        if (hooks.isEmpty()) {
            LOG.debugf("No hooks configured for event '%s'", prEvent);
            return HookEvalResult.empty();
        }

        List<String> submittedJobIds = new ArrayList<>();
        List<String> executedHookNames = new ArrayList<>();

        for (AutomationHook hook : hooks) {
            if (!matchesBranch(hook.branchPattern(), destBranch)) {
                LOG.debugf("Hook '%s' skipped: branch '%s' does not match pattern '%s'",
                        hook.name(), destBranch, hook.branchPattern());
                continue;
            }

            if (repoSettingsStore.isHookDisabled(workspace, repoSlug, hook.name())) {
                LOG.infof("Hook '%s' skipped: disabled for %s/%s via repo settings",
                        hook.name(), workspace, repoSlug);
                continue;
            }

            String jobId = submitHookJob(hook, workspace, repoSlug, repoUrl, destBranch);
            if (jobId != null) {
                submittedJobIds.add(jobId);
                executedHookNames.add(hook.name());
            }
        }

        return new HookEvalResult(submittedJobIds, executedHookNames);
    }

    /**
     * Evaluates all hooks matching the given trigger type with optional context injection.
     * Returns a {@link HookEvalResult} containing the submitted job IDs and the executed hook names.
     */
    public HookEvalResult evaluateByTrigger(String triggerType, String workspace,
                                            String repoSlug, String repoUrl,
                                            Map<String, String> context) {
        List<AutomationHook> hooks = hookStore.findByTriggerType(triggerType);
        if (hooks.isEmpty()) {
            LOG.debugf("No hooks configured for trigger type '%s'", triggerType);
            return HookEvalResult.empty();
        }

        List<String> submittedJobIds = new ArrayList<>();
        List<String> executedHookNames = new ArrayList<>();

        for (AutomationHook hook : hooks) {
            // Check trigger filter matching
            if (!matchesTriggerFilter(hook.triggerFilter(), context)) {
                LOG.debugf("Hook '%s' skipped: context does not match trigger filter", hook.name());
                continue;
            }

            // Check repo-level hook override
            if (repoSettingsStore.isHookDisabled(workspace, repoSlug, hook.name())) {
                LOG.infof("Hook '%s' skipped: disabled for %s/%s via repo settings",
                        hook.name(), workspace, repoSlug);
                continue;
            }

            String jobId = submitHookJobWithContext(hook, workspace, repoSlug, repoUrl, context);
            if (jobId != null) {
                submittedJobIds.add(jobId);
                executedHookNames.add(hook.name());
            }
        }

        return new HookEvalResult(submittedJobIds, executedHookNames);
    }

    private boolean matchesTriggerFilter(Map<String, String> filter, Map<String, String> context) {
        if (filter == null || filter.isEmpty()) {
            return true; // No filter means match everything
        }
        if (context == null) {
            return false; // Filter exists but no context provided
        }

        // All filter entries must match context values
        for (Map.Entry<String, String> entry : filter.entrySet()) {
            String contextValue = context.get(entry.getKey());
            if (contextValue == null) {
                return false;
            }
            String filterValue = entry.getValue();
            // Support comma-separated values: any match is sufficient (OR semantics)
            if (filterValue.contains(",")) {
                boolean anyMatch = false;
                for (String candidate : filterValue.split(",")) {
                    if (contextValue.equalsIgnoreCase(candidate.trim())) {
                        anyMatch = true;
                        break;
                    }
                }
                if (!anyMatch) return false;
            } else {
                if (!contextValue.equalsIgnoreCase(filterValue.trim())) {
                    return false;
                }
            }
        }
        return true;
    }

    private String submitHookJobWithContext(AutomationHook hook, String workspace, 
                                            String repoSlug, String repoUrl, 
                                            Map<String, String> context) {
        // Use hook's repoUrl if provided, otherwise fall back to the event's repoUrl
        String targetRepoUrl = (hook.repoUrl() != null && !hook.repoUrl().isBlank()) 
            ? hook.repoUrl() : repoUrl;
        
        if (targetRepoUrl == null || targetRepoUrl.isBlank()) {
            LOG.warnf("Hook '%s' skipped: no repository URL available", hook.name());
            return null;
        }

        // Parse workspace/slug from repoUrl if hook has custom repoUrl
        String targetWorkspace = workspace;
        String targetRepoSlug = repoSlug;
        if (hook.repoUrl() != null && !hook.repoUrl().isBlank()) {
            try {
                RepoCoordinates coords = RepoCoordinates.parse(hook.repoUrl());
                targetWorkspace = coords.organization();
                targetRepoSlug = coords.repository();
            } catch (IllegalArgumentException e) {
                LOG.warnf("Hook '%s' has invalid repoUrl '%s': %s", hook.name(), hook.repoUrl(), e.getMessage());
                return null;
            }
        }

        String targetBranch = hook.targetBranch() != null && !hook.targetBranch().isBlank()
                ? hook.targetBranch() : "develop";

        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String branchName = "agent/hook-" + hook.name() + "-" + timestamp;

        // Dispatch service desk triage jobs
        String actionType = hook.actionType();
        if ("service_desk_triage".equals(actionType)) {
            String issueKey    = context != null ? context.get("issueKey")    : null;
            String projectKey  = context != null ? context.get("projectKey")  : null;
            String summary     = context != null ? context.get("summary")     : null;
            String description = context != null ? context.get("description") : null;
            String issueType   = context != null ? context.get("issueType")   : null;
            String priority    = context != null ? context.get("priority")    : null;

            if (issueKey == null || issueKey.isBlank()) {
                LOG.warnf("Hook '%s' is a service_desk_triage action but no issueKey in context — skipping",
                        hook.name());
                return null;
            }
            ServiceDeskTriageRequest triageReq = new ServiceDeskTriageRequest(
                    issueKey, projectKey, summary, description, issueType, priority);
            String jobId = UUID.randomUUID().toString();
            JobRecord job = new JobRecord(jobId, triageReq);
            String result = enqueueHookJob(job, hook.name(), "service-desk-triage for " + issueKey);
            if (result != null) {
                LOG.infof("Hook '%s' triggered SERVICE_DESK_TRIAGE job %s for issue %s",
                        hook.name(), result, issueKey);
            }
            return result;
        }

        // Dispatch Jira review jobs when the hook action type is a review action
        if ("review_epic".equals(actionType) || "review_feature".equals(actionType)
                || "review_userstory".equals(actionType)) {
            String issueKey = context != null ? context.get("issue_key") : null;
            if (issueKey == null || issueKey.isBlank()) {
                LOG.warnf("Hook '%s' is a review action but no issue_key found in context — skipping",
                        hook.name());
                return null;
            }
            JobType jobType = switch (actionType) {
                case "review_epic"      -> JobType.REVIEW_EPIC;
                case "review_feature"   -> JobType.REVIEW_FEATURE;
                default                 -> JobType.REVIEW_USERSTORY;
            };
            JiraReviewRequest reviewReq = new JiraReviewRequest(null, issueKey,
                    jobType.name().replace("REVIEW_", ""));
            String jobId = UUID.randomUUID().toString();
            JobRecord job = new JobRecord(jobId, reviewReq, jobType);
            String result = enqueueHookJob(job, hook.name(), jobType + " review for issue " + issueKey);
            if (result != null) {
                LOG.infof("Hook '%s' triggered %s review job %s for issue %s",
                        hook.name(), jobType, result, issueKey);
            }
            return result;
        }

        // Build enhanced prompt with context
        String enhancedPrompt = buildPromptWithContext(hook.prompt(), context);

        HookJobRequest request = new HookJobRequest(
                targetRepoUrl, targetWorkspace, targetRepoSlug,
                branchName, targetBranch,
                enhancedPrompt,
                hook.ruleNames(), hook.extraRules(),
                hook.commitDirect(), hook.name()
        );

        String jobId = UUID.randomUUID().toString();
        JobRecord job = new JobRecord(jobId, request);
        return enqueueHookJob(job, hook.name(),
                targetWorkspace + "/" + targetRepoSlug + " (branch: " + targetBranch + ")");
    }

    private String buildPromptWithContext(String basePrompt, Map<String, String> context) {
        if (context == null || context.isEmpty()) {
            return basePrompt;
        }

        StringBuilder contextSection = new StringBuilder();
        contextSection.append("\n\n## Trigger Context\n\n");
        
        for (Map.Entry<String, String> entry : context.entrySet()) {
            contextSection.append("- **").append(entry.getKey()).append("**: ")
                          .append(entry.getValue()).append("\n");
        }

        return basePrompt + contextSection.toString();
    }

    private String submitHookJob(AutomationHook hook, String workspace, String repoSlug,
                                 String repoUrl, String destBranch) {
        String targetBranch = hook.targetBranch() != null && !hook.targetBranch().isBlank()
                ? hook.targetBranch() : destBranch;

        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String branchName = "agent/hook-" + hook.name() + "-" + timestamp;

        HookJobRequest request = new HookJobRequest(
                repoUrl, workspace, repoSlug,
                branchName, targetBranch,
                hook.prompt(),
                hook.ruleNames(), hook.extraRules(),
                hook.commitDirect(), hook.name()
        );

        String jobId = UUID.randomUUID().toString();
        JobRecord job = new JobRecord(jobId, request);
        return enqueueHookJob(job, hook.name(),
                workspace + "/" + repoSlug + " (branch: " + targetBranch + ")");
    }

    /**
     * Persists a hook job and submits it to the queue.
     * Returns the job ID on success, or {@code null} if the queue is full.
     */
    private String enqueueHookJob(JobRecord job, String hookName, String logContext) {
        jobStore.put(job);
        if (!jobQueue.submit(job)) {
            LOG.warnf("Hook '%s' job rejected: queue full", hookName);
            return null;
        }
        LOG.infof("Hook '%s' triggered job %s for %s", hookName, job.getJobId(), logContext);
        return job.getJobId();
    }

    /**
     * Evaluates all {@code quality.report_generated} hooks against a completed quality report.
     * Hooks are fired when the report's score or coverage falls <em>below</em> the configured
     * thresholds ({@code minScore} and {@code minCoverage} in the hook's trigger filter).
     * A missing threshold means "always match that dimension".
     *
     * <p>The full report metrics are injected into the hook prompt as trigger context.
     */
    public HookEvalResult evaluateQualityReport(QualityReport report, String repoUrl) {
        List<AutomationHook> hooks = hookStore.findByTriggerType(TriggerType.QUALITY_REPORT_GENERATED);
        if (hooks.isEmpty()) {
            LOG.debugf("No quality report hooks configured");
            return HookEvalResult.empty();
        }

        // score() is [0,1] — convert to [0,100] to match the UI filter values
        double scorePercent   = report.score() * 100.0;
        // lineRate() is already in [0,100] scale
        double coveragePct    = report.coverage() != null ? report.coverage().lineRate() : -1.0;

        List<String> submittedJobIds    = new ArrayList<>();
        List<String> executedHookNames  = new ArrayList<>();

        for (AutomationHook hook : hooks) {
            Map<String, String> filter = hook.triggerFilter();

            // Optional repoSlug restriction (comma-separated OR)
            if (filter != null && filter.containsKey("repoSlug")) {
                boolean slugMatch = false;
                for (String s : filter.get("repoSlug").split(",")) {
                    if (report.repoSlug().equalsIgnoreCase(s.trim())) { slugMatch = true; break; }
                }
                if (!slugMatch) {
                    LOG.debugf("Hook '%s' skipped: repoSlug '%s' not in filter '%s'",
                            hook.name(), report.repoSlug(), filter.get("repoSlug"));
                    continue;
                }
            }

            // Numeric threshold: fire only when BELOW the configured value
            if (filter != null && filter.containsKey("minScore")) {
                try {
                    double threshold = Double.parseDouble(filter.get("minScore"));
                    if (scorePercent >= threshold) {
                        LOG.debugf("Hook '%s' skipped: score %.2f >= threshold %.2f",
                                hook.name(), scorePercent, threshold);
                        continue;
                    }
                } catch (NumberFormatException ignored) {
                    LOG.warnf("Hook '%s' has non-numeric minScore filter: %s", hook.name(), filter.get("minScore"));
                }
            }

            if (filter != null && filter.containsKey("minCoverage")) {
                try {
                    double threshold = Double.parseDouble(filter.get("minCoverage"));
                    if (coveragePct < 0 || coveragePct >= threshold) {
                        LOG.debugf("Hook '%s' skipped: coverage %.2f >= threshold %.2f",
                                hook.name(), coveragePct, threshold);
                        continue;
                    }
                } catch (NumberFormatException ignored) {
                    LOG.warnf("Hook '%s' has non-numeric minCoverage filter: %s", hook.name(), filter.get("minCoverage"));
                }
            }

            if (repoSettingsStore.isHookDisabled(report.workspace(), report.repoSlug(), hook.name())) {
                LOG.infof("Hook '%s' skipped: disabled for %s/%s via repo settings",
                        hook.name(), report.workspace(), report.repoSlug());
                continue;
            }

            Map<String, String> context = buildQualityContext(report, scorePercent, coveragePct);
            String jobId = submitHookJobWithContext(hook, report.workspace(), report.repoSlug(), repoUrl, context);
            if (jobId != null) {
                submittedJobIds.add(jobId);
                executedHookNames.add(hook.name());
            }
        }

        LOG.debugf("Quality report hook evaluation for %s/%s: %d hook(s) triggered",
                report.workspace(), report.repoSlug(), submittedJobIds.size());
        return new HookEvalResult(submittedJobIds, executedHookNames);
    }

    private static Map<String, String> buildQualityContext(QualityReport report,
                                                           double scorePercent,
                                                           double coveragePct) {
        Map<String, String> ctx = new java.util.LinkedHashMap<>();
        ctx.put("reportId",  report.reportId());
        ctx.put("workspace", report.workspace());
        ctx.put("repoSlug",  report.repoSlug());
        ctx.put("branch",    report.branch());
        ctx.put("score",     String.format("%.1f / 100", scorePercent));

        if (report.coverage() != null) {
            ctx.put("lineCoverage",    String.format("%.1f%%", report.coverage().lineRate()));
            ctx.put("branchCoverage",  String.format("%.1f%%", report.coverage().branchRate()));
            ctx.put("methodCoverage",  String.format("%.1f%%", report.coverage().methodRate()));
        } else if (coveragePct < 0) {
            ctx.put("lineCoverage", "n/a");
        }

        if (report.linter() != null) {
            ctx.put("linterFindings", String.valueOf(report.linter().totalFindings()));
            ctx.put("linterErrors",   String.valueOf(report.linter().errorCount()));
            ctx.put("linterWarnings", String.valueOf(report.linter().warningCount()));
        }

        if (report.aikido() != null) {
            ctx.put("securityIssues",    String.valueOf(report.aikido().totalIssues()));
            ctx.put("securityCritical",  String.valueOf(report.aikido().criticalCount()));
            ctx.put("securityHigh",      String.valueOf(report.aikido().highCount()));
        }

        if (report.complexity() != null) {
            ctx.put("avgComplexity",          String.format("%.2f", report.complexity().avgComplexity()));
            ctx.put("maxComplexity",          String.valueOf(report.complexity().maxComplexity()));
            ctx.put("methodsAboveThreshold",  String.valueOf(report.complexity().methodsAboveThreshold()));
        }

        if (report.testPresence() != null) {
            ctx.put("testRatio",     String.format("%.1f%%", report.testPresence().testRatio() * 100.0));
            ctx.put("sourceFiles",   String.valueOf(report.testPresence().sourceFiles()));
            ctx.put("testFiles",     String.valueOf(report.testPresence().testFiles()));
        }

        return ctx;
    }

    private static boolean matchesBranch(String pattern, String branch) {
        if (pattern == null || pattern.isBlank()) {
            return true;
        }
        try {
            return Pattern.matches(pattern, branch);
        } catch (PatternSyntaxException e) {
            return pattern.equals(branch);
        }
    }
}
