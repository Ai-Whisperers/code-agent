package com.eneve.agent.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.eneve.agent.settings.SettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * HTTP client for the Voyage AI embeddings and reranking APIs.
 * Supports batching and asymmetric search (document vs query input types),
 * as well as two-stage retrieval via the rerank endpoint.
 */
@ApplicationScoped
public class VoyageEmbeddingService {

    private static final Logger LOG = Logger.getLogger(VoyageEmbeddingService.class);
    private static final String VOYAGE_API_URL = "https://api.voyageai.com/v1/embeddings";
    private static final String VOYAGE_RERANK_URL = "https://api.voyageai.com/v1/rerank";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Holds the original document index and its relevance score after reranking. */
    public record RerankResult(int index, double relevanceScore) {}

    @Inject
    SettingsService settingsService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public boolean isConfigured() {
        String apiKey = settingsService.getSecret("voyage.api.key");
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Embed a single text. Convenience wrapper around batch embed.
     *
     * @param text      the text to embed
     * @param inputType "document" for indexing, "query" for search queries
     * @return the embedding vector, or null on failure
     */
    public float[] embedSingle(String text, String inputType) {
        List<float[]> results = embed(List.of(text), inputType);
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * Embed multiple texts in batches.
     *
     * @param texts     list of texts to embed
     * @param inputType "document" for indexing, "query" for search queries
     * @return list of embedding vectors (same order as input); empty entries are null on failure
     */
    public List<float[]> embed(List<String> texts, String inputType) {
        if (!isConfigured()) {
            LOG.warn("Voyage API key not configured — skipping embedding");
            return List.of();
        }
        if (texts.isEmpty()) {
            return List.of();
        }

        List<float[]> allEmbeddings = new ArrayList<>();
        int batchSize = Integer.parseInt(settingsService.get("voyage.batch-size", "128"));

        for (int i = 0; i < texts.size(); i += batchSize) {
            List<String> batch = texts.subList(i, Math.min(i + batchSize, texts.size()));
            List<float[]> batchResult = callApi(batch, inputType);
            allEmbeddings.addAll(batchResult);
        }

        return allEmbeddings;
    }

    /**
     * Reranks a list of documents by relevance to the query using Voyage's rerank endpoint.
     *
     * @param query     the search query
     * @param documents the candidate document texts to rerank
     * @param topK      how many top results to return
     * @return list of {@link RerankResult} sorted by descending relevance score; empty on failure
     */
    public List<RerankResult> rerank(String query, List<String> documents, int topK) {
        if (!isConfigured()) {
            LOG.warn("Voyage API key not configured — skipping reranking");
            return List.of();
        }
        if (query == null || query.isBlank() || documents.isEmpty()) {
            return List.of();
        }

        try {
            String apiKey = settingsService.getSecret("voyage.api.key");
            String model = settingsService.get("voyage.rerank.model", "rerank-2");

            var requestBody = MAPPER.createObjectNode();
            requestBody.put("model", model);
            requestBody.put("query", query);
            requestBody.put("top_k", topK);
            var docsArray = requestBody.putArray("documents");
            documents.forEach(docsArray::add);

            String json = MAPPER.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(VOYAGE_RERANK_URL))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOG.errorf("Voyage rerank API error (HTTP %d): %s", response.statusCode(),
                        truncate(response.body(), 500));
                return List.of();
            }

            JsonNode root = MAPPER.readTree(response.body());
            JsonNode data = root.get("data");
            if (data == null || !data.isArray()) {
                LOG.error("Voyage rerank response missing 'data' array");
                return List.of();
            }

            List<RerankResult> results = new ArrayList<>();
            for (JsonNode item : data) {
                int index = item.path("index").asInt(-1);
                double score = item.path("relevance_score").asDouble(0.0);
                if (index >= 0) {
                    results.add(new RerankResult(index, score));
                }
            }

            results.sort((a, b) -> Double.compare(b.relevanceScore(), a.relevanceScore()));
            LOG.debugf("Voyage rerank returned %d results for query (model=%s)", results.size(), model);
            return results;

        } catch (Exception e) {
            LOG.errorf("Voyage rerank API call failed: %s", e.getMessage());
            return List.of();
        }
    }

    private List<float[]> callApi(List<String> texts, String inputType) {
        try {
            String apiKey = settingsService.getSecret("voyage.api.key");
            String model = settingsService.get("voyage.model", "voyage-code-3");
            var requestBody = MAPPER.createObjectNode();
            requestBody.put("model", model);
            requestBody.put("input_type", inputType);
            var inputArray = requestBody.putArray("input");
            texts.forEach(inputArray::add);

            String json = MAPPER.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(VOYAGE_API_URL))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOG.errorf("Voyage API error (HTTP %d): %s", response.statusCode(),
                        truncate(response.body(), 500));
                return List.of();
            }

            JsonNode root = MAPPER.readTree(response.body());
            JsonNode data = root.get("data");
            if (data == null || !data.isArray()) {
                LOG.error("Voyage API response missing 'data' array");
                return List.of();
            }

            List<float[]> embeddings = new ArrayList<>();
            for (JsonNode item : data) {
                JsonNode embNode = item.get("embedding");
                if (embNode == null || !embNode.isArray()) {
                    embeddings.add(null);
                    continue;
                }
                float[] vec = new float[embNode.size()];
                for (int j = 0; j < embNode.size(); j++) {
                    vec[j] = (float) embNode.get(j).asDouble();
                }
                embeddings.add(vec);
            }

            LOG.debugf("Voyage API returned %d embeddings (dim=%d)",
                    embeddings.size(), embeddings.isEmpty() ? 0 : embeddings.get(0).length);
            return embeddings;

        } catch (Exception e) {
            LOG.errorf("Voyage API call failed: %s", e.getMessage());
            return List.of();
        }
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
