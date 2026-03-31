package com.eneve.agent.agent.service;

import com.eneve.agent.agent.store.KnowledgeEmbeddingStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Thin search facade: embeds the user query via AWS Bedrock and executes
 * a pgvector cosine-similarity search over the {@code knowledge_embeddings} table.
 *
 * <p>Supported source types: {@code jira}, {@code confluence},
 * {@code jira-attachment}, {@code web-docs}, {@code static-file}.
 */
@ApplicationScoped
public class KnowledgeSearchService {

    private static final Logger LOG = Logger.getLogger(KnowledgeSearchService.class);
    private static final int DEFAULT_TOP_K = 10;
    private static final int MAX_TOP_K = 25;

    @Inject
    BedrockEmbeddingService bedrockService;

    @Inject
    KnowledgeEmbeddingStore store;

    /**
     * Search the knowledge base by semantic similarity.
     *
     * @param query       natural-language question or keywords
     * @param sourceTypes optional whitelist of source types ('jira', 'confluence', 'jira-attachment',
     *                    'web-docs', 'static-file'); null or empty means all sources
     * @param topK        number of results (clamped to 1–25)
     * @return ranked list of matching knowledge chunks
     */
    public List<KnowledgeEmbeddingStore.KnowledgeSearchResult> search(
            String query,
            List<String> sourceTypes,
            int topK) {

        LOG.infof("Searching for query: %s", query);

        if (!bedrockService.isConfigured()) {
            LOG.warn("Bedrock embedding not configured — knowledge search unavailable");
            return List.of();
        }

        int k = Math.min(Math.max(1, topK), MAX_TOP_K);
        float[] vector = bedrockService.embedSingleText(query, "query");
        if (vector == null) {
            LOG.warnf("Failed to embed query: %s", query);
            return List.of();
        }

        List<KnowledgeEmbeddingStore.KnowledgeSearchResult> results =
                store.searchSimilar(vector, k, sourceTypes);

        LOG.debugf("Knowledge search '%s': %d results (sources=%s)", query, results.size(), sourceTypes);
        return results;
    }

    /**
     * Convenience overload with default topK.
     */
    public List<KnowledgeEmbeddingStore.KnowledgeSearchResult> search(
            String query, List<String> sourceTypes) {
        return search(query, sourceTypes, DEFAULT_TOP_K);
    }
}
