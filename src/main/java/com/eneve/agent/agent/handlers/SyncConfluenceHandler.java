package com.eneve.agent.agent.handlers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.eneve.agent.agent.JobHandler;
import com.eneve.agent.agent.JobLifecycleHelper;
import com.eneve.agent.agent.JobStore;
import com.eneve.agent.agent.RepoSettings;
import com.eneve.agent.agent.RepoSettingsStore;
import com.eneve.agent.confluence.ConfluenceService;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobStatus;
import com.eneve.agent.model.JobType;
import com.eneve.agent.model.RepoCoordinates;
import com.eneve.agent.model.SyncConfluenceRequest;
import com.eneve.agent.workspace.WorkspaceContext;

@ApplicationScoped
public class SyncConfluenceHandler implements JobHandler {

    private static final Logger LOG = Logger.getLogger(SyncConfluenceHandler.class);

    @Inject ConfluenceService confluenceService;
    @Inject RepoSettingsStore repoSettingsStore;
    @Inject JobStore jobStore;
    @Inject JobLifecycleHelper lifecycle;

    @ConfigProperty(name = "git.username")
    String gitUser;

    @ConfigProperty(name = "git.password")
    String gitPassword;

    @ConfigProperty(name = "run-fix.job-timeout-minutes", defaultValue = "30")
    long jobTimeoutMinutes;

    @Override
    public JobType jobType() {
        return JobType.SYNC_CONFLUENCE;
    }

    @Override
    public void handle(JobRecord job) {
        SyncConfluenceRequest request = job.getSyncConfluenceRequest();
        job.setStatus(JobStatus.RUNNING);
        jobStore.update(job);

        LOG.infof("SyncConfluence job %s starting for %s (branch=%s, docsPath=%s)",
                job.getJobId(), request.repoUrl(), request.branchOrDefault(), request.docsPathOrDefault());

        if (!confluenceService.isEnabled()) {
            lifecycle.failSyncConfluence(job,
                    "Confluence is not configured (set CONFLUENCE_BASE_URL, CONFLUENCE_USER, CONFLUENCE_API_TOKEN)");
            return;
        }

        RepoCoordinates coords;
        try {
            coords = RepoCoordinates.parse(request.repoUrl());
        } catch (IllegalArgumentException e) {
            lifecycle.failSyncConfluence(job, "Invalid repo URL: " + e.getMessage());
            return;
        }

        String ws = coords.organization();
        String repoSlug = coords.repository();

        RepoSettings settings = repoSettingsStore.find(ws, repoSlug)
                .orElse(RepoSettings.defaults(ws, repoSlug));

        String spaceKey = (request.confluenceSpaceKey() != null && !request.confluenceSpaceKey().isBlank())
                ? request.confluenceSpaceKey()
                : settings.confluenceSpaceKey();

        if (spaceKey == null || spaceKey.isBlank()) {
            lifecycle.failSyncConfluence(job,
                    "No Confluence space key available. Provide it in the request or configure it in repo settings.");
            return;
        }

        String parentPageId = (request.confluenceParentPageId() != null && !request.confluenceParentPageId().isBlank())
                ? request.confluenceParentPageId()
                : settings.confluenceParentPageId();

        try (WorkspaceContext workspace = WorkspaceContext.create(job.getJobId())) {

            String authUrl = coords.httpsCloneUrl(gitUser, gitPassword);
            try {
                workspace.cloneRepo(authUrl, request.branchOrDefault(), jobTimeoutMinutes);
            } catch (Exception e) {
                lifecycle.failSyncConfluence(job, "Clone failed: " + e.getMessage());
                return;
            }

            Path docsDir = workspace.getRoot().resolve(request.docsPathOrDefault());
            if (!Files.isDirectory(docsDir)) {
                lifecycle.failSyncConfluence(job, "Docs folder not found: " + request.docsPathOrDefault());
                return;
            }

            List<Path> mdFiles;
            try (Stream<Path> stream = Files.walk(docsDir)) {
                mdFiles = stream
                        .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".md"))
                        .sorted(Comparator.comparing(p ->
                                p.getFileName().toString().equalsIgnoreCase("README.md")
                                        ? "0" : p.getFileName().toString()))
                        .collect(Collectors.toList());
            } catch (Exception e) {
                lifecycle.failSyncConfluence(job, "Failed to list docs files: " + e.getMessage());
                return;
            }

            if (mdFiles.isEmpty()) {
                job.setStatus(JobStatus.SUCCESS);
                job.setSummary("No Markdown files found in " + request.docsPathOrDefault() + "; nothing to sync.");
                jobStore.archive(job);
                return;
            }

            int synced = 0;
            String docsRootPageId = null;

            for (Path mdFile : mdFiles) {
                String fileName = mdFile.getFileName().toString();
                String title = fileName.replaceAll("\\.md$", "").replace("-", " ").replace("_", " ");
                if (fileName.equalsIgnoreCase("README.md")) {
                    title = repoSlug + " Documentation";
                }

                String markdownContent;
                try {
                    markdownContent = Files.readString(mdFile);
                } catch (Exception e) {
                    LOG.warnf("SyncConfluence job %s: could not read %s — skipping: %s",
                            job.getJobId(), fileName, e.getMessage());
                    continue;
                }

                String effectiveParent = (docsRootPageId != null) ? docsRootPageId : parentPageId;

                try {
                    ConfluenceService.PageResult result =
                            confluenceService.createOrUpdatePage(spaceKey, effectiveParent, title, markdownContent);
                    if (result != null) {
                        if (docsRootPageId == null) {
                            docsRootPageId = result.pageId();
                        }
                        synced++;
                        LOG.debugf("SyncConfluence job %s: published '%s' → %s",
                                job.getJobId(), title, result.pageUrl());
                    } else {
                        LOG.warnf("SyncConfluence job %s: failed to publish '%s'", job.getJobId(), title);
                    }
                } catch (Exception e) {
                    LOG.warnf("SyncConfluence job %s: error publishing '%s': %s",
                            job.getJobId(), title, e.getMessage());
                }
            }

            String summary = "Synced " + synced + " of " + mdFiles.size()
                    + " Markdown files to Confluence space " + spaceKey + ".";
            job.setStatus(JobStatus.SUCCESS);
            job.setSummary(summary);
            jobStore.archive(job);
            LOG.infof("SyncConfluence job %s completed: %s", job.getJobId(), summary);

        } catch (Exception e) {
            lifecycle.failSyncConfluence(job, "Unexpected error in Confluence sync: " + e.getMessage());
        }
    }
}
