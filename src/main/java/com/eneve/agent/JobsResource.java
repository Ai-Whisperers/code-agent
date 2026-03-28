package com.eneve.agent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.eneve.agent.agent.JobQueue;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.audit.AuditService;
import com.eneve.agent.audit.AuditStore;
import com.eneve.agent.model.DiffFileEntry;
import com.eneve.agent.model.DiffHunkEntry;
import com.eneve.agent.model.DiffLineEntry;
import com.eneve.agent.model.EvidenceEntry;
import com.eneve.agent.model.JobDiffResponse;
import com.eneve.agent.model.JobEvidenceResponse;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobReviewResponse;
import com.eneve.agent.model.JobStatus;
import com.eneve.agent.model.JobStatusResponse;
import com.eneve.agent.model.JobType;
import com.eneve.agent.model.RepoCoordinates;
import com.eneve.agent.model.ReviewCommentEntry;
import com.eneve.agent.model.ReviewPrRequest;
import com.eneve.agent.scm.AgentComment;
import com.eneve.agent.scm.GitPlatformService;
import com.eneve.agent.scytale.ScytaleService;
import com.eneve.agent.settings.SettingsService;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import io.quarkus.security.Authenticated;
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

@RequestScoped
@Path("/jobs")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Jobs", description = "Query and manage agent jobs")
public class JobsResource {

    private static final Logger LOG = Logger.getLogger(JobsResource.class);

    @Inject
    JobStore jobStore;

    @Inject
    GitPlatformService gitPlatformService;

    @Inject
    AuditStore auditStore;

    @Inject
    AuditService auditService;

    @Inject
    JobQueue jobQueue;

    @Inject
    SettingsService settings;

    @Inject
    ScytaleService scytaleService;

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

    // ── Review endpoints ─────────────────────────────────────────────────

    @GET
    @Path("/{jobId}/review")
    @Operation(operationId = "getJobReview", summary = "Get PR review for a job",
               description = "Returns the bot review summary and inline comments for the PR. "
                           + "Also surfaces the linked REVIEW job status for polling.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Review data"),
            @APIResponse(responseCode = "404", description = "Job not found or has no PR")
    })
    public Response getJobReview(@PathParam("jobId") String jobId) {
        var jobOpt = jobStore.get(jobId);
        if (jobOpt.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "Job not found: " + jobId)).build();
        }
        JobRecord job = jobOpt.get();

        if (job.getPrId() == null || job.getPrId().isBlank()) {
            return Response.status(404).entity(Map.of("error", "Job has no associated pull request")).build();
        }

        // Find the most recent REVIEW job for this PR
        String reviewJobId = null;
        String reviewJobStatus = null;
        String reviewSummary = null;
        Instant reviewedAt = null;

        List<JobRecord> relatedJobs = jobStore.findByPrId(job.getPrId());
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

        // Fetch inline comments from SCM
        List<ReviewCommentEntry> comments = new ArrayList<>();
        try {
            RepoCoordinates coords = resolveCoords(job);
            List<AgentComment> agentComments = gitPlatformService.getAgentPrComments(
                    coords.organization(), coords.project(), coords.repository(), job.getPrId());
            for (AgentComment c : agentComments) {
                comments.add(new ReviewCommentEntry(c.filePath(), c.line(), c.content()));
            }
        } catch (Exception e) {
            LOG.warnf("Could not fetch PR comments for job %s: %s", jobId, e.getMessage());
        }

        return Response.ok(new JobReviewResponse(reviewJobId, reviewJobStatus,
                reviewSummary, reviewedAt, comments)).build();
    }

    @POST
    @Path("/{jobId}/request-review")
    @RolesAllowed({"app_developer", "app_admin"})
    @Operation(operationId = "requestReview", summary = "Request a bot review",
               description = "Submits a new REVIEW job for the PR linked to this job. "
                           + "Returns 409 if a review is already running or pending.")
    @APIResponses({
            @APIResponse(responseCode = "202", description = "Review job submitted"),
            @APIResponse(responseCode = "404", description = "Job not found or has no PR"),
            @APIResponse(responseCode = "409", description = "Review already running")
    })
    public Response requestReview(@PathParam("jobId") String jobId) {
        var jobOpt = jobStore.get(jobId);
        if (jobOpt.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "Job not found: " + jobId)).build();
        }
        JobRecord job = jobOpt.get();

        if (job.getPrId() == null || job.getPrId().isBlank()) {
            return Response.status(404).entity(Map.of("error", "Job has no associated pull request")).build();
        }

        // Guard: don't create duplicate review if one is already running/pending
        List<JobRecord> relatedJobs = jobStore.findByPrId(job.getPrId());
        boolean alreadyRunning = relatedJobs.stream()
                .filter(j -> j.getJobType() == JobType.REVIEW)
                .anyMatch(j -> j.getStatus() == JobStatus.RUNNING || j.getStatus() == JobStatus.PENDING
                             || j.getStatus() == JobStatus.QUEUED);
        if (alreadyRunning) {
            return Response.status(409).entity(Map.of("error", "A review is already running for this PR")).build();
        }

        String repoUrl;
        try {
            RepoCoordinates coords = resolveCoords(job);
            repoUrl = buildRepoUrl(coords, job);
        } catch (Exception e) {
            return Response.status(404).entity(Map.of("error", "Cannot resolve repository: " + e.getMessage())).build();
        }

        String jiraKey = extractJiraKey(job);
        ReviewPrRequest reviewRequest = new ReviewPrRequest(
                repoUrl, job.getPrId(), null, jiraKey, null, null, null, null, null, null);

        String reviewJobId = UUID.randomUUID().toString();
        JobRecord reviewJob = new JobRecord(reviewJobId, reviewRequest);
        jobStore.put(reviewJob);
        if (!jobQueue.submit(reviewJob)) {
            return Response.status(429).entity(Map.of("error", "Job queue is full")).build();
        }

        auditService.log("JOBS", "REVIEW_REQUESTED", "job", jobId,
                Map.of("reviewJobId", reviewJobId));

        return Response.accepted(Map.of("reviewJobId", reviewJobId)).build();
    }

    // ── Evidence endpoint ─────────────────────────────────────────────────

    @GET
    @Path("/{jobId}/evidence")
    @Operation(operationId = "getJobEvidence", summary = "Get SOC II evidence for a job",
               description = "Returns compliance checks derived from the immutable audit log "
                           + "and the full audit trail for the job.")
    @APIResponse(responseCode = "200", description = "Evidence data")
    @APIResponse(responseCode = "404", description = "Job not found")
    public Response getJobEvidence(@PathParam("jobId") String jobId) {
        var jobOpt = jobStore.get(jobId);
        if (jobOpt.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "Job not found: " + jobId)).build();
        }
        JobRecord job = jobOpt.get();

        // Audit trail (chronological, immutable)
        var rawAuditEntries = auditStore.findByResourceId(jobId, 100);
        List<EvidenceEntry> auditTrail = rawAuditEntries.stream()
                .map(e -> new EvidenceEntry(e.occurredAt(), e.actor(), e.action(), e.detail()))
                .toList();

        // Determine if SOC II compliance applies
        String bugTypesStr = settings.get("soc2.bug-issue-types", "Bug,Defect");
        List<String> bugTypes = Arrays.asList(bugTypesStr.split("\\s*,\\s*"));
        String issueType = job.getJiraIssueType();
        boolean complianceApplicable = issueType != null
                && bugTypes.stream().anyMatch(t -> t.equalsIgnoreCase(issueType));

        // Linked REVIEW job
        String reviewJobId = null;
        String reviewJobStatus = null;
        if (job.getPrId() != null) {
            JobRecord reviewJob = jobStore.findByPrId(job.getPrId()).stream()
                    .filter(j -> j.getJobType() == JobType.REVIEW)
                    .findFirst().orElse(null);
            if (reviewJob != null) {
                reviewJobId = reviewJob.getJobId();
                reviewJobStatus = reviewJob.getStatus().name();
            }
        }

        // Derive branches
        String sourceBranchRaw = null;
        String targetBranchRaw = null;
        if (job.getRequest() != null) {
            sourceBranchRaw = job.getRequest().branchName();
            targetBranchRaw = job.getRequest().targetBranchOrDefault();
        } else if (job.getHookRequest() != null) {
            sourceBranchRaw = job.getHookRequest().branchName();
            targetBranchRaw = job.getHookRequest().targetBranch();
        }
        final String sourceBranch = sourceBranchRaw;
        final String targetBranch = targetBranchRaw;

        String jiraKey = extractJiraKey(job);

        // Build compliance checklist from audit events (not mutable status)
        List<JobEvidenceResponse.ComplianceCheck> checks = new ArrayList<>();
        if (complianceApplicable) {
            boolean prCreated       = hasAuditEvent(rawAuditEntries, "PR_CREATED");
            boolean reviewCompleted = hasAuditEvent(rawAuditEntries, "REVIEW_COMPLETED");
            boolean humanApproval   = hasAuditEvent(rawAuditEntries, "JOB_APPROVED");
            boolean merged          = hasAuditEvent(rawAuditEntries, "MERGE_COMPLETED");
            boolean slaMet          = hasAuditEvent(rawAuditEntries, "SLA_MET");
            boolean slaMissed       = hasAuditEvent(rawAuditEntries, "SLA_MISSED")
                                   || hasAuditEvent(rawAuditEntries, "SLA_OVERDUE");
            boolean scytaleUploaded = hasAuditEvent(rawAuditEntries, "SOC2_EVIDENCE_UPLOADED");

            String protectedBranches = settings.get("soc2.protected-branches", "develop,main,master,production");
            boolean targetProtected = targetBranch != null
                    && Arrays.stream(protectedBranches.split("\\s*,\\s*"))
                             .anyMatch(b -> b.equalsIgnoreCase(targetBranch));

            boolean promotionTracked = job.getPromotionJobId() != null
                    || (targetBranch != null && targetBranch.equalsIgnoreCase(
                            settings.get("soc2.production-branch", "main")));

            checks.add(new JobEvidenceResponse.ComplianceCheck(
                    "Linked Bug ticket", jiraKey != null,
                    jiraKey != null ? jiraKey : "No Jira key found"));
            checks.add(new JobEvidenceResponse.ComplianceCheck(
                    "PR raised (not direct push)", prCreated || job.getPrUrl() != null,
                    job.getPrUrl() != null ? job.getPrUrl() : "No PR URL recorded"));
            checks.add(new JobEvidenceResponse.ComplianceCheck(
                    "Bot code review completed",
                    reviewCompleted || "SUCCESS".equals(reviewJobStatus),
                    reviewJobId != null ? "Review job: " + reviewJobId : "No review job found"));
            checks.add(new JobEvidenceResponse.ComplianceCheck(
                    "Human approval obtained", humanApproval,
                    humanApproval ? "Approval recorded in audit log" : "No approval event found"));
            checks.add(new JobEvidenceResponse.ComplianceCheck(
                    "Merged to target branch", merged,
                    merged ? "Merge event in audit log" : "Not yet merged"));
            checks.add(new JobEvidenceResponse.ComplianceCheck(
                    "Target branch is protected", targetProtected,
                    targetBranch != null ? targetBranch : "Target branch unknown"));
            checks.add(new JobEvidenceResponse.ComplianceCheck(
                    "Production promotion tracked", promotionTracked,
                    job.getPromotionJobId() != null ? "Promotion job: " + job.getPromotionJobId() : "Merged directly to production"));
            checks.add(new JobEvidenceResponse.ComplianceCheck(
                    "SLA compliance", slaMet && !slaMissed,
                    slaMet ? "SLA met" : (slaMissed ? "SLA missed or overdue" : "SLA in progress")));
            checks.add(new JobEvidenceResponse.ComplianceCheck(
                    "Full audit trail present", !auditTrail.isEmpty(),
                    auditTrail.size() + " audit events recorded"));
            checks.add(new JobEvidenceResponse.ComplianceCheck(
                    "SOC II evidence uploaded to Scytale", scytaleUploaded,
                    job.getScytaleEvidenceRef() != null ? "Ref: " + job.getScytaleEvidenceRef() : "Not yet uploaded"));
        }

        boolean scytaleEnabled = !settings.get("scytale.api.key", "").isBlank();

        return Response.ok(new JobEvidenceResponse(
                job.getJobId(), job.getJobType(), job.getPrUrl(),
                sourceBranch, targetBranch,
                job.getCreatedAt(), null,
                jiraKey, issueType,
                reviewJobId, reviewJobStatus,
                job.getPromotionJobId(),
                complianceApplicable, checks, auditTrail,
                job.getScytaleEvidenceRef(), scytaleEnabled
        )).build();
    }

    // ── Scytale upload endpoint ───────────────────────────────────────────

    @POST
    @Path("/{jobId}/evidence/upload-scytale")
    @RolesAllowed({"app_developer", "app_admin"})
    @Operation(operationId = "uploadScytaleEvidence", summary = "Upload SOC II evidence to Scytale",
               description = "Manually triggers evidence upload to Scytale for this job's compliance record.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Upload succeeded"),
            @APIResponse(responseCode = "404", description = "Job not found"),
            @APIResponse(responseCode = "503", description = "Scytale not configured or upload failed")
    })
    public Response uploadScytaleEvidence(@PathParam("jobId") String jobId) {
        var jobOpt = jobStore.get(jobId);
        if (jobOpt.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "Job not found: " + jobId)).build();
        }
        if (settings.get("scytale.api.key", "").isBlank()) {
            return Response.status(503).entity(Map.of("error", "Scytale integration not configured")).build();
        }
        JobRecord job = jobOpt.get();

        // Build lightweight compliance-check and audit-trail maps for the payload
        List<com.eneve.agent.audit.AuditEntry> rawEntries = auditStore.findByResourceId(jobId, 200);
        List<java.util.Map<String, Object>> checksForPayload = List.of(
                java.util.Map.of("name", "Bot code review completed",
                        "passed", hasAuditEvent(rawEntries, "REVIEW_COMPLETED")),
                java.util.Map.of("name", "Human approval obtained",
                        "passed", hasAuditEvent(rawEntries, "JOB_APPROVED")),
                java.util.Map.of("name", "Merged to target branch",
                        "passed", hasAuditEvent(rawEntries, "MERGE_COMPLETED"))
        );
        List<java.util.Map<String, Object>> auditPayload = rawEntries.stream()
                .map(e -> java.util.Map.<String, Object>of(
                        "timestamp", e.occurredAt().toString(),
                        "actor",     e.actor(),
                        "action",    e.action(),
                        "detail",    e.detail() != null ? e.detail() : ""))
                .toList();

        ScytaleService.ScytaleUploadResult result = scytaleService.upload(job, checksForPayload, auditPayload);

        if (result.success()) {
            job.setScytaleEvidenceRef(result.ref());
            job.setScytaleUploadedAt(Instant.now());
            jobStore.update(job);
            auditService.log("SOC2", "SOC2_EVIDENCE_UPLOADED", "job", jobId,
                    Map.of("scytaleRef", result.ref()));
            return Response.ok(Map.of("ref", result.ref())).build();
        } else {
            auditService.log("SOC2", "SOC2_EVIDENCE_UPLOAD_FAILED", "job", jobId,
                    Map.of("error", result.errorMessage() != null ? result.errorMessage() : "unknown"));
            return Response.status(503)
                    .entity(Map.of("error", result.errorMessage() != null ? result.errorMessage() : "Upload failed"))
                    .build();
        }
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

    // ── Private helpers ───────────────────────────────────────────────────

    private static boolean hasAuditEvent(List<com.eneve.agent.audit.AuditEntry> entries, String action) {
        return entries.stream().anyMatch(e -> action.equals(e.action()));
    }

    private static String extractJiraKey(JobRecord job) {
        if (job.getRequest() != null) return job.getRequest().jiraKey();
        if (job.getReviewRequest() != null) return job.getReviewRequest().jiraKey();
        if (job.getFixPrRequest() != null) return job.getFixPrRequest().jiraKey();
        return null;
    }

    private static String buildRepoUrl(RepoCoordinates coords, JobRecord job) {
        if (job.getRequest() != null && job.getRequest().repoUrl() != null)
            return job.getRequest().repoUrl();
        if (job.getFixPrRequest() != null && job.getFixPrRequest().repoUrl() != null)
            return job.getFixPrRequest().repoUrl();
        if (job.getReviewRequest() != null && job.getReviewRequest().repoUrl() != null)
            return job.getReviewRequest().repoUrl();
        // Reconstruct a reasonable URL from coords as fallback
        return "https://bitbucket.org/" + coords.organization() + "/" + coords.repository();
    }
}
