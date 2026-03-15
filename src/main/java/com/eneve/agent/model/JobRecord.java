package com.eneve.agent.model;

import java.time.Instant;

public class JobRecord {

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
    private final JobType jobType;
    private final Instant createdAt;
    private volatile JobStatus status;
    private volatile String summary;
    private volatile String errorMessage;
    private volatile String prUrl;
    private volatile int filesChanged;
    private volatile int linesChanged;
    private volatile String prId;

    public JobRecord(String jobId, RunFixRequest request) {
        this.jobId = jobId;
        this.request = request;
        this.reviewRequest = null;
        this.fixPrRequest = null;
        this.replyRequest = null;
        this.hookRequest = null;
        this.generateTestsRequest = null;
        this.generateDocsRequest = null;
        this.syncConfluenceRequest = null;
        this.metricsRequest = null;
        this.jobType = JobType.FIX;
        this.createdAt = Instant.now();
        this.status = JobStatus.PENDING;
    }

    public JobRecord(String jobId, ReviewPrRequest reviewRequest) {
        this.jobId = jobId;
        this.request = null;
        this.reviewRequest = reviewRequest;
        this.fixPrRequest = null;
        this.replyRequest = null;
        this.hookRequest = null;
        this.generateTestsRequest = null;
        this.generateDocsRequest = null;
        this.syncConfluenceRequest = null;
        this.metricsRequest = null;
        this.jobType = JobType.REVIEW;
        this.createdAt = Instant.now();
        this.status = JobStatus.PENDING;
    }

    public JobRecord(String jobId, FixPrRequest fixPrRequest) {
        this.jobId = jobId;
        this.request = null;
        this.reviewRequest = null;
        this.fixPrRequest = fixPrRequest;
        this.replyRequest = null;
        this.hookRequest = null;
        this.generateTestsRequest = null;
        this.generateDocsRequest = null;
        this.syncConfluenceRequest = null;
        this.metricsRequest = null;
        this.jobType = JobType.FIX_PR;
        this.createdAt = Instant.now();
        this.status = JobStatus.PENDING;
    }

    public JobRecord(String jobId, ReplyCommentRequest replyRequest) {
        this(jobId, replyRequest, JobType.REPLY);
    }

    public JobRecord(String jobId, ReplyCommentRequest replyRequest, JobType jobType) {
        this.jobId = jobId;
        this.request = null;
        this.reviewRequest = null;
        this.fixPrRequest = null;
        this.replyRequest = replyRequest;
        this.hookRequest = null;
        this.generateTestsRequest = null;
        this.generateDocsRequest = null;
        this.syncConfluenceRequest = null;
        this.metricsRequest = null;
        this.jobType = jobType;
        this.createdAt = Instant.now();
        this.status = JobStatus.PENDING;
    }

    public JobRecord(String jobId, HookJobRequest hookRequest) {
        this.jobId = jobId;
        this.request = null;
        this.reviewRequest = null;
        this.fixPrRequest = null;
        this.replyRequest = null;
        this.hookRequest = hookRequest;
        this.generateTestsRequest = null;
        this.generateDocsRequest = null;
        this.syncConfluenceRequest = null;
        this.metricsRequest = null;
        this.jobType = JobType.HOOK;
        this.createdAt = Instant.now();
        this.status = JobStatus.PENDING;
    }

    public JobRecord(String jobId, GenerateTestsRequest generateTestsRequest) {
        this.jobId = jobId;
        this.request = null;
        this.reviewRequest = null;
        this.fixPrRequest = null;
        this.replyRequest = null;
        this.hookRequest = null;
        this.generateTestsRequest = generateTestsRequest;
        this.generateDocsRequest = null;
        this.syncConfluenceRequest = null;
        this.metricsRequest = null;
        this.jobType = JobType.GENERATE_TESTS;
        this.createdAt = Instant.now();
        this.status = JobStatus.PENDING;
    }

    public JobRecord(String jobId, GenerateDocsRequest generateDocsRequest) {
        this.jobId = jobId;
        this.request = null;
        this.reviewRequest = null;
        this.fixPrRequest = null;
        this.replyRequest = null;
        this.hookRequest = null;
        this.generateTestsRequest = null;
        this.generateDocsRequest = generateDocsRequest;
        this.syncConfluenceRequest = null;
        this.metricsRequest = null;
        this.jobType = JobType.GENERATE_DOCS;
        this.createdAt = Instant.now();
        this.status = JobStatus.PENDING;
    }

    public JobRecord(String jobId, SyncConfluenceRequest syncConfluenceRequest) {
        this.jobId = jobId;
        this.request = null;
        this.reviewRequest = null;
        this.fixPrRequest = null;
        this.replyRequest = null;
        this.hookRequest = null;
        this.generateTestsRequest = null;
        this.generateDocsRequest = null;
        this.syncConfluenceRequest = syncConfluenceRequest;
        this.metricsRequest = null;
        this.jobType = JobType.SYNC_CONFLUENCE;
        this.createdAt = Instant.now();
        this.status = JobStatus.PENDING;
    }

    public JobRecord(String jobId, MetricsJobRequest metricsRequest) {
        this.jobId = jobId;
        this.request = null;
        this.reviewRequest = null;
        this.fixPrRequest = null;
        this.replyRequest = null;
        this.hookRequest = null;
        this.generateTestsRequest = null;
        this.generateDocsRequest = null;
        this.syncConfluenceRequest = null;
        this.metricsRequest = metricsRequest;
        this.jobType = JobType.METRICS;
        this.createdAt = Instant.now();
        this.status = JobStatus.PENDING;
    }

    public String getJobId() { return jobId; }
    public RunFixRequest getRequest() { return request; }
    public ReviewPrRequest getReviewRequest() { return reviewRequest; }
    public FixPrRequest getFixPrRequest() { return fixPrRequest; }
    public ReplyCommentRequest getReplyRequest() { return replyRequest; }
    public HookJobRequest getHookRequest() { return hookRequest; }
    public GenerateTestsRequest getGenerateTestsRequest() { return generateTestsRequest; }
    public GenerateDocsRequest getGenerateDocsRequest() { return generateDocsRequest; }
    public SyncConfluenceRequest getSyncConfluenceRequest() { return syncConfluenceRequest; }
    public MetricsJobRequest getMetricsRequest() { return metricsRequest; }
    public JobType getJobType() { return jobType; }
    public Instant getCreatedAt() { return createdAt; }

    public JobStatus getStatus() { return status; }
    public void setStatus(JobStatus status) { this.status = status; }

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
}
