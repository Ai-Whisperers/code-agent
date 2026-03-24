package com.eneve.agent.agent;

import com.eneve.agent.agent.model.AutomationHook;
import com.eneve.agent.agent.model.HookEvalResult;
import com.eneve.agent.agent.store.HookStore;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.agent.store.RepoSettingsStore;
import com.eneve.agent.model.HookJobRequest;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.RepoCoordinates;
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
        jobStore.put(job);

        if (!jobQueue.submit(job)) {
            LOG.warnf("Hook '%s' job rejected: queue full", hook.name());
            return null;
        }

        LOG.infof("Hook '%s' triggered job %s for %s/%s (branch: %s)",
                hook.name(), jobId, targetWorkspace, targetRepoSlug, targetBranch);
        return jobId;
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
        jobStore.put(job);

        if (!jobQueue.submit(job)) {
            LOG.warnf("Hook '%s' job rejected: queue full", hook.name());
            return null;
        }

        LOG.infof("Hook '%s' triggered job %s for %s/%s (branch: %s)",
                hook.name(), jobId, workspace, repoSlug, targetBranch);
        return jobId;
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
