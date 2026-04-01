package com.eneve.agent.scope;

import com.eneve.agent.agent.JobQueue;
import com.eneve.agent.agent.handlers.ReviewEpicHandler;
import com.eneve.agent.agent.handlers.ReviewFeatureHandler;
import com.eneve.agent.agent.handlers.ReviewUserStoryHandler;
import com.eneve.agent.agent.store.JiraIssueReviewStore;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.agent.store.ScopeItemOverrideStore;
import com.eneve.agent.agent.store.ScopeItemStore;
import com.eneve.agent.agent.store.ScopeStore;
import com.eneve.agent.audit.AuditService;
import com.eneve.agent.jira.JiraService;
import com.eneve.agent.model.JiraIssueReview;
import com.eneve.agent.model.JiraReviewRequest;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobType;
import com.eneve.agent.model.ScopeItem;
import com.eneve.agent.scope.ScopeExceptions.*;
import com.eneve.agent.settings.SettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Handles scope evaluation: tree/sprint views, AI review queue, overrides, and item refresh.
 * Corresponds to the {@code ScopeDetail.tsx} screen.
 */
@ApplicationScoped
public class ScopeEvaluationService {

    private static final Logger LOG = Logger.getLogger(ScopeEvaluationService.class);

    @Inject ScopeStore scopeStore;
    @Inject ScopeItemStore scopeItemStore;
    @Inject JiraIssueReviewStore reviewStore;
    @Inject ScopeItemOverrideStore overrideStore;
    @Inject JobStore jobStore;
    @Inject JobQueue jobQueue;
    @Inject JiraService jiraService;
    @Inject AuditService auditService;
    @Inject SettingsService settings;
    @Inject ReviewEpicHandler reviewEpicHandler;
    @Inject ReviewFeatureHandler reviewFeatureHandler;
    @Inject ReviewUserStoryHandler reviewUserStoryHandler;

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
                        m.put("issueKey",       i.issueKey());
                        m.put("issueType",      i.issueType());
                        m.put("summary",        i.summary() != null ? i.summary() : "");
                        m.put("parentKey",      i.parentKey());
                        m.put("grandparentKey", i.grandparentKey());
                        m.put("jiraStatus",     mapStatus(i.jiraStatus()));
                        if (i.assignee()    != null) m.put("assignee",    i.assignee());
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
     * Refreshes a single item's live fields from Jira and returns the updated tree-item map.
     *
     * @throws ScopeNotFoundException     if the scope does not exist
     * @throws JiraIssueNotFoundException if the item is not in scope_items
     */
    public Map<String, Object> refreshItem(String scopeId, String issueKey) {
        if (scopeStore.findById(scopeId).isEmpty()) throw new ScopeNotFoundException(scopeId);

        ScopeItem stored = scopeItemStore.findByScopeAndIssueKey(scopeId, issueKey)
                .orElseThrow(() -> new JiraIssueNotFoundException(issueKey));

        JiraService.JiraIssueDetail live = jiraService.fetchIssueDetail(issueKey);
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

    public ReviewAllResult enqueueReviewAll(String scopeId, boolean force) {
        if (scopeStore.findById(scopeId).isEmpty()) throw new ScopeNotFoundException(scopeId);

        List<ScopeItem> items = scopeItemStore.findByScope(scopeId);
        if (items.isEmpty()) {
            LOG.infof("ScopeEvaluationService.enqueueReviewAll: no items synced for scope %s", scopeId);
            return new ReviewAllResult(0, 0, 0);
        }

        Map<String, String> overrideMap = overrideStore.findByScope(scopeId);
        Map<String, JiraIssueReview> reviewMap = reviewStore.findByScope(scopeId).stream()
                .collect(Collectors.toMap(JiraIssueReview::issueKey, r -> r));

        int enqueued = 0, skipped = 0, unchanged = 0;

        for (ScopeItem item : items) {
            if (item.issueKey().startsWith("VIRTUAL-")) { skipped++; continue; }
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
        LOG.infof("ScopeEvaluationService.enqueueReviewAll: enqueued=%d skipped=%d unchanged=%d scope=%s",
                enqueued, skipped, unchanged, scopeId);
        ReviewAllResult result = new ReviewAllResult(enqueued, skipped, unchanged);
        auditService.log("SCOPE", "REVIEW_ALL_ENQUEUED", "scope", scopeId,
                Map.of("jobsEnqueued", enqueued, "jobsSkipped", skipped, "jobsUnchanged", unchanged));
        return result;
    }

    /**
     * Enqueues a single AI review job for {@code issueKey}.
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
     * Returns the persisted {@link JiraIssueReview} immediately.
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

    public long countActiveReviewJobs(String scopeId) {
        return jobStore.countActiveReviewJobsForRoadmap(scopeId);
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

    // ─── Private helpers ──────────────────────────────────────────────────────

    public Map<String, Object> buildTreeItem(ScopeItem item, JiraIssueReview review,
                                              String overrideStatus) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("issueKey",  item.issueKey());
        out.put("issueType", item.issueType());
        out.put("summary",   item.summary() != null ? item.summary() : "");
        if (item.parentKey()      != null) out.put("parentKey",      item.parentKey());
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

    public String mapStatus(String raw) {
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

    /**
     * Computes an aggregate score that blends the parent item's own writing-quality score
     * with the (optionally complexity-weighted) average of its children's aggregate scores.
     *
     * <p>Formula when children are present:
     * <pre>  aggregate = 0.40 * ownScore + 0.60 * childAvg</pre>
     */
    Integer computeAggregate(Integer ownScore, List<int[]> childScores, boolean weightEnabled) {
        if (childScores.isEmpty()) return ownScore;

        double childAvg;
        if (weightEnabled) {
            long totalWeight = childScores.stream().mapToLong(a -> a[1]).sum();
            childAvg = totalWeight == 0
                    ? childScores.stream().mapToInt(a -> a[0]).average().orElse(0)
                    : childScores.stream().mapToDouble(a -> (double) a[0] * a[1]).sum() / totalWeight;
        } else {
            childAvg = childScores.stream().mapToInt(a -> a[0]).average().orElse(0);
        }

        if (ownScore == null) return (int) Math.round(childAvg);
        return (int) Math.round(0.40 * ownScore + 0.60 * childAvg);
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
}
