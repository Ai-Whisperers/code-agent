package com.eneve.agent.agent.handlers;

import com.eneve.agent.agent.*;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.architecture.ArchitectureDiagramStore;
import com.eneve.agent.architecture.ArchitectureDiagramDto;
import com.eneve.agent.architecture.StructurizrService;
import com.eneve.agent.model.*;
import com.eneve.agent.scm.GitPlatformRegistry;
import com.eneve.agent.scm.GitPlatformService;
import com.eneve.agent.settings.SettingsService;
import com.eneve.agent.workspace.WorkspaceContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Handles {@link JobType#GENERATE_ARCHITECTURE} jobs.
 *
 * <p>Flow:
 * <ol>
 *   <li>Clone the repository.</li>
 *   <li>Load the pinned DSL from the store (if any) as the AI baseline.</li>
 *   <li>Run the {@code ClaudeToolUseLoop} with the {@code generate-architecture.txt} prompt.</li>
 *   <li>Read the written {@code docs/architecture.dsl} file.</li>
 *   <li>Validate and export to Mermaid via {@link StructurizrService}.</li>
 *   <li>Persist each view as a new version in {@link ArchitectureDiagramStore}.</li>
 *   <li>Commit the DSL file and create a PR (or commit directly).</li>
 * </ol>
 */
@ApplicationScoped
public class GenerateArchitectureHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(GenerateArchitectureHandler.class);
    private static final String DSL_PATH = "docs/architecture.dsl";

    @Inject ClaudeToolUseLoop toolUseLoop;
    @Inject AgentPromptBuilder promptBuilder;
    @Inject GitPlatformRegistry platformRegistry;
    @Inject JobStore jobStore;
    @Inject GitWorkspaceHelper gitHelper;
    @Inject JobLifecycleHelper lifecycle;
    @Inject StructurizrService structurizrService;
    @Inject ArchitectureDiagramStore diagramStore;
    @Inject SettingsService settings;

    @Override
    public JobType jobType() {
        return JobType.GENERATE_ARCHITECTURE;
    }

    @Override
    public void handle(JobRecord job) {
        long jobTimeoutMinutes = Long.parseLong(settings.get("run-fix.job-timeout-minutes", "30"));
        int maxIterations = Integer.parseInt(settings.get("generate-architecture.max-loop-iterations", "150"));

        GenerateArchitectureRequest request = (GenerateArchitectureRequest) job.getPayload();
        job.setStatus(JobStatus.RUNNING);
        jobStore.update(job);

        LOG.infof("GenerateArchitecture job %s starting for %s (commitDirect=%s)",
                job.getJobId(), request.repoUrl(), request.isCommitDirect());

        RepoCoordinates coords;
        try {
            coords = RepoCoordinates.parse(request.repoUrl());
        } catch (IllegalArgumentException e) {
            failJob(job, "Invalid repo URL: " + e.getMessage());
            return;
        }

        final GitPlatformService platformService = platformRegistry.resolve(request.repoUrl());
        String repoSlug = coords.repository();

        WorkspaceContext workspace;
        try {
            workspace = WorkspaceContext.create(job.getJobId());
        } catch (Exception e) {
            failJob(job, "Failed to create workspace: " + e.getMessage());
            return;
        }

        try (WorkspaceContext ignored = workspace) {
            String authUrl = platformService.buildCloneUrl(
                    coords.organization(), coords.project(), coords.repository());
            String targetBranch = request.targetBranchOrDefault();

            if (request.isCommitDirect()) {
                try {
                    workspace.cloneRepo(authUrl, targetBranch, jobTimeoutMinutes);
                } catch (Exception e) {
                    failJob(job, "Clone failed: " + e.getMessage());
                    return;
                }
            } else {
                try {
                    workspace.cloneRepo(authUrl, request.branchName(), jobTimeoutMinutes);
                } catch (Exception e) {
                    try {
                        workspace.cloneAndCreateBranch(authUrl, targetBranch,
                                request.branchName(), jobTimeoutMinutes);
                    } catch (Exception e2) {
                        failJob(job, "Clone/branch failed: " + e2.getMessage());
                        return;
                    }
                }
            }

            gitHelper.configureGitIfNeeded(workspace);

            // Load pinned DSL as AI baseline (empty string if none exists)
            Optional<String> pinnedDsl = diagramStore.findPinnedDsl(repoSlug);
            String priorDsl = pinnedDsl.orElse("");

            String systemPrompt = buildPrompt(priorDsl);

            String summary;
            try {
                summary = toolUseLoop.run(
                        systemPrompt,
                        workspace,
                        ToolDefinitions.docsGeneration(),
                        "Please generate a Structurizr DSL architecture model for this repository. "
                                + "Explore the codebase, then write docs/architecture.dsl.",
                        maxIterations,
                        job.getJobId(),
                        job.getJobType().name());
            } catch (Exception e) {
                failJob(job, "Agent loop error: " + e.getMessage());
                return;
            }

            // Read and validate the written DSL file
            Path dslFile = workspace.getRoot().resolve(DSL_PATH);
            if (!Files.exists(dslFile)) {
                failJob(job, "Agent did not produce docs/architecture.dsl");
                return;
            }

            String dslContent;
            try {
                dslContent = Files.readString(dslFile);
            } catch (Exception e) {
                failJob(job, "Failed to read docs/architecture.dsl: " + e.getMessage());
                return;
            }

            List<ArchitectureDiagramDto> diagrams;
            try {
                diagrams = structurizrService.validateAndExport(dslContent);
            } catch (Exception e) {
                failJob(job, "DSL validation failed: " + e.getMessage());
                return;
            }

            if (diagrams.isEmpty()) {
                failJob(job, "DSL parsed successfully but no views were found");
                return;
            }

            // Persist each view as a new AI-generated version
            for (ArchitectureDiagramDto diagram : diagrams) {
                diagramStore.insertRepoVersion(
                        repoSlug,
                        request.repoUrl(),
                        diagram.viewName(),
                        diagram.viewType(),
                        "ai",
                        dslContent,
                        diagram.mermaidSrc());
            }
            LOG.infof("GenerateArchitecture: persisted %d view(s) for repo %s", diagrams.size(), repoSlug);

            // Commit and push
            boolean hasChanges;
            try {
                hasChanges = workspace.commitAll("docs: generate Structurizr architecture model");
            } catch (Exception e) {
                failJob(job, "Commit failed: " + e.getMessage());
                return;
            }

            String pushBranch = request.isCommitDirect() ? targetBranch : request.branchName();

            if (hasChanges) {
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
                    failJob(job, "Push failed: " + e.getMessage());
                    return;
                }
            }

            if (request.isCommitDirect() || !hasChanges) {
                job.setStatus(JobStatus.SUCCESS);
                job.setSummary(summary);
                jobStore.archive(job);
                LOG.infof("GenerateArchitecture job %s completed (direct commit or no changes)", job.getJobId());
            } else {
                try {
                    String title = "docs: generate Structurizr architecture model";
                    String description = "**Architecture model generated by code-agent**\n\n" + summary;
                    String[] prResult = platformService.createPullRequest(
                            coords.organization(), coords.project(), coords.repository(),
                            request.branchName(), targetBranch, title, description);
                    job.setStatus(JobStatus.AWAITING_APPROVAL);
                    job.setSummary(summary);
                    job.setPrUrl(prResult[0]);
                    job.setPrId(prResult[1]);
                    jobStore.update(job);
                    LOG.infof("GenerateArchitecture job %s completed: PR %s created", job.getJobId(), prResult[0]);
                } catch (Exception e) {
                    failJob(job, "Create PR failed: " + e.getMessage());
                }
            }

        } catch (Exception e) {
            failJob(job, "Unexpected error: " + e.getMessage());
        }
    }

    private String buildPrompt(String priorDsl) {
        String priorDslSection = priorDsl.isBlank() ? "" : priorDsl;
        // Use the prompt template service via the promptBuilder's underlying mechanism.
        // We inline the template loading here to avoid coupling to a specific promptBuilder method.
        try {
            String template = new String(
                    getClass().getClassLoader()
                            .getResourceAsStream("prompts/generate-architecture.txt")
                            .readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);

            if (priorDslSection.isBlank()) {
                // Remove the {{#if PRIOR_ARCHITECTURE_DSL}} block entirely
                template = template.replaceAll(
                        "(?s)\\{\\{#if PRIOR_ARCHITECTURE_DSL\\}\\}.*?\\{\\{/if\\}\\}\\s*",
                        "");
            } else {
                template = template
                        .replace("{{#if PRIOR_ARCHITECTURE_DSL}}", "")
                        .replace("{{/if}}", "")
                        .replace("{{PRIOR_ARCHITECTURE_DSL}}", priorDslSection);
            }
            return template;
        } catch (Exception e) {
            LOG.warnf("Failed to load generate-architecture.txt prompt, using fallback: %s", e.getMessage());
            return "You are generating a Structurizr DSL architecture model. "
                    + "Explore the repository and write docs/architecture.dsl with C4 model views.";
        }
    }

    private void failJob(JobRecord job, String reason) {
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(reason);
        jobStore.update(job);
        LOG.errorf("GenerateArchitecture job %s failed: %s", job.getJobId(), reason);
    }
}
