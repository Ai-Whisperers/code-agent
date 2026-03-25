package com.eneve.agent;

import java.util.Map;

import com.eneve.agent.agent.model.ChatEvent;
import com.eneve.agent.agent.service.ChatService;
import com.eneve.agent.model.ChatRequest;
import com.eneve.agent.security.AppPermission;
import com.eneve.agent.security.PermissionService;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Multi;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestStreamElementType;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Freeform AI chat endpoint backed by a streaming Claude tool-use loop.
 *
 * <p>The response is a Server-Sent Events (SSE) stream of JSON objects.
 * Each event carries a {@code type} field: {@code text}, {@code tool_start},
 * {@code tool_end}, {@code done}, or {@code error}.
 *
 * <p>Example SSE events:
 * <pre>
 * data: {"type":"text","text":"Based on the Jira history, "}
 * data: {"type":"tool_start","tool":"search_knowledge_base"}
 * data: {"type":"tool_end","tool":"search_knowledge_base"}
 * data: {"type":"text","text":"Here is what I found…"}
 * data: {"type":"done","conversationId":"chat-abc123"}
 * </pre>
 */
@RequestScoped
@Path("/chat")
@Authenticated
@Tag(name = "Chat", description = "Freeform AI chat with knowledge base and code search access")
public class ChatResource {

    @Inject
    ChatService chatService;

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    JsonWebToken jwt;

    @Inject
    PermissionService permissionService;

    @POST
    @Blocking
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    @Operation(
            operationId = "chat",
            summary = "Chat with the AI assistant",
            description = "Sends a freeform question to the AI and receives a real-time SSE stream. "
                    + "Claude will search the knowledge base, look up customer context, and search source code "
                    + "as needed before generating its response. "
                    + "Responses are formatted in Markdown and may include Mermaid diagrams. "
                    + "Each SSE data frame is a JSON object with a 'type' field: "
                    + "'text' (Markdown delta), 'tool_start', 'tool_end', 'done', or 'error'."
    )
    @RequestBody(
            required = true,
            content = @Content(schema = @Schema(implementation = ChatRequest.class))
    )
    @APIResponses({
            @APIResponse(responseCode = "200", description = "SSE stream of ChatEvent JSON objects"),
            @APIResponse(responseCode = "400", description = "Missing or blank message")
    })
    public Multi<ChatEvent> chat(ChatRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            throw new WebApplicationException(
                    Response.status(400)
                            .entity(Map.of("error", "message is required"))
                            .build()
            );
        }
        boolean canExecuteJobs = permissionService.getPermissions().contains(AppPermission.EXECUTE_FIX_JOBS);
        return chatService.chatStream(request, resolveUserId(), canExecuteJobs);
    }

    private String resolveUserId() {
        if (securityIdentity.isAnonymous()) {
            // OIDC disabled (dev mode / API-key-only setup) — fall back to anonymous user
            return "anonymous";
        }
        try {
            // Use the stable 'sub' claim (UUID assigned by Keycloak) rather than
            // preferred_username which may change on user rename.
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
