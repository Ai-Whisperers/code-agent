package com.eneve.agent;

import com.eneve.agent.model.ProductSecuritySummary;

import jakarta.annotation.security.RolesAllowed;
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
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

@RequestScoped
@Path("/security/issues")
@RolesAllowed({"app_staff", "app_developer", "app_admin"})
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Security", description = "Security issue tracking across products and repositories")
public class SecurityIssuesResource {

    private static final Logger LOG = Logger.getLogger(SecurityIssuesResource.class);

    @Inject SecurityIssuesCacheService cacheService;
    @Inject SecurityIssuesService securityIssuesService;

    @GET
    @Operation(
            operationId = "listSecurityIssues",
            summary = "List open security issues by product",
            description = "Returns all open Aikido vulnerability issues grouped by product and repository. "
                    + "Results are served from an in-memory cache refreshed every 5 minutes and on "
                    + "every Aikido webhook event. Pass ?refresh=true to force an immediate rebuild."
    )
    @APIResponse(responseCode = "200", description = "Security issues snapshot",
            content = @Content(schema = @Schema(implementation = ProductSecuritySummary.class)))
    public Response listSecurityIssues(
            @Parameter(description = "Force a cache rebuild before returning results")
            @QueryParam("refresh") @DefaultValue("false") boolean refresh
    ) {
        try {
            if (refresh) {
                LOG.info("Security issues: manual refresh requested");
                cacheService.invalidate();
            }
            List<ProductSecuritySummary> items = cacheService.getSnapshot();
            return Response.ok(Map.of(
                    "items",    items,
                    "cachedAt", cacheService.getCachedAt() != null
                            ? cacheService.getCachedAt().toString()
                            : ""
            )).build();
        } catch (Exception e) {
            LOG.errorf("Failed to retrieve security issues: %s", e.getMessage());
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    @GET
    @Path("/counts")
    @Operation(
            operationId = "getSecurityIssueCounts",
            summary = "Get total critical and high issue counts",
            description = "Returns the total number of open critical and high severity Aikido issues "
                    + "across all products. Reads from cache only — never triggers a rebuild. "
                    + "Intended for lightweight polling by the navigation sidebar."
    )
    @APIResponse(responseCode = "200", description = "Issue counts",
            content = @Content(schema = @Schema(example = "{\"criticals\": 12, \"highs\": 34}")))
    public Response getSecurityIssueCounts() {
        try {
            List<ProductSecuritySummary> snapshot = cacheService.getSnapshot();
            SecurityIssuesService.SecurityCounts counts = securityIssuesService.computeCounts(snapshot);
            return Response.ok(Map.of(
                    "criticals", counts.criticals(),
                    "highs",     counts.highs()
            )).build();
        } catch (Exception e) {
            LOG.errorf("Failed to compute security issue counts: %s", e.getMessage());
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }
}
