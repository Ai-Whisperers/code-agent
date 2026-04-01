package com.eneve.agent.scope;

import com.eneve.agent.agent.store.CustomerRegistryStore;
import com.eneve.agent.agent.store.ScopeItemStore;
import com.eneve.agent.agent.store.ScopeStore;
import com.eneve.agent.audit.AuditService;
import com.eneve.agent.jira.JiraService;
import com.eneve.agent.model.ProductConfig;
import com.eneve.agent.model.ScopeRecord;
import com.eneve.agent.scope.ScopeExceptions.*;
import com.eneve.agent.settings.SettingsService;
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
 * Manages scope lifecycle: CRUD, Jira sync, product links, attachments, and token stats.
 * Corresponds to the {@code Scopes.tsx} screen.
 */
@ApplicationScoped
public class ScopeManagementService {

    private static final Logger LOG = Logger.getLogger(ScopeManagementService.class);

    @Inject ScopeStore scopeStore;
    @Inject ScopeItemStore scopeItemStore;
    @Inject CustomerRegistryStore customerRegistryStore;
    @Inject JiraService jiraService;
    @Inject AuditService auditService;
    @Inject SettingsService settings;
    @Inject AgroalDataSource dataSource;
    @Inject ManagedExecutor managedExecutor;

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
        String epic    = blankFallback(epicIssuetype,     "roadmap.jira.epic-issuetype",       "Epic");
        String feature = blankFallback(featureIssuetype,  "roadmap.jira.feature-issuetype",    "Story");
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
     * and atomically replaces the stored items. After replacing items, orphaned
     * reviews and overrides for issue keys no longer in the scope are cleaned up.
     *
     * @return number of items stored
     * @throws ScopeNotFoundException if the scope does not exist
     */
    public int syncScope(String scopeId) {
        ScopeRecord scope = scopeStore.findById(scopeId)
                .orElseThrow(() -> new ScopeNotFoundException(scopeId));

        List<com.eneve.agent.model.ScopeItem> items = fetchItemsFromJira(scopeId, scope);
        scopeItemStore.replaceAll(scopeId, items);
        cleanupOrphanedData(scopeId);
        LOG.infof("ScopeManagementService.syncScope: stored %d items for scope %s", items.size(), scopeId);
        auditService.log("SCOPE", "SCOPE_SYNCED", "scope", scopeId,
                Map.of("itemsSynced", items.size()));
        return items.size();
    }

    // ─── Products ─────────────────────────────────────────────────────────────

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

    // ─── Attachments ──────────────────────────────────────────────────────────

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

    // ─── Token stats ──────────────────────────────────────────────────────────

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
    private List<com.eneve.agent.model.ScopeItem> fetchItemsFromJira(String scopeId, ScopeRecord scope) {
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

        List<JiraService.JiraIssueDetail> unparentedFeatures = allLabelFeatures.stream()
                .filter(f -> !epicFeatureKeys.contains(f.key()))
                .collect(Collectors.toList());

        Map<String, List<JiraService.JiraIssueDetail>> unparentedByVirtualEpic = new LinkedHashMap<>();
        for (JiraService.JiraIssueDetail f : unparentedFeatures) {
            String virtualKey = "VIRTUAL-" + scopeId.substring(0, 8);
            unparentedByVirtualEpic.computeIfAbsent(virtualKey, k -> new ArrayList<>()).add(f);
        }

        // ── Build item list ──────────────────────────────────────────────────

        List<com.eneve.agent.model.ScopeItem> items = new ArrayList<>();

        for (int ei = 0; ei < epics.size(); ei++) {
            JiraService.JiraIssueDetail epic = epics.get(ei);
            items.add(new com.eneve.agent.model.ScopeItem(null, scopeId, epic.key(), "EPIC",
                    null, null, epic.summary(), epic.status(), null, epic.updatedAt(),
                    epic.assignee(), epic.reporter(), null, null, null));

            List<JiraService.JiraIssueDetail> features = featuresByEpic.get(ei);
            for (JiraService.JiraIssueDetail feature : features) {
                items.add(new com.eneve.agent.model.ScopeItem(null, scopeId, feature.key(), "FEATURE",
                        epic.key(), null, feature.summary(), feature.status(), null, feature.updatedAt(),
                        feature.assignee(), feature.reporter(),
                        feature.sprintName(), feature.sprintStart(), feature.sprintEnd()));

                int featureIdx = allEpicFeatures.indexOf(feature);
                List<JiraService.JiraIssueDetail> stories = featureIdx >= 0
                        ? storiesByFeature.get(featureIdx) : List.of();
                for (JiraService.JiraIssueDetail story : stories) {
                    items.add(new com.eneve.agent.model.ScopeItem(null, scopeId, story.key(), "USERSTORY",
                            feature.key(), epic.key(), story.summary(), story.status(), null, story.updatedAt(),
                            story.assignee(), story.reporter(),
                            story.sprintName(), story.sprintStart(), story.sprintEnd()));
                }
            }
        }

        for (Map.Entry<String, List<JiraService.JiraIssueDetail>> entry : unparentedByVirtualEpic.entrySet()) {
            String virtualEpicKey = entry.getKey();
            List<JiraService.JiraIssueDetail> orphanedFeatures = entry.getValue();
            if (orphanedFeatures.isEmpty()) continue;

            items.add(new com.eneve.agent.model.ScopeItem(null, scopeId, virtualEpicKey, "EPIC",
                    null, null, "Technical / Unparented Work", null, null, null,
                    null, null, null, null, null));

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
                items.add(new com.eneve.agent.model.ScopeItem(null, scopeId, feature.key(), "FEATURE",
                        virtualEpicKey, null, feature.summary(), feature.status(), null, feature.updatedAt(),
                        feature.assignee(), feature.reporter(),
                        feature.sprintName(), feature.sprintStart(), feature.sprintEnd()));

                for (JiraService.JiraIssueDetail story : orphanStoriesByFeature.get(fi)) {
                    items.add(new com.eneve.agent.model.ScopeItem(null, scopeId, story.key(), "USERSTORY",
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
                if (deleted > 0) LOG.infof("ScopeManagementService.cleanupOrphanedData: removed %d orphaned reviews for scope %s", deleted, scopeId);
            }
            try (PreparedStatement ps = conn.prepareStatement(cleanOverrides)) {
                ps.setString(1, scopeId);
                ps.setString(2, scopeId);
                int deleted = ps.executeUpdate();
                if (deleted > 0) LOG.infof("ScopeManagementService.cleanupOrphanedData: removed %d orphaned overrides for scope %s", deleted, scopeId);
            }
        } catch (Exception e) {
            LOG.warnf("ScopeManagementService.cleanupOrphanedData: failed for scope %s: %s", scopeId, e.getMessage());
        }
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

    private String blankFallback(String value, String settingKey, String hardDefault) {
        if (value != null && !value.isBlank()) return value.trim();
        String fromSettings = settings.get(settingKey, hardDefault);
        return fromSettings != null && !fromSettings.isBlank() ? fromSettings : hardDefault;
    }

    private static String blankFallback(String value, String fallback) {
        return (value != null && !value.isBlank()) ? value.trim() : fallback;
    }
}
