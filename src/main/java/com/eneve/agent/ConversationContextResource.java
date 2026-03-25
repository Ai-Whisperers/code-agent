package com.eneve.agent;

import com.eneve.agent.agent.store.ConversationContextStore;
import com.eneve.agent.agent.service.ContextSelectionService;
import com.eneve.agent.model.ConversationContext;
import com.eneve.agent.model.ContextItem;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;

@Path("/conversation-context")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Conversation Context", description = "Manage conversation context including customers, products, issues, and documents")
public class ConversationContextResource {

    @Inject
    ConversationContextStore contextStore;
    
    @Inject
    ContextSelectionService selectionService;

    @GET
    @Path("/{conversationId}")
    @Operation(
        operationId = "getConversationContext",
        summary = "Get conversation context",
        description = "Retrieve the context (customers, products, issues, documents) associated with a conversation"
    )
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Context found", 
                   content = @Content(schema = @Schema(implementation = ConversationContext.class))),
        @APIResponse(responseCode = "404", description = "Context not found")
    })
    public Response getConversationContext(
            @Parameter(required = true, description = "Conversation ID") 
            @PathParam("conversationId") String conversationId) {
        return contextStore.getContext(conversationId)
                .map(context -> Response.ok(context).build())
                .orElse(Response.status(404)
                        .entity(Map.of("error", "Context not found for conversation: " + conversationId))
                        .build());
    }

    @PUT
    @Path("/{conversationId}")
    @Operation(
        operationId = "updateConversationContext", 
        summary = "Create or update conversation context",
        description = "Set the complete context for a conversation, replacing any existing context"
    )
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = UpdateContextRequest.class)))
    @APIResponse(responseCode = "200", description = "Context updated",
               content = @Content(schema = @Schema(implementation = ConversationContext.class)))
    public Response updateConversationContext(
            @Parameter(required = true, description = "Conversation ID")
            @PathParam("conversationId") String conversationId,
            UpdateContextRequest request) {
        
        ConversationContext context = contextStore.updateContext(
            conversationId, 
            request.customerIds(),
            request.productIds(),
            request.aikidoIssueIds(),
            request.jiraIssueKeys(),
            request.confluenceDocIds()
        );
        return Response.ok(context).build();
    }

    @DELETE
    @Path("/{conversationId}")
    @Operation(
        operationId = "deleteConversationContext",
        summary = "Delete conversation context", 
        description = "Remove all context associated with a conversation"
    )
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Context deleted"),
        @APIResponse(responseCode = "404", description = "Context not found")
    })
    public Response deleteConversationContext(
            @Parameter(required = true, description = "Conversation ID")
            @PathParam("conversationId") String conversationId) {
        
        if (contextStore.deleteContext(conversationId)) {
            return Response.ok(Map.of("deleted", conversationId)).build();
        }
        return Response.status(404)
                .entity(Map.of("error", "Context not found for conversation: " + conversationId))
                .build();
    }

    // Context selection endpoints

    @GET
    @Path("/selection/customers")
    @Operation(
        operationId = "getCustomersForContext",
        summary = "Get customers available for context selection",
        description = "Returns up to 5 customers for context attachment"
    )
    @APIResponse(responseCode = "200", description = "Available customers")
    public Response getCustomersForContext() {
        List<ContextItem.CustomerContextItem> customers = selectionService.getCustomersForContext(5);
        return Response.ok(Map.of("customers", customers)).build();
    }

    @GET
    @Path("/selection/products")
    @Operation(
        operationId = "getProductsForContext",
        summary = "Get products available for context selection",
        description = "Returns up to 10 products for context attachment"
    )
    @APIResponse(responseCode = "200", description = "Available products")
    public Response getProductsForContext() {
        List<ContextItem.ProductContextItem> products = selectionService.getProductsForContext(10);
        return Response.ok(Map.of("products", products)).build();
    }

    @GET
    @Path("/selection/aikido-issues")
    @Operation(
        operationId = "getAikidoIssuesForContext",
        summary = "Get Aikido issues available for context selection", 
        description = "Returns up to 10 Aikido security issues for context attachment"
    )
    @APIResponse(responseCode = "200", description = "Available Aikido issues")
    public Response getAikidoIssuesForContext(
            @Parameter(description = "Repository slug to filter issues")
            @QueryParam("repoSlug") String repoSlug) {
        List<ContextItem.AikidoIssueContextItem> issues = selectionService.getAikidoIssuesForContext(repoSlug, 10);
        return Response.ok(Map.of("aikidoIssues", issues)).build();
    }

    @GET
    @Path("/selection/jira-issues")
    @Operation(
        operationId = "getJiraIssuesForContext",
        summary = "Get Jira issues available for context selection",
        description = "Returns up to 25 Jira issues for context attachment"
    )
    @APIResponse(responseCode = "200", description = "Available Jira issues")
    public Response getJiraIssuesForContext(
            @Parameter(description = "Search query for Jira issues")
            @QueryParam("query") @DefaultValue("") String query,
            @Parameter(description = "Product ID to scope search")
            @QueryParam("productId") String productId) {
        List<ContextItem.JiraIssueContextItem> issues = selectionService.getJiraIssuesForContext(query, productId, 25);
        return Response.ok(Map.of("jiraIssues", issues)).build();
    }

    @GET
    @Path("/selection/confluence-docs")
    @Operation(
        operationId = "getConfluenceDocsForContext",
        summary = "Get Confluence documents available for context selection",
        description = "Returns up to 3 Confluence documents for context attachment"
    )
    @APIResponse(responseCode = "200", description = "Available Confluence documents")
    public Response getConfluenceDocsForContext(
            @Parameter(description = "Search query for Confluence documents")
            @QueryParam("query") @DefaultValue("") String query,
            @Parameter(description = "Product ID to scope search")
            @QueryParam("productId") String productId) {
        List<ContextItem.ConfluenceDocContextItem> docs = selectionService.getConfluenceDocsForContext(query, productId, 3);
        return Response.ok(Map.of("confluenceDocs", docs)).build();
    }

    public record UpdateContextRequest(
        @Schema(description = "Customer IDs to attach to conversation")
        List<String> customerIds,
        @Schema(description = "Product IDs to attach to conversation")  
        List<String> productIds,
        @Schema(description = "Aikido issue group IDs to attach to conversation")
        List<Integer> aikidoIssueIds,
        @Schema(description = "Jira issue keys to attach to conversation")
        List<String> jiraIssueKeys,
        @Schema(description = "Confluence document IDs to attach to conversation")
        List<String> confluenceDocIds
    ) {}
}
