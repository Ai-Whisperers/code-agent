package com.eneve.agent;

import com.eneve.agent.agent.store.CloudAccountStore;
import com.eneve.agent.model.CloudAccount;
import com.eneve.agent.model.CloudAccountType;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.Map;

@Path("/cloud-accounts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Cloud Accounts", description = "Manage named cloud provider accounts and their credentials")
public class CloudAccountResource {

    @Inject
    CloudAccountStore store;

    @GET
    @Operation(operationId = "listCloudAccounts", summary = "List all cloud accounts")
    @APIResponse(responseCode = "200", description = "List of cloud accounts (credentials masked)")
    public Response listCloudAccounts() {
        return Response.ok(store.listCloudAccounts()).build();
    }

    @GET
    @Path("/{id}")
    @Operation(operationId = "getCloudAccount", summary = "Get a cloud account by ID")
    @APIResponse(responseCode = "200", description = "Cloud account (credentials masked)")
    @APIResponse(responseCode = "404", description = "Not found")
    public Response getCloudAccount(
            @Parameter(required = true) @PathParam("id") String id) {
        return store.getCloudAccount(id)
                .map(a -> Response.ok(a).build())
                .orElse(Response.status(404)
                        .entity(Map.of("error", "Cloud account not found: " + id))
                        .build());
    }

    @PUT
    @Path("/{id}")
    @Operation(operationId = "upsertCloudAccount", summary = "Create or update a cloud account")
    @APIResponse(responseCode = "200", description = "Saved successfully")
    public Response upsertCloudAccount(
            @Parameter(required = true) @PathParam("id") String id,
            CloudAccount body) {
        if (body == null) {
            return Response.status(400).entity(Map.of("error", "Request body is required")).build();
        }
        if (body.name() == null || body.name().isBlank()) {
            return Response.status(400).entity(Map.of("error", "name is required")).build();
        }

        CloudAccountType type = body.type() != null ? body.type() : CloudAccountType.AWS;
        CloudAccount account = new CloudAccount(
                id,
                body.name(),
                body.description(),
                type,
                body.credentials(),
                body.createdAt(),
                body.updatedAt()
        );
        store.upsertCloudAccount(account);
        return store.getCloudAccount(id)
                .map(a -> Response.ok(a).build())
                .orElse(Response.ok().build());
    }

    @DELETE
    @Path("/{id}")
    @Operation(operationId = "deleteCloudAccount", summary = "Delete a cloud account")
    @APIResponse(responseCode = "204", description = "Deleted")
    @APIResponse(responseCode = "404", description = "Not found")
    public Response deleteCloudAccount(
            @Parameter(required = true) @PathParam("id") String id) {
        boolean deleted = store.deleteCloudAccount(id);
        return deleted
                ? Response.noContent().build()
                : Response.status(404).entity(Map.of("error", "Cloud account not found: " + id)).build();
    }
}
