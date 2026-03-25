package com.eneve.agent;

import com.eneve.agent.agent.store.DeveloperMetricsStore;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST endpoint exposing per-developer review quality scorecards.
 * Aggregates review job and finding data by PR author within a rolling time window.
 */
@Path("/metrics")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Developer Scorecard", description = "Per-developer PR review quality metrics")
public class DeveloperScorecardResource {

    @Inject
    DeveloperMetricsStore metricsStore;

    @GET
    @Path("/developer-scorecard/{workspace}/{repoSlug}")
    @Operation(
            operationId = "getDeveloperScorecard",
            summary = "Get per-developer review quality scorecard",
            description = "Returns a ranked list of PR authors with their review metrics "
                    + "(findings count, resolution rate, total PRs reviewed) for a given repo "
                    + "within a rolling time window."
    )
    @APIResponse(responseCode = "200", description = "Developer scorecard for the repository")
    public Response getScorecard(
            @Parameter(description = "Workspace or GitLab namespace", required = true)
            @PathParam("workspace") String workspace,

            @Parameter(description = "Repository slug", required = true)
            @PathParam("repoSlug") String repoSlug,

            @Parameter(description = "Number of days to look back (default 30, max 365)")
            @QueryParam("days") @DefaultValue("30") int days) {

        int safeDays = Math.min(Math.max(1, days), 365);

        List<Map<String, Object>> authors = metricsStore.scorecardByAuthor(workspace, repoSlug, safeDays);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workspace",  workspace);
        body.put("repoSlug",   repoSlug);
        body.put("periodDays", safeDays);
        body.put("authors",    authors);

        return Response.ok(body).build();
    }
}
