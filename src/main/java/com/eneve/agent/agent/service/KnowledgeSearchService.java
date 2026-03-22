package com.eneve.agent.agent.service;

import com.eneve.agent.agent.store.KnowledgeEmbeddingStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Thin search facade: embeds the user query via Voyage AI and executes
 * a pgvector cosine-similarity search over the {@code knowledge_embeddings} table.
 */
@ApplicationScoped
public class KnowledgeSearchService {

    private static final Logger LOG = Logger.getLogger(KnowledgeSearchService.class);
    private static final int DEFAULT_TOP_K = 10;
    private static final int MAX_TOP_K = 25;

    @Inject
    VoyageEmbeddingService voyageService;

    @Inject
    KnowledgeEmbeddingStore store;

    /**
     * Search the knowledge base by semantic similarity.
     *
     * @param query       natural-language question or keywords
     * @param sourceTypes optional whitelist of source types ('jira', 'confluence', 'jira-attachment');
     *                    null or empty means all sources
     * @param productId   optional product scope; null means search across all products
     * @param topK        number of results (clamped to 1–25)
     * @return ranked list of matching knowledge chunks
     */
    public List<KnowledgeEmbeddingStore.KnowledgeSearchResult> search(
            String query,
            List<String> sourceTypes,
            String productId,
            int topK) {

        LOG.infof("Searching for query: %s", query);

        if (!voyageService.isConfigured()) {
            LOG.warn("Voyage AI not configured — knowledge search unavailable");
            return List.of();
        }

        int k = Math.min(Math.max(1, topK), MAX_TOP_K);
        float[] vector = voyageService.embedSingle(query, "query");
        if (vector == null) {
            LOG.warnf("Failed to embed query: %s", query);
            return List.of();
        }

        List<KnowledgeEmbeddingStore.KnowledgeSearchResult> results =
                store.searchSimilar(vector, k, sourceTypes, productId);

        LOG.debugf("Knowledge search '%s': %d results (sources=%s, product=%s)",
                query, results.size(), sourceTypes, productId);
        return results;
    }

    /**
     * Convenience overload with default topK.
     */
    public List<KnowledgeEmbeddingStore.KnowledgeSearchResult> search(
            String query, List<String> sourceTypes, String productId) {
        return search(query, sourceTypes, productId, DEFAULT_TOP_K);
    }
}
