package com.eneve.agent.mcp;

import java.util.Map;
import java.util.UUID;

import com.eneve.agent.agent.JobQueue;
import com.eneve.agent.agent.JobStore;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.QualityReportJobRequest;
import com.eneve.agent.tools.ToolExecutor;
import com.eneve.agent.workspace.WorkspaceContext;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * MCP tool: Trigger a quality report collection job.
 */
@ApplicationScoped
public class AgentTriggerQualityReportTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(AgentTriggerQualityReportTool.class);

    @Inject
    JobQueue jobQueue;

    @Inject
    JobStore jobStore;

    @Override
    public String name() {
        return "agent_trigger_quality_report";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String workspaceName = (String) input.get("workspace");
        String repoSlug = (String) input.get("repoSlug");
        String branch = (String) input.get("branch");
        String repoUrl = (String) input.get("repoUrl");

        if (workspaceName == null || workspaceName.isBlank()) {
            return "ERROR: 'workspace' parameter is required";
        }
        if (repoSlug == null || repoSlug.isBlank()) {
            return "ERROR: 'repoSlug' parameter is required";
        }
        if (branch == null || branch.isBlank()) {
            return "ERROR: 'branch' parameter is required";
        }
        if (repoUrl == null || repoUrl.isBlank()) {
            return "ERROR: 'repoUrl' parameter is required";
        }

        try {
            String jobId = UUID.randomUUID().toString();
            QualityReportJobRequest request = new QualityReportJobRequest(
                    repoUrl,
                    branch,
                    workspaceName,
                    repoSlug
            );
            JobRecord job = new JobRecord(jobId, request);
            jobStore.put(job);

            if (!jobQueue.submit(job)) {
                return "ERROR: Job queue is full";
            }

            return "Quality report job triggered. Job ID: " + jobId +
                   " (" + workspaceName + "/" + repoSlug + "@" + branch + ", status: QUEUED)";
        } catch (Exception e) {
            LOG.errorf("Failed to trigger quality report: %s", e.getMessage());
            return "ERROR: Failed to trigger quality report: " + e.getMessage();
        }
    }
}
