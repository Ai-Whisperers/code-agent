package com.eneve.agent;

import java.util.List;
import java.util.Map;

import com.eneve.agent.agent.store.CustomerRegistryStore;
import com.eneve.agent.agent.store.KnowledgeEmbeddingStore;
import com.eneve.agent.agent.service.KnowledgeIndexerService;
import com.eneve.agent.agent.service.KnowledgeSearchService;

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
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/knowledge")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Knowledge Base", description = "Index and search Jira/Confluence knowledge")
public class KnowledgeResource {

    @Inject KnowledgeIndexerService indexer;
    @Inject KnowledgeSearchService searcher;
    @Inject KnowledgeEmbeddingStore store;
    @Inject CustomerRegistryStore registryStore;

    // ──────────────────────────────────────────────────────────────────────
    // Indexing endpoints
    // ──────────────────────────────────────────────────────────────────────

    @POST
    @Path("/index/jira")
    @Operation(
            operationId = "indexJiraProject",
            summary = "Index a Jira project",
            description = "Fetches all issues (including attachments and linked Confluence pages) "
                    + "for the given project key and stores embeddings. Runs synchronously.")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = IndexJiraRequest.class)))
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Indexing completed"),
            @APIResponse(responseCode = "400", description = "Missing required fields")
    })
    public Response indexJira(IndexJiraRequest request) {
        if (request == null || request.projectKey() == null || request.projectKey().isBlank()) {
            return Response.status(400).entity(Map.of("error", "projectKey is required")).build();
        }
        var result = indexer.indexJiraProject(
                request.projectKey(),
                request.productId(),
                request.customerId()
        );
        return Response.ok(result).build();
    }

    @POST
    @Path("/index/confluence")
    @Operation(
            operationId = "indexConfluenceSpace",
            summary = "Index a Confluence space",
            description = "Fetches all pages in the space, chunks them, and stores embeddings. "
                    + "Runs synchronously.")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = IndexConfluenceRequest.class)))
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Indexing completed"),
            @APIResponse(responseCode = "400", description = "Missing required fields")
    })
    public Response indexConfluence(IndexConfluenceRequest request) {
        if (request == null || request.spaceKey() == null || request.spaceKey().isBlank()) {
            return Response.status(400).entity(Map.of("error", "spaceKey is required")).build();
        }
        var result = indexer.indexConfluenceSpace(
                request.spaceKey(),
                request.productId(),
                request.customerId()
        );
        return Response.ok(result).build();
    }

    @POST
    @Path("/index/all")
    @Operation(
            operationId = "reindexAll",
            summary = "Reindex all products",
            description = "Iterates every product in the customer registry and indexes "
                    + "all configured Jira projects and Confluence spaces. May take several minutes.")
    @APIResponse(responseCode = "200", description = "Reindex completed")
    public Response reindexAll() {
        var results = indexer.reindexAll();
        int totalIndexed = results.stream().mapToInt(KnowledgeIndexerService.IndexResult::chunksIndexed).sum();
        int totalErrors = results.stream().mapToInt(r -> r.errors().size()).sum();
        return Response.ok(Map.of(
                "runs", results.size(),
                "totalChunksIndexed", totalIndexed,
                "totalErrors", totalErrors,
                "details", results
        )).build();
    }

    @POST
    @Path("/index/product/{productId}")
    @Operation(
            operationId = "indexProduct",
            summary = "Index a single product",
            description = "Indexes all Jira projects and Confluence spaces configured for the product.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Indexing completed"),
            @APIResponse(responseCode = "404", description = "Product not found")
    })
    public Response indexProduct(
            @Parameter(required = true) @jakarta.ws.rs.PathParam("productId") String productId) {
        return registryStore.getProduct(productId).map(product -> {
            var results = indexer.indexProduct(product);
            int totalIndexed = results.stream().mapToInt(KnowledgeIndexerService.IndexResult::chunksIndexed).sum();
            return Response.ok(Map.of(
                    "productId", productId,
                    "totalChunksIndexed", totalIndexed,
                    "details", results
            )).build();
        }).orElse(Response.status(404).entity(Map.of("error", "Product not found: " + productId)).build());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Search endpoint
    // ──────────────────────────────────────────────────────────────────────

    @GET
    @Path("/search")
    @Operation(
            operationId = "searchKnowledge",
            summary = "Semantic search the knowledge base",
            description = "Embeds the query and performs cosine-similarity search across "
                    + "indexed Jira issues, Confluence pages, and attachments.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Search results"),
            @APIResponse(responseCode = "400", description = "Missing query")
    })
    public Response search(
            @Parameter(required = true, description = "Natural-language search query")
            @QueryParam("q") String query,

            @Parameter(description = "Filter by source type(s): jira, confluence, jira-attachment (repeatable)")
            @QueryParam("sourceType") List<String> sourceTypes,

            @Parameter(description = "Filter to a specific product ID")
            @QueryParam("productId") String productId,

            @Parameter(description = "Maximum results (1–25, default 10)")
            @QueryParam("topK") @DefaultValue("10") int topK) {

        if (query == null || query.isBlank()) {
            return Response.status(400).entity(Map.of("error", "q (query) is required")).build();
        }

        var results = searcher.search(query, sourceTypes, productId, topK);
        return Response.ok(Map.of(
                "query", query,
                "count", results.size(),
                "results", results
        )).build();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Stats endpoint
    // ──────────────────────────────────────────────────────────────────────

    @GET
    @Path("/stats")
    @Operation(
            operationId = "knowledgeStats",
            summary = "Index statistics",
            description = "Returns chunk counts per source type, optionally scoped to a product.")
    @APIResponse(responseCode = "200", description = "Statistics")
    public Response stats(
            @Parameter(description = "Filter to a specific product ID")
            @QueryParam("productId") String productId) {

        return Response.ok(Map.of(
                "jira", store.countBySource("jira", productId),
                "confluence", store.countBySource("confluence", productId),
                "jiraAttachment", store.countBySource("jira-attachment", productId)
        )).build();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Request body records
    // ──────────────────────────────────────────────────────────────────────

    public record IndexJiraRequest(
            @Schema(required = true, description = "Jira project key", example = "ENG")
            String projectKey,
            @Schema(description = "Product ID for scoping", example = "myproduct-platform")
            String productId,
            @Schema(description = "Customer ID for scoping", example = "acme-corp")
            String customerId
    ) {}

    public record IndexConfluenceRequest(
            @Schema(required = true, description = "Confluence space key", example = "MYPRODUCT")
            String spaceKey,
            @Schema(description = "Product ID for scoping", example = "myproduct-platform")
            String productId,
            @Schema(description = "Customer ID for scoping", example = "acme-corp")
            String customerId
    ) {}
}
