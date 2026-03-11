package com.eneve.agent.tools;

import com.eneve.agent.workspace.WorkspaceContext;

/**
 * Dispatches tool calls from the Claude agent loop to the appropriate executor.
 */
public interface ToolExecutor {

    String name();

    String execute(WorkspaceContext workspace, java.util.Map<String, Object> input);
}
