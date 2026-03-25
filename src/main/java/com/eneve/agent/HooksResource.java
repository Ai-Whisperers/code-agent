package com.eneve.agent;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.eneve.agent.agent.model.AutomationHook;
import com.eneve.agent.agent.store.HookStore;

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
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * REST API for managing automation hooks — configurable triggers that
 * run agent tasks on events like PR merges or on a schedule.
 */
@Path("/settings/hooks")
@RolesAllowed("app_admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Automation Hooks", description = "Manage automation hook definitions and triggers")
public class HooksResource {

    @Inject HookStore hookStore;

    @GET
    @Operation(operationId = "listHooks", summary = "List all automation hooks")
    @APIResponse(responseCode = "200", description = "List of hooks")
    public Response list() {
        return Response.ok(hookStore.listAll()).build();
    }

    @GET
    @Path("/{name}")
    @Operation(operationId = "getHook", summary = "Get a hook by name")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Hook found"),
            @APIResponse(responseCode = "404", description = "Hook not found")
    })
    public Response get(
            @Parameter(description = "Hook name", required = true)
            @PathParam("name") String name) {
        return hookStore.findByName(name)
                .map(h -> Response.ok(h).build())
                .orElse(Response.status(404)
                        .entity(Map.of("error", "Hook not found: " + name))
                        .build());
    }

    @POST
    @Operation(operationId = "createHook", summary = "Create a new automation hook")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Hook created"),
            @APIResponse(responseCode = "409", description = "Hook with this name already exists")
    })
    public Response create(
            @RequestBody(description = "Hook definition", required = true)
            UpsertHookRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            return Response.status(400)
                    .entity(Map.of("error", "Hook name is required"))
                    .build();
        }
        if (hookStore.findByName(request.name()).isPresent()) {
            return Response.status(409)
                    .entity(Map.of("error", "Hook already exists: " + request.name()))
                    .build();
        }
        AutomationHook hook = toHook(request);
        hookStore.upsert(hook);
        return Response.ok(Map.of("action", "created", "name", hook.name())).build();
    }

    @PUT
    @Path("/{name}")
    @Operation(operationId = "updateHook", summary = "Update an existing automation hook")
    @APIResponse(responseCode = "200", description = "Hook updated")
    public Response update(
            @Parameter(description = "Hook name", required = true)
            @PathParam("name") String name,
            @RequestBody(description = "Hook definition", required = true)
            UpsertHookRequest request) {
        AutomationHook hook = toHook(name, request);
        hookStore.upsert(hook);
        return Response.ok(Map.of("action", "updated", "name", name)).build();
    }

    @PATCH
    @Path("/{name}/enable")
    @Operation(operationId = "enableHook", summary = "Enable an automation hook")
    @APIResponse(responseCode = "200", description = "Hook enabled")
    public Response enable(
            @Parameter(description = "Hook name", required = true)
            @PathParam("name") String name) {
        hookStore.setEnabled(name, true);
        return Response.ok(Map.of("action", "enabled", "name", name)).build();
    }

    @PATCH
    @Path("/{name}/disable")
    @Operation(operationId = "disableHook", summary = "Disable an automation hook")
    @APIResponse(responseCode = "200", description = "Hook disabled")
    public Response disable(
            @Parameter(description = "Hook name", required = true)
            @PathParam("name") String name) {
        hookStore.setEnabled(name, false);
        return Response.ok(Map.of("action", "disabled", "name", name)).build();
    }

    @DELETE
    @Path("/{name}")
    @Operation(operationId = "deleteHook", summary = "Delete an automation hook")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Hook deleted"),
            @APIResponse(responseCode = "404", description = "Hook not found")
    })
    public Response delete(
            @Parameter(description = "Hook name", required = true)
            @PathParam("name") String name) {
        if (hookStore.delete(name)) {
            return Response.ok(Map.of("action", "deleted", "name", name)).build();
        }
        return Response.status(404)
                .entity(Map.of("error", "Hook not found: " + name))
                .build();
    }

    // ─── Request / mapping ──────────────────────────────────────────────

    public record UpsertHookRequest(
            String name,
            String description,
            Boolean enabled,
            List<String> triggerTypes,
            String prEvent,
            String branchPattern,
            String cronExpr,
            String actionType,
            String prompt,
            List<String> ruleNames,
            String extraRules,
            String targetBranch,
            Boolean commitDirect,
            String repoUrl,
            Map<String, String> triggerFilter
    ) {}

    private AutomationHook toHook(UpsertHookRequest r) {
        return toHook(r.name(), r);
    }

    private AutomationHook toHook(String name, UpsertHookRequest r) {
        return new AutomationHook(
                null, name,
                r.description(),
                r.enabled() != null ? r.enabled() : true,
                r.triggerTypes() != null ? r.triggerTypes() : List.of(),
                r.prEvent(),
                r.branchPattern(),
                r.cronExpr(),
                r.actionType(),
                r.prompt(),
                r.ruleNames(),
                r.extraRules(),
                r.targetBranch(),
                r.commitDirect() != null ? r.commitDirect() : false,
                r.repoUrl(),
                r.triggerFilter() != null ? r.triggerFilter() : Map.of(),
                null, null
        );
    }
}
