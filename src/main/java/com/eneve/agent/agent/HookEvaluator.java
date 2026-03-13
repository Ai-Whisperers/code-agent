package com.eneve.agent.agent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.eneve.agent.model.HookJobRequest;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobStatus;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Evaluates automation hooks against incoming events and submits matching
 * hook jobs to the queue. Checks per-repo overrides before firing.
 */
@ApplicationScoped
public class HookEvaluator {

    private static final Logger LOG = Logger.getLogger(HookEvaluator.class);

    @Inject HookStore hookStore;
    @Inject RepoSettingsStore repoSettingsStore;
    @Inject JobQueue jobQueue;
    @Inject JobStore jobStore;

    /**
     * Evaluates all hooks matching the given PR event and destination branch.
     * Returns a list of job IDs that were submitted.
     */
    public List<String> evaluate(String workspace, String repoSlug,
                                 String repoUrl, String prEvent,
                                 String destBranch) {

        List<AutomationHook> hooks = hookStore.findByTrigger("pr_event", prEvent);
        if (hooks.isEmpty()) {
            LOG.debugf("No hooks configured for event '%s'", prEvent);
            return List.of();
        }

        List<String> submittedJobIds = new ArrayList<>();

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
            }
        }

        return submittedJobIds;
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
            job.setStatus(JobStatus.FAILED);
            job.setErrorMessage("Job queue is full");
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
