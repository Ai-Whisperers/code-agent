package com.eneve.agent.tools;

import java.util.List;
import java.util.Map;

import com.eneve.agent.agent.store.CodeGraphStore;
import com.eneve.agent.workspace.WorkspaceContext;

import com.eneve.agent.settings.SettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class QueryCodeGraphTool implements ToolExecutor {

    @Inject
    CodeGraphStore codeGraphStore;

    @Inject
    SettingsService settings;

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
        String scope = input.get("scope") instanceof String s ? s : "repo";

        if (symbol == null || symbol.isBlank()) {
            return "ERROR: 'symbol' parameter is required";
        }
        if (relation == null || relation.isBlank()) {
            return "ERROR: 'relation' parameter is required (one of: callers, implementations, dependents)";
        }

        String ws = workspace.getMetadata("workspace");

        String repo = input.get("repoSlug") instanceof String s && !s.isBlank()
                ? s : workspace.getMetadata("repoSlug");
        if (repo == null) {
            String available = workspace.getMetadata("productRepos");
            return "ERROR: repoSlug is required. Call set_product_context to establish active product context, " +
                   "or use the 'repoSlug' parameter to specify which repository to query."
                    + (available != null ? " Known repos: " + available : "");
        }

        if (ws != null && "workspace".equalsIgnoreCase(scope) && Boolean.parseBoolean(settings.get("code-graph.cross-repo.enabled", "true"))) {
            return executeWorkspaceScope(ws, repo, symbol, relation);
        }

        if (ws != null) {
            return executeRepoScope(ws, repo, symbol, relation);
        }
        return executeRepoScopeNoWorkspace(repo, symbol, relation);
    }

    private String executeRepoScope(String ws, String repo, String symbol, String relation) {
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

    private String executeRepoScopeNoWorkspace(String repo, String symbol, String relation) {
        List<CodeGraphStore.EdgeResult> results;
        String label;

        switch (relation.toLowerCase()) {
            case "callers" -> {
                results = codeGraphStore.findCallersByRepo(repo, symbol);
                label = "Callers of " + symbol;
            }
            case "implementations" -> {
                results = codeGraphStore.findImplementationsByRepo(repo, symbol);
                label = "Implementations/extensions of " + symbol;
            }
            case "dependents" -> {
                results = codeGraphStore.findDependentsByRepo(repo, symbol);
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

    private String executeWorkspaceScope(String ws, String repo, String symbol, String relation) {
        if (!relation.equalsIgnoreCase("callers") && !relation.equalsIgnoreCase("implementations")
                && !relation.equalsIgnoreCase("dependents")) {
            return "ERROR: Invalid relation '" + relation + "'. Must be one of: callers, implementations, dependents";
        }

        // First do the per-repo query so the caller still gets local results
        String localResults = executeRepoScope(ws, repo, symbol, relation);

        // Then check cross-repo fan-out
        int count = codeGraphStore.countDistinctReposUsing(ws, repo, symbol);
        if (count == 0) {
            return localResults + "\nCross-repo (workspace): (none found in other repositories)";
        }

        StringBuilder sb = new StringBuilder(localResults);
        sb.append("\nCross-repo (workspace-wide):\n");

        if (count >= Integer.parseInt(settings.get("code-graph.cross-repo.critical-threshold", "3"))) {
            sb.append("- **CRITICAL**: `").append(symbol)
                    .append("` is used across **").append(count)
                    .append("** other repositories — this is a widely-shared symbol. ")
                    .append("Changes here have a wide blast radius.\n");
            return sb.toString();
        }

        List<CodeGraphStore.CrossRepoEdgeResult> crossResults = switch (relation.toLowerCase()) {
            case "implementations" -> codeGraphStore.findImplementationsAcrossWorkspace(ws, repo, symbol);
            case "dependents"      -> codeGraphStore.findDependentsAcrossWorkspace(ws, repo, symbol);
            default                -> codeGraphStore.findCallersAcrossWorkspace(ws, repo, symbol);
        };

        if (crossResults.isEmpty()) {
            sb.append("(none found in other repositories)\n");
        } else {
            for (CodeGraphStore.CrossRepoEdgeResult edge : crossResults) {
                sb.append("- [").append(edge.repoSlug()).append("] ").append(edge.sourceNode());
                if (edge.sourceFile() != null) {
                    sb.append(" (").append(edge.sourceFile()).append(")");
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }
}
