package com.eneve.agent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.eneve.agent.agent.JobQueue;
import com.eneve.agent.agent.model.CommentFeedbackEntry;
import com.eneve.agent.agent.store.CommentFeedbackStore;
import com.eneve.agent.agent.store.CommentStore;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.audit.AuditService;
import com.eneve.agent.audit.AuditStore;
import com.eneve.agent.diff.JobDiffParser;
import com.eneve.agent.model.EvidenceEntry;
import com.eneve.agent.model.JobCommitsResponse;
import com.eneve.agent.model.JobDiffResponse;
import com.eneve.agent.model.JobEvidenceResponse;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobReviewResponse;
import com.eneve.agent.model.JobStatus;
import com.eneve.agent.model.JobStatusResponse;
import com.eneve.agent.agent.model.ChatEvent;
import com.eneve.agent.agent.service.CommentChatService;
import com.eneve.agent.model.CommentChatRequest;
import com.eneve.agent.model.JobType;
import com.eneve.agent.model.PromoteRequest;
import com.eneve.agent.model.RepoCoordinates;
import com.eneve.agent.model.ReviewCommentEntry;
import com.eneve.agent.model.ReviewPrRequest;
import com.eneve.agent.jira.JiraService;
import com.eneve.agent.exception.JobConflictException;
import com.eneve.agent.exception.JobNotFoundException;
import com.eneve.agent.exception.JobQueueFullException;
import com.eneve.agent.scm.AgentComment;
import com.eneve.agent.scm.GitPlatformService;
import com.eneve.agent.scytale.ScytaleService;
import com.eneve.agent.settings.SettingsService;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class JobsService {

    private static final Logger LOG = Logger.getLogger(JobsService.class);

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

    @Inject
    CommentStore commentStore;

    @Inject
    CommentFeedbackStore commentFeedbackStore;

    @Inject
    CommentChatService commentChatService;

    @Inject
    JiraService jiraService;

    @Inject
    Soc2Policy soc2Policy;

    // ── SOC2-specific exception (jobs only) ───────────────────────────────

    public static class Soc2GuardException extends RuntimeException {
        public Soc2GuardException(String message) { super(message); }
    }

    // ── Public service methods ────────────────────────────────────────────

    public List<JobStatusResponse> listJobs(String statusParam, String jobTypeParam, int limit, int page) {
        JobStatus status = null;
        if (statusParam != null && !statusParam.isBlank()) {
            try {
                status = JobStatus.valueOf(statusParam.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid status: " + statusParam
                        + ". Must be one of: PENDING, QUEUED, RUNNING, SUCCESS, FAILED, AWAITING_APPROVAL");
            }
        }

        JobType jobType = null;
        if (jobTypeParam != null && !jobTypeParam.isBlank()) {
            try {
                jobType = JobType.valueOf(jobTypeParam.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid jobType: " + jobTypeParam);
            }
        }

        int safeLimit = Math.min(Math.max(1, limit), 200);
        int offset = Math.max(0, page) * safeLimit;

        return jobStore.search(status, jobType, safeLimit, offset);
    }

    public JobDiffResponse getJobDiff(String jobId) {
        JobRecord job = requireJob(jobId);
        requirePrId(job, jobId);

        RepoCoordinates coords = resolveCoords(job);

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

        String rawDiff;
        try {
            rawDiff = gitPlatformService.getPullRequestDiff(
                    coords.organization(), coords.project(), coords.repository(), job.getPrId());
        } catch (Exception e) {
            LOG.warnf("Could not fetch PR diff for job %s: %s", jobId, e.getMessage());
            throw new RuntimeException("SCM diff unavailable: " + e.getMessage(), e);
        }

        return JobDiffParser.parse(sourceBranch, targetBranch, rawDiff);
    }

    public JobCommitsResponse getJobCommits(String jobId) {
        JobRecord job = requireJob(jobId);
        requirePrId(job, jobId);

        RepoCoordinates coords = resolveCoords(job);

        try {
            var commits = gitPlatformService.getPrCommits(
                    coords.organization(), coords.project(), coords.repository(), job.getPrId());
            return new JobCommitsResponse(commits);
        } catch (Exception e) {
            LOG.warnf("Could not fetch commits for job %s: %s", jobId, e.getMessage());
            return new JobCommitsResponse(List.of());
        }
    }

    public JobDiffResponse getCommitDiff(String jobId, String sha) {
        JobRecord job = requireJob(jobId);
        requirePrId(job, jobId);

        RepoCoordinates coords = resolveCoords(job);

        String rawDiff = gitPlatformService.getCommitDiff(
                coords.organization(), coords.project(), coords.repository(), sha);
        return JobDiffParser.parse(sha, "parent", rawDiff);
    }

    public JobReviewResponse getJobReview(String jobId) {
        JobRecord job = requireJob(jobId);
        requirePrId(job, jobId);

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

        List<ReviewCommentEntry> comments = new ArrayList<>();
        try {
            RepoCoordinates coords = resolveCoords(job);
            List<AgentComment> agentComments = gitPlatformService.getAgentPrComments(
                    coords.organization(), coords.project(), coords.repository(), job.getPrId());

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
            LOG.warnf("Could not fetch PR comments for job %s: %s", jobId, e.getMessage());
        }

        return new JobReviewResponse(reviewJobId, reviewJobStatus, reviewSummary, reviewedAt, comments);
    }

    public String requestReview(String jobId) {
        JobRecord job = requireJob(jobId);
        requirePrId(job, jobId);

        List<JobRecord> relatedJobs = jobStore.findByPrId(job.getPrId());
        boolean alreadyRunning = relatedJobs.stream()
                .filter(j -> j.getJobType() == JobType.REVIEW)
                .anyMatch(j -> j.getStatus() == JobStatus.RUNNING || j.getStatus() == JobStatus.PENDING
                             || j.getStatus() == JobStatus.QUEUED);
        if (alreadyRunning) {
            throw new JobConflictException("A review is already running for this PR");
        }

        RepoCoordinates coords = resolveCoords(job);
        String repoUrl = buildRepoUrl(coords, job);

        String jiraKey = extractJiraKey(job);
        ReviewPrRequest reviewRequest = new ReviewPrRequest(
                repoUrl, job.getPrId(), null, jiraKey, null, null, null, null, null, null);

        String reviewJobId = UUID.randomUUID().toString();
        JobRecord reviewJob = new JobRecord(reviewJobId, reviewRequest);
        jobStore.put(reviewJob);
        if (!jobQueue.submit(reviewJob)) {
            throw new JobQueueFullException("Job queue is full");
        }

        auditService.log("JOBS", "REVIEW_REQUESTED", "job", jobId,
                Map.of("reviewJobId", reviewJobId));

        return reviewJobId;
    }

    public String requestFixPr(String jobId) {
        JobRecord job = requireJob(jobId);
        requirePrId(job, jobId);

        List<JobRecord> relatedJobs = jobStore.findByPrId(job.getPrId());
        boolean alreadyRunning = relatedJobs.stream()
                .filter(j -> j.getJobType() == JobType.FIX_PR)
                .anyMatch(j -> j.getStatus() == JobStatus.RUNNING || j.getStatus() == JobStatus.PENDING
                             || j.getStatus() == JobStatus.QUEUED);
        if (alreadyRunning) {
            throw new JobConflictException("A fix-PR job is already running for this PR");
        }

        RepoCoordinates coords = resolveCoords(job);
        String repoUrl = buildRepoUrl(coords, job);

        String jiraKey = extractJiraKey(job);
        com.eneve.agent.model.FixPrRequest fixPrRequest = new com.eneve.agent.model.FixPrRequest(
                repoUrl, job.getPrId(), jiraKey, null, null, null, null);

        String fixPrJobId = UUID.randomUUID().toString();
        JobRecord fixPrJob = new JobRecord(fixPrJobId, fixPrRequest);
        jobStore.put(fixPrJob);
        if (!jobQueue.submit(fixPrJob)) {
            throw new JobQueueFullException("Job queue is full");
        }

        auditService.log("JOBS", "FIX_PR_REQUESTED", "job", jobId,
                Map.of("fixPrJobId", fixPrJobId));

        return fixPrJobId;
    }

    public String requestFixComment(String jobId, long commentId, String filePath, int line) {
        JobRecord job = requireJob(jobId);
        requirePrId(job, jobId);

        RepoCoordinates coords = resolveCoords(job);
        String repoUrl = buildRepoUrl(coords, job);

        com.eneve.agent.model.ReplyCommentRequest replyRequest = new com.eneve.agent.model.ReplyCommentRequest(
                repoUrl, job.getPrId(), commentId, "Please fix this issue.",
                filePath != null ? filePath : "", line);

        String fixCommentJobId = UUID.randomUUID().toString();
        JobRecord fixCommentJob = new JobRecord(fixCommentJobId, replyRequest, JobType.FIX_COMMENT);
        jobStore.put(fixCommentJob);
        if (!jobQueue.submit(fixCommentJob)) {
            throw new JobQueueFullException("Job queue is full");
        }

        auditService.log("JOBS", "FIX_COMMENT_REQUESTED", "job", jobId,
                Map.of("fixCommentJobId", fixCommentJobId, "commentId", String.valueOf(commentId)));

        return fixCommentJobId;
    }

    public void resolveComment(String jobId, long commentId) {
        JobRecord job = requireJob(jobId);

        try {
            RepoCoordinates coords = resolveCoords(job);
            gitPlatformService.resolveComment(coords.organization(), coords.project(), coords.repository(),
                    job.getPrId(), commentId);
        } catch (Exception e) {
            LOG.warnf("SCM resolveComment failed for comment %d: %s", commentId, e.getMessage());
        }
        commentStore.markResolved(commentId, "API User");

        auditService.log("JOBS", "COMMENT_RESOLVED", "job", jobId,
                Map.of("commentId", String.valueOf(commentId)));
    }

    public void markFalsePositive(String jobId, long commentId) {
        JobRecord job = requireJob(jobId);

        var ctx = commentStore.find(commentId);
        String category = ctx.map(c -> c.category()).orElse(null);
        String findingText = ctx.map(c -> c.findingText()).orElse(null);
        String prId = job.getPrId() != null ? job.getPrId() : ctx.map(c -> c.prId()).orElse("");
        String workspace;
        String repoSlug;
        try {
            RepoCoordinates coords = resolveCoords(job);
            workspace = coords.organization();
            repoSlug = coords.repository();
        } catch (Exception e) {
            workspace = ctx.map(c -> c.organization()).orElse("");
            repoSlug = ctx.map(c -> c.repository()).orElse("");
        }

        CommentFeedbackEntry feedback = CommentFeedbackEntry.falsePositive(
                commentId, prId, workspace, repoSlug, category, findingText, "API User");
        commentFeedbackStore.save(feedback);

        commentStore.markResolved(commentId, "API User");

        try {
            RepoCoordinates coords = resolveCoords(job);
            gitPlatformService.resolveComment(coords.organization(), coords.project(), coords.repository(),
                    job.getPrId(), commentId);
            gitPlatformService.replyToComment(coords.organization(), coords.project(), coords.repository(),
                    job.getPrId(), commentId,
                    "This finding has been marked as a false positive and will be suppressed in future reviews.");
        } catch (Exception e) {
            LOG.warnf("SCM false-positive actions failed for comment %d: %s", commentId, e.getMessage());
        }

        auditService.log("JOBS", "COMMENT_FALSE_POSITIVE", "job", jobId,
                Map.of("commentId", String.valueOf(commentId)));
    }

    public long replyToComment(String jobId, long commentId, String message) {
        JobRecord job = requireJob(jobId);

        RepoCoordinates coords = resolveCoords(job);
        long replyId = gitPlatformService.replyToComment(coords.organization(), coords.project(), coords.repository(),
                job.getPrId(), commentId, message);
        auditService.log("JOBS", "COMMENT_REPLY_POSTED", "job", jobId,
                Map.of("commentId", String.valueOf(commentId), "replyId", String.valueOf(replyId)));
        return replyId;
    }

    public JobEvidenceResponse getJobEvidence(String jobId) {
        JobRecord job = requireJob(jobId);

        var rawAuditEntries = auditStore.findByResourceId(jobId, 100);
        List<EvidenceEntry> auditTrail = rawAuditEntries.stream()
                .map(e -> new EvidenceEntry(e.occurredAt(), e.actor(), e.action(), e.detail()))
                .toList();

        String issueType = job.getJiraIssueType();
        boolean complianceApplicable = soc2Policy.isBugType(issueType);

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

            boolean targetProtected = soc2Policy.isProtected(targetBranch);

            boolean promotionTracked = job.getPromotionJobId() != null
                    || (targetBranch != null && targetBranch.equalsIgnoreCase(soc2Policy.productionBranch()));

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

        return new JobEvidenceResponse(
                job.getJobId(), job.getJobType(), job.getPrUrl(),
                sourceBranch, targetBranch,
                job.getCreatedAt(), null,
                jiraKey, issueType,
                reviewJobId, reviewJobStatus,
                job.getPromotionJobId(),
                complianceApplicable, checks, auditTrail,
                job.getScytaleEvidenceRef(), scytaleEnabled);
    }

    public String uploadScytaleEvidence(String jobId) {
        JobRecord job = requireJob(jobId);

        if (settings.get("scytale.api.key", "").isBlank()) {
            throw new RuntimeException("Scytale integration not configured");
        }

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
            return result.ref();
        } else {
            auditService.log("SOC2", "SOC2_EVIDENCE_UPLOAD_FAILED", "job", jobId,
                    Map.of("error", result.errorMessage() != null ? result.errorMessage() : "unknown"));
            throw new RuntimeException(result.errorMessage() != null ? result.errorMessage() : "Upload failed");
        }
    }

    public void approveJob(String jobId) {
        JobRecord job = requireJob(jobId);

        if (job.getStatus() != JobStatus.AWAITING_APPROVAL) {
            throw new JobConflictException(
                    "Job is not awaiting approval. Current status: " + job.getStatus());
        }

        String target           = resolveTargetBranch(job);
        String issueType        = job.getJiraIssueType();
        String productionBranch = soc2Policy.productionBranch();

        if (soc2Policy.isBugType(issueType) && soc2Policy.isProtected(target)) {
            boolean reviewed = jobStore.findByPrId(job.getPrId()).stream()
                    .anyMatch(j -> j.getJobType() == JobType.REVIEW && j.getStatus() == JobStatus.SUCCESS);
            if (!reviewed) {
                String jiraKey = extractJobJiraKey(job);
                auditService.log("SOC2", "APPROVAL_BLOCKED_SOC2", "job", jobId,
                        Map.of("reason", "no_bot_review",
                               "jiraKey", jiraKey != null ? jiraKey : "unknown",
                               "targetBranch", target != null ? target : "unknown"));
                throw new Soc2GuardException("SOC II CC8.1: A completed bot review is required before merging "
                        + (jiraKey != null ? jiraKey : "this job") + " to " + target);
            }
        }

        RepoCoordinates coords = resolveCoords(job);
        gitPlatformService.mergePullRequest(
                coords.organization(), coords.project(), coords.repository(), job.getPrId());
        job.setStatus(JobStatus.SUCCESS);
        jobStore.archive(job);
        auditService.log("JOBS", "JOB_APPROVED",    "job", jobId, Map.of());
        auditService.log("JOBS", "MERGE_COMPLETED", "job", jobId,
                Map.of("prId", job.getPrId() != null ? job.getPrId() : "unknown"));

        String jiraKey = extractJobJiraKey(job);
        boolean isMergeToProduction = target != null && target.equalsIgnoreCase(productionBranch);

        if (soc2Policy.isBugType(issueType)) {
            if (isMergeToProduction) {
                if (jiraKey != null && !jiraKey.isBlank()) {
                    final String key = jiraKey;
                    final String prodBranch = productionBranch;
                    Thread.ofVirtual().start(() -> {
                        try {
                            jiraService.transitionToDone(key);
                            jiraService.addComment(key,
                                    "Fix fully deployed to " + prodBranch
                                    + ". SOC2 remediation complete.");
                        } catch (Exception e) {
                            LOG.warnf("JIRA update on main merge failed (non-fatal): %s", e.getMessage());
                        }
                    });
                }
            } else {
                if (jiraKey != null && !jiraKey.isBlank()) {
                    final String key = jiraKey;
                    final String targetFinal = target;
                    final String prodBranch = productionBranch;
                    Thread.ofVirtual().start(() -> {
                        try {
                            jiraService.addComment(key,
                                    "Fix merged to " + (targetFinal != null ? targetFinal : "develop")
                                    + " branch. Promotion to " + prodBranch + " is in progress.");
                        } catch (Exception e) {
                            LOG.warnf("JIRA comment on develop merge failed (non-fatal): %s", e.getMessage());
                        }
                    });
                }
                if (target != null && soc2Policy.isProtected(productionBranch)) {
                    schedulePromotion(job, target, productionBranch, jobId);
                }
            }
        }
    }

    public Multi<ChatEvent> commentChat(String jobId, CommentChatRequest request) {
        return commentChatService.chatStream(jobId, request);
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private JobRecord requireJob(String jobId) {
        return jobStore.get(jobId)
                .orElseThrow(() -> new JobNotFoundException("Job not found: " + jobId));
    }

    private static void requirePrId(JobRecord job, String jobId) {
        if (job.getPrId() == null || job.getPrId().isBlank()) {
            throw new JobNotFoundException("Job has no associated pull request");
        }
    }

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

        if (job.getPrUrl() != null && !job.getPrUrl().isBlank()) {
            String prUrl = job.getPrUrl().replaceAll("/pull-requests/.*$", "")
                                         .replaceAll("/pulls/.*$", "")
                                         .replaceAll("/-/merge_requests/.*$", "");
            return RepoCoordinates.parse(prUrl);
        }

        throw new IllegalStateException("No repository URL available on job " + job.getJobId());
    }

    private static String buildRepoUrl(RepoCoordinates coords, JobRecord job) {
        if (job.getRequest() != null && job.getRequest().repoUrl() != null)
            return job.getRequest().repoUrl();
        if (job.getFixPrRequest() != null && job.getFixPrRequest().repoUrl() != null)
            return job.getFixPrRequest().repoUrl();
        if (job.getReviewRequest() != null && job.getReviewRequest().repoUrl() != null)
            return job.getReviewRequest().repoUrl();
        return "https://bitbucket.org/" + coords.organization() + "/" + coords.repository();
    }

    private static String extractJiraKey(JobRecord job) {
        if (job.getRequest() != null) return job.getRequest().jiraKey();
        if (job.getReviewRequest() != null) return job.getReviewRequest().jiraKey();
        if (job.getFixPrRequest() != null) return job.getFixPrRequest().jiraKey();
        return null;
    }

    private static String extractJobJiraKey(JobRecord job) {
        if (job.getRequest() != null) return job.getRequest().jiraKey();
        if (job.getReviewRequest() != null) return job.getReviewRequest().jiraKey();
        if (job.getFixPrRequest() != null) return job.getFixPrRequest().jiraKey();
        if (job.getPromoteRequest() != null) return job.getPromoteRequest().jiraKey();
        return null;
    }

    private static String resolveTargetBranch(JobRecord job) {
        if (job.getRequest() != null) return job.getRequest().targetBranchOrDefault();
        if (job.getGenerateTestsRequest() != null) return job.getGenerateTestsRequest().targetBranchOrDefault();
        if (job.getGenerateDocsRequest() != null) return job.getGenerateDocsRequest().targetBranchOrDefault();
        if (job.getHookRequest() != null) return job.getHookRequest().targetBranch();
        return null;
    }

    private void schedulePromotion(JobRecord originalJob, String fromBranch, String toBranch, String originalJobId) {
        try {
            String repoUrl = null;
            if (originalJob.getRequest() != null) repoUrl = originalJob.getRequest().repoUrl();
            if (repoUrl == null && originalJob.getFixPrRequest() != null)
                repoUrl = originalJob.getFixPrRequest().repoUrl();
            if (repoUrl == null) {
                LOG.warnf("Cannot schedule promotion for job %s: no repoUrl", originalJobId);
                return;
            }

            String jiraKey = extractJobJiraKey(originalJob);
            String fixBranchName = originalJob.getFixBranchName();
            if (fixBranchName == null && originalJob.getRequest() != null) {
                fixBranchName = originalJob.getRequest().branchName();
            }

            PromoteRequest promoteRequest = new PromoteRequest(
                    repoUrl,
                    jiraKey != null ? jiraKey : "UNKNOWN",
                    fixBranchName != null ? fixBranchName : "",
                    originalJob.getPrId(),
                    toBranch,
                    originalJob.getAikidoIssueId());

            String promotionJobId = UUID.randomUUID().toString();
            JobRecord promoteJob = new JobRecord(promotionJobId, promoteRequest);
            promoteJob.setAikidoIssueId(originalJob.getAikidoIssueId());
            if (jiraKey != null) {
                promoteJob.setJiraIssueType(originalJob.getJiraIssueType());
                promoteJob.setJiraPriority(originalJob.getJiraPriority());
                promoteJob.setJiraCreatedAt(originalJob.getJiraCreatedAt());
            }

            jobStore.put(promoteJob);

            originalJob.setPromotionJobId(promotionJobId);
            jobStore.update(originalJob);

            boolean queued = jobQueue.submit(promoteJob);

            auditService.log("SOC2", "SOC2_PROMOTION_STARTED", "job", originalJobId,
                    Map.of("promotionJobId", promotionJobId,
                           "fromBranch", fromBranch,
                           "toBranch", toBranch,
                           "jiraKey", jiraKey != null ? jiraKey : "unknown",
                           "queued", String.valueOf(queued)));

            LOG.infof("SOC2 PROMOTE job %s created for %s: %s → %s (queued=%s)",
                    promotionJobId, originalJobId, fromBranch, toBranch, queued);
        } catch (Exception e) {
            LOG.warnf("Failed to schedule promotion for job %s: %s", originalJobId, e.getMessage());
        }
    }

    private static boolean hasAuditEvent(List<com.eneve.agent.audit.AuditEntry> entries, String action) {
        return entries.stream().anyMatch(e -> action.equals(e.action()));
    }

}
