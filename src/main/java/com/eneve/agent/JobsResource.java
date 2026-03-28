package com.eneve.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.model.DiffFileEntry;
import com.eneve.agent.model.DiffHunkEntry;
import com.eneve.agent.model.DiffLineEntry;
import com.eneve.agent.model.JobDiffResponse;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobStatus;
import com.eneve.agent.model.JobStatusResponse;
import com.eneve.agent.model.JobType;
import com.eneve.agent.model.RepoCoordinates;
import com.eneve.agent.scm.GitPlatformService;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import io.quarkus.security.Authenticated;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@RequestScoped
@Path("/jobs")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Jobs", description = "Query and manage agent jobs")
public class JobsResource {

    private static final Logger LOG = Logger.getLogger(JobsResource.class);

    @Inject
    JobStore jobStore;

    @Inject
    GitPlatformService gitPlatformService;

    @GET
    @Operation(
            operationId = "listJobs",
            summary = "List jobs",
            description = "Returns jobs from both the active queue and job history, newest first. "
                    + "Supports optional filtering by status and/or job type with pagination."
    )
    @APIResponse(responseCode = "200", description = "List of jobs",
            content = @Content(schema = @Schema(implementation = JobStatusResponse.class)))
    @APIResponse(responseCode = "400", description = "Invalid status or jobType value")
    public Response listJobs(

            @Parameter(description = "Filter by job status. One of: PENDING, QUEUED, RUNNING, SUCCESS, FAILED, AWAITING_APPROVAL")
            @QueryParam("status") String statusParam,

            @Parameter(description = "Filter by job type. One of: FIX, REVIEW, FIX_PR, GENERATE_TESTS, GENERATE_DOCS, METRICS, QUALITY_REPORT, …")
            @QueryParam("jobType") String jobTypeParam,

            @Parameter(description = "Maximum number of results to return (1–200, default 50)")
            @QueryParam("limit") @DefaultValue("50") int limit,

            @Parameter(description = "Zero-based page number for pagination (default 0)")
            @QueryParam("page") @DefaultValue("0") int page

    ) {
        JobStatus status = null;
        if (statusParam != null && !statusParam.isBlank()) {
            try {
                status = JobStatus.valueOf(statusParam.toUpperCase());
            } catch (IllegalArgumentException e) {
                return Response.status(400)
                        .entity(java.util.Map.of("error", "Invalid status: " + statusParam
                                + ". Must be one of: PENDING, QUEUED, RUNNING, SUCCESS, FAILED, AWAITING_APPROVAL"))
                        .build();
            }
        }

        JobType jobType = null;
        if (jobTypeParam != null && !jobTypeParam.isBlank()) {
            try {
                jobType = JobType.valueOf(jobTypeParam.toUpperCase());
            } catch (IllegalArgumentException e) {
                return Response.status(400)
                        .entity(java.util.Map.of("error", "Invalid jobType: " + jobTypeParam))
                        .build();
            }
        }

        int safeLimit = Math.min(Math.max(1, limit), 200);
        int offset = Math.max(0, page) * safeLimit;

        List<JobStatusResponse> jobs = jobStore.search(status, jobType, safeLimit, offset);
        return Response.ok(jobs).build();
    }

    @GET
    @Path("/{jobId}/diff")
    @Operation(
            operationId = "getJobDiff",
            summary = "Get PR diff for a job",
            description = "Fetches the unified diff from the SCM for the pull request associated with this job. "
                    + "Returns per-file diff entries with line-level additions and deletions."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Diff data",
                    content = @Content(schema = @Schema(implementation = JobDiffResponse.class))),
            @APIResponse(responseCode = "404", description = "Job not found or has no pull request"),
            @APIResponse(responseCode = "503", description = "SCM diff unavailable")
    })
    public Response getJobDiff(
            @Parameter(description = "UUID of the job", required = true)
            @PathParam("jobId") String jobId) {

        var jobOpt = jobStore.get(jobId);
        if (jobOpt.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "Job not found: " + jobId)).build();
        }
        JobRecord job = jobOpt.get();

        if (job.getPrId() == null || job.getPrId().isBlank()) {
            return Response.status(404).entity(Map.of("error", "Job has no associated pull request")).build();
        }

        RepoCoordinates coords;
        try {
            coords = resolveCoords(job);
        } catch (Exception e) {
            LOG.warnf("Cannot resolve repo coordinates for job %s: %s", jobId, e.getMessage());
            return Response.status(404).entity(Map.of("error", "Cannot resolve repository for job: " + e.getMessage())).build();
        }

        String sourceBranch = "";
        String targetBranch = "";
        try {
            Map<String, String> prInfo = gitPlatformService.getPullRequestInfo(
                    coords.organization(), coords.project(), coords.repository(), job.getPrId());
            sourceBranch = prInfo.getOrDefault("sourceBranch", "");
            targetBranch = prInfo.getOrDefault("destinationBranch", "");
        } catch (Exception e) {
            LOG.warnf("Could not fetch PR info for job %s: %s", jobId, e.getMessage());
        }

        String rawDiff = "";
        try {
            rawDiff = gitPlatformService.getPullRequestDiff(
                    coords.organization(), coords.project(), coords.repository(), job.getPrId());
        } catch (Exception e) {
            LOG.warnf("Could not fetch PR diff for job %s: %s", jobId, e.getMessage());
            return Response.status(503).entity(Map.of("error", "SCM diff unavailable: " + e.getMessage())).build();
        }

        JobDiffResponse diffResponse = buildDiffResponse(sourceBranch, targetBranch, rawDiff);
        return Response.ok(diffResponse).build();
    }

    // ── Repo coordinate resolution ────────────────────────────────────────

    /**
     * Resolves repository coordinates (org / project / repo) for a job by examining
     * its request payload in priority order. Falls back to parsing the prUrl as a
     * last resort if no request payload carries a repoUrl.
     */
    private static RepoCoordinates resolveCoords(JobRecord job) {
        String repoUrl = null;

        if (job.getRequest() != null && job.getRequest().repoUrl() != null)
            repoUrl = job.getRequest().repoUrl();
        else if (job.getFixPrRequest() != null && job.getFixPrRequest().repoUrl() != null)
            repoUrl = job.getFixPrRequest().repoUrl();
        else if (job.getReviewRequest() != null && job.getReviewRequest().repoUrl() != null)
            repoUrl = job.getReviewRequest().repoUrl();
        else if (job.getHookRequest() != null && job.getHookRequest().repoUrl() != null)
            repoUrl = job.getHookRequest().repoUrl();
        else if (job.getGenerateTestsRequest() != null && job.getGenerateTestsRequest().repoUrl() != null)
            repoUrl = job.getGenerateTestsRequest().repoUrl();
        else if (job.getGenerateDocsRequest() != null && job.getGenerateDocsRequest().repoUrl() != null)
            repoUrl = job.getGenerateDocsRequest().repoUrl();

        if (repoUrl != null && !repoUrl.isBlank()) {
            return RepoCoordinates.parse(repoUrl);
        }

        // Last resort: derive from prUrl (strip /pull-requests/... suffix)
        if (job.getPrUrl() != null && !job.getPrUrl().isBlank()) {
            String prUrl = job.getPrUrl().replaceAll("/pull-requests/.*$", "")
                                         .replaceAll("/pulls/.*$", "")
                                         .replaceAll("/-/merge_requests/.*$", "");
            return RepoCoordinates.parse(prUrl);
        }

        throw new IllegalStateException("No repository URL available on job " + job.getJobId());
    }

    // ── Diff parsing ──────────────────────────────────────────────────────

    private static final Pattern DIFF_HUNK_HEADER = Pattern.compile(
            "^@@\\s+-(\\d+)(?:,(\\d+))?\\s+\\+(\\d+)(?:,(\\d+))?\\s+@@(.*)$");

    private static JobDiffResponse buildDiffResponse(String sourceBranch, String targetBranch, String rawDiff) {
        List<DiffFileEntry> files = new ArrayList<>();

        if (rawDiff == null || rawDiff.isBlank()) {
            return new JobDiffResponse(sourceBranch, targetBranch, 0, 0, files);
        }

        String currentPath = null;
        String currentOldPath = null;
        List<DiffHunkEntry> currentHunks = null;
        List<DiffLineEntry> currentLines = null;
        String currentHunkHeader = null;
        int oldLineNo = 0;
        int newLineNo = 0;

        for (String raw : rawDiff.split("\n", -1)) {

            if (raw.startsWith("diff --git ")) {
                flushHunk(currentHunks, currentLines, currentHunkHeader);
                flushFile(files, currentPath, currentOldPath, currentHunks);
                currentPath = null;
                currentOldPath = null;
                currentHunks = new ArrayList<>();
                currentLines = null;
                currentHunkHeader = null;
                oldLineNo = 0;
                newLineNo = 0;
                continue;
            }

            if (raw.startsWith("--- ")) {
                String path = raw.substring(4).trim();
                if (path.startsWith("a/")) path = path.substring(2);
                currentOldPath = "/dev/null".equals(path) ? null : path;
                continue;
            }

            if (raw.startsWith("+++ ")) {
                String path = raw.substring(4).trim();
                if (path.startsWith("b/")) path = path.substring(2);
                if (!"/dev/null".equals(path)) currentPath = path;
                continue;
            }

            Matcher m = DIFF_HUNK_HEADER.matcher(raw);
            if (m.find()) {
                flushHunk(currentHunks, currentLines, currentHunkHeader);
                oldLineNo = Integer.parseInt(m.group(1));
                newLineNo = Integer.parseInt(m.group(3));
                currentHunkHeader = raw.trim();
                currentLines = new ArrayList<>();
                continue;
            }

            if (currentLines == null) continue;

            if (raw.startsWith("+")) {
                currentLines.add(new DiffLineEntry("add", 0, newLineNo, raw.substring(1)));
                newLineNo++;
            } else if (raw.startsWith("-")) {
                currentLines.add(new DiffLineEntry("del", oldLineNo, 0, raw.substring(1)));
                oldLineNo++;
            } else if (raw.startsWith(" ")) {
                currentLines.add(new DiffLineEntry("ctx", oldLineNo, newLineNo, raw.substring(1)));
                oldLineNo++;
                newLineNo++;
            }
        }

        flushHunk(currentHunks, currentLines, currentHunkHeader);
        flushFile(files, currentPath, currentOldPath, currentHunks);

        int totalAdditions = files.stream().mapToInt(DiffFileEntry::additions).sum();
        int totalDeletions = files.stream().mapToInt(DiffFileEntry::deletions).sum();
        return new JobDiffResponse(sourceBranch, targetBranch, totalAdditions, totalDeletions, files);
    }

    private static void flushHunk(List<DiffHunkEntry> hunks, List<DiffLineEntry> lines, String header) {
        if (hunks != null && lines != null && !lines.isEmpty()) {
            hunks.add(new DiffHunkEntry(header != null ? header : "", List.copyOf(lines)));
        }
    }

    private static void flushFile(List<DiffFileEntry> files, String path, String oldPath,
                                   List<DiffHunkEntry> hunks) {
        if (hunks == null || hunks.isEmpty()) return;
        String effectivePath = path != null ? path : (oldPath != null ? oldPath : "unknown");
        String status = path == null ? "removed" : (oldPath == null ? "added" : "modified");
        int additions = 0;
        int deletions = 0;
        for (DiffHunkEntry hunk : hunks) {
            for (DiffLineEntry line : hunk.lines()) {
                if ("add".equals(line.type())) additions++;
                else if ("del".equals(line.type())) deletions++;
            }
        }
        files.add(new DiffFileEntry(effectivePath, status, additions, deletions, List.copyOf(hunks)));
    }
}
