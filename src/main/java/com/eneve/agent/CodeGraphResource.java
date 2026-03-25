package com.eneve.agent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.eneve.agent.agent.service.CodeGraphBuildService;
import com.eneve.agent.agent.store.CodeGraphStore;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/graph")
@RolesAllowed("app_admin")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Code Graph", description = "Build and manage per-repo code graphs for impact analysis")
public class CodeGraphResource {

    private static final Logger LOG = Logger.getLogger(CodeGraphResource.class);

    @Inject
    CodeGraphBuildService buildService;

    @Inject
    CodeGraphStore codeGraphStore;

    private final ExecutorService graphExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "graph-build");
        t.setDaemon(true);
        return t;
    });

    @GET
    @Path("/status")
    @Operation(
            operationId = "getGraphStatus",
            summary = "Get code graph status for all repositories",
            description = "Returns the workspace, repo slug, node count, and last updated timestamp "
                    + "for every repository that has a code graph built."
    )
    @APIResponse(responseCode = "200", description = "List of graph status entries")
    public Response getStatus() {
        List<CodeGraphStore.GraphStatus> statuses = codeGraphStore.getGraphStatusAll();
        return Response.ok(statuses).build();
    }

    @POST
    @Path("/build-missing")
    @Operation(
            operationId = "buildMissingGraphs",
            summary = "Build code graphs for all repos that lack one",
            description = "Submits a background job that scans repo_settings for review-enabled repos "
                    + "without a code graph and builds them. Returns immediately."
    )
    @APIResponse(responseCode = "202", description = "Build job accepted")
    public Response buildMissing() {
        graphExecutor.submit(() -> {
            try {
                CodeGraphBuildService.BuildResult result = buildService.buildMissingGraphs();
                LOG.infof("Build missing graphs finished: %d built, %d skipped, %d already present",
                        result.built(), result.skipped(), result.alreadyPresent());
            } catch (Exception e) {
                LOG.errorf("Build missing graphs failed: %s", e.getMessage());
            }
        });

        return Response.accepted(Map.of("action", "build_missing_started")).build();
    }

    @POST
    @Path("/detect-archetypes")
    @Operation(
            operationId = "detectArchetypesAll",
            summary = "Detect framework archetype for all repos that lack one",
            description = "Lightweight scan: clones each repo whose archetype is not yet set, runs "
                    + "ArchetypeDetector, and persists the result. Does NOT rebuild the code graph "
                    + "or regenerate embeddings, so it is much faster than /build-missing. "
                    + "Returns immediately; detection runs in the background."
    )
    @APIResponse(responseCode = "202", description = "Detection job accepted")
    public Response detectArchetypesAll() {
        graphExecutor.submit(() -> {
            try {
                CodeGraphBuildService.DetectResult result = buildService.detectArchetypesForAll();
                LOG.infof("Archetype detection finished: %d detected, %d skipped, %d unchanged",
                        result.detected(), result.skipped(), result.unchanged());
            } catch (Exception e) {
                LOG.errorf("Archetype detection failed: %s", e.getMessage());
            }
        });
        return Response.accepted(Map.of("action", "detect_archetypes_started")).build();
    }

    @POST
    @Path("/detect-archetypes/{workspace}/{repoSlug}")
    @Operation(
            operationId = "detectArchetypeSingle",
            summary = "Detect framework archetype for a single repository",
            description = "Clones the repo, runs ArchetypeDetector, and persists the result. "
                    + "Does NOT rebuild the code graph or embeddings. Returns immediately."
    )
    @APIResponse(responseCode = "202", description = "Detection job accepted")
    public Response detectArchetypeSingle(
            @Parameter(description = "Workspace or GitLab namespace", required = true)
            @PathParam("workspace") String workspace,
            @Parameter(description = "Repository slug", required = true)
            @PathParam("repoSlug") String repoSlug) {

        graphExecutor.submit(() -> {
            try {
                Boolean detected = buildService.detectArchetype(workspace, repoSlug);
                if (Boolean.TRUE.equals(detected)) {
                    LOG.infof("Archetype detected for %s/%s", workspace, repoSlug);
                } else if (Boolean.FALSE.equals(detected)) {
                    LOG.infof("No archetype recognised for %s/%s", workspace, repoSlug);
                } else {
                    LOG.warnf("Archetype detection skipped (clone failed?) for %s/%s", workspace, repoSlug);
                }
            } catch (Exception e) {
                LOG.errorf("Archetype detection error for %s/%s: %s", workspace, repoSlug, e.getMessage());
            }
        });

        return Response.accepted(Map.of(
                "action", "detect_archetype_started",
                "workspace", workspace,
                "repoSlug", repoSlug
        )).build();
    }

    @POST
    @Path("/rebuild/{workspace}/{repoSlug}")
    @Operation(
            operationId = "rebuildGraph",
            summary = "Rebuild the code graph (and embeddings if enabled) for a single repository",
            description = "Submits a background job that deletes the existing graph and rebuilds it "
                    + "from scratch by cloning the default branch. If vector indexing is enabled for "
                    + "the repo, embeddings are also regenerated. Returns immediately."
    )
    @APIResponse(responseCode = "202", description = "Rebuild job accepted")
    public Response rebuild(
            @Parameter(description = "Workspace or GitLab namespace", required = true)
            @PathParam("workspace") String workspace,
            @Parameter(description = "Repository slug", required = true)
            @PathParam("repoSlug") String repoSlug) {

        graphExecutor.submit(() -> {
            try {
                boolean success = buildService.buildGraph(workspace, repoSlug);
                if (success) {
                    LOG.infof("Graph rebuild succeeded for %s/%s", workspace, repoSlug);
                } else {
                    LOG.warnf("Graph rebuild failed for %s/%s", workspace, repoSlug);
                }
            } catch (Exception e) {
                LOG.errorf("Graph rebuild error for %s/%s: %s", workspace, repoSlug, e.getMessage());
            }
        });

        return Response.accepted(Map.of(
                "action", "rebuild_started",
                "workspace", workspace,
                "repoSlug", repoSlug
        )).build();
    }
}
