package com.eneve.agent;

import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import com.eneve.agent.model.Soc2JobSummary;

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

    @Inject ComplianceService complianceService;

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
        try {
            ComplianceService.Soc2PageResult result =
                    complianceService.listSoc2Jobs(statusParam, slaStatusParam, reviewStatusParam, limit, page);
            return Response.ok(Map.of(
                    "items", result.items(),
                    "total", result.total(),
                    "page",  result.page(),
                    "limit", result.limit()
            )).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("error", e.getMessage())).build();
        }
    }
}
