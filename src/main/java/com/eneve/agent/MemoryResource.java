package com.eneve.agent;

import java.util.List;
import java.util.Map;

import com.eneve.agent.agent.model.MemoryEntry;
import com.eneve.agent.agent.store.MemoryStore;

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
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * REST endpoints for managing the review memory — team preferences and
 * learned patterns that the reviewer remembers across PR reviews.
 */
@Path("/memory")
@RolesAllowed("app_admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Review Memory", description = "Manage learned team preferences used during PR reviews")
public class MemoryResource {

    @Inject
    MemoryStore memoryStore;

    @GET
    @Path("/{workspace}/{repoSlug}")
    @Operation(
            operationId = "listMemories",
            summary = "List review memories for a repository",
            description = "Returns all memory entries (including inactive) for the given workspace and repository."
    )
    @APIResponse(responseCode = "200", description = "List of memory entries")
    public Response list(
            @Parameter(description = "Bitbucket workspace slug", required = true)
            @PathParam("workspace") String workspace,
            @Parameter(description = "Bitbucket repository slug", required = true)
            @PathParam("repoSlug") String repoSlug) {

        List<MemoryEntry> memories = memoryStore.listAll(workspace, repoSlug);
        return Response.ok(memories).build();
    }

    @POST
    @Operation(
            operationId = "createMemory",
            summary = "Manually add a review memory",
            description = "Creates a new explicit memory entry for a repository. "
                    + "The reviewer will respect this preference in future reviews."
    )
    @APIResponses({
            @APIResponse(responseCode = "201", description = "Memory created"),
            @APIResponse(responseCode = "400", description = "Invalid request")
    })
    public Response create(
            @RequestBody(description = "Memory entry to create", required = true)
            CreateMemoryRequest request) {

        if (request.workspace() == null || request.workspace().isBlank()
                || request.repoSlug() == null || request.repoSlug().isBlank()
                || request.memoryText() == null || request.memoryText().isBlank()) {
            return Response.status(400)
                    .entity(Map.of("error", "workspace, repoSlug, and memoryText are required"))
                    .build();
        }

        if (memoryStore.exists(request.workspace(), request.repoSlug(), request.memoryText())) {
            return Response.status(409)
                    .entity(Map.of("error", "This preference already exists for the repository"))
                    .build();
        }

        MemoryEntry entry = MemoryEntry.explicit(
                request.workspace(), request.repoSlug(),
                request.memoryText(), request.createdBy());
        memoryStore.save(entry);

        return Response.status(201)
                .entity(Map.of(
                        "action", "created",
                        "workspace", request.workspace(),
                        "repoSlug", request.repoSlug(),
                        "memory", request.memoryText()))
                .build();
    }

    @DELETE
    @Path("/{id}")
    @Operation(
            operationId = "deactivateMemory",
            summary = "Deactivate a review memory",
            description = "Soft-deletes a memory entry by setting it to inactive. "
                    + "The preference will no longer be included in future reviews."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Memory deactivated"),
            @APIResponse(responseCode = "404", description = "Memory not found")
    })
    public Response deactivate(
            @Parameter(description = "Memory entry ID", required = true)
            @PathParam("id") long id) {

        boolean updated = memoryStore.deactivate(id);
        if (!updated) {
            return Response.status(404)
                    .entity(Map.of("error", "Memory entry not found or already inactive"))
                    .build();
        }
        return Response.ok(Map.of("action", "deactivated", "id", id)).build();
    }

    public record CreateMemoryRequest(
            String workspace,
            String repoSlug,
            String memoryText,
            String createdBy
    ) {}
}
