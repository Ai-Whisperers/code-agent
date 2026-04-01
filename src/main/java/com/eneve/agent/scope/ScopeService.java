package com.eneve.agent.scope;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.eneve.agent.agent.ClaudeToolUseLoop;
import com.eneve.agent.agent.JobQueue;
import com.eneve.agent.agent.ToolDefinitions;
import com.eneve.agent.agent.handlers.ReviewEpicHandler;
import com.eneve.agent.agent.handlers.ReviewFeatureHandler;
import com.eneve.agent.agent.handlers.ReviewUserStoryHandler;
import com.eneve.agent.agent.service.JiraReviewContextBuilder;
import com.eneve.agent.agent.service.PromptTemplateService;
import com.eneve.agent.agent.store.CustomerRegistryStore;
import com.eneve.agent.agent.store.JiraIssueReviewStore;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.agent.store.ScopeItemOverrideStore;
import com.eneve.agent.agent.store.ScopeItemProposalStore;
import com.eneve.agent.agent.store.ScopeItemStore;
import com.eneve.agent.agent.store.ScopeStore;
import com.eneve.agent.jira.JiraService;
import com.eneve.agent.jira.JiraService.JiraIssueDetail;
import com.eneve.agent.model.JiraIssueReview;
import com.eneve.agent.model.JiraReviewRequest;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobType;
import com.eneve.agent.model.ProductConfig;
import com.eneve.agent.model.ScopeItem;
import com.eneve.agent.model.ScopeProposal;
import com.eneve.agent.model.ScopeRecord;
import com.eneve.agent.audit.AuditService;
import com.eneve.agent.settings.SettingsService;
import com.eneve.agent.workspace.WorkspaceContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Business logic for the Scope feature.
 *
 * <h3>Two-phase design</h3>
 * <ol>
 *   <li><b>Sync</b> ({@link #syncScope}) — fetches the complete Jira issue hierarchy
 *       (epics → features → stories) and stores it in {@code scope_items}.
 *       Each item's {@code jira_modified_at} is recorded from the Jira {@code updated}
 *       field.</li>
 *   <li><b>Review</b> ({@link #enqueueReviewAll}) — enqueues AI review jobs for items
 *       in {@code scope_items} that have changed since their last review. Pass
 *       {@code force=true} to re-review everything regardless.</li>
 * </ol>
 */
@ApplicationScoped
public class ScopeService {

    private static final Logger LOG = Logger.getLogger(ScopeService.class);

    @Inject ObjectMapper mapper;

    @Inject ScopeStore scopeStore;
    @Inject ScopeItemStore scopeItemStore;
    @Inject JiraIssueReviewStore reviewStore;
    @Inject ScopeItemOverrideStore overrideStore;
    @Inject JobQueue jobQueue;
    @Inject JobStore jobStore;
    @Inject JiraService jiraService;
    @Inject SettingsService settings;
    @Inject ManagedExecutor managedExecutor;
    @Inject ScopeItemProposalStore proposalStore;
    @Inject JiraReviewContextBuilder contextBuilder;
    @Inject PromptTemplateService promptTemplates;
    @Inject AnthropicClient anthropicClient;
    @Inject ClaudeToolUseLoop toolLoop;
    @Inject CustomerRegistryStore customerRegistryStore;
    @Inject AgroalDataSource dataSource;
    @Inject AuditService auditService;

    @Inject ReviewEpicHandler      reviewEpicHandler;
    @Inject ReviewFeatureHandler   reviewFeatureHandler;
    @Inject ReviewUserStoryHandler reviewUserStoryHandler;

    // ─── Exception types ─────────────────────────────────────────────────────

    public static final class ScopeNotFoundException extends RuntimeException {
        public ScopeNotFoundException(String id) { super("Scope not found: " + id); }
    }

    public static final class ItemOverriddenException extends RuntimeException {
        public ItemOverriddenException(String issueKey) {
            super("Item is overridden (ACCEPTED/REMOVED). Clear override to re-enable reviews: " + issueKey);
        }
    }

    public static final class ActiveJobExistsException extends RuntimeException {
        public ActiveJobExistsException(String issueKey) {
            super("A review job for this item is already active: " + issueKey);
        }
    }

    public static final class JiraIssueNotFoundException extends RuntimeException {
        public JiraIssueNotFoundException(String issueKey) {
            super("Jira issue not found in scope_items (sync first): " + issueKey);
        }
    }

    public static final class ProposalNotFoundException extends RuntimeException {
        public ProposalNotFoundException(String proposalId) {
            super("Proposal not found: " + proposalId);
        }
    }

    public static final class ImprovementGenerationException extends RuntimeException {
        public ImprovementGenerationException(String message) { super(message); }
    }

    // ─── Result records ───────────────────────────────────────────────────────

    public record CreateScopeResult(ScopeRecord scope, int itemsSynced) {}

    /**
     * @param jobsEnqueued  items queued for AI review this call
     * @param jobsSkipped   items skipped due to active job or override
     * @param jobsUnchanged items skipped because Jira was not modified since last review
     */
    public record ReviewAllResult(int jobsEnqueued, int jobsSkipped, int jobsUnchanged) {}

    /** Result of {@link #initProposal}: the existing/created DRAFT, Jira attachments, and the Jira issue's own last-modified timestamp. */
    public record InitProposalResult(ScopeProposal proposal, List<JiraService.JiraAttachment> attachments, java.time.Instant jiraUpdatedAt) {}

    // ─── CRUD ─────────────────────────────────────────────────────────────────

    public List<ScopeRecord> listScopes() {
        return scopeStore.findAll();
    }

    public ScopeRecord getScope(String id) {
        return scopeStore.findById(id)
                .orElseThrow(() -> new ScopeNotFoundException(id));
    }

    /**
     * Creates a new scope and immediately syncs all Jira issues.
     * Issue type names default to the three global settings values when blank.
     *
     * @param labels ordered list of Jira labels for this scope (at least one required)
     */
    public CreateScopeResult createScope(String name, List<String> labels,
                                          String epicIssuetype, String featureIssuetype,
                                          String userstoryIssuetype) {
        String epic    = blankFallback(epicIssuetype,    "roadmap.jira.epic-issuetype",        "Epic");
        String feature = blankFallback(featureIssuetype, "roadmap.jira.feature-issuetype",     "Story");
        String story   = blankFallback(userstoryIssuetype,"roadmap.jira.userstory-issuetype",  "Sub-task");

        ScopeRecord scope = scopeStore.create(name, labels, epic, feature, story);
        int itemsSynced = syncScope(scope.id());
        auditService.log("SCOPE", "SCOPE_CREATED", "scope", scope.id(),
                Map.of("name", name, "labels", labels, "itemsSynced", itemsSynced));
        return new CreateScopeResult(scope, itemsSynced);
    }

    /**
     * Updates name, labels and/or issue-type mappings for an existing scope.
     *
     * @throws ScopeNotFoundException if no scope with {@code id} exists
     */
    public ScopeRecord updateScope(String id, String name, List<String> labels,
                                    String epicIssuetype, String featureIssuetype,
                                    String userstoryIssuetype) {
        ScopeRecord existing = scopeStore.findById(id)
                .orElseThrow(() -> new ScopeNotFoundException(id));

        String epic    = blankFallback(epicIssuetype,    existing.epicIssuetype());
        String feature = blankFallback(featureIssuetype, existing.featureIssuetype());
        String story   = blankFallback(userstoryIssuetype, existing.userstoryIssuetype());

        // Fall back to existing labels when none provided
        List<String> effectiveLabels = (labels != null && !labels.isEmpty()) ? labels : existing.labels();

        scopeStore.update(id, name, effectiveLabels, epic, feature, story);
        ScopeRecord updated = scopeStore.findById(id).orElseThrow(() -> new ScopeNotFoundException(id));
        auditService.log("SCOPE", "SCOPE_UPDATED", "scope", id,
                Map.of("name", name, "labels", effectiveLabels));
        return updated;
    }

    /**
     * Preview: returns matching Jira issues for the given labels without persisting anything.
     * Used by the scope create/edit dialog to show a live preview table.
     *
     * @param labels list of Jira labels to search
     * @return list of maps with keys: issueKey, summary, status
     */
    public List<Map<String, Object>> previewLabels(List<String> labels) {
        if (labels == null || labels.isEmpty()) return List.of();
        List<JiraService.JiraIssueDetail> issues = jiraService.previewIssuesByLabels(labels);
        return issues.stream().map(issue -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("issueKey", issue.key());
            m.put("summary",  issue.summary() != null ? issue.summary() : "");
            m.put("status",   mapStatus(issue.status()));
            return m;
        }).collect(Collectors.toList());
    }

    /**
     * Deletes a scope and cascades to all associated items, reviews, and overrides.
     *
     * @throws ScopeNotFoundException if no scope with {@code id} exists
     */
    public void deleteScope(String id) {
        if (scopeStore.findById(id).isEmpty()) throw new ScopeNotFoundException(id);
        scopeStore.delete(id);
        auditService.log("SCOPE", "SCOPE_DELETED", "scope", id);
    }

    // ─── Sync ─────────────────────────────────────────────────────────────────

    /**
     * Fetches the full Jira issue hierarchy using this scope's configured issue types
     * and atomically replaces the stored items. Each item's {@code jira_modified_at}
     * is set from the Jira {@code updated} timestamp. After replacing items, orphaned
     * reviews and overrides for issue keys no longer in the scope are cleaned up.
     *
     * @return number of items stored
     * @throws ScopeNotFoundException if the scope does not exist
     */
    public int syncScope(String scopeId) {
        ScopeRecord scope = scopeStore.findById(scopeId)
                .orElseThrow(() -> new ScopeNotFoundException(scopeId));

        List<ScopeItem> items = fetchItemsFromJira(scopeId, scope);
        scopeItemStore.replaceAll(scopeId, items);
        cleanupOrphanedData(scopeId);
        LOG.infof("ScopeService.syncScope: stored %d items for scope %s", items.size(), scopeId);
        auditService.log("SCOPE", "SCOPE_SYNCED", "scope", scopeId,
                Map.of("itemsSynced", items.size()));
        return items.size();
    }

    // ─── Tree ─────────────────────────────────────────────────────────────────

    /**
     * Builds the flat tree-item list entirely from the database.
     * No Jira calls are made. Each item includes staleness information:
     * {@code isStale=true} when Jira was modified after the last AI review.
     *
     * @throws ScopeNotFoundException if the scope does not exist
     */
    public List<Map<String, Object>> buildTree(String scopeId) {
        if (scopeStore.findById(scopeId).isEmpty()) throw new ScopeNotFoundException(scopeId);

        int threshold    = Integer.parseInt(settings.get("roadmap.delivery.readiness-threshold", "70"));
        boolean weighted = Boolean.parseBoolean(settings.get("roadmap.delivery.complexity-weight-enabled", "true"));

        List<ScopeItem> allItems  = scopeItemStore.findByScope(scopeId);
        Map<String, JiraIssueReview> reviewMap = reviewStore.findByScope(scopeId).stream()
                .collect(Collectors.toMap(JiraIssueReview::issueKey, r -> r));
        Map<String, String> overrideMap = overrideStore.findByScope(scopeId);

        List<ScopeItem> epics = allItems.stream()
                .filter(i -> "EPIC".equals(i.issueType())).collect(Collectors.toList());
        Map<String, List<ScopeItem>> featuresByEpic = allItems.stream()
                .filter(i -> "FEATURE".equals(i.issueType()))
                .collect(Collectors.groupingBy(i -> i.parentKey() != null ? i.parentKey() : ""));
        Map<String, List<ScopeItem>> storiesByFeature = allItems.stream()
                .filter(i -> "USERSTORY".equals(i.issueType()))
                .collect(Collectors.groupingBy(i -> i.parentKey() != null ? i.parentKey() : ""));

        List<Map<String, Object>> result = new ArrayList<>();

        for (ScopeItem epic : epics) {
            List<int[]> featureAggregates = new ArrayList<>();
            List<ScopeItem> epicFeatures = featuresByEpic.getOrDefault(epic.issueKey(), List.of());

            for (ScopeItem feature : epicFeatures) {
                List<int[]> storyAggregates = new ArrayList<>();
                List<ScopeItem> featureStories = storiesByFeature.getOrDefault(feature.issueKey(), List.of());

                for (ScopeItem story : featureStories) {
                    JiraIssueReview rev = reviewMap.get(story.issueKey());
                    Map<String, Object> storyItem = buildTreeItem(story, rev, overrideMap.get(story.issueKey()));
                    Integer readiness = rev != null ? rev.readinessScore() : null;
                    Integer complexity = rev != null ? rev.complexityScore() : null;
                    if (readiness != null) {
                        storyItem.put("aggregateScore", readiness);
                        storyItem.put("readyForDelivery", readiness >= threshold);
                        storyAggregates.add(new int[]{readiness, complexity != null ? complexity : 0});
                    }
                    result.add(storyItem);
                }

                JiraIssueReview featureRev = reviewMap.get(feature.issueKey());
                Map<String, Object> featureItem = buildTreeItem(feature, featureRev, overrideMap.get(feature.issueKey()));
                // A feature with no user stories is not decomposed and cannot be delivery-ready.
                // A feature whose stories exist but none are reviewed yet yields an unknown aggregate.
                Integer featureAggregate;
                if (featureStories.isEmpty()) {
                    featureAggregate = featureRev != null ? 0 : null;
                } else if (storyAggregates.isEmpty()) {
                    featureAggregate = null;
                } else {
                    featureAggregate = computeAggregate(
                            featureRev != null ? featureRev.readinessScore() : null, storyAggregates, weighted);
                }
                if (featureAggregate != null) {
                    featureItem.put("aggregateScore", featureAggregate);
                    featureItem.put("readyForDelivery", featureAggregate >= threshold);
                    Integer fc = featureRev != null ? featureRev.complexityScore() : null;
                    featureAggregates.add(new int[]{featureAggregate, fc != null ? fc : 0});
                }
                result.add(featureItem);
            }

            JiraIssueReview epicRev = reviewMap.get(epic.issueKey());
            Map<String, Object> epicItem = buildTreeItem(epic, epicRev, overrideMap.get(epic.issueKey()));
            // An epic with no features is not decomposed and cannot be delivery-ready.
            // An epic whose features exist but none have computable aggregates yet yields unknown.
            Integer epicAggregate;
            if (epicFeatures.isEmpty()) {
                epicAggregate = epicRev != null ? 0 : null;
            } else if (featureAggregates.isEmpty()) {
                epicAggregate = null;
            } else {
                epicAggregate = computeAggregate(
                        epicRev != null ? epicRev.readinessScore() : null, featureAggregates, weighted);
            }
            if (epicAggregate != null) {
                epicItem.put("aggregateScore", epicAggregate);
                epicItem.put("readyForDelivery", epicAggregate >= threshold);
            }
            result.add(epicItem);
        }

        return result;
    }

    // ─── Sprint view ──────────────────────────────────────────────────────────

    /**
     * Builds a sprint-grouped view for the Gantt chart.
     * Returns a list of sprint groups, each containing the features and stories
     * assigned to that sprint, ordered by sprint start date.
     * Items without a sprint assignment are excluded from this view.
     *
     * @throws ScopeNotFoundException if the scope does not exist
     */
    public List<Map<String, Object>> buildSprintView(String scopeId) {
        if (scopeStore.findById(scopeId).isEmpty()) throw new ScopeNotFoundException(scopeId);

        List<ScopeItem> sprintItems = scopeItemStore.findSprintItems(scopeId);

        Map<String, List<ScopeItem>> bySprint = new LinkedHashMap<>();
        Map<String, java.time.Instant> sprintStarts = new LinkedHashMap<>();
        Map<String, java.time.Instant> sprintEnds   = new LinkedHashMap<>();

        for (ScopeItem item : sprintItems) {
            String name = item.sprintName();
            bySprint.computeIfAbsent(name, k -> new ArrayList<>()).add(item);
            sprintStarts.putIfAbsent(name, item.sprintStart());
            sprintEnds.putIfAbsent(name, item.sprintEnd());
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, List<ScopeItem>> entry : bySprint.entrySet()) {
            String sprintName = entry.getKey();
            List<Map<String, Object>> itemMaps = entry.getValue().stream()
                    .map(i -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("issueKey",   i.issueKey());
                        m.put("issueType",  i.issueType());
                        m.put("summary",    i.summary() != null ? i.summary() : "");
                        m.put("parentKey",  i.parentKey());
                        m.put("grandparentKey", i.grandparentKey());
                        m.put("jiraStatus", mapStatus(i.jiraStatus()));
                        if (i.assignee() != null) m.put("assignee", i.assignee());
                        if (i.sprintStart() != null) m.put("sprintStart", i.sprintStart());
                        if (i.sprintEnd()   != null) m.put("sprintEnd",   i.sprintEnd());
                        return m;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> group = new LinkedHashMap<>();
            group.put("sprintName",  sprintName);
            group.put("sprintStart", sprintStarts.get(sprintName));
            group.put("sprintEnd",   sprintEnds.get(sprintName));
            group.put("items",       itemMaps);
            result.add(group);
        }
        return result;
    }

    // ─── Live refresh ─────────────────────────────────────────────────────────

    /**
     * Fetches fresh Jira data for a single item, updates its stored live fields,
     * then returns a tree-item map in the same shape as {@link #buildTree}.
     *
     * @throws ScopeNotFoundException     if the scope does not exist
     * @throws JiraIssueNotFoundException if the item is not in scope_items
     */
    public Map<String, Object> refreshItem(String scopeId, String issueKey) {
        if (scopeStore.findById(scopeId).isEmpty()) throw new ScopeNotFoundException(scopeId);

        ScopeItem stored = scopeItemStore.findByScopeAndIssueKey(scopeId, issueKey)
                .orElseThrow(() -> new JiraIssueNotFoundException(issueKey));

        JiraIssueDetail live = jiraService.fetchIssueDetail(issueKey);
        if (live != null) {
            scopeItemStore.refreshLiveFields(
                    scopeId, issueKey,
                    live.summary(), live.status(), live.updatedAt(),
                    live.assignee(), live.reporter(),
                    live.sprintName(), live.sprintStart(), live.sprintEnd());
            stored = scopeItemStore.findByScopeAndIssueKey(scopeId, issueKey)
                    .orElse(stored);
        }

        Map<String, JiraIssueReview> reviewMap = reviewStore.findByScope(scopeId).stream()
                .collect(Collectors.toMap(JiraIssueReview::issueKey, r -> r));
        Map<String, String> overrideMap = overrideStore.findByScope(scopeId);

        Map<String, Object> result = buildTreeItem(stored, reviewMap.get(issueKey), overrideMap.get(issueKey));
        auditService.log("SCOPE", "ITEM_REFRESHED", "scope_item", issueKey,
                Map.of("scopeId", scopeId));
        return result;
    }

    // ─── Reviews ──────────────────────────────────────────────────────────────

    /**
     * Enqueues AI review jobs for items in {@code scope_items}.
     *
     * <p>By default ({@code force=false}) items whose Jira {@code updated} timestamp
     * is not newer than their last review are skipped — they haven't changed and
     * do not need re-reviewing. Pass {@code force=true} to re-review everything.
     *
     * @throws ScopeNotFoundException if the scope does not exist
     */
    public ReviewAllResult enqueueReviewAll(String scopeId, boolean force) {
        if (scopeStore.findById(scopeId).isEmpty()) throw new ScopeNotFoundException(scopeId);

        List<ScopeItem> items = scopeItemStore.findByScope(scopeId);
        if (items.isEmpty()) {
            LOG.infof("ScopeService.enqueueReviewAll: no items synced for scope %s", scopeId);
            return new ReviewAllResult(0, 0, 0);
        }

        Map<String, String> overrideMap = overrideStore.findByScope(scopeId);
        Map<String, JiraIssueReview> reviewMap = reviewStore.findByScope(scopeId).stream()
                .collect(Collectors.toMap(JiraIssueReview::issueKey, r -> r));

        int enqueued = 0, skipped = 0, unchanged = 0;

        for (ScopeItem item : items) {
            if (item.issueKey().startsWith("VIRTUAL-")) {
                skipped++;
                continue;
            }
            if (overrideMap.containsKey(item.issueKey()) || jobStore.hasActiveReviewJob(item.issueKey())) {
                skipped++;
                continue;
            }

            if (!force) {
                JiraIssueReview existingReview = reviewMap.get(item.issueKey());
                if (existingReview != null
                        && existingReview.reviewedAt() != null
                        && item.jiraModifiedAt() != null
                        && !item.jiraModifiedAt().isAfter(existingReview.reviewedAt())) {
                    unchanged++;
                    continue;
                }
            }

            enqueueJob(scopeId, item.issueKey(), resolveJobType(item.issueType()),
                    item.parentKey(), item.grandparentKey());
            enqueued++;
        }
        LOG.infof("ScopeService.enqueueReviewAll: enqueued=%d skipped=%d unchanged=%d scope=%s",
                enqueued, skipped, unchanged, scopeId);
        ReviewAllResult result = new ReviewAllResult(enqueued, skipped, unchanged);
        auditService.log("SCOPE", "REVIEW_ALL_ENQUEUED", "scope", scopeId,
                Map.of("jobsEnqueued", enqueued, "jobsSkipped", skipped, "jobsUnchanged", unchanged));
        return result;
    }

    /**
     * Enqueues a single AI review job for {@code issueKey}.
     * The item must already be present in {@code scope_items} (sync first).
     *
     * @return the new job ID
     */
    public String enqueueReview(String scopeId, String issueKey) {
        if (scopeStore.findById(scopeId).isEmpty()) throw new ScopeNotFoundException(scopeId);
        if (overrideStore.isOverridden(scopeId, issueKey)) throw new ItemOverriddenException(issueKey);
        if (jobStore.hasActiveReviewJob(issueKey)) throw new ActiveJobExistsException(issueKey);

        ScopeItem item = scopeItemStore.findByScopeAndIssueKey(scopeId, issueKey)
                .orElseThrow(() -> new JiraIssueNotFoundException(issueKey));

        String jobId = UUID.randomUUID().toString();
        JobType jobType = resolveJobType(item.issueType());
        JiraReviewRequest req = new JiraReviewRequest(
                scopeId, issueKey, item.issueType(), item.parentKey(), item.grandparentKey());
        JobRecord job = new JobRecord(jobId, req, jobType);
        jobStore.put(job);
        jobQueue.submitReviewJob(job);
        auditService.log("SCOPE", "REVIEW_ENQUEUED", "scope_item", issueKey,
                Map.of("scopeId", scopeId, "jobId", jobId));
        return jobId;
    }

    /**
     * Runs a review synchronously, bypassing the job queue.
     * Returns the persisted {@link JiraIssueReview} immediately — suitable for
     * fire-and-wait background calls from the UI (e.g. after saving a proposal).
     *
     * @throws ScopeNotFoundException     if the scope does not exist
     * @throws JiraIssueNotFoundException if the issue is not found in scope_items
     * @throws RuntimeException           if the Claude call or JSON parsing fails
     */
    public JiraIssueReview reviewItemDirect(String scopeId, String issueKey) {
        if (scopeStore.findById(scopeId).isEmpty()) throw new ScopeNotFoundException(scopeId);

        ScopeItem item = scopeItemStore.findByScopeAndIssueKey(scopeId, issueKey)
                .orElseThrow(() -> new JiraIssueNotFoundException(issueKey));

        JiraReviewRequest req = new JiraReviewRequest(
                scopeId, issueKey, item.issueType(), item.parentKey(), item.grandparentKey());

        var handler = switch (item.issueType()) {
            case "EPIC"    -> reviewEpicHandler;
            case "FEATURE" -> reviewFeatureHandler;
            default        -> reviewUserStoryHandler;
        };

        JiraIssueReview review = handler.runReview(req, null);
        auditService.log("SCOPE", "REVIEW_DIRECT", "scope_item", issueKey,
                Map.of("scopeId", scopeId, "readiness", String.valueOf(review.readinessScore())));
        return review;
    }

    // ─── Overrides ────────────────────────────────────────────────────────────

    public void setOverride(String scopeId, String issueKey, String status, String updatedBy) {
        if (scopeStore.findById(scopeId).isEmpty()) throw new ScopeNotFoundException(scopeId);
        overrideStore.setOverride(scopeId, issueKey, status, updatedBy);
        auditService.log("SCOPE", "OVERRIDE_SET", "scope_item", issueKey,
                Map.of("scopeId", scopeId, "status", status));
    }

    public void clearOverride(String scopeId, String issueKey) {
        if (scopeStore.findById(scopeId).isEmpty()) throw new ScopeNotFoundException(scopeId);
        overrideStore.clearOverride(scopeId, issueKey);
        auditService.log("SCOPE", "OVERRIDE_CLEARED", "scope_item", issueKey,
                Map.of("scopeId", scopeId));
    }

    // ─── AI Proposals ─────────────────────────────────────────────────────────

    /**
     * Generates an AI improvement proposal for the given issue and stores it as DRAFT.
     * Synchronous — call from a JAX-RS endpoint that can tolerate latency.
     *
     * @throws ScopeNotFoundException     if the scope does not exist
     * @throws JiraIssueNotFoundException if the item is not in scope_items
     * @throws ImprovementGenerationException if the AI call fails or returns unparseable JSON
     */
    public ScopeProposal improveItem(String scopeId, String issueKey) {
        if (scopeStore.findById(scopeId).isEmpty()) throw new ScopeNotFoundException(scopeId);
        ScopeItem item = scopeItemStore.findByScopeAndIssueKey(scopeId, issueKey)
                .orElseThrow(() -> new JiraIssueNotFoundException(issueKey));

        String context = buildImproveContext(item);
        String promptKey = "improve-" + item.issueType().toLowerCase();
        String prompt = promptTemplates.resolve(promptKey, Map.of("jira_context", context));

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

    /**
     * Returns all proposals for a given scope + issue key (newest first).
     */
    public List<ScopeProposal> getProposals(String scopeId, String issueKey) {
        if (scopeStore.findById(scopeId).isEmpty()) throw new ScopeNotFoundException(scopeId);
        return proposalStore.findByScopeAndIssueKey(scopeId, issueKey);
    }

    /**
     * Initialises a proposal for the given scope item.
     * <ul>
     *   <li>If a DRAFT already exists → returns it (no Jira call).</li>
     *   <li>Otherwise → fetches Jira issue detail and seeds a new DRAFT with
     *       {@code proposedSummary}, {@code proposedDescription}, {@code proposedLabel}
     *       (first label if any), and {@code proposedPriority}.</li>
     * </ul>
     * Always returns the full list of Jira attachments from the issue (may be empty).
     *
     * @throws ScopeNotFoundException     if the scope does not exist
     * @throws JiraIssueNotFoundException if Jira returns no data for the issue key
     */
    public InitProposalResult initProposal(String scopeId, String issueKey) {
        if (scopeStore.findById(scopeId).isEmpty()) throw new ScopeNotFoundException(scopeId);

        Optional<ScopeProposal> existing = proposalStore.findDraftByScopeAndIssueKey(scopeId, issueKey);
        if (existing.isPresent()) {
            JiraIssueDetail detail = jiraService.fetchIssueDetail(issueKey);
            List<JiraService.JiraAttachment> attachments = detail != null
                    ? detail.attachments() : List.of();
            java.time.Instant jiraUpdatedAt = detail != null ? detail.updatedAt() : null;
            return new InitProposalResult(existing.get(), attachments != null ? attachments : List.of(), jiraUpdatedAt);
        }

        JiraIssueDetail detail = jiraService.fetchIssueDetail(issueKey);
        if (detail == null) throw new JiraIssueNotFoundException(issueKey);

        ScopeItem item = scopeItemStore.findByScopeAndIssueKey(scopeId, issueKey)
                .orElse(null);
        String issueType = item != null ? item.issueType() : "FEATURE";
        String parentKey  = item != null ? item.parentKey()  : null;

        String proposedLabel = (detail.labels() != null && !detail.labels().isEmpty())
                ? detail.labels().get(0) : null;

        ScopeProposal proposal = proposalStore.create(
                scopeId, issueKey, issueType, parentKey,
                detail.summary(),
                detail.description(),
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
     *
     * @param scopeId  scope the epic belongs to
     * @param epicKey  Jira key of the EPIC to analyse
     * @return list of newly created DRAFT proposals (may be empty)
     * @throws ScopeNotFoundException if the scope does not exist
     */
    public List<ScopeProposal> proposeFeaturesForEpic(String scopeId, String epicKey) {
        if (scopeStore.findById(scopeId).isEmpty()) throw new ScopeNotFoundException(scopeId);

        // ── 1. Collect context ──────────────────────────────────────────────
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

        // ── 2. Build prompt ─────────────────────────────────────────────────
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

        // ── 3. Call Claude ──────────────────────────────────────────────────
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
                if (block.isText()) {
                    responseText = block.asText().text().trim();
                    break;
                }
            }
        } catch (Exception e) {
            LOG.errorf("proposeFeaturesForEpic: Claude call failed for %s: %s", epicKey, e.getMessage());
            return List.of();
        }

        if (responseText == null || responseText.isBlank()) return List.of();

        // Strip optional markdown fences the model may still emit
        if (responseText.startsWith("```")) {
            responseText = responseText.replaceAll("(?s)^```[a-z]*\\n?", "").replaceAll("```$", "").trim();
        }

        // ── 4. Parse & create proposals ─────────────────────────────────────
        List<ScopeProposal> created = new ArrayList<>();
        try {
            JsonNode arr = mapper.readTree(responseText);
            if (!arr.isArray()) return List.of();
            for (JsonNode node : arr) {
                String title       = node.path("title").asText(null);
                String description = node.path("description").asText(null);
                if (title == null || title.isBlank()) continue;

                String shortId = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
                String syntheticKey = "NEW-" + shortId;
                ScopeProposal proposal = proposalStore.create(scopeId, syntheticKey, "FEATURE", epicKey,
                        title, description, null, null, null, null, null);
                created.add(proposal);
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
     *
     * @param scopeId    scope the feature belongs to
     * @param featureKey Jira key of the FEATURE to analyse
     * @return list of newly created DRAFT proposals (may be empty)
     * @throws ScopeNotFoundException if the scope does not exist
     */
    public List<ScopeProposal> proposeUserStoriesForFeature(String scopeId, String featureKey) {
        if (scopeStore.findById(scopeId).isEmpty()) throw new ScopeNotFoundException(scopeId);

        // ── 1. Collect context ──────────────────────────────────────────────
        JiraIssueDetail feature = jiraService.fetchIssueDetail(featureKey);
        String featureSummary     = feature != null ? feature.summary()     : featureKey;
        String featureDescription = feature != null ? feature.description() : "";

        // Fetch parent Epic for extra context if available
        ScopeItem featureItem = scopeItemStore.findByScopeAndIssueKey(scopeId, featureKey).orElse(null);
        String epicContext = "";
        if (featureItem != null && featureItem.parentKey() != null) {
            JiraIssueDetail epic = jiraService.fetchIssueDetail(featureItem.parentKey());
            if (epic != null) {
                epicContext = "Parent Epic: " + featureItem.parentKey()
                        + " — " + epic.summary() + "\n\n";
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

        // ── 2. Build prompt ─────────────────────────────────────────────────
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

        // ── 3. Call Claude ──────────────────────────────────────────────────
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
                if (block.isText()) {
                    responseText = block.asText().text().trim();
                    break;
                }
            }
        } catch (Exception e) {
            LOG.errorf("proposeUserStoriesForFeature: Claude call failed for %s: %s", featureKey, e.getMessage());
            return List.of();
        }

        if (responseText == null || responseText.isBlank()) return List.of();

        // Strip optional markdown fences the model may still emit
        if (responseText.startsWith("```")) {
            responseText = responseText.replaceAll("(?s)^```[a-z]*\\n?", "").replaceAll("```$", "").trim();
        }

        // ── 4. Parse & create proposals ─────────────────────────────────────
        List<ScopeProposal> created = new ArrayList<>();
        try {
            JsonNode arr = mapper.readTree(responseText);
            if (!arr.isArray()) return List.of();
            for (JsonNode node : arr) {
                String title       = node.path("title").asText(null);
                String description = node.path("description").asText(null);
                if (title == null || title.isBlank()) continue;

                String shortId = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
                String syntheticKey = "NEW-" + shortId;
                ScopeProposal proposal = proposalStore.create(
                        scopeId, syntheticKey, "USERSTORY", featureKey,
                        title, description, null, null, null, null, null);
                created.add(proposal);
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
     * Creates a blank DRAFT FEATURE proposal that is not yet backed by a Jira issue.
     * A synthetic issue key of the form {@code NEW-XXXXXXXX} is generated.
     * The proposal can be populated by the user and accepted later, at which point a real
     * Jira issue is created.
     *
     * @param scopeId        scope the proposal belongs to
     * @param parentKey      Jira key of the parent EPIC (used to derive the project key on accept)
     * @param proposedSummary optional initial title
     * @throws ScopeNotFoundException if the scope does not exist
     */
    public ScopeProposal createNewFeatureProposal(String scopeId, String parentKey, String proposedSummary) {
        if (scopeStore.findById(scopeId).isEmpty()) throw new ScopeNotFoundException(scopeId);

        String shortId = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String syntheticKey = "NEW-" + shortId;

        ScopeProposal proposal = proposalStore.create(scopeId, syntheticKey, "FEATURE", parentKey,
                proposedSummary, null, null, null, null, null, null);

        auditService.log("SCOPE", "PROPOSAL_CREATED", "scope_item", syntheticKey,
                Map.of("scopeId", scopeId, "source", "manual", "parentKey", parentKey != null ? parentKey : ""));

        return proposal;
    }

    /**
     * Updates the text fields of an existing proposal (allowed at any status).
     *
     * @throws ProposalNotFoundException if the proposal does not exist
     */
    public ScopeProposal updateProposal(String scopeId, String proposalId,
                                         String summary, String description,
                                         String criteria, String technical) {
        return updateProposal(scopeId, proposalId, summary, description, criteria, technical, null, null, null);
    }

    /**
     * Updates all editable fields of an existing proposal, including label and priority.
     *
     * @throws ProposalNotFoundException if the proposal does not exist
     */
    public ScopeProposal updateProposal(String scopeId, String proposalId,
                                         String summary, String description,
                                         String criteria, String technical,
                                         String label, String priority) {
        return updateProposal(scopeId, proposalId, summary, description, criteria, technical, label, priority, null);
    }

    /**
     * Updates all editable fields of an existing proposal, recording who made the change.
     *
     * @throws ProposalNotFoundException if the proposal does not exist
     */
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
     * Accepts a proposal:
     * <ul>
     *   <li>EPIC — updates the existing Jira Epic in place</li>
     *   <li>FEATURE / USERSTORY — creates a new Jira issue as a child of the parent</li>
     * </ul>
     * Marks the proposal ACCEPTED and stores the resulting Jira key.
     *
     * @throws ProposalNotFoundException if the proposal does not exist
     * @throws ImprovementGenerationException if the Jira write fails
     */
    public ScopeProposal acceptProposal(String scopeId, String proposalId) {
        return acceptProposal(scopeId, proposalId, null);
    }

    public ScopeProposal acceptProposal(String scopeId, String proposalId, String syncedBy) {
        ScopeProposal proposal = proposalStore.findById(proposalId)
                .orElseThrow(() -> new ProposalNotFoundException(proposalId));

        List<String> labels = (proposal.proposedLabel() != null && !proposal.proposedLabel().isBlank())
                ? List.of(proposal.proposedLabel()) : null;
        String priority = proposal.proposedPriority();

        String jiraResultKey;
        if ("EPIC".equals(proposal.issueType())) {
            jiraService.updateIssueSystem(
                    proposal.issueKey(),
                    proposal.proposedSummary(),
                    proposal.proposedDescription(),
                    labels, priority);
            jiraResultKey = proposal.issueKey();
        } else {
            // Prefer parentKey for project key derivation so that synthetic NEW-* keys work correctly
            String rawKey = (proposal.parentKey() != null && !proposal.parentKey().isBlank())
                    ? proposal.parentKey() : proposal.issueKey();
            String projectKey = rawKey.replaceAll("-\\d+$", "");
            ScopeRecord scope = scopeStore.findById(scopeId)
                    .orElseThrow(() -> new ScopeNotFoundException(scopeId));
            String issueType = "FEATURE".equals(proposal.issueType())
                    ? scope.featureIssuetype()
                    : scope.userstoryIssuetype();
            jiraResultKey = jiraService.createIssueSystem(
                    projectKey,
                    proposal.proposedSummary(),
                    proposal.proposedDescription(),
                    issueType,
                    proposal.parentKey(),
                    labels != null ? labels : List.of(),
                    null,
                    priority);
        }

        if (jiraResultKey == null) {
            throw new ImprovementGenerationException(
                    "Jira write failed for proposal " + proposalId + " — check system Jira credentials");
        }

        proposalStore.updateStatus(proposalId, "ACCEPTED", jiraResultKey, syncedBy);
        ScopeProposal accepted = proposalStore.findById(proposalId).orElse(proposal);
        auditService.log("SCOPE", "PROPOSAL_ACCEPTED", "scope_proposal", proposalId,
                Map.of("scopeId", scopeId));
        return accepted;
    }

    /**
     * Soft-rejects a proposal (marks REJECTED, keeps the row for reference).
     *
     * @throws ProposalNotFoundException if the proposal does not exist
     */
    public ScopeProposal rejectProposal(String scopeId, String proposalId) {
        ScopeProposal proposal = proposalStore.findById(proposalId)
                .orElseThrow(() -> new ProposalNotFoundException(proposalId));
        proposalStore.updateStatus(proposalId, "REJECTED", null, null);
        ScopeProposal rejected = proposalStore.findById(proposalId).orElse(proposal);
        auditService.log("SCOPE", "PROPOSAL_REJECTED", "scope_proposal", proposalId,
                Map.of("scopeId", scopeId));
        return rejected;
    }

    /**
     * Hard-deletes a proposal. Allowed at any status.
     *
     * @throws ProposalNotFoundException if the proposal does not exist
     */
    public void deleteProposal(String scopeId, String proposalId) {
        if (proposalStore.findById(proposalId).isEmpty()) throw new ProposalNotFoundException(proposalId);
        proposalStore.delete(proposalId);
        auditService.log("SCOPE", "PROPOSAL_DELETED", "scope_proposal", proposalId,
                Map.of("scopeId", scopeId));
    }

    // ─── Product links ────────────────────────────────────────────────────────

    public List<ProductConfig> listLinkedProducts(String scopeId) {
        if (scopeStore.findById(scopeId).isEmpty()) throw new ScopeNotFoundException(scopeId);
        return scopeStore.listLinkedProductIds(scopeId).stream()
                .map(pid -> customerRegistryStore.getProduct(pid).orElse(null))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
    }

    public void linkProduct(String scopeId, String productId) {
        if (scopeStore.findById(scopeId).isEmpty()) throw new ScopeNotFoundException(scopeId);
        scopeStore.linkProduct(scopeId, productId);
        auditService.log("SCOPE", "PRODUCT_LINKED", "scope", scopeId,
                Map.of("productId", productId));
    }

    public void unlinkProduct(String scopeId, String productId) {
        if (scopeStore.findById(scopeId).isEmpty()) throw new ScopeNotFoundException(scopeId);
        scopeStore.unlinkProduct(scopeId, productId);
        auditService.log("SCOPE", "PRODUCT_UNLINKED", "scope", scopeId,
                Map.of("productId", productId));
    }

    public long countActiveReviewJobs(String scopeId) {
        return jobStore.countActiveReviewJobsForRoadmap(scopeId);
    }

    /**
     * Fetches raw bytes of a Jira attachment given its content URL.
     * Used by the attachment proxy endpoint to stream files to the browser.
     *
     * @return raw bytes, or {@code null} if the fetch failed
     */
    public byte[] fetchJiraAttachmentBytes(String contentUrl) {
        return jiraService.fetchAttachmentBytes(contentUrl);
    }

    /**
     * Returns the list of attachments for a real Jira issue.
     * Used by the Attachments tab in the Improve UI (lazy-loaded on demand).
     *
     * @return attachment metadata list, never {@code null}
     */
    public List<JiraService.JiraAttachment> fetchAttachmentsForIssue(String issueKey) {
        JiraService.JiraIssueDetail detail = jiraService.fetchIssueDetail(issueKey);
        if (detail == null || detail.attachments() == null) return List.of();
        return detail.attachments();
    }

    // ─── Token stats ─────────────────────────────────────────────────────────

    /**
     * Returns average input/output token counts per scope review job type,
     * computed from the {@code ai_calls} ledger.
     */
    public Map<String, Object> getReviewTokenStats() {
        String sql = """
                SELECT job_type,
                       ROUND(AVG(input_tokens))  AS avg_input,
                       ROUND(AVG(output_tokens)) AS avg_output,
                       COUNT(*)                  AS sample_count
                FROM ai_calls
                WHERE job_type IN ('REVIEW_EPIC','REVIEW_FEATURE','REVIEW_USERSTORY')
                  AND is_error = false
                GROUP BY job_type
                """;
        Map<String, Object> result = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Long> stats = new LinkedHashMap<>();
                stats.put("avgInputTokens",  rs.getLong("avg_input"));
                stats.put("avgOutputTokens", rs.getLong("avg_output"));
                stats.put("sampleCount",     rs.getLong("sample_count"));
                result.put(rs.getString("job_type"), stats);
            }
        } catch (Exception e) {
            LOG.warnf("Failed to query review token stats: %s", e.getMessage());
        }
        return result;
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    /**
     * Fetches epics → features → stories in parallel using the scope's configured issue types.
     *
     * <h3>Two-pass strategy</h3>
     * <ol>
     *   <li>Top-down: fetch all epics matching any of the scope's labels, then fetch their
     *       child features and grandchild stories.</li>
     *   <li>Bottom-up: fetch ALL features matching any label directly (not via their epic).
     *       Any feature whose epic key was NOT already fetched in pass 1 is grouped under a
     *       synthetic "VIRTUAL-{label}" epic so the tree remains consistent in the UI.</li>
     * </ol>
     */
    private List<ScopeItem> fetchItemsFromJira(String scopeId, ScopeRecord scope) {
        List<String> labels = scope.labels();
        if (labels == null || labels.isEmpty()) return List.of();

        // ── Pass 1: top-down from epics ──────────────────────────────────────

        List<JiraService.JiraIssueDetail> epics =
                jiraService.searchEpicsByLabels(labels, scope.epicIssuetype());

        Set<String> epicKeys = new HashSet<>();
        for (JiraService.JiraIssueDetail e : epics) epicKeys.add(e.key());

        List<CompletableFuture<List<JiraService.JiraIssueDetail>>> featureFutures = epics.stream()
                .map(e -> CompletableFuture.supplyAsync(
                        () -> jiraService.searchFeaturesForEpic(e.key(), scope.featureIssuetype()),
                        managedExecutor))
                .collect(Collectors.toList());
        List<List<JiraService.JiraIssueDetail>> featuresByEpic = featureFutures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());

        List<JiraService.JiraIssueDetail> allEpicFeatures = featuresByEpic.stream()
                .flatMap(Collection::stream).collect(Collectors.toList());
        Set<String> epicFeatureKeys = allEpicFeatures.stream()
                .map(JiraService.JiraIssueDetail::key).collect(Collectors.toSet());

        List<CompletableFuture<List<JiraService.JiraIssueDetail>>> storyFutures = allEpicFeatures.stream()
                .map(f -> CompletableFuture.supplyAsync(
                        () -> jiraService.searchStoriesForFeature(f.key(), scope.userstoryIssuetype()),
                        managedExecutor))
                .collect(Collectors.toList());
        List<List<JiraService.JiraIssueDetail>> storiesByFeature = storyFutures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());

        // ── Pass 2: direct-label features (unparented / technical work) ───────

        List<JiraService.JiraIssueDetail> allLabelFeatures =
                jiraService.searchFeaturesByLabels(labels, scope.featureIssuetype());

        // Features not already found under an epic → group under virtual epic
        List<JiraService.JiraIssueDetail> unparentedFeatures = allLabelFeatures.stream()
                .filter(f -> !epicFeatureKeys.contains(f.key()))
                .collect(Collectors.toList());

        // Group unparented features by their Jira parentKey (if any) or by a single
        // virtual epic per scope (fallback for truly unparented issues)
        Map<String, List<JiraService.JiraIssueDetail>> unparentedByVirtualEpic = new LinkedHashMap<>();
        for (JiraService.JiraIssueDetail f : unparentedFeatures) {
            // If the feature has a parentKey that is NOT in our epic list, it belongs to
            // a different epic hierarchy — still inject a virtual epic grouping.
            String virtualKey = "VIRTUAL-" + scopeId.substring(0, 8);
            unparentedByVirtualEpic.computeIfAbsent(virtualKey, k -> new ArrayList<>()).add(f);
        }

        // ── Build item list ──────────────────────────────────────────────────

        List<ScopeItem> items = new ArrayList<>();

        // Epics + their features + stories
        for (int ei = 0; ei < epics.size(); ei++) {
            JiraService.JiraIssueDetail epic = epics.get(ei);
            items.add(new ScopeItem(null, scopeId, epic.key(), "EPIC",
                    null, null, epic.summary(), epic.status(), null, epic.updatedAt(),
                    epic.assignee(), epic.reporter(), null, null, null));

            List<JiraService.JiraIssueDetail> features = featuresByEpic.get(ei);
            for (JiraService.JiraIssueDetail feature : features) {
                items.add(new ScopeItem(null, scopeId, feature.key(), "FEATURE",
                        epic.key(), null, feature.summary(), feature.status(), null, feature.updatedAt(),
                        feature.assignee(), feature.reporter(),
                        feature.sprintName(), feature.sprintStart(), feature.sprintEnd()));

                int featureIdx = allEpicFeatures.indexOf(feature);
                List<JiraService.JiraIssueDetail> stories = featureIdx >= 0
                        ? storiesByFeature.get(featureIdx) : List.of();
                for (JiraService.JiraIssueDetail story : stories) {
                    items.add(new ScopeItem(null, scopeId, story.key(), "USERSTORY",
                            feature.key(), epic.key(), story.summary(), story.status(), null, story.updatedAt(),
                            story.assignee(), story.reporter(),
                            story.sprintName(), story.sprintStart(), story.sprintEnd()));
                }
            }
        }

        // Virtual epics + unparented features
        for (Map.Entry<String, List<JiraService.JiraIssueDetail>> entry : unparentedByVirtualEpic.entrySet()) {
            String virtualEpicKey = entry.getKey();
            List<JiraService.JiraIssueDetail> orphanedFeatures = entry.getValue();
            if (orphanedFeatures.isEmpty()) continue;

            // Inject virtual epic row
            items.add(new ScopeItem(null, scopeId, virtualEpicKey, "EPIC",
                    null, null, "Technical / Unparented Work", null, null, null,
                    null, null, null, null, null));

            // Fetch stories for each unparented feature in parallel
            List<CompletableFuture<List<JiraService.JiraIssueDetail>>> orphanStoryFutures = orphanedFeatures.stream()
                    .map(f -> CompletableFuture.supplyAsync(
                            () -> jiraService.searchStoriesForFeature(f.key(), scope.userstoryIssuetype()),
                            managedExecutor))
                    .collect(Collectors.toList());
            List<List<JiraService.JiraIssueDetail>> orphanStoriesByFeature = orphanStoryFutures.stream()
                    .map(CompletableFuture::join)
                    .collect(Collectors.toList());

            for (int fi = 0; fi < orphanedFeatures.size(); fi++) {
                JiraService.JiraIssueDetail feature = orphanedFeatures.get(fi);
                items.add(new ScopeItem(null, scopeId, feature.key(), "FEATURE",
                        virtualEpicKey, null, feature.summary(), feature.status(), null, feature.updatedAt(),
                        feature.assignee(), feature.reporter(),
                        feature.sprintName(), feature.sprintStart(), feature.sprintEnd()));

                for (JiraService.JiraIssueDetail story : orphanStoriesByFeature.get(fi)) {
                    items.add(new ScopeItem(null, scopeId, story.key(), "USERSTORY",
                            feature.key(), virtualEpicKey, story.summary(), story.status(), null, story.updatedAt(),
                            story.assignee(), story.reporter(),
                            story.sprintName(), story.sprintStart(), story.sprintEnd()));
                }
            }
        }

        return items;
    }

    /**
     * Removes orphaned reviews and overrides for issue keys that are no longer
     * present in scope_items for this scope, after a sync.
     */
    private void cleanupOrphanedData(String scopeId) {
        String cleanReviews = """
                DELETE FROM jira_issue_reviews
                WHERE scope_id = ?::uuid
                  AND issue_key NOT IN (
                      SELECT issue_key FROM scope_items WHERE scope_id = ?::uuid
                  )
                """;
        String cleanOverrides = """
                DELETE FROM scope_item_overrides
                WHERE scope_id = ?::uuid
                  AND issue_key NOT IN (
                      SELECT issue_key FROM scope_items WHERE scope_id = ?::uuid
                  )
                """;
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(cleanReviews)) {
                ps.setString(1, scopeId);
                ps.setString(2, scopeId);
                int deleted = ps.executeUpdate();
                if (deleted > 0) LOG.infof("ScopeService.cleanupOrphanedData: removed %d orphaned reviews for scope %s", deleted, scopeId);
            }
            try (PreparedStatement ps = conn.prepareStatement(cleanOverrides)) {
                ps.setString(1, scopeId);
                ps.setString(2, scopeId);
                int deleted = ps.executeUpdate();
                if (deleted > 0) LOG.infof("ScopeService.cleanupOrphanedData: removed %d orphaned overrides for scope %s", deleted, scopeId);
            }
        } catch (Exception e) {
            LOG.warnf("ScopeService.cleanupOrphanedData: failed for scope %s: %s", scopeId, e.getMessage());
        }
    }

    private Map<String, Object> buildTreeItem(ScopeItem item, JiraIssueReview review,
                                               String overrideStatus) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("issueKey",  item.issueKey());
        out.put("issueType", item.issueType());
        out.put("summary",   item.summary() != null ? item.summary() : "");
        if (item.parentKey() != null)      out.put("parentKey",      item.parentKey());
        if (item.grandparentKey() != null) out.put("grandparentKey", item.grandparentKey());
        out.put("jiraStatus", mapStatus(item.jiraStatus()));
        if (item.jiraModifiedAt() != null) out.put("jiraModifiedAt", item.jiraModifiedAt());
        if (item.assignee()       != null) out.put("assignee",       item.assignee());
        if (item.reporter()       != null) out.put("reporter",       item.reporter());
        if (item.sprintName()     != null) out.put("sprintName",     item.sprintName());
        if (item.sprintStart()    != null) out.put("sprintStart",    item.sprintStart());
        if (item.sprintEnd()      != null) out.put("sprintEnd",      item.sprintEnd());

        if (review != null) {
            out.put("readinessScore",     review.readinessScore());
            out.put("readinessLabel",     review.readinessLabel());
            out.put("complexityScore",    review.complexityScore());
            out.put("improvementSummary", review.improvementSummary());
            out.put("reviewedAt",         review.reviewedAt());

            if (item.jiraModifiedAt() != null && review.reviewedAt() != null) {
                out.put("isStale", item.jiraModifiedAt().isAfter(review.reviewedAt()));
            }
        }

        if (overrideStatus != null) out.put("overrideStatus", overrideStatus);
        if (item.issueKey().startsWith("VIRTUAL-")) out.put("isVirtual", true);
        return out;
    }

    private String mapStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String lower = raw.trim().toLowerCase();
        if (matchesCsv(lower, settings.get("roadmap.jira.status-map.closed",     "Done,Closed,Resolved"))) return "Closed";
        if (matchesCsv(lower, settings.get("roadmap.jira.status-map.qa",         "In Review,QA,Testing"))) return "QA";
        if (matchesCsv(lower, settings.get("roadmap.jira.status-map.in-progress","In Progress")))          return "In Progress";
        if (matchesCsv(lower, settings.get("roadmap.jira.status-map.new",        "To Do,Open,New")))       return "New";
        return raw;
    }

    private static boolean matchesCsv(String lower, String csv) {
        if (csv == null) return false;
        for (String token : csv.split(",")) {
            if (lower.equals(token.trim().toLowerCase())) return true;
        }
        return false;
    }

    Integer computeAggregate(Integer ownScore, List<int[]> childScores, boolean weightEnabled) {
        if (childScores.isEmpty()) return ownScore;
        if (weightEnabled) {
            long totalWeight = childScores.stream().mapToLong(a -> a[1]).sum();
            if (totalWeight == 0) {
                return (int) Math.round(childScores.stream().mapToInt(a -> a[0]).average().orElse(0));
            }
            double weightedSum = childScores.stream().mapToDouble(a -> (double) a[0] * a[1]).sum();
            return (int) Math.round(weightedSum / totalWeight);
        }
        return (int) Math.round(childScores.stream().mapToInt(a -> a[0]).average().orElse(0));
    }

    private static JobType resolveJobType(String issueType) {
        return switch (issueType) {
            case "EPIC"    -> JobType.REVIEW_EPIC;
            case "FEATURE" -> JobType.REVIEW_FEATURE;
            default        -> JobType.REVIEW_USERSTORY;
        };
    }

    private void enqueueJob(String scopeId, String issueKey, JobType jobType,
                             String parentKey, String grandparentKey) {
        String jobId = UUID.randomUUID().toString();
        JiraReviewRequest req = new JiraReviewRequest(
                scopeId, issueKey, issueType(jobType), parentKey, grandparentKey);
        JobRecord job = new JobRecord(jobId, req, jobType);
        jobStore.put(job);
        jobQueue.submitReviewJob(job);
    }

    private static String issueType(JobType jt) {
        return jt.name().replace("REVIEW_", "");
    }

    private String blankFallback(String value, String settingKey, String hardDefault) {
        if (value != null && !value.isBlank()) return value.trim();
        String fromSettings = settings.get(settingKey, hardDefault);
        return fromSettings != null && !fromSettings.isBlank() ? fromSettings : hardDefault;
    }

    private static String blankFallback(String value, String fallback) {
        return (value != null && !value.isBlank()) ? value.trim() : fallback;
    }

    private String buildImproveContext(ScopeItem item) {
        return switch (item.issueType()) {
            case "EPIC"      -> contextBuilder.buildEpicContext(item.issueKey());
            case "FEATURE"   -> contextBuilder.buildFeatureContext(item.issueKey(), item.parentKey());
            default          -> contextBuilder.buildUserStoryContext(item.issueKey(), item.parentKey(), item.grandparentKey());
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
            LOG.errorf("ScopeService.callClaudeForProposal: Claude call failed for %s: %s",
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
            LOG.warnf("ScopeService.callClaudeWithTools: could not create workspace, falling back to simple call: %s",
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
            int nl = s.indexOf('\n');
            int end = s.lastIndexOf("```");
            if (nl > 0 && end > nl) s = s.substring(nl + 1, end).strip();
        }
        return s;
    }
}
