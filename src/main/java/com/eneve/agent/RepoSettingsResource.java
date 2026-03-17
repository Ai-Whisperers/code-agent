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
        boolean vectorEnabled = request.vectorEnabled() != null ? request.vectorEnabled() : false;
        boolean docsEnabled = request.docsEnabled() != null ? request.docsEnabled() : true;
        boolean upgradeEnabled = request.upgradeEnabled() != null ? request.upgradeEnabled() : true;
        boolean qualityReportEnabled = request.qualityReportEnabled() != null ? request.qualityReportEnabled() : false;
        boolean archived = request.archived() != null ? request.archived() : false;
        List<String> ruleNames = request.ruleNames() != null ? request.ruleNames() : List.of();
        String prompt = request.reviewPrompt();
        List<String> disabledHooks = request.disabledHooks() != null ? request.disabledHooks() : List.of();
        String confluenceSpaceKey = request.confluenceSpaceKey();
        String confluenceParentPageId = request.confluenceParentPageId();

        settingsStore.upsert(workspace, repoSlug, enabled, vectorEnabled, docsEnabled, upgradeEnabled,
                qualityReportEnabled, archived, ruleNames, prompt, disabledHooks, confluenceSpaceKey, confluenceParentPageId);

        return Response.ok(Map.of(
                "action", "saved",
                "workspace", workspace,
                "repoSlug", repoSlug,
                "reviewEnabled", enabled,
                "vectorEnabled", vectorEnabled,
                "docsEnabled", docsEnabled,
                "upgradeEnabled", upgradeEnabled,
                "qualityReportEnabled", qualityReportEnabled,
                "archived", archived
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

    @PATCH
    @Path("/{workspace}/{repoSlug}/vector/enable")
    @Operation(
            operationId = "enableRepoVector",
            summary = "Enable vector indexing for a repository",
            description = "Turns on semantic vector indexing for the specified repository. "
                    + "Embeddings will be generated on the next scheduled or manual graph build."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Vector indexing enabled"),
            @APIResponse(responseCode = "404", description = "No settings found for this repo")
    })
    public Response enableVector(
            @Parameter(description = "Bitbucket workspace slug", required = true)
            @PathParam("workspace") String workspace,
            @Parameter(description = "Bitbucket repository slug", required = true)
            @PathParam("repoSlug") String repoSlug) {

        if (settingsStore.find(workspace, repoSlug).isEmpty()) {
            return Response.status(404)
                    .entity(Map.of("error", "No settings found for " + workspace + "/" + repoSlug))
                    .build();
        }
        settingsStore.setVectorEnabled(workspace, repoSlug, true);
        return Response.ok(Map.of("action", "vector_enabled", "workspace", workspace, "repoSlug", repoSlug)).build();
    }

    @PATCH
    @Path("/{workspace}/{repoSlug}/vector/disable")
    @Operation(
            operationId = "disableRepoVector",
            summary = "Disable vector indexing for a repository",
            description = "Turns off semantic vector indexing for the specified repository. "
                    + "Existing embeddings are retained but no new ones will be generated."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Vector indexing disabled"),
            @APIResponse(responseCode = "404", description = "No settings found for this repo")
    })
    public Response disableVector(
            @Parameter(description = "Bitbucket workspace slug", required = true)
            @PathParam("workspace") String workspace,
            @Parameter(description = "Bitbucket repository slug", required = true)
            @PathParam("repoSlug") String repoSlug) {

        if (settingsStore.find(workspace, repoSlug).isEmpty()) {
            return Response.status(404)
                    .entity(Map.of("error", "No settings found for " + workspace + "/" + repoSlug))
                    .build();
        }
        settingsStore.setVectorEnabled(workspace, repoSlug, false);
        return Response.ok(Map.of("action", "vector_disabled", "workspace", workspace, "repoSlug", repoSlug)).build();
    }

    @PATCH
    @Path("/{workspace}/{repoSlug}/docs/enable")
    @Operation(
            operationId = "enableRepoDocs",
            summary = "Enable documentation generation for a repository",
            description = "Turns on automated documentation generation for the specified repository."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Docs generation enabled"),
            @APIResponse(responseCode = "404", description = "No settings found for this repo")
    })
    public Response enableDocs(
            @Parameter(description = "Bitbucket workspace slug", required = true)
            @PathParam("workspace") String workspace,
            @Parameter(description = "Bitbucket repository slug", required = true)
            @PathParam("repoSlug") String repoSlug) {

        if (settingsStore.find(workspace, repoSlug).isEmpty()) {
            return Response.status(404)
                    .entity(Map.of("error", "No settings found for " + workspace + "/" + repoSlug))
                    .build();
        }
        settingsStore.setDocsEnabled(workspace, repoSlug, true);
        return Response.ok(Map.of("action", "docs_enabled", "workspace", workspace, "repoSlug", repoSlug)).build();
    }

    @PATCH
    @Path("/{workspace}/{repoSlug}/docs/disable")
    @Operation(
            operationId = "disableRepoDocs",
            summary = "Disable documentation generation for a repository",
            description = "Turns off automated documentation generation for the specified repository."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Docs generation disabled"),
            @APIResponse(responseCode = "404", description = "No settings found for this repo")
    })
    public Response disableDocs(
            @Parameter(description = "Bitbucket workspace slug", required = true)
            @PathParam("workspace") String workspace,
            @Parameter(description = "Bitbucket repository slug", required = true)
            @PathParam("repoSlug") String repoSlug) {

        if (settingsStore.find(workspace, repoSlug).isEmpty()) {
            return Response.status(404)
                    .entity(Map.of("error", "No settings found for " + workspace + "/" + repoSlug))
                    .build();
        }
        settingsStore.setDocsEnabled(workspace, repoSlug, false);
        return Response.ok(Map.of("action", "docs_disabled", "workspace", workspace, "repoSlug", repoSlug)).build();
    }

    @PATCH
    @Path("/{workspace}/{repoSlug}/upgrade/enable")
    @Operation(
            operationId = "enableRepoUpgrade",
            summary = "Enable automatic upgrades for a repository",
            description = "Turns on automated framework version upgrades for the specified repository. "
                    + "The repo will be included in the next upgrade scheduler run."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Auto-upgrade enabled"),
            @APIResponse(responseCode = "404", description = "No settings found for this repo")
    })
    public Response enableUpgrade(
            @Parameter(description = "Workspace or GitLab namespace", required = true)
            @PathParam("workspace") String workspace,
            @Parameter(description = "Repository slug", required = true)
            @PathParam("repoSlug") String repoSlug) {

        if (settingsStore.find(workspace, repoSlug).isEmpty()) {
            return Response.status(404)
                    .entity(Map.of("error", "No settings found for " + workspace + "/" + repoSlug))
                    .build();
        }
        settingsStore.setUpgradeEnabled(workspace, repoSlug, true);
        return Response.ok(Map.of("action", "upgrade_enabled", "workspace", workspace, "repoSlug", repoSlug)).build();
    }

    @PATCH
    @Path("/{workspace}/{repoSlug}/upgrade/disable")
    @Operation(
            operationId = "disableRepoUpgrade",
            summary = "Disable automatic upgrades for a repository",
            description = "Prevents the automated upgrade scheduler from creating upgrade plans "
                    + "for the specified repository. Manual triggers via the /upgrades API still work."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Auto-upgrade disabled"),
            @APIResponse(responseCode = "404", description = "No settings found for this repo")
    })
    public Response disableUpgrade(
            @Parameter(description = "Workspace or GitLab namespace", required = true)
            @PathParam("workspace") String workspace,
            @Parameter(description = "Repository slug", required = true)
            @PathParam("repoSlug") String repoSlug) {

        if (settingsStore.find(workspace, repoSlug).isEmpty()) {
            return Response.status(404)
                    .entity(Map.of("error", "No settings found for " + workspace + "/" + repoSlug))
                    .build();
        }
        settingsStore.setUpgradeEnabled(workspace, repoSlug, false);
        return Response.ok(Map.of("action", "upgrade_disabled", "workspace", workspace, "repoSlug", repoSlug)).build();
    }

    @PATCH
    @Path("/{workspace}/{repoSlug}/quality-report/enable")
    @Operation(
            operationId = "enableRepoQualityReport",
            summary = "Enable quality report collection for a repository",
            description = "Turns on automated quality report collection for the specified repository. "
                    + "The repo will be included in the next scheduled quality report run."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Quality report enabled"),
            @APIResponse(responseCode = "404", description = "No settings found for this repo")
    })
    public Response enableQualityReport(
            @Parameter(description = "Workspace or GitLab namespace", required = true)
            @PathParam("workspace") String workspace,
            @Parameter(description = "Repository slug", required = true)
            @PathParam("repoSlug") String repoSlug) {

        if (settingsStore.find(workspace, repoSlug).isEmpty()) {
            return Response.status(404)
                    .entity(Map.of("error", "No settings found for " + workspace + "/" + repoSlug))
                    .build();
        }
        settingsStore.setQualityReportEnabled(workspace, repoSlug, true);
        return Response.ok(Map.of("action", "quality_report_enabled", "workspace", workspace, "repoSlug", repoSlug)).build();
    }

    @PATCH
    @Path("/{workspace}/{repoSlug}/quality-report/disable")
    @Operation(
            operationId = "disableRepoQualityReport",
            summary = "Disable quality report collection for a repository",
            description = "Prevents the quality report scheduler from collecting reports for this repository. "
                    + "Existing report history is retained."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Quality report disabled"),
            @APIResponse(responseCode = "404", description = "No settings found for this repo")
    })
    public Response disableQualityReport(
            @Parameter(description = "Workspace or GitLab namespace", required = true)
            @PathParam("workspace") String workspace,
            @Parameter(description = "Repository slug", required = true)
            @PathParam("repoSlug") String repoSlug) {

        if (settingsStore.find(workspace, repoSlug).isEmpty()) {
            return Response.status(404)
                    .entity(Map.of("error", "No settings found for " + workspace + "/" + repoSlug))
                    .build();
        }
        settingsStore.setQualityReportEnabled(workspace, repoSlug, false);
        return Response.ok(Map.of("action", "quality_report_disabled", "workspace", workspace, "repoSlug", repoSlug)).build();
    }

    @PATCH
    @Path("/{workspace}/{repoSlug}/archive")
    @Operation(
            operationId = "archiveRepo",
            summary = "Archive a repository",
            description = "Marks the repository as archived. Archived repositories are excluded from "
                    + "scheduled jobs and can be filtered out in the frontend."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Repository archived"),
            @APIResponse(responseCode = "404", description = "No settings found for this repo")
    })
    public Response archive(
            @Parameter(description = "Workspace or GitLab namespace", required = true)
            @PathParam("workspace") String workspace,
            @Parameter(description = "Repository slug", required = true)
            @PathParam("repoSlug") String repoSlug) {

        if (settingsStore.find(workspace, repoSlug).isEmpty()) {
            return Response.status(404)
                    .entity(Map.of("error", "No settings found for " + workspace + "/" + repoSlug))
                    .build();
        }
        settingsStore.setArchived(workspace, repoSlug, true);
        return Response.ok(Map.of("action", "archived", "workspace", workspace, "repoSlug", repoSlug)).build();
    }

    @PATCH
    @Path("/{workspace}/{repoSlug}/unarchive")
    @Operation(
            operationId = "unarchiveRepo",
            summary = "Unarchive a repository",
            description = "Removes the archived flag from the repository, making it visible and active again."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Repository unarchived"),
            @APIResponse(responseCode = "404", description = "No settings found for this repo")
    })
    public Response unarchive(
            @Parameter(description = "Workspace or GitLab namespace", required = true)
            @PathParam("workspace") String workspace,
            @Parameter(description = "Repository slug", required = true)
            @PathParam("repoSlug") String repoSlug) {

        if (settingsStore.find(workspace, repoSlug).isEmpty()) {
            return Response.status(404)
                    .entity(Map.of("error", "No settings found for " + workspace + "/" + repoSlug))
                    .build();
        }
        settingsStore.setArchived(workspace, repoSlug, false);
        return Response.ok(Map.of("action", "unarchived", "workspace", workspace, "repoSlug", repoSlug)).build();
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
            Boolean vectorEnabled,
            Boolean docsEnabled,
            Boolean upgradeEnabled,
            Boolean qualityReportEnabled,
            Boolean archived,
            List<String> ruleNames,
            String reviewPrompt,
            List<String> disabledHooks,
            String confluenceSpaceKey,
            String confluenceParentPageId
    ) {}
}
