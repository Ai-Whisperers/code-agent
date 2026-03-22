package com.eneve.agent.mcp;

import java.util.Map;

import com.eneve.agent.planner.PlanStore;
import com.eneve.agent.tools.ToolExecutor;
import com.eneve.agent.workspace.WorkspaceContext;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * MCP tool: Read the current markdown content of the active plan.
 * The plan ID is resolved from workspace metadata (set by the /implement endpoint).
 */
@ApplicationScoped
public class PlanReadTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(PlanReadTool.class);

    @Inject
    PlanStore planStore;

    @Override
    public String name() {
        return "plan_read";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String planId = workspace.getMetadata("planId");
        if (planId == null || planId.isBlank()) {
            return "ERROR: No active plan in context";
        }

        return planStore.find(planId)
                .map(plan -> {
                    String content = plan.markdownContent();
                    if (content == null || content.isBlank()) {
                        return "Plan has no markdown content yet.";
                    }
                    LOG.debugf("plan_read: returned %d chars for plan %s", content.length(), planId);
                    return content;
                })
                .orElse("ERROR: Plan not found: " + planId);
    }
}
