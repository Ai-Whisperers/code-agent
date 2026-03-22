package com.eneve.agent;

import java.util.List;
import java.util.Map;

import com.eneve.agent.agent.ConversationRepository;
import com.eneve.agent.attachment.AttachmentService;
import com.eneve.agent.model.ConversationSummary;
import com.eneve.agent.planner.PlanStore;

import io.quarkus.security.identity.SecurityIdentity;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * REST endpoints for managing a user's chat conversation history.
 *
 * <p>All operations are scoped to the authenticated user (Keycloak JWT {@code sub} claim).
 * A user can only see and modify their own conversations.
 */
@Path("/conversations")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Conversations", description = "Manage saved chat conversation history")
public class ConversationsResource {

    private static final Logger LOG = Logger.getLogger(ConversationsResource.class);

    @Inject
    ConversationRepository conversationRepository;

    @Inject
    AttachmentService attachmentService;

    @Inject
    PlanStore planStore;

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    JsonWebToken jwt;

    // ──────────────────────────────────────────────────────────────────────
    // Endpoints
    // ──────────────────────────────────────────────────────────────────────

    @GET
    @Operation(
            operationId = "listConversations",
            summary = "List all conversations for the current user",
            description = "Returns conversation summaries sorted by most recently updated first. "
                    + "Use the returned `conversationId` in a `POST /chat` request to resume a conversation."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "List of conversation summaries"),
            @APIResponse(responseCode = "401", description = "Not authenticated")
    })
    public List<ConversationSummary> listConversations() {
        String userId = resolveUserId();
        LOG.debugf("GET /conversations — userId=%s", userId);
        return conversationRepository.listConversations(userId);
    }

    @DELETE
    @Path("/{conversationId}")
    @Operation(
            operationId = "deleteConversation",
            summary = "Delete a conversation and all its messages",
            description = "Permanently removes the conversation and all associated messages. "
                    + "Only the owning user can delete a conversation."
    )
    @APIResponses({
            @APIResponse(responseCode = "204", description = "Conversation deleted"),
            @APIResponse(responseCode = "401", description = "Not authenticated"),
            @APIResponse(responseCode = "404", description = "Conversation not found or not owned by user")
    })
    public Response deleteConversation(
            @Parameter(description = "Conversation ID to delete", required = true)
            @PathParam("conversationId") String conversationId) {
        String userId = resolveUserId();
        if (!conversationRepository.exists(conversationId, userId)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Conversation not found"))
                    .build();
        }
        attachmentService.getAttachmentsByConversation(conversationId)
                .forEach(att -> attachmentService.deleteAttachment(att.attachmentId()));
        planStore.findByConversationId(conversationId)
                .forEach(plan -> planStore.delete(plan.planId()));
        conversationRepository.deleteConversation(conversationId, userId);
        LOG.debugf("DELETE /conversations/%s — userId=%s", conversationId, userId);
        return Response.noContent().build();
    }

    @PATCH
    @Path("/{conversationId}/title")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            operationId = "renameConversation",
            summary = "Rename a conversation",
            description = "Updates the display title of a conversation. "
                    + "Only the owning user can rename a conversation."
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Title updated"),
            @APIResponse(responseCode = "400", description = "Missing or blank title"),
            @APIResponse(responseCode = "401", description = "Not authenticated"),
            @APIResponse(responseCode = "404", description = "Conversation not found or not owned by user")
    })
    public Response renameConversation(
            @Parameter(description = "Conversation ID to rename", required = true)
            @PathParam("conversationId") String conversationId,
            Map<String, String> body) {
        String title = body == null ? null : body.get("title");
        if (title == null || title.isBlank()) {
            throw new WebApplicationException(
                    Response.status(400)
                            .entity(Map.of("error", "title is required"))
                            .build());
        }
        String userId = resolveUserId();
        boolean updated = conversationRepository.renameConversation(conversationId, userId, title.strip());
        if (!updated) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Conversation not found"))
                    .build();
        }
        LOG.debugf("PATCH /conversations/%s/title — userId=%s title=%s",
                conversationId, userId, title);
        return Response.ok(Map.of("conversationId", conversationId, "title", title.strip())).build();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────

    private String resolveUserId() {
        if (securityIdentity.isAnonymous()) {
            throw new WebApplicationException(Response.Status.UNAUTHORIZED);
        }
        // Use the stable 'sub' claim (UUID assigned by Keycloak) rather than
        // preferred_username which may change on user rename.
        try {
            String sub = jwt.getClaim("sub");
            if (sub != null && !sub.isBlank()) {
                return sub;
            }
        } catch (Exception ignored) {
            // fall through to principal name
        }
        return securityIdentity.getPrincipal().getName();
    }
}
