package com.eneve.agent;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.models.ModelListParams;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * REST API for fetching available AI model lists from provider APIs.
 */
@RolesAllowed("app_admin")
@Path("/models")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Models", description = "Fetch available AI models from provider APIs")
public class ModelsResource {

    @Inject
    AnthropicClient anthropicClient;

    @GET
    @Path("/claude")
    @Operation(
            operationId = "listClaudeModels",
            summary = "List available Claude models",
            description = "Fetches the current list of available Claude models from the Anthropic API, "
                    + "sorted newest first. Returns model id and display name for each entry."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "List of available Claude models"),
            @APIResponse(responseCode = "503", description = "Anthropic API unreachable or API key not configured")
    })
    public Response listClaudeModels() {
        try {
            var page = anthropicClient.models().list(ModelListParams.builder().limit(1000L).build());
            List<Map<String, String>> models = StreamSupport
                    .stream(page.autoPager().spliterator(), false)
                    .map(m -> Map.of(
                            "id", m.id(),
                            "displayName", m.displayName()
                    ))
                    .collect(Collectors.toList());
            return Response.ok(models).build();
        } catch (Exception e) {
            return Response.status(503)
                    .entity(Map.of("error", "Failed to fetch models from Anthropic API: " + e.getMessage()))
                    .build();
        }
    }
}
