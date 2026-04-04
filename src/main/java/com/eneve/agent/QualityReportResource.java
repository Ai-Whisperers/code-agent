package com.eneve.agent;

import com.eneve.agent.agent.store.QualityReportStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.eneve.agent.agent.model.QualityReport;

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

    @Inject QualityReportService qualityReportService;
    @Inject QualityReportStore qualityReportStore;

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

        try {
            return Response.ok(qualityReportService.getLatest(workspace, repoSlug, branch)).build();
        } catch (QualityReportService.ReportNotFoundException e) {
            return Response.status(404).entity(Map.of("error", e.getMessage())).build();
        }
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

        List<QualityReport> history = qualityReportService.getHistory(workspace, repoSlug, branch, limit);
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

        QualityReportService.CompareResult result =
                qualityReportService.compare(workspace, repoSlug, branchesParam);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("workspace", result.workspace());
        response.put("repoSlug",  result.repoSlug());
        response.put("branches",  result.branches());
        if (result.deltas() != null) {
            response.put("deltas", result.deltas());
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

        String repoUrl = request != null ? request.repoUrl() : null;
        try {
            QualityReportService.TriggerResult result =
                    qualityReportService.trigger(workspace, repoSlug, branch, repoUrl);
            return Response.accepted(Map.of(
                    "jobId",     result.jobId(),
                    "workspace", result.workspace(),
                    "repoSlug",  result.repoSlug(),
                    "branch",    result.branch(),
                    "status",    result.status()
            )).build();
        } catch (QualityReportService.MissingRepoUrlException e) {
            return Response.status(400).entity(Map.of("error", e.getMessage())).build();
        } catch (QualityReportService.QueueFullException e) {
            return Response.status(429).entity(Map.of("error", e.getMessage())).build();
        }
    }

    public record TriggerQualityReportRequest(
            @Parameter(description = "Repository HTTPS clone URL", required = true)
            String repoUrl
    ) {}

    @GET
    @Path("/{workspace}/all/coverage-trend")
    @Operation(
            operationId = "getCoverageTrend",
            summary = "Cross-repo test coverage trend",
            description = "Returns weekly-bucketed average line coverage % per repository for all repos "
                    + "in the workspace. Uses JSONB path extraction for efficiency. "
                    + "Repos without JaCoCo coverage data are excluded."
    )
    @APIResponse(responseCode = "200", description = "Weekly coverage trend per repository")
    public Response getCoverageTrend(
            @Parameter(description = "Workspace or GitLab namespace", required = true)
            @PathParam("workspace") String workspace,

            @Parameter(description = "Branch name (default: main)")
            @QueryParam("branch") @DefaultValue("main") String branch,

            @Parameter(description = "Number of days to look back (default 90, max 365)")
            @QueryParam("days") @DefaultValue("90") int days) {

        int safeDays = Math.min(Math.max(1, days), 365);

        List<Map<String, Object>> trend = qualityReportStore.getCoverageTrendAllRepos(workspace, branch, safeDays);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workspace",  workspace);
        body.put("branch",     branch);
        body.put("periodDays", safeDays);
        body.put("trend",      trend);

        return Response.ok(body).build();
    }
}
