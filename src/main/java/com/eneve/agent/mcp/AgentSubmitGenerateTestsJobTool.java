package com.eneve.agent.mcp;

import com.eneve.agent.agent.JobQueue;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.model.GenerateTestsRequest;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.tools.ToolExecutor;
import com.eneve.agent.workspace.WorkspaceContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.UUID;

/**
 * MCP tool: Submit a generate tests job to the agent queue.
 */
@ApplicationScoped
public class AgentSubmitGenerateTestsJobTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(AgentSubmitGenerateTestsJobTool.class);

    @Inject
    JobQueue jobQueue;

    @Inject
    JobStore jobStore;

    @Override
    public String name() {
        return "agent_submit_generate_tests_job";
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

        if (repoUrl == null || repoUrl.isBlank()) {
            return "ERROR: 'repoUrl' parameter is required";
        }
        if (branchName == null || branchName.isBlank()) {
            return "ERROR: 'branchName' parameter is required";
        }
        if (jiraKey == null || jiraKey.isBlank()) {
            return "ERROR: 'jiraKey' parameter is required";
        }

        String prompt = (String) input.get("prompt");

        try {
            String jobId = UUID.randomUUID().toString();
            GenerateTestsRequest request = new GenerateTestsRequest(
                    repoUrl,
                    branchName,
                    "develop", // targetBranch default
                    null,      // targetFiles
                    jiraKey,
                    null,      // n8nWebhookUrl
                    null,      // rulesRepoUrl
                    null,      // ruleNames
                    prompt     // extraRules
            );
            JobRecord job = new JobRecord(jobId, request);
            jobStore.put(job);
            boolean accepted = jobQueue.submit(job);
            if (accepted) {
                return "Generate tests job submitted. Job ID: " + jobId + " (jiraKey: " + jiraKey + ")";
            } else {
                return "ERROR: Job queue is full. Please try again later.";
            }
        } catch (Exception e) {
            LOG.errorf("Failed to submit generate tests job: %s", e.getMessage());
            return "ERROR: Failed to submit generate tests job: " + e.getMessage();
        }
    }
}
