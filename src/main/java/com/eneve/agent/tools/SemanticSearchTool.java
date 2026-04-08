package com.eneve.agent.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.eneve.agent.agent.service.BedrockEmbeddingService.RerankResult;
import com.eneve.agent.agent.store.EmbeddingStore;
import com.eneve.agent.agent.service.BedrockEmbeddingService;
import com.eneve.agent.settings.SettingsService;
import com.eneve.agent.workspace.WorkspaceContext;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Semantic code search tool for the Claude agent loop.
 * Searches across all vector-indexed repositories by meaning, letting the agent
 * find library implementations, shared utilities, and related code in other repos.
 * <p>
 * Uses a two-stage retrieval pipeline: vector similarity search to fetch a broad
 * candidate set (topK * 3), followed by optional Bedrock reranking to reorder by
 * true semantic relevance, then score-threshold filtering.
 */
@ApplicationScoped
public class SemanticSearchTool implements ToolExecutor {

    private static final int DEFAULT_TOP_K = 10;
    private static final int MAX_TOP_K = 25;
    private static final int CANDIDATE_MULTIPLIER = 3;
    private static final int MAX_SOURCE_DISPLAY_CHARS = 2000;

    @Inject BedrockEmbeddingService bedrockService;
    @Inject EmbeddingStore embeddingStore;
    @Inject SettingsService settingsService;

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
        if (!bedrockService.isConfigured()) {
            return "ERROR: Semantic search is not configured (Bedrock credentials unavailable)";
        }

        String query = (String) input.get("query");
        if (query == null || query.isBlank()) {
            return "ERROR: 'query' parameter is required";
        }

        String repo = (String) input.get("repo");
        String repoSlug = (String) input.get("repoSlug");
        
        int topK = DEFAULT_TOP_K;
        Object topKObj = input.get("top_k");
        if (topKObj instanceof Number n) {
            topK = Math.min(Math.max(n.intValue(), 1), MAX_TOP_K);
        }

        String ws = workspace.getMetadata("workspace");
        
        // Use active product context if no explicit repo specified
        if (repo == null && repoSlug == null) {
            repoSlug = workspace.getMetadata("repoSlug");
        }

        float[] queryVector = bedrockService.embedSingle(query, "query");
        if (queryVector == null) {
            return "ERROR: Failed to generate embedding for query";
        }

        boolean rerankEnabled = Boolean.parseBoolean(
                settingsService.get("semantic-search.rerank.enabled", "true"));
        double scoreThreshold = Double.parseDouble(
                settingsService.get("semantic-search.score.threshold", "0.5"));

        // Fetch a wider candidate pool when reranking is enabled
        int candidateK = rerankEnabled ? Math.min(topK * CANDIDATE_MULTIPLIER, MAX_TOP_K * CANDIDATE_MULTIPLIER) : topK;

        List<EmbeddingStore.SearchResult> candidates;
        // Prioritize explicit repo URL over repoSlug
        if (ws != null && repo != null && !repo.isBlank()) {
            candidates = embeddingStore.searchSimilar(queryVector, ws, repo, candidateK);
        } else if (ws != null && repoSlug != null && !repoSlug.isBlank()) {
            candidates = embeddingStore.searchSimilar(queryVector, ws, repoSlug, candidateK);
        } else if (ws != null) {
            candidates = embeddingStore.searchSimilar(queryVector, ws, candidateK);
        } else if (repo != null && !repo.isBlank()) {
            candidates = embeddingStore.searchSimilarByRepo(queryVector, repo, candidateK);
        } else if (repoSlug != null && !repoSlug.isBlank()) {
            candidates = embeddingStore.searchSimilarByRepo(queryVector, repoSlug, candidateK);
        } else {
            candidates = embeddingStore.searchSimilarGlobal(queryVector, candidateK);
        }

        List<EmbeddingStore.SearchResult> results;
        if (rerankEnabled && candidates.size() > 1) {
            results = applyReranking(query, candidates, topK, scoreThreshold);
        } else {
            // No reranking: apply score threshold to raw cosine scores and truncate
            results = candidates.stream()
                    .filter(r -> r.score() >= scoreThreshold)
                    .limit(topK)
                    .toList();
        }

        if (results.isEmpty()) {
            String scope = repo != null ? " (in repo: " + repo + ")"
                    : repoSlug != null ? " (in repo: " + repoSlug + ")"
                    : ws != null ? " (in workspace: " + ws + ")"
                    : " (across all indexed repos)";
            return "No semantic matches found for: " + query + scope;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Semantic search results for: ").append(query).append("\n");
        sb.append("Found ").append(results.size()).append(" matches");
        if (repo != null) {
            sb.append(" in ").append(repo);
        } else if (repoSlug != null) {
            sb.append(" in ").append(repoSlug);
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

    /**
     * Reranks the candidate results using the Bedrock Rerank API and returns the top-K
     * results above the score threshold, preserving the original {@link EmbeddingStore.SearchResult}
     * metadata but ordered by rerank relevance.
     */
    private List<EmbeddingStore.SearchResult> applyReranking(
            String query, List<EmbeddingStore.SearchResult> candidates,
            int topK, double scoreThreshold) {

        List<String> sources = candidates.stream()
                .map(EmbeddingStore.SearchResult::sourceText)
                .toList();

        List<RerankResult> rerankResults = bedrockService.rerank(query, sources, topK);

        if (rerankResults.isEmpty()) {
            // Rerank failed — fall back to vector similarity order with threshold
            return candidates.stream()
                    .filter(r -> r.score() >= scoreThreshold)
                    .limit(topK)
                    .toList();
        }

        List<EmbeddingStore.SearchResult> reranked = new ArrayList<>();
        for (RerankResult rr : rerankResults) {
            if (rr.relevanceScore() < scoreThreshold) {
                continue;
            }
            if (rr.index() < candidates.size()) {
                reranked.add(candidates.get(rr.index()));
            }
        }
        return reranked;
    }
}
