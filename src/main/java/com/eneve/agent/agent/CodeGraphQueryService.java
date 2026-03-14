package com.eneve.agent.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CodeGraphQueryService {

    private static final Logger LOG = Logger.getLogger(CodeGraphQueryService.class);
    private static final int MAX_OUTPUT_CHARS = 3000;
    private static final int MAX_DIAGRAM_CHARS = 2000;
    private static final int MAX_FILES_FOR_DIAGRAM = 5;
    private static final int MAX_SYMBOLS_PER_FILE = 10;
    private static final int MAX_EDGES_PER_SYMBOL = 5;

    @Inject
    CodeGraphStore store;

    public String buildImpactSection(String workspace, String repoSlug, List<String> changedFiles) {
        List<String> sourceFiles = changedFiles.stream()
                .filter(f -> f.endsWith(".java") || f.endsWith(".cs"))
                .toList();

        if (sourceFiles.isEmpty()) {
            return "";
        }

        Map<String, List<CodeGraphStore.EdgeResult>> callersMap = new LinkedHashMap<>();
        Map<String, List<CodeGraphStore.EdgeResult>> implMap = new LinkedHashMap<>();

        for (String filePath : sourceFiles) {
            List<String> symbols = store.findSymbolsInFile(workspace, repoSlug, filePath);
            for (String symbol : symbols) {
                List<CodeGraphStore.EdgeResult> callers = store.findCallers(workspace, repoSlug, symbol);
                if (!callers.isEmpty()) {
                    callersMap.put(symbol, callers);
                }

                if (!symbol.contains(".")) {
                    List<CodeGraphStore.EdgeResult> impls = store.findImplementations(workspace, repoSlug, symbol);
                    if (!impls.isEmpty()) {
                        implMap.put(symbol, impls);
                    }
                }
            }
        }

        if (callersMap.isEmpty() && implMap.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## Impact Analysis (from code graph)\n");
        sb.append("The following code depends on symbols changed in this PR:\n\n");

        for (var entry : callersMap.entrySet()) {
            if (sb.length() > MAX_OUTPUT_CHARS) {
                sb.append("- ... (truncated)\n");
                break;
            }
            sb.append("- **").append(entry.getKey()).append("** is called by:\n");
            for (CodeGraphStore.EdgeResult edge : entry.getValue()) {
                String location = edge.sourceFile() != null ? " (" + edge.sourceFile() + ")" : "";
                sb.append("  - ").append(edge.sourceNode()).append(location).append("\n");
            }
        }

        for (var entry : implMap.entrySet()) {
            if (sb.length() > MAX_OUTPUT_CHARS) {
                sb.append("- ... (truncated)\n");
                break;
            }
            sb.append("- **").append(entry.getKey()).append("** is implemented/extended by:\n");
            for (CodeGraphStore.EdgeResult edge : entry.getValue()) {
                String location = edge.sourceFile() != null ? " (" + edge.sourceFile() + ")" : "";
                sb.append("  - ").append(edge.sourceNode()).append(location).append("\n");
            }
        }

        sb.append("\n");

        if (sb.length() > MAX_OUTPUT_CHARS) {
            return sb.substring(0, MAX_OUTPUT_CHARS) + "\n... (truncated)\n";
        }

        LOG.debugf("Built impact section (%d chars) for %s/%s from %d changed files",
                sb.length(), workspace, repoSlug, sourceFiles.size());
        return sb.toString();
    }

    /**
     * Builds a structured description of call relationships and class hierarchies
     * for the symbols changed in this PR. Used as context for LLM-generated Mermaid diagrams.
     */
    public String buildDiagramContext(String workspace, String repoSlug, List<String> changedFiles) {
        List<String> sourceFiles = changedFiles.stream()
                .filter(f -> f.endsWith(".java") || f.endsWith(".cs"))
                .limit(MAX_FILES_FOR_DIAGRAM)
                .toList();

        if (sourceFiles.isEmpty()) {
            return "";
        }

        StringBuilder calls = new StringBuilder();
        StringBuilder hierarchy = new StringBuilder();

        for (String filePath : sourceFiles) {
            List<String> symbols = store.findSymbolsInFile(workspace, repoSlug, filePath)
                    .stream().limit(MAX_SYMBOLS_PER_FILE).toList();

            for (String symbol : symbols) {
                if (calls.length() + hierarchy.length() > MAX_DIAGRAM_CHARS) {
                    break;
                }

                if (symbol.contains(".")) {
                    // Method symbol: gather callers and callees
                    List<CodeGraphStore.EdgeResult> callers =
                            store.findCallers(workspace, repoSlug, symbol)
                                    .stream().limit(MAX_EDGES_PER_SYMBOL).toList();
                    List<CodeGraphStore.EdgeResult> callees =
                            store.findCallees(workspace, repoSlug, symbol)
                                    .stream().limit(MAX_EDGES_PER_SYMBOL).toList();

                    if (!callers.isEmpty()) {
                        calls.append("- `").append(symbol).append("` ← called by: ");
                        calls.append(callers.stream()
                                .map(CodeGraphStore.EdgeResult::sourceNode)
                                .reduce((a, b) -> a + ", " + b).orElse(""));
                        calls.append("\n");
                    }
                    if (!callees.isEmpty()) {
                        calls.append("- `").append(symbol).append("` → calls: ");
                        calls.append(callees.stream()
                                .map(CodeGraphStore.EdgeResult::sourceNode)
                                .reduce((a, b) -> a + ", " + b).orElse(""));
                        calls.append("\n");
                    }
                } else {
                    // Type symbol: gather implementations/extensions
                    List<CodeGraphStore.EdgeResult> impls =
                            store.findImplementations(workspace, repoSlug, symbol)
                                    .stream().limit(MAX_EDGES_PER_SYMBOL).toList();

                    if (!impls.isEmpty()) {
                        hierarchy.append("- `").append(symbol).append("` ← implemented/extended by: ");
                        hierarchy.append(impls.stream()
                                .map(CodeGraphStore.EdgeResult::sourceNode)
                                .reduce((a, b) -> a + ", " + b).orElse(""));
                        hierarchy.append("\n");
                    }
                }
            }
        }

        if (calls.isEmpty() && hierarchy.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## Code Relationships (for diagram generation)\n");
        if (!calls.isEmpty()) {
            sb.append("\n### Call Graph\n").append(calls);
        }
        if (!hierarchy.isEmpty()) {
            sb.append("\n### Class Hierarchy\n").append(hierarchy);
        }

        String result = sb.toString();
        if (result.length() > MAX_DIAGRAM_CHARS) {
            result = result.substring(0, MAX_DIAGRAM_CHARS) + "\n... (truncated)\n";
        }
        LOG.debugf("Built diagram context (%d chars) for %s/%s", result.length(), workspace, repoSlug);
        return result;
    }
}
