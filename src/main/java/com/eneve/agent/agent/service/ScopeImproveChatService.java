package com.eneve.agent.agent.service;

import com.anthropic.models.messages.MessageParam;
import com.eneve.agent.agent.ClaudeToolUseLoop;
import com.eneve.agent.agent.ToolDefinitions;
import com.eneve.agent.agent.model.ChatEvent;
import com.eneve.agent.agent.store.ConversationContextStore;
import com.eneve.agent.agent.store.ConversationRepository;
import com.eneve.agent.agent.store.CustomerRegistryStore;
import com.eneve.agent.agent.store.ScopeItemProposalStore;
import com.eneve.agent.agent.store.ScopeStore;
import com.eneve.agent.model.ConversationContext;
import com.eneve.agent.model.ScopeProposal;
import com.eneve.agent.workspace.WorkspaceContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Orchestrates the Product Owner AI chat for the Scope Improve screen.
 *
 * <p>The AI can call {@code update_proposal} (via {@link com.eneve.agent.tools.UpdateProposalToolExecutor})
 * to directly edit proposal fields. After each successful {@code update_proposal} tool call the
 * service additionally emits a {@link ChatEvent.ProposalUpdated} event so the UI updates live.
 */
@ApplicationScoped
public class ScopeImproveChatService {

    private static final Logger LOG = Logger.getLogger(ScopeImproveChatService.class);
    private static final int MAX_ITERATIONS = 30;

    @Inject ClaudeToolUseLoop toolLoop;
    @Inject ConversationRepository conversationRepository;
    @Inject ConversationContextStore conversationContextStore;
    @Inject CustomerRegistryStore registryStore;
    @Inject PromptTemplateService promptTemplates;
    @Inject ContextEnrichmentService contextEnrichmentService;
    @Inject ScopeItemProposalStore proposalStore;
    @Inject ScopeStore scopeStore;
    @Inject ObjectMapper mapper;

    /** Request DTO wrapping all parameters for the improve-chat endpoint. */
    public record ScopeImproveChatRequest(
            String message,
            String conversationId,
            String scopeId,
            String issueKey,
            List<String> proposalIds,
            ConversationContext conversationContext,
            /** "chat" (default) or "ask" (read-only, no proposal editing). */
            String mode
    ) {}

    public Multi<ChatEvent> chatStream(ScopeImproveChatRequest request, String userId) {
        return Multi.createFrom().<ChatEvent>emitter(emitter -> {
            WorkspaceContext workspace = null;
            try {
                // ── Resolve or create conversation ───────────────────────
                String conversationId;
                List<MessageParam> history;

                String requestedId = request.conversationId();
                if (requestedId != null && conversationRepository.exists(requestedId, userId)) {
                    conversationId = requestedId;
                    history = sanitizeHistory(conversationRepository.loadMessages(conversationId, userId));
                } else {
                    conversationId = "scope-improve-" + UUID.randomUUID();
                    history = new ArrayList<>();
                    String title = "Improve " + request.issueKey();
                    conversationRepository.createConversation(userId, conversationId, title, null);
                }

                int priorCount = history.size();

                // ── Seed product context on first turn ───────────────────
                List<String> scopeLinkedProductIds = scopeStore.listLinkedProductIds(request.scopeId());
                ConversationContext storedContext = conversationContextStore.getContext(conversationId).orElse(null);
                ConversationContext inlineContext = request.conversationContext();

                // Build effective context merging stored + inline + scope-linked products
                ConversationContext effectiveContext = mergeContexts(
                        mergeContexts(storedContext, inlineContext),
                        scopeLinkedProductIds.isEmpty() ? null
                                : new ConversationContext(conversationId, null, scopeLinkedProductIds,
                                        null, null, null, null, null));

                // Persist merged context so product selections survive across turns
                if (effectiveContext != null) {
                    conversationContextStore.mergeContext(
                            conversationId,
                            effectiveContext.customerIds() != null ? effectiveContext.customerIds() : List.of(),
                            effectiveContext.productIds()  != null ? effectiveContext.productIds()  : List.of());
                }

                // ── Create workspace ─────────────────────────────────────
                String primaryProductId = effectiveContext != null
                        && effectiveContext.productIds() != null
                        && !effectiveContext.productIds().isEmpty()
                        ? effectiveContext.productIds().get(0) : null;

                workspace = createChatWorkspace(conversationId, primaryProductId);
                workspace.setUserId(userId);
                workspace.setConversationId(conversationId);
                workspace.putMetadata("scopeImproveChat", "true");

                // ── Build system prompt ───────────────────────────────────
                String scopeName = scopeStore.findById(request.scopeId())
                        .map(com.eneve.agent.model.ScopeRecord::name)
                        .orElse(request.scopeId());
                String proposalsSnapshot = buildProposalsSnapshot(request.proposalIds());
                String enrichedContext = buildEnrichedContext(effectiveContext, userId);

                // Determine the primary issue type from the proposals so we can inject
                // type-specific writing guidance into the system prompt.
                String primaryIssueType = resolvePrimaryIssueType(request.issueKey(), request.proposalIds());
                String issueTypeGuidance = buildIssueTypeGuidance(primaryIssueType);

                String systemPrompt = promptTemplates.resolve("scope-improve-chat", Map.of(
                        "scope_name",          scopeName,
                        "issue_key",           request.issueKey() != null ? request.issueKey() : "",
                        "current_proposals",   proposalsSnapshot,
                        "product_context",     enrichedContext,
                        "issue_type_guidance", issueTypeGuidance
                ));

                if (systemPrompt.isBlank()) {
                    // Fallback inline prompt if template is not configured yet
                    systemPrompt = buildFallbackSystemPrompt(scopeName, request.issueKey(),
                            primaryIssueType, proposalsSnapshot, enrichedContext);
                }

                boolean isAskMode = "ask".equals(request.mode());
                if (isAskMode) {
                    systemPrompt += "\n\n**IMPORTANT — Ask Mode:** You are operating in read-only Ask mode. "
                            + "You may analyse and answer questions about the proposals and Jira issues, "
                            + "but you must NOT call update_proposal or make any changes to proposals or Jira.";
                }

                // ── Run streaming loop with proposal_updated interception ─
                final WorkspaceContext finalWorkspace = workspace;
                final String finalConversationId = conversationId;
                final int finalPriorCount = priorCount;

                List<MessageParam> updatedHistory = toolLoop.runStreaming(
                        systemPrompt,
                        finalWorkspace,
                        isAskMode ? ToolDefinitions.scopeImproveChatAsk() : ToolDefinitions.scopeImproveChat(),
                        request.message(),
                        history,
                        conversationId,
                        "SCOPE_IMPROVE_CHAT",
                        MAX_ITERATIONS,
                        event -> {
                            emitter.emit(event);

                            // After update_proposal completes, emit a proposal_updated event
                            if (event instanceof ChatEvent.ToolEnd te
                                    && "update_proposal".equals(te.tool())) {
                                try {
                                    Map<String, Object> proposalMap = mapper.readValue(
                                            te.result(), new TypeReference<>() {});
                                    String pid = (String) proposalMap.get("id");
                                    if (pid != null) {
                                        emitter.emit(new ChatEvent.ProposalUpdated(pid, proposalMap));
                                    }
                                } catch (Exception ex) {
                                    LOG.warnf("ScopeImproveChatService: could not parse proposal update: %s",
                                            ex.getMessage());
                                }
                            }

                            if (event instanceof ChatEvent.Done || event instanceof ChatEvent.Error) {
                                emitter.complete();
                            }
                        });

                // ── Persist messages ──────────────────────────────────────
                try {
                    conversationRepository.appendMessages(finalConversationId, updatedHistory, finalPriorCount);
                    conversationRepository.touch(finalConversationId);
                } catch (Exception e) {
                    LOG.warnf("ScopeImproveChatService: failed to persist messages for %s: %s",
                            finalConversationId, e.getMessage());
                }

                emitter.complete();

            } catch (Exception e) {
                LOG.errorf("ScopeImproveChatService error: %s", e.getMessage());
                emitter.emit(new ChatEvent.Error(e.getMessage() != null ? e.getMessage() : "Internal error"));
                emitter.complete();
            } finally {
                if (workspace != null) workspace.close();
            }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private WorkspaceContext createChatWorkspace(String conversationId, String productId) {
        try {
            WorkspaceContext workspace = WorkspaceContext.create(conversationId);
            if (productId != null && !productId.isBlank()) {
                var product = registryStore.getProduct(productId).orElse(null);
                if (product != null && product.git() != null && product.git().workspace() != null) {
                    workspace.putMetadata("workspace", product.git().workspace());
                    var repos = product.git().repos();
                    if (repos != null && !repos.isEmpty()) {
                        workspace.putMetadata("productRepos", String.join(",", repos));
                        workspace.putMetadata("repoSlug", repos.get(0));
                    }
                }
            }
            return workspace;
        } catch (IOException e) {
            throw new RuntimeException("Failed to create workspace: " + e.getMessage(), e);
        }
    }

    private String buildProposalsSnapshot(List<String> proposalIds) {
        if (proposalIds == null || proposalIds.isEmpty()) return "No proposals loaded yet.";
        List<ScopeProposal> proposals = proposalIds.stream()
                .map(id -> proposalStore.findById(id).orElse(null))
                .filter(p -> p != null)
                .collect(Collectors.toList());
        if (proposals.isEmpty()) return "No proposals found.";
        StringBuilder sb = new StringBuilder();
        for (ScopeProposal p : proposals) {
            sb.append("### Proposal ID: ").append(p.id()).append("\n");
            sb.append("Issue: ").append(p.issueKey()).append(" (").append(p.issueType()).append(")\n");
            sb.append("Summary: ").append(p.proposedSummary() != null ? p.proposedSummary() : "—").append("\n");
            sb.append("Label: ").append(p.proposedLabel() != null ? p.proposedLabel() : "—").append("\n");
            sb.append("Priority: ").append(p.proposedPriority() != null ? p.proposedPriority() : "—").append("\n");
            if (p.proposedDescription() != null && !p.proposedDescription().isBlank()) {
                sb.append("Description:\n").append(p.proposedDescription()).append("\n");
            }
            if (p.proposedCriteria() != null && !p.proposedCriteria().isBlank()) {
                sb.append("Acceptance Criteria:\n").append(p.proposedCriteria()).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String buildEnrichedContext(ConversationContext ctx, String userId) {
        if (ctx == null) return "";
        try {
            return contextEnrichmentService.enrichContext(ctx, userId);
        } catch (Exception e) {
            LOG.warnf("ScopeImproveChatService: failed to enrich context: %s", e.getMessage());
            return "";
        }
    }

    /**
     * Looks up the issue type of the primary issue key from the loaded proposals.
     * Falls back to "FEATURE" if nothing is found.
     */
    private String resolvePrimaryIssueType(String issueKey, List<String> proposalIds) {
        if (issueKey == null || proposalIds == null) return "FEATURE";
        return proposalIds.stream()
                .map(id -> proposalStore.findById(id).orElse(null))
                .filter(p -> p != null && issueKey.equals(p.issueKey()))
                .map(ScopeProposal::issueType)
                .filter(t -> t != null)
                .findFirst()
                .orElse("FEATURE");
    }

    /**
     * Returns a focused writing-guide block tailored to the given issue type.
     * The criteria in each block deliberately mirror the scoring dimensions used by the
     * corresponding review prompt (review-epic / review-feature / review-userstory) so the
     * AI assistant steers the user toward the same quality bar the reviewer will measure.
     *
     * <ul>
     *   <li>EPIC → <em>The Why</em> — business case and strategic rationale</li>
     *   <li>FEATURE → <em>The What</em> — scope, boundaries, user-facing behaviour</li>
     *   <li>USERSTORY → <em>The How</em> — implementation steps and acceptance criteria</li>
     * </ul>
     */
    private String buildIssueTypeGuidance(String issueType) {
        return switch (issueType != null ? issueType.toUpperCase() : "") {
            case "EPIC" -> """
                    This is an **EPIC** — focus on **The Why (business case)**.
                    The review will score this Epic on six dimensions; make sure the proposal addresses all of them:

                    1. **Goal clarity** — State a clear, measurable outcome that explains *why* this work is being done. Avoid vague goals.
                    2. **Scope definition** — Explicitly describe what is in scope *and* what is out of scope.
                    3. **Acceptance criteria** — Add high-level conditions that indicate when the Epic is "done" from a business perspective (not detailed Given/When/Then — that belongs in User Stories).
                    4. **Dependencies** — Identify known blockers or upstream/downstream dependencies, or explicitly note that none are known.
                    5. **Business value** — Articulate the business impact or user value delivered by this Epic.
                    6. **Decomposition readiness** — The Epic should be scoped so it can be broken into concrete child Features. If no Features exist yet, the description must make decomposition obvious.

                    The summary should express a business goal, not a technical task (e.g. "Enable customers to self-serve account changes" not "Build account settings API").
                    """;
            case "USERSTORY" -> """
                    This is a **User Story** — focus on **The How (implementation detail)**.
                    The review will score this User Story on six dimensions; make sure the proposal addresses all of them:

                    1. **Story format** — Use "As a [role], I want [action] so that [benefit]" in the summary or opening of the description.
                    2. **Acceptance criteria** — Write concrete, testable "Given / When / Then" scenarios in the Acceptance Criteria field. One scenario per block; cover the happy path and key variants.
                    3. **Scope & boundaries** — Clearly state what is in scope for *this* story; it must be completable in a single sprint. If the original is too large, flag it.
                    4. **Technical clarity** — Note the technical approach, affected services, or constraints in the Technical Notes field.
                    5. **Dependencies & blockers** — Identify any blocking stories or external dependencies.
                    6. **Edge cases & error handling** — Describe key edge cases, validation rules, and error conditions in the description or acceptance criteria.
                    """;
            default -> """
                    This is a **Feature** — focus on **The What (scope and user value)**.
                    The review will score this Feature on six dimensions; make sure the proposal addresses all of them:

                    1. **Goal clarity** — State a clear, user-facing or system outcome.
                    2. **User perspective** — Identify the beneficiary (user role or system) and the value they receive. Use "As a [role]…" framing where it fits.
                    3. **Acceptance criteria** — Write specific, *testable* conditions for "done" (not aspirational — "The system shall…" not "It should feel…").
                    4. **Technical scope** — Note key technical decisions, integrations, or constraints relevant to implementation.
                    5. **Dependencies** — Identify upstream blockers or downstream requirements.
                    6. **Decomposition readiness** — The Feature should be scoped to 1–2 sprints and decomposable into concrete User Stories. If no User Stories exist yet, the description must make decomposition straightforward.

                    The summary should name the capability (e.g. "Allow bulk export of invoices as PDF").
                    """;
        };
    }

    private String buildFallbackSystemPrompt(String scopeName, String issueKey,
                                              String issueType, String proposalsSnapshot,
                                              String productContext) {
        return """
                You are an experienced Product Owner specialising in writing clear, outcome-driven
                scope items for engineering teams. Your goal is to help refine the scope item
                below so it is well-scoped, measurable, and ready for delivery.

                **Scope:** %s
                **Issue being refined:** %s

                ## Issue type focus
                %s

                **Current proposal snapshot:**
                %s

                %s

                ## Guidelines
                - Write acceptance criteria as "Given / When / Then" bullet points.
                - Keep summaries concise (≤ 15 words).
                - Use plain markdown; no HTML.
                - When you want to apply a change, **call `update_proposal`** with the affected
                  fields — do NOT just describe the change in prose.
                """.formatted(scopeName, issueKey != null ? issueKey : "",
                buildIssueTypeGuidance(issueType), proposalsSnapshot, productContext);
    }

    private List<MessageParam> sanitizeHistory(List<MessageParam> raw) {
        if (raw == null) return new ArrayList<>();
        return new ArrayList<>(raw);
    }

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
}
