package com.eneve.agent.scope;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.eneve.agent.agent.ClaudeToolUseLoop;
import com.eneve.agent.agent.ToolDefinitions;
import com.eneve.agent.agent.service.JiraReviewContextBuilder;
import com.eneve.agent.agent.service.PromptTemplateService;
import com.eneve.agent.agent.store.CustomerRegistryStore;
import com.eneve.agent.agent.store.ScopeItemProposalStore;
import com.eneve.agent.agent.store.ScopeItemStore;
import com.eneve.agent.agent.store.ScopeStore;
import com.eneve.agent.audit.AuditService;
import com.eneve.agent.jira.JiraService;
import com.eneve.agent.jira.JiraService.JiraIssueDetail;
import com.eneve.agent.model.ProductConfig;
import com.eneve.agent.model.ScopeItem;
import com.eneve.agent.model.ScopeProposal;
import com.eneve.agent.model.ScopeRecord;
import com.eneve.agent.scope.ScopeExceptions.*;
import com.eneve.agent.settings.SettingsService;
import com.eneve.agent.workspace.WorkspaceContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Handles AI-driven scope improvement: proposals, Claude generation, and Jira write-back.
 * Corresponds to the {@code ScopeImprove.tsx} screen.
 */
@ApplicationScoped
public class ScopeImprovementService {

    private static final Logger LOG = Logger.getLogger(ScopeImprovementService.class);

    @Inject ScopeStore scopeStore;
    @Inject ScopeItemStore scopeItemStore;
    @Inject ScopeItemProposalStore proposalStore;
    @Inject CustomerRegistryStore customerRegistryStore;
    @Inject JiraService jiraService;
    @Inject AuditService auditService;
    @Inject SettingsService settings;
    @Inject ObjectMapper mapper;
    @Inject AnthropicClient anthropicClient;
    @Inject ClaudeToolUseLoop toolLoop;
    @Inject JiraReviewContextBuilder contextBuilder;
    @Inject PromptTemplateService promptTemplates;

    // ─── Improve ──────────────────────────────────────────────────────────────

    /**
     * Generates an AI improvement proposal for the given issue and stores it as DRAFT.
     * Synchronous — call from a JAX-RS endpoint that can tolerate latency.
     *
     * @throws ScopeNotFoundException         if the scope does not exist
     * @throws JiraIssueNotFoundException     if the item is not in scope_items
     * @throws ImprovementGenerationException if the AI call fails or returns unparseable JSON
     */
    public ScopeProposal improveItem(String scopeId, String issueKey) {
        if (scopeStore.findById(scopeId).isEmpty()) throw new ScopeNotFoundException(scopeId);
        ScopeItem item = scopeItemStore.findByScopeAndIssueKey(scopeId, issueKey)
                .orElseThrow(() -> new JiraIssueNotFoundException(issueKey));

        String context  = buildImproveContext(item);
        String promptKey = "improve-" + item.issueType().toLowerCase();
        String prompt   = promptTemplates.resolve(promptKey, Map.of("jira_context", context));

        List<ProductConfig> linkedProducts = scopeStore.listLinkedProductIds(scopeId).stream()
                .map(pid -> customerRegistryStore.getProduct(pid).orElse(null))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());

        String responseText = linkedProducts.isEmpty()
                ? callClaudeForProposal(prompt, issueKey)
                : callClaudeWithTools(prompt, issueKey, scopeId, linkedProducts);
        if (responseText == null) {
            throw new ImprovementGenerationException("AI call returned no content for " + issueKey);
        }

        String cleaned = extractJson(responseText);
        JsonNode root;
        try {
            root = mapper.readTree(cleaned);
        } catch (Exception e) {
            throw new ImprovementGenerationException("Malformed JSON from AI for " + issueKey + ": " + e.getMessage());
        }

        ScopeProposal proposal = proposalStore.create(
                scopeId, issueKey, item.issueType(), item.parentKey(),
                root.path("proposed_summary").asText(""),
                root.path("proposed_description").asText(""),
                root.path("proposed_criteria").asText(""),
                root.path("proposed_technical").asText(""),
                root.path("ai_explanation").asText("")
        );
        auditService.log("SCOPE", "PROPOSAL_CREATED", "scope_item", issueKey,
                Map.of("scopeId", scopeId));
        return proposal;
    }

    // ─── Proposals ────────────────────────────────────────────────────────────

    /** Returns all proposals for a given scope + issue key (newest first). */
    public List<ScopeProposal> getProposals(String scopeId, String issueKey) {
        if (scopeStore.findById(scopeId).isEmpty()) throw new ScopeNotFoundException(scopeId);
        return proposalStore.findByScopeAndIssueKey(scopeId, issueKey);
    }

    /**
     * Initialises a proposal for the given scope item.
     * <ul>
     *   <li>If a DRAFT already exists → returns it (no Jira call).</li>
     *   <li>Otherwise → fetches Jira issue detail and seeds a new DRAFT.</li>
     * </ul>
     *
     * @throws ScopeNotFoundException     if the scope does not exist
     * @throws JiraIssueNotFoundException if Jira returns no data for the issue key
     */
    public InitProposalResult initProposal(String scopeId, String issueKey) {
        if (scopeStore.findById(scopeId).isEmpty()) throw new ScopeNotFoundException(scopeId);

        Optional<ScopeProposal> existing = proposalStore.findDraftByScopeAndIssueKey(scopeId, issueKey);
        if (existing.isPresent()) {
            JiraIssueDetail detail = jiraService.fetchIssueDetail(issueKey);
            List<JiraService.JiraAttachment> attachments = detail != null ? detail.attachments() : List.of();
            java.time.Instant jiraUpdatedAt = detail != null ? detail.updatedAt() : null;
            return new InitProposalResult(existing.get(), attachments != null ? attachments : List.of(), jiraUpdatedAt);
        }

        JiraIssueDetail detail = jiraService.fetchIssueDetail(issueKey);
        if (detail == null) throw new JiraIssueNotFoundException(issueKey);

        ScopeItem item = scopeItemStore.findByScopeAndIssueKey(scopeId, issueKey).orElse(null);
        String issueType = item != null ? item.issueType() : "FEATURE";
        String parentKey  = item != null ? item.parentKey()  : null;

        String proposedLabel = (detail.labels() != null && !detail.labels().isEmpty())
                ? detail.labels().get(0) : null;

        ScopeProposal proposal = proposalStore.create(
                scopeId, issueKey, issueType, parentKey,
                detail.summary(), detail.description(),
                null, null, null,
                proposedLabel, detail.priority());

        auditService.log("SCOPE", "PROPOSAL_CREATED", "scope_item", issueKey,
                Map.of("scopeId", scopeId, "source", "initProposal"));

        List<JiraService.JiraAttachment> attachments = detail.attachments();
        return new InitProposalResult(proposal, attachments != null ? attachments : List.of(), detail.updatedAt());
    }

    /**
     * Analyses an EPIC and its existing features with Claude, then creates DRAFT proposals
     * for any features that appear to be missing.
     */
    public List<ScopeProposal> proposeFeaturesForEpic(String scopeId, String epicKey) {
        if (scopeStore.findById(scopeId).isEmpty()) throw new ScopeNotFoundException(scopeId);

        JiraIssueDetail epic = jiraService.fetchIssueDetail(epicKey);
        String epicSummary     = epic != null ? epic.summary()     : epicKey;
        String epicDescription = epic != null ? epic.description() : "";

        List<ScopeItem> allItems = scopeItemStore.findByScope(scopeId);
        List<ScopeItem> existingFeatures = allItems.stream()
                .filter(i -> "FEATURE".equals(i.issueType()) && epicKey.equals(i.parentKey()))
                .toList();

        StringBuilder featureList = new StringBuilder();
        if (existingFeatures.isEmpty()) {
            featureList.append("(none yet)");
        } else {
            for (ScopeItem f : existingFeatures) {
                featureList.append("- ").append(f.issueKey()).append(": ").append(f.summary()).append("\n");
            }
        }

        String prompt = """
                You are a product owner reviewing the scope of an Epic.

                Epic key: %s
                Epic summary: %s
                Epic description:
                %s

                Existing features already linked to this Epic:
                %s

                Identify any features that seem MISSING or INCOMPLETE given the Epic's goals.
                Consider edge cases, error handling, admin/settings flows, and non-happy-path scenarios.

                Respond ONLY with a valid JSON array — no prose, no markdown fences. Each element:
                { "title": "<short feature title>", "description": "<1-2 sentence description>" }

                Return at most 6 suggestions. If nothing is missing, return [].
                """.formatted(epicKey, epicSummary,
                epicDescription != null ? epicDescription : "",
                featureList.toString().trim());

        String modelName = settings.get("anthropic.fast-model", "claude-haiku-4-5");
        MessageCreateParams params = MessageCreateParams.builder()
                .model(Model.of(modelName))
                .maxTokens(2048)
                .messages(List.of(MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .content(prompt)
                        .build()))
                .build();

        String responseText = null;
        try {
            Message response = anthropicClient.messages().create(params);
            for (ContentBlock block : response.content()) {
                if (block.isText()) { responseText = block.asText().text().trim(); break; }
            }
        } catch (Exception e) {
            LOG.errorf("proposeFeaturesForEpic: Claude call failed for %s: %s", epicKey, e.getMessage());
            return List.of();
        }

        if (responseText == null || responseText.isBlank()) return List.of();
        if (responseText.startsWith("```")) {
            responseText = responseText.replaceAll("(?s)^```[a-z]*\\n?", "").replaceAll("```$", "").trim();
        }

        List<ScopeProposal> created = new ArrayList<>();
        try {
            JsonNode arr = mapper.readTree(responseText);
            if (!arr.isArray()) return List.of();
            for (JsonNode node : arr) {
                String title       = node.path("title").asText(null);
                String description = node.path("description").asText(null);
                if (title == null || title.isBlank()) continue;
                String syntheticKey = "NEW-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
                created.add(proposalStore.create(scopeId, syntheticKey, "FEATURE", epicKey,
                        title, description, null, null, null, null, null));
            }
        } catch (Exception e) {
            LOG.errorf("proposeFeaturesForEpic: JSON parse failed for %s: %s", epicKey, e.getMessage());
        }

        if (!created.isEmpty()) {
            auditService.log("SCOPE", "FEATURES_PROPOSED", "scope_item", epicKey,
                    Map.of("scopeId", scopeId, "count", String.valueOf(created.size())));
        }
        return created;
    }

    /**
     * Analyses a FEATURE and its existing user stories with Claude, then creates DRAFT proposals
     * for any user stories that appear to be missing.
     */
    public List<ScopeProposal> proposeUserStoriesForFeature(String scopeId, String featureKey) {
        if (scopeStore.findById(scopeId).isEmpty()) throw new ScopeNotFoundException(scopeId);

        JiraIssueDetail feature = jiraService.fetchIssueDetail(featureKey);
        String featureSummary     = feature != null ? feature.summary()     : featureKey;
        String featureDescription = feature != null ? feature.description() : "";

        ScopeItem featureItem = scopeItemStore.findByScopeAndIssueKey(scopeId, featureKey).orElse(null);
        String epicContext = "";
        if (featureItem != null && featureItem.parentKey() != null) {
            JiraIssueDetail epic = jiraService.fetchIssueDetail(featureItem.parentKey());
            if (epic != null) {
                epicContext = "Parent Epic: " + featureItem.parentKey() + " — " + epic.summary() + "\n\n";
            }
        }

        List<ScopeItem> allItems = scopeItemStore.findByScope(scopeId);
        List<ScopeItem> existingStories = allItems.stream()
                .filter(i -> "USERSTORY".equals(i.issueType()) && featureKey.equals(i.parentKey()))
                .toList();

        StringBuilder storyList = new StringBuilder();
        if (existingStories.isEmpty()) {
            storyList.append("(none yet)");
        } else {
            for (ScopeItem s : existingStories) {
                storyList.append("- ").append(s.issueKey()).append(": ").append(s.summary()).append("\n");
            }
        }

        String prompt = """
                You are a product owner reviewing the scope of a Feature.

                %sFeature key: %s
                Feature summary: %s
                Feature description:
                %s

                Existing user stories already linked to this Feature:
                %s

                Identify any user stories that seem MISSING or INCOMPLETE given the Feature's goals.
                Consider edge cases, error handling, admin/settings flows, and non-happy-path scenarios.
                Each story must be small enough to complete in a single sprint.

                Respond ONLY with a valid JSON array — no prose, no markdown fences. Each element:
                { "title": "<short story title preferably in 'As a ... I want ...' format>", "description": "<1-2 sentence description including key acceptance notes>" }

                Return at most 8 suggestions. If nothing is missing, return [].
                """.formatted(epicContext, featureKey, featureSummary,
                featureDescription != null ? featureDescription : "",
                storyList.toString().trim());

        String modelName = settings.get("anthropic.fast-model", "claude-haiku-4-5");
        MessageCreateParams params = MessageCreateParams.builder()
                .model(Model.of(modelName))
                .maxTokens(2048)
                .messages(List.of(MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .content(prompt)
                        .build()))
                .build();

        String responseText = null;
        try {
            Message response = anthropicClient.messages().create(params);
            for (ContentBlock block : response.content()) {
                if (block.isText()) { responseText = block.asText().text().trim(); break; }
            }
        } catch (Exception e) {
            LOG.errorf("proposeUserStoriesForFeature: Claude call failed for %s: %s", featureKey, e.getMessage());
            return List.of();
        }

        if (responseText == null || responseText.isBlank()) return List.of();
        if (responseText.startsWith("```")) {
            responseText = responseText.replaceAll("(?s)^```[a-z]*\\n?", "").replaceAll("```$", "").trim();
        }

        List<ScopeProposal> created = new ArrayList<>();
        try {
            JsonNode arr = mapper.readTree(responseText);
            if (!arr.isArray()) return List.of();
            for (JsonNode node : arr) {
                String title       = node.path("title").asText(null);
                String description = node.path("description").asText(null);
                if (title == null || title.isBlank()) continue;
                String syntheticKey = "NEW-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
                created.add(proposalStore.create(scopeId, syntheticKey, "USERSTORY", featureKey,
                        title, description, null, null, null, null, null));
            }
        } catch (Exception e) {
            LOG.errorf("proposeUserStoriesForFeature: JSON parse failed for %s: %s", featureKey, e.getMessage());
        }

        if (!created.isEmpty()) {
            auditService.log("SCOPE", "STORIES_PROPOSED", "scope_item", featureKey,
                    Map.of("scopeId", scopeId, "count", String.valueOf(created.size())));
        }
        return created;
    }

    /**
     * Creates a blank DRAFT FEATURE proposal not yet backed by a Jira issue.
     * A synthetic issue key of the form {@code NEW-XXXXXXXX} is generated.
     */
    public ScopeProposal createNewFeatureProposal(String scopeId, String parentKey, String proposedSummary) {
        if (scopeStore.findById(scopeId).isEmpty()) throw new ScopeNotFoundException(scopeId);

        String syntheticKey = "NEW-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        ScopeProposal proposal = proposalStore.create(scopeId, syntheticKey, "FEATURE", parentKey,
                proposedSummary, null, null, null, null, null, null);

        auditService.log("SCOPE", "PROPOSAL_CREATED", "scope_item", syntheticKey,
                Map.of("scopeId", scopeId, "source", "manual", "parentKey", parentKey != null ? parentKey : ""));
        return proposal;
    }

    /** Updates the text fields of an existing proposal (allowed at any status). */
    public ScopeProposal updateProposal(String scopeId, String proposalId,
                                         String summary, String description,
                                         String criteria, String technical) {
        return updateProposal(scopeId, proposalId, summary, description, criteria, technical, null, null, null);
    }

    /** Updates all editable fields of an existing proposal, including label and priority. */
    public ScopeProposal updateProposal(String scopeId, String proposalId,
                                         String summary, String description,
                                         String criteria, String technical,
                                         String label, String priority) {
        return updateProposal(scopeId, proposalId, summary, description, criteria, technical, label, priority, null);
    }

    /** Updates all editable fields of an existing proposal, recording who made the change. */
    public ScopeProposal updateProposal(String scopeId, String proposalId,
                                         String summary, String description,
                                         String criteria, String technical,
                                         String label, String priority,
                                         String updatedBy) {
        ScopeProposal existing = proposalStore.findById(proposalId)
                .orElseThrow(() -> new ProposalNotFoundException(proposalId));
        proposalStore.updateFields(proposalId, summary, description, criteria, technical, label, priority, updatedBy);
        ScopeProposal updated = proposalStore.findById(proposalId).orElse(existing);
        auditService.log("SCOPE", "PROPOSAL_UPDATED", "scope_proposal", proposalId,
                Map.of("scopeId", scopeId));
        return updated;
    }

    /**
     * Accepts a proposal and synchronises the changes to Jira.
     * Existing issues are updated in-place; NEW-* proposals create a new Jira issue.
     */
    public ScopeProposal acceptProposal(String scopeId, String proposalId) {
        return acceptProposal(scopeId, proposalId, null);
    }

    public ScopeProposal acceptProposal(String scopeId, String proposalId, String syncedBy) {
        ScopeProposal proposal = proposalStore.findById(proposalId)
                .orElseThrow(() -> new ProposalNotFoundException(proposalId));

        String jiraResultKey = proposal.issueKey();
        boolean isNew = proposal.issueKey().startsWith("NEW-");
        String combinedDescription = buildJiraDescription(proposal);
        List<String> labels = (proposal.proposedLabel() != null && !proposal.proposedLabel().isBlank())
                ? List.of(proposal.proposedLabel()) : null;

        if (isNew) {
            String projectKey = deriveProjectKey(proposal.parentKey());
            if (projectKey != null) {
                ScopeRecord scope = scopeStore.findById(scopeId).orElse(null);
                String jiraIssueType = resolveJiraIssueType(scope, proposal.issueType());
                String newKey = jiraService.createIssueSystem(
                        projectKey,
                        proposal.proposedSummary() != null ? proposal.proposedSummary() : "",
                        combinedDescription,
                        jiraIssueType,
                        proposal.parentKey(),
                        labels != null ? labels : List.of(),
                        null,
                        proposal.proposedPriority());
                if (newKey != null) {
                    jiraResultKey = newKey;
                    scopeItemStore.insertItem(scopeId, newKey, proposal.issueType(),
                            proposal.parentKey(), null, proposal.proposedSummary());
                    LOG.infof("ScopeImprovementService.acceptProposal: created Jira issue %s from proposal %s", newKey, proposalId);
                } else {
                    LOG.warnf("ScopeImprovementService.acceptProposal: Jira issue creation returned null for proposal %s", proposalId);
                }
            } else {
                LOG.warnf("ScopeImprovementService.acceptProposal: cannot derive project key from parentKey '%s' for proposal %s",
                        proposal.parentKey(), proposalId);
            }
        } else {
            jiraService.updateIssueSystem(
                    proposal.issueKey(),
                    proposal.proposedSummary(),
                    combinedDescription,
                    labels,
                    proposal.proposedPriority());
            if (proposal.proposedSummary() != null && !proposal.proposedSummary().isBlank()) {
                scopeItemStore.updateSummary(scopeId, proposal.issueKey(), proposal.proposedSummary());
            }
            LOG.infof("ScopeImprovementService.acceptProposal: updated Jira issue %s from proposal %s", proposal.issueKey(), proposalId);
        }

        proposalStore.updateStatus(proposalId, "ACCEPTED", jiraResultKey, syncedBy);
        ScopeProposal accepted = proposalStore.findById(proposalId).orElse(proposal);
        auditService.log("SCOPE", "PROPOSAL_ACCEPTED", "scope_proposal", proposalId,
                Map.of("scopeId", scopeId, "jiraKey", jiraResultKey));
        return accepted;
    }

    /** Soft-rejects a proposal (marks REJECTED, keeps the row for reference). */
    public ScopeProposal rejectProposal(String scopeId, String proposalId) {
        ScopeProposal proposal = proposalStore.findById(proposalId)
                .orElseThrow(() -> new ProposalNotFoundException(proposalId));
        proposalStore.updateStatus(proposalId, "REJECTED", null, null);
        ScopeProposal rejected = proposalStore.findById(proposalId).orElse(proposal);
        auditService.log("SCOPE", "PROPOSAL_REJECTED", "scope_proposal", proposalId,
                Map.of("scopeId", scopeId));
        return rejected;
    }

    /** Hard-deletes a proposal. Allowed at any status. */
    public void deleteProposal(String scopeId, String proposalId) {
        if (proposalStore.findById(proposalId).isEmpty()) throw new ProposalNotFoundException(proposalId);
        proposalStore.delete(proposalId);
        auditService.log("SCOPE", "PROPOSAL_DELETED", "scope_proposal", proposalId,
                Map.of("scopeId", scopeId));
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private String buildImproveContext(ScopeItem item) {
        return switch (item.issueType()) {
            case "EPIC"    -> contextBuilder.buildEpicContext(item.issueKey());
            case "FEATURE" -> contextBuilder.buildFeatureContext(item.issueKey(), item.parentKey());
            default        -> contextBuilder.buildUserStoryContext(item.issueKey(), item.parentKey(), item.grandparentKey());
        };
    }

    private String callClaudeForProposal(String prompt, String issueKey) {
        String modelName = settings.get("roadmap.review.model", "");
        if (modelName.isBlank()) modelName = settings.get("anthropic.model", "claude-3-5-sonnet-20241022");
        int maxTokens = Integer.parseInt(settings.get("roadmap.review.max-tokens", "4096"));

        MessageCreateParams params = MessageCreateParams.builder()
                .model(Model.of(modelName))
                .maxTokens(maxTokens)
                .messages(List.of(MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .content(prompt)
                        .build()))
                .build();
        try {
            Message response = anthropicClient.messages().create(params);
            for (ContentBlock block : response.content()) {
                if (block.isText()) return block.asText().text().trim();
            }
            return null;
        } catch (Exception e) {
            LOG.errorf("ScopeImprovementService.callClaudeForProposal: Claude call failed for %s: %s",
                    issueKey, e.getMessage());
            return null;
        }
    }

    private String callClaudeWithTools(String userPrompt, String issueKey,
                                       String scopeId, List<ProductConfig> products) {
        WorkspaceContext workspace;
        try {
            workspace = WorkspaceContext.create("scope-improve-" + issueKey);
        } catch (Exception e) {
            LOG.warnf("ScopeImprovementService.callClaudeWithTools: could not create workspace, falling back: %s",
                    e.getMessage());
            return callClaudeForProposal(userPrompt, issueKey);
        }

        try {
            ProductConfig primary = products.get(0);
            if (primary.git() != null) {
                if (primary.git().workspace() != null)
                    workspace.putMetadata("workspace", primary.git().workspace());
                if (primary.git().repos() != null && !primary.git().repos().isEmpty())
                    workspace.putMetadata("repoSlug", primary.git().repos().get(0));
                if (primary.git().repos() != null && primary.git().repos().size() > 1)
                    workspace.putMetadata("productRepos", String.join(",", primary.git().repos()));
            }

            String productContext = buildProductContext(products);
            String systemPrompt = """
                    You are a senior product manager and software architect improving Jira issues.
                    Use the available tools to research the current codebase architecture, knowledge base,
                    and documentation so your improvements are grounded in the actual implementation.
                    Always respond with valid JSON only — no prose outside the JSON block.
                    """ + productContext;

            String modelName = settings.get("roadmap.review.model", "");
            if (modelName.isBlank()) modelName = settings.get("anthropic.model", "claude-3-5-sonnet-20241022");
            int maxTokens = Integer.parseInt(settings.get("roadmap.review.max-tokens", "4096"));
            int maxIterations = Integer.parseInt(settings.get("roadmap.improve.max-tool-iterations", "10"));

            return toolLoop.run(systemPrompt, workspace, ToolDefinitions.scopeImprove(),
                    userPrompt, maxIterations,
                    "scope-improve-" + issueKey, "SCOPE_IMPROVE");
        } finally {
            workspace.close();
        }
    }

    private static String buildProductContext(List<ProductConfig> products) {
        if (products.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\nLinked products for codebase context:\n");
        for (ProductConfig p : products) {
            sb.append("- ").append(p.displayName()).append(" (id: ").append(p.productId()).append(")");
            if (p.git() != null && p.git().repos() != null && !p.git().repos().isEmpty()) {
                sb.append(" repos: ").append(String.join(", ", p.git().repos()));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static String extractJson(String text) {
        String s = text.strip();
        if (s.startsWith("```")) {
            int nl  = s.indexOf('\n');
            int end = s.lastIndexOf("```");
            if (nl > 0 && end > nl) s = s.substring(nl + 1, end).strip();
        }
        return s;
    }

    private static String buildJiraDescription(ScopeProposal proposal) {
        StringBuilder sb = new StringBuilder();
        if (proposal.proposedDescription() != null && !proposal.proposedDescription().isBlank()) {
            sb.append(proposal.proposedDescription().strip());
        }
        if (proposal.proposedCriteria() != null && !proposal.proposedCriteria().isBlank()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("## Acceptance Criteria\n\n").append(proposal.proposedCriteria().strip());
        }
        if (proposal.proposedTechnical() != null && !proposal.proposedTechnical().isBlank()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("## Technical Notes\n\n").append(proposal.proposedTechnical().strip());
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private static String deriveProjectKey(String issueKey) {
        if (issueKey == null || issueKey.isBlank()) return null;
        int dash = issueKey.indexOf('-');
        return dash > 0 ? issueKey.substring(0, dash) : null;
    }

    private static String resolveJiraIssueType(ScopeRecord scope, String issueType) {
        if (scope == null) return "Story";
        return switch (issueType) {
            case "EPIC"    -> scope.epicIssuetype()      != null ? scope.epicIssuetype()      : "Epic";
            case "FEATURE" -> scope.featureIssuetype()   != null ? scope.featureIssuetype()   : "Story";
            default        -> scope.userstoryIssuetype() != null ? scope.userstoryIssuetype() : "Sub-task";
        };
    }
}
