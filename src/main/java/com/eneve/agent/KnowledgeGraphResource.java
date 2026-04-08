package com.eneve.agent;

import com.eneve.agent.agent.JobQueue;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.exception.JobQueueFullException;
import com.eneve.agent.knowledge.KnowledgeGraphStore;
import com.eneve.agent.knowledge.KnowledgeGraphStore.*;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.KnowledgeGraphRequest;
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
@Path("/knowledge-graph")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Knowledge Graph", description = "Team knowledge graph — engineer expertise scores and bus-factor risks")
public class KnowledgeGraphResource {

    private static final Logger LOG = Logger.getLogger(KnowledgeGraphResource.class);

    @Inject KnowledgeGraphStore store;
    @Inject JobQueue jobQueue;
    @Inject JobStore jobStore;

    // ── Trigger ───────────────────────────────────────────────────────────────

    @POST
    @Path("/generate")
    @RolesAllowed("app_admin")
    @Operation(
            operationId = "generateKnowledgeGraph",
            summary = "Queue a knowledge graph computation job",
            description = "Analyses git history across all (or a specific) product's repos, "
                    + "computes expertise scores, and persists results. "
                    + "Returns immediately with a jobId for polling via GET /status/{jobId}."
    )
    public Response generate(
            @QueryParam("productId")   String productId,
            @QueryParam("lookbackDays") @DefaultValue("365") int lookbackDays) {

        KnowledgeGraphRequest request = new KnowledgeGraphRequest(productId, lookbackDays);
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

        LOG.infof("KnowledgeGraph job %s accepted (productId=%s, lookbackDays=%d)",
                jobId, productId, lookbackDays);
        return Response.accepted(Map.of("jobId", jobId)).build();
    }

    // ── Snapshots ─────────────────────────────────────────────────────────────

    @GET
    @Path("/snapshots")
    @Operation(
            operationId = "listKnowledgeSnapshots",
            summary = "List available knowledge graph snapshots",
            description = "Returns all snapshots newest-first. Each snapshot represents one computation run."
    )
    public Response listSnapshots() {
        List<KnowledgeSnapshot> snapshots = store.listSnapshots();
        return Response.ok(snapshots).build();
    }

    // ── Scores ────────────────────────────────────────────────────────────────

    @GET
    @Path("/scores")
    @Operation(
            operationId = "getKnowledgeScores",
            summary = "Get file-level expertise scores for a snapshot",
            description = "Returns per-author per-file scores. "
                    + "Optionally filter by repo slug and/or author email."
    )
    public Response getScores(
            @QueryParam("snapshotId") Long snapshotId,
            @QueryParam("repo")       String repoSlug,
            @QueryParam("author")     String authorEmail) {

        long sid = resolveSnapshotId(snapshotId);
        if (sid < 0) return Response.status(404).entity(Map.of("error", "No snapshot found")).build();

        List<KnowledgeScore> scores = store.findScores(sid, repoSlug, authorEmail);
        return Response.ok(scores).build();
    }

    @GET
    @Path("/service-scores")
    @Operation(
            operationId = "getServiceScores",
            summary = "Get aggregated per-repo per-author service scores for a snapshot"
    )
    public Response getServiceScores(@QueryParam("snapshotId") Long snapshotId) {
        long sid = resolveSnapshotId(snapshotId);
        if (sid < 0) return Response.status(404).entity(Map.of("error", "No snapshot found")).build();

        List<ServiceScore> scores = store.findServiceScores(sid);
        return Response.ok(scores).build();
    }

    // ── Bus factor ────────────────────────────────────────────────────────────

    @GET
    @Path("/bus-factor")
    @Operation(
            operationId = "getBusFactor",
            summary = "Get bus-factor risk rows for a snapshot",
            description = "Returns per-file bus-factor data. "
                    + "Use flaggedOnly=true to return only files with bus_factor_flag=true."
    )
    public Response getBusFactor(
            @QueryParam("snapshotId")  Long    snapshotId,
            @QueryParam("repo")        String  repoSlug,
            @QueryParam("flaggedOnly") @DefaultValue("false") boolean flaggedOnly) {

        long sid = resolveSnapshotId(snapshotId);
        if (sid < 0) return Response.status(404).entity(Map.of("error", "No snapshot found")).build();

        List<BusFactorRow> rows = store.findBusFactor(sid, repoSlug, flaggedOnly);
        return Response.ok(rows).build();
    }

    // ── Authors ───────────────────────────────────────────────────────────────

    @GET
    @Path("/authors")
    @Operation(
            operationId = "getKnowledgeAuthors",
            summary = "List distinct authors in a snapshot"
    )
    public Response getAuthors(@QueryParam("snapshotId") Long snapshotId) {
        long sid = resolveSnapshotId(snapshotId);
        if (sid < 0) return Response.status(404).entity(Map.of("error", "No snapshot found")).build();

        List<AuthorSummary> authors = store.findAuthors(sid);
        return Response.ok(authors).build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** If snapshotId is null, falls back to the latest snapshot. Returns -1 if none found. */
    private long resolveSnapshotId(Long snapshotId) {
        if (snapshotId != null) return snapshotId;
        return store.findLatestSnapshot().map(KnowledgeSnapshot::id).orElse(-1L);
    }
}
