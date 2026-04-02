package com.eneve.agent;

import com.eneve.agent.Soc2Policy;
import com.eneve.agent.agent.service.PrCacheSyncService;
import com.eneve.agent.agent.store.CommentStore;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.agent.store.PrCacheStore;
import com.eneve.agent.diff.JobDiffParser;
import com.eneve.agent.model.JobCommitsResponse;
import com.eneve.agent.model.JobDiffResponse;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobReviewResponse;
import com.eneve.agent.model.JobStatus;
import com.eneve.agent.model.JobType;
import com.eneve.agent.model.OpenPrEntry;
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
