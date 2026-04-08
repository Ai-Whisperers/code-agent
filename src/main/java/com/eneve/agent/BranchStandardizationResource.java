package com.eneve.agent;

import java.util.List;
import java.util.Map;

import com.eneve.agent.agent.service.BranchStandardizationService;
import com.eneve.agent.agent.service.BranchStandardizationService.RepoResult;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * REST endpoints for standardising the branch topology of Bitbucket repositories.
 * <p>
 * Ensures every managed repository has:
 * <ul>
 *   <li>{@code main} as the default production branch (renamed from {@code master} if needed)</li>
 *   <li>{@code develop} as the integration branch (created from {@code main} if absent)</li>
 * </ul>
 *
 * <p>Operations are idempotent — running the same endpoint multiple times is always safe.
 */
@Path("/branches")
@RolesAllowed("app_admin")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Branch Management", description = "Standardise branch topology across Bitbucket repositories")
public class BranchStandardizationResource {

    @Inject
    BranchStandardizationService standardizationService;

    @POST
    @Path("/standardize")
    @Operation(
            operationId = "standardizeAllBranches",
            summary = "Standardize branches for all managed repositories",
            description = "Runs branch standardization for every non-archived repository in repo_settings. "
                    + "For each repo: renames master → main if master exists, sets main as the default branch, "
                    + "and creates develop from main if it does not already exist. "
                    + "Returns a per-repo report of actions taken, steps skipped, and any errors."
    )
    @APIResponse(responseCode = "200", description = "Standardization complete — see per-repo results for details")
    public Response standardizeAll() {
        List<RepoResult> results = standardizationService.standardizeAll();

        long errorCount = results.stream().filter(RepoResult::hasErrors).count();
        long actionCount = results.stream().mapToLong(r -> r.actions().size()).sum();

        return Response.ok(Map.of(
                "reposProcessed", results.size(),
                "totalActions", actionCount,
                "reposWithErrors", errorCount,
                "results", results.stream().map(RepoResult::toMap).toList()
        )).build();
    }

    @POST
    @Path("/standardize/{workspace}/{repoSlug}")
    @Operation(
            operationId = "standardizeRepoBranches",
            summary = "Standardize branches for a single repository",
            description = "Runs branch standardization for one repository: renames master → main if master "
                    + "exists, sets main as the default branch, and creates develop from main if absent. "
                    + "Returns a report of actions taken, steps skipped, and any errors."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Standardization complete"),
            @APIResponse(responseCode = "409", description = "Standardization completed with errors")
    })
    public Response standardizeRepo(
            @Parameter(description = "Bitbucket workspace slug", required = true)
            @PathParam("workspace") String workspace,
            @Parameter(description = "Bitbucket repository slug", required = true)
            @PathParam("repoSlug") String repoSlug) {

        RepoResult result = standardizationService.standardizeRepo(workspace, repoSlug);
        int status = result.hasErrors() ? 409 : 200;
        return Response.status(status).entity(result.toMap()).build();
    }
}
