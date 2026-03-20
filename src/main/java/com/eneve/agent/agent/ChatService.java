package com.eneve.agent.agent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolUnion;
import com.eneve.agent.model.ChatRequest;
import com.eneve.agent.model.EnvironmentConfig;
import com.eneve.agent.model.ProductConfig;
import com.eneve.agent.model.TeamMember;
import com.eneve.agent.workspace.WorkspaceContext;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Orchestrates a streaming chat interaction with Claude.
 *
 * <p>The service:
 * <ol>
 *   <li>Loads prior conversation history from {@link ConversationRepository} (if resuming).</li>
 *   <li>Builds a system prompt from the {@code chat-system} template, injecting optional
 *       product context (teams, environments, Jira projects).</li>
 *   <li>Selects read-only Claude tools suitable for Q&amp;A (knowledge search, customer lookup,
 *       code search, fetch URL).</li>
 *   <li>Runs the streaming agent loop via {@link ClaudeToolUseLoop#runStreaming}.</li>
 *   <li>Persists newly added messages back to the database.</li>
 *   <li>Returns a {@link Multi} of {@link ChatEvent} items that the REST layer converts to SSE.</li>
 * </ol>
 */
@ApplicationScoped
public class ChatService {

    private static final Logger LOG = Logger.getLogger(ChatService.class);
    private static final int CHAT_MAX_ITERATIONS = 20;
    private static final int AUTO_TITLE_MAX_LENGTH = 80;

    @Inject ClaudeToolUseLoop toolLoop;
    @Inject ConversationRepository conversationRepository;
    @Inject CustomerRegistryStore registryStore;
    @Inject PromptTemplateService promptTemplateService;

    /**
     * Start or resume a streaming chat conversation.
     *
     * <p>If {@code request.conversationId()} is provided and owned by {@code userId} the prior
     * message history is loaded and prepended to the new turn. Otherwise a new conversation is
     * created and its ID is returned in the terminal {@link ChatEvent.Done} event.
     *
     * @param request incoming chat request
     * @param userId  stable user identifier (Keycloak JWT {@code sub} claim)
     * @return a stream of {@link ChatEvent} items (text deltas, tool events, done/error)
     */
    public Multi<ChatEvent> chatStream(ChatRequest request, String userId) {
        return Multi.createFrom().<ChatEvent>emitter(emitter -> {
            WorkspaceContext workspace = null;
            try {
                // ── Resolve conversation ───────────────────────────────
                String conversationId;
                List<MessageParam> history;

                String requestedId = request.conversationId();
                if (requestedId != null && conversationRepository.exists(requestedId, userId)) {
                    // Resume existing conversation
                    conversationId = requestedId;
                    history = conversationRepository.loadMessages(conversationId, userId);
                } else {
                    // New conversation — generate a stable UUID and create the DB record
                    conversationId = "chat-" + UUID.randomUUID();
                    history = new ArrayList<>();
                    String title = request.message().length() > AUTO_TITLE_MAX_LENGTH
                            ? request.message().substring(0, AUTO_TITLE_MAX_LENGTH)
                            : request.message();
                    conversationRepository.createConversation(
                            userId, conversationId, title, request.productId());
                }

                int priorCount = history.size();

                // ── Create workspace context for tool access ───────────
                workspace = createChatWorkspace(conversationId, request.productId());

                // ── Run the streaming loop ─────────────────────────────
                String systemPrompt = buildSystemPrompt(request.productId());
                List<ToolUnion> tools = ToolDefinitions.chat();

                List<MessageParam> updatedHistory = toolLoop.runStreaming(
                        systemPrompt,
                        workspace,
                        tools,
                        request.message(),
                        history,
                        conversationId,
                        "CHAT",
                        CHAT_MAX_ITERATIONS,
                        event -> {
                            emitter.emit(event);
                            if (event instanceof ChatEvent.Done || event instanceof ChatEvent.Error) {
                                emitter.complete();
                            }
                        }
                );

                // ── Persist new messages and update timestamp ──────────
                conversationRepository.appendMessages(conversationId, updatedHistory, priorCount);
                conversationRepository.touch(conversationId);

                // If the loop returned without emitting Done (shouldn't happen), complete anyway
                emitter.complete();

            } catch (Exception e) {
                LOG.errorf("ChatService error: %s", e.getMessage());
                emitter.emit(new ChatEvent.Error(e.getMessage() != null ? e.getMessage() : "Internal error"));
                emitter.complete();
            } finally {
                if (workspace != null) {
                    workspace.close();
                }
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    /**
     * Creates a workspace context for a chat session.
     *
     * <p>The workspace directory is ephemeral (no repo is cloned into it), but the metadata
     * it carries allows read-only tools like {@code semantic_search} and {@code query_code_graph}
     * to scope their queries to the correct git workspace/organisation.
     */
    private WorkspaceContext createChatWorkspace(String conversationId, String productId) {
        try {
            WorkspaceContext workspace = WorkspaceContext.create(conversationId);
            if (productId != null && !productId.isBlank()) {
                var product = registryStore.getProduct(productId).orElse(null);
                if (product != null && product.git() != null
                        && product.git().workspace() != null) {
                    workspace.putMetadata("workspace", product.git().workspace());
                }
            }
            return workspace;
        } catch (IOException e) {
            throw new RuntimeException("Failed to create chat workspace: " + e.getMessage(), e);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // System prompt construction
    // ──────────────────────────────────────────────────────────────────────

    private String buildSystemPrompt(String productId) {
        String customerName = "Engineering";
        String productContext = "";

        if (productId != null && !productId.isBlank()) {
            var product = registryStore.getProduct(productId).orElse(null);
            if (product != null) {
                var customer = registryStore.getCustomer(product.customerId()).orElse(null);
                if (customer != null) {
                    customerName = customer.name();
                }
                productContext = buildProductContext(product);
            }
        }

        return promptTemplateService.resolve("chat-system", Map.of(
                "CUSTOMER_NAME", customerName,
                "PRODUCT_CONTEXT", productContext
        ));
    }

    private String buildProductContext(ProductConfig product) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Active Product Context\n\n");
        sb.append("You are assisting with **").append(product.displayName())
          .append("** (product ID: `").append(product.productId()).append("`).\n\n");

        if (product.teams() != null && !product.teams().isEmpty()) {
            sb.append("### Team\n");
            for (Map.Entry<String, List<TeamMember>> entry : product.teams().entrySet()) {
                sb.append("**").append(entry.getKey()).append("**: ");
                sb.append(entry.getValue().stream()
                        .map(m -> m.name() + (m.email() != null ? " <" + m.email() + ">" : ""))
                        .reduce((a, b) -> a + ", " + b).orElse("(none)"));
                sb.append("\n");
            }
            sb.append("\n");
        }

        if (product.environments() != null && !product.environments().isEmpty()) {
            sb.append("### Environments\n");
            for (EnvironmentConfig env : product.environments()) {
                sb.append("- **").append(env.name()).append("**");
                if (env.aws() != null) {
                    sb.append(": AWS account `").append(env.aws().accountId()).append("`");
                    if (env.aws().region() != null) {
                        sb.append(", region `").append(env.aws().region()).append("`");
                    }
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        if (product.jira() != null && product.jira().projects() != null
                && !product.jira().projects().isEmpty()) {
            sb.append("### Jira Projects\n");
            product.jira().projects().forEach((role, key) ->
                    sb.append("- ").append(role).append(": `").append(key).append("`\n"));
            sb.append("\n");
        }

        if (product.confluence() != null && product.confluence().spaceKey() != null) {
            sb.append("### Confluence Space: `")
              .append(product.confluence().spaceKey()).append("`\n\n");
        }

        return sb.toString();
    }
}
