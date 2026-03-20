package com.eneve.agent.agent;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolUnion;
import com.eneve.agent.model.ChatRequest;
import com.eneve.agent.model.EnvironmentConfig;
import com.eneve.agent.model.ProductConfig;
import com.eneve.agent.model.TeamMember;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Orchestrates a streaming chat interaction with Claude.
 *
 * <p>The service:
 * <ol>
 *   <li>Builds a system prompt from the {@code chat-system} template, injecting optional
 *       product context (teams, environments, Jira projects).</li>
 *   <li>Selects read-only Claude tools suitable for Q&amp;A (knowledge search, customer lookup,
 *       code search, fetch URL).</li>
 *   <li>Runs the streaming agent loop via {@link ClaudeToolUseLoop#runStreaming}.</li>
 *   <li>Returns a {@link Multi} of {@link ChatEvent} items that the REST layer converts to SSE.</li>
 * </ol>
 */
@ApplicationScoped
public class ChatService {

    private static final Logger LOG = Logger.getLogger(ChatService.class);
    private static final int CHAT_MAX_ITERATIONS = 20;

    @Inject ClaudeToolUseLoop toolLoop;
    @Inject ConversationStore conversationStore;
    @Inject CustomerRegistryStore registryStore;
    @Inject PromptTemplateService promptTemplateService;

    /**
     * Start a streaming chat conversation.
     *
     * @param request the incoming chat request
     * @return a stream of {@link ChatEvent} items (text deltas, tool events, done/error)
     */
    public Multi<ChatEvent> chatStream(ChatRequest request) {
        return Multi.createFrom().<ChatEvent>emitter(emitter -> {
            try {
                String conversationId = request.conversationId() != null
                        ? request.conversationId()
                        : "chat-" + UUID.randomUUID();

                List<MessageParam> history = conversationStore.get(conversationId);
                String systemPrompt = buildSystemPrompt(request.productId());
                List<ToolUnion> tools = ToolDefinitions.chat();

                List<MessageParam> updatedHistory = toolLoop.runStreaming(
                        systemPrompt,
                        null,
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
                conversationStore.save(conversationId, updatedHistory);
                // If the loop returned without emitting Done (shouldn't happen), complete anyway
                emitter.complete();
            } catch (Exception e) {
                LOG.errorf("ChatService error: %s", e.getMessage());
                emitter.emit(new ChatEvent.Error(e.getMessage() != null ? e.getMessage() : "Internal error"));
                emitter.complete();
            }
        });
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
                // Try to get the customer name
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

        // Teams
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

        // Environments
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

        // Jira
        if (product.jira() != null && product.jira().projects() != null
                && !product.jira().projects().isEmpty()) {
            sb.append("### Jira Projects\n");
            product.jira().projects().forEach((role, key) ->
                    sb.append("- ").append(role).append(": `").append(key).append("`\n"));
            sb.append("\n");
        }

        // Confluence
        if (product.confluence() != null && product.confluence().spaceKey() != null) {
            sb.append("### Confluence Space: `")
              .append(product.confluence().spaceKey()).append("`\n\n");
        }

        return sb.toString();
    }
}
