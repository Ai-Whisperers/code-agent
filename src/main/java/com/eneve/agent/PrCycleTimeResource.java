package com.eneve.agent;

import com.eneve.agent.agent.store.PrCycleTimeStore;

import io.quarkus.security.Authenticated;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST endpoints for PR cycle time metrics.
 *
 * <p>"Cycle time" here measures the elapsed hours between a PR being opened on the SCM
 * and the first agent REVIEW job being posted, and between PR open and merge.
 * There is no human-reviewer approval timestamp available; labels in the API and UI
 * explicitly say "agent review" to avoid confusion.
 */
@RequestScoped
@Path("/metrics")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "PR Cycle Time", description = "PR open-to-agent-review and open-to-merge cycle time metrics")
public class PrCycleTimeResource {

    @Inject
    PrCycleTimeStore store;

    @GET
    @Path("/pr-cycle-time/{workspace}/{repoSlug}")
    @Operation(
            operationId = "getPrCycleTime",
            summary = "PR cycle time summary",
            description = "Returns avg/p50/p95 hours from PR open to first agent review and from PR open to merge, "
                    + "grouped by repo or author within a rolling time window. "
                    + "'Agent review' refers to when the agent posted its REVIEW job, not a human reviewer."
    )
    @APIResponse(responseCode = "200", description = "Cycle time summary")
    public Response getSummary(
            @Parameter(description = "Workspace or GitLab namespace", required = true)
            @PathParam("workspace") String workspace,

            @Parameter(description = "Repository slug", required = true)
            @PathParam("repoSlug") String repoSlug,

            @Parameter(description = "Number of days to look back (default 30, max 365)")
            @QueryParam("days") @DefaultValue("30") int days,

            @Parameter(description = "Group results by 'repo' or 'author' (default: repo)")
            @QueryParam("groupBy") @DefaultValue("repo") String groupBy) {

        int safeDays = Math.min(Math.max(1, days), 365);
        String safeGroupBy = "author".equalsIgnoreCase(groupBy) ? "author" : "repo";

        List<Map<String, Object>> rows = store.getSummary(workspace, repoSlug, safeDays, safeGroupBy);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workspace",  workspace);
        body.put("repoSlug",   repoSlug);
        body.put("periodDays", safeDays);
        body.put("groupBy",    safeGroupBy);
        body.put("rows",       rows);

        return Response.ok(body).build();
    }

    @GET
    @Path("/pr-cycle-time/{workspace}/{repoSlug}/trend")
    @Operation(
            operationId = "getPrCycleTimeTrend",
            summary = "PR cycle time weekly trend",
            description = "Returns weekly-bucketed avg open-to-agent-review hours for a repository, "
                    + "suitable for rendering a time-series line chart."
    )
    @APIResponse(responseCode = "200", description = "Weekly trend data")
    public Response getTrend(
            @Parameter(description = "Workspace or GitLab namespace", required = true)
            @PathParam("workspace") String workspace,

            @Parameter(description = "Repository slug", required = true)
            @PathParam("repoSlug") String repoSlug,

            @Parameter(description = "Number of days to look back (default 90, max 365)")
            @QueryParam("days") @DefaultValue("90") int days) {

        int safeDays = Math.min(Math.max(1, days), 365);

        List<Map<String, Object>> trend = store.getTrend(workspace, repoSlug, safeDays);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workspace",  workspace);
        body.put("repoSlug",   repoSlug);
        body.put("periodDays", safeDays);
        body.put("trend",      trend);

        return Response.ok(body).build();
    }
}
