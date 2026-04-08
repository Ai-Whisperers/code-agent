package com.eneve.agent.mcp;

import com.eneve.agent.agent.AgentRunner;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobStatus;
import com.eneve.agent.tools.ToolExecutor;
import com.eneve.agent.workspace.WorkspaceContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.Optional;

/**
 * MCP tool: Reject a job that is awaiting approval (declines the PR).
 */
@ApplicationScoped
public class AgentRejectJobTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(AgentRejectJobTool.class);

    @Inject
    JobStore jobStore;

    @Inject
    AgentRunner agentRunner;

    @Override
    public String name() {
        return "agent_reject_job";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override public boolean isDestructive() { return true; }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String jobId = (String) input.get("jobId");
        if (jobId == null || jobId.isBlank()) {
            return "ERROR: 'jobId' parameter is required";
        }

        String reason = (String) input.get("reason");

        try {
            Optional<JobRecord> jobOpt = jobStore.get(jobId);
            if (jobOpt.isEmpty()) {
                return "ERROR: Job not found: " + jobId;
            }

            JobRecord job = jobOpt.get();
            if (job.getStatus() != JobStatus.AWAITING_APPROVAL) {
                return "ERROR: Job is not awaiting approval. Current status: " + job.getStatus();
            }

            agentRunner.reject(job, reason);
            return "Job rejected and PR declined. Job ID: " + jobId +
                   (reason != null && !reason.isBlank() ? " (reason: " + reason + ")" : "");
        } catch (Exception e) {
            LOG.errorf("Failed to reject job %s: %s", jobId, e.getMessage());
            return "ERROR: Failed to reject job: " + e.getMessage();
        }
    }
}
