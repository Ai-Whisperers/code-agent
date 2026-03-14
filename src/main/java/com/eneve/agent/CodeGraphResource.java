package com.eneve.agent;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.eneve.agent.agent.CodeGraphBuildService;
import com.eneve.agent.agent.CodeGraphStore;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/graph")
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
