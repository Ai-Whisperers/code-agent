package com.eneve.agent;

import com.eneve.agent.Soc2Policy;
import com.eneve.agent.RepoSettingsService;
import com.eneve.agent.agent.JobQueue;
import com.eneve.agent.agent.model.RepoSettings;
import com.eneve.agent.agent.service.PrCacheSyncService;
import com.eneve.agent.agent.store.CommentStore;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.agent.store.PrCacheStore;
import com.eneve.agent.agent.store.RepoSettingsStore;
import com.eneve.agent.diff.JobDiffParser;
import com.eneve.agent.model.JobCommitsResponse;
import com.eneve.agent.model.JobDiffResponse;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobReviewResponse;
import com.eneve.agent.model.JobStatus;
import com.eneve.agent.model.JobType;
import com.eneve.agent.model.OpenPrEntry;
import com.eneve.agent.model.PromoteRequest;
import com.eneve.agent.model.ReviewCommentEntry;
import com.eneve.agent.scm.AgentComment;
import com.eneve.agent.scm.GitPlatformRegistry;
import com.eneve.agent.scm.GitPlatformService;
import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RequestScoped
@Path("/pull-requests")
@RolesAllowed({"app_developer", "app_admin"})
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Pull Requests", description = "List and inspect open pull requests across all active repositories")
public class PullRequestsResource {

    private static final Logger LOG = Logger.getLogger(PullRequestsResource.class);

    @Inject
    PrCacheStore prCacheStore;

    @Inject
    PrCacheSyncService prCacheSyncService;

    @Inject
    GitPlatformRegistry gitPlatformRegistry;

    @Inject
    JobStore jobStore;

    @Inject
    CommentStore commentStore;

    @Inject
    Soc2Policy soc2Policy;

    @Inject
    JobQueue jobQueue;

    @Inject
    RepoSettingsStore repoSettingsStore;

    @Inject
    RepoSettingsService repoSettingsService;

    // ── List PRs from cache (paged + searchable) ──────────────────────────

    @GET
    @Blocking
    @Operation(operationId = "listOpenPullRequests",
               summary = "List pull requests",
               description = "Returns pull requests from the cache with optional free-text search, "
                           + "status filter, and pagination. Defaults to OPEN status only.")
    public Response listOpenPullRequests(
            @QueryParam("q")      String q,
            @QueryParam("status") @DefaultValue("OPEN") String status,
            @QueryParam("page")   @DefaultValue("0")    int page,
            @QueryParam("size")   @DefaultValue("50")   int size) {

        int safeSize   = Math.min(Math.max(1, size), 200);
        int safePage   = Math.max(0, page);
        int offset     = safePage * safeSize;

        String effectiveStatus = (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status))
                ? null : status.toUpperCase();
        String effectiveQ = (q == null || q.isBlank()) ? null : q.trim();

        List<OpenPrEntry> cached = prCacheStore.search(effectiveQ, effectiveStatus, safeSize, offset);
        int total = prCacheStore.count(effectiveQ, effectiveStatus);

        List<OpenPrEntry> enriched = new ArrayList<>(cached.size());
        for (OpenPrEntry pr : cached) {
            JobRecord linkedJob = findLinkedJob(pr.prId(), pr.repoSlug());
            String jobId = linkedJob != null ? linkedJob.getJobId() : null;
            boolean soc2 = linkedJob != null
                    && JobStore.isSoc2Applicable(linkedJob, soc2Policy.bugIssueTypes());
            // Persist soc2 flag back to cache if it changed
            if (soc2 != pr.soc2()) {
                prCacheStore.upsert(new OpenPrEntry(
                        pr.workspace(), pr.repoSlug(), pr.prId(), pr.prUrl(),
                        pr.title(), pr.sourceBranch(), pr.targetBranch(),
                        pr.author(), pr.createdOn(), pr.updatedOn(),
                        jobId, pr.status(), soc2));
            }
            enriched.add(new OpenPrEntry(
                    pr.workspace(), pr.repoSlug(), pr.prId(), pr.prUrl(),
                    pr.title(), pr.sourceBranch(), pr.targetBranch(),
                    pr.author(), pr.createdOn(), pr.updatedOn(),
                    jobId, pr.status(), soc2));
        }

        Map<String, Object> envelope = new HashMap<>();
        envelope.put("items", enriched);
        envelope.put("total", total);
        envelope.put("page", safePage);
        envelope.put("size", safeSize);
        return Response.ok(envelope).build();
    }

    // ── Admin: force full cache refresh ───────────────────────────────────

    @POST
    @Blocking
    @Path("/sync")
    @RolesAllowed("app_admin")
    @Operation(operationId = "syncPrCache",
               summary = "Force PR cache refresh",
               description = "Re-fetches open PRs from the SCM for all active repositories and "
                           + "updates the cache. Restricted to app_admin.")
    public Response syncPrCache() {
        try {
            int synced = prCacheSyncService.syncAll();
            return Response.ok(Map.of("synced", synced)).build();
        } catch (Exception e) {
            LOG.warnf("Manual PR cache sync failed: %s", e.getMessage());
            return Response.status(503).entity(Map.of("error", e.getMessage())).build();
        }
    }

    // ── Merged PRs from SCM (last N days, for promote tab bootstrap) ─────

    @GET
    @Blocking
    @Path("/merged")
    @Operation(operationId = "listMergedPullRequests",
               summary = "List recently merged pull requests from SCM",
               description = "Fetches merged PRs directly from the SCM for all active repositories, "
                           + "going back the specified number of days (default 30). "
                           + "Results are not cached — each call hits the SCM API.")
    public Response listMergedPullRequests(
            @QueryParam("days") @DefaultValue("30") int days) {

        int safeDays = Math.min(Math.max(1, days), 180);
        Instant since = Instant.now().minusSeconds((long) safeDays * 86_400);

        List<RepoSettings> repos = repoSettingsService.listAll().stream()
                .filter(r -> !Boolean.TRUE.equals(r.archived()))
                .toList();

        if (repos.isEmpty()) {
            return Response.ok(Map.of("items", List.of(), "days", safeDays)).build();
        }

        GitPlatformService platform = gitPlatformRegistry.defaultPlatform();
        List<OpenPrEntry> all = new ArrayList<>();

        for (RepoSettings repo : repos) {
            try {
                List<OpenPrEntry> merged = platform.listMergedPullRequests(
                        repo.workspace(), "", repo.repoSlug(), since);
                // Also upsert into the cache so the status filter on the main list works too
                for (OpenPrEntry pr : merged) {
                    prCacheStore.upsert(pr);
                }
                all.addAll(merged);
            } catch (Exception e) {
                LOG.warnf("listMergedPullRequests failed for %s/%s (non-fatal): %s",
                        repo.workspace(), repo.repoSlug(), e.getMessage());
            }
        }

        // Sort newest-first by updatedOn
        all.sort((a, b) -> {
            String ta = a.updatedOn() != null ? a.updatedOn() : "";
            String tb = b.updatedOn() != null ? b.updatedOn() : "";
            return tb.compareTo(ta);
        });

        return Response.ok(Map.of("items", all, "days", safeDays)).build();
    }

    // ── Trigger promotion cherry-pick to main ────────────────────────────

    @POST
    @Blocking
    @Path("/{workspace}/{repoSlug}/{prId}/promote")
    @Operation(operationId = "promotePr",
               summary = "Trigger promotion cherry-pick",
               description = "Creates a PROMOTE job that cherry-picks the commits from this PR onto the "
                           + "production branch (main) and raises a promotion PR for review. "
                           + "Works for both agent-created and manually created PRs. "
                           + "Returns 409 if a promotion job already exists for this PR.")
    public Response promotePr(
            @PathParam("workspace") String workspace,
            @PathParam("repoSlug") String repoSlug,
            @PathParam("prId") String prId) {

        // Check for an existing linked job (may be null for manually created PRs)
        JobRecord originalJob = findLinkedJob(prId, repoSlug);

        if (originalJob != null && originalJob.getPromotionJobId() != null) {
            return Response.status(409)
                    .entity(Map.of("error", "A promotion job already exists for this PR.",
                                   "promotionJobId", originalJob.getPromotionJobId()))
                    .build();
        }

        // Resolve repo URL — prefer linked job, fall back to repo settings, then SCM API
        String repoUrl = null;
        if (originalJob != null) {
            if (originalJob.getRequest() != null) repoUrl = originalJob.getRequest().repoUrl();
            if (repoUrl == null && originalJob.getFixPrRequest() != null)
                repoUrl = originalJob.getFixPrRequest().repoUrl();
        }
        if (repoUrl == null) {
            repoUrl = repoSettingsStore.find(workspace, repoSlug)
                    .map(RepoSettings::gitPlatformUrl)
                    .orElse(null);
        }
        if (repoUrl == null) {
            // Last resort: ask the SCM for the PR info which includes the repo URL
            try {
                GitPlatformService platform = gitPlatformRegistry.defaultPlatform();
                Map<String, String> info = platform.getPullRequestInfo(workspace, "", repoSlug, prId);
                repoUrl = info.get("repoUrl");
            } catch (Exception e) {
                LOG.warnf("Could not resolve repoUrl for %s/%s: %s", workspace, repoSlug, e.getMessage());
            }
        }
        if (repoUrl == null) {
            return Response.status(400)
                    .entity(Map.of("error", "Cannot determine repository URL. "
                            + "Ensure the repository is configured in Repo Settings."))
                    .build();
        }

        // Resolve jiraKey and fix branch from linked job if available
        String jiraKey = null;
        String fixBranchName = null;
        if (originalJob != null) {
            if (originalJob.getRequest() != null) jiraKey = originalJob.getRequest().jiraKey();
            if (jiraKey == null && originalJob.getFixPrRequest() != null)
                jiraKey = originalJob.getFixPrRequest().jiraKey();
            if (jiraKey == null && originalJob.getReviewRequest() != null)
                jiraKey = originalJob.getReviewRequest().jiraKey();
            fixBranchName = originalJob.getFixBranchName();
            if (fixBranchName == null && originalJob.getRequest() != null)
                fixBranchName = originalJob.getRequest().branchName();
        }

        // For manually created PRs with no linked job, get the source branch from the SCM
        if (fixBranchName == null) {
            try {
                GitPlatformService platform = gitPlatformRegistry.defaultPlatform();
                Map<String, String> info = platform.getPullRequestInfo(workspace, "", repoSlug, prId);
                fixBranchName = info.get("sourceBranch");
            } catch (Exception e) {
                LOG.warnf("Could not resolve source branch for PR %s/%s#%s: %s",
                        workspace, repoSlug, prId, e.getMessage());
            }
        }

        String productionBranch = soc2Policy.productionBranch();
        String effectiveJiraKey = jiraKey != null ? jiraKey : prId;

        PromoteRequest promoteRequest = new PromoteRequest(
                repoUrl,
                effectiveJiraKey,
                fixBranchName != null ? fixBranchName : "",
                prId,
                productionBranch,
                originalJob != null ? originalJob.getAikidoIssueId() : null);

        String promotionJobId = java.util.UUID.randomUUID().toString();
        JobRecord promoteJob = new JobRecord(promotionJobId, promoteRequest);
        if (originalJob != null) {
            promoteJob.setAikidoIssueId(originalJob.getAikidoIssueId());
            if (jiraKey != null) {
                promoteJob.setJiraIssueType(originalJob.getJiraIssueType());
                promoteJob.setJiraPriority(originalJob.getJiraPriority());
                promoteJob.setJiraCreatedAt(originalJob.getJiraCreatedAt());
            }
        }

        jobStore.put(promoteJob);

        if (originalJob != null) {
            originalJob.setPromotionJobId(promotionJobId);
            jobStore.update(originalJob);
        }

        boolean queued = jobQueue.submit(promoteJob);
        if (!queued) {
            return Response.status(429)
                    .entity(Map.of("error", "Job queue is full. Please try again shortly."))
                    .build();
        }

        LOG.infof("Manual PROMOTE job %s created for PR %s/%s#%s (linkedJob=%s, queued=true)",
                promotionJobId, workspace, repoSlug, prId, originalJob != null ? originalJob.getJobId() : "none");

        return Response.accepted(Map.of("jobId", promotionJobId)).build();
    }

    // ── PR info ───────────────────────────────────────────────────────────

    @GET
    @Blocking
    @Path("/{workspace}/{repoSlug}/{prId}/info")
    @Operation(operationId = "getPrInfo",
               summary = "Get PR metadata",
               description = "Returns title, branches, and author for the given pull request.")
    public Response getPrInfo(
            @PathParam("workspace") String workspace,
            @PathParam("repoSlug") String repoSlug,
            @PathParam("prId") String prId) {
        try {
            GitPlatformService platform = gitPlatformRegistry.defaultPlatform();
            Map<String, String> info = new HashMap<>(platform.getPullRequestInfo(workspace, "", repoSlug, prId));
            String jobId = findLinkedJobId(prId, repoSlug);
            if (jobId != null) {
                info.put("jobId", jobId);
            }
            return Response.ok(info).build();
        } catch (Exception e) {
            LOG.warnf("Failed to get PR info for %s/%s#%s: %s", workspace, repoSlug, prId, e.getMessage());
            return Response.status(503).entity(Map.of("error", e.getMessage())).build();
        }
    }

    // ── PR diff ───────────────────────────────────────────────────────────

    @GET
    @Blocking
    @Path("/{workspace}/{repoSlug}/{prId}/diff")
    @Operation(operationId = "getPrDiff",
               summary = "Get PR diff",
               description = "Fetches the unified diff for the given pull request.")
    public Response getPrDiff(
            @PathParam("workspace") String workspace,
            @PathParam("repoSlug") String repoSlug,
            @PathParam("prId") String prId) {
        try {
            GitPlatformService platform = gitPlatformRegistry.defaultPlatform();

            String sourceBranch = "";
            String targetBranch = "";
            try {
                Map<String, String> info = platform.getPullRequestInfo(workspace, "", repoSlug, prId);
                sourceBranch = info.getOrDefault("sourceBranch", "");
                targetBranch = info.getOrDefault("destinationBranch", "");
            } catch (Exception e) {
                LOG.warnf("Could not fetch PR info for %s/%s#%s: %s", workspace, repoSlug, prId, e.getMessage());
            }

            String rawDiff = platform.getPullRequestDiff(workspace, "", repoSlug, prId);
            JobDiffResponse diff = JobDiffParser.parse(sourceBranch, targetBranch, rawDiff);
            return Response.ok(diff).build();
        } catch (Exception e) {
            LOG.warnf("Failed to get PR diff for %s/%s#%s: %s", workspace, repoSlug, prId, e.getMessage());
            return Response.status(503).entity(Map.of("error", e.getMessage())).build();
        }
    }

    // ── PR commits ────────────────────────────────────────────────────────

    @GET
    @Blocking
    @Path("/{workspace}/{repoSlug}/{prId}/commits")
    @Operation(operationId = "getPrCommits",
               summary = "List PR commits",
               description = "Returns the list of commits for the given pull request.")
    public Response getPrCommits(
            @PathParam("workspace") String workspace,
            @PathParam("repoSlug") String repoSlug,
            @PathParam("prId") String prId) {
        try {
            GitPlatformService platform = gitPlatformRegistry.defaultPlatform();
            var commits = platform.getPrCommits(workspace, "", repoSlug, prId);
            return Response.ok(new JobCommitsResponse(commits)).build();
        } catch (Exception e) {
            LOG.warnf("Failed to get PR commits for %s/%s#%s: %s", workspace, repoSlug, prId, e.getMessage());
            return Response.ok(new JobCommitsResponse(List.of())).build();
        }
    }

    // ── Commit diff ───────────────────────────────────────────────────────

    @GET
    @Blocking
    @Path("/{workspace}/{repoSlug}/{prId}/commits/{sha}/diff")
    @Operation(operationId = "getPrCommitDiff",
               summary = "Get diff for a single commit in a PR",
               description = "Fetches the unified diff for the given commit SHA.")
    public Response getPrCommitDiff(
            @PathParam("workspace") String workspace,
            @PathParam("repoSlug") String repoSlug,
            @PathParam("prId") String prId,
            @PathParam("sha") String sha) {
        try {
            GitPlatformService platform = gitPlatformRegistry.defaultPlatform();
            String rawDiff = platform.getCommitDiff(workspace, "", repoSlug, sha);
            JobDiffResponse diff = JobDiffParser.parse(sha, "parent", rawDiff);
            return Response.ok(diff).build();
        } catch (Exception e) {
            LOG.warnf("Failed to get commit diff for %s/%s sha %s: %s", workspace, repoSlug, sha, e.getMessage());
            return Response.status(503).entity(Map.of("error", e.getMessage())).build();
        }
    }

    // ── PR review ─────────────────────────────────────────────────────────

    @GET
    @Blocking
    @Path("/{workspace}/{repoSlug}/{prId}/review")
    @Operation(operationId = "getPrReview",
               summary = "Get bot review for a PR",
               description = "Returns the bot review summary and inline comments for the PR. "
                           + "Looks up a linked REVIEW job by prId in the job store.")
    public Response getPrReview(
            @PathParam("workspace") String workspace,
            @PathParam("repoSlug") String repoSlug,
            @PathParam("prId") String prId) {
        try {
            String reviewJobId = null;
            String reviewJobStatus = null;
            String reviewSummary = null;
            Instant reviewedAt = null;

            List<JobRecord> relatedJobs = jobStore.findByPrId(prId);
            JobRecord reviewJob = relatedJobs.stream()
                    .filter(j -> j.getJobType() == JobType.REVIEW)
                    .findFirst()
                    .orElse(null);

            if (reviewJob != null) {
                reviewJobId = reviewJob.getJobId();
                reviewJobStatus = reviewJob.getStatus().name();
                reviewSummary = reviewJob.getSummary();
                reviewedAt = reviewJob.getCreatedAt();
            }

            List<ReviewCommentEntry> comments = new ArrayList<>();
            try {
                GitPlatformService platform = gitPlatformRegistry.defaultPlatform();
                List<AgentComment> agentComments = platform.getAgentPrComments(workspace, "", repoSlug, prId);

                List<Long> ids = agentComments.stream().map(AgentComment::id).toList();
                Map<Long, CommentStore.ResolvedInfo> resolvedInfoMap = commentStore.getResolvedInfoBatch(ids);

                for (AgentComment c : agentComments) {
                    if (c.content() != null && c.content().trim().startsWith("<!-- agent-reviewed-up-to:")) {
                        continue;
                    }
                    CommentStore.ResolvedInfo ri = resolvedInfoMap.getOrDefault(
                            c.id(), CommentStore.ResolvedInfo.OPEN);
                    comments.add(new ReviewCommentEntry(
                            c.id(), c.filePath(), c.line(), c.content(),
                            ri.resolved(),
                            ri.resolvedAt() != null ? ri.resolvedAt().toString() : null,
                            ri.resolvedBy(),
                            c.parentId()));
                }
            } catch (Exception e) {
                LOG.warnf("Could not fetch PR comments for %s/%s#%s: %s", workspace, repoSlug, prId, e.getMessage());
            }

            return Response.ok(new JobReviewResponse(reviewJobId, reviewJobStatus, reviewSummary, reviewedAt, comments)).build();
        } catch (Exception e) {
            LOG.warnf("Failed to get PR review for %s/%s#%s: %s", workspace, repoSlug, prId, e.getMessage());
            return Response.status(503).entity(Map.of("error", e.getMessage())).build();
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private JobRecord findLinkedJob(String prId, String repoSlug) {
        try {
            List<JobRecord> jobs = jobStore.findByPrId(prId);
            return jobs.stream()
                    .filter(j -> j.getStatus() == JobStatus.AWAITING_APPROVAL
                            || j.getStatus() == JobStatus.RUNNING
                            || j.getStatus() == JobStatus.SUCCESS)
                    .filter(j -> repoSlug == null || j.getRepoSlug() == null
                            || repoSlug.equalsIgnoreCase(j.getRepoSlug()))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            LOG.debugf("Could not find linked job for prId %s: %s", prId, e.getMessage());
            return null;
        }
    }

    private String findLinkedJobId(String prId, String repoSlug) {
        JobRecord job = findLinkedJob(prId, repoSlug);
        return job != null ? job.getJobId() : null;
    }
}
