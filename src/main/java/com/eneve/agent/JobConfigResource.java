package com.eneve.agent;

import com.eneve.agent.agent.service.JobConfigService;
import com.eneve.agent.agent.service.JobConfigService.JobConfigView;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

@ApplicationScoped
@Path("/job-configs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("app_admin")
@Tag(name = "Job Configuration", description = "Per-job-type AI model and thinking configuration")
public class JobConfigResource {

    @Inject
    JobConfigService jobConfigService;

    @GET
    @Operation(summary = "List all job configurations with effective resolved values")
    public List<JobConfigView> listAll() {
        return jobConfigService.listAll();
    }

    @GET
    @Path("/{jobType}")
    @Operation(summary = "Get configuration for a specific job type")
    public Response get(@PathParam("jobType") String jobType) {
        return Response.ok(jobConfigService.getConfig(jobType.toUpperCase())).build();
    }

    @PUT
    @Path("/{jobType}")
    @Operation(summary = "Save configuration for a job type")
    public Response save(@PathParam("jobType") String jobType, JobConfigRequest request) {
        if (request == null) {
            return Response.status(400).entity("Request body required").build();
        }
        try {
            jobConfigService.save(
                    jobType.toUpperCase(),
                    request.modelTier(),
                    request.thinkingEnabled(),
                    request.thinkingBudget(),
                    request.maxOutputTokens()
            );
            return Response.ok(jobConfigService.getConfig(jobType.toUpperCase())).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(e.getMessage()).build();
        }
    }

    @DELETE
    @Path("/{jobType}")
    @Operation(summary = "Reset job configuration to defaults (removes DB override)")
    public Response reset(@PathParam("jobType") String jobType) {
        jobConfigService.reset(jobType.toUpperCase());
        return Response.ok(jobConfigService.getConfig(jobType.toUpperCase())).build();
    }

    public record JobConfigRequest(
            String modelTier,
            boolean thinkingEnabled,
            Integer thinkingBudget,
            Integer maxOutputTokens
    ) {}
}
