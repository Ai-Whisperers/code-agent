package com.eneve.agent.upgrade;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.eneve.agent.agent.model.RepoSettings;
import com.eneve.agent.agent.store.RepoSettingsStore;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * REST endpoints for triggering framework upgrade checks and inspecting the latest known
 * framework versions.
 *
 * <p>Background execution follows the same pattern as {@code CodeGraphResource}.
 */
@Path("/upgrades")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Upgrades", description = "Automated framework version upgrade detection and plan creation")
public class UpgradeResource {

    private static final Logger LOG = Logger.getLogger(UpgradeResource.class);

    @Inject UpgradeService upgradeService;
    @Inject RepoSettingsStore repoSettingsStore;
    @Inject MavenCentralClient mavenCentralClient;
    @Inject DotnetReleaseClient dotnetReleaseClient;
    @Inject WildflyReleaseClient wildflyReleaseClient;
    @Inject JavaLtsClient javaLtsClient;
    @Inject PhpReleaseClient phpReleaseClient;
    @Inject NpmRegistryClient npmRegistryClient;
    @Inject PackagistClient packagistClient;
    @Inject PostgresJdbcClient postgresJdbcClient;

    private final ExecutorService upgradeExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "upgrade-check");
        t.setDaemon(true);
        return t;
    });

    @POST
    @Path("/check")
    @Operation(
            operationId = "checkAllUpgrades",
            summary = "Check all supported repos for available framework upgrades",
            description = "Scans all repos in repo_settings whose archetype is one of the "
                    + "supported types (quarkus, dotnet, wildfly, angular, react, laravel, symfony, php) "
                    + "and creates auto-executing upgrade plans for any that are below the latest "
                    + "published version. Runs in the background and returns immediately."
    )
    @APIResponse(responseCode = "202", description = "Upgrade check accepted and running in background")
    public Response checkAll() {
        upgradeExecutor.submit(() -> {
            try {
                UpgradeService.UpgradeResult result = upgradeService.checkAndUpgradeAll();
                LOG.infof("Upgrade check finished: %d checked, %d outdated, %d plans created",
                        result.checked(), result.outdated(), result.plansCreated());
            } catch (Exception e) {
                LOG.errorf("Upgrade check failed: %s", e.getMessage());
            }
        });

        return Response.accepted(Map.of("action", "upgrade_check_started")).build();
    }

    @POST
    @Path("/check/{workspace}/{repoSlug}")
    @Operation(
            operationId = "checkOneUpgrade",
            summary = "Check a single repository for an available framework upgrade",
            description = "Checks whether the given repository is below the latest version for its "
                    + "detected archetype and, if so, creates and auto-executes an upgrade plan. "
                    + "Returns immediately; the upgrade runs in the background."
    )
    @APIResponses({
            @APIResponse(responseCode = "202", description = "Upgrade check accepted"),
            @APIResponse(responseCode = "400", description = "Missing path parameters")
    })
    public Response checkOne(
            @Parameter(description = "Workspace or GitLab namespace", required = true)
            @PathParam("workspace") String workspace,
            @Parameter(description = "Repository slug", required = true)
            @PathParam("repoSlug") String repoSlug) {

        try {
            UpgradeService.UpgradeResult result = upgradeService.checkAndUpgradeOne(workspace, repoSlug);
            LOG.infof("Upgrade check for %s/%s: checked=%d, outdated=%d, plans=%d",
                    workspace, repoSlug, result.checked(), result.outdated(), result.plansCreated());

            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("action", "upgrade_check_started");
            body.put("workspace", workspace);
            body.put("repoSlug", repoSlug);
            if (!result.planIds().isEmpty()) {
                body.put("planId", result.planIds().get(0));
            }
            return Response.accepted(body).build();
        } catch (Exception e) {
            LOG.errorf("Upgrade check failed for %s/%s: %s", workspace, repoSlug, e.getMessage());
            return Response.serverError()
                    .entity(Map.of("error", "Upgrade check failed: " + e.getMessage()))
                    .build();
        }
    }

    @GET
    @Path("/latest-versions")
    @Operation(
            operationId = "getLatestVersions",
            summary = "Return the latest known framework versions",
            description = "Returns the latest stable version for each supported framework, "
                    + "fetched from official release sources (results may be cached). "
                    + "Useful for verifying connectivity and inspecting the current upgrade baseline."
    )
    @APIResponse(responseCode = "200", description = "Latest versions per framework")
    public Response latestVersions() {
        Map<String, String> versions = new LinkedHashMap<>();
        versions.put("quarkus",  mavenCentralClient.getLatestQuarkusVersion().orElse("unavailable"));
        versions.put("dotnet",   dotnetReleaseClient.getLatestDotnetVersion().orElse("unavailable"));
        versions.put("wildfly",  wildflyReleaseClient.getLatestWildflyVersion().orElse("unavailable"));
        versions.put("java-lts", javaLtsClient.getLatestJavaLtsVersion().orElse("unavailable"));
        versions.put("php",      phpReleaseClient.getLatestPhpVersion().orElse("unavailable"));
        versions.put("react",    npmRegistryClient.getLatestVersion("react").orElse("unavailable"));
        versions.put("angular",  npmRegistryClient.getLatestVersion("@angular/core").orElse("unavailable"));
        versions.put("laravel",          packagistClient.getLatestVersion("laravel", "framework").orElse("unavailable"));
        versions.put("symfony",          packagistClient.getLatestVersion("symfony", "framework-bundle").orElse("unavailable"));
        versions.put("postgresql-jdbc",  postgresJdbcClient.getLatestPostgresJdbcVersion().orElse("unavailable"));
        return Response.ok(versions).build();
    }

    @GET
    @Path("/dependency-status/{dependency}")
    @Operation(
            operationId = "getDependencyStatus",
            summary = "Show upgrade status for a tracked dependency across all repos",
            description = "Returns all repositories that have a detected version for the given dependency "
                    + "(e.g. 'postgresql-jdbc'), together with each repo's current version, the latest "
                    + "stable version, and whether an upgrade is needed. "
                    + "Results are sorted: outdated repos first, then up-to-date ones."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Dependency status per repository"),
            @APIResponse(responseCode = "404", description = "Unknown dependency name")
    })
    public Response dependencyStatus(
            @Parameter(description = "Dependency key, e.g. 'postgresql-jdbc'", required = true)
            @PathParam("dependency") String dependency) {

        String latestVersion = resolveLatestDependencyVersion(dependency);
        if (latestVersion == null) {
            return Response.status(404)
                    .entity(Map.of("error", "Unknown dependency: " + dependency))
                    .build();
        }

        List<RepoSettings> repos = repoSettingsStore.listByDependency(dependency);

        List<Map<String, Object>> outdated = new ArrayList<>();
        List<Map<String, Object>> upToDate = new ArrayList<>();

        for (RepoSettings repo : repos) {
            String currentVersion = repo.dependencyVersions().get(dependency);
            String source         = repo.dependencyVersions().get(dependency + "-source");
            boolean needsUpgrade  = UpgradeService.isOlderVersion(currentVersion, latestVersion);

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("workspace",      repo.workspace());
            entry.put("repoSlug",       repo.repoSlug());
            entry.put("archetype",      repo.archetype());
            entry.put("currentVersion", currentVersion);
            entry.put("latestVersion",  latestVersion);
            entry.put("source",         source != null ? source : "unknown");
            entry.put("upgradeNeeded",  needsUpgrade);

            if (needsUpgrade) {
                outdated.add(entry);
            } else {
                upToDate.add(entry);
            }
        }

        List<Map<String, Object>> allRepos = new ArrayList<>(outdated);
        allRepos.addAll(upToDate);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dependency",    dependency);
        result.put("latestVersion", latestVersion);
        result.put("total",         allRepos.size());
        result.put("outdated",      outdated.size());
        result.put("repos",         allRepos);

        return Response.ok(result).build();
    }

    /**
     * Resolves the latest stable version for a tracked dependency key.
     * Returns {@code null} if the dependency is not recognised.
     */
    private String resolveLatestDependencyVersion(String dependency) {
        return switch (dependency) {
            case "postgresql-jdbc" -> postgresJdbcClient.getLatestPostgresJdbcVersion().orElse(null);
            default                -> null;
        };
    }
}
