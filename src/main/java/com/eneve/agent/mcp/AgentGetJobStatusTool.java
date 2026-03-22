package com.eneve.agent.mcp;

import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.tools.ToolExecutor;
import com.eneve.agent.workspace.WorkspaceContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.Optional;

/**
 * MCP tool: Get the status and result of a previously submitted agent job.
 */
@ApplicationScoped
public class AgentGetJobStatusTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(AgentGetJobStatusTool.class);

    @Inject
    JobStore jobStore;

    @Override
    public String name() {
        return "agent_get_job_status";
    }

    @Override
    public boolean isReadOnly() {
        return true;
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
                return "Job not found: " + jobId;
            }

            JobRecord job = jobOpt.get();
            StringBuilder sb = new StringBuilder();
            sb.append("Job ID: ").append(job.getJobId()).append("\n");
            sb.append("Status: ").append(job.getStatus()).append("\n");
            sb.append("Type: ").append(job.getJobType()).append("\n");
            sb.append("Created: ").append(job.getCreatedAt()).append("\n");
            if (job.getSummary() != null && !job.getSummary().isBlank()) {
                sb.append("Summary: ").append(job.getSummary()).append("\n");
            }
            if (job.getPrUrl() != null && !job.getPrUrl().isBlank()) {
                sb.append("PR URL: ").append(job.getPrUrl()).append("\n");
            }
            if (job.getErrorMessage() != null && !job.getErrorMessage().isBlank()) {
                sb.append("Error: ").append(job.getErrorMessage()).append("\n");
            }
            if (job.getFilesChanged() > 0) {
                sb.append("Files changed: ").append(job.getFilesChanged()).append("\n");
                sb.append("Lines changed: ").append(job.getLinesChanged()).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            LOG.errorf("Failed to get job status: %s", e.getMessage());
            return "ERROR: Failed to get job status: " + e.getMessage();
        }
    }
}
