package com.eneve.agent.agent.handlers;

import com.eneve.agent.agent.*;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.model.*;
import com.eneve.agent.notifications.TeamsNotifier;
import com.eneve.agent.scm.GitPlatformService;
import com.eneve.agent.workspace.WorkspaceContext;
import com.eneve.agent.settings.SettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class HookHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(HookHandler.class);

    @Inject ClaudeToolUseLoop toolUseLoop;
    @Inject AgentPromptBuilder promptBuilder;
    @Inject GitPlatformService platformService;
    @Inject JobStore jobStore;
    @Inject GitWorkspaceHelper gitHelper;
    @Inject JobLifecycleHelper lifecycle;
    @Inject TeamsNotifier teamsNotifier;
    @Inject SettingsService settings;

    @Override
    public JobType jobType() {
        return JobType.HOOK;
    }

    @Override
    public void handle(JobRecord job) {
        long jobTimeoutMinutes = Long.parseLong(settings.get("run-fix.job-timeout-minutes", "30"));
        HookJobRequest request = job.getHookRequest();
        job.setStatus(JobStatus.RUNNING);
        jobStore.update(job);

        LOG.infof("Hook job %s starting: hook='%s' repo=%s/%s target=%s commitDirect=%s",
                job.getJobId(), request.hookName(), request.workspace(), request.repoSlug(),
                request.targetBranch(), request.commitDirect());

        RepoCoordinates coords;
        try {
            coords = RepoCoordinates.parse(request.repoUrl());
        } catch (IllegalArgumentException e) {
            lifecycle.failHook(job, "Invalid repo URL: " + e.getMessage());
            return;
        }

        try (WorkspaceContext workspace = WorkspaceContext.create(job.getJobId())) {

            String authUrl = platformService.buildCloneUrl(coords.organization(), coords.project(), coords.repository());

            if (request.commitDirect()) {
                try {
                    workspace.cloneRepo(authUrl, request.targetBranch(), jobTimeoutMinutes);
                } catch (Exception e) {
                    lifecycle.failHook(job, "Clone failed: " + e.getMessage());
                    return;
                }
            } else {
                try {
                    workspace.cloneAndCreateBranch(authUrl, request.targetBranch(),
                            request.branchName(), jobTimeoutMinutes);
                } catch (Exception e) {
                    lifecycle.failHook(job, "Clone/branch failed: " + e.getMessage());
                    return;
                }
            }

            gitHelper.configureGitIfNeeded(workspace);

            RunFixRequest fixRequest = new RunFixRequest(
                    request.repoUrl(), request.branchName(), null,
                    request.prompt(), request.targetBranch(), null, null,
                    request.ruleNames(), request.extraRules(), null, null);

            String systemPrompt = promptBuilder.buildRunFixPrompt(fixRequest, request.prompt(), workspace, "");

            String summary;
            try {
                summary = toolUseLoop.run(systemPrompt, workspace,
                        job.getJobId(), job.getJobType().name());
            } catch (Exception e) {
                lifecycle.failHook(job, "Agent loop error: " + e.getMessage());
                return;
            }

            String pushBranch = request.commitDirect() ? request.targetBranch() : request.branchName();
            String commitMsg = "chore(hook-" + request.hookName() + "): " + summary;
            if (commitMsg.length() > 200) {
                commitMsg = commitMsg.substring(0, 197) + "...";
            }

            boolean hasChanges;
            try {
                hasChanges = workspace.commitAll(commitMsg);
            } catch (Exception e) {
                lifecycle.failHook(job, "Commit failed: " + e.getMessage());
                return;
            }

            if (!hasChanges) {
                job.setStatus(JobStatus.SUCCESS);
                job.setSummary("Hook '" + request.hookName() + "' completed with no changes needed.");
                jobStore.archive(job);
                LOG.infof("Hook job %s completed: no changes made", job.getJobId());
                teamsNotifier.sendNotification(lifecycle.buildHookResult(job, true));
                return;
            }

            try {
                workspace.push(pushBranch, jobTimeoutMinutes);
            } catch (Exception e) {
                lifecycle.failHook(job, "Push failed: " + e.getMessage());
                return;
            }

            if (request.commitDirect()) {
                job.setStatus(JobStatus.SUCCESS);
                job.setSummary(summary);
                jobStore.archive(job);
                LOG.infof("Hook job %s completed: committed directly to %s", job.getJobId(), pushBranch);
                teamsNotifier.sendNotification(lifecycle.buildHookResult(job, true));
            } else {
                try {
                    String title = "chore: " + request.hookName();
                    String description = "**Automated PR created by hook: " + request.hookName() + "**\n\n" + summary;
                    String[] prResult = platformService.createPullRequest(
                            coords.organization(), coords.project(), coords.repository(),
                            request.branchName(), request.targetBranch(),
                            title, description);
                    job.setStatus(JobStatus.AWAITING_APPROVAL);
                    job.setSummary(summary);
                    job.setPrUrl(prResult[0]);
                    job.setPrId(prResult[1]);
                    jobStore.update(job);
                    LOG.infof("Hook job %s completed: PR %s created", job.getJobId(), prResult[0]);
                    teamsNotifier.sendNotification(lifecycle.buildHookResult(job, true));
                } catch (Exception e) {
                    lifecycle.failHook(job, "Create PR failed: " + e.getMessage());
                }
            }

        } catch (Exception e) {
            lifecycle.failHook(job, "Unexpected error in hook execution: " + e.getMessage());
        }
    }
}
