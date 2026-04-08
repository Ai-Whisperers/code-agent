package com.eneve.agent;

import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobStatus;
import com.eneve.agent.model.JobType;
import com.eneve.agent.model.Soc2JobSummary;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Business logic for the SOC II compliance view.
 *
 * <p>Fetches fix/upgrade jobs that are SOC II–applicable (Bug-type Jira tickets),
 * derives SLA and review status, applies optional filters, and paginates results.
 */
@ApplicationScoped
public class ComplianceService {

    private static final Logger LOG = Logger.getLogger(ComplianceService.class);

    @Inject JobStore jobStore;
    @Inject Soc2Policy soc2Policy;

    public record Soc2PageResult(List<Soc2JobSummary> items, int total, int page, int limit) {}

    /**
     * Returns a paginated, optionally-filtered list of SOC II job summaries.
     *
     * @throws IllegalArgumentException if {@code statusParam} is not a valid {@link JobStatus} name
     */
    public Soc2PageResult listSoc2Jobs(String statusParam, String slaStatusParam,
                                        String reviewStatusParam, String priorityParam,
                                        int limit, int page) {
        String bugIssueTypes = soc2Policy.bugIssueTypes();
        int criticalDays     = soc2Policy.criticalSlaDays();
        int highDays         = soc2Policy.highSlaDays();

        int safeLimit = Math.min(Math.max(1, limit), 200);
        int offset    = Math.max(0, page) * safeLimit;

        List<JobRecord> candidates = jobStore.findJobsWithJiraIssueType();

        JobStatus filterStatus = null;
        if (statusParam != null && !statusParam.isBlank()) {
            filterStatus = JobStatus.valueOf(statusParam.toUpperCase());
        }

        List<Soc2JobSummary> results = new ArrayList<>();

        for (JobRecord job : candidates) {
            if (!JobStore.isSoc2Applicable(job, bugIssueTypes)) continue;
            if (filterStatus != null && job.getStatus() != filterStatus) continue;

            if (priorityParam != null && !priorityParam.isBlank()
                    && !priorityParam.equalsIgnoreCase(job.getJiraPriority())) {
                continue;
            }

            String slaStatus  = deriveSlaStatus(job, criticalDays, highDays);
            Instant slaDeadline = deriveSlaDeadline(job, criticalDays, highDays);

            if (slaStatusParam != null && !slaStatusParam.isBlank()
                    && !slaStatusParam.equalsIgnoreCase(slaStatus)) {
                continue;
            }

            String reviewStatus = deriveReviewStatus(job);

            if (reviewStatusParam != null && !reviewStatusParam.isBlank()
                    && !reviewStatusParam.equalsIgnoreCase(reviewStatus)) {
                continue;
            }

            String jiraKey = switch (job.getPayload()) {
                case com.eneve.agent.model.RunFixRequest r  -> r.jiraKey();
                case com.eneve.agent.model.FixPrRequest r   -> r.jiraKey();
                default -> null;
            };

            results.add(new Soc2JobSummary(
                    job.getJobId(),
                    job.getJobType(),
                    jiraKey,
                    job.getJiraPriority(),
                    job.getAikidoIssueId(),
                    slaStatus,
                    slaDeadline,
                    reviewStatus,
                    job.getStatus(),
                    job.getPrUrl(),
                    job.getScytaleEvidenceRef() != null,
                    job.getCreatedAt()
            ));
        }

        int total    = results.size();
        int from     = Math.min(offset, total);
        int to       = Math.min(from + safeLimit, total);
        return new Soc2PageResult(results.subList(from, to), total, Math.max(0, page), safeLimit);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private String deriveSlaStatus(JobRecord job, int criticalDays, int highDays) {
        String priority       = job.getJiraPriority();
        Instant jiraCreatedAt = job.getJiraCreatedAt();

        if (priority == null || jiraCreatedAt == null) return "NOT_APPLICABLE";

        int slaDays = 0;
        if ("Critical".equalsIgnoreCase(priority))   slaDays = criticalDays;
        else if ("High".equalsIgnoreCase(priority))  slaDays = highDays;

        if (slaDays == 0) return "NOT_APPLICABLE";

        Instant deadline     = jiraCreatedAt.plusSeconds((long) slaDays * 86400);
        long secondsLeft     = deadline.getEpochSecond() - Instant.now().getEpochSecond();
        boolean merged       = job.getStatus() == JobStatus.SUCCESS;

        if (merged)              return secondsLeft >= 0 ? "MET"       : "MISSED";
        if (secondsLeft < 0)     return "OVERDUE";
        if (secondsLeft <= 2L * 86400) return "AT_RISK";
        return "ON_TRACK";
    }

    private Instant deriveSlaDeadline(JobRecord job, int criticalDays, int highDays) {
        String priority       = job.getJiraPriority();
        Instant jiraCreatedAt = job.getJiraCreatedAt();

        if (priority == null || jiraCreatedAt == null) return null;

        int slaDays = 0;
        if ("Critical".equalsIgnoreCase(priority))   slaDays = criticalDays;
        else if ("High".equalsIgnoreCase(priority))  slaDays = highDays;

        return slaDays > 0 ? jiraCreatedAt.plusSeconds((long) slaDays * 86400) : null;
    }

    private String deriveReviewStatus(JobRecord job) {
        if (job.getPrId() == null) return "NONE";
        try {
            String status = "NONE";
            for (JobRecord r : jobStore.findByPrId(job.getPrId(), job.getWorkspace(), job.getRepoSlug())) {
                if (r.getJobType() != JobType.REVIEW) continue;
                if (r.getStatus() == JobStatus.SUCCESS) return "COMPLETE";
                if (r.getStatus() == JobStatus.RUNNING
                        || r.getStatus() == JobStatus.QUEUED
                        || r.getStatus() == JobStatus.PENDING) {
                    status = "IN_PROGRESS";
                }
            }
            return status;
        } catch (Exception e) {
            LOG.warnf("Could not determine review status for job %s: %s", job.getJobId(), e.getMessage());
            return "NONE";
        }
    }

}
