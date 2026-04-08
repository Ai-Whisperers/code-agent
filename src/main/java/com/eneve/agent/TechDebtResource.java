package com.eneve.agent;

import com.eneve.agent.agent.JobQueue;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.exception.JobQueueFullException;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.TechDebtRequest;
import com.eneve.agent.techdebt.TechDebtStore;
import com.eneve.agent.techdebt.TechDebtStore.TechDebtFileRow;
import com.eneve.agent.techdebt.TechDebtStore.TechDebtSnapshot;
import io.quarkus.security.Authenticated;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RequestScoped
@Authenticated
@Path("/tech-debt")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Technical Debt", description = "Technical Debt Heatmap — per-file composite debt scores derived from complexity, coverage, churn, and staleness signals")
public class TechDebtResource {

    private static final Logger LOG = Logger.getLogger(TechDebtResource.class);

    @Inject TechDebtStore store;
    @Inject JobQueue      jobQueue;
    @Inject JobStore      jobStore;

    // ── Trigger ───────────────────────────────────────────────────────────────

    @POST
    @Path("/generate")
    @RolesAllowed("app_admin")
    @Operation(
            operationId = "generateTechDebt",
            summary = "Queue a technical debt analysis job",
            description = "Combines knowledge-graph scores with quality-report data to produce "
                    + "per-file debt scores. Returns immediately with a jobId for polling via "
                    + "GET /jobs/status/{jobId}."
    )
    public Response generate(
            @QueryParam("productId")   String productId,
            @QueryParam("lookbackDays") @DefaultValue("365") int lookbackDays) {

        TechDebtRequest request = new TechDebtRequest(productId, lookbackDays);
        String jobId = UUID.randomUUID().toString();
        JobRecord job = new JobRecord(jobId, request);
        jobStore.put(job);

        try {
            if (!jobQueue.submit(job)) {
                throw new JobQueueFullException("Job queue is full");
            }
        } catch (JobQueueFullException e) {
            return Response.status(429).entity(Map.of("error", e.getMessage())).build();
        }

        LOG.infof("TechDebt job %s accepted (productId=%s, lookbackDays=%d)",
                jobId, productId, lookbackDays);
        return Response.accepted(Map.of("jobId", jobId)).build();
    }

    // ── Snapshots ─────────────────────────────────────────────────────────────

    @GET
    @Path("/snapshots")
    @Operation(
            operationId = "listTechDebtSnapshots",
            summary = "List available technical debt snapshots",
            description = "Returns all snapshots newest-first. Each snapshot represents one analysis run."
    )
    public Response listSnapshots() {
        List<TechDebtSnapshot> snapshots = store.listSnapshots();
        return Response.ok(snapshots).build();
    }

    // ── Heatmap data ──────────────────────────────────────────────────────────

    @GET
    @Path("/heatmap")
    @Operation(
            operationId = "getTechDebtHeatmap",
            summary = "Get per-file debt scores for a snapshot",
            description = "Returns file-level debt scores sorted by debt_score descending. "
                    + "Optionally filter by repo slug. When snapshotId is omitted the latest "
                    + "snapshot is used automatically."
    )
    public Response getHeatmap(
            @QueryParam("snapshotId") Long   snapshotId,
            @QueryParam("repo")       String repoSlug) {

        long sid = resolveSnapshotId(snapshotId);
        if (sid < 0) return Response.status(404).entity(Map.of("error", "No snapshot found")).build();

        List<TechDebtFileRow> rows = store.findFiles(sid, repoSlug);
        return Response.ok(rows).build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** If snapshotId is null, falls back to the latest snapshot. Returns -1 if none found. */
    private long resolveSnapshotId(Long snapshotId) {
        if (snapshotId != null) return snapshotId;
        return store.findLatestSnapshot().map(TechDebtSnapshot::id).orElse(-1L);
    }
}
