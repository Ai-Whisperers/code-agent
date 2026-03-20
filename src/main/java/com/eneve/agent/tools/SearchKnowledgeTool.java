package com.eneve.agent.tools;

import java.util.List;
import java.util.Map;

import com.eneve.agent.agent.KnowledgeEmbeddingStore;
import com.eneve.agent.agent.KnowledgeSearchService;
import com.eneve.agent.workspace.WorkspaceContext;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Claude tool that searches the unified knowledge base (Jira issues,
 * Confluence pages, and Jira attachments) by semantic similarity.
 */
@ApplicationScoped
public class SearchKnowledgeTool implements ToolExecutor {

    private static final int MAX_CHUNK_DISPLAY_CHARS = 1500;

    @Inject
    KnowledgeSearchService searchService;

    @Override
    public String name() {
        return "search_knowledge_base";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String execute(WorkspaceContext workspace, Map<String, Object> input) {
        String query = (String) input.get("query");
        if (query == null || query.isBlank()) {
            return "ERROR: 'query' parameter is required";
        }

        @SuppressWarnings("unchecked")
        List<String> sourceTypes = input.get("sourceTypes") instanceof List<?>
                ? (List<String>) input.get("sourceTypes") : null;

        String productId = (String) input.get("productId");

        int topK = 10;
        Object topKObj = input.get("topK");
        if (topKObj instanceof Number n) {
            topK = Math.min(Math.max(n.intValue(), 1), 25);
        }

        List<KnowledgeEmbeddingStore.KnowledgeSearchResult> results =
                searchService.search(query, sourceTypes, productId, topK);

        if (results.isEmpty()) {
            return "No results found in the knowledge base for: " + query;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Knowledge base search results for: ").append(query).append("\n");
        sb.append("Found ").append(results.size()).append(" result(s)");
        if (productId != null) sb.append(" (product: ").append(productId).append(")");
        sb.append(":\n\n");

        for (int i = 0; i < results.size(); i++) {
            KnowledgeEmbeddingStore.KnowledgeSearchResult r = results.get(i);
            sb.append("--- Result ").append(i + 1)
              .append(" [score: ").append(String.format("%.3f", r.score())).append("] ---\n");
            sb.append("Source: ").append(r.sourceType()).append(" / ").append(r.sourceId()).append("\n");
            if (r.title() != null && !r.title().isBlank()) {
                sb.append("Title: ").append(r.title()).append("\n");
            }

            // Append useful metadata fields
            Map<String, Object> meta = r.metadata();
            if (meta != null) {
                appendMeta(sb, meta, "status");
                appendMeta(sb, meta, "assignee");
                appendMeta(sb, meta, "labels");
                appendMeta(sb, meta, "url");
            }

            String chunk = r.contentChunk();
            if (chunk.length() > MAX_CHUNK_DISPLAY_CHARS) {
                chunk = chunk.substring(0, MAX_CHUNK_DISPLAY_CHARS) + "\n... [truncated]";
            }
            sb.append("Content:\n").append(chunk).append("\n\n");
        }

        return sb.toString();
    }

    private static void appendMeta(StringBuilder sb, Map<String, Object> meta, String key) {
        Object v = meta.get(key);
        if (v != null && !v.toString().isBlank()) {
            sb.append(capitalize(key)).append(": ").append(v).append("\n");
        }
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
