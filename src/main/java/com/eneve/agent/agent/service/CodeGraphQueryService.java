package com.eneve.agent.agent.service;

import com.eneve.agent.agent.store.CodeGraphStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    @ConfigProperty(name = "code-graph.cross-repo.enabled", defaultValue = "true")
    boolean crossRepoEnabled;

    @ConfigProperty(name = "code-graph.cross-repo.critical-threshold", defaultValue = "3")
    int criticalThreshold;

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

        boolean hasCrossRepo = crossRepoEnabled && buildCrossRepoImpact(workspace, repoSlug, sourceFiles, new StringBuilder()) > 0;

        if (callersMap.isEmpty() && implMap.isEmpty() && !hasCrossRepo) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## Impact Analysis (from code graph)\n");
        sb.append("The following code depends on symbols changed in this PR:\n\n");

        for (var entry : callersMap.entrySet()) {
            if (sb.length() > MAX_OUTPUT_CHARS / 2) {
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
            if (sb.length() > MAX_OUTPUT_CHARS / 2) {
                sb.append("- ... (truncated)\n");
                break;
            }
            sb.append("- **").append(entry.getKey()).append("** is implemented/extended by:\n");
            for (CodeGraphStore.EdgeResult edge : entry.getValue()) {
                String location = edge.sourceFile() != null ? " (" + edge.sourceFile() + ")" : "";
                sb.append("  - ").append(edge.sourceNode()).append(location).append("\n");
            }
        }

        if (crossRepoEnabled) {
            StringBuilder crossRepo = new StringBuilder();
            int crossRepoCount = buildCrossRepoImpact(workspace, repoSlug, sourceFiles, crossRepo);
            if (crossRepoCount > 0) {
                sb.append("\n### Cross-repo impact\n");
                sb.append(crossRepo);
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
     * Builds the cross-repo impact subsection and appends it to {@code out}.
     * Returns the number of symbols that had any cross-repo usage (used to decide
     * whether the subsection should be included at all, without running the queries twice).
     */
    private int buildCrossRepoImpact(String workspace, String repoSlug,
                                     List<String> sourceFiles, StringBuilder out) {
        int symbolsWithCrossRepoUsage = 0;
        int remainingBudget = MAX_OUTPUT_CHARS / 2;

        for (String filePath : sourceFiles) {
            List<String> symbols = store.findSymbolsInFile(workspace, repoSlug, filePath);
            for (String symbol : symbols) {
                if (out.length() >= remainingBudget) {
                    out.append("- ... (truncated)\n");
                    return symbolsWithCrossRepoUsage;
                }

                int count = store.countDistinctReposUsing(workspace, repoSlug, symbol);
                if (count == 0) {
                    continue;
                }

                symbolsWithCrossRepoUsage++;

                if (count >= criticalThreshold) {
                    out.append("- **CRITICAL**: `").append(symbol)
                            .append("` is used across **").append(count)
                            .append("** other repositories — changes here have a wide blast radius. ")
                            .append("Exercise extra caution.\n");
                } else {
                    // Detail mode: list individual callers grouped by repo
                    List<CodeGraphStore.CrossRepoEdgeResult> callers =
                            store.findCallersAcrossWorkspace(workspace, repoSlug, symbol);
                    if (symbol.contains(".")) {
                        // Method symbol: show callers
                        if (!callers.isEmpty()) {
                            out.append("- **`").append(symbol).append("`** is called from other repos:\n");
                            for (CodeGraphStore.CrossRepoEdgeResult edge : callers) {
                                String location = edge.sourceFile() != null ? " (" + edge.sourceFile() + ")" : "";
                                out.append("  - ").append(edge.repoSlug()).append(": ")
                                        .append(edge.sourceNode()).append(location).append("\n");
                            }
                        }
                    } else {
                        // Type symbol: show implementations/extensions
                        List<CodeGraphStore.CrossRepoEdgeResult> impls =
                                store.findImplementationsAcrossWorkspace(workspace, repoSlug, symbol);
                        if (!impls.isEmpty()) {
                            out.append("- **`").append(symbol).append("`** is implemented/extended in other repos:\n");
                            for (CodeGraphStore.CrossRepoEdgeResult edge : impls) {
                                String location = edge.sourceFile() != null ? " (" + edge.sourceFile() + ")" : "";
                                out.append("  - ").append(edge.repoSlug()).append(": ")
                                        .append(edge.sourceNode()).append(location).append("\n");
                            }
                        } else if (!callers.isEmpty()) {
                            out.append("- **`").append(symbol).append("`** is used in other repos:\n");
                            for (CodeGraphStore.CrossRepoEdgeResult edge : callers) {
                                String location = edge.sourceFile() != null ? " (" + edge.sourceFile() + ")" : "";
                                out.append("  - ").append(edge.repoSlug()).append(": ")
                                        .append(edge.sourceNode()).append(location).append("\n");
                            }
                        }
                    }
                }
            }
        }
        return symbolsWithCrossRepoUsage;
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

        if (crossRepoEnabled) {
            StringBuilder crossNotes = new StringBuilder();
            for (String filePath : sourceFiles) {
                List<String> symbols = store.findSymbolsInFile(workspace, repoSlug, filePath)
                        .stream().limit(MAX_SYMBOLS_PER_FILE).toList();
                for (String symbol : symbols) {
                    if (sb.length() + crossNotes.length() > MAX_DIAGRAM_CHARS) break;
                    int count = store.countDistinctReposUsing(workspace, repoSlug, symbol);
                    if (count >= criticalThreshold) {
                        crossNotes.append("- `").append(symbol)
                                .append("` is a **cross-repo critical symbol** (used in ")
                                .append(count).append(" other repos)\n");
                    } else if (count > 0) {
                        crossNotes.append("- `").append(symbol)
                                .append("` has cross-repo usage (").append(count).append(" other repo(s))\n");
                    }
                }
            }
            if (!crossNotes.isEmpty()) {
                sb.append("\n### Cross-repo Notes\n").append(crossNotes);
            }
        }

        String result = sb.toString();
        if (result.length() > MAX_DIAGRAM_CHARS) {
            result = result.substring(0, MAX_DIAGRAM_CHARS) + "\n... (truncated)\n";
        }
        LOG.debugf("Built diagram context (%d chars) for %s/%s", result.length(), workspace, repoSlug);
        return result;
    }
}
