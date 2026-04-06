package com.eneve.agent.agent.handlers;

import com.eneve.agent.agent.*;
import com.eneve.agent.agent.model.RepoSettings;
import com.eneve.agent.agent.service.DocsEmbeddingService;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.agent.store.RepoSettingsStore;
import com.eneve.agent.model.*;
import com.eneve.agent.notifications.N8nWebhookNotifier;
import com.eneve.agent.notifications.TeamsNotifier;
import com.eneve.agent.scm.GitPlatformRegistry;
import com.eneve.agent.scm.GitPlatformService;
import com.eneve.agent.workspace.PlanWorkspaceManager;
import com.eneve.agent.workspace.WorkspaceContext;
import com.eneve.agent.settings.SettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class GenerateDocsHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(GenerateDocsHandler.class);

    @Inject ClaudeToolUseLoop toolUseLoop;
    @Inject AgentPromptBuilder promptBuilder;
    @Inject GitPlatformRegistry platformRegistry;
    @Inject DocsEmbeddingService docsEmbeddingService;
    @Inject RepoSettingsStore repoSettingsStore;
    @Inject JobStore jobStore;
    @Inject GitWorkspaceHelper gitHelper;
    @Inject JobLifecycleHelper lifecycle;
    @Inject TeamsNotifier teamsNotifier;
    @Inject N8nWebhookNotifier n8nNotifier;
    @Inject PlanWorkspaceManager planWorkspaceManager;
    @Inject SettingsService settings;

    @Override
    public JobType jobType() {
        return JobType.GENERATE_DOCS;
    }

    @Override
    public void handle(JobRecord job) {
        long jobTimeoutMinutes = Long.parseLong(settings.get("run-fix.job-timeout-minutes", "30"));
        int generateDocsMaxIterations = Integer.parseInt(settings.get("generate-docs.max-loop-iterations", "200"));
        GenerateDocsRequest request = job.getGenerateDocsRequest();
        job.setStatus(JobStatus.RUNNING);
        jobStore.update(job);

        LOG.infof("GenerateDocs job %s starting for %s (commitDirect=%s, branch=%s, target=%s)",
                job.getJobId(), request.repoUrl(), request.isCommitDirect(),
                request.branchName(), request.targetBranchOrDefault());

        RepoCoordinates coords;
        try {
            coords = RepoCoordinates.parse(request.repoUrl());
        } catch (IllegalArgumentException e) {
            lifecycle.failGenerateDocs(job, "Invalid repo URL: " + e.getMessage());
            return;
        }

        final GitPlatformService platformService = platformRegistry.resolve(request.repoUrl());

        String ws = coords.organization();
        String repoSlug = coords.repository();

        RepoSettings settings = repoSettingsStore.find(ws, repoSlug)
                .orElse(RepoSettings.defaults(ws, repoSlug));

        if (!settings.docsEnabled()) {
            lifecycle.failGenerateDocs(job,
                    "Documentation generation is disabled for " + ws + "/" + repoSlug);
            return;
        }

        WorkspaceContext workspace;
        try {
            workspace = job.getPlanId() != null
                    ? planWorkspaceManager.acquire(job.getPlanId())
                    : WorkspaceContext.create(job.getJobId());
        } catch (Exception e) {
            lifecycle.failGenerateDocs(job, "Failed to acquire workspace: " + e.getMessage());
            return;
        }

        try (WorkspaceContext ignored = workspace) {

            String authUrl = platformService.buildCloneUrl(coords.organization(), coords.project(), coords.repository());
            String targetBranch = request.targetBranchOrDefault();

            if (workspace.hasClonedRepo()) {
                LOG.infof("GenerateDocs: reusing existing workspace for plan %s", job.getPlanId());
            } else if (request.isCommitDirect()) {
                try {
                    workspace.cloneRepo(authUrl, targetBranch, jobTimeoutMinutes);
                } catch (Exception e) {
                    lifecycle.failGenerateDocs(job, "Clone failed: " + e.getMessage());
                    return;
                }
            } else {
                try {
                    workspace.cloneRepo(authUrl, request.branchName(), jobTimeoutMinutes);
                } catch (Exception e) {
                    // Branch doesn't exist yet — create it from target
                    try {
                        workspace.cloneAndCreateBranch(authUrl, targetBranch,
                                request.branchName(), jobTimeoutMinutes);
                    } catch (Exception e2) {
                        lifecycle.failGenerateDocs(job, "Clone/branch failed: " + e2.getMessage());
                        return;
                    }
                }
            }

            gitHelper.configureGitIfNeeded(workspace);

            workspace.putMetadata("workspace", ws);
            workspace.putMetadata("repoSlug", repoSlug);

            String systemPrompt = promptBuilder.buildGenerateDocsPrompt(request, workspace, settings);

            var tools = ToolDefinitions.docsGeneration();

            String summary;
            try {
                summary = toolUseLoop.run(systemPrompt, workspace, tools,
                        "Please generate comprehensive documentation for this repository. "
                                + "Start by exploring the project structure, then create all doc files.",
                        generateDocsMaxIterations, job.getJobId(), job.getJobType().name(),
                        job.getParentJobId(), job.getDepth());
            } catch (Exception e) {
                lifecycle.failGenerateDocs(job, "Agent loop error: " + e.getMessage());
                return;
            }

            try {
                docsEmbeddingService.indexDocs(workspace, ws, repoSlug);
            } catch (Exception e) {
                LOG.warnf("Doc embedding failed (non-fatal): %s", e.getMessage());
            }

            String pushBranch = request.isCommitDirect() ? targetBranch : request.branchName();

            boolean hasChanges;
            try {
                hasChanges = workspace.commitAll("docs: generate project documentation");
            } catch (Exception e) {
                lifecycle.failGenerateDocs(job, "Commit failed: " + e.getMessage());
                return;
            }

            if (!hasChanges) {
                job.setStatus(JobStatus.SUCCESS);
                job.setSummary("Documentation generation completed with no new files.");
                jobStore.archive(job);
                LOG.infof("GenerateDocs job %s completed: no changes made", job.getJobId());
                return;
            }

            if (request.isCommitDirect()) {
                try {
                    workspace.pullRebase(targetBranch, jobTimeoutMinutes);
                } catch (Exception e) {
                    LOG.warnf("Pull --rebase before push failed (non-fatal): %s", e.getMessage());
                }
            }

            try {
                workspace.push(pushBranch, jobTimeoutMinutes);
            } catch (Exception e) {
                lifecycle.failGenerateDocs(job, "Push failed: " + e.getMessage());
                return;
            }

            if (request.isCommitDirect()) {
                job.setStatus(JobStatus.SUCCESS);
                job.setSummary(summary);
                jobStore.archive(job);
                LOG.infof("GenerateDocs job %s completed: committed to %s", job.getJobId(), pushBranch);
            } else {
                try {
                    String title = "docs: generate project documentation";
                    String description = "**Automated documentation generated by code-agent**\n\n" + summary;
                    String[] prResult = platformService.createPullRequest(
                            coords.organization(), coords.project(), coords.repository(),
                            request.branchName(), targetBranch,
                            title, description);
                    job.setStatus(JobStatus.AWAITING_APPROVAL);
                    job.setSummary(summary);
                    job.setPrUrl(prResult[0]);
                    job.setPrId(prResult[1]);
                    jobStore.update(job);
                    LOG.infof("GenerateDocs job %s completed: PR %s created", job.getJobId(), prResult[0]);
                } catch (Exception e) {
                    lifecycle.failGenerateDocs(job, "Create PR failed: " + e.getMessage());
                    return;
                }
            }

            RunResult result = lifecycle.buildGenerateDocsResult(job, true);
            lifecycle.notifyResult(result, request.n8nWebhookUrl());

        } catch (Exception e) {
            lifecycle.failGenerateDocs(job, "Unexpected error in doc generation: " + e.getMessage());
        }
    }
}
