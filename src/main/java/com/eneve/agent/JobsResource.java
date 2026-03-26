package com.eneve.agent;

import java.util.List;

import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.model.JobStatus;
import com.eneve.agent.model.JobStatusResponse;
import com.eneve.agent.model.JobType;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import io.quarkus.security.Authenticated;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@RequestScoped
@Path("/jobs")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Jobs", description = "Query and manage agent jobs")
public class JobsResource {

    @Inject
    JobStore jobStore;

    @GET
    @Operation(
            operationId = "listJobs",
            summary = "List jobs",
            description = "Returns jobs from both the active queue and job history, newest first. "
                    + "Supports optional filtering by status and/or job type with pagination."
    )
    @APIResponse(responseCode = "200", description = "List of jobs",
            content = @Content(schema = @Schema(implementation = JobStatusResponse.class)))
    @APIResponse(responseCode = "400", description = "Invalid status or jobType value")
    public Response listJobs(

            @Parameter(description = "Filter by job status. One of: PENDING, QUEUED, RUNNING, SUCCESS, FAILED, AWAITING_APPROVAL")
            @QueryParam("status") String statusParam,

            @Parameter(description = "Filter by job type. One of: FIX, REVIEW, FIX_PR, GENERATE_TESTS, GENERATE_DOCS, METRICS, QUALITY_REPORT, …")
            @QueryParam("jobType") String jobTypeParam,

            @Parameter(description = "Maximum number of results to return (1–200, default 50)")
            @QueryParam("limit") @DefaultValue("50") int limit,

            @Parameter(description = "Zero-based page number for pagination (default 0)")
            @QueryParam("page") @DefaultValue("0") int page

    ) {
        JobStatus status = null;
        if (statusParam != null && !statusParam.isBlank()) {
            try {
                status = JobStatus.valueOf(statusParam.toUpperCase());
            } catch (IllegalArgumentException e) {
                return Response.status(400)
                        .entity(java.util.Map.of("error", "Invalid status: " + statusParam
                                + ". Must be one of: PENDING, QUEUED, RUNNING, SUCCESS, FAILED, AWAITING_APPROVAL"))
                        .build();
            }
        }

        JobType jobType = null;
        if (jobTypeParam != null && !jobTypeParam.isBlank()) {
            try {
                jobType = JobType.valueOf(jobTypeParam.toUpperCase());
            } catch (IllegalArgumentException e) {
                return Response.status(400)
                        .entity(java.util.Map.of("error", "Invalid jobType: " + jobTypeParam))
                        .build();
            }
        }

        int safeLimit = Math.min(Math.max(1, limit), 200);
        int offset = Math.max(0, page) * safeLimit;

        List<JobStatusResponse> jobs = jobStore.search(status, jobType, safeLimit, offset);
        return Response.ok(jobs).build();
    }
}
