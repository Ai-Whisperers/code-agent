package com.eneve.agent;

import java.util.Map;

import com.eneve.agent.exception.JobNotFoundException;
import com.eneve.agent.exception.JobQueueFullException;
import com.eneve.agent.model.AikidoFixRequest;
import com.eneve.agent.model.FixPrRequest;
import com.eneve.agent.model.GenerateDocsRequest;
import com.eneve.agent.model.GenerateTestsRequest;
import com.eneve.agent.model.JobStatusResponse;
import com.eneve.agent.model.QuickFixRequest;
import com.eneve.agent.model.ReviewPrRequest;
import com.eneve.agent.model.RunFixRequest;
import com.eneve.agent.model.SyncConfluenceRequest;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import io.quarkus.security.Authenticated;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@RequestScoped
@Authenticated
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Code Agent", description = "Automated code fix and dependency upgrade runner")
public class RunFixResource {

    @Inject
    RunFixService runFixService;

    @POST
    @Path("/run-fix")
    @RolesAllowed({"app_developer", "app_admin"})
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
            @APIResponse(responseCode = "429", description = "Job queue is full",
                    content = @Content(schema = @Schema(example = "{\"error\": \"Job queue is full\"}")))
    })
    public Response runFix(RunFixRequest request) {
        try {
            String jobId = runFixService.runFix(request);
            return Response.accepted(Map.of("jobId", jobId)).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("error", e.getMessage())).build();
        } catch (JobQueueFullException e) {
            return Response.status(429).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @Path("/quick-fix")
    @RolesAllowed({"app_developer", "app_admin"})
    @Operation(
            operationId = "quickFix",
            summary = "Submit a quick fix job from a JIRA ticket",
            description = "Simplified endpoint that only requires a JIRA key and repo URL. "
                    + "The agent fetches the issue summary and description from JIRA to use as the prompt, "
                    + "auto-generates a branch name as agent/{JIRA_KEY}-{summary-slug}, "
                    + "and always uses 'develop' as the base branch."
    )
    @RequestBody(
            required = true,
            description = "JIRA key and repository URL",
            content = @Content(schema = @Schema(implementation = QuickFixRequest.class))
    )
    @APIResponses({
            @APIResponse(responseCode = "202", description = "Job accepted and queued",
                    content = @Content(schema = @Schema(example = "{\"jobId\": \"...\", \"branch\": \"agent/JTP-10967-upgrade-cxf-xjc\"}"))),
            @APIResponse(responseCode = "400", description = "Missing required fields or JIRA fetch failed",
                    content = @Content(schema = @Schema(example = "{\"error\": \"Could not fetch JIRA issue JTP-10967\"}"))),
            @APIResponse(responseCode = "429", description = "Job queue is full")
    })
    public Response quickFix(QuickFixRequest request) {
        try {
            RunFixService.QuickFixResult result = runFixService.quickFix(request);
            return Response.accepted(Map.of("jobId", result.jobId(), "branch", result.branch())).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("error", e.getMessage())).build();
        } catch (JobQueueFullException e) {
            return Response.status(429).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @Path("/aikido-fix")
    @RolesAllowed({"app_developer", "app_admin"})
    @Tag(name = "Aikido")
    @Operation(
            operationId = "aikidoFix",
            summary = "Submit an Aikido-driven fix job",
            description = "Resolves vulnerability context from Aikido Security (package, versions, CVE, changelog) "
                    + "using either a JIRA key or an Aikido issue group ID. Builds an enriched prompt with full "
                    + "vulnerability details, auto-generates a branch name, and uses 'develop' as the base branch. "
                    + "After the PR is created, optionally triggers an Aikido CI scan to verify the fix."
    )
    @RequestBody(
            required = true,
            description = "JIRA key and/or Aikido issue group ID",
            content = @Content(schema = @Schema(implementation = AikidoFixRequest.class))
    )
    @APIResponses({
            @APIResponse(responseCode = "202", description = "Job accepted and queued",
                    content = @Content(schema = @Schema(example = "{\"jobId\": \"...\", \"branch\": \"agent/JTP-10967-upgrade-log4j\", \"aikidoIssue\": {\"package\": \"log4j-core\", \"cve\": \"CVE-2024-XXXXX\"}}"))),
            @APIResponse(responseCode = "400", description = "Missing fields or Aikido issue not found"),
            @APIResponse(responseCode = "429", description = "Job queue is full"),
            @APIResponse(responseCode = "503", description = "Aikido integration not configured")
    })
    public Response aikidoFix(AikidoFixRequest request) {
        try {
            RunFixService.AikidoFixResult r = runFixService.aikidoFix(request);
            return Response.accepted(Map.of(
                    "jobId", r.jobId(),
                    "branch", r.branch(),
                    "aikidoIssue", Map.of(
                            "groupId",        r.aikidoGroupId(),
                            "package",        r.packageName(),
                            "currentVersion", r.currentVersion(),
                            "fixedVersion",   r.fixedVersion(),
                            "cve",            r.cve(),
                            "severity",       r.severity()
                    )
            )).build();
        } catch (RunFixService.AikidoNotConfiguredException e) {
            return Response.status(503).entity(Map.of("error", e.getMessage())).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("error", e.getMessage())).build();
        } catch (JobQueueFullException e) {
            return Response.status(429).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @Path("/review-pr")
    @RolesAllowed({"app_developer", "app_admin"})
    @Tag(name = "Code Review")
    @Operation(
            operationId = "reviewPr",
            summary = "Submit a PR review job",
            description = "Queues a job that clones the repo, checks out the PR branch, computes the diff against "
                    + "the target branch, and runs an AI-powered code review. The review checks for security issues, "
                    + "design principles, code quality, testing coverage, performance, and best practices. "
                    + "The review is posted as a Bitbucket PR comment. Returns immediately with a jobId for polling."
    )
    @RequestBody(
            required = true,
            description = "PR review specification with repo URL and PR ID",
            content = @Content(schema = @Schema(implementation = ReviewPrRequest.class))
    )
    @APIResponses({
            @APIResponse(responseCode = "202", description = "Review job accepted and queued",
                    content = @Content(schema = @Schema(example = "{\"jobId\": \"550e8400-e29b-41d4-a716-446655440000\"}"))),
            @APIResponse(responseCode = "400", description = "Missing required fields",
                    content = @Content(schema = @Schema(example = "{\"error\": \"repoUrl is required\"}"))),
            @APIResponse(responseCode = "429", description = "Job queue is full",
                    content = @Content(schema = @Schema(example = "{\"error\": \"Job queue is full\"}")))
    })
    public Response reviewPr(ReviewPrRequest request) {
        try {
            String jobId = runFixService.reviewPr(request);
            return Response.accepted(Map.of("jobId", jobId, "prId", request.prId())).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("error", e.getMessage())).build();
        } catch (JobQueueFullException e) {
            return Response.status(429).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @Path("/fix-pr")
    @RolesAllowed({"app_developer", "app_admin"})
    @Tag(name = "Code Review")
    @Operation(
            operationId = "fixPr",
            summary = "Auto-fix PR review comments",
            description = "Queues a job that fetches review comments from a Bitbucket pull request, "
                    + "runs the AI agent to address each comment, and creates a new PR targeting the "
                    + "original PR's source branch. The fix PR goes through the standard approval flow. "
                    + "Returns immediately with a jobId for polling."
    )
    @RequestBody(
            required = true,
            description = "PR fix specification with repo URL and PR ID",
            content = @Content(schema = @Schema(implementation = FixPrRequest.class))
    )
    @APIResponses({
            @APIResponse(responseCode = "202", description = "Fix-PR job accepted and queued",
                    content = @Content(schema = @Schema(example = "{\"jobId\": \"550e8400-e29b-41d4-a716-446655440000\", \"prId\": \"42\"}"))),
            @APIResponse(responseCode = "400", description = "Missing required fields",
                    content = @Content(schema = @Schema(example = "{\"error\": \"repoUrl is required\"}"))),
            @APIResponse(responseCode = "429", description = "Job queue is full",
                    content = @Content(schema = @Schema(example = "{\"error\": \"Job queue is full\"}")))
    })
    public Response fixPr(FixPrRequest request) {
        try {
            String jobId = runFixService.fixPr(request);
            return Response.accepted(Map.of("jobId", jobId, "prId", request.prId())).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("error", e.getMessage())).build();
        } catch (JobQueueFullException e) {
            return Response.status(429).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @Path("/generate-tests")
    @RolesAllowed({"app_developer", "app_admin"})
    @Tag(name = "Code Review")
    @Operation(
            operationId = "generateTests",
            summary = "Submit a unit test generation job",
            description = "Queues a job that clones the repo, uses the AI agent to generate unit tests "
                    + "for the specified source files (or discovers untested classes automatically), "
                    + "validates the tests with `mvn test`, and creates a pull request with the generated tests. "
                    + "Returns immediately with a jobId for polling."
    )
    @RequestBody(
            required = true,
            description = "Unit test generation specification with repo URL and branch name",
            content = @Content(schema = @Schema(implementation = GenerateTestsRequest.class))
    )
    @APIResponses({
            @APIResponse(responseCode = "202", description = "Test generation job accepted and queued",
                    content = @Content(schema = @Schema(example = "{\"jobId\": \"550e8400-e29b-41d4-a716-446655440000\"}"))),
            @APIResponse(responseCode = "400", description = "Missing required fields",
                    content = @Content(schema = @Schema(example = "{\"error\": \"repoUrl is required\"}"))),
            @APIResponse(responseCode = "429", description = "Job queue is full",
                    content = @Content(schema = @Schema(example = "{\"error\": \"Job queue is full\"}")))
    })
    public Response generateTests(GenerateTestsRequest request) {
        try {
            String jobId = runFixService.generateTests(request);
            return Response.accepted(Map.of("jobId", jobId, "branch", request.branchName())).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("error", e.getMessage())).build();
        } catch (JobQueueFullException e) {
            return Response.status(429).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @Path("/generate-docs")
    @RolesAllowed({"app_developer", "app_admin"})
    @Tag(name = "Documentation")
    @Operation(
            operationId = "generateDocs",
            summary = "Submit a documentation generation job",
            description = "Queues a job that clones the repo, uses the AI agent to explore the codebase "
                    + "and generate comprehensive Markdown documentation in the docs/ folder. "
                    + "Includes architecture, API, data model, onboarding, flow, and configuration docs "
                    + "with Mermaid diagrams. Use POST /sync-confluence to publish docs to Confluence. "
                    + "Returns immediately with a jobId for polling."
    )
    @RequestBody(
            required = true,
            description = "Documentation generation specification",
            content = @Content(schema = @Schema(implementation = GenerateDocsRequest.class))
    )
    @APIResponses({
            @APIResponse(responseCode = "202", description = "Docs generation job accepted and queued",
                    content = @Content(schema = @Schema(example = "{\"jobId\": \"550e8400-e29b-41d4-a716-446655440000\"}"))),
            @APIResponse(responseCode = "400", description = "Missing required fields"),
            @APIResponse(responseCode = "429", description = "Job queue is full")
    })
    public Response generateDocs(GenerateDocsRequest request) {
        try {
            String jobId = runFixService.generateDocs(request);
            return Response.accepted(Map.of("jobId", jobId)).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("error", e.getMessage())).build();
        } catch (JobQueueFullException e) {
            return Response.status(429).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @Path("/sync-confluence")
    @RolesAllowed({"app_developer", "app_admin"})
    @Tag(name = "Documentation")
    @Operation(
            operationId = "syncConfluence",
            summary = "Submit a Confluence sync job",
            description = "Queues a job that clones the repo and publishes all Markdown files from the docs/ folder "
                    + "to Confluence. No AI agent loop — purely programmatic and deterministic. "
                    + "Intended for release-time syncs. Returns immediately with a jobId for polling."
    )
    @RequestBody(
            required = true,
            description = "Confluence sync specification",
            content = @Content(schema = @Schema(implementation = SyncConfluenceRequest.class))
    )
    @APIResponses({
            @APIResponse(responseCode = "202", description = "Confluence sync job accepted and queued",
                    content = @Content(schema = @Schema(example = "{\"jobId\": \"550e8400-e29b-41d4-a716-446655440000\"}"))),
            @APIResponse(responseCode = "400", description = "Missing required fields"),
            @APIResponse(responseCode = "429", description = "Job queue is full")
    })
    public Response syncConfluence(SyncConfluenceRequest request) {
        try {
            String jobId = runFixService.syncConfluence(request);
            return Response.accepted(Map.of("jobId", jobId)).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("error", e.getMessage())).build();
        } catch (JobQueueFullException e) {
            return Response.status(429).entity(Map.of("error", e.getMessage())).build();
        }
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
        try {
            return Response.ok(runFixService.getStatus(jobId)).build();
        } catch (JobNotFoundException e) {
            return Response.status(404).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @Path("/sync-jira")
    @RolesAllowed({"app_developer", "app_admin"})
    @Operation(
            operationId = "syncJira",
            summary = "Sync open issues from JIRA",
            description = "Searches JIRA for open issues with the configured agent label "
                    + "(jira.agent.label, default: WALL-E) and queues fix jobs for any that don't already have an active job. "
                    + "For each new issue, tries Aikido-enriched context first, then falls back to JIRA description."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Sync completed",
                    content = @Content(schema = @Schema(example = "{\"found\": 5, \"queued\": 2, \"skipped\": [{\"key\": \"PROJ-1\", \"reason\": \"Active job exists\"}]}"))),
            @APIResponse(responseCode = "400", description = "Agent label not configured")
    })
    public Response syncJira() {
        try {
            RunFixService.SyncJiraResult result = runFixService.syncJira();
            return Response.ok(Map.of(
                    "found",      result.found(),
                    "queued",     result.queued(),
                    "queuedJobs", result.queuedJobs(),
                    "skipped",    result.skipped()
            )).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("error", e.getMessage())).build();
        }
    }
}
