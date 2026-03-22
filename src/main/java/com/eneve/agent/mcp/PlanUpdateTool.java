package com.eneve.agent.mcp;

import com.eneve.agent.planner.PlanStore;
import com.eneve.agent.tools.ToolExecutor;
import com.eneve.agent.workspace.WorkspaceContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * MCP tool: Update the markdown content of the active plan.
 * The plan ID is resolved from workspace metadata (set by the /implement endpoint).
 * The AI uses this to tick off completed tasks and add execution notes inline.
 */
@ApplicationScoped
public class PlanUpdateTool implements ToolExecutor {

    private static final Logger LOG = Logger.getLogger(PlanUpdateTool.class);

    @Inject
    PlanStore planStore;

    @Override
    public String name() {
        return "plan_update";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String planId = workspace.getMetadata("planId");
        if (planId == null || planId.isBlank()) {
            return "ERROR: No active plan in context";
        }

        String markdownContent = (String) input.get("markdownContent");
        if (markdownContent == null || markdownContent.isBlank()) {
            return "ERROR: 'markdownContent' parameter is required";
        }

        if (planStore.find(planId).isEmpty()) {
            return "ERROR: Plan not found: " + planId;
        }

        planStore.updateMarkdownContent(planId, markdownContent);
        LOG.debugf("plan_update: updated %d chars for plan %s", markdownContent.length(), planId);
        return "Plan updated";
    }
}
