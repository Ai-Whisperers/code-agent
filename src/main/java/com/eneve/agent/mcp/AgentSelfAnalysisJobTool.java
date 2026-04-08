package com.eneve.agent.mcp;

import com.eneve.agent.agent.JobQueue;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.SelfAnalysisRequest;
import com.eneve.agent.tools.ToolExecutor;
import com.eneve.agent.workspace.WorkspaceContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.UUID;

/**
 * MCP tool: Submit an autonomous self-analysis job from a chat session.
 *
 * <p>The job clones the target repository, inspects the codebase and optionally
 * recent CloudWatch logs, diagnoses issues, implements fixes, and raises a PR.
 */
@ApplicationScoped
public class AgentSelfAnalysisJobTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(AgentSelfAnalysisJobTool.class);

    @Inject
    JobQueue jobQueue;

    @Inject
    JobStore jobStore;

    @Override
    public String name() {
        return "agent_self_analysis";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public boolean isDestructive() {
        return true;
    }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String repoUrl = (String) input.get("repoUrl");
        if (repoUrl == null || repoUrl.isBlank()) {
            return "ERROR: 'repoUrl' parameter is required";
        }

        // failedJobId is optional — default to "manual" so the handler can still build
        // a branch name and commit message without a real job ID.
        String failedJobId = (String) input.get("failedJobId");
        if (failedJobId == null || failedJobId.isBlank()) {
            failedJobId = "manual-" + UUID.randomUUID().toString().substring(0, 8);
        }

        String targetBranch    = (String) input.get("targetBranch");
        String customerId      = (String) input.get("customerId");
        String environmentName = (String) input.get("environmentName");
        String logGroupName    = (String) input.get("logGroupName");
        String jiraProjectKey  = (String) input.get("jiraProjectKey");

        try {
            String jobId = UUID.randomUUID().toString();
            SelfAnalysisRequest request = new SelfAnalysisRequest(
                    failedJobId,
                    repoUrl,
                    targetBranch,
                    customerId,
                    environmentName,
                    logGroupName,
                    jiraProjectKey
            );
            JobRecord job = new JobRecord(jobId, request);
            jobStore.put(job);
            boolean accepted = jobQueue.submit(job);
            if (accepted) {
                return "Self-analysis job submitted. Job ID: " + jobId + " (repo: " + repoUrl + ")";
            } else {
                return "ERROR: Job queue is full. Please try again later.";
            }
        } catch (Exception e) {
            LOG.errorf("Failed to submit self-analysis job: %s", e.getMessage());
            return "ERROR: Failed to submit self-analysis job: " + e.getMessage();
        }
    }
}
