package com.eneve.agent.agent.handlers;

import com.eneve.agent.agent.store.ScopeItemStore;
import com.eneve.agent.model.JiraReviewRequest;
import com.eneve.agent.model.JobType;
import jakarta.enterprise.context.ApplicationScoped;

/** Handles {@link JobType#REVIEW_EPIC} jobs. */
@ApplicationScoped
public class ReviewEpicHandler extends AbstractScopeItemReviewHandler {

    @Override
    public JobType jobType() { return JobType.REVIEW_EPIC; }

    @Override
    protected String itemTypeLabel() { return "EPIC"; }

    @Override
    protected String promptTemplateKey() { return "review-epic"; }

    @Override
    protected String buildContext(JiraReviewRequest req, ScopeItemStore scopeItems) {
        int featureCount = req.scopeId() != null
                ? scopeItems.countChildrenByParent(req.scopeId(), req.issueKey())
                : -1;
        return contextBuilder.buildEpicContext(req.issueKey(), featureCount);
    }
}
