package com.eneve.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.eneve.agent.agent.JobQueue;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.agent.model.QualityReport;
import com.eneve.agent.agent.store.QualityReportStore;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.QualityReportJobRequest;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.annotation.security.RolesAllowed;
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

/**
 * REST endpoints for per-repository quality reports.
 * Exposes latest reports, history, branch comparison, and on-demand collection triggers.
 */
@Path("/metrics/quality-reports")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Quality Reports", description = "Per-repository quality snapshots with aggregate score and branch comparison")
public class QualityReportResource {

    @Inject QualityReportStore reportStore;
    @Inject JobStore jobStore;
    @Inject JobQueue jobQueue;

    @GET
    @Path("/{workspace}/{repoSlug}/{branch}")
    @Operation(
            operationId = "getLatestQualityReport",
            summary = "Get the latest quality report for a branch",
            description = "Returns the most recent quality snapshot for the specified branch, "
                    + "including coverage, linter, security, complexity, and review quality metrics "
                    + "combined into an aggregate score (0-1)."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Latest quality report"),
            @APIResponse(responseCode = "404", description = "No reports found for this branch")
    })
    public Response getLatest(
            @Parameter(description = "Workspace or GitLab namespace", required = true)
            @PathParam("workspace") String workspace,
            @Parameter(description = "Repository slug", required = true)
            @PathParam("repoSlug") String repoSlug,
            @Parameter(description = "Branch name", required = true)
            @PathParam("branch") String branch) {

        return reportStore.findLatest(workspace, repoSlug, branch)
                .map(r -> Response.ok(r).build())
                .orElse(Response.status(404)
                        .entity(Map.of("error", "No quality report found for "
                                + workspace + "/" + repoSlug + "@" + branch))
                        .build());
    }

    @GET
    @Path("/{workspace}/{repoSlug}/{branch}/history")
    @Operation(
            operationId = "getQualityReportHistory",
            summary = "Get quality report history for a branch",
            description = "Returns historical quality snapshots for the specified branch, "
                    + "newest first. Useful for tracking score trends over time."
    )
    @APIResponse(responseCode = "200", description = "List of quality reports (newest first)")
    public Response getHistory(
            @Parameter(description = "Workspace or GitLab namespace", required = true)
            @PathParam("workspace") String workspace,
            @Parameter(description = "Repository slug", required = true)
            @PathParam("repoSlug") String repoSlug,
            @Parameter(description = "Branch name", required = true)
            @PathParam("branch") String branch,
            @Parameter(description = "Maximum number of records to return (default: 30)")
            @QueryParam("limit") @DefaultValue("30") int limit) {

        List<QualityReport> history = reportStore.findHistory(workspace, repoSlug, branch, limit);
        return Response.ok(history).build();
    }

    @GET
    @Path("/{workspace}/{repoSlug}/compare")
    @Operation(
            operationId = "compareQualityReports",
            summary = "Compare latest quality reports across branches",
            description = "Returns the latest quality report for each requested branch side-by-side, "
                    + "with a delta map showing score and per-metric differences (develop vs main)."
    )
    @APIResponse(responseCode = "200", description = "Branch comparison with deltas")
    public Response compare(
            @Parameter(description = "Workspace or GitLab namespace", required = true)
            @PathParam("workspace") String workspace,
            @Parameter(description = "Repository slug", required = true)
            @PathParam("repoSlug") String repoSlug,
            @Parameter(description = "Comma-separated list of branches to compare (default: main,develop)")
            @QueryParam("branches") @DefaultValue("main,develop") String branchesParam) {

        String[] requestedBranches = branchesParam.split(",");
        Map<String, QualityReport> latestPerBranch = reportStore.findLatestPerBranch(workspace, repoSlug);

        Map<String, Object> branchReports = new LinkedHashMap<>();
        for (String b : requestedBranches) {
            String trimmed = b.trim();
            QualityReport report = latestPerBranch.get(trimmed);
            branchReports.put(trimmed, report);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("workspace", workspace);
        response.put("repoSlug", repoSlug);
        response.put("branches", branchReports);

        if (requestedBranches.length == 2) {
            String branchA = requestedBranches[0].trim();
            String branchB = requestedBranches[1].trim();
            QualityReport a = latestPerBranch.get(branchA);
            QualityReport b = latestPerBranch.get(branchB);
            if (a != null && b != null) {
                response.put("deltas", computeDeltas(a, b));
            }
        }

        return Response.ok(response).build();
    }

    @POST
    @Path("/{workspace}/{repoSlug}/{branch}")
    @RolesAllowed({"app_developer", "app_admin"})
    @Operation(
            operationId = "triggerQualityReport",
            summary = "Trigger a quality report collection",
            description = "Queues a QUALITY_REPORT job to collect a fresh quality snapshot for the "
                    + "specified branch. Returns the job ID to poll for status."
    )
    @APIResponses({
            @APIResponse(responseCode = "202", description = "Job queued"),
            @APIResponse(responseCode = "429", description = "Job queue is full")
    })
    public Response trigger(
            @Parameter(description = "Workspace or GitLab namespace", required = true)
            @PathParam("workspace") String workspace,
            @Parameter(description = "Repository slug", required = true)
            @PathParam("repoSlug") String repoSlug,
            @Parameter(description = "Branch to measure", required = true)
            @PathParam("branch") String branch,
            @RequestBody(description = "Repository URL and optional overrides", required = true)
            TriggerQualityReportRequest request) {

        String jobId = UUID.randomUUID().toString();
        QualityReportJobRequest jobRequest = new QualityReportJobRequest(
                request.repoUrl(), branch, workspace, repoSlug);
        JobRecord job = new JobRecord(jobId, jobRequest);
        jobStore.put(job);

        if (!jobQueue.submit(job)) {
            return Response.status(429).entity(Map.of("error", "Job queue is full")).build();
        }

        return Response.accepted(Map.of(
                "jobId", jobId,
                "workspace", workspace,
                "repoSlug", repoSlug,
                "branch", branch,
                "status", "QUEUED"
        )).build();
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private Map<String, Object> computeDeltas(QualityReport a, QualityReport b) {
        Map<String, Object> deltas = new LinkedHashMap<>();
        deltas.put("score", round(b.score() - a.score()));

        if (a.coverage() != null && b.coverage() != null) {
            deltas.put("coverage.lineRate", round(b.coverage().lineRate() - a.coverage().lineRate()));
            deltas.put("coverage.branchRate", round(b.coverage().branchRate() - a.coverage().branchRate()));
        }
        if (a.linter() != null && b.linter() != null) {
            deltas.put("linter.totalFindings", b.linter().totalFindings() - a.linter().totalFindings());
            deltas.put("linter.errorCount", b.linter().errorCount() - a.linter().errorCount());
        }
        if (a.aikido() != null && b.aikido() != null) {
            deltas.put("aikido.totalIssues", b.aikido().totalIssues() - a.aikido().totalIssues());
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

    public record TriggerQualityReportRequest(
            @Parameter(description = "Repository HTTPS clone URL", required = true)
            String repoUrl
    ) {}
}
