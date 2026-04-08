package com.eneve.agent.loganalysis;

import com.eneve.agent.RunFixService;
import com.eneve.agent.agent.store.CloudAccountStore;
import com.eneve.agent.agent.store.CustomerRegistryStore;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.jira.JiraService;
import com.eneve.agent.model.CloudAccount;
import com.eneve.agent.model.CustomerConfig;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.QuickFixRequest;
import com.eneve.agent.tools.AwsClientFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogGroupsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogGroupsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.LogGroup;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST endpoints for the log analysis findings screen.
 */
@Path("/log-analysis")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"app_admin", "app_developer"})
@Tag(name = "Log Analysis", description = "Production log analysis findings")
public class LogAnalysisFindingsResource {

    private static final Logger LOG = Logger.getLogger(LogAnalysisFindingsResource.class);
    private static final int MAX_LIMIT = 200;

    @Inject LogAnalysisFindingsStore findingsStore;
    @Inject LogAnalysisService logAnalysisService;
    @Inject JiraService jiraService;
    @Inject RunFixService runFixService;
    @Inject JobStore jobStore;
    @Inject ObjectMapper mapper;
    @Inject CustomerRegistryStore customerRegistryStore;
    @Inject CloudAccountStore cloudAccountStore;
    @Inject AwsClientFactory awsClientFactory;

    /**
     * Lists GENUINE, OPEN findings for the UI.
     *
     * @param customerId optional filter by customer
     * @param severity   optional filter: high | medium | low
     * @param limit      page size (default 50, max 200)
     * @param offset     pagination offset (default 0)
     */
    @GET
    @Path("/findings")
    @Operation(operationId = "listLogFindings", summary = "List genuine log analysis findings")
    public Response listFindings(
            @QueryParam("customerId") String customerId,
            @QueryParam("severity")   String severity,
            @QueryParam("limit")      @DefaultValue("50")  int limit,
            @QueryParam("offset")     @DefaultValue("0")   int offset) {

        limit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        offset = Math.max(offset, 0);

        List<LogAnalysisFinding> findings = findingsStore.listGenuineFindings(
                customerId, severity, limit, offset);

        ArrayNode items = mapper.createArrayNode();
        for (LogAnalysisFinding f : findings) {
            items.add(toJson(f));
        }

        ObjectNode response = mapper.createObjectNode();
        response.set("items", items);
        response.put("limit", limit);
        response.put("offset", offset);
        response.put("count", findings.size());

        return Response.ok(response).build();
    }

    /**
     * Lists FALSE_POSITIVE findings for the UI.
     *
     * @param customerId optional filter by customer
     * @param severity   optional filter: high | medium | low
     * @param limit      page size (default 50, max 200)
     * @param offset     pagination offset (default 0)
     */
    @GET
    @Path("/false-positives")
    @Operation(operationId = "listFalsePositiveFindings", summary = "List false-positive log analysis findings")
    public Response listFalsePositives(
            @QueryParam("customerId") String customerId,
            @QueryParam("severity")   String severity,
            @QueryParam("limit")      @DefaultValue("50")  int limit,
            @QueryParam("offset")     @DefaultValue("0")   int offset) {

        limit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        offset = Math.max(offset, 0);

        List<LogAnalysisFinding> findings = findingsStore.listFalsePositiveFindings(
                customerId, severity, limit, offset);

        ArrayNode items = mapper.createArrayNode();
        for (LogAnalysisFinding f : findings) {
            items.add(toJson(f));
        }

        ObjectNode response = mapper.createObjectNode();
        response.set("items", items);
        response.put("limit", limit);
        response.put("offset", offset);
        response.put("count", findings.size());

        return Response.ok(response).build();
    }

    /**
     * Returns aggregate stats for the findings screen stat cards.
     */
    @GET
    @Path("/stats")
    @Operation(operationId = "getLogFindingStats", summary = "Get log analysis stats")
    public Response getStats() {
        LogAnalysisFindingsStore.FindingStats stats = findingsStore.getStats();
        ObjectNode response = mapper.createObjectNode();
        response.put("openTotal",         stats.openTotal());
        response.put("openHigh",          stats.openHigh());
        response.put("newToday",          stats.newToday());
        response.put("dismissedThisWeek", stats.dismissedThisWeek());
        response.put("monitoringTotal",   stats.monitoringTotal());
        return Response.ok(response).build();
    }

    /**
     * Dismisses a finding (sets status = DISMISSED).
     */
    @POST
    @Path("/findings/{id}/dismiss")
    @Operation(operationId = "dismissLogFinding", summary = "Dismiss a log analysis finding")
    public Response dismiss(@PathParam("id") long id) {
        boolean updated = findingsStore.dismiss(id);
        if (!updated) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"Finding not found or already dismissed\"}")
                    .build();
        }
        LOG.infof("LogAnalysisFindingsResource: finding %d dismissed", id);
        return Response.ok("{\"status\":\"dismissed\"}").build();
    }

    /**
     * Triggers a deep Claude Sonnet analysis for a finding and persists the result.
     * The finding is marked as analysed (analysed_at is set).
     */
    @POST
    @Path("/findings/{id}/analyse")
    @Operation(operationId = "analyseLogFinding", summary = "Run deep AI analysis on a log finding")
    public Response analyse(@PathParam("id") long id) {
        try {
            String analysis = logAnalysisService.performDeepAnalysis(id);
            if (analysis == null) {
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity("{\"error\":\"Analysis call failed — check server logs\"}")
                        .build();
            }
            ObjectNode response = mapper.createObjectNode();
            response.put("deepAnalysis", analysis);
            return Response.ok(response).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"" + e.getMessage() + "\"}")
                    .build();
        } catch (Exception e) {
            LOG.warnf("LogAnalysisFindingsResource: deep analysis failed for finding %d: %s", id, e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Unexpected error: " + e.getMessage() + "\"}")
                    .build();
        }
    }

    /**
     * Creates a Jira ticket for a finding and immediately starts a quick-fix agent job.
     *
     * <p>Request body (JSON):
     * <pre>
     * {
     *   "projectKey": "ENG",      // required — Jira project to create the ticket in
     *   "repoUrl":    "https://…" // required — repository the fix job will target
     *   "issueType":  "Bug"       // optional — defaults to "Bug"
     *   "priority":   "High"      // optional — Jira priority name
     * }
     * </pre>
     *
     * <p>Response (200):
     * <pre>{ "jiraKey": "ENG-42", "jobId": "uuid" }</pre>
     */
    @POST
    @Path("/findings/{id}/create-jira-and-fix")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(operationId = "createJiraAndFix",
               summary = "Create a Jira ticket for a finding and start a fix job")
    public Response createJiraAndFix(@PathParam("id") long id, String body) {
        LogAnalysisFinding finding = findingsStore.findById(id)
                .orElse(null);
        if (finding == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"Finding not found\"}")
                    .build();
        }

        // Parse request body
        String projectKey;
        String repoUrl;
        String issueType;
        String priority;
        try {
            JsonNode req = mapper.readTree(body);
            projectKey = req.path("projectKey").asText(null);
            repoUrl    = req.path("repoUrl").asText(null);
            issueType  = req.path("issueType").asText("Bug");
            priority   = req.path("priority").asText(null);
        } catch (Exception e) {
            return Response.status(400).entity("{\"error\":\"Invalid request body\"}").build();
        }

        if (projectKey == null || projectKey.isBlank()) {
            return Response.status(400).entity("{\"error\":\"projectKey is required\"}").build();
        }
        if (repoUrl == null || repoUrl.isBlank()) {
            return Response.status(400).entity("{\"error\":\"repoUrl is required\"}").build();
        }
        if (!jiraService.isConfigured()) {
            return Response.status(503).entity("{\"error\":\"Jira is not configured\"}").build();
        }

        // Build Jira ticket content
        String exceptionClass = finding.exceptionClass() != null ? finding.exceptionClass() : "(unknown)";
        String summary = String.format("[Log Analysis] %s in %s/%s",
                exceptionClass, finding.customerId(), finding.environmentName());

        StringBuilder desc = new StringBuilder();
        desc.append("## Production Exception Detected\n\n");
        desc.append(String.format("**Exception:** `%s`\n", exceptionClass));
        desc.append(String.format("**Customer:** %s | **Environment:** %s\n",
                finding.customerId(), finding.environmentName()));
        desc.append(String.format("**Occurrences:** %d | **Severity:** %s\n",
                finding.occurrenceCount(), finding.severity() != null ? finding.severity() : "unknown"));
        if (finding.firstSeenAt() != null) {
            desc.append(String.format("**First seen:** %s\n", finding.firstSeenAt()));
        }
        if (finding.aiReason() != null) {
            desc.append(String.format("\n**AI triage:** %s\n", finding.aiReason()));
        }
        if (finding.topFrames() != null) {
            desc.append("\n**Stack frames:**\n```\n").append(finding.topFrames()).append("\n```\n");
        }
        if (finding.sampleMessage() != null) {
            String msg = finding.sampleMessage().length() > 1000
                    ? finding.sampleMessage().substring(0, 1000) + "…"
                    : finding.sampleMessage();
            desc.append("\n**Sample message:**\n```\n").append(msg).append("\n```\n");
        }
        if (finding.deepAnalysis() != null) {
            desc.append("\n---\n\n## Deep Analysis\n\n").append(finding.deepAnalysis());
        }

        // Create Jira ticket
        String jiraKey;
        try {
            jiraKey = jiraService.createIssueSystem(
                    projectKey, summary, desc.toString(), issueType, null,
                    List.of("log-analysis", "agent"), null,
                    priority != null && !priority.isBlank() ? priority : null);
        } catch (Exception e) {
            LOG.warnf("LogAnalysisFindingsResource: failed to create Jira ticket for finding %d: %s",
                    id, e.getMessage());
            return Response.status(502)
                    .entity("{\"error\":\"Failed to create Jira ticket: " + e.getMessage() + "\"}")
                    .build();
        }
        if (jiraKey == null) {
            return Response.status(502).entity("{\"error\":\"Jira ticket creation returned no key\"}").build();
        }

        // Persist the Jira key on the finding
        findingsStore.saveJiraKey(id, jiraKey);
        LOG.infof("LogAnalysisFindingsResource: created Jira ticket %s for finding %d", jiraKey, id);

        // Start quick-fix agent job
        String jobId;
        try {
            RunFixService.QuickFixResult result = runFixService.quickFix(new QuickFixRequest(repoUrl, jiraKey));
            jobId = result.jobId();
            LOG.infof("LogAnalysisFindingsResource: started fix job %s for Jira ticket %s", jobId, jiraKey);
            findingsStore.saveJobAndPr(id, jobId, null);
        } catch (Exception e) {
            LOG.warnf("LogAnalysisFindingsResource: Jira ticket %s created but fix job failed: %s",
                    jiraKey, e.getMessage());
            // Ticket was created — return partial success so the UI can still link to it
            ObjectNode resp = mapper.createObjectNode();
            resp.put("jiraKey", jiraKey);
            resp.put("jobId", (String) null);
            resp.put("warning", "Jira ticket created but fix job could not be started: " + e.getMessage());
            return Response.ok(resp).build();
        }

        ObjectNode resp = mapper.createObjectNode();
        resp.put("jiraKey", jiraKey);
        resp.put("jobId", jobId);
        return Response.ok(resp).build();
    }

    /**
     * Returns up to 5 open, high-priority findings for the dashboard "Needs Attention" section.
     * Also returns the total count of open genuine findings.
     */
    @GET
    @Path("/attention")
    @Operation(operationId = "getLogFindingAttention", summary = "Get top open findings for the dashboard")
    public Response getAttention() {
        LogAnalysisFindingsStore.FindingStats stats = findingsStore.getStats();
        List<LogAnalysisFinding> top = findingsStore.listAttentionFindings(5);

        ArrayNode items = mapper.createArrayNode();
        for (LogAnalysisFinding f : top) {
            items.add(toJson(f));
        }

        ObjectNode response = mapper.createObjectNode();
        response.put("openTotal",      stats.openTotal());
        response.put("monitoringTotal", stats.monitoringTotal());
        response.set("items", items);
        return Response.ok(response).build();
    }

    /**
     * Manually transitions a finding to MONITORING status (e.g. after confirming a fix was deployed).
     */
    @POST
    @Path("/findings/{id}/set-monitoring")
    @Operation(operationId = "setFindingMonitoring", summary = "Move a finding to MONITORING status")
    public Response setMonitoring(@PathParam("id") long id) {
        boolean updated = logAnalysisService.setMonitoring(id);
        if (!updated) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"Finding not found or not in a transitionable state\"}")
                    .build();
        }
        return Response.ok("{\"status\":\"monitoring\"}").build();
    }

    /**
     * Lists CloudWatch log groups using the AWS credentials for a given customer.
     * Accepts the IAM role ARN and region directly so the endpoint works even for
     * environments that have not been saved yet (e.g. while editing the form).
     *
     * @param customerId the customer ID (used to resolve the base cloud account credentials)
     * @param iamRole    IAM role ARN to assume (from the environment's AWS config)
     * @param region     AWS region (from the environment's AWS config)
     * @param prefix     optional prefix filter passed to CloudWatch DescribeLogGroups
     */
    @GET
    @Path("/log-groups")
    @Operation(operationId = "listLogGroups", summary = "List CloudWatch log groups for a customer environment")
    public Response listLogGroups(
            @QueryParam("customerId") String customerId,
            @QueryParam("iamRole")    String iamRole,
            @QueryParam("region")     String region,
            @QueryParam("prefix")     String prefix) {

        if (customerId == null || customerId.isBlank()) {
            return Response.status(400).entity(Map.of("error", "customerId is required")).build();
        }

        Optional<CustomerConfig> customerOpt = customerRegistryStore.getCustomer(customerId);
        if (customerOpt.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "Customer not found: " + customerId)).build();
        }

        CustomerConfig customer = customerOpt.get();
        CloudAccount cloudAccount = null;
        if (customer.cloudAccountId() != null && !customer.cloudAccountId().isBlank()) {
            cloudAccount = cloudAccountStore.getCloudAccountUnmasked(customer.cloudAccountId()).orElse(null);
        }

        String effectiveRegion = (region != null && !region.isBlank()) ? region : "eu-west-1";

        try (CloudWatchLogsClient cwClient = awsClientFactory.cloudWatchLogsClient(
                iamRole != null ? iamRole : "", effectiveRegion, cloudAccount)) {

            DescribeLogGroupsRequest.Builder req = DescribeLogGroupsRequest.builder().limit(50);
            if (prefix != null && !prefix.isBlank()) {
                req.logGroupNamePrefix(prefix);
            }
            DescribeLogGroupsResponse resp = cwClient.describeLogGroups(req.build());

            ArrayNode items = mapper.createArrayNode();
            for (LogGroup g : resp.logGroups()) {
                ObjectNode item = mapper.createObjectNode();
                item.put("name", g.logGroupName());
                if (g.retentionInDays() != null) item.put("retentionDays", g.retentionInDays());
                if (g.storedBytes()     != null) item.put("storedBytes",    g.storedBytes());
                items.add(item);
            }

            ObjectNode response = mapper.createObjectNode();
            response.set("items", items);
            response.put("hasMore", resp.nextToken() != null);
            return Response.ok(response).build();

        } catch (Exception e) {
            LOG.warnf("LogAnalysisFindingsResource: failed to list log groups for %s: %s",
                    customerId, e.getMessage());
            return Response.status(502)
                    .entity(Map.of("error", "Failed to fetch log groups from AWS: " + e.getMessage()))
                    .build();
        }
    }

    // ── Serialisation helper ──────────────────────────────────────────────────

    private ObjectNode toJson(LogAnalysisFinding f) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id",              f.id());
        node.put("fingerprint",     f.fingerprint());
        node.put("customerId",      f.customerId());
        node.put("environmentName", f.environmentName());
        node.put("logGroupName",    f.logGroupName());
        if (f.exceptionClass() != null) node.put("exceptionClass", f.exceptionClass());
        if (f.topFrames()      != null) node.put("topFrames",      f.topFrames());
        if (f.sampleMessage()  != null) node.put("sampleMessage",  f.sampleMessage());
        node.put("firstSeenAt",     f.firstSeenAt()  != null ? f.firstSeenAt().toString()  : null);
        node.put("lastSeenAt",      f.lastSeenAt()   != null ? f.lastSeenAt().toString()   : null);
        node.put("occurrenceCount", f.occurrenceCount());
        if (f.severity()      != null) node.put("severity",      f.severity());
        if (f.aiReason()      != null) node.put("aiReason",      f.aiReason());
        node.put("status",             f.status());
        if (f.deepAnalysis()    != null) node.put("deepAnalysis",    f.deepAnalysis());
        if (f.analysedAt()      != null) node.put("analysedAt",      f.analysedAt().toString());
        if (f.jiraKey()         != null) node.put("jiraKey",         f.jiraKey());
        if (f.monitoringSince() != null) node.put("monitoringSince", f.monitoringSince().toString());

        // Job tracking — look up live status and PR URL from the job store
        if (f.jobId() != null) {
            node.put("jobId", f.jobId());
            jobStore.get(f.jobId()).ifPresentOrElse(job -> {
                node.put("jobStatus", job.getStatus() != null ? job.getStatus().name() : null);
                String prUrl = job.getPrUrl() != null ? job.getPrUrl() : f.prUrl();
                if (prUrl != null) {
                    node.put("prUrl", prUrl);
                    // Keep pr_url in sync with the job's latest value
                    if (job.getPrUrl() != null && !job.getPrUrl().equals(f.prUrl())) {
                        findingsStore.savePrUrl(f.id(), job.getPrUrl());
                    }
                }
            }, () -> {
                // Job not in active store — fall back to persisted values
                if (f.prUrl() != null) node.put("prUrl", f.prUrl());
            });
        } else if (f.prUrl() != null) {
            node.put("prUrl", f.prUrl());
        }

        return node;
    }
}
