package com.eneve.agent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.audit.AuditStore;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobStatus;
import com.eneve.agent.model.JobType;
import com.eneve.agent.model.Soc2JobSummary;
import com.eneve.agent.settings.SettingsService;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import io.quarkus.security.Authenticated;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@RequestScoped
@Path("/compliance")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Compliance", description = "SOC II compliance audit endpoints")
public class ComplianceResource {

    private static final Logger LOG = Logger.getLogger(ComplianceResource.class);

    @Inject
    JobStore jobStore;

    @Inject
    AuditStore auditStore;

    @Inject
    SettingsService settings;

    @GET
    @Path("/soc2")
    @Operation(
            operationId = "listSoc2Jobs",
            summary = "List SOC II compliance jobs",
            description = "Returns all jobs linked to Jira Bug tickets that are subject to SOC II "
                    + "compliance requirements. Includes SLA status, review status, and Scytale "
                    + "upload status for each job. Supports optional filtering by SLA status, "
                    + "job status, and review status."
    )
    @APIResponse(responseCode = "200", description = "List of SOC II job summaries",
            content = @Content(schema = @Schema(implementation = Soc2JobSummary.class)))
    public Response listSoc2Jobs(

            @Parameter(description = "Filter by job status")
            @QueryParam("status") String statusParam,

            @Parameter(description = "Filter by SLA status: ON_TRACK, AT_RISK, OVERDUE, MET, MISSED, NOT_APPLICABLE")
            @QueryParam("slaStatus") String slaStatusParam,

            @Parameter(description = "Filter by review status: NONE, IN_PROGRESS, COMPLETE")
            @QueryParam("reviewStatus") String reviewStatusParam,

            @Parameter(description = "Maximum number of results (1–200, default 100)")
            @QueryParam("limit") @DefaultValue("100") int limit,

            @Parameter(description = "Zero-based page number for pagination (default 0)")
            @QueryParam("page") @DefaultValue("0") int page

    ) {
        String bugIssueTypes = settings.get("soc2.bug-issue-types", "Bug,Defect");
        String criticalDaysStr = settings.get("soc2.sla.critical-days", "5");
        String highDaysStr = settings.get("soc2.sla.high-days", "20");
        int criticalDays = parseInt(criticalDaysStr, 5);
        int highDays = parseInt(highDaysStr, 20);

        int safeLimit = Math.min(Math.max(1, limit), 200);
        int offset = Math.max(0, page) * safeLimit;

        List<JobRecord> candidates = jobStore.findJobsWithJiraIssueType(safeLimit + offset + 100);

        JobStatus filterStatus = null;
        if (statusParam != null && !statusParam.isBlank()) {
            try {
                filterStatus = JobStatus.valueOf(statusParam.toUpperCase());
            } catch (IllegalArgumentException e) {
                return Response.status(400)
                        .entity(Map.of("error", "Invalid status: " + statusParam))
                        .build();
            }
        }

        List<Soc2JobSummary> results = new ArrayList<>();

        for (JobRecord job : candidates) {
            // Only include jobs that are SOC II applicable (Bug-type Jira tickets)
            if (!JobStore.isSoc2Applicable(job, bugIssueTypes)) {
                continue;
            }

            // Status filter
            if (filterStatus != null && job.getStatus() != filterStatus) {
                continue;
            }

            // Derive SLA info
            String slaStatus = "NOT_APPLICABLE";
            Instant slaDeadline = null;
            String priority = job.getJiraPriority();
            Instant jiraCreatedAt = job.getJiraCreatedAt();

            if (priority != null && jiraCreatedAt != null) {
                int slaDays = 0;
                if ("Critical".equalsIgnoreCase(priority)) {
                    slaDays = criticalDays;
                } else if ("High".equalsIgnoreCase(priority)) {
                    slaDays = highDays;
                }
                if (slaDays > 0) {
                    slaDeadline = jiraCreatedAt.plusSeconds((long) slaDays * 86400);
                    Instant now = Instant.now();
                    long secondsLeft = slaDeadline.getEpochSecond() - now.getEpochSecond();
                    boolean merged = job.getStatus() == JobStatus.SUCCESS;
                    if (merged) {
                        slaStatus = secondsLeft >= 0 ? "MET" : "MISSED";
                    } else if (secondsLeft < 0) {
                        slaStatus = "OVERDUE";
                    } else if (secondsLeft <= 2L * 86400) {
                        slaStatus = "AT_RISK";
                    } else {
                        slaStatus = "ON_TRACK";
                    }
                }
            }

            // SLA status filter
            if (slaStatusParam != null && !slaStatusParam.isBlank()
                    && !slaStatusParam.equalsIgnoreCase(slaStatus)) {
                continue;
            }

            // Derive review status from related REVIEW jobs
            String reviewStatus = "NONE";
            try {
                if (job.getPrId() != null) {
                    List<JobRecord> related = jobStore.findByPrId(job.getPrId());
                    for (JobRecord r : related) {
                        if (r.getJobType() == JobType.REVIEW) {
                            if (r.getStatus() == JobStatus.RUNNING
                                    || r.getStatus() == JobStatus.QUEUED
                                    || r.getStatus() == JobStatus.PENDING) {
                                reviewStatus = "IN_PROGRESS";
                            } else if (r.getStatus() == JobStatus.SUCCESS) {
                                reviewStatus = "COMPLETE";
                                break;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LOG.warnf("Could not determine review status for job %s: %s", job.getJobId(), e.getMessage());
            }

            // Review status filter
            if (reviewStatusParam != null && !reviewStatusParam.isBlank()
                    && !reviewStatusParam.equalsIgnoreCase(reviewStatus)) {
                continue;
            }

            // Extract jiraKey
            String jiraKey = null;
            if (job.getRequest() != null) jiraKey = job.getRequest().jiraKey();
            else if (job.getFixPrRequest() != null) jiraKey = job.getFixPrRequest().jiraKey();

            results.add(new Soc2JobSummary(
                    job.getJobId(),
                    job.getJobType(),
                    jiraKey,
                    priority,
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

        // Paginate the in-memory filtered results
        int total = results.size();
        int from = Math.min(offset, total);
        int to = Math.min(from + safeLimit, total);
        List<Soc2JobSummary> pageItems = results.subList(from, to);

        return Response.ok(Map.of(
                "items", pageItems,
                "total", total,
                "page", Math.max(0, page),
                "limit", safeLimit
        )).build();
    }

    private static int parseInt(String value, int defaultValue) {
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
