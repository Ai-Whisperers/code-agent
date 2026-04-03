package com.eneve.agent.loganalysis;

import com.eneve.agent.agent.store.CloudAccountStore;
import com.eneve.agent.agent.store.CustomerRegistryStore;
import com.eneve.agent.model.CloudAccount;
import com.eneve.agent.model.CustomerConfig;
import com.eneve.agent.tools.AwsClientFactory;
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
        if (f.severity()   != null) node.put("severity",   f.severity());
        if (f.aiReason()   != null) node.put("aiReason",   f.aiReason());
        node.put("status",          f.status());
        return node;
    }
}
