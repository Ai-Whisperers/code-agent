package com.eneve.agent.model;

import java.util.List;
import java.util.Map;

/**
 * Response payload for {@code GET /scope/{id}/qa-readiness}.
 *
 * <p>Contains a pre-aggregated {@link Summary} of readiness counters and the full
 * flat item list (virtual epics excluded) in the same shape as {@code /evaluation/tree}.
 */
public record QaReadinessResponse(Summary summary, List<Map<String, Object>> items) {

    /**
     * Pre-aggregated readiness counters computed from the scope tree.
     *
     * @param totalItems              total non-virtual items in the scope
     * @param reviewed                items that have at least one AI review
     * @param fullyReadyCount         items with readinessLabel = "fully_ready"
     * @param minorImprovementsCount  items with readinessLabel = "ready_with_minor_improvements"
     * @param needsRefinementCount    items with readinessLabel = "needs_refinement"
     * @param poorCount               items with readinessLabel = "poor"
     * @param inQaStatusCount         items whose jiraStatus = "QA"
     * @param closedCount             items whose jiraStatus = "Closed"
     * @param staleCount              items where isStale = true
     * @param readyForDeliveryCount   items where readyForDelivery = true
     */
    public record Summary(
            int totalItems,
            int reviewed,
            int fullyReadyCount,
            int minorImprovementsCount,
            int needsRefinementCount,
            int poorCount,
            int inQaStatusCount,
            int closedCount,
            int staleCount,
            int readyForDeliveryCount
    ) {}
}
