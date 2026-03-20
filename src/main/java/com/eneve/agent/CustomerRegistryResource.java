package com.eneve.agent;

import java.util.List;
import java.util.Map;

import com.eneve.agent.agent.CustomerRegistryStore;
import com.eneve.agent.model.ConfluenceProductConfig;
import com.eneve.agent.model.CustomerConfig;
import com.eneve.agent.model.EnvironmentConfig;
import com.eneve.agent.model.GitConfig;
import com.eneve.agent.model.JiraProjectConfig;
import com.eneve.agent.model.ProductConfig;
import com.eneve.agent.model.TeamMember;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

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

@Path("/customer-registry")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Customer Registry", description = "Manage customers, products, teams, and environments")
public class CustomerRegistryResource {

    @Inject
    CustomerRegistryStore store;

    // ──────────────────────────────────────────────────────────────────────
    // Customers
    // ──────────────────────────────────────────────────────────────────────

    @GET
    @Path("/customers")
    @Operation(operationId = "listCustomers", summary = "List all customers")
    @APIResponse(responseCode = "200", description = "List of customers")
    public Response listCustomers() {
        return Response.ok(store.listCustomers()).build();
    }

    @GET
    @Path("/customers/{customerId}")
    @Operation(operationId = "getCustomer", summary = "Get a customer by ID")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Customer found"),
            @APIResponse(responseCode = "404", description = "Customer not found")
    })
    public Response getCustomer(
            @Parameter(required = true) @PathParam("customerId") String customerId) {
        return store.getCustomer(customerId)
                .map(c -> Response.ok(c).build())
                .orElse(Response.status(404).entity(Map.of("error", "Customer not found: " + customerId)).build());
    }

    @PUT
    @Path("/customers/{customerId}")
    @Operation(operationId = "upsertCustomer", summary = "Create or update a customer")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = UpsertCustomerRequest.class)))
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Customer saved"),
            @APIResponse(responseCode = "400", description = "Invalid request")
    })
    public Response upsertCustomer(
            @Parameter(required = true) @PathParam("customerId") String customerId,
            UpsertCustomerRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            return Response.status(400).entity(Map.of("error", "name is required")).build();
        }
        CustomerConfig config = new CustomerConfig(customerId, request.name(), request.metadata(), null, null);
        store.upsertCustomer(config);
        return store.getCustomer(customerId)
                .map(c -> Response.ok(c).build())
                .orElse(Response.ok(Map.of("customerId", customerId)).build());
    }

    @DELETE
    @Path("/customers/{customerId}")
    @Operation(operationId = "deleteCustomer", summary = "Delete a customer and all its products")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Customer deleted"),
            @APIResponse(responseCode = "404", description = "Customer not found")
    })
    public Response deleteCustomer(
            @Parameter(required = true) @PathParam("customerId") String customerId) {
        if (store.deleteCustomer(customerId)) {
            return Response.ok(Map.of("deleted", customerId)).build();
        }
        return Response.status(404).entity(Map.of("error", "Customer not found: " + customerId)).build();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Products
    // ──────────────────────────────────────────────────────────────────────

    @GET
    @Path("/customers/{customerId}/products")
    @Operation(operationId = "listProducts", summary = "List all products for a customer")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "List of products"),
            @APIResponse(responseCode = "404", description = "Customer not found")
    })
    public Response listProducts(
            @Parameter(required = true) @PathParam("customerId") String customerId) {
        if (store.getCustomer(customerId).isEmpty()) {
            return Response.status(404).entity(Map.of("error", "Customer not found: " + customerId)).build();
        }
        return Response.ok(store.listProducts(customerId)).build();
    }

    @GET
    @Path("/products/{productId}")
    @Operation(operationId = "getProduct", summary = "Get a product by ID")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Product found"),
            @APIResponse(responseCode = "404", description = "Product not found")
    })
    public Response getProduct(
            @Parameter(required = true) @PathParam("productId") String productId) {
        return store.getProduct(productId)
                .map(p -> Response.ok(p).build())
                .orElse(Response.status(404).entity(Map.of("error", "Product not found: " + productId)).build());
    }

    @PUT
    @Path("/products/{productId}")
    @Operation(operationId = "upsertProduct", summary = "Create or update a product")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = UpsertProductRequest.class)))
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Product saved"),
            @APIResponse(responseCode = "400", description = "Invalid request")
    })
    public Response upsertProduct(
            @Parameter(required = true) @PathParam("productId") String productId,
            UpsertProductRequest request) {
        if (request == null || request.customerId() == null || request.customerId().isBlank()) {
            return Response.status(400).entity(Map.of("error", "customerId is required")).build();
        }
        if (request.displayName() == null || request.displayName().isBlank()) {
            return Response.status(400).entity(Map.of("error", "displayName is required")).build();
        }
        ProductConfig config = new ProductConfig(
                productId, request.customerId(), request.displayName(),
                request.git(), request.jira(), request.confluence(),
                request.environments(), request.teams(), request.metadata(),
                null, null
        );
        store.upsertProduct(config);
        return store.getProduct(productId)
                .map(p -> Response.ok(p).build())
                .orElse(Response.ok(Map.of("productId", productId)).build());
    }

    @DELETE
    @Path("/products/{productId}")
    @Operation(operationId = "deleteProduct", summary = "Delete a product")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Product deleted"),
            @APIResponse(responseCode = "404", description = "Product not found")
    })
    public Response deleteProduct(
            @Parameter(required = true) @PathParam("productId") String productId) {
        if (store.deleteProduct(productId)) {
            return Response.ok(Map.of("deleted", productId)).build();
        }
        return Response.status(404).entity(Map.of("error", "Product not found: " + productId)).build();
    }

    @PUT
    @Path("/products/{productId}/teams")
    @Operation(operationId = "updateProductTeams", summary = "Update the teams configuration for a product")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = UpdateTeamsRequest.class)))
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Teams updated"),
            @APIResponse(responseCode = "404", description = "Product not found")
    })
    public Response updateTeams(
            @Parameter(required = true) @PathParam("productId") String productId,
            UpdateTeamsRequest request) {
        return store.getProduct(productId).map(existing -> {
            ProductConfig updated = new ProductConfig(
                    existing.productId(), existing.customerId(), existing.displayName(),
                    existing.git(), existing.jira(), existing.confluence(),
                    existing.environments(),
                    request.teams(),
                    existing.metadata(),
                    null, null
            );
            store.upsertProduct(updated);
            return Response.ok(store.getProduct(productId).orElse(updated)).build();
        }).orElse(Response.status(404).entity(Map.of("error", "Product not found: " + productId)).build());
    }

    @PUT
    @Path("/products/{productId}/environments")
    @Operation(operationId = "updateProductEnvironments", summary = "Update the environments for a product")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = UpdateEnvironmentsRequest.class)))
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Environments updated"),
            @APIResponse(responseCode = "404", description = "Product not found")
    })
    public Response updateEnvironments(
            @Parameter(required = true) @PathParam("productId") String productId,
            UpdateEnvironmentsRequest request) {
        return store.getProduct(productId).map(existing -> {
            ProductConfig updated = new ProductConfig(
                    existing.productId(), existing.customerId(), existing.displayName(),
                    existing.git(), existing.jira(), existing.confluence(),
                    request.environments(),
                    existing.teams(),
                    existing.metadata(),
                    null, null
            );
            store.upsertProduct(updated);
            return Response.ok(store.getProduct(productId).orElse(updated)).build();
        }).orElse(Response.status(404).entity(Map.of("error", "Product not found: " + productId)).build());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Request body records
    // ──────────────────────────────────────────────────────────────────────

    public record UpsertCustomerRequest(
            @Schema(required = true) String name,
            Map<String, Object> metadata
    ) {}

    public record UpsertProductRequest(
            @Schema(required = true) String customerId,
            @Schema(required = true) String displayName,
            GitConfig git,
            JiraProjectConfig jira,
            ConfluenceProductConfig confluence,
            List<EnvironmentConfig> environments,
            Map<String, List<TeamMember>> teams,
            Map<String, Object> metadata
    ) {}

    public record UpdateTeamsRequest(
            @Schema(required = true) Map<String, List<TeamMember>> teams
    ) {}

    public record UpdateEnvironmentsRequest(
            @Schema(required = true) List<EnvironmentConfig> environments
    ) {}
}
