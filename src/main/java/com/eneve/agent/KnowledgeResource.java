package com.eneve.agent;

import java.util.List;
import java.util.Map;

import com.eneve.agent.agent.store.CustomerRegistryStore;
import com.eneve.agent.agent.store.KnowledgeEmbeddingStore;
import com.eneve.agent.agent.store.WebDocSourceStore;
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
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
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
    @Inject WebDocSourceStore webDocSourceStore;

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
        var result = indexer.indexJiraProject(request.projectKey());
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
        var result = indexer.indexConfluenceSpace(request.spaceKey());
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

            @Parameter(description = "Filter by source type(s): jira, confluence, jira-attachment, web-docs (repeatable)")
            @QueryParam("sourceType") List<String> sourceTypes,

            @Parameter(description = "Maximum results (1–25, default 10)")
            @QueryParam("topK") @DefaultValue("10") int topK) {

        if (query == null || query.isBlank()) {
            return Response.status(400).entity(Map.of("error", "q (query) is required")).build();
        }

        var results = searcher.search(query, sourceTypes, topK);
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
    public Response stats() {

        return Response.ok(Map.of(
                "jira", store.countBySource("jira"),
                "confluence", store.countBySource("confluence"),
                "jiraAttachment", store.countBySource("jira-attachment"),
                "webDocs", store.countBySource("web-docs")
        )).build();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Web doc sources — management
    // ──────────────────────────────────────────────────────────────────────

    @POST
    @Path("/web-doc-sources")
    @Operation(operationId = "registerWebDocSource", summary = "Register a web documentation source",
               description = "Adds a new site to the crawler registry. Does not trigger crawling immediately.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Source registered"),
            @APIResponse(responseCode = "400", description = "Missing required fields")
    })
    public Response registerWebDocSource(WebDocSourceRequest request) {
        if (request == null || request.baseUrl() == null || request.baseUrl().isBlank()) {
            return Response.status(400).entity(Map.of("error", "baseUrl is required")).build();
        }
        if (request.name() == null || request.name().isBlank()) {
            return Response.status(400).entity(Map.of("error", "name is required")).build();
        }
        if (request.allowedPathPrefix() == null || request.allowedPathPrefix().isBlank()) {
            return Response.status(400).entity(Map.of("error", "allowedPathPrefix is required")).build();
        }
        int maxPages = request.maxPages() != null ? request.maxPages() : 500;
        int crawlDelayMs = request.crawlDelayMs() != null ? request.crawlDelayMs() : 500;
        return webDocSourceStore.insert(
                request.name(), request.baseUrl(), request.allowedPathPrefix(),
                maxPages, crawlDelayMs
        ).map(s -> Response.ok(s).build())
         .orElse(Response.status(409).entity(Map.of("error", "A source with this baseUrl already exists")).build());
    }

    @GET
    @Path("/web-doc-sources")
    @Operation(operationId = "listWebDocSources", summary = "List registered web documentation sources")
    @APIResponse(responseCode = "200", description = "List of sources")
    public Response listWebDocSources() {
        return Response.ok(webDocSourceStore.listAll()).build();
    }

    @DELETE
    @Path("/web-doc-sources/{id}")
    @Operation(operationId = "deleteWebDocSource", summary = "Remove a web documentation source",
               description = "Deletes the source configuration and all its indexed embeddings.")
    @APIResponses({
            @APIResponse(responseCode = "204", description = "Deleted"),
            @APIResponse(responseCode = "404", description = "Source not found")
    })
    public Response deleteWebDocSource(@Parameter(required = true) @PathParam("id") String id) {
        return webDocSourceStore.findById(id).map(source -> {
            store.deleteBySourceIdPrefix("web-docs", source.baseUrl());
            webDocSourceStore.delete(id);
            return Response.noContent().build();
        }).orElse(Response.status(404).entity(Map.of("error", "Web doc source not found: " + id)).build());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Web doc sources — crawl triggers
    // ──────────────────────────────────────────────────────────────────────

    @POST
    @Path("/index/web-docs/{id}")
    @Operation(operationId = "crawlWebDocSource", summary = "Trigger crawl for one web doc source",
               description = "Immediately crawls and re-indexes the specified source. Runs synchronously.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Crawl completed"),
            @APIResponse(responseCode = "404", description = "Source not found")
    })
    public Response crawlWebDocSource(@Parameter(required = true) @PathParam("id") String id) {
        return webDocSourceStore.findById(id).map(source -> {
            var result = indexer.indexWebDocSource(source);
            return Response.ok(result).build();
        }).orElse(Response.status(404).entity(Map.of("error", "Web doc source not found: " + id)).build());
    }

    @POST
    @Path("/index/web-docs")
    @Operation(operationId = "crawlAllWebDocSources", summary = "Crawl all web documentation sources",
               description = "Crawls and re-indexes every registered web doc source. May take several minutes.")
    @APIResponse(responseCode = "200", description = "Crawl results")
    public Response crawlAllWebDocSources() {
        var results = indexer.indexAllWebDocSources();
        int totalIndexed = results.stream().mapToInt(KnowledgeIndexerService.IndexResult::chunksIndexed).sum();
        int totalErrors  = results.stream().mapToInt(r -> r.errors().size()).sum();
        return Response.ok(Map.of(
                "runs", results.size(),
                "totalChunksIndexed", totalIndexed,
                "totalErrors", totalErrors,
                "details", results
        )).build();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Request body records
    // ──────────────────────────────────────────────────────────────────────

    public record IndexJiraRequest(
            @Schema(required = true, description = "Jira project key", example = "ENG")
            String projectKey
    ) {}

    public record IndexConfluenceRequest(
            @Schema(required = true, description = "Confluence space key", example = "MYPRODUCT")
            String spaceKey
    ) {}

    public record WebDocSourceRequest(
            @Schema(required = true, description = "Display name for the source", example = "Quarkus Guides")
            String name,
            @Schema(required = true, description = "Base URL of the documentation site", example = "https://quarkus.io/guides/")
            String baseUrl,
            @Schema(required = true, description = "Only follow links under this URL prefix", example = "https://quarkus.io/guides/")
            String allowedPathPrefix,
            @Schema(description = "Maximum number of pages to crawl (default 500)")
            Integer maxPages,
            @Schema(description = "Delay between HTTP requests in milliseconds (default 500)")
            Integer crawlDelayMs
    ) {}
}
