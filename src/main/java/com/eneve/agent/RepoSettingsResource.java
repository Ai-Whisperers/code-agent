package com.eneve.agent;

import java.util.List;
import java.util.Map;

import com.eneve.agent.agent.RepoSettings;
import com.eneve.agent.agent.RepoSettingsStore;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * REST endpoints for managing per-repository settings — review enablement,
 * shared rule selection, and custom review prompt templates.
 */
@Path("/settings/repos")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Repo Settings", description = "Manage per-repository review settings and prompt templates")
public class RepoSettingsResource {

    @Inject
    RepoSettingsStore settingsStore;

    @GET
    @Operation(
            operationId = "listRepoSettings",
            summary = "List all configured repositories",
            description = "Returns settings for every repository that has been configured."
    )
    @APIResponse(responseCode = "200", description = "List of repo settings")
    public Response list() {
        List<RepoSettings> settings = settingsStore.listAll();
        return Response.ok(settings).build();
    }

    @GET
    @Path("/{workspace}/{repoSlug}")
    @Operation(
            operationId = "getRepoSettings",
            summary = "Get settings for a repository",
            description = "Returns the current settings for the specified workspace and repository."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Repo settings"),
            @APIResponse(responseCode = "404", description = "No settings found for this repo")
    })
    public Response get(
            @Parameter(description = "Bitbucket workspace slug", required = true)
            @PathParam("workspace") String workspace,
            @Parameter(description = "Bitbucket repository slug", required = true)
            @PathParam("repoSlug") String repoSlug) {

        return settingsStore.find(workspace, repoSlug)
                .map(s -> Response.ok(s).build())
                .orElse(Response.status(404)
                        .entity(Map.of("error", "No settings found for " + workspace + "/" + repoSlug))
                        .build());
    }

    @PUT
    @Path("/{workspace}/{repoSlug}")
    @Operation(
            operationId = "upsertRepoSettings",
            summary = "Create or update repository settings",
            description = "Creates or fully replaces the settings for a repository. "
                    + "Fields not provided will be set to their defaults."
    )
    @APIResponse(responseCode = "200", description = "Settings saved")
    public Response upsert(
            @Parameter(description = "Bitbucket workspace slug", required = true)
            @PathParam("workspace") String workspace,
            @Parameter(description = "Bitbucket repository slug", required = true)
            @PathParam("repoSlug") String repoSlug,
            @RequestBody(description = "Repository settings", required = true)
            UpsertRepoSettingsRequest request) {

        boolean enabled = request.reviewEnabled() != null ? request.reviewEnabled() : true;
        List<String> ruleNames = request.ruleNames() != null ? request.ruleNames() : List.of();
        String prompt = request.reviewPrompt();

        settingsStore.upsert(workspace, repoSlug, enabled, ruleNames, prompt);

        return Response.ok(Map.of(
                "action", "saved",
                "workspace", workspace,
                "repoSlug", repoSlug,
                "reviewEnabled", enabled
        )).build();
    }

    @PATCH
    @Path("/{workspace}/{repoSlug}/enable")
    @Operation(
            operationId = "enableRepoReview",
            summary = "Enable automated review for a repository",
            description = "Turns on automated PR review for the specified repository."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Review enabled"),
            @APIResponse(responseCode = "404", description = "No settings found for this repo")
    })
    public Response enable(
            @Parameter(description = "Bitbucket workspace slug", required = true)
            @PathParam("workspace") String workspace,
            @Parameter(description = "Bitbucket repository slug", required = true)
            @PathParam("repoSlug") String repoSlug) {

        if (settingsStore.find(workspace, repoSlug).isEmpty()) {
            return Response.status(404)
                    .entity(Map.of("error", "No settings found for " + workspace + "/" + repoSlug))
                    .build();
        }
        settingsStore.setReviewEnabled(workspace, repoSlug, true);
        return Response.ok(Map.of("action", "enabled", "workspace", workspace, "repoSlug", repoSlug)).build();
    }

    @PATCH
    @Path("/{workspace}/{repoSlug}/disable")
    @Operation(
            operationId = "disableRepoReview",
            summary = "Disable automated review for a repository",
            description = "Turns off automated PR review for the specified repository. "
                    + "Incoming webhooks for this repo will be silently skipped."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Review disabled"),
            @APIResponse(responseCode = "404", description = "No settings found for this repo")
    })
    public Response disable(
            @Parameter(description = "Bitbucket workspace slug", required = true)
            @PathParam("workspace") String workspace,
            @Parameter(description = "Bitbucket repository slug", required = true)
            @PathParam("repoSlug") String repoSlug) {

        if (settingsStore.find(workspace, repoSlug).isEmpty()) {
            return Response.status(404)
                    .entity(Map.of("error", "No settings found for " + workspace + "/" + repoSlug))
                    .build();
        }
        settingsStore.setReviewEnabled(workspace, repoSlug, false);
        return Response.ok(Map.of("action", "disabled", "workspace", workspace, "repoSlug", repoSlug)).build();
    }

    @DELETE
    @Path("/{workspace}/{repoSlug}")
    @Operation(
            operationId = "deleteRepoSettings",
            summary = "Remove repository settings",
            description = "Deletes all settings for the repository, reverting it to global defaults."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Settings deleted"),
            @APIResponse(responseCode = "404", description = "No settings found for this repo")
    })
    public Response delete(
            @Parameter(description = "Bitbucket workspace slug", required = true)
            @PathParam("workspace") String workspace,
            @Parameter(description = "Bitbucket repository slug", required = true)
            @PathParam("repoSlug") String repoSlug) {

        boolean deleted = settingsStore.delete(workspace, repoSlug);
        if (!deleted) {
            return Response.status(404)
                    .entity(Map.of("error", "No settings found for " + workspace + "/" + repoSlug))
                    .build();
        }
        return Response.ok(Map.of("action", "deleted", "workspace", workspace, "repoSlug", repoSlug)).build();
    }

    public record UpsertRepoSettingsRequest(
            Boolean reviewEnabled,
            List<String> ruleNames,
            String reviewPrompt
    ) {}
}
