package com.eneve.agent;

import com.eneve.agent.agent.JobQueue;
import com.eneve.agent.agent.model.QualityReport;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.agent.store.QualityReportStore;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.QualityReportJobRequest;
import com.eneve.agent.scm.GitPlatformService;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Business logic for per-repository quality reports.
 *
 * <p>Encapsulates report retrieval, branch comparison (including delta computation),
 * and on-demand collection job submission.
 */
@ApplicationScoped
public class QualityReportService {

    @Inject QualityReportStore reportStore;
    @Inject JobStore jobStore;
    @Inject JobQueue jobQueue;
    @Inject GitPlatformService platformService;

    // ─── Exception types ──────────────────────────────────────────────────────

    public static final class ReportNotFoundException extends RuntimeException {
        public ReportNotFoundException(String msg) { super(msg); }
    }

    public static final class MissingRepoUrlException extends RuntimeException {
        public MissingRepoUrlException(String msg) { super(msg); }
    }

    public static final class QueueFullException extends RuntimeException {
        public QueueFullException() { super("Job queue is full"); }
    }

    // ─── Result types ─────────────────────────────────────────────────────────

    public record TriggerResult(String jobId, String workspace, String repoSlug,
                                 String branch, String status) {}

    public record CompareResult(String workspace, String repoSlug,
                                 Map<String, QualityReport> branches,
                                 Map<String, Object> deltas) {}

    // ─── Service methods ──────────────────────────────────────────────────────

    /**
     * @throws ReportNotFoundException if no report exists for the given branch
     */
    public QualityReport getLatest(String workspace, String repoSlug, String branch) {
        return reportStore.findLatest(workspace, repoSlug, branch)
                .orElseThrow(() -> new ReportNotFoundException(
                        "No quality report found for " + workspace + "/" + repoSlug + "@" + branch));
    }

    public List<QualityReport> getHistory(String workspace, String repoSlug, String branch, int limit) {
        return reportStore.findHistory(workspace, repoSlug, branch, limit);
    }

    public CompareResult compare(String workspace, String repoSlug, String branchesParam) {
        String[] requestedBranches = branchesParam.split(",");
        Map<String, QualityReport> latestPerBranch = reportStore.findLatestPerBranch(workspace, repoSlug);

        Map<String, QualityReport> branchReports = new LinkedHashMap<>();
        for (String b : requestedBranches) {
            branchReports.put(b.trim(), latestPerBranch.get(b.trim()));
        }

        Map<String, Object> deltas = null;
        if (requestedBranches.length == 2) {
            QualityReport a = latestPerBranch.get(requestedBranches[0].trim());
            QualityReport b = latestPerBranch.get(requestedBranches[1].trim());
            if (a != null && b != null) {
                deltas = computeDeltas(a, b);
            }
        }

        return new CompareResult(workspace, repoSlug, branchReports, deltas);
    }

    /**
     * @throws MissingRepoUrlException if repo URL cannot be resolved
     * @throws QueueFullException      if the job queue is at capacity
     */
    public TriggerResult trigger(String workspace, String repoSlug, String branch, String repoUrl) {
        String resolvedRepoUrl = (repoUrl != null && !repoUrl.isBlank())
                ? repoUrl
                : platformService.buildCloneUrl(workspace, repoSlug);

        if (resolvedRepoUrl == null || resolvedRepoUrl.isBlank()) {
            throw new MissingRepoUrlException(
                    "repoUrl is required and could not be resolved for " + workspace + "/" + repoSlug);
        }

        String jobId = UUID.randomUUID().toString();
        QualityReportJobRequest jobRequest = new QualityReportJobRequest(
                resolvedRepoUrl, branch, workspace, repoSlug);
        JobRecord job = new JobRecord(jobId, jobRequest);
        job.setWorkspace(workspace);
        job.setRepoSlug(repoSlug);
        jobStore.put(job);

        if (!jobQueue.submit(job)) {
            throw new QueueFullException();
        }

        return new TriggerResult(jobId, workspace, repoSlug, branch, "QUEUED");
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> computeDeltas(QualityReport a, QualityReport b) {
        Map<String, Object> deltas = new LinkedHashMap<>();
        deltas.put("score", round(b.score() - a.score()));

        if (a.coverage() != null && b.coverage() != null) {
            deltas.put("coverage.lineRate",   round(b.coverage().lineRate()   - a.coverage().lineRate()));
            deltas.put("coverage.branchRate", round(b.coverage().branchRate() - a.coverage().branchRate()));
        }
        if (a.linter() != null && b.linter() != null) {
            deltas.put("linter.totalFindings", b.linter().totalFindings() - a.linter().totalFindings());
            deltas.put("linter.errorCount",    b.linter().errorCount()    - a.linter().errorCount());
        }
        if (a.aikido() != null && b.aikido() != null) {
            deltas.put("aikido.totalIssues",   b.aikido().totalIssues()   - a.aikido().totalIssues());
            deltas.put("aikido.criticalCount", b.aikido().criticalCount() - a.aikido().criticalCount());
        }
        if (a.complexity() != null && b.complexity() != null) {
            deltas.put("complexity.avgComplexity",
                    round(b.complexity().avgComplexity() - a.complexity().avgComplexity()));
            deltas.put("complexity.methodsAboveThreshold",
                    b.complexity().methodsAboveThreshold() - a.complexity().methodsAboveThreshold());
        }
        if (a.reviewQuality() != null && b.reviewQuality() != null) {
            deltas.put("reviewQuality.resolutionRate",
                    round(b.reviewQuality().resolutionRate() - a.reviewQuality().resolutionRate()));
        }
        return deltas;
    }

    private static double round(double val) {
        return Math.round(val * 10000.0) / 10000.0;
    }
}
