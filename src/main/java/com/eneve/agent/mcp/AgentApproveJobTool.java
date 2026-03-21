package com.eneve.agent.mcp;

import java.util.Map;
import java.util.Optional;

import com.eneve.agent.agent.AgentRunner;
import com.eneve.agent.agent.JobStore;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.JobStatus;
import com.eneve.agent.tools.ToolExecutor;
import com.eneve.agent.workspace.WorkspaceContext;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * MCP tool: Approve a job that is awaiting approval (merges the PR).
 */
@ApplicationScoped
public class AgentApproveJobTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(AgentApproveJobTool.class);

    @Inject
    JobStore jobStore;

    @Inject
    AgentRunner agentRunner;

    @Override
    public String name() {
        return "agent_approve_job";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String jobId = (String) input.get("jobId");
        if (jobId == null || jobId.isBlank()) {
            return "ERROR: 'jobId' parameter is required";
        }

        try {
            Optional<JobRecord> jobOpt = jobStore.get(jobId);
            if (jobOpt.isEmpty()) {
                return "ERROR: Job not found: " + jobId;
            }

            JobRecord job = jobOpt.get();
            if (job.getStatus() != JobStatus.AWAITING_APPROVAL) {
                return "ERROR: Job is not awaiting approval. Current status: " + job.getStatus();
            }

            agentRunner.approve(job);
            return "Job approved and PR merged. Job ID: " + jobId;
        } catch (Exception e) {
            LOG.errorf("Failed to approve job %s: %s", jobId, e.getMessage());
            return "ERROR: Failed to approve job: " + e.getMessage();
        }
    }
}
