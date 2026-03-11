package com.eneve.agent.model;

import java.time.Instant;

public class JobRecord {

    private final String jobId;
    private final RunFixRequest request;
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
        this.createdAt = Instant.now();
        this.status = JobStatus.PENDING;
    }

    public String getJobId() { return jobId; }
    public RunFixRequest getRequest() { return request; }
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
