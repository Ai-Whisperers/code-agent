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
}
