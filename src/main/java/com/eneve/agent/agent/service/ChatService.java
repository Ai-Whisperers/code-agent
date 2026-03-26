package com.eneve.agent.agent.service;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import com.eneve.agent.agent.ClaudeToolUseLoop;
import com.eneve.agent.agent.PlanFileManager;
import com.eneve.agent.agent.ToolDefinitions;
import com.eneve.agent.agent.model.ChatEvent;
import com.eneve.agent.agent.store.ConversationContextStore;
import com.eneve.agent.agent.store.ConversationRepository;
import com.eneve.agent.agent.store.CustomerRegistryStore;
import com.eneve.agent.attachment.AttachmentService;
import com.eneve.agent.attachment.ChatAttachment;
import com.eneve.agent.model.ChatRequest;
import com.eneve.agent.model.ConversationContext;
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

import java.io.IOException;
import java.util.*;

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
    @Inject ConversationContextStore conversationContextStore;
    @Inject CustomerRegistryStore registryStore;
    @Inject PromptTemplateService promptTemplateService;
    @Inject PlannerService plannerService;
    @Inject PlanStore planStore;
    @Inject PlanFileManager planFileManager;
    @Inject AttachmentService attachmentService;
    @Inject ContextEnrichmentService contextEnrichmentService;

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
    public Multi<ChatEvent> chatStream(ChatRequest request, String userId, boolean canExecuteJobs) {
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
                workspace.setConversationId(conversationId);

                // ── Hydrate workspace from persisted context (resume) ──
                hydrateWorkspaceFromStoredContext(conversationId, workspace);

                // ── Build message content blocks (text + attachments) ────
                List<ContentBlockParam> userContentBlocks = buildMessageContentWithAttachments(
                        request.message(), request.attachmentIds());

                // ── Run the streaming loop ─────────────────────────────
                ConversationContext storedContext = conversationContextStore.getContext(conversationId).orElse(null);
                ConversationContext effectiveContext = mergeContexts(storedContext, request.conversationContext());
                boolean hasCustomer = workspace.getMetadata("customerId") != null
                        || (request.productId() != null && !request.productId().isBlank())
                        || (effectiveContext != null
                            && effectiveContext.customerIds() != null
                            && !effectiveContext.customerIds().isEmpty());
                String systemPrompt = buildSystemPrompt(
                        request.productId(),
                        effectiveContext,
                        userId,
                        hasCustomer);
                List<ToolUnion> tools = ToolDefinitions.chat(canExecuteJobs, hasCustomer);
                
                // Create final reference for lambda
                final WorkspaceContext finalWorkspace = workspace;
                final String finalConversationId = conversationId;

                List<MessageParam> updatedHistory = toolLoop.runStreaming(
                        systemPrompt,
                        workspace,
                        tools,
                        userContentBlocks,
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
                    LOG.infof("Persisting messages for conversation: %s", finalConversationId);
                    conversationRepository.appendMessages(finalConversationId, updatedHistory, priorCount);
                    conversationRepository.touch(finalConversationId);
                    LOG.infof("Messages persisted successfully for conversation: %s", finalConversationId);
                    
                    // Link attachments to the message after persisting
                    if (request.attachmentIds() != null && !request.attachmentIds().isEmpty()) {
                        // The last message should be the user message we just added
                        int lastIndex = updatedHistory.size() - 1;
                        if (lastIndex >= 0) {
                            MessageParam lastMessage = updatedHistory.get(lastIndex);
                            // We can't directly get the message ID from MessageParam,
                            // so we need to get it from the database
                            linkAttachmentsToLastMessage(finalConversationId, request.attachmentIds());
                        }
                    }
                } catch (Exception e) {
                    LOG.errorf("Failed to persist messages for conversation %s: %s", finalConversationId, e.getMessage());
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

    private String buildSystemPrompt(String productId, ConversationContext conversationContext,
                                     String userId, boolean hasCustomer) {
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
        } else if (!hasCustomer) {
            productContext = "No customer or product is pre-selected. "
                    + "If the user mentions a customer by name, call `lookup_customer_context` with `customerName` "
                    + "to resolve their environments, AWS accounts, and linked products. "
                    + "If no customer is clear from the question, call `lookup_customer_context` with **no parameters** "
                    + "to list all registered customers and their environments.\n\n";
        }

        // Enrich conversation context if present
        String enrichedContext = "";
        if (conversationContext != null) {
            try {
                enrichedContext = contextEnrichmentService.enrichContext(conversationContext, userId);
                if (!enrichedContext.isEmpty()) {
                    LOG.infof("Enriched conversation context with %d characters of detail", enrichedContext.length());
                }
            } catch (Exception e) {
                LOG.warnf("Failed to enrich conversation context: %s", e.getMessage());
            }
        }

        String customerToolsSection;
        String awsToolsSection;
        if (hasCustomer) {
            customerToolsSection = "**Customer context:** Already resolved. Use the `customerId` "
                    + "and `environmentName` values from the active context when calling AWS or code tools.\n";
            awsToolsSection = "**AWS infrastructure:** Use the resolved `customerId` and `environmentName` with:\n"
                    + "- `aws_ecs` \u2014 ECS clusters, services, and task status\n"
                    + "- `aws_cloudwatch_metrics` \u2014 CloudWatch metrics and alarms\n"
                    + "- `aws_cloudwatch_logs` \u2014 CloudWatch log groups and log events\n"
                    + "- `aws_rds` \u2014 RDS instances and cluster health\n";
        } else {
            customerToolsSection = "**Discovering context:**\n"
                    + "- When the user mentions a customer by name, call `lookup_customer_context` with `customerName` "
                    + "to resolve environments, AWS accounts, and linked products.\n"
                    + "- When no customer is clear, call `lookup_customer_context` with **no parameters** "
                    + "to list all registered customers.\n"
                    + "- Use the returned repo slugs for code tool calls.\n";
            awsToolsSection = "";
        }

        Map<String, String> templateVars = Map.of(
                "CUSTOMER_NAME", customerName,
                "PRODUCT_CONTEXT", productContext,
                "CONVERSATION_CONTEXT", enrichedContext,
                "CUSTOMER_TOOLS_SECTION", customerToolsSection,
                "AWS_TOOLS_SECTION", awsToolsSection
        );

        return promptTemplateService.resolve("chat-system", templateVars);
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

    /**
     * Loads the persisted {@link ConversationContext} for a conversation and hydrates
     * workspace metadata from it.  This restores the customer / product context that
     * was resolved in earlier turns (e.g. via {@code lookup_customer_context}) so the
     * workspace is correctly configured when a conversation is resumed.
     */
    private void hydrateWorkspaceFromStoredContext(String conversationId, WorkspaceContext workspace) {
        try {
            var storedContext = conversationContextStore.getContext(conversationId).orElse(null);
            if (storedContext == null) return;

            // Restore customerId so AWS tools can read it without a fresh lookup
            if (storedContext.customerIds() != null && !storedContext.customerIds().isEmpty()) {
                workspace.putMetadata("customerId", storedContext.customerIds().get(0));
            }

            // Restore git workspace / repo metadata from the first linked product
            if (storedContext.productIds() != null && !storedContext.productIds().isEmpty()) {
                for (String pid : storedContext.productIds()) {
                    var product = registryStore.getProduct(pid).orElse(null);
                    if (product != null && product.git() != null && product.git().workspace() != null) {
                        workspace.putMetadata("workspace", product.git().workspace());
                        var repos = product.git().repos();
                        if (repos != null && !repos.isEmpty()) {
                            workspace.putMetadata("productRepos", String.join(",", repos));
                            workspace.putMetadata("repoSlug", repos.get(0));
                        }
                        break; // use first product that has git config
                    }
                }
            }

            LOG.debugf("Hydrated workspace from stored context for conversation %s "
                    + "(customers=%s products=%s)", conversationId,
                    storedContext.customerIds(), storedContext.productIds());
        } catch (Exception e) {
            LOG.warnf("Failed to hydrate workspace from stored context for %s: %s",
                    conversationId, e.getMessage());
        }
    }

    /**
     * Returns a union of two {@link ConversationContext} objects.
     * The stored context (from DB) and the inline request context (from the UI) are
     * both valid sources; merging ensures neither overwrites the other.
     */
    private static ConversationContext mergeContexts(ConversationContext stored, ConversationContext inline) {
        if (stored == null) return inline;
        if (inline == null) return stored;
        return new ConversationContext(
                stored.conversationId(),
                mergeLists(stored.customerIds(),      inline.customerIds()),
                mergeLists(stored.productIds(),       inline.productIds()),
                mergeIntLists(stored.aikidoIssueIds(), inline.aikidoIssueIds()),
                mergeLists(stored.jiraIssueKeys(),    inline.jiraIssueKeys()),
                mergeLists(stored.confluenceDocIds(), inline.confluenceDocIds()),
                stored.createdAt(),
                stored.updatedAt()
        );
    }

    private static <T> List<T> mergeLists(List<T> a, List<T> b) {
        if (b == null || b.isEmpty()) return a != null ? a : List.of();
        List<T> result = new ArrayList<>(a != null ? a : List.of());
        for (T item : b) {
            if (item != null && !result.contains(item)) result.add(item);
        }
        return result;
    }

    private static List<Integer> mergeIntLists(List<Integer> a, List<Integer> b) {
        return mergeLists(a, b);
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
                        
                        // Markdown comes directly from PlannerService; persist and create physical file
                        String markdownContent = plan.markdownContent();
                        if (markdownContent != null) {
                            planStore.updateMarkdownContent(plan.planId(), markdownContent);
                        }
                        
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

    /**
     * Builds content blocks for the user message, including text and any attachments.
     * Images are encoded as base64 and sent as image blocks.
     * Text files are read and their content is prepended with a filename header.
     */
    private List<ContentBlockParam> buildMessageContentWithAttachments(String text, List<String> attachmentIds) {
        List<ContentBlockParam> blocks = new ArrayList<>();
        
        // Add text block first
        if (text != null && !text.isBlank()) {
            blocks.add(ContentBlockParam.ofText(TextBlockParam.builder()
                    .text(text.trim())
                    .build()));
        }
        
        // Add attachment blocks if any
        if (attachmentIds != null && !attachmentIds.isEmpty()) {
            for (String attachmentId : attachmentIds) {
                try {
                    Optional<ChatAttachment> attachmentOpt = attachmentService.getAttachment(attachmentId);
                    if (attachmentOpt.isEmpty()) {
                        LOG.warnf("Attachment not found: %s", attachmentId);
                        continue;
                    }
                    
                    ChatAttachment attachment = attachmentOpt.get();
                    byte[] content = attachmentService.getAttachmentContent(attachment);
                    
                    if (attachment.isImage()) {
                        // Encode image as base64 and add as image block
                        String base64Content = Base64.getEncoder().encodeToString(content);
                        String mediaType = attachment.contentType();

                        blocks.add(
                            ContentBlockParam.ofImage(
                                ImageBlockParam.builder()
                                    .source(
                                        ImageBlockParam.Source.ofBase64(
                                            Base64ImageSource.builder()
                                                .type(JsonValue.from("base64"))
                                                .mediaType(Base64ImageSource.MediaType.of(mediaType))
                                                .data(base64Content)
                                                .build()
                                        )
                                    )
                                    .build()
                            )
                        );
                        LOG.infof("Added image attachment to message: %s (%s, %d bytes)",
                                attachment.filename(), mediaType, content.length);
                    } else if (attachment.isText()) {
                        // Read text content and add as text block with filename context
                        String fileContent = new String(content, java.nio.charset.StandardCharsets.UTF_8);
                        String contextualText = String.format(
                                "Content of file '%s':\n```\n%s\n```", 
                                attachment.filename(), fileContent);
                        blocks.add(ContentBlockParam.ofText(TextBlockParam.builder()
                                .text(contextualText)
                                .build()));
                        LOG.infof("Added text attachment to message: %s (%d bytes)", 
                                attachment.filename(), content.length);
                    } else {
                        // For other file types (PDF, etc.), try to extract text or add a reference
                        String contextualText = String.format(
                                "[Attached file: '%s' (%s, %s)]", 
                                attachment.filename(), 
                                attachment.contentType(),
                                attachment.getFormattedFileSize());
                        blocks.add(ContentBlockParam.ofText(TextBlockParam.builder()
                                .text(contextualText)
                                .build()));
                        LOG.infof("Added reference for attachment to message: %s (%s)", 
                                attachment.filename(), attachment.contentType());
                    }
                } catch (Exception e) {
                    LOG.errorf("Failed to process attachment %s: %s", attachmentId, e.getMessage());
                    // Add an error note as text block
                    blocks.add(ContentBlockParam.ofText(TextBlockParam.builder()
                            .text(String.format("[Error loading attachment: %s]", attachmentId))
                            .build()));
                }
            }
        }
        
        return blocks;
    }
    
    /**
     * Links attachments to the most recent message in the conversation.
     * This marks the attachments as having been sent with a specific message.
     */
    private void linkAttachmentsToLastMessage(String conversationId, List<String> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return;
        }
        
        // Find the most recent message ID for this conversation
        // We need to query the database to get the last sequence number
        try {
            // Get the max sequence number and then the message ID
            // Since messages are stored with sequence_num, the last message has the highest sequence
            // We'll use the conversation repository to find the last message and get its ID
            
            // For now, we query the database directly to get the last message ID
            // This is a simplified approach - in production, we might want to return the message ID from appendMessages
            Long messageId = getLastMessageId(conversationId);
            if (messageId != null) {
                attachmentService.linkAttachmentsToMessage(attachmentIds, messageId);
            } else {
                LOG.warnf("Could not find message ID to link attachments for conversation: %s", conversationId);
            }
        } catch (Exception e) {
            LOG.errorf("Failed to link attachments to message for conversation %s: %s", 
                    conversationId, e.getMessage());
        }
    }
    
    /**
     * Gets the ID of the most recently added message for a conversation.
     */
    private Long getLastMessageId(String conversationId) {
        // Query the database for the max sequence number's message ID
        // This is a simplified query - we're using the conversation repository's data source
        String sql = """
                SELECT id FROM chat_messages 
                WHERE conversation_id = ? 
                ORDER BY sequence_num DESC 
                LIMIT 1
                """;
        
        try (java.sql.Connection conn = ((javax.sql.DataSource) 
                io.quarkus.arc.Arc.container().instance(io.agroal.api.AgroalDataSource.class).get()).getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("id");
                }
            }
        } catch (Exception e) {
            LOG.errorf("Failed to get last message ID for conversation %s: %s", conversationId, e.getMessage());
        }
        return null;
    }

}
