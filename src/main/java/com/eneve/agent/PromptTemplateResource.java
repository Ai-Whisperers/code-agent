package com.eneve.agent;

import java.util.Map;

import com.eneve.agent.agent.service.PromptTemplateService;
import com.eneve.agent.agent.store.PromptTemplateStore;

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
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * REST endpoints for viewing and overriding AI prompt templates.
 *
 * All templates have JSON-file defaults. A PUT stores a DB override that takes precedence.
 * A DELETE removes the override, reverting the key to its JSON default.
 */
@Path("/settings/prompts")
@RolesAllowed("app_admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Prompt Templates", description = "Manage AI prompt templates — view defaults and create per-key overrides")
public class PromptTemplateResource {

    @Inject
    PromptTemplateService templateService;

    @Inject
    PromptTemplateStore templateStore;

    @GET
    @Operation(
            operationId = "listPromptTemplates",
            summary = "List all prompt templates",
            description = "Returns all known prompt templates merged with any DB overrides. "
                    + "The 'overridden' flag indicates whether a DB override is active for that key."
    )
    @APIResponse(responseCode = "200", description = "List of all templates")
    public Response list() {
        return Response.ok(templateService.listAll()).build();
    }

    @GET
    @Path("/{key}")
    @Operation(
            operationId = "getPromptTemplate",
            summary = "Get a prompt template by key",
            description = "Returns the current template content (DB override if present, otherwise JSON default), "
                    + "the original default, available placeholders, and override status."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Template details"),
            @APIResponse(responseCode = "404", description = "Unknown template key")
    })
    public Response get(
            @Parameter(description = "Template key (e.g. review, fix-pr, guardrails.writable)", required = true)
            @PathParam("key") String key) {

        return templateService.get(key)
                .map(info -> Response.ok(info).build())
                .orElse(Response.status(404)
                        .entity(Map.of("error", "Unknown prompt template key: " + key))
                        .build());
    }

    @PUT
    @Path("/{key}")
    @Operation(
            operationId = "upsertPromptTemplate",
            summary = "Create or update a prompt template override",
            description = "Stores a DB override for the given key. The override takes precedence over the "
                    + "JSON default at runtime. Use {{PLACEHOLDER}} syntax for dynamic values."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Override saved"),
            @APIResponse(responseCode = "400", description = "Unknown template key or missing content")
    })
    public Response upsert(
            @Parameter(description = "Template key", required = true)
            @PathParam("key") String key,
            @RequestBody(description = "New template content", required = true)
            UpsertPromptTemplateRequest request) {

        if (!templateService.isKnownKey(key)) {
            return Response.status(400)
                    .entity(Map.of("error", "Unknown prompt template key: " + key))
                    .build();
        }
        if (request == null || request.content() == null || request.content().isBlank()) {
            return Response.status(400)
                    .entity(Map.of("error", "content must not be blank"))
                    .build();
        }

        templateStore.upsert(key, request.content(), request.description());
        return Response.ok(Map.of("action", "saved", "key", key)).build();
    }

    @DELETE
    @Path("/{key}")
    @Operation(
            operationId = "deletePromptTemplateOverride",
            summary = "Delete a prompt template override",
            description = "Removes the DB override for the given key. "
                    + "The template reverts to the built-in JSON default."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Override deleted"),
            @APIResponse(responseCode = "404", description = "No override exists for this key")
    })
    public Response delete(
            @Parameter(description = "Template key", required = true)
            @PathParam("key") String key) {

        boolean deleted = templateStore.delete(key);
        if (!deleted) {
            return Response.status(404)
                    .entity(Map.of("error", "No override found for key: " + key))
                    .build();
        }
        return Response.ok(Map.of("action", "deleted", "key", key)).build();
    }

    @POST
    @Path("/{key}/preview")
    @Operation(
            operationId = "previewPromptTemplate",
            summary = "Preview a rendered prompt template",
            description = "Resolves the template for the given key using the supplied placeholder values "
                    + "and returns the rendered text. Useful for validating a template before saving it."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Rendered template text"),
            @APIResponse(responseCode = "404", description = "Unknown template key")
    })
    public Response preview(
            @Parameter(description = "Template key", required = true)
            @PathParam("key") String key,
            @RequestBody(description = "Placeholder values to substitute", required = false)
            PreviewRequest request) {

        if (!templateService.isKnownKey(key)) {
            return Response.status(404)
                    .entity(Map.of("error", "Unknown prompt template key: " + key))
                    .build();
        }

        Map<String, String> placeholders = (request != null && request.placeholders() != null)
                ? request.placeholders()
                : Map.of();

        String rendered = templateService.resolve(key, placeholders);
        return Response.ok(Map.of("key", key, "content", rendered)).build();
    }

    // ─── Request/response records ─────────────────────────────────────

    public record UpsertPromptTemplateRequest(String content, String description) {}

    public record PreviewRequest(Map<String, String> placeholders) {}
}
