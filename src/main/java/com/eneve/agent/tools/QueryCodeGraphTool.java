package com.eneve.agent.tools;

import java.util.List;
import java.util.Map;

import com.eneve.agent.agent.CodeGraphStore;
import com.eneve.agent.workspace.WorkspaceContext;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class QueryCodeGraphTool implements ToolExecutor {

    @Inject
    CodeGraphStore codeGraphStore;

    @Override
    public String name() {
        return "query_code_graph";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String symbol = (String) input.get("symbol");
        String relation = (String) input.get("relation");

        if (symbol == null || symbol.isBlank()) {
            return "ERROR: 'symbol' parameter is required";
        }
        if (relation == null || relation.isBlank()) {
            return "ERROR: 'relation' parameter is required (one of: callers, implementations, dependents)";
        }

        String ws = workspace.getMetadata("workspace");
        String repo = workspace.getMetadata("repoSlug");
        if (ws == null || repo == null) {
            return "ERROR: Code graph coordinates not available in this context";
        }

        List<CodeGraphStore.EdgeResult> results;
        String label;

        switch (relation.toLowerCase()) {
            case "callers" -> {
                results = codeGraphStore.findCallers(ws, repo, symbol);
                label = "Callers of " + symbol;
            }
            case "implementations" -> {
                results = codeGraphStore.findImplementations(ws, repo, symbol);
                label = "Implementations/extensions of " + symbol;
            }
            case "dependents" -> {
                results = codeGraphStore.findDependents(ws, repo, symbol);
                label = "Dependents of " + symbol;
            }
            default -> {
                return "ERROR: Invalid relation '" + relation + "'. Must be one of: callers, implementations, dependents";
            }
        }

        if (results.isEmpty()) {
            return label + ": (none found)";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(label).append(":\n");
        for (CodeGraphStore.EdgeResult edge : results) {
            sb.append("- ").append(edge.sourceNode());
            if (edge.sourceFile() != null) {
                sb.append(" (").append(edge.sourceFile()).append(")");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
