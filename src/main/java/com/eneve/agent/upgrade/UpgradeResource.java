package com.eneve.agent.upgrade;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
 * REST endpoints for manually triggering Quarkus upgrade checks.
 * Background execution follows the same pattern as {@code CodeGraphResource}.
 */
@Path("/upgrades")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Upgrades", description = "Automated framework version upgrade detection and plan creation")
public class UpgradeResource {

    private static final Logger LOG = Logger.getLogger(UpgradeResource.class);

    @Inject UpgradeService upgradeService;
    @Inject MavenCentralClient mavenCentralClient;

    private final ExecutorService upgradeExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "upgrade-check");
        t.setDaemon(true);
        return t;
    });

    @POST
    @Path("/check")
    @Operation(
            operationId = "checkAllUpgrades",
            summary = "Check all Quarkus repos for available upgrades",
            description = "Scans all repos in repo_settings whose detected archetype is 'quarkus' "
                    + "and creates auto-executing upgrade plans for any that are below the latest "
                    + "Quarkus version. Runs in the background and returns immediately."
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
            summary = "Check a single repository for a Quarkus upgrade",
            description = "Checks whether the given repository is below the latest Quarkus version "
                    + "and, if so, creates and auto-executes an upgrade plan. Returns immediately."
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

        upgradeExecutor.submit(() -> {
            try {
                UpgradeService.UpgradeResult result = upgradeService.checkAndUpgradeOne(workspace, repoSlug);
                LOG.infof("Upgrade check for %s/%s: checked=%d, outdated=%d, plans=%d",
                        workspace, repoSlug, result.checked(), result.outdated(), result.plansCreated());
            } catch (Exception e) {
                LOG.errorf("Upgrade check failed for %s/%s: %s", workspace, repoSlug, e.getMessage());
            }
        });

        return Response.accepted(Map.of(
                "action", "upgrade_check_started",
                "workspace", workspace,
                "repoSlug", repoSlug
        )).build();
    }

    @GET
    @Path("/latest-versions")
    @Operation(
            operationId = "getLatestVersions",
            summary = "Return the latest known framework versions",
            description = "Returns the latest stable Quarkus version fetched from Maven Central "
                    + "(result may be cached). Useful for verifying connectivity and current state."
    )
    @APIResponse(responseCode = "200", description = "Latest versions")
    public Response latestVersions() {
        Optional<String> quarkus = mavenCentralClient.getLatestQuarkusVersion();
        return Response.ok(Map.of(
                "quarkus", quarkus.orElse("unavailable")
        )).build();
    }
}
