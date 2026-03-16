package com.eneve.agent.tools;

import com.eneve.agent.workspace.WorkspaceContext;

/**
 * Dispatches tool calls from the Claude agent loop to the appropriate executor.
 */
public interface ToolExecutor {

    String name();

    String execute(WorkspaceContext workspace, java.util.Map<String, Object> input);

    /**
     * Returns true when this tool only reads state and never mutates the workspace.
     * Read-only tools can be dispatched in parallel within a single agent loop iteration.
     */
    default boolean isReadOnly() {
        return false;
    }
}
