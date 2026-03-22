package com.eneve.agent.mcp;

import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.model.JobStatus;
import com.eneve.agent.model.JobStatusResponse;
import com.eneve.agent.model.JobType;
import com.eneve.agent.tools.ToolExecutor;
import com.eneve.agent.workspace.WorkspaceContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * MCP tool: List jobs with optional filtering by status, job type, and pagination.
 */
@ApplicationScoped
public class AgentListJobsTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(AgentListJobsTool.class);

    @Inject
    JobStore jobStore;

    @Override
    public String name() {
        return "agent_list_jobs";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String statusParam = (String) input.get("status");
        String jobTypeParam = (String) input.get("jobType");
        int limit = parseInt(input.get("limit"), 20);
        int page = parseInt(input.get("page"), 0);

        JobStatus status = null;
        if (statusParam != null && !statusParam.isBlank()) {
            try {
                status = JobStatus.valueOf(statusParam.toUpperCase());
            } catch (IllegalArgumentException e) {
                return "ERROR: Invalid status: " + statusParam
                        + ". Must be one of: PENDING, QUEUED, RUNNING, SUCCESS, FAILED, AWAITING_APPROVAL";
            }
        }

        JobType jobType = null;
        if (jobTypeParam != null && !jobTypeParam.isBlank()) {
            try {
                jobType = JobType.valueOf(jobTypeParam.toUpperCase());
            } catch (IllegalArgumentException e) {
                return "ERROR: Invalid jobType: " + jobTypeParam;
            }
        }

        int safeLimit = Math.min(Math.max(1, limit), 200);
        int offset = Math.max(0, page) * safeLimit;

        try {
            List<JobStatusResponse> jobs = jobStore.search(status, jobType, safeLimit, offset);
            if (jobs.isEmpty()) {
                return "No jobs found.";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Jobs (").append(jobs.size()).append(" results):\n\n");
            for (JobStatusResponse job : jobs) {
                sb.append("- ").append(job.jobId()).append(" | ")
                        .append(job.status()).append(" | ")
                        .append(job.jobType());
                if (job.summary() != null && !job.summary().isBlank()) {
                    sb.append(" | ").append(job.summary());
                }
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            LOG.errorf("Failed to list jobs: %s", e.getMessage());
            return "ERROR: Failed to list jobs: " + e.getMessage();
        }
    }

    private int parseInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
