package com.eneve.agent;

import com.eneve.agent.agent.store.AiAcceptanceStore;

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
 * REST endpoints for AI suggestion acceptance rate metrics.
 *
 * <p>Classifies agent review findings into accepted (resolved, no feedback),
 * rejected (marked false-positive), and ignored (not resolved, no feedback).
 */
@RequestScoped
@Path("/metrics")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "AI Acceptance", description = "AI suggestion acceptance rate metrics per repository")
public class AiAcceptanceResource {

    @Inject
    AiAcceptanceStore store;

    @GET
    @Path("/ai-acceptance/{workspace}/{repoSlug}")
    @Operation(
            operationId = "getAiAcceptance",
            summary = "AI suggestion acceptance rate summary",
            description = "Returns overall accepted/rejected/ignored counts and percentages for the repository, "
                    + "plus a breakdown grouped by repo, job type, or PR author."
    )
    @APIResponse(responseCode = "200", description = "Acceptance rate summary and breakdown")
    public Response getSummary(
            @Parameter(description = "Workspace or GitLab namespace", required = true)
            @PathParam("workspace") String workspace,

            @Parameter(description = "Repository slug", required = true)
            @PathParam("repoSlug") String repoSlug,

            @Parameter(description = "Number of days to look back (default 30, max 365)")
            @QueryParam("days") @DefaultValue("30") int days,

            @Parameter(description = "Group breakdown by 'repo', 'jobType', or 'author' (default: repo)")
            @QueryParam("groupBy") @DefaultValue("repo") String groupBy) {

        int safeDays = Math.min(Math.max(1, days), 365);
        String safeGroupBy = resolveGroupBy(groupBy);

        Map<String, Object> body = store.getSummary(workspace, repoSlug, safeDays, safeGroupBy);
        return Response.ok(body).build();
    }

    @GET
    @Path("/ai-acceptance/{workspace}/{repoSlug}/trend")
    @Operation(
            operationId = "getAiAcceptanceTrend",
            summary = "AI suggestion acceptance rate weekly trend",
            description = "Returns weekly-bucketed accepted/rejected/ignored counts and acceptance rate "
                    + "for a repository, suitable for rendering a time-series line chart."
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

    private static String resolveGroupBy(String raw) {
        if (raw == null) return "repo";
        return switch (raw.toLowerCase()) {
            case "jobtype" -> "jobType";
            case "author"  -> "author";
            default        -> "repo";
        };
    }
}
