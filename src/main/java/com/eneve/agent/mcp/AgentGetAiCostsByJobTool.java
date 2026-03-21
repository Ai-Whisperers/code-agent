package com.eneve.agent.mcp;

import java.util.List;
import java.util.Map;

import com.eneve.agent.agent.AiCallRecord;
import com.eneve.agent.agent.AiCallStore;
import com.eneve.agent.tools.ToolExecutor;
import com.eneve.agent.workspace.WorkspaceContext;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * MCP tool: Get AI call costs for a specific job.
 */
@ApplicationScoped
public class AgentGetAiCostsByJobTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(AgentGetAiCostsByJobTool.class);

    @Inject
    AiCallStore aiCallStore;

    @Override
    public String name() {
        return "agent_get_ai_costs_by_job";
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
            List<AiCallRecord> calls = aiCallStore.findByJobId(jobId);
            if (calls.isEmpty()) {
                return "No AI calls found for job: " + jobId;
            }

            long totalInput = calls.stream().mapToLong(AiCallRecord::inputTokens).sum();
            long totalOutput = calls.stream().mapToLong(AiCallRecord::outputTokens).sum();
            long totalCacheWrite = calls.stream().mapToLong(AiCallRecord::cacheCreationInputTokens).sum();
            long totalCacheRead = calls.stream().mapToLong(AiCallRecord::cacheReadInputTokens).sum();
            long totalDurationMs = calls.stream().mapToLong(AiCallRecord::durationMs).sum();
            double estimatedCost = aiCallStore.estimateCost(totalInput, totalOutput, totalCacheWrite, totalCacheRead);

            StringBuilder sb = new StringBuilder();
            sb.append("AI Costs for Job: ").append(jobId).append("\n\n");
            sb.append("Total Calls: ").append(calls.size()).append("\n");
            sb.append("Total Input Tokens: ").append(totalInput).append("\n");
            sb.append("Total Output Tokens: ").append(totalOutput).append("\n");
            sb.append("Total Cache Write Tokens: ").append(totalCacheWrite).append("\n");
            sb.append("Total Cache Read Tokens: ").append(totalCacheRead).append("\n");
            sb.append("Total Duration: ").append(formatDuration(totalDurationMs)).append("\n");
            sb.append("Estimated Cost: $").append(String.format("%.4f", estimatedCost)).append("\n\n");

            sb.append("Call Details:\n");
            for (int i = 0; i < calls.size(); i++) {
                AiCallRecord call = calls.get(i);
                sb.append("  ").append(i + 1).append(". ");
                sb.append("Model: ").append(call.model()).append(" | ");
                sb.append("In: ").append(call.inputTokens()).append(" | ");
                sb.append("Out: ").append(call.outputTokens()).append(" | ");
                sb.append("Cost: $").append(String.format("%.4f",
                        aiCallStore.estimateCost(call.inputTokens(), call.outputTokens(),
                                call.cacheCreationInputTokens(), call.cacheReadInputTokens())));
                sb.append("\n");
            }

            return sb.toString();
        } catch (Exception e) {
            LOG.errorf("Failed to get AI costs by job: %s", e.getMessage());
            return "ERROR: Failed to get AI costs by job: " + e.getMessage();
        }
    }

    private String formatDuration(long ms) {
        if (ms < 1000) {
            return ms + "ms";
        }
        if (ms < 60000) {
            return String.format("%.1fs", ms / 1000.0);
        }
        long minutes = ms / 60000;
        long seconds = (ms % 60000) / 1000;
        return minutes + "m " + seconds + "s";
    }
}
