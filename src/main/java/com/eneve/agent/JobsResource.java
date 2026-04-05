package com.eneve.agent;

import java.util.Map;

import com.eneve.agent.agent.model.ChatEvent;
import com.eneve.agent.exception.JobConflictException;
import com.eneve.agent.exception.JobNotFoundException;
import com.eneve.agent.exception.JobQueueFullException;
import com.eneve.agent.model.CommentChatRequest;
import com.eneve.agent.model.JobDiffResponse;
import com.eneve.agent.model.JobStatusResponse;
import com.eneve.agent.model.RejectRequest;

import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Multi;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.resteasy.reactive.RestStreamElementType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import io.quarkus.security.Authenticated;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
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

import java.util.List;

@RequestScoped
@Path("/jobs")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Jobs", description = "Query and manage agent jobs")
public class JobsResource {

    private static final Logger LOG = Logger.getLogger(JobsResource.class);

    @Inject
    JobsService jobsService;

    @Inject
    RunFixService runFixService;

    @GET
    @Path("/status/{jobId}")
    @Operation(
            operationId = "getJobStatus",
            summary = "Get status of a single job",
            description = "Returns current status, summary, and error message for the given job."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Job status",
                    content = @Content(schema = @Schema(implementation = JobStatusResponse.class))),
            @APIResponse(responseCode = "404", description = "Job not found")
    })
    public Response getJobStatus(
            @Parameter(description = "UUID of the job", required = true)
            @PathParam("jobId") String jobId) {
        try {
            return Response.ok(runFixService.getStatus(jobId)).build();
        } catch (JobNotFoundException e) {
            return Response.status(404).entity(Map.of("error", e.getMessage())).build();
        }
    }

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
        try {
            List<JobStatusResponse> jobs = jobsService.listJobs(statusParam, jobTypeParam, limit, page);
            return Response.ok(jobs).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @GET
    @Path("/{jobId}/diff")
    @Operation(
            operationId = "getJobDiff",
            summary = "Get PR diff for a job",
            description = "Fetches the unified diff from the SCM for the pull request associated with this job. "
                    + "Returns per-file diff entries with line-level additions and deletions."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Diff data",
                    content = @Content(schema = @Schema(implementation = JobDiffResponse.class))),
            @APIResponse(responseCode = "404", description = "Job not found or has no pull request"),
            @APIResponse(responseCode = "503", description = "SCM diff unavailable")
    })
    public Response getJobDiff(
            @Parameter(description = "UUID of the job", required = true)
            @PathParam("jobId") String jobId) {
        try {
            return Response.ok(jobsService.getJobDiff(jobId)).build();
        } catch (JobNotFoundException e) {
            return Response.status(404).entity(Map.of("error", e.getMessage())).build();
        } catch (Exception e) {
            return Response.status(503).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @GET
    @Path("/{jobId}/commits")
    @Operation(operationId = "getJobCommits", summary = "List commits in the PR for a job",
               description = "Returns an ordered list of commits that belong to the pull request associated with this job.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Commit list"),
            @APIResponse(responseCode = "404", description = "Job not found or has no pull request")
    })
    public Response getJobCommits(
            @Parameter(description = "UUID of the job", required = true)
            @PathParam("jobId") String jobId) {
        try {
            return Response.ok(jobsService.getJobCommits(jobId)).build();
        } catch (JobNotFoundException e) {
            return Response.status(404).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @GET
    @Path("/{jobId}/commits/{sha}/diff")
    @Operation(operationId = "getCommitDiff", summary = "Get the diff for a single commit",
               description = "Fetches the unified diff for the given commit SHA from the SCM. "
                           + "Returns the same per-file diff structure as the PR diff endpoint. "
                           + "Returns an empty file list if the platform does not support per-commit diffs.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Commit diff data"),
            @APIResponse(responseCode = "404", description = "Job not found or has no pull request")
    })
    public Response getCommitDiff(
            @Parameter(description = "UUID of the job", required = true)
            @PathParam("jobId") String jobId,
            @Parameter(description = "Full or abbreviated commit SHA", required = true)
            @PathParam("sha") String sha) {
        try {
            return Response.ok(jobsService.getCommitDiff(jobId, sha)).build();
        } catch (JobNotFoundException e) {
            return Response.status(404).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @GET
    @Path("/{jobId}/review")
    @Operation(operationId = "getJobReview", summary = "Get PR review for a job",
               description = "Returns the bot review summary and inline comments for the PR. "
                           + "Also surfaces the linked REVIEW job status for polling.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Review data"),
            @APIResponse(responseCode = "404", description = "Job not found or has no PR")
    })
    public Response getJobReview(@PathParam("jobId") String jobId) {
        try {
            return Response.ok(jobsService.getJobReview(jobId)).build();
        } catch (JobNotFoundException e) {
            return Response.status(404).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @Path("/{jobId}/request-review")
    @RolesAllowed({"app_developer", "app_admin"})
    @Operation(operationId = "requestReview", summary = "Request a bot review",
               description = "Submits a new REVIEW job for the PR linked to this job. "
                           + "Returns 409 if a review is already running or pending.")
    @APIResponses({
            @APIResponse(responseCode = "202", description = "Review job submitted"),
            @APIResponse(responseCode = "404", description = "Job not found or has no PR"),
            @APIResponse(responseCode = "409", description = "Review already running")
    })
    public Response requestReview(@PathParam("jobId") String jobId) {
        try {
            String reviewJobId = jobsService.requestReview(jobId);
            return Response.accepted(Map.of("reviewJobId", reviewJobId)).build();
        } catch (JobNotFoundException e) {
            return Response.status(404).entity(Map.of("error", e.getMessage())).build();
        } catch (JobConflictException e) {
            return Response.status(409).entity(Map.of("error", e.getMessage())).build();
        } catch (JobQueueFullException e) {
            return Response.status(429).entity(Map.of("error", e.getMessage())).build();
        } catch (Exception e) {
            return Response.status(404).entity(Map.of("error", "Cannot resolve repository: " + e.getMessage())).build();
        }
    }

    @POST
    @Path("/{jobId}/request-fix-pr")
    @RolesAllowed({"app_developer", "app_admin"})
    @Operation(operationId = "requestFixPr", summary = "Request an automated fix for all PR review comments",
               description = "Queues a FIX_PR job that addresses all open bot review comments on the PR "
                           + "linked to this job. Returns 409 if a fix-PR job is already running.")
    @APIResponses({
            @APIResponse(responseCode = "202", description = "Fix-PR job submitted"),
            @APIResponse(responseCode = "404", description = "Job not found or has no PR"),
            @APIResponse(responseCode = "409", description = "Fix-PR already running")
    })
    public Response requestFixPr(@PathParam("jobId") String jobId) {
        try {
            String fixPrJobId = jobsService.requestFixPr(jobId);
            return Response.accepted(Map.of("fixPrJobId", fixPrJobId)).build();
        } catch (JobNotFoundException e) {
            return Response.status(404).entity(Map.of("error", e.getMessage())).build();
        } catch (JobConflictException e) {
            return Response.status(409).entity(Map.of("error", e.getMessage())).build();
        } catch (JobQueueFullException e) {
            return Response.status(429).entity(Map.of("error", e.getMessage())).build();
        } catch (Exception e) {
            return Response.status(404).entity(Map.of("error", "Cannot resolve repository: " + e.getMessage())).build();
        }
    }

    @POST
    @Path("/{jobId}/request-fix-comment")
    @RolesAllowed({"app_developer", "app_admin"})
    @Operation(operationId = "requestFixComment", summary = "Request an automated fix for a single review comment",
               description = "Queues a FIX_COMMENT job to address a specific bot review comment on the PR "
                           + "linked to this job. Requires the SCM platform comment ID.")
    @APIResponses({
            @APIResponse(responseCode = "202", description = "Fix-comment job submitted"),
            @APIResponse(responseCode = "400", description = "Missing commentId"),
            @APIResponse(responseCode = "404", description = "Job not found, has no PR, or comment not found")
    })
    public Response requestFixComment(@PathParam("jobId") String jobId, Map<String, Object> body) {
        Object rawId = body != null ? body.get("commentId") : null;
        if (rawId == null) {
            return Response.status(400).entity(Map.of("error", "commentId is required")).build();
        }
        long commentId;
        try {
            commentId = ((Number) rawId).longValue();
        } catch (ClassCastException e) {
            return Response.status(400).entity(Map.of("error", "commentId must be a number")).build();
        }
        String filePath = body.containsKey("filePath") ? String.valueOf(body.get("filePath")) : "";
        int line = body.containsKey("line") ? ((Number) body.get("line")).intValue() : 0;

        try {
            String fixCommentJobId = jobsService.requestFixComment(jobId, commentId, filePath, line);
            return Response.accepted(Map.of("fixCommentJobId", fixCommentJobId)).build();
        } catch (JobNotFoundException e) {
            return Response.status(404).entity(Map.of("error", e.getMessage())).build();
        } catch (JobQueueFullException e) {
            return Response.status(429).entity(Map.of("error", e.getMessage())).build();
        } catch (Exception e) {
            return Response.status(404).entity(Map.of("error", "Cannot resolve repository: " + e.getMessage())).build();
        }
    }

    @POST
    @Path("/{jobId}/resolve-comment")
    @RolesAllowed({"app_developer", "app_admin"})
    @Operation(operationId = "resolveComment", summary = "Resolve a review comment",
               description = "Marks a bot review comment as resolved both in the platform SCM and in the local store.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Comment resolved"),
            @APIResponse(responseCode = "400", description = "Missing commentId"),
            @APIResponse(responseCode = "404", description = "Job not found")
    })
    public Response resolveComment(@PathParam("jobId") String jobId, Map<String, Object> body) {
        Object rawId = body != null ? body.get("commentId") : null;
        if (rawId == null) {
            return Response.status(400).entity(Map.of("error", "commentId is required")).build();
        }
        long commentId;
        try {
            commentId = ((Number) rawId).longValue();
        } catch (ClassCastException e) {
            return Response.status(400).entity(Map.of("error", "commentId must be a number")).build();
        }

        try {
            jobsService.resolveComment(jobId, commentId);
            return Response.ok(Map.of("resolved", true)).build();
        } catch (JobNotFoundException e) {
            return Response.status(404).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @Path("/{jobId}/false-positive")
    @RolesAllowed({"app_developer", "app_admin"})
    @Operation(operationId = "markFalsePositive", summary = "Mark a review comment as a false positive",
               description = "Records false-positive feedback, resolves the comment, and posts a reply to the PR thread.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Marked as false positive"),
            @APIResponse(responseCode = "400", description = "Missing commentId"),
            @APIResponse(responseCode = "404", description = "Job not found")
    })
    public Response markFalsePositive(@PathParam("jobId") String jobId, Map<String, Object> body) {
        Object rawId = body != null ? body.get("commentId") : null;
        if (rawId == null) {
            return Response.status(400).entity(Map.of("error", "commentId is required")).build();
        }
        long commentId;
        try {
            commentId = ((Number) rawId).longValue();
        } catch (ClassCastException e) {
            return Response.status(400).entity(Map.of("error", "commentId must be a number")).build();
        }

        try {
            jobsService.markFalsePositive(jobId, commentId);
            return Response.ok(Map.of("falsePositive", true)).build();
        } catch (JobNotFoundException e) {
            return Response.status(404).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @Path("/{jobId}/reply-comment")
    @RolesAllowed({"app_developer", "app_admin"})
    @Operation(operationId = "replyToComment", summary = "Reply to a review comment",
               description = "Posts a reply to a bot review comment on the pull request.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Reply posted"),
            @APIResponse(responseCode = "400", description = "Missing commentId or message"),
            @APIResponse(responseCode = "404", description = "Job not found")
    })
    public Response replyToComment(@PathParam("jobId") String jobId, Map<String, Object> body) {
        Object rawId = body != null ? body.get("commentId") : null;
        Object rawMsg = body != null ? body.get("message") : null;
        if (rawId == null) {
            return Response.status(400).entity(Map.of("error", "commentId is required")).build();
        }
        if (rawMsg == null || rawMsg.toString().isBlank()) {
            return Response.status(400).entity(Map.of("error", "message is required")).build();
        }
        long commentId;
        try {
            commentId = ((Number) rawId).longValue();
        } catch (ClassCastException e) {
            return Response.status(400).entity(Map.of("error", "commentId must be a number")).build();
        }
        String message = rawMsg.toString().trim();

        try {
            long replyId = jobsService.replyToComment(jobId, commentId, message);
            return Response.ok(Map.of("replyId", replyId)).build();
        } catch (JobNotFoundException e) {
            return Response.status(404).entity(Map.of("error", e.getMessage())).build();
        } catch (Exception e) {
            LOG.errorf("Failed to post reply for comment %d: %s", commentId, e.getMessage());
            return Response.status(500).entity(Map.of("error", "Failed to post reply: " + e.getMessage())).build();
        }
    }

    @GET
    @Path("/{jobId}/evidence")
    @Operation(operationId = "getJobEvidence", summary = "Get SOC II evidence for a job",
               description = "Returns compliance checks derived from the immutable audit log "
                           + "and the full audit trail for the job.")
    @APIResponse(responseCode = "200", description = "Evidence data")
    @APIResponse(responseCode = "404", description = "Job not found")
    public Response getJobEvidence(@PathParam("jobId") String jobId) {
        try {
            return Response.ok(jobsService.getJobEvidence(jobId)).build();
        } catch (JobNotFoundException e) {
            return Response.status(404).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @Path("/{jobId}/evidence/upload-scytale")
    @RolesAllowed({"app_developer", "app_admin"})
    @Operation(operationId = "uploadScytaleEvidence", summary = "Upload SOC II evidence to Scytale",
               description = "Manually triggers evidence upload to Scytale for this job's compliance record.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Upload succeeded"),
            @APIResponse(responseCode = "404", description = "Job not found"),
            @APIResponse(responseCode = "503", description = "Scytale not configured or upload failed")
    })
    public Response uploadScytaleEvidence(@PathParam("jobId") String jobId) {
        try {
            String ref = jobsService.uploadScytaleEvidence(jobId);
            return Response.ok(Map.of("ref", ref)).build();
        } catch (JobNotFoundException e) {
            return Response.status(404).entity(Map.of("error", e.getMessage())).build();
        } catch (Exception e) {
            return Response.status(503).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @Path("/{jobId}/approve")
    @RolesAllowed({"app_developer", "app_admin"})
    @Operation(operationId = "approveJob", summary = "Approve and merge the pull request",
               description = "Merges the PR, archives the job, logs audit events, triggers JIRA transitions, "
                       + "and (for bug PRs targeting protected branches) schedules a promotion PR.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "PR merged successfully"),
            @APIResponse(responseCode = "404", description = "Job not found"),
            @APIResponse(responseCode = "409", description = "Job is not awaiting approval"),
            @APIResponse(responseCode = "422", description = "SOC2 guard blocked the merge"),
            @APIResponse(responseCode = "500", description = "Merge failed")
    })
    public Response approveJob(@PathParam("jobId") String jobId) {
        try {
            jobsService.approveJob(jobId);
            return Response.ok(Map.of("status", "merged", "jobId", jobId)).build();
        } catch (JobNotFoundException e) {
            return Response.status(404).entity(Map.of("error", e.getMessage())).build();
        } catch (JobConflictException e) {
            return Response.status(409).entity(Map.of("error", e.getMessage())).build();
        } catch (JobsService.Soc2GuardException e) {
            return Response.status(422).entity(Map.of("error", e.getMessage())).build();
        } catch (Exception e) {
            LOG.errorf("approve failed for job %s: %s", jobId, e.getMessage());
            return Response.status(500).entity(Map.of("error", "Merge failed: " + e.getMessage())).build();
        }
    }

    @POST
    @Path("/{jobId}/comment-chat")
    @Blocking
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    @Operation(
            operationId = "commentChat",
            summary = "Discuss a review comment with the AI",
            description = "Opens a stateless streaming chat session about a specific review comment. "
                    + "The full message history must be sent on every request. "
                    + "The AI can resolve the comment, mark it as a false positive, or start an automated fix as a conversation conclusion. "
                    + "No conversation history is stored in the database."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "SSE stream of ChatEvent objects"),
            @APIResponse(responseCode = "400", description = "Missing or invalid request body"),
            @APIResponse(responseCode = "404", description = "Job or comment not found")
    })
    public Multi<ChatEvent> commentChat(
            @PathParam("jobId") String jobId,
            CommentChatRequest request) {

        if (request == null || request.commentId() <= 0) {
            return Multi.createFrom().item(new ChatEvent.Error("commentId is required"));
        }
        return jobsService.commentChat(jobId, request);
    }

    @POST
    @Path("/{jobId}/rerun")
    @RolesAllowed({"app_developer", "app_admin"})
    @Operation(
            operationId = "rerunJob",
            summary = "Rerun a failed or finished job",
            description = "Creates a new job with the same parameters as the original and queues it for execution."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "New job queued",
                    content = @Content(schema = @Schema(example = "{\"status\": \"queued\", \"jobId\": \"...\", \"originalJobId\": \"...\"}"))),
            @APIResponse(responseCode = "404", description = "Job not found"),
            @APIResponse(responseCode = "409", description = "Job is not in a rerunnable state")
    })
    public Response rerunJob(
            @Parameter(description = "UUID of the job to rerun", required = true)
            @PathParam("jobId") String jobId) {
        try {
            String newJobId = runFixService.rerunJob(jobId);
            return Response.ok(Map.of("status", "queued", "jobId", newJobId, "originalJobId", jobId)).build();
        } catch (JobNotFoundException e) {
            return Response.status(404).entity(Map.of("error", e.getMessage())).build();
        } catch (JobConflictException e) {
            return Response.status(409).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @Path("/{jobId}/cancel")
    @RolesAllowed({"app_developer", "app_admin"})
    @Operation(
            operationId = "cancelJob",
            summary = "Cancel a queued job",
            description = "Cancels a PENDING or QUEUED job, removing it from the dispatch queue."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Job cancelled",
                    content = @Content(schema = @Schema(example = "{\"status\": \"cancelled\", \"jobId\": \"...\"}"))),
            @APIResponse(responseCode = "404", description = "Job not found"),
            @APIResponse(responseCode = "409", description = "Job is not in a cancellable state")
    })
    public Response cancelJob(
            @Parameter(description = "UUID of the job to cancel", required = true)
            @PathParam("jobId") String jobId) {
        try {
            runFixService.cancelJob(jobId);
            return Response.ok(Map.of("status", "cancelled", "jobId", jobId)).build();
        } catch (JobNotFoundException e) {
            return Response.status(404).entity(Map.of("error", e.getMessage())).build();
        } catch (RunFixService.Soc2DeletionBlockedException e) {
            return Response.status(403).entity(Map.of("error", e.getMessage())).build();
        } catch (JobConflictException e) {
            return Response.status(409).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @Path("/{jobId}/reject")
    @RolesAllowed({"app_developer", "app_admin"})
    @Operation(
            operationId = "rejectJob",
            summary = "Reject and decline the PR",
            description = "Declines the pull request in the SCM and adds a JIRA comment with the rejection reason."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "PR declined",
                    content = @Content(schema = @Schema(example = "{\"status\": \"rejected\", \"jobId\": \"...\"}"))),
            @APIResponse(responseCode = "404", description = "Job not found"),
            @APIResponse(responseCode = "409", description = "Job is not awaiting approval")
    })
    public Response rejectJob(
            @Parameter(description = "UUID of the job to reject", required = true)
            @PathParam("jobId") String jobId,
            RejectRequest request) {
        try {
            runFixService.reject(jobId, request);
            return Response.ok(Map.of("status", "rejected", "jobId", jobId)).build();
        } catch (JobNotFoundException e) {
            return Response.status(404).entity(Map.of("error", e.getMessage())).build();
        } catch (JobConflictException e) {
            return Response.status(409).entity(Map.of("error", e.getMessage())).build();
        }
    }
}
