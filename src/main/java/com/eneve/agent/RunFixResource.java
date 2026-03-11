package com.eneve.agent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

import com.eneve.agent.agent.AgentRunner;
import com.eneve.agent.agent.JobStore;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobStatus;
import com.eneve.agent.model.JobStatusResponse;
import com.eneve.agent.model.RejectRequest;
import com.eneve.agent.model.RunFixRequest;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Code Agent", description = "Automated code fix and dependency upgrade runner")
public class RunFixResource {

    private static final Logger LOG = Logger.getLogger(RunFixResource.class);
    private static final int MAX_CONCURRENT_JOBS = 3;

    @Inject AgentRunner agentRunner;
    @Inject JobStore jobStore;

    private final ExecutorService executor = Executors.newFixedThreadPool(MAX_CONCURRENT_JOBS);
    private final Semaphore semaphore = new Semaphore(MAX_CONCURRENT_JOBS);

    @POST
    @Path("/run-fix")
    @Operation(
            operationId = "runFix",
            summary = "Submit a new fix job",
            description = "Queues an agent job that clones the repo, runs the AI tool-use loop, "
                    + "validates with Maven, pushes the branch, creates a Bitbucket PR, and updates JIRA. "
                    + "Returns immediately with a jobId for polling."
    )
    @RequestBody(
            required = true,
            description = "Job specification with repo URL, branch, JIRA key, and prompt",
            content = @Content(schema = @Schema(implementation = RunFixRequest.class))
    )
    @APIResponses({
            @APIResponse(responseCode = "202", description = "Job accepted and queued",
                    content = @Content(schema = @Schema(example = "{\"jobId\": \"550e8400-e29b-41d4-a716-446655440000\"}"))),
            @APIResponse(responseCode = "400", description = "Missing required fields",
                    content = @Content(schema = @Schema(example = "{\"error\": \"repoUrl is required\"}"))),
            @APIResponse(responseCode = "429", description = "Too many concurrent jobs",
                    content = @Content(schema = @Schema(example = "{\"error\": \"Too many concurrent jobs. Max: 3\"}")))
    })
    public Response runFix(RunFixRequest request) {
        if (request.repoUrl() == null || request.repoUrl().isBlank()) {
            return Response.status(400).entity(Map.of("error", "repoUrl is required")).build();
        }
        if (request.branchName() == null || request.branchName().isBlank()) {
            return Response.status(400).entity(Map.of("error", "branchName is required")).build();
        }
        if (request.jiraKey() == null || request.jiraKey().isBlank()) {
            return Response.status(400).entity(Map.of("error", "jiraKey is required")).build();
        }
        if (request.prompt() == null || request.prompt().isBlank()) {
            return Response.status(400).entity(Map.of("error", "prompt is required")).build();
        }

        if (!semaphore.tryAcquire()) {
            return Response.status(429)
                    .entity(Map.of("error", "Too many concurrent jobs. Max: " + MAX_CONCURRENT_JOBS))
                    .build();
        }

        String jobId = UUID.randomUUID().toString();
        JobRecord job = new JobRecord(jobId, request);
        jobStore.put(job);

        executor.submit(() -> {
            try {
                agentRunner.execute(job);
            } catch (Exception e) {
                LOG.errorf("Unhandled error in job %s: %s", jobId, e.getMessage());
                job.setStatus(JobStatus.FAILED);
                job.setErrorMessage("Unhandled error: " + e.getMessage());
            } finally {
                semaphore.release();
            }
        });

        LOG.infof("Job %s accepted for %s", jobId, request.jiraKey());
        return Response.accepted(Map.of("jobId", jobId)).build();
    }

    @GET
    @Path("/status/{jobId}")
    @Operation(
            operationId = "getStatus",
            summary = "Poll job status",
            description = "Returns current status, summary, PR URL, and diff stats for the given job."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Job status",
                    content = @Content(schema = @Schema(implementation = JobStatusResponse.class))),
            @APIResponse(responseCode = "404", description = "Job not found")
    })
    public Response getStatus(
            @Parameter(description = "UUID of the job returned by POST /run-fix", required = true, example = "550e8400-e29b-41d4-a716-446655440000")
            @PathParam("jobId") String jobId) {
        return jobStore.get(jobId)
                .map(job -> Response.ok(JobStatusResponse.from(job)).build())
                .orElse(Response.status(404).entity(Map.of("error", "Job not found: " + jobId)).build());
    }

    @POST
    @Path("/jobs/{jobId}/approve")
    @Operation(
            operationId = "approveJob",
            summary = "Approve and merge the PR",
            description = "Merges the pull request in Bitbucket Cloud, transitions the JIRA issue to Done, "
                    + "and adds a final comment. Called by n8n after human approval."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "PR merged successfully",
                    content = @Content(schema = @Schema(example = "{\"status\": \"merged\", \"jobId\": \"...\"}"))),
            @APIResponse(responseCode = "404", description = "Job not found"),
            @APIResponse(responseCode = "409", description = "Job is not awaiting approval"),
            @APIResponse(responseCode = "500", description = "Merge failed")
    })
    public Response approve(
            @Parameter(description = "UUID of the job to approve", required = true)
            @PathParam("jobId") String jobId) {
        return jobStore.get(jobId).map(job -> {
            if (job.getStatus() != JobStatus.AWAITING_APPROVAL) {
                return Response.status(409)
                        .entity(Map.of("error", "Job is not awaiting approval. Current status: " + job.getStatus()))
                        .build();
            }
            try {
                agentRunner.approve(job);
                return Response.ok(Map.of("status", "merged", "jobId", jobId)).build();
            } catch (Exception e) {
                return Response.serverError()
                        .entity(Map.of("error", "Merge failed: " + e.getMessage()))
                        .build();
            }
        }).orElse(Response.status(404).entity(Map.of("error", "Job not found: " + jobId)).build());
    }

    @POST
    @Path("/jobs/{jobId}/reject")
    @Operation(
            operationId = "rejectJob",
            summary = "Reject and decline the PR",
            description = "Declines the pull request in Bitbucket Cloud and adds a JIRA comment with the rejection reason. "
                    + "Called by n8n if human rejects."
    )
    @RequestBody(
            description = "Optional rejection reason",
            content = @Content(schema = @Schema(implementation = RejectRequest.class))
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "PR declined",
                    content = @Content(schema = @Schema(example = "{\"status\": \"rejected\", \"jobId\": \"...\"}"))),
            @APIResponse(responseCode = "404", description = "Job not found"),
            @APIResponse(responseCode = "409", description = "Job is not awaiting approval")
    })
    public Response reject(
            @Parameter(description = "UUID of the job to reject", required = true)
            @PathParam("jobId") String jobId,
            RejectRequest request) {
        String reason = request != null ? request.reason() : null;
        return jobStore.get(jobId).map(job -> {
            if (job.getStatus() != JobStatus.AWAITING_APPROVAL) {
                return Response.status(409)
                        .entity(Map.of("error", "Job is not awaiting approval. Current status: " + job.getStatus()))
                        .build();
            }
            agentRunner.reject(job, reason);
            return Response.ok(Map.of("status", "rejected", "jobId", jobId)).build();
        }).orElse(Response.status(404).entity(Map.of("error", "Job not found: " + jobId)).build());
    }

    @GET
    @Path("/health")
    @Tag(name = "Health")
    @Operation(
            operationId = "healthCheck",
            summary = "Health check",
            description = "Returns service health status and available job slots."
    )
    @APIResponse(responseCode = "200", description = "Service is healthy",
            content = @Content(schema = @Schema(example = "{\"status\": \"UP\", \"availableSlots\": 3, \"maxConcurrentJobs\": 3}")))
    public Response health() {
        return Response.ok(Map.of(
                "status", "UP",
                "availableSlots", semaphore.availablePermits(),
                "maxConcurrentJobs", MAX_CONCURRENT_JOBS
        )).build();
    }
}
