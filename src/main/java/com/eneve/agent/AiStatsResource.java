package com.eneve.agent;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import io.quarkus.security.Authenticated;
import jakarta.enterprise.context.RequestScoped;

import com.eneve.agent.agent.model.AiCallRecord;
import com.eneve.agent.agent.store.AiCallStore;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@RequestScoped
@Path("/stats/ai-calls")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "AI Statistics", description = "Endpoints for AI call telemetry and cost analytics")
public class AiStatsResource {

    @Inject
    AiCallStore aiCallStore;

    @GET
    @Operation(
            operationId = "listAiCalls",
            summary = "List AI calls",
            description = "Paginated list of AI calls with optional filters by job type and time range."
    )
    @APIResponse(responseCode = "200", description = "Paginated list of AI call records")
    public Response listCalls(
            @Parameter(description = "Max results to return") @QueryParam("limit") @DefaultValue("50") int limit,
            @Parameter(description = "Offset for pagination") @QueryParam("offset") @DefaultValue("0") int offset,
            @Parameter(description = "Filter by job type (FIX, REVIEW, etc.)") @QueryParam("jobType") String jobType,
            @Parameter(description = "Start of time range (ISO-8601)") @QueryParam("from") String from,
            @Parameter(description = "End of time range (ISO-8601)") @QueryParam("to") String to) {

        Instant fromInstant = parseInstant(from);
        Instant toInstant = parseInstant(to);

        int safeLimit = Math.min(Math.max(limit, 1), 500);
        int safeOffset = Math.max(offset, 0);

        List<AiCallRecord> calls = aiCallStore.getRecentCalls(safeLimit, safeOffset, jobType,
                fromInstant, toInstant);
        return Response.ok(calls).build();
    }

    @GET
    @Path("/summary")
    @Operation(
            operationId = "getAiCallSummary",
            summary = "Aggregated AI call statistics",
            description = "Returns total tokens, estimated cost, average call duration, and call counts grouped by model and job type."
    )
    @APIResponse(responseCode = "200", description = "Aggregated statistics")
    public Response getSummary(
            @Parameter(description = "Start of time range (ISO-8601)") @QueryParam("from") String from,
            @Parameter(description = "End of time range (ISO-8601)") @QueryParam("to") String to) {

        Instant fromInstant = parseInstant(from);
        Instant toInstant = parseInstant(to);

        List<Map<String, Object>> summary = aiCallStore.getSummary(fromInstant, toInstant);
        return Response.ok(summary).build();
    }

    @GET
    @Path("/by-job/{jobId}")
    @Operation(
            operationId = "getAiCallsByJob",
            summary = "AI calls for a specific job",
            description = "Returns all AI call records for a given job ID, ordered by iteration."
    )
    @APIResponse(responseCode = "200", description = "List of AI call records for the job")
    public Response getByJob(
            @Parameter(description = "Job UUID", required = true) @PathParam("jobId") String jobId) {

        List<AiCallRecord> calls = aiCallStore.findByJobId(jobId);

        long totalInput = calls.stream().mapToLong(AiCallRecord::inputTokens).sum();
        long totalOutput = calls.stream().mapToLong(AiCallRecord::outputTokens).sum();
        long totalCacheWrite = calls.stream().mapToLong(AiCallRecord::cacheCreationInputTokens).sum();
        long totalCacheRead = calls.stream().mapToLong(AiCallRecord::cacheReadInputTokens).sum();
        long totalDurationMs = calls.stream().mapToLong(AiCallRecord::durationMs).sum();
        double estimatedCost = aiCallStore.estimateCost(totalInput, totalOutput, totalCacheWrite, totalCacheRead);

        return Response.ok(Map.of(
                "jobId", jobId,
                "calls", calls,
                "totalCalls", calls.size(),
                "totalInputTokens", totalInput,
                "totalOutputTokens", totalOutput,
                "totalCacheWriteTokens", totalCacheWrite,
                "totalCacheReadTokens", totalCacheRead,
                "totalDurationMs", totalDurationMs,
                "estimatedCostUsd", estimatedCost
        )).build();
    }

    @GET
    @Path("/summary-by-job-type")
    @Operation(
            operationId = "getAiCallSummaryByJobType",
            summary = "AI call statistics grouped by job type",
            description = "Returns job type breakdown, overall stats excluding CHAT, and CHAT-specific stats for dashboard display."
    )
    @APIResponse(responseCode = "200", description = "Job type breakdown and enhanced statistics")
    public Response getSummaryByJobType(
            @Parameter(description = "Start of time range (ISO-8601)") @QueryParam("from") String from,
            @Parameter(description = "End of time range (ISO-8601)") @QueryParam("to") String to) {

        Instant fromInstant = parseInstant(from);
        Instant toInstant = parseInstant(to);

        Map<String, Object> summaryByJobType = aiCallStore.getSummaryByJobType(fromInstant, toInstant);
        return Response.ok(summaryByJobType).build();
    }

    @GET
    @Path("/daily")
    @Operation(
            operationId = "getDailyAiCallSummary",
            summary = "Daily AI call aggregation",
            description = "Returns daily aggregated token counts, call counts, and estimated cost for time-series charts."
    )
    @APIResponse(responseCode = "200", description = "Daily aggregated statistics")
    public Response getDailySummary(
            @Parameter(description = "Start of time range (ISO-8601)") @QueryParam("from") String from,
            @Parameter(description = "End of time range (ISO-8601)") @QueryParam("to") String to) {

        Instant fromInstant = parseInstant(from);
        Instant toInstant = parseInstant(to);

        List<Map<String, Object>> daily = aiCallStore.getDailySummary(fromInstant, toInstant);
        return Response.ok(daily).build();
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
