package com.eneve.agent.scope;

import com.eneve.agent.agent.store.ScopeStore;
import com.eneve.agent.jira.JiraService;
import com.eneve.agent.model.QaReadinessResponse;
import com.eneve.agent.model.ScopeRecord;
import com.eneve.agent.scope.ScopeExceptions.ScopeNotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * Computes QA readiness statistics for a scope.
 *
 * <p>Delegates tree construction to {@link ScopeEvaluationService}, then sweeps
 * the flat item list once to compute all summary counters. No new DB queries.
 */
@ApplicationScoped
public class QaReadinessService {

    private static final Logger LOG = Logger.getLogger(QaReadinessService.class);

    @Inject ScopeEvaluationService evaluationService;
    @Inject ScopeStore scopeStore;
    @Inject JiraService jiraService;

    /**
     * Builds the QA readiness response for the given scope.
     *
     * @throws ScopeNotFoundException if the scope does not exist
     */
    public QaReadinessResponse buildQaReadiness(String scopeId) {
        List<Map<String, Object>> allItems = evaluationService.buildTree(scopeId);

        // Exclude virtual epics — they are synthetic grouping rows, not real Jira issues
        List<Map<String, Object>> realItems = allItems.stream()
                .filter(item -> !Boolean.TRUE.equals(item.get("isVirtual")))
                .toList();

        int totalItems             = realItems.size();
        int reviewed               = 0;
        int fullyReadyCount        = 0;
        int minorImprovementsCount = 0;
        int needsRefinementCount   = 0;
        int poorCount              = 0;
        int inQaStatusCount        = 0;
        int closedCount            = 0;
        int staleCount             = 0;
        int readyForDeliveryCount  = 0;

        for (Map<String, Object> item : realItems) {
            String readinessLabel = (String) item.get("readinessLabel");
            String jiraStatus     = (String) item.get("jiraStatus");
            boolean isStale       = Boolean.TRUE.equals(item.get("isStale"));
            boolean readyForDel   = Boolean.TRUE.equals(item.get("readyForDelivery"));

            if (readinessLabel != null) {
                reviewed++;
                switch (readinessLabel) {
                    case "fully_ready"                    -> fullyReadyCount++;
                    case "ready_with_minor_improvements"  -> minorImprovementsCount++;
                    case "needs_refinement"               -> needsRefinementCount++;
                    case "poor"                           -> poorCount++;
                    default -> { /* unknown label — counted as reviewed but not categorised */ }
                }
            }

            if ("QA".equals(jiraStatus))     inQaStatusCount++;
            if ("Closed".equals(jiraStatus)) closedCount++;
            if (isStale)                     staleCount++;
            if (readyForDel)                 readyForDeliveryCount++;
        }

        QaReadinessResponse.Summary summary = new QaReadinessResponse.Summary(
                totalItems,
                reviewed,
                fullyReadyCount,
                minorImprovementsCount,
                needsRefinementCount,
                poorCount,
                inQaStatusCount,
                closedCount,
                staleCount,
                readyForDeliveryCount
        );

        return new QaReadinessResponse(summary, realItems);
    }

    /**
     * Fetches QA-ready features for a scope (features with the QGStoryDone label).
     * Uses the feature-level issuetype, not epic-level, to correctly search for individual features.
     *
     * @throws ScopeNotFoundException if the scope does not exist
     */
    public List<JiraService.JiraIssueDetail> fetchQAReadyFeatures(String scopeId, String label) {
        ScopeRecord scope = scopeStore.findById(scopeId)
                .orElseThrow(() -> new ScopeNotFoundException(scopeId));
        if (label == null || label.isBlank()) return List.of();
        String featureIssuetype = scope.featureIssuetype() != null ? scope.featureIssuetype() : "Story";
        return jiraService.searchQAFeaturesByLabels(List.of(label), featureIssuetype);
    }
}
