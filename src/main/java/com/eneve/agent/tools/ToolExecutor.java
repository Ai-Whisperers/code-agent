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

    /**
     * Authorization gate called by the agent loop before every tool execution.
     *
     * <p>Returns {@code true} if this tool is permitted to execute in the given workspace
     * context. The default implementation always permits execution — override this method
     * in write-capable tools to enforce additional restrictions such as read-only workspace
     * mode, user-level permission checks, or environment guards.
     *
     * <p>When this method returns {@code false}, the agent loop returns an
     * {@code "UNAUTHORIZED"} error string to Claude instead of calling
     * {@link #execute(WorkspaceContext, java.util.Map)}, providing a defence-in-depth
     * layer independent of the {@code ToolDefinitions} mode that was used to build the
     * tool list.
     *
     * @param workspace the current workspace context; may be {@code null} in chat mode
     * @return {@code true} to allow execution; {@code false} to block it
     */
    default boolean isAuthorized(WorkspaceContext workspace) {
        return true;
    }
}
