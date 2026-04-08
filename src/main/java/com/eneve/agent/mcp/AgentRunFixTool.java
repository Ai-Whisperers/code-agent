package com.eneve.agent.mcp;

import com.eneve.agent.agent.JobQueue;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.RunFixRequest;
import com.eneve.agent.tools.ToolExecutor;
import com.eneve.agent.workspace.WorkspaceContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.UUID;

/**
 * MCP tool: Submit a code-fix job to the agent queue.
 */
@ApplicationScoped
public class AgentRunFixTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(AgentRunFixTool.class);

    @Inject
    JobQueue jobQueue;

    @Inject
    JobStore jobStore;

    @Override
    public String name() {
        return "agent_run_fix";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override public boolean isDestructive() { return true; }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String repoUrl = (String) input.get("repoUrl");
        String branchName = (String) input.get("branchName");
        String jiraKey = (String) input.get("jiraKey");
        String prompt = (String) input.get("prompt");

        if (repoUrl == null || repoUrl.isBlank()) {
            return "ERROR: 'repoUrl' parameter is required";
        }
        if (jiraKey == null || jiraKey.isBlank()) {
            return "ERROR: 'jiraKey' parameter is required";
        }
        if (prompt == null || prompt.isBlank()) {
            return "ERROR: 'prompt' parameter is required";
        }
        if (branchName == null || branchName.isBlank()) {
            branchName = "agent/" + jiraKey.toLowerCase() + "-fix";
        }

        try {
            String jobId = UUID.randomUUID().toString();
            RunFixRequest request = new RunFixRequest(
                    repoUrl, branchName, jiraKey, prompt, null, null, null, null, null, null, false);
            JobRecord job = new JobRecord(jobId, request);
            jobStore.put(job);
            boolean accepted = jobQueue.submit(job);
            if (accepted) {
                return "Job submitted. Job ID: " + jobId + " (jiraKey: " + jiraKey + ")";
            } else {
                return "ERROR: Job queue is full. Please try again later.";
            }
        } catch (Exception e) {
            LOG.errorf("Failed to submit job: %s", e.getMessage());
            return "ERROR: Failed to submit job: " + e.getMessage();
        }
    }
}
