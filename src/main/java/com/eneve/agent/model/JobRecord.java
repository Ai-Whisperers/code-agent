package com.eneve.agent.model;

import java.time.Instant;

public class JobRecord {

    private final String jobId;
    private final JobPayload payload;
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

    // Restart tracking — set when this job is a restart of a previously failed job
    private volatile String restartFromJobId;
    private volatile int restartIteration;
    private volatile int additionalIterations;

    private JobRecord(String jobId, JobPayload payload, JobType jobType) {
        this.jobId = jobId;
        this.payload = payload;
        this.jobType = jobType;
        this.createdAt = Instant.now();
        this.status = JobStatus.PENDING;
        this.priority = jobType.defaultPriority();
    }

    public JobRecord(String jobId, RunFixRequest request) {
        this(jobId, request, JobType.FIX);
    }

    public JobRecord(String jobId, ReviewPrRequest reviewRequest) {
        this(jobId, reviewRequest, JobType.REVIEW);
    }

    public JobRecord(String jobId, FixPrRequest fixPrRequest) {
        this(jobId, fixPrRequest, JobType.FIX_PR);
    }

    public JobRecord(String jobId, ReplyCommentRequest replyRequest) {
        this(jobId, replyRequest, JobType.REPLY);
    }

    public JobRecord(String jobId, ReplyCommentRequest replyRequest, JobType jobType) {
        this(jobId, (JobPayload) replyRequest, jobType);
    }

    public JobRecord(String jobId, HookJobRequest hookRequest) {
        this(jobId, hookRequest, JobType.HOOK);
    }

    public JobRecord(String jobId, GenerateTestsRequest generateTestsRequest) {
        this(jobId, generateTestsRequest, JobType.GENERATE_TESTS);
    }

    public JobRecord(String jobId, GenerateDocsRequest generateDocsRequest) {
        this(jobId, generateDocsRequest, JobType.GENERATE_DOCS);
    }

    public JobRecord(String jobId, SyncConfluenceRequest syncConfluenceRequest) {
        this(jobId, syncConfluenceRequest, JobType.SYNC_CONFLUENCE);
    }

    public JobRecord(String jobId, MetricsJobRequest metricsRequest) {
        this(jobId, metricsRequest, JobType.METRICS);
    }

    public JobRecord(String jobId, QualityReportJobRequest qualityReportRequest) {
        this(jobId, qualityReportRequest, JobType.QUALITY_REPORT);
    }

    public JobRecord(String jobId, JiraReviewRequest jiraReviewRequest, JobType jobType) {
        this(jobId, (JobPayload) jiraReviewRequest, jobType);
    }

    public JobRecord(String jobId, PromoteRequest promoteRequest) {
        this(jobId, promoteRequest, JobType.PROMOTE);
    }

    public JobRecord(String jobId, SelfAnalysisRequest selfAnalysisRequest) {
        this(jobId, selfAnalysisRequest, JobType.SELF_ANALYSIS);
    }

    public JobRecord(String jobId, GenerateArchitectureRequest request) {
        this(jobId, request, JobType.GENERATE_ARCHITECTURE);
    }

    public JobRecord(String jobId, GenerateCloudArchitectureRequest request) {
        this(jobId, request, JobType.GENERATE_CLOUD_ARCHITECTURE);
    }

    public JobRecord(String jobId, KnowledgeGraphRequest request) {
        this(jobId, request, JobType.KNOWLEDGE_GRAPH);
    }

    public JobRecord(String jobId, TechDebtRequest request) {
        this(jobId, request, JobType.TECH_DEBT);
    }

    public JobRecord(String jobId, RewriteRequest request) {
        this(jobId, request, JobType.REWRITE);
    }

    public JobRecord(String jobId, ServiceDeskTriageRequest request) {
        this(jobId, request, JobType.SERVICE_DESK_TRIAGE);
    }

    public JobRecord(String jobId, QaTestPlanAnalysisRequest request) {
        this(jobId, request, JobType.QA_TESTPLAN_ANALYSIS);
    }

    public JobRecord(String jobId, QaTestPlanConversionRequest request) {
        this(jobId, request, JobType.QA_TESTPLAN_CONVERSION);
    }

    public JobRecord(String jobId, QaTestCaseGenerationRequest request) {
        this(jobId, request, JobType.QA_TESTCASE_GENERATION);
    }

    public String getJobId() { return jobId; }
    public JobType getJobType() { return jobType; }

    /** Returns the unified job payload. Prefer this over the individual typed getters. */
    public JobPayload getPayload() { return payload; }

    /** @deprecated Use {@link #getPayload()} with pattern matching instead. */
    @Deprecated public RunFixRequest getRequest() { return payload instanceof RunFixRequest r ? r : null; }
    /** @deprecated Use {@link #getPayload()} with pattern matching instead. */
    @Deprecated public ReviewPrRequest getReviewRequest() { return payload instanceof ReviewPrRequest r ? r : null; }
    /** @deprecated Use {@link #getPayload()} with pattern matching instead. */
    @Deprecated public FixPrRequest getFixPrRequest() { return payload instanceof FixPrRequest r ? r : null; }
    /** @deprecated Use {@link #getPayload()} with pattern matching instead. */
    @Deprecated public ReplyCommentRequest getReplyRequest() { return payload instanceof ReplyCommentRequest r ? r : null; }
    /** @deprecated Use {@link #getPayload()} with pattern matching instead. */
    @Deprecated public HookJobRequest getHookRequest() { return payload instanceof HookJobRequest r ? r : null; }
    /** @deprecated Use {@link #getPayload()} with pattern matching instead. */
    @Deprecated public GenerateTestsRequest getGenerateTestsRequest() { return payload instanceof GenerateTestsRequest r ? r : null; }
    /** @deprecated Use {@link #getPayload()} with pattern matching instead. */
    @Deprecated public GenerateDocsRequest getGenerateDocsRequest() { return payload instanceof GenerateDocsRequest r ? r : null; }
    /** @deprecated Use {@link #getPayload()} with pattern matching instead. */
    @Deprecated public SyncConfluenceRequest getSyncConfluenceRequest() { return payload instanceof SyncConfluenceRequest r ? r : null; }
    /** @deprecated Use {@link #getPayload()} with pattern matching instead. */
    @Deprecated public MetricsJobRequest getMetricsRequest() { return payload instanceof MetricsJobRequest r ? r : null; }
    /** @deprecated Use {@link #getPayload()} with pattern matching instead. */
    @Deprecated public QualityReportJobRequest getQualityReportRequest() { return payload instanceof QualityReportJobRequest r ? r : null; }
    /** @deprecated Use {@link #getPayload()} with pattern matching instead. */
    @Deprecated public JiraReviewRequest getJiraReviewRequest() { return payload instanceof JiraReviewRequest r ? r : null; }

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

    public PromoteRequest getPromoteRequest() { return payload instanceof PromoteRequest r ? r : null; }

    public String getScytaleEvidenceRef() { return scytaleEvidenceRef; }
    public void setScytaleEvidenceRef(String scytaleEvidenceRef) { this.scytaleEvidenceRef = scytaleEvidenceRef; }

    public Instant getScytaleUploadedAt() { return scytaleUploadedAt; }
    public void setScytaleUploadedAt(Instant scytaleUploadedAt) { this.scytaleUploadedAt = scytaleUploadedAt; }

    public String getRestartFromJobId() { return restartFromJobId; }
    public void setRestartFromJobId(String restartFromJobId) { this.restartFromJobId = restartFromJobId; }

    public int getRestartIteration() { return restartIteration; }
    public void setRestartIteration(int restartIteration) { this.restartIteration = restartIteration; }

    /** Extra iterations granted by the user when restarting (added on top of remaining budget). */
    public int getAdditionalIterations() { return additionalIterations; }
    public void setAdditionalIterations(int additionalIterations) { this.additionalIterations = additionalIterations; }
}
