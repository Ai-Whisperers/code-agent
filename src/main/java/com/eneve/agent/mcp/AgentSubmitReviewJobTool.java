package com.eneve.agent.mcp;

import com.eneve.agent.agent.JobQueue;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.ReviewPrRequest;
import com.eneve.agent.tools.ToolExecutor;
import com.eneve.agent.workspace.WorkspaceContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.UUID;

/**
 * MCP tool: Submit a PR review job to the agent queue.
 */
@ApplicationScoped
public class AgentSubmitReviewJobTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(AgentSubmitReviewJobTool.class);

    @Inject
    JobQueue jobQueue;

    @Inject
    JobStore jobStore;

    @Override
    public String name() {
        return "agent_submit_review_job";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String repoUrl = (String) input.get("repoUrl");
        String prId = (String) input.get("prId");

        if (repoUrl == null || repoUrl.isBlank()) {
            return "ERROR: 'repoUrl' parameter is required";
        }
        if (prId == null || prId.isBlank()) {
            return "ERROR: 'prId' parameter is required";
        }

        String targetBranch = (String) input.get("targetBranch");
        String jiraKey = (String) input.get("jiraKey");
        String extraRules = (String) input.get("extraRules");

        try {
            String jobId = UUID.randomUUID().toString();
            ReviewPrRequest request = new ReviewPrRequest(
                    repoUrl,
                    prId,
                    targetBranch,
                    jiraKey,
                    null, // rulesRepoUrl
                    null, // ruleNames
                    extraRules,
                    null, // n8nWebhookUrl
                    null, // headCommitSha
                    null  // prAuthor
            );
            JobRecord job = new JobRecord(jobId, request);
            jobStore.put(job);
            boolean accepted = jobQueue.submit(job);
            if (accepted) {
                return "Review job submitted. Job ID: " + jobId + " (repo: " + repoUrl + ", PR: " + prId + ")";
            } else {
                return "ERROR: Job queue is full. Please try again later.";
            }
        } catch (Exception e) {
            LOG.errorf("Failed to submit review job: %s", e.getMessage());
            return "ERROR: Failed to submit review job: " + e.getMessage();
        }
    }
}
