package com.eneve.agent;

import java.util.Map;

import com.eneve.agent.audit.AuditService;
import com.eneve.agent.settings.SettingsService;
import com.eneve.agent.settings.SettingsService.UpsertRequest;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * REST API for managing runtime application settings stored in the database.
 *
 * Settings override their corresponding application.properties / environment-variable
 * values without requiring a restart. Secret values are encrypted at rest (AES-256-GCM)
 * and are never returned in plaintext — they appear as "****" in all responses.
 *
 * Deleting a key reverts that setting to its application.properties / env-var default.
 */
@RolesAllowed("app_admin")
@Path("/settings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Settings", description = "Manage runtime application settings and secrets without restarting")
public class SettingsResource {

    @Inject
    SettingsService settingsService;

    @Inject
    AuditService auditService;

    @GET
    @Operation(
            operationId = "listSettings",
            summary = "List all DB-stored settings",
            description = "Returns every setting currently stored in the database. "
                    + "Secret values are masked as '****'. "
                    + "Settings not present here fall back to application.properties / env vars."
    )
    @APIResponse(responseCode = "200", description = "List of settings")
    public Response list() {
        return Response.ok(settingsService.listAll()).build();
    }

    @GET
    @Path("/{key}")
    @Operation(
            operationId = "getSetting",
            summary = "Get a single setting by key",
            description = "Returns the DB row for the given key. Secret value is masked. "
                    + "Returns 404 when no DB override exists (the setting may still be active via env var)."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Setting details"),
            @APIResponse(responseCode = "404", description = "No DB override for this key")
    })
    public Response get(
            @Parameter(description = "Setting key (e.g. anthropic.api.key)", required = true)
            @PathParam("key") String key) {

        return settingsService.findView(key)
                .map(v -> Response.ok(v).build())
                .orElse(Response.status(404)
                        .entity(Map.of("error", "No DB override found for key: " + key,
                                       "hint",  "The setting may still be active via application.properties or env var"))
                        .build());
    }

    @PUT
    @Path("/{key}")
    @Operation(
            operationId = "upsertSetting",
            summary = "Create or update a setting",
            description = "Stores the value in the database. When isSecret=true the value is "
                    + "encrypted with AES-256-GCM before storage — SETTINGS_ENCRYPTION_KEY must be configured. "
                    + "The new value takes effect within the cache TTL (default 30 s) without a restart."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Setting saved"),
            @APIResponse(responseCode = "400", description = "Missing or blank value"),
            @APIResponse(responseCode = "500", description = "Encryption not configured (secret=true but no master key)")
    })
    public Response upsert(
            @Parameter(description = "Setting key", required = true)
            @PathParam("key") String key,
            @RequestBody(description = "Setting value and metadata", required = true)
            UpsertRequest request) {

        if (request == null || request.value() == null || request.value().isBlank()) {
            return Response.status(400)
                    .entity(Map.of("error", "value must not be blank"))
                    .build();
        }

        try {
            settingsService.set(key, request.value(), request.isSecret(), request.description());
        } catch (IllegalStateException e) {
            return Response.status(500)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }

        auditService.log("SETTINGS", "SETTING_CHANGED", "setting", key,
                Map.of("isSecret", String.valueOf(request.isSecret())));
        return Response.ok(Map.of(
                "action",   "saved",
                "key",      key,
                "isSecret", request.isSecret()
        )).build();
    }

    @DELETE
    @Path("/{key}")
    @Operation(
            operationId = "deleteSetting",
            summary = "Delete a setting override",
            description = "Removes the DB row for the given key. "
                    + "The setting reverts to its application.properties / environment-variable value. "
                    + "Returns 404 when no DB override exists."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Setting deleted"),
            @APIResponse(responseCode = "404", description = "No DB override for this key")
    })
    public Response delete(
            @Parameter(description = "Setting key", required = true)
            @PathParam("key") String key) {

        boolean deleted = settingsService.delete(key);
        if (!deleted) {
            return Response.status(404)
                    .entity(Map.of("error", "No DB override found for key: " + key))
                    .build();
        }
        auditService.log("SETTINGS", "SETTING_DELETED", "setting", key, null);
        return Response.ok(Map.of("action", "deleted", "key", key)).build();
    }
}
