package com.eneve.agent.mcp;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import com.eneve.agent.agent.AiCallStore;
import com.eneve.agent.tools.ToolExecutor;
import com.eneve.agent.workspace.WorkspaceContext;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * MCP tool: Get daily aggregated AI call statistics.
 */
@ApplicationScoped
public class AgentGetDailyAiStatsTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(AgentGetDailyAiStatsTool.class);

    @Inject
    AiCallStore aiCallStore;

    @Override
    public String name() {
        return "agent_get_daily_ai_stats";
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
            List<Map<String, Object>> daily = aiCallStore.getDailySummary(from, to);

            if (daily.isEmpty()) {
                return "No daily AI statistics found for the specified time range.";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Daily AI Call Statistics:\n\n");

            for (Map<String, Object> row : daily) {
                String date = (String) row.get("date");
                long totalCalls = ((Number) row.get("totalCalls")).longValue();
                long totalInput = ((Number) row.get("totalInputTokens")).longValue();
                long totalOutput = ((Number) row.get("totalOutputTokens")).longValue();
                double estimatedCost = ((Number) row.get("estimatedCostUsd")).doubleValue();

                sb.append(date).append(":\n");
                sb.append("  Calls: ").append(totalCalls).append("\n");
                sb.append("  Input Tokens: ").append(totalInput).append("\n");
                sb.append("  Output Tokens: ").append(totalOutput).append("\n");
                sb.append("  Estimated Cost: $").append(String.format("%.4f", estimatedCost)).append("\n\n");
            }

            return sb.toString();
        } catch (Exception e) {
            LOG.errorf("Failed to get daily AI stats: %s", e.getMessage());
            return "ERROR: Failed to get daily AI stats: " + e.getMessage();
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
