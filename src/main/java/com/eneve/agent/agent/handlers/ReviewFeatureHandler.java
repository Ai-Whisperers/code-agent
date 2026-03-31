package com.eneve.agent.agent.handlers;

import com.eneve.agent.agent.store.ScopeItemStore;
import com.eneve.agent.model.JiraReviewRequest;
import com.eneve.agent.model.JobType;
import jakarta.enterprise.context.ApplicationScoped;

/** Handles {@link JobType#REVIEW_FEATURE} jobs. */
@ApplicationScoped
public class ReviewFeatureHandler extends AbstractScopeItemReviewHandler {

    @Override
    public JobType jobType() { return JobType.REVIEW_FEATURE; }

    @Override
    protected String itemTypeLabel() { return "FEATURE"; }

    @Override
    protected String promptTemplateKey() { return "review-feature"; }

    @Override
    protected String buildContext(JiraReviewRequest req, ScopeItemStore scopeItems) {
        int storyCount = req.scopeId() != null
                ? scopeItems.countChildrenByParent(req.scopeId(), req.issueKey())
                : -1;
        return contextBuilder.buildFeatureContext(req.issueKey(), req.parentKey(), storyCount);
    }
}
