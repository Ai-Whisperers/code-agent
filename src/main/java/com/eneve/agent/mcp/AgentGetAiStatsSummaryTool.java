package com.eneve.agent.mcp;

import com.eneve.agent.agent.store.AiCallStore;
import com.eneve.agent.tools.ToolExecutor;
import com.eneve.agent.workspace.WorkspaceContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * MCP tool: Get aggregated AI call statistics and cost summary.
 */
@ApplicationScoped
public class AgentGetAiStatsSummaryTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(AgentGetAiStatsSummaryTool.class);

    @Inject
    AiCallStore aiCallStore;

    @Override
    public String name() {
        return "agent_get_ai_stats_summary";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        Instant from = parseInstant((String) input.get("from"));
        Instant to = parseInstant((String) input.get("to"));

        try {
            List<Map<String, Object>> summary = aiCallStore.getSummary(from, to);

            if (summary.isEmpty()) {
                return "No AI call statistics found for the specified time range.";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("AI Call Summary:\n\n");

            for (Map<String, Object> row : summary) {
                String model = (String) row.get("model");
                String jobType = (String) row.get("jobType");
                long totalCalls = ((Number) row.get("totalCalls")).longValue();
                long totalInput = ((Number) row.get("totalInputTokens")).longValue();
                long totalOutput = ((Number) row.get("totalOutputTokens")).longValue();
                double estimatedCost = ((Number) row.get("estimatedCostUsd")).doubleValue();

                sb.append("Model: ").append(model != null ? model : "Unknown").append("\n");
                sb.append("  Job Type: ").append(jobType != null ? jobType : "All").append("\n");
                sb.append("  Calls: ").append(totalCalls).append("\n");
                sb.append("  Input Tokens: ").append(totalInput).append("\n");
                sb.append("  Output Tokens: ").append(totalOutput).append("\n");
                sb.append("  Estimated Cost: $").append(String.format("%.4f", estimatedCost)).append("\n\n");
            }

            return sb.toString();
        } catch (Exception e) {
            LOG.errorf("Failed to get AI stats summary: %s", e.getMessage());
            return "ERROR: Failed to get AI stats summary: " + e.getMessage();
        }
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
