package com.eneve.agent;

import java.util.LinkedHashMap;
import java.util.Map;

import com.eneve.agent.agent.CodeGraphBuildService;
import com.eneve.agent.agent.CodeGraphStore;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

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

    @Inject
    CodeGraphBuildService buildService;

    @Inject
    CodeGraphStore codeGraphStore;

    @POST
    @Path("/build-missing")
    @Operation(
            operationId = "buildMissingGraphs",
            summary = "Build code graphs for all repos that lack one",
            description = "Scans repo_settings for review-enabled repos without a code graph "
                    + "and builds one by cloning the default branch and indexing all source files."
    )
    @APIResponse(responseCode = "200", description = "Build results summary")
    public Response buildMissing() {
        CodeGraphBuildService.BuildResult result = buildService.buildMissingGraphs();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("built", result.built());
        body.put("skipped", result.skipped());
        body.put("alreadyPresent", result.alreadyPresent());
        return Response.ok(body).build();
    }

    @POST
    @Path("/rebuild/{workspace}/{repoSlug}")
    @Operation(
            operationId = "rebuildGraph",
            summary = "Rebuild the code graph for a single repository",
            description = "Deletes the existing graph (if any) and rebuilds it from scratch "
                    + "by cloning the default branch."
    )
    @APIResponse(responseCode = "200", description = "Build succeeded")
    @APIResponse(responseCode = "500", description = "Build failed")
    public Response rebuild(
            @Parameter(description = "Workspace or GitLab namespace", required = true)
            @PathParam("workspace") String workspace,
            @Parameter(description = "Repository slug", required = true)
            @PathParam("repoSlug") String repoSlug) {

        boolean success = buildService.buildGraph(workspace, repoSlug);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workspace", workspace);
        body.put("repoSlug", repoSlug);
        body.put("success", success);

        if (success) {
            return Response.ok(body).build();
        }
        body.put("error", "Graph build failed — check server logs for details");
        return Response.serverError().entity(body).build();
    }
}
