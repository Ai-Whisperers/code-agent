package com.eneve.agent.roadmap;

import com.eneve.agent.agent.JobQueue;
import com.eneve.agent.agent.store.JiraIssueReviewStore;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.agent.store.RoadmapItemOverrideStore;
import com.eneve.agent.agent.store.RoadmapItemStore;
import com.eneve.agent.agent.store.RoadmapStore;
import com.eneve.agent.jira.JiraService;
import com.eneve.agent.jira.JiraService.JiraIssueDetail;
import com.eneve.agent.model.JiraIssueReview;
import com.eneve.agent.model.JiraReviewRequest;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobType;
import com.eneve.agent.model.RoadmapItem;
import com.eneve.agent.model.RoadmapRecord;
import com.eneve.agent.settings.SettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Business logic for the Roadmap feature.
 *
 * <h3>Two-phase design</h3>
 * <ol>
 *   <li><b>Sync</b> ({@link #syncRoadmap}) — fetches the complete Jira issue hierarchy
 *       (epics → features → stories) and stores it in {@code roadmap_items}.
 *       Each item's {@code jira_modified_at} is recorded from the Jira {@code updated}
 *       field.</li>
 *   <li><b>Review</b> ({@link #enqueueReviewAll}) — enqueues AI review jobs for items
 *       in {@code roadmap_items} that have changed since their last review. Pass
 *       {@code force=true} to re-review everything regardless.</li>
 * </ol>
 */
@ApplicationScoped
public class RoadmapService {

    private static final Logger LOG = Logger.getLogger(RoadmapService.class);

    @Inject RoadmapStore roadmapStore;
    @Inject RoadmapItemStore roadmapItemStore;
    @Inject JiraIssueReviewStore reviewStore;
    @Inject RoadmapItemOverrideStore overrideStore;
    @Inject JobQueue jobQueue;
    @Inject JobStore jobStore;
    @Inject JiraService jiraService;
    @Inject SettingsService settings;
    @Inject ManagedExecutor managedExecutor;

    // ─── Exception types ─────────────────────────────────────────────────────

    public static final class RoadmapNotFoundException extends RuntimeException {
        public RoadmapNotFoundException(String id) { super("Roadmap not found: " + id); }
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
            super("Jira issue not found in roadmap_items (sync first): " + issueKey);
        }
    }

    // ─── Result records ───────────────────────────────────────────────────────

    public record CreateRoadmapResult(RoadmapRecord roadmap, int itemsSynced) {}

    /**
     * @param jobsEnqueued  items queued for AI review this call
     * @param jobsSkipped   items skipped due to active job or override
     * @param jobsUnchanged items skipped because Jira was not modified since last review
     */
    public record ReviewAllResult(int jobsEnqueued, int jobsSkipped, int jobsUnchanged) {}

    // ─── CRUD ─────────────────────────────────────────────────────────────────

    public List<RoadmapRecord> listRoadmaps() {
        return roadmapStore.findAll();
    }

    /**
     * Creates a new roadmap and immediately syncs all Jira issues.
     * Issue type names default to the three global settings values when blank.
     */
    public CreateRoadmapResult createRoadmap(String name, String label,
                                              String epicIssuetype, String featureIssuetype,
                                              String userstoryIssuetype) {
        String epic    = blankFallback(epicIssuetype,    "roadmap.jira.epic-issuetype",        "Epic");
        String feature = blankFallback(featureIssuetype, "roadmap.jira.feature-issuetype",     "Story");
        String story   = blankFallback(userstoryIssuetype,"roadmap.jira.userstory-issuetype",  "Sub-task");

        RoadmapRecord roadmap = roadmapStore.create(name, label, epic, feature, story);
        int itemsSynced = syncRoadmap(roadmap.id());
        return new CreateRoadmapResult(roadmap, itemsSynced);
    }

    /**
     * Updates name, label and/or issue-type mappings for an existing roadmap.
     *
     * @throws RoadmapNotFoundException if no roadmap with {@code id} exists
     */
    public RoadmapRecord updateRoadmap(String id, String name, String label,
                                        String epicIssuetype, String featureIssuetype,
                                        String userstoryIssuetype) {
        RoadmapRecord existing = roadmapStore.findById(id)
                .orElseThrow(() -> new RoadmapNotFoundException(id));

        String epic    = blankFallback(epicIssuetype,    existing.epicIssuetype());
        String feature = blankFallback(featureIssuetype, existing.featureIssuetype());
        String story   = blankFallback(userstoryIssuetype, existing.userstoryIssuetype());

        roadmapStore.update(id, name, label, epic, feature, story);
        return roadmapStore.findById(id).orElseThrow(() -> new RoadmapNotFoundException(id));
    }

    /**
     * Deletes a roadmap and cascades to all associated items, reviews, and overrides.
     *
     * @throws RoadmapNotFoundException if no roadmap with {@code id} exists
     */
    public void deleteRoadmap(String id) {
        if (roadmapStore.findById(id).isEmpty()) throw new RoadmapNotFoundException(id);
        roadmapStore.delete(id);
    }

    // ─── Sync ─────────────────────────────────────────────────────────────────

    /**
     * Fetches the full Jira issue hierarchy using this roadmap's configured issue types
     * and atomically replaces the stored items. Each item's {@code jira_modified_at}
     * is set from the Jira {@code updated} timestamp.
     *
     * @return number of items stored
     * @throws RoadmapNotFoundException if the roadmap does not exist
     */
    public int syncRoadmap(String roadmapId) {
        RoadmapRecord roadmap = roadmapStore.findById(roadmapId)
                .orElseThrow(() -> new RoadmapNotFoundException(roadmapId));

        List<RoadmapItem> items = fetchItemsFromJira(roadmapId, roadmap);
        roadmapItemStore.replaceAll(roadmapId, items);
        LOG.infof("RoadmapService.syncRoadmap: stored %d items for roadmap %s", items.size(), roadmapId);
        return items.size();
    }

    // ─── Tree ─────────────────────────────────────────────────────────────────

    /**
     * Builds the flat tree-item list entirely from the database.
     * No Jira calls are made. Each item includes staleness information:
     * {@code isStale=true} when Jira was modified after the last AI review.
     *
     * @throws RoadmapNotFoundException if the roadmap does not exist
     */
    public List<Map<String, Object>> buildTree(String roadmapId) {
        if (roadmapStore.findById(roadmapId).isEmpty()) throw new RoadmapNotFoundException(roadmapId);

        int threshold    = Integer.parseInt(settings.get("roadmap.delivery.readiness-threshold", "70"));
        boolean weighted = Boolean.parseBoolean(settings.get("roadmap.delivery.complexity-weight-enabled", "true"));

        List<RoadmapItem> allItems  = roadmapItemStore.findByRoadmap(roadmapId);
        Map<String, JiraIssueReview> reviewMap = reviewStore.findByRoadmap(roadmapId).stream()
                .collect(Collectors.toMap(JiraIssueReview::issueKey, r -> r));
        Map<String, String> overrideMap = overrideStore.findByRoadmap(roadmapId);

        List<RoadmapItem> epics = allItems.stream()
                .filter(i -> "EPIC".equals(i.issueType())).collect(Collectors.toList());
        Map<String, List<RoadmapItem>> featuresByEpic = allItems.stream()
                .filter(i -> "FEATURE".equals(i.issueType()))
                .collect(Collectors.groupingBy(i -> i.parentKey() != null ? i.parentKey() : ""));
        Map<String, List<RoadmapItem>> storiesByFeature = allItems.stream()
                .filter(i -> "USERSTORY".equals(i.issueType()))
                .collect(Collectors.groupingBy(i -> i.parentKey() != null ? i.parentKey() : ""));

        List<Map<String, Object>> result = new ArrayList<>();

        for (RoadmapItem epic : epics) {
            List<int[]> featureAggregates = new ArrayList<>();
            List<RoadmapItem> epicFeatures = featuresByEpic.getOrDefault(epic.issueKey(), List.of());

            for (RoadmapItem feature : epicFeatures) {
                List<int[]> storyAggregates = new ArrayList<>();
                List<RoadmapItem> featureStories = storiesByFeature.getOrDefault(feature.issueKey(), List.of());

                for (RoadmapItem story : featureStories) {
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

    // ─── Reviews ──────────────────────────────────────────────────────────────

    /**
     * Enqueues AI review jobs for items in {@code roadmap_items}.
     *
     * <p>By default ({@code force=false}) items whose Jira {@code updated} timestamp
     * is not newer than their last review are skipped — they haven't changed and
     * do not need re-reviewing. Pass {@code force=true} to re-review everything.
     *
     * @throws RoadmapNotFoundException if the roadmap does not exist
     */
    public ReviewAllResult enqueueReviewAll(String roadmapId, boolean force) {
        if (roadmapStore.findById(roadmapId).isEmpty()) throw new RoadmapNotFoundException(roadmapId);

        List<RoadmapItem> items = roadmapItemStore.findByRoadmap(roadmapId);
        if (items.isEmpty()) {
            LOG.infof("RoadmapService.enqueueReviewAll: no items synced for roadmap %s", roadmapId);
            return new ReviewAllResult(0, 0, 0);
        }

        int maxJobs = Integer.parseInt(settings.get("roadmap.review.max-jobs-per-review-all", "50"));
        Map<String, String> overrideMap = overrideStore.findByRoadmap(roadmapId);
        Map<String, JiraIssueReview> reviewMap = reviewStore.findByRoadmap(roadmapId).stream()
                .collect(Collectors.toMap(JiraIssueReview::issueKey, r -> r));

        int enqueued = 0, skipped = 0, unchanged = 0;

        for (RoadmapItem item : items) {
            if (enqueued >= maxJobs) break;

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

            enqueueJob(roadmapId, item.issueKey(), resolveJobType(item.issueType()),
                    item.parentKey(), item.grandparentKey());
            enqueued++;
        }
        LOG.infof("RoadmapService.enqueueReviewAll: enqueued=%d skipped=%d unchanged=%d roadmap=%s",
                enqueued, skipped, unchanged, roadmapId);
        return new ReviewAllResult(enqueued, skipped, unchanged);
    }

    /**
     * Enqueues a single AI review job for {@code issueKey}.
     * The item must already be present in {@code roadmap_items} (sync first).
     *
     * @return the new job ID
     */
    public String enqueueReview(String roadmapId, String issueKey) {
        if (roadmapStore.findById(roadmapId).isEmpty()) throw new RoadmapNotFoundException(roadmapId);
        if (overrideStore.isOverridden(roadmapId, issueKey)) throw new ItemOverriddenException(issueKey);
        if (jobStore.hasActiveReviewJob(issueKey)) throw new ActiveJobExistsException(issueKey);

        RoadmapItem item = roadmapItemStore.findByRoadmapAndIssueKey(roadmapId, issueKey)
                .orElseThrow(() -> new JiraIssueNotFoundException(issueKey));

        String jobId = UUID.randomUUID().toString();
        JobType jobType = resolveJobType(item.issueType());
        JiraReviewRequest req = new JiraReviewRequest(
                roadmapId, issueKey, item.issueType(), item.parentKey(), item.grandparentKey());
        JobRecord job = new JobRecord(jobId, req, jobType);
        jobStore.put(job);
        jobQueue.submit(job);
        return jobId;
    }

    // ─── Overrides ────────────────────────────────────────────────────────────

    public void setOverride(String roadmapId, String issueKey, String status, String updatedBy) {
        if (roadmapStore.findById(roadmapId).isEmpty()) throw new RoadmapNotFoundException(roadmapId);
        overrideStore.setOverride(roadmapId, issueKey, status, updatedBy);
    }

    public void clearOverride(String roadmapId, String issueKey) {
        if (roadmapStore.findById(roadmapId).isEmpty()) throw new RoadmapNotFoundException(roadmapId);
        overrideStore.clearOverride(roadmapId, issueKey);
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    /**
     * Fetches epics → features → stories in parallel using the roadmap's
     * per-project issue-type configuration.
     */
    private List<RoadmapItem> fetchItemsFromJira(String roadmapId, RoadmapRecord roadmap) {
        List<JiraIssueDetail> epics =
                jiraService.searchEpicsByLabel(roadmap.label(), roadmap.epicIssuetype());

        List<CompletableFuture<List<JiraIssueDetail>>> featureFutures = epics.stream()
                .map(e -> CompletableFuture.supplyAsync(
                        () -> jiraService.searchFeaturesForEpic(e.key(), roadmap.featureIssuetype()),
                        managedExecutor))
                .collect(Collectors.toList());
        List<List<JiraIssueDetail>> featuresByEpic = featureFutures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());

        List<JiraIssueDetail> allFeatures = featuresByEpic.stream()
                .flatMap(Collection::stream).collect(Collectors.toList());
        List<CompletableFuture<List<JiraIssueDetail>>> storyFutures = allFeatures.stream()
                .map(f -> CompletableFuture.supplyAsync(
                        () -> jiraService.searchStoriesForFeature(f.key(), roadmap.userstoryIssuetype()),
                        managedExecutor))
                .collect(Collectors.toList());
        List<List<JiraIssueDetail>> storiesByFeature = storyFutures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());

        List<RoadmapItem> items = new ArrayList<>();
        for (int ei = 0; ei < epics.size(); ei++) {
            JiraIssueDetail epic = epics.get(ei);
            items.add(new RoadmapItem(null, roadmapId, epic.key(), "EPIC",
                    null, null, epic.summary(), epic.status(), null, epic.updatedAt()));

            List<JiraIssueDetail> features = featuresByEpic.get(ei);
            for (JiraIssueDetail feature : features) {
                items.add(new RoadmapItem(null, roadmapId, feature.key(), "FEATURE",
                        epic.key(), null, feature.summary(), feature.status(), null, feature.updatedAt()));

                int featureIdx = allFeatures.indexOf(feature);
                List<JiraIssueDetail> stories = featureIdx >= 0
                        ? storiesByFeature.get(featureIdx) : List.of();
                for (JiraIssueDetail story : stories) {
                    items.add(new RoadmapItem(null, roadmapId, story.key(), "USERSTORY",
                            feature.key(), epic.key(), story.summary(), story.status(), null, story.updatedAt()));
                }
            }
        }
        return items;
    }

    private Map<String, Object> buildTreeItem(RoadmapItem item, JiraIssueReview review,
                                               String overrideStatus) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("issueKey",  item.issueKey());
        out.put("issueType", item.issueType());
        out.put("summary",   item.summary() != null ? item.summary() : "");
        if (item.parentKey() != null)      out.put("parentKey",      item.parentKey());
        if (item.grandparentKey() != null) out.put("grandparentKey", item.grandparentKey());
        out.put("jiraStatus", mapStatus(item.jiraStatus()));
        if (item.jiraModifiedAt() != null) out.put("jiraModifiedAt", item.jiraModifiedAt());

        if (review != null) {
            out.put("readinessScore",     review.readinessScore());
            out.put("readinessLabel",     review.readinessLabel());
            out.put("complexityScore",    review.complexityScore());
            out.put("improvementSummary", review.improvementSummary());
            out.put("reviewedAt",         review.reviewedAt());

            // isStale = Jira changed after the last AI review
            if (item.jiraModifiedAt() != null && review.reviewedAt() != null) {
                out.put("isStale", item.jiraModifiedAt().isAfter(review.reviewedAt()));
            }
        }

        if (overrideStatus != null) out.put("overrideStatus", overrideStatus);
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

    private void enqueueJob(String roadmapId, String issueKey, JobType jobType,
                             String parentKey, String grandparentKey) {
        String jobId = UUID.randomUUID().toString();
        JiraReviewRequest req = new JiraReviewRequest(
                roadmapId, issueKey, issueType(jobType), parentKey, grandparentKey);
        jobStore.put(new JobRecord(jobId, req, jobType));
        jobQueue.submit(new JobRecord(jobId, req, jobType));
    }

    private static String issueType(JobType jt) {
        return jt.name().replace("REVIEW_", "");
    }

    /** Returns {@code value} unless blank, then falls back to the setting, then the hardcoded default. */
    private String blankFallback(String value, String settingKey, String hardDefault) {
        if (value != null && !value.isBlank()) return value.trim();
        String fromSettings = settings.get(settingKey, hardDefault);
        return fromSettings != null && !fromSettings.isBlank() ? fromSettings : hardDefault;
    }

    /** Falls back to {@code fallback} when {@code value} is blank. */
    private static String blankFallback(String value, String fallback) {
        return (value != null && !value.isBlank()) ? value.trim() : fallback;
    }
}
