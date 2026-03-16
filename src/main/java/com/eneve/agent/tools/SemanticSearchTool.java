package com.eneve.agent.tools;

import java.util.List;
import java.util.Map;

import com.eneve.agent.agent.EmbeddingStore;
import com.eneve.agent.agent.VoyageEmbeddingService;
import com.eneve.agent.workspace.WorkspaceContext;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Semantic code search tool for the Claude agent loop.
 * Searches across all vector-indexed repositories by meaning, letting the agent
 * find library implementations, shared utilities, and related code in other repos.
 */
@ApplicationScoped
public class SemanticSearchTool implements ToolExecutor {

    private static final int DEFAULT_TOP_K = 10;
    private static final int MAX_TOP_K = 25;
    private static final int MAX_SOURCE_DISPLAY_CHARS = 2000;

    @Inject VoyageEmbeddingService voyageService;
    @Inject EmbeddingStore embeddingStore;

    @Override
    public String name() {
        return "semantic_search";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        if (!voyageService.isConfigured()) {
            return "ERROR: Semantic search is not configured (no Voyage API key set)";
        }

        String query = (String) input.get("query");
        if (query == null || query.isBlank()) {
            return "ERROR: 'query' parameter is required";
        }

        String repo = (String) input.get("repo");
        int topK = DEFAULT_TOP_K;
        Object topKObj = input.get("top_k");
        if (topKObj instanceof Number n) {
            topK = Math.min(Math.max(n.intValue(), 1), MAX_TOP_K);
        }

        String ws = workspace.getMetadata("workspace");
        if (ws == null) {
            return "ERROR: Workspace context not available for semantic search";
        }

        float[] queryVector = voyageService.embedSingle(query, "query");
        if (queryVector == null) {
            return "ERROR: Failed to generate embedding for query";
        }

        List<EmbeddingStore.SearchResult> results;
        if (repo != null && !repo.isBlank()) {
            results = embeddingStore.searchSimilar(queryVector, ws, repo, topK);
        } else {
            results = embeddingStore.searchSimilar(queryVector, ws, topK);
        }

        if (results.isEmpty()) {
            return "No semantic matches found for: " + query
                    + (repo != null ? " (in repo: " + repo + ")" : " (across all indexed repos)");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Semantic search results for: ").append(query).append("\n");
        sb.append("Found ").append(results.size()).append(" matches");
        if (repo != null) {
            sb.append(" in ").append(repo);
        } else {
            sb.append(" across indexed repos");
        }
        sb.append(":\n\n");

        for (int i = 0; i < results.size(); i++) {
            EmbeddingStore.SearchResult r = results.get(i);
            sb.append("--- Result ").append(i + 1)
              .append(" [score: ").append(String.format("%.3f", r.score())).append("] ---\n");
            sb.append("Repo: ").append(r.repoSlug()).append("\n");
            sb.append("File: ").append(r.filePath()).append("\n");
            sb.append("Symbol: ").append(r.symbolName())
              .append(" (").append(r.symbolType()).append(")\n");
            if (r.lineStart() != null) {
                sb.append("Lines: ").append(r.lineStart());
                if (r.lineEnd() != null) {
                    sb.append("-").append(r.lineEnd());
                }
                sb.append("\n");
            }
            sb.append("Source:\n");

            String source = r.sourceText();
            if (source.length() > MAX_SOURCE_DISPLAY_CHARS) {
                source = source.substring(0, MAX_SOURCE_DISPLAY_CHARS) + "\n... [truncated]";
            }
            sb.append(source).append("\n\n");
        }

        return sb.toString();
    }
}
