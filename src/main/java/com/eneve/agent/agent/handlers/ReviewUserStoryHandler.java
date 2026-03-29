package com.eneve.agent.agent.handlers;

import com.eneve.agent.agent.store.ScopeItemStore;
import com.eneve.agent.model.JiraReviewRequest;
import com.eneve.agent.model.JobType;
import jakarta.enterprise.context.ApplicationScoped;

/** Handles {@link JobType#REVIEW_USERSTORY} jobs. */
@ApplicationScoped
public class ReviewUserStoryHandler extends AbstractScopeItemReviewHandler {

    @Override
    public JobType jobType() { return JobType.REVIEW_USERSTORY; }

    @Override
    protected String itemTypeLabel() { return "USERSTORY"; }

    @Override
    protected String promptTemplateKey() { return "review-userstory"; }

    @Override
    protected String buildContext(JiraReviewRequest req, ScopeItemStore scopeItems) {
        return contextBuilder.buildUserStoryContext(
                req.issueKey(), req.parentKey(), req.grandparentKey());
    }
}
