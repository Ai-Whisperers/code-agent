package com.eneve.agent.mcp;

import com.eneve.agent.agent.JobQueue;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.model.GenerateDocsRequest;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.tools.ToolExecutor;
import com.eneve.agent.workspace.WorkspaceContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.UUID;

/**
 * MCP tool: Submit a generate documentation job to the agent queue.
 */
@ApplicationScoped
public class AgentSubmitGenerateDocsJobTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(AgentSubmitGenerateDocsJobTool.class);

    @Inject
    JobQueue jobQueue;

    @Inject
    JobStore jobStore;

    @Override
    public String name() {
        return "agent_submit_generate_docs_job";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String repoUrl = (String) input.get("repoUrl");
        String branchName = (String) input.get("branchName");

        if (repoUrl == null || repoUrl.isBlank()) {
            return "ERROR: 'repoUrl' parameter is required";
        }
        if (branchName == null || branchName.isBlank()) {
            return "ERROR: 'branchName' parameter is required";
        }

        String prompt = (String) input.get("prompt");

        try {
            String jobId = UUID.randomUUID().toString();
            GenerateDocsRequest request = new GenerateDocsRequest(
                    repoUrl,
                    branchName,
                    "develop", // targetBranch default
                    null,      // ruleNames
                    prompt,    // extraRules
                    null,      // n8nWebhookUrl
                    null       // commitDirect (false)
            );
            JobRecord job = new JobRecord(jobId, request);
            jobStore.put(job);
            boolean accepted = jobQueue.submit(job);
            if (accepted) {
                return "Generate docs job submitted. Job ID: " + jobId + " (branch: " + branchName + ")";
            } else {
                return "ERROR: Job queue is full. Please try again later.";
            }
        } catch (Exception e) {
            LOG.errorf("Failed to submit generate docs job: %s", e.getMessage());
            return "ERROR: Failed to submit generate docs job: " + e.getMessage();
        }
    }
}
