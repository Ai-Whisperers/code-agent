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
import com.eneve.agent.planner.ExecutionPlan;
import com.eneve.agent.planner.PlanStore;
import com.eneve.agent.planner.PlannerService;
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
    private static final int CHAT_MAX_ITERATIONS = 100;
    private static final int AUTO_TITLE_MAX_LENGTH = 80;

    @Inject ClaudeToolUseLoop toolLoop;
    @Inject ConversationRepository conversationRepository;
    @Inject CustomerRegistryStore registryStore;
    @Inject PromptTemplateService promptTemplateService;
    @Inject PlannerService plannerService;
    @Inject PlanStore planStore;
    @Inject PlanFileManager planFileManager;

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
                    history = sanitizeHistory(
                            conversationRepository.loadMessages(conversationId, userId));
                } else {
                    // New conversation — generate a stable UUID and create the DB record
                    conversationId = "chat-" + UUID.randomUUID();
                    history = new ArrayList<>();
                    String title = request.message().length() > AUTO_TITLE_MAX_LENGTH
                            ? request.message().substring(0, AUTO_TITLE_MAX_LENGTH)
                            : request.message();
                    
                    LOG.infof("Creating new conversation: %s", conversationId);
                    conversationRepository.createConversation(
                            userId, conversationId, title, request.productId());
                    LOG.infof("Conversation created successfully: %s", conversationId);
                    
                    // Verify the conversation was created before proceeding
                    if (!conversationRepository.exists(conversationId, userId)) {
                        throw new RuntimeException("Failed to create conversation: " + conversationId);
                    }
                }

                int priorCount = history.size();

                // ── Create workspace context for tool access ───────────
                workspace = createChatWorkspace(conversationId, request.productId());
                workspace.setUserId(userId);

                // ── Run the streaming loop ─────────────────────────────
                String systemPrompt = buildSystemPrompt(request.productId());
                List<ToolUnion> tools = ToolDefinitions.chat();
                
                // Create final reference for lambda
                final WorkspaceContext finalWorkspace = workspace;

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
                            // Handle Done events specially for plan generation
                            if (event instanceof ChatEvent.Done) {
                                String userMessage = request.message().toLowerCase();
                                boolean shouldGeneratePlan = "plan".equals(request.mode()) || containsPlanTriggers(userMessage);
                                
                                if (shouldGeneratePlan) {
                                    // Generate plan first, then emit Done event
                                    checkAndGeneratePlan(request, conversationId, finalWorkspace, emitter, event);
                                    // checkAndGeneratePlan will emit the plan event, then Done, then complete
                                } else {
                                    // No plan needed, emit Done and complete normally
                                    emitter.emit(event);
                                    emitter.complete();
                                }
                            } else {
                                // For all other events, emit normally
                                emitter.emit(event);
                                
                                // Check for plan generation opportunity during text streaming (for early detection)
                                if (event instanceof ChatEvent.TextDelta) {
                                    // Log plan generation check but don't actually generate until Done
                                    String userMessage = request.message().toLowerCase();
                                    boolean shouldGeneratePlan = "plan".equals(request.mode()) || containsPlanTriggers(userMessage);
                                    LOG.infof("Plan generation check - mode: %s, shouldGenerate: %s, eventType: %s", 
                                        request.mode(), shouldGeneratePlan, event.getClass().getSimpleName());
                                }
                                
                                // Complete stream on error
                                if (event instanceof ChatEvent.Error) {
                                    emitter.complete();
                                }
                            }
                        }
                );

                // ── Persist new messages and update timestamp ──────────
                try {
                    LOG.infof("Persisting messages for conversation: %s", conversationId);
                    conversationRepository.appendMessages(conversationId, updatedHistory, priorCount);
                    conversationRepository.touch(conversationId);
                    LOG.infof("Messages persisted successfully for conversation: %s", conversationId);
                } catch (Exception e) {
                    LOG.errorf("Failed to persist messages for conversation %s: %s", conversationId, e.getMessage());
                    // Don't fail the whole chat if message persistence fails
                }

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
                    var repos = product.git().repos();
                    if (repos != null && !repos.isEmpty()) {
                        workspace.putMetadata("productRepos", String.join(",", repos));
                        // Always set first repo as default for consistent behavior
                        workspace.putMetadata("repoSlug", repos.get(0));
                    }
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
        } else {
            productContext = "No product is pre-selected. Call `lookup_customer_context` with **no parameters** "
                    + "to list all available products and identify which one the user is asking about.\n\n";
        }

        return promptTemplateService.resolve("chat-system", Map.of(
                "CUSTOMER_NAME", customerName,
                "PRODUCT_CONTEXT", productContext
        ));
    }

    // ──────────────────────────────────────────────────────────────────────
    // History sanitization
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Removes trailing incomplete tool exchanges from a conversation history.
     *
     * <p>An incomplete exchange occurs when a session was interrupted after Claude emitted an
     * assistant message containing {@code tool_use} blocks but before the corresponding
     * {@code tool_result} user message was appended (e.g. max-iterations hit, crash, redeploy).
     * Sending such history to the Anthropic API causes a 400 error.
     *
     * <p>The method scans forward and trims from the first assistant message that has
     * {@code tool_use} blocks but is not immediately followed by a user message that has
     * {@code tool_result} blocks.
     */
    private List<MessageParam> sanitizeHistory(List<MessageParam> history) {
        for (int i = 0; i < history.size(); i++) {
            MessageParam msg = history.get(i);
            if (msg.role() == MessageParam.Role.ASSISTANT && hasToolUseBlocks(msg)) {
                boolean nextIsToolResult = i + 1 < history.size()
                        && history.get(i + 1).role() == MessageParam.Role.USER
                        && hasToolResultBlocks(history.get(i + 1));
                if (!nextIsToolResult) {
                    int trimmed = history.size() - i;
                    LOG.warnf("Trimming %d dangling message(s) from conversation history "
                            + "(incomplete tool exchange at index %d)", trimmed, i);
                    return new ArrayList<>(history.subList(0, i));
                }
            }
        }
        return history;
    }

    private static boolean hasToolUseBlocks(MessageParam msg) {
        if (!msg.content().isBlockParams()) return false;
        return msg.content().asBlockParams().stream().anyMatch(b -> b.toolUse().isPresent());
    }

    private static boolean hasToolResultBlocks(MessageParam msg) {
        if (!msg.content().isBlockParams()) return false;
        return msg.content().asBlockParams().stream().anyMatch(b -> b.toolResult().isPresent());
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

        if (product.git() != null && product.git().repos() != null
                && !product.git().repos().isEmpty()) {
            sb.append("### Repositories\n");
            for (String repo : product.git().repos()) {
                sb.append("- `").append(repo).append("`\n");
            }
            sb.append("\nWhen using `query_code_graph`, pass the `repoSlug` parameter with one of the repository slugs listed above.\n\n");
        }

        return sb.toString();
    }

    /**
     * Checks if the conversation context indicates a need for plan generation and creates ExecutionPlan if appropriate.
     * This method analyzes chat content for plan-worthy requests like feature development, bug fixes, or code changes.
     */
    private void checkAndGeneratePlan(ChatRequest request, String conversationId, WorkspaceContext workspace, 
                                    io.smallrye.mutiny.subscription.MultiEmitter<? super ChatEvent> emitter, ChatEvent currentEvent) {
        try {
            // Check if user explicitly requested plan mode or message contains plan triggers
            String userMessage = request.message().toLowerCase();
            boolean shouldGeneratePlan = "plan".equals(request.mode()) || containsPlanTriggers(userMessage);
            
            LOG.infof("Plan generation check - mode: %s, shouldGenerate: %s, eventType: %s", 
                request.mode(), shouldGeneratePlan, currentEvent.getClass().getSimpleName());
            
            if (shouldGeneratePlan && currentEvent instanceof ChatEvent.Done) {
                LOG.infof("Checking for existing plan for conversation: %s", conversationId);
                
                // First check if a plan already exists for this conversation
                var existingPlans = planStore.findByConversationId(conversationId);
                ExecutionPlan plan = null;
                boolean isNewPlan = false;
                
                if (!existingPlans.isEmpty()) {
                    // Use the existing plan
                    plan = existingPlans.get(0);
                    LOG.infof("Found existing plan for conversation: %s", plan.planId());
                } else {
                    // Generate plan summary first for the PlanStart event
                    String planSummary = "Generated from chat: " + 
                        (request.message().length() > 100 
                            ? request.message().substring(0, 97) + "..." 
                            : request.message());
                    
                    // Emit plan_start event immediately to show loading indicator with plan name
                    LOG.infof("Emitting PlanStart event for conversation: %s with title: %s", conversationId, planSummary);
                    emitter.emit(new ChatEvent.PlanStart(conversationId, planSummary));
                    LOG.infof("PlanStart event emitted, beginning plan generation");
                            
                    LOG.infof("No existing plan found, creating new plan with summary: %s", planSummary);
                    plan = plannerService.generatePlan(
                        planSummary,
                        workspace.getMetadata("repoSlug"),
                        "main", // default target branch
                        "CHAT", // sourceType
                        conversationId // sourceRef
                    );
                    isNewPlan = true;
                    LOG.infof("Plan generation result: %s", plan != null ? plan.planId() : "null");
                }
                
                // Handle plan metadata and file creation
                if (plan != null) {
                    if (isNewPlan) {
                        LOG.infof("Processing new plan: %s", plan.planId());
                        
                        // First, save the plan to the database
                        LOG.infof("Saving new plan to database: %s", plan.planId());
                        planStore.create(plan);
                        LOG.infof("Plan saved to database successfully: %s", plan.planId());
                        
                        // Then update the metadata
                        planStore.updateConversationId(plan.planId(), conversationId);
                        
                        // Create the markdown content and physical file using PlanFileManager
                        String markdownContent = planFileManager.generatePlanMarkdown(plan, request.message());
                        planStore.updateMarkdownContent(plan.planId(), markdownContent);
                        
                        // Create physical .md file in plan workspace
                        String workspacePath = planFileManager.createPlanMarkdownFile(plan.planId(), markdownContent);
                        if (workspacePath != null) {
                            planStore.updateWorkspacePath(plan.planId(), workspacePath);
                        }
                        
                        LOG.infof("Emitting PlanCreated event for new plan: %s", plan.planId());
                        // Emit plan created event
                        emitter.emit(new ChatEvent.PlanCreated(
                            plan.planId(),
                            plan.title(),
                            plan.status()
                        ));
                        LOG.infof("PlanCreated event emitted successfully");
                    } else {
                        LOG.infof("Emitting PlanUpdated event for existing plan: %s", plan.planId());
                        // Emit plan updated event for existing plan
                        emitter.emit(new ChatEvent.PlanUpdated(
                            plan.planId(),
                            plan.title(),
                            plan.status()
                        ));
                        LOG.infof("PlanUpdated event emitted successfully");
                    }
                    
                    // Now emit the Done event and complete the stream
                    LOG.infof("Emitting Done event after plan event");
                    emitter.emit(currentEvent); // This is the Done event
                    emitter.complete();
                } else {
                    LOG.warnf("Plan generation returned null - no plan created");
                    // Complete the stream even if plan creation failed
                    emitter.complete();
                }
            } else if (currentEvent instanceof ChatEvent.Done && shouldGeneratePlan) {
                LOG.infof("Plan generation skipped - not a Done event");
                emitter.complete();
            }
        } catch (Exception e) {
            LOG.warnf("Failed to generate plan for conversation %s: %s", conversationId, e.getMessage());
        }
    }

    
    /**
     * Checks if the user message contains keywords that suggest plan generation is appropriate.
     */
    private boolean containsPlanTriggers(String message) {
        String[] triggers = {
            "implement", "create", "build", "develop", "add feature", "fix bug", 
            "refactor", "update", "change", "modify", "enhance", "improve"
        };
        
        for (String trigger : triggers) {
            if (message.contains(trigger)) {
                return true;
            }
        }
        
        return false;
    }

}
