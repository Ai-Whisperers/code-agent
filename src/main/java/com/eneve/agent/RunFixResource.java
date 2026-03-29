package com.eneve.agent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.eneve.agent.agent.AgentRunner;
import com.eneve.agent.agent.JobQueue;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.aikido.AikidoIssueInfo;
import com.eneve.agent.aikido.AikidoService;
import com.eneve.agent.audit.AuditService;
import com.eneve.agent.jira.JiraService;
import com.eneve.agent.model.AikidoFixRequest;
import com.eneve.agent.model.FixPrRequest;
import com.eneve.agent.model.GenerateDocsRequest;
import com.eneve.agent.model.GenerateTestsRequest;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobStatus;
import com.eneve.agent.model.JobStatusResponse;
import com.eneve.agent.model.JobType;
import com.eneve.agent.model.QuickFixRequest;
import com.eneve.agent.model.RejectRequest;
import com.eneve.agent.model.RepoCoordinates;
import com.eneve.agent.model.ReviewPrRequest;
import com.eneve.agent.model.RunFixRequest;
import com.eneve.agent.model.SyncConfluenceRequest;
import com.eneve.agent.settings.SettingsService;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

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

    private static final Logger LOG = Logger.getLogger(RunFixResource.class);

    @Inject AgentRunner agentRunner;
    @Inject JobQueue jobQueue;
    @Inject JobStore jobStore;
    @Inject JiraService jiraService;
    @Inject AikidoService aikidoService;
    @Inject AuditService auditService;
    @Inject SettingsService settings;

    private String agentLabel()    { return settings.get("jira.agent.label", "WALL-E"); }
    private String defaultRepoUrl() { return settings.get("jira.agent.default-repo-url", ""); }

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
        if (request.repoUrl() == null || request.repoUrl().isBlank()) {
            return Response.status(400).entity(Map.of("error", "repoUrl is required")).build();
        }
        if (request.branchName() == null || request.branchName().isBlank()) {
            return Response.status(400).entity(Map.of("error", "branchName is required")).build();
        }
        if (request.jiraKey() == null || request.jiraKey().isBlank()) {
            return Response.status(400).entity(Map.of("error", "jiraKey is required")).build();
        }

        String jobId = UUID.randomUUID().toString();
        JobRecord job = new JobRecord(jobId, request);
        jobStore.put(job);

        if (!jobQueue.submit(job)) {
            return Response.status(429).entity(Map.of("error", "Job queue is full")).build();
        }

        LOG.infof("Job %s accepted for %s", jobId, request.jiraKey());
        auditService.log("JOBS", "JOB_SUBMITTED", "job", jobId,
                Map.of("jobType", "FIX", "jiraKey", request.jiraKey()));
        return Response.accepted(Map.of("jobId", jobId)).build();
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
        if (request.repoUrl() == null || request.repoUrl().isBlank()) {
            return Response.status(400).entity(Map.of("error", "repoUrl is required")).build();
        }
        if (request.jiraKey() == null || request.jiraKey().isBlank()) {
            return Response.status(400).entity(Map.of("error", "jiraKey is required")).build();
        }

        String summary;
        try {
            summary = jiraService.fetchIssueSummary(request.jiraKey());
        } catch (Exception e) {
            return Response.status(400)
                    .entity(Map.of("error", "Failed to fetch JIRA issue: " + e.getMessage()))
                    .build();
        }
        if (summary == null || summary.isBlank()) {
            return Response.status(400)
                    .entity(Map.of("error", "Could not fetch JIRA issue " + request.jiraKey()))
                    .build();
        }

        String branchName = "agent/" + request.jiraKey() + "-" + slugify(summary);

        RunFixRequest fullRequest = new RunFixRequest(
                request.repoUrl(),
                branchName,
                request.jiraKey(),
                null,
                "develop",
                null, null, null, null, null, null
        );

        String jobId = UUID.randomUUID().toString();
        JobRecord job = new JobRecord(jobId, fullRequest);
        jobStore.put(job);

        if (!jobQueue.submit(job)) {
            return Response.status(429).entity(Map.of("error", "Job queue is full")).build();
        }

        LOG.infof("Quick-fix job %s accepted for %s (branch: %s)", jobId, request.jiraKey(), branchName);
        auditService.log("JOBS", "JOB_SUBMITTED", "job", jobId,
                Map.of("jobType", "QUICK_FIX", "jiraKey", request.jiraKey()));
        return Response.accepted(Map.of("jobId", jobId, "branch", branchName)).build();
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
        if (!aikidoService.isEnabled()) {
            return Response.status(503)
                    .entity(Map.of("error", "Aikido integration not configured. Set AIKIDO_CLIENT_ID and AIKIDO_CLIENT_SECRET."))
                    .build();
        }

        if ((request.jiraKey() == null || request.jiraKey().isBlank())
                && request.aikidoGroupId() == null) {
            return Response.status(400)
                    .entity(Map.of("error", "Either jiraKey or aikidoGroupId is required"))
                    .build();
        }

        // 1. Resolve Aikido issue group ID (try multiple strategies)
        Integer groupId = request.aikidoGroupId();
        if (groupId == null) {
            groupId = aikidoService.findIssueGroupByJiraKey(request.jiraKey());
        }
        // 1b. Fallback: check JIRA description for Aikido URLs and try each candidate
        AikidoIssueInfo issueInfo = null;
        if (groupId != null) {
            issueInfo = aikidoService.getIssueGroupDetail(groupId);
        }

        JiraService.JiraDescriptionContext descCtx = null;
        if (issueInfo == null && request.jiraKey() != null) {
            LOG.infof("Aikido API lookup failed for %s, checking JIRA description for Aikido URL", request.jiraKey());
            descCtx = jiraService.extractDescriptionContext(request.jiraKey());
            for (Integer candidateId : descCtx.aikidoCandidateIds()) {
                LOG.infof("Trying Aikido candidate ID: %d", candidateId);
                issueInfo = aikidoService.getIssueGroupDetail(candidateId);
                if (issueInfo != null) {
                    groupId = candidateId;
                    LOG.infof("Aikido issue resolved via JIRA description: group ID %d", groupId);
                    break;
                }
            }
        }

        if (issueInfo == null) {
            return Response.status(400)
                    .entity(Map.of("error", "No Aikido issue found for JIRA key: "
                            + request.jiraKey() + ". Checked: Aikido linked issues, JIRA description for Aikido URL."))
                    .build();
        }

        // 3. Resolve repo URL (with container-to-repo fallback)
        String repoUrl = request.repoUrl();
        if (repoUrl == null || repoUrl.isBlank()) {
            repoUrl = issueInfo.repoUrl();
        }
        if (repoUrl == null || repoUrl.isBlank()) {
            repoUrl = resolveRepoUrlFromContainer(issueInfo, descCtx, request.jiraKey());
        }
        if (repoUrl == null || repoUrl.isBlank()) {
            return Response.status(400)
                    .entity(Map.of("error", "Could not resolve repository URL from Aikido. "
                            + "Issue references a container image"
                            + (issueInfo.containerImage() != null ? " (" + issueInfo.containerImage() + ")" : "")
                            + " but no matching code repo was found. Provide repoUrl explicitly."))
                    .build();
        }

        // 4. Resolve JIRA key (may come from request or we derive it)
        String jiraKey = request.jiraKey();
        if (jiraKey == null || jiraKey.isBlank()) {
            jiraKey = "AIKIDO-" + groupId;
        }

        // 5. Build enriched prompt and branch name
        String prompt = issueInfo.toPromptSection();
        String branchSlug = slugify(issueInfo.packageName() + "-" + (issueInfo.fixedVersion() != null
                ? issueInfo.fixedVersion() : "fix"));
        String branchName = "agent/" + jiraKey + "-" + branchSlug;

        RunFixRequest fullRequest = new RunFixRequest(
                repoUrl,
                branchName,
                jiraKey,
                prompt,
                "develop",
                null, null,
                request.ruleNames(),
                request.extraRules(),
                null, null
        );

        String jobId = UUID.randomUUID().toString();
        JobRecord job = new JobRecord(jobId, fullRequest);
        jobStore.put(job);

        if (!jobQueue.submit(job)) {
            return Response.status(429).entity(Map.of("error", "Job queue is full")).build();
        }

        LOG.infof("Aikido-fix job %s accepted for %s (group=%d, package=%s, branch=%s)",
                jobId, jiraKey, groupId, issueInfo.packageName(), branchName);
        auditService.log("JOBS", "JOB_SUBMITTED", "job", jobId,
                Map.of("jobType", "AIKIDO_FIX", "jiraKey", jiraKey,
                       "aikidoGroupId", String.valueOf(groupId)));

        return Response.accepted(Map.of(
                "jobId", jobId,
                "branch", branchName,
                "aikidoIssue", Map.of(
                        "groupId", groupId,
                        "package", issueInfo.packageName(),
                        "currentVersion", issueInfo.currentVersion() != null ? issueInfo.currentVersion() : "",
                        "fixedVersion", issueInfo.fixedVersion() != null ? issueInfo.fixedVersion() : "",
                        "cve", issueInfo.cveId() != null ? issueInfo.cveId() : "",
                        "severity", issueInfo.severity()
                )
        )).build();
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
        if (request.repoUrl() == null || request.repoUrl().isBlank()) {
            return Response.status(400).entity(Map.of("error", "repoUrl is required")).build();
        }
        if (request.prId() == null || request.prId().isBlank()) {
            return Response.status(400).entity(Map.of("error", "prId is required")).build();
        }

        String jobId = UUID.randomUUID().toString();
        JobRecord job = new JobRecord(jobId, request);
        if (request.prAuthor() != null && !request.prAuthor().isBlank()) {
            job.setPrAuthor(request.prAuthor());
        }
        jobStore.put(job);

        if (!jobQueue.submit(job)) {
            return Response.status(429).entity(Map.of("error", "Job queue is full")).build();
        }

        LOG.infof("Review job %s accepted for PR #%s on %s", jobId, request.prId(), request.repoUrl());
        auditService.log("JOBS", "JOB_SUBMITTED", "job", jobId,
                Map.of("jobType", "REVIEW", "prId", request.prId()));
        return Response.accepted(Map.of("jobId", jobId, "prId", request.prId())).build();
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
        if (request.repoUrl() == null || request.repoUrl().isBlank()) {
            return Response.status(400).entity(Map.of("error", "repoUrl is required")).build();
        }
        if (request.prId() == null || request.prId().isBlank()) {
            return Response.status(400).entity(Map.of("error", "prId is required")).build();
        }

        String jobId = UUID.randomUUID().toString();
        JobRecord job = new JobRecord(jobId, request);
        jobStore.put(job);

        if (!jobQueue.submit(job)) {
            return Response.status(429).entity(Map.of("error", "Job queue is full")).build();
        }

        LOG.infof("Fix-PR job %s accepted for PR #%s on %s", jobId, request.prId(), request.repoUrl());
        auditService.log("JOBS", "JOB_SUBMITTED", "job", jobId,
                Map.of("jobType", "FIX_PR", "prId", request.prId()));
        return Response.accepted(Map.of("jobId", jobId, "prId", request.prId())).build();
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
        if (request.repoUrl() == null || request.repoUrl().isBlank()) {
            return Response.status(400).entity(Map.of("error", "repoUrl is required")).build();
        }
        if (request.branchName() == null || request.branchName().isBlank()) {
            return Response.status(400).entity(Map.of("error", "branchName is required")).build();
        }

        String jobId = UUID.randomUUID().toString();
        JobRecord job = new JobRecord(jobId, request);
        jobStore.put(job);

        if (!jobQueue.submit(job)) {
            return Response.status(429).entity(Map.of("error", "Job queue is full")).build();
        }

        LOG.infof("GenerateTests job %s accepted for %s (branch: %s)", jobId, request.repoUrl(), request.branchName());
        auditService.log("JOBS", "JOB_SUBMITTED", "job", jobId,
                Map.of("jobType", "GENERATE_TESTS", "branch", request.branchName()));
        return Response.accepted(Map.of("jobId", jobId, "branch", request.branchName())).build();
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
        if (request.repoUrl() == null || request.repoUrl().isBlank()) {
            return Response.status(400).entity(Map.of("error", "repoUrl is required")).build();
        }

        String branchName = request.branchName();
        if (!request.isCommitDirect() && (branchName == null || branchName.isBlank())) {
            branchName = "agent/generate-docs";
        }

        String jobId = UUID.randomUUID().toString();
        GenerateDocsRequest effective = new GenerateDocsRequest(
                request.repoUrl(), branchName, request.targetBranch(),
                request.ruleNames(), request.extraRules(), request.n8nWebhookUrl(),
                request.commitDirect());
        JobRecord job = new JobRecord(jobId, effective);
        jobStore.put(job);

        if (!jobQueue.submit(job)) {
            return Response.status(429).entity(Map.of("error", "Job queue is full")).build();
        }

        LOG.infof("GenerateDocs job %s accepted for %s", jobId, request.repoUrl());
        auditService.log("JOBS", "JOB_SUBMITTED", "job", jobId,
                Map.of("jobType", "GENERATE_DOCS"));
        return Response.accepted(Map.of("jobId", jobId)).build();
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
        if (request.repoUrl() == null || request.repoUrl().isBlank()) {
            return Response.status(400).entity(Map.of("error", "repoUrl is required")).build();
        }

        String jobId = UUID.randomUUID().toString();
        JobRecord job = new JobRecord(jobId, request);
        jobStore.put(job);

        if (!jobQueue.submit(job)) {
            return Response.status(429).entity(Map.of("error", "Job queue is full")).build();
        }

        LOG.infof("SyncConfluence job %s accepted for %s", jobId, request.repoUrl());
        auditService.log("JOBS", "JOB_SUBMITTED", "job", jobId,
                Map.of("jobType", "SYNC_CONFLUENCE"));
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
                .map(job -> Response.ok(buildStatusResponse(job, jobId)).build())
                .orElse(Response.status(404).entity(Map.of("error", "Job not found: " + jobId)).build());
    }

    @POST
    @Path("/jobs/{jobId}/reject")
    @RolesAllowed({"app_developer", "app_admin"})
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
            auditService.log("JOBS", "JOB_REJECTED", "job", jobId,
                    reason != null ? Map.of("reason", reason) : null);
            return Response.ok(Map.of("status", "rejected", "jobId", jobId)).build();
        }).orElse(Response.status(404).entity(Map.of("error", "Job not found: " + jobId)).build());
    }

    @POST
    @Path("/jobs/{jobId}/cancel")
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
        return jobStore.get(jobId).map(job -> {
            // SOC II deletion guard
            String bugIssueTypes = settings.get("soc2.bug-issue-types", "Bug,Defect");
            if (com.eneve.agent.agent.store.JobStore.isSoc2Applicable(job, bugIssueTypes)) {
                auditService.log("SOC2", "SOC2_DELETE_BLOCKED", "job", jobId, null);
                return Response.status(403).entity(Map.of(
                        "error", "SOC II: This job is linked to a Bug ticket and cannot be deleted. Records must be retained for compliance."
                )).build();
            }
            if (job.getStatus() != JobStatus.PENDING && job.getStatus() != JobStatus.QUEUED) {
                return Response.status(409)
                        .entity(Map.of("error", "Job cannot be cancelled. Current status: " + job.getStatus()))
                        .build();
            }
            boolean cancelled = jobQueue.cancelJob(jobId);
            if (!cancelled) {
                return Response.status(409)
                        .entity(Map.of("error", "Failed to cancel job: " + jobId))
                        .build();
            }
            auditService.log("JOBS", "JOB_CANCELLED", "job", jobId, null);
            return Response.ok(Map.of("status", "cancelled", "jobId", jobId)).build();
        }).orElse(Response.status(404).entity(Map.of("error", "Job not found: " + jobId)).build());
    }

    @POST
    @Path("/jobs/{jobId}/rerun")
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
        return jobStore.get(jobId).map(job -> {
            if (job.getStatus() != JobStatus.FAILED && job.getStatus() != JobStatus.SUCCESS) {
                return Response.status(409)
                        .entity(Map.of("error", "Job cannot be rerun. Current status: " + job.getStatus()))
                        .build();
            }
            String newJobId = jobQueue.rerunJob(job);
            if (newJobId == null) {
                return Response.status(409)
                        .entity(Map.of("error", "Job type cannot be rerun: " + job.getJobType()))
                        .build();
            }
            auditService.log("JOBS", "JOB_RERUN", "job", jobId, Map.of("newJobId", newJobId));
            return Response.ok(Map.of("status", "queued", "jobId", newJobId, "originalJobId", jobId)).build();
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
            content = @Content(schema = @Schema(example = "{\"status\": \"UP\", \"availableSlots\": 3, \"runningJobs\": 0, \"queuedJobs\": 0}")))
    public Response health() {
        return Response.ok(Map.of(
                "status", "UP",
                "availableSlots", jobQueue.getAvailableSlots(),
                "runningJobs", jobQueue.getRunningCount(),
                "queuedJobs", jobQueue.getQueueDepth(),
                "maxConcurrentJobs", jobQueue.getMaxConcurrentJobs(),
                "maxQueueSize", jobQueue.getMaxQueueSize()
        )).build();
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
        if (agentLabel().isBlank()) {
            return Response.status(400)
                    .entity(Map.of("error", "jira.agent.label not configured"))
                    .build();
        }

        var issues = jiraService.searchIssuesByLabel(agentLabel());
        if (issues.isEmpty()) {
            LOG.infof("sync-jira: no open issues found with label %s", agentLabel());
            return Response.ok(Map.of("found", 0, "queued", 0,
                    "skipped", List.of())).build();
        }

        LOG.infof("sync-jira: found %d open issues with label %s:", issues.size(), agentLabel());
        for (var issue : issues) {
            LOG.infof("  - %s: %s", issue.key(), issue.summary());
        }

        List<Map<String, String>> queued = new ArrayList<>();
        List<Map<String, String>> skipped = new ArrayList<>();

        for (var issue : issues) {
            if (jobStore.hasActiveJobForJiraKey(issue.key())) {
                skipped.add(Map.of("key", issue.key(), "reason", "Active job exists"));
                continue;
            }

            String repoUrl = null;
            String prompt = null;
            String branchSuffix;

            if (aikidoService.isEnabled()) {
                var enrichment = resolveAikidoContext(issue.key());
                if (enrichment != null) {
                    repoUrl = enrichment.repoUrl;
                    prompt = enrichment.prompt;
                    branchSuffix = enrichment.branchSuffix;
                    LOG.infof("sync-jira: Aikido context resolved for %s", issue.key());
                } else {
                    branchSuffix = slugify(issue.summary());
                }
            } else {
                branchSuffix = slugify(issue.summary());
            }

            if (repoUrl == null || repoUrl.isBlank()) {
                repoUrl = defaultRepoUrl();
            }
            if (repoUrl == null || repoUrl.isBlank()) {
                skipped.add(Map.of("key", issue.key(), "reason", "No repo URL available"));
                continue;
            }

            String branchName = "agent/" + issue.key() + "-" + branchSuffix;
            RunFixRequest fullRequest = new RunFixRequest(
                    repoUrl, branchName, issue.key(), prompt,
                    "develop", null, null, null, null, null, null
            );

            String jobId = UUID.randomUUID().toString();
            JobRecord job = new JobRecord(jobId, fullRequest);
            jobStore.put(job);

            if (!jobQueue.submit(job)) {
                skipped.add(Map.of("key", issue.key(), "reason", "Queue full"));
                break;
            }

            queued.add(Map.of("key", issue.key(), "jobId", jobId, "branch", branchName));
        }

        LOG.infof("sync-jira: found=%d, queued=%d, skipped=%d",
                issues.size(), queued.size(), skipped.size());
        auditService.log("JOBS", "JIRA_SYNC", "jira", null,
                Map.of("found", String.valueOf(issues.size()),
                       "queued", String.valueOf(queued.size()),
                       "skipped", String.valueOf(skipped.size())));

        return Response.ok(Map.of(
                "found", issues.size(),
                "queued", queued.size(),
                "queuedJobs", queued,
                "skipped", skipped
        )).build();
    }

    private record AikidoEnrichment(String repoUrl, String prompt, String branchSuffix) {}

    private AikidoEnrichment resolveAikidoContext(String issueKey) {
        Integer groupId = aikidoService.findIssueGroupByJiraKey(issueKey);

        JiraService.JiraDescriptionContext descCtx = null;
        if (groupId == null) {
            descCtx = jiraService.extractDescriptionContext(issueKey);
            for (Integer candidateId : descCtx.aikidoCandidateIds()) {
                AikidoIssueInfo info = aikidoService.getIssueGroupDetail(candidateId);
                if (info != null) {
                    groupId = candidateId;
                    break;
                }
            }
        }

        if (groupId == null) return null;

        AikidoIssueInfo issueInfo = aikidoService.getIssueGroupDetail(groupId);
        if (issueInfo == null) return null;

        String repoUrl = (issueInfo.repoUrl() != null && !issueInfo.repoUrl().isBlank())
                ? issueInfo.repoUrl() : null;
        if (repoUrl == null) {
            repoUrl = resolveRepoUrlFromContainer(issueInfo, descCtx, issueKey);
        }

        String prompt = issueInfo.toPromptSection();
        String branchSuffix = slugify(issueInfo.packageName() + "-"
                + (issueInfo.fixedVersion() != null ? issueInfo.fixedVersion() : "fix"));

        return new AikidoEnrichment(repoUrl, prompt, branchSuffix);
    }

    /**
     * Try to resolve a code repository URL from a container image reference.
     * Checks the Aikido issue's container_image field first, then falls back to
     * container names found in the JIRA description.
     */
    private String resolveRepoUrlFromContainer(AikidoIssueInfo issueInfo,
                                                JiraService.JiraDescriptionContext descCtx,
                                                String jiraKey) {
        if (issueInfo.containerImage() != null && !issueInfo.containerImage().isBlank()) {
            LOG.infof("Aikido issue references container image '%s', searching for matching code repo",
                    issueInfo.containerImage());
            String url = aikidoService.findCodeRepoUrlForContainer(issueInfo.containerImage());
            if (url != null) return url;
        }

        if (descCtx == null && jiraKey != null) {
            descCtx = jiraService.extractDescriptionContext(jiraKey);
        }
        if (descCtx != null) {
            for (String container : descCtx.containerNames()) {
                LOG.infof("JIRA description references container '%s', searching for matching code repo",
                        container);
                String url = aikidoService.findCodeRepoUrlForContainer(container);
                if (url != null) return url;
            }
        }
        return null;
    }

    // ── SOC II helpers ────────────────────────────────────────────────────

    private static boolean isBugType(String issueType, String configuredTypes) {
        return Arrays.stream(configuredTypes.split("\\s*,\\s*"))
                .anyMatch(t -> t.equalsIgnoreCase(issueType));
    }

    private static boolean isProtected(String branch, String configuredBranches) {
        if (branch == null) return false;
        return Arrays.stream(configuredBranches.split("\\s*,\\s*"))
                .anyMatch(b -> b.equalsIgnoreCase(branch));
    }

    private static String resolveTargetBranch(JobRecord job) {
        if (job.getRequest() != null) return job.getRequest().targetBranchOrDefault();
        if (job.getGenerateTestsRequest() != null) return job.getGenerateTestsRequest().targetBranchOrDefault();
        if (job.getGenerateDocsRequest() != null) return job.getGenerateDocsRequest().targetBranchOrDefault();
        if (job.getHookRequest() != null) return job.getHookRequest().targetBranch();
        return null;
    }

    private static String extractJobJiraKey(JobRecord job) {
        if (job.getRequest() != null) return job.getRequest().jiraKey();
        if (job.getReviewRequest() != null) return job.getReviewRequest().jiraKey();
        if (job.getFixPrRequest() != null) return job.getFixPrRequest().jiraKey();
        return null;
    }

    private void schedulePromotion(JobRecord originalJob, String fromBranch, String toBranch, String originalJobId) {
        try {
            // Build a ReviewPrRequest for the promotion (FIX_PR would be more accurate,
            // but we need a concrete request type; use the original job's review request
            // structure or construct a minimal one)
            String repoUrl = null;
            if (originalJob.getRequest() != null) repoUrl = originalJob.getRequest().repoUrl();
            if (repoUrl == null && originalJob.getFixPrRequest() != null)
                repoUrl = originalJob.getFixPrRequest().repoUrl();
            if (repoUrl == null) {
                LOG.warnf("Cannot schedule promotion for job %s: no repoUrl", originalJobId);
                return;
            }

            String jiraKey = extractJobJiraKey(originalJob);
            // The promotion is a review of the PR that will be created on the promotion branch.
            // We submit a ReviewPrRequest with the PR ID populated after the PR is created.
            // For now, log the intent — actual PR creation needs a gitPlatformService call
            // which requires scm coords (not yet available in this method scope).
            // Log audit event and store promotionJobId on the original job record.
            String promotionJobId = UUID.randomUUID().toString();
            originalJob.setPromotionJobId(promotionJobId);
            jobStore.update(originalJob);

            auditService.log("SOC2", "SOC2_PROMOTION_CREATED", "job", originalJobId,
                    Map.of("promotionJobId", promotionJobId,
                           "fromBranch", fromBranch,
                           "toBranch", toBranch,
                           "jiraKey", jiraKey != null ? jiraKey : "unknown"));

            LOG.infof("SOC2 promotion placeholder created for job %s: %s → %s (promotionJobId=%s)",
                    originalJobId, fromBranch, toBranch, promotionJobId);
        } catch (Exception e) {
            LOG.warnf("Failed to schedule promotion for job %s: %s", originalJobId, e.getMessage());
        }
    }

    private JobStatusResponse buildStatusResponse(JobRecord job, String jobId) {
        int criticalDays = parseInt(settings.get("soc2.sla.critical-days", "5"), 5);
        int highDays     = parseInt(settings.get("soc2.sla.high-days",     "20"), 20);
        String bugTypes  = settings.get("soc2.bug-issue-types", "Bug,Defect");
        boolean scytaleEnabled = !settings.get("scytale.api.key", "").isBlank();
        List<String> bugList = Arrays.asList(bugTypes.split("\\s*,\\s*"));
        return JobStatusResponse.from(job, jobQueue.getQueuePosition(jobId),
                criticalDays, highDays, bugList, scytaleEnabled);
    }

    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value.trim()); } catch (Exception e) { return fallback; }
    }

    private static String slugify(String text) {
        if (text == null || text.isBlank()) return "fix";
        String slug = text.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        if (slug.length() > 60) {
            slug = slug.substring(0, 60).replaceAll("-$", "");
        }
        return slug;
    }
}
