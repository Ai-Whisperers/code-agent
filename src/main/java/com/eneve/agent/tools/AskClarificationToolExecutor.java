package com.eneve.agent.tools;

import com.eneve.agent.workspace.WorkspaceContext;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;

/**
 * Satisfies the {@link ToolExecutor} contract for the {@code ask_clarification} tool.
 *
 * <p>This executor is never invoked at runtime — {@code ClaudeToolUseLoop} intercepts
 * {@code ask_clarification} calls before they reach {@link ToolRegistry#get(String)} and
 * handles them by emitting a {@code ClarificationRequest} event directly. This bean exists
 * solely so that {@code ToolDefinitionsContractTest} can verify that every tool schema
 * included in a mode has a corresponding registered executor.
 */
@ApplicationScoped
public class AskClarificationToolExecutor implements ToolExecutor {

    @Override
    public String name() {
        return "ask_clarification";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        // Should never be reached — the agent loop intercepts this tool before dispatch.
        return "Questions have been presented to the user. Await their response before proceeding.";
    }
}
