package com.eneve.agent.model;

import java.time.Instant;

public class JobRecord {

    // ── Single unified payload (preferred) ───────────────────────────────────
    private final JobPayload payload;

    // ── PROMOTE job request (cherry-pick promotion to main) ──────────────────
    private final PromoteRequest promoteRequest;

    // ── SOC II / SLA fields (populated at submission time from Jira) ─────────
    // Kept volatile so they can be set after construction without synchronization.


    private final String jobId;
    private final RunFixRequest request;
    private final ReviewPrRequest reviewRequest;
    private final FixPrRequest fixPrRequest;
    private final ReplyCommentRequest replyRequest;
    private final HookJobRequest hookRequest;
    private final GenerateTestsRequest generateTestsRequest;
    private final GenerateDocsRequest generateDocsRequest;
    private final SyncConfluenceRequest syncConfluenceRequest;
    private final MetricsJobRequest metricsRequest;
    private final QualityReportJobRequest qualityReportRequest;
    private final JiraReviewRequest jiraReviewRequest;
    private final JobType jobType;
    private final Instant createdAt;
    private volatile JobStatus status;
    private volatile int priority;
    private volatile String summary;
    private volatile String errorMessage;
    private volatile String prUrl;
    private volatile int filesChanged;
    private volatile int linesChanged;
    private volatile String prId;
    private volatile String planId;
    private volatile String prAuthor;
    private volatile String workspace;
    private volatile String repoSlug;
    private volatile String workspacePath;
    private volatile JobCoverageData coverageData;

    // Jira metadata cached at submission time so approve() needs zero external calls
    private volatile String jiraIssueType;
    private volatile String jiraPriority;
    private volatile Instant jiraCreatedAt;

    // Set after a develop→main promotion PR/job is auto-created
    private volatile String promotionJobId;

    // Optional Aikido vulnerability issue ID (set via webhook or manual link)
    private volatile String aikidoIssueId;

    // Original fix branch name stored for cherry-pick promotion (develop → main)
    private volatile String fixBranchName;

    // Scytale evidence upload tracking
    private volatile String scytaleEvidenceRef;
    private volatile Instant scytaleUploadedAt;

    public JobRecord(String jobId, RunFixRequest request) {
        this.jobId = jobId;
        this.payload = request;
        this.request = request;
        this.reviewRequest = null;
        this.fixPrRequest = null;
        this.replyRequest = null;
        this.hookRequest = null;
        this.generateTestsRequest = null;
        this.generateDocsRequest = null;
        this.syncConfluenceRequest = null;
        this.metricsRequest = null;
        this.qualityReportRequest = null;
        this.jiraReviewRequest = null;
        this.promoteRequest = null;
        this.jobType = JobType.FIX;
        this.createdAt = Instant.now();
        this.status = JobStatus.PENDING;
        this.priority = JobType.FIX.defaultPriority();
    }

    public JobRecord(String jobId, ReviewPrRequest reviewRequest) {
        this.jobId = jobId;
        this.payload = reviewRequest;
        this.request = null;
        this.reviewRequest = reviewRequest;
        this.fixPrRequest = null;
        this.replyRequest = null;
        this.hookRequest = null;
        this.generateTestsRequest = null;
        this.generateDocsRequest = null;
        this.syncConfluenceRequest = null;
        this.metricsRequest = null;
        this.qualityReportRequest = null;
        this.jiraReviewRequest = null;
        this.promoteRequest = null;
        this.jobType = JobType.REVIEW;
        this.createdAt = Instant.now();
        this.status = JobStatus.PENDING;
        this.priority = JobType.REVIEW.defaultPriority();
    }

    public JobRecord(String jobId, FixPrRequest fixPrRequest) {
        this.jobId = jobId;
        this.payload = fixPrRequest;
        this.request = null;
        this.reviewRequest = null;
        this.fixPrRequest = fixPrRequest;
        this.replyRequest = null;
        this.hookRequest = null;
        this.generateTestsRequest = null;
        this.generateDocsRequest = null;
        this.syncConfluenceRequest = null;
        this.metricsRequest = null;
        this.qualityReportRequest = null;
        this.jiraReviewRequest = null;
        this.promoteRequest = null;
        this.jobType = JobType.FIX_PR;
        this.createdAt = Instant.now();
        this.status = JobStatus.PENDING;
        this.priority = JobType.FIX_PR.defaultPriority();
    }

    public JobRecord(String jobId, ReplyCommentRequest replyRequest) {
        this(jobId, replyRequest, JobType.REPLY);
    }

    public JobRecord(String jobId, ReplyCommentRequest replyRequest, JobType jobType) {
        this.jobId = jobId;
        this.payload = replyRequest;
        this.request = null;
        this.reviewRequest = null;
        this.fixPrRequest = null;
        this.replyRequest = replyRequest;
        this.hookRequest = null;
        this.generateTestsRequest = null;
        this.generateDocsRequest = null;
        this.syncConfluenceRequest = null;
        this.metricsRequest = null;
        this.qualityReportRequest = null;
        this.jiraReviewRequest = null;
        this.promoteRequest = null;
        this.jobType = jobType;
        this.createdAt = Instant.now();
        this.status = JobStatus.PENDING;
        this.priority = jobType.defaultPriority();
    }

    public JobRecord(String jobId, HookJobRequest hookRequest) {
        this.jobId = jobId;
        this.payload = hookRequest;
        this.request = null;
        this.reviewRequest = null;
        this.fixPrRequest = null;
        this.replyRequest = null;
        this.hookRequest = hookRequest;
        this.generateTestsRequest = null;
        this.generateDocsRequest = null;
        this.syncConfluenceRequest = null;
        this.metricsRequest = null;
        this.qualityReportRequest = null;
        this.jiraReviewRequest = null;
        this.promoteRequest = null;
        this.jobType = JobType.HOOK;
        this.createdAt = Instant.now();
        this.status = JobStatus.PENDING;
        this.priority = JobType.HOOK.defaultPriority();
    }

    public JobRecord(String jobId, GenerateTestsRequest generateTestsRequest) {
        this.jobId = jobId;
        this.payload = generateTestsRequest;
        this.request = null;
        this.reviewRequest = null;
        this.fixPrRequest = null;
        this.replyRequest = null;
        this.hookRequest = null;
        this.generateTestsRequest = generateTestsRequest;
        this.generateDocsRequest = null;
        this.syncConfluenceRequest = null;
        this.metricsRequest = null;
        this.qualityReportRequest = null;
        this.jiraReviewRequest = null;
        this.promoteRequest = null;
        this.jobType = JobType.GENERATE_TESTS;
        this.createdAt = Instant.now();
        this.status = JobStatus.PENDING;
        this.priority = JobType.GENERATE_TESTS.defaultPriority();
    }

    public JobRecord(String jobId, GenerateDocsRequest generateDocsRequest) {
        this.jobId = jobId;
        this.payload = generateDocsRequest;
        this.request = null;
        this.reviewRequest = null;
        this.fixPrRequest = null;
        this.replyRequest = null;
        this.hookRequest = null;
        this.generateTestsRequest = null;
        this.generateDocsRequest = generateDocsRequest;
        this.syncConfluenceRequest = null;
        this.metricsRequest = null;
        this.qualityReportRequest = null;
        this.jiraReviewRequest = null;
        this.promoteRequest = null;
        this.jobType = JobType.GENERATE_DOCS;
        this.createdAt = Instant.now();
        this.status = JobStatus.PENDING;
        this.priority = JobType.GENERATE_DOCS.defaultPriority();
    }

    public JobRecord(String jobId, SyncConfluenceRequest syncConfluenceRequest) {
        this.jobId = jobId;
        this.payload = syncConfluenceRequest;
        this.request = null;
        this.reviewRequest = null;
        this.fixPrRequest = null;
        this.replyRequest = null;
        this.hookRequest = null;
        this.generateTestsRequest = null;
        this.generateDocsRequest = null;
        this.syncConfluenceRequest = syncConfluenceRequest;
        this.metricsRequest = null;
        this.qualityReportRequest = null;
        this.jiraReviewRequest = null;
        this.promoteRequest = null;
        this.jobType = JobType.SYNC_CONFLUENCE;
        this.createdAt = Instant.now();
        this.status = JobStatus.PENDING;
        this.priority = JobType.SYNC_CONFLUENCE.defaultPriority();
    }

    public JobRecord(String jobId, MetricsJobRequest metricsRequest) {
        this.jobId = jobId;
        this.payload = metricsRequest;
        this.request = null;
        this.reviewRequest = null;
        this.fixPrRequest = null;
        this.replyRequest = null;
        this.hookRequest = null;
        this.generateTestsRequest = null;
        this.generateDocsRequest = null;
        this.syncConfluenceRequest = null;
        this.metricsRequest = metricsRequest;
        this.qualityReportRequest = null;
        this.jiraReviewRequest = null;
        this.promoteRequest = null;
        this.jobType = JobType.METRICS;
        this.createdAt = Instant.now();
        this.status = JobStatus.PENDING;
        this.priority = JobType.METRICS.defaultPriority();
    }

    public JobRecord(String jobId, QualityReportJobRequest qualityReportRequest) {
        this.jobId = jobId;
        this.payload = qualityReportRequest;
        this.request = null;
        this.reviewRequest = null;
        this.fixPrRequest = null;
        this.replyRequest = null;
        this.hookRequest = null;
        this.generateTestsRequest = null;
        this.generateDocsRequest = null;
        this.syncConfluenceRequest = null;
        this.metricsRequest = null;
        this.qualityReportRequest = qualityReportRequest;
        this.jiraReviewRequest = null;
        this.promoteRequest = null;
        this.jobType = JobType.QUALITY_REPORT;
        this.createdAt = Instant.now();
        this.status = JobStatus.PENDING;
        this.priority = JobType.QUALITY_REPORT.defaultPriority();
    }

    public JobRecord(String jobId, JiraReviewRequest jiraReviewRequest, JobType jobType) {
        this.jobId = jobId;
        this.payload = jiraReviewRequest;
        this.request = null;
        this.reviewRequest = null;
        this.fixPrRequest = null;
        this.replyRequest = null;
        this.hookRequest = null;
        this.generateTestsRequest = null;
        this.generateDocsRequest = null;
        this.syncConfluenceRequest = null;
        this.metricsRequest = null;
        this.qualityReportRequest = null;
        this.jiraReviewRequest = jiraReviewRequest;
        this.promoteRequest = null;
        this.jobType = jobType;
        this.createdAt = Instant.now();
        this.status = JobStatus.PENDING;
        this.priority = jobType.defaultPriority();
    }

    public JobRecord(String jobId, PromoteRequest promoteRequest) {
        this.jobId = jobId;
        this.payload = promoteRequest;
        this.request = null;
        this.reviewRequest = null;
        this.fixPrRequest = null;
        this.replyRequest = null;
        this.hookRequest = null;
        this.generateTestsRequest = null;
        this.generateDocsRequest = null;
        this.syncConfluenceRequest = null;
        this.metricsRequest = null;
        this.qualityReportRequest = null;
        this.jiraReviewRequest = null;
        this.promoteRequest = promoteRequest;
        this.jobType = JobType.PROMOTE;
        this.createdAt = Instant.now();
        this.status = JobStatus.PENDING;
        this.priority = JobType.PROMOTE.defaultPriority();
    }

    public JobRecord(String jobId, SelfAnalysisRequest selfAnalysisRequest) {
        this.jobId = jobId;
        this.payload = selfAnalysisRequest;
        this.request = null;
        this.reviewRequest = null;
        this.fixPrRequest = null;
        this.replyRequest = null;
        this.hookRequest = null;
        this.generateTestsRequest = null;
        this.generateDocsRequest = null;
        this.syncConfluenceRequest = null;
        this.metricsRequest = null;
        this.qualityReportRequest = null;
        this.jiraReviewRequest = null;
        this.promoteRequest = null;
        this.jobType = JobType.SELF_ANALYSIS;
        this.createdAt = Instant.now();
        this.status = JobStatus.PENDING;
        this.priority = JobType.SELF_ANALYSIS.defaultPriority();
    }

    public JobRecord(String jobId, GenerateArchitectureRequest request) {
        this.jobId = jobId;
        this.payload = request;
        this.request = null;
        this.reviewRequest = null;
        this.fixPrRequest = null;
        this.replyRequest = null;
        this.hookRequest = null;
        this.generateTestsRequest = null;
        this.generateDocsRequest = null;
        this.syncConfluenceRequest = null;
        this.metricsRequest = null;
        this.qualityReportRequest = null;
        this.jiraReviewRequest = null;
        this.promoteRequest = null;
        this.jobType = JobType.GENERATE_ARCHITECTURE;
        this.createdAt = Instant.now();
        this.status = JobStatus.PENDING;
        this.priority = JobType.GENERATE_ARCHITECTURE.defaultPriority();
    }

    public JobRecord(String jobId, GenerateCloudArchitectureRequest request) {
        this.jobId = jobId;
        this.payload = request;
        this.request = null;
        this.reviewRequest = null;
        this.fixPrRequest = null;
        this.replyRequest = null;
        this.hookRequest = null;
        this.generateTestsRequest = null;
        this.generateDocsRequest = null;
        this.syncConfluenceRequest = null;
        this.metricsRequest = null;
        this.qualityReportRequest = null;
        this.jiraReviewRequest = null;
        this.promoteRequest = null;
        this.jobType = JobType.GENERATE_CLOUD_ARCHITECTURE;
        this.createdAt = Instant.now();
        this.status = JobStatus.PENDING;
        this.priority = JobType.GENERATE_CLOUD_ARCHITECTURE.defaultPriority();
    }

    public JobRecord(String jobId, KnowledgeGraphRequest request) {
        this.jobId = jobId;
        this.payload = request;
        this.request = null;
        this.reviewRequest = null;
        this.fixPrRequest = null;
        this.replyRequest = null;
        this.hookRequest = null;
        this.generateTestsRequest = null;
        this.generateDocsRequest = null;
        this.syncConfluenceRequest = null;
        this.metricsRequest = null;
        this.qualityReportRequest = null;
        this.jiraReviewRequest = null;
        this.promoteRequest = null;
        this.jobType = JobType.KNOWLEDGE_GRAPH;
        this.createdAt = Instant.now();
        this.status = JobStatus.PENDING;
        this.priority = JobType.KNOWLEDGE_GRAPH.defaultPriority();
    }

    public JobRecord(String jobId, TechDebtRequest request) {
        this.jobId = jobId;
        this.payload = request;
        this.request = null;
        this.reviewRequest = null;
        this.fixPrRequest = null;
        this.replyRequest = null;
        this.hookRequest = null;
        this.generateTestsRequest = null;
        this.generateDocsRequest = null;
        this.syncConfluenceRequest = null;
        this.metricsRequest = null;
        this.qualityReportRequest = null;
        this.jiraReviewRequest = null;
        this.promoteRequest = null;
        this.jobType = JobType.TECH_DEBT;
        this.createdAt = Instant.now();
        this.status = JobStatus.PENDING;
        this.priority = JobType.TECH_DEBT.defaultPriority();
    }

    public String getJobId() { return jobId; }
    public JobType getJobType() { return jobType; }

    /** Returns the unified job payload. Prefer this over the individual typed getters. */
    public JobPayload getPayload() { return payload; }

    /** @deprecated Use {@link #getPayload()} with pattern matching instead. */
    @Deprecated public RunFixRequest getRequest() { return request; }
    /** @deprecated Use {@link #getPayload()} with pattern matching instead. */
    @Deprecated public ReviewPrRequest getReviewRequest() { return reviewRequest; }
    /** @deprecated Use {@link #getPayload()} with pattern matching instead. */
    @Deprecated public FixPrRequest getFixPrRequest() { return fixPrRequest; }
    /** @deprecated Use {@link #getPayload()} with pattern matching instead. */
    @Deprecated public ReplyCommentRequest getReplyRequest() { return replyRequest; }
    /** @deprecated Use {@link #getPayload()} with pattern matching instead. */
    @Deprecated public HookJobRequest getHookRequest() { return hookRequest; }
    /** @deprecated Use {@link #getPayload()} with pattern matching instead. */
    @Deprecated public GenerateTestsRequest getGenerateTestsRequest() { return generateTestsRequest; }
    /** @deprecated Use {@link #getPayload()} with pattern matching instead. */
    @Deprecated public GenerateDocsRequest getGenerateDocsRequest() { return generateDocsRequest; }
    /** @deprecated Use {@link #getPayload()} with pattern matching instead. */
    @Deprecated public SyncConfluenceRequest getSyncConfluenceRequest() { return syncConfluenceRequest; }
    /** @deprecated Use {@link #getPayload()} with pattern matching instead. */
    @Deprecated public MetricsJobRequest getMetricsRequest() { return metricsRequest; }
    /** @deprecated Use {@link #getPayload()} with pattern matching instead. */
    @Deprecated public QualityReportJobRequest getQualityReportRequest() { return qualityReportRequest; }
    /** @deprecated Use {@link #getPayload()} with pattern matching instead. */
    @Deprecated public JiraReviewRequest getJiraReviewRequest() { return jiraReviewRequest; }
    public Instant getCreatedAt() { return createdAt; }

    public JobStatus getStatus() { return status; }
    public void setStatus(JobStatus status) { this.status = status; }

    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getPrUrl() { return prUrl; }
    public void setPrUrl(String prUrl) { this.prUrl = prUrl; }

    public int getFilesChanged() { return filesChanged; }
    public void setFilesChanged(int filesChanged) { this.filesChanged = filesChanged; }

    public int getLinesChanged() { return linesChanged; }
    public void setLinesChanged(int linesChanged) { this.linesChanged = linesChanged; }

    public String getPrId() { return prId; }
    public void setPrId(String prId) { this.prId = prId; }

    public String getPlanId() { return planId; }
    public void setPlanId(String planId) { this.planId = planId; }

    public String getPrAuthor() { return prAuthor; }
    public void setPrAuthor(String prAuthor) { this.prAuthor = prAuthor; }

    public String getWorkspace() { return workspace; }
    public void setWorkspace(String workspace) { this.workspace = workspace; }

    public String getRepoSlug() { return repoSlug; }
    public void setRepoSlug(String repoSlug) { this.repoSlug = repoSlug; }

    public String getWorkspacePath() { return workspacePath; }
    public void setWorkspacePath(String workspacePath) { this.workspacePath = workspacePath; }

    public JobCoverageData getCoverageData() { return coverageData; }
    public void setCoverageData(JobCoverageData coverageData) { this.coverageData = coverageData; }

    public String getJiraIssueType() { return jiraIssueType; }
    public void setJiraIssueType(String jiraIssueType) { this.jiraIssueType = jiraIssueType; }

    public String getJiraPriority() { return jiraPriority; }
    public void setJiraPriority(String jiraPriority) { this.jiraPriority = jiraPriority; }

    public Instant getJiraCreatedAt() { return jiraCreatedAt; }
    public void setJiraCreatedAt(Instant jiraCreatedAt) { this.jiraCreatedAt = jiraCreatedAt; }

    public String getPromotionJobId() { return promotionJobId; }
    public void setPromotionJobId(String promotionJobId) { this.promotionJobId = promotionJobId; }

    public String getAikidoIssueId() { return aikidoIssueId; }
    public void setAikidoIssueId(String aikidoIssueId) { this.aikidoIssueId = aikidoIssueId; }

    public String getFixBranchName() { return fixBranchName; }
    public void setFixBranchName(String fixBranchName) { this.fixBranchName = fixBranchName; }

    public PromoteRequest getPromoteRequest() { return promoteRequest; }

    public String getScytaleEvidenceRef() { return scytaleEvidenceRef; }
    public void setScytaleEvidenceRef(String scytaleEvidenceRef) { this.scytaleEvidenceRef = scytaleEvidenceRef; }

    public Instant getScytaleUploadedAt() { return scytaleUploadedAt; }
    public void setScytaleUploadedAt(Instant scytaleUploadedAt) { this.scytaleUploadedAt = scytaleUploadedAt; }
}
