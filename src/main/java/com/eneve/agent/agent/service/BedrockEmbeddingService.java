package com.eneve.agent.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.eneve.agent.settings.SettingsService;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockagentruntime.BedrockAgentRuntimeClient;
import software.amazon.awssdk.services.bedrockagentruntime.model.BedrockRerankingConfiguration;
import software.amazon.awssdk.services.bedrockagentruntime.model.BedrockRerankingModelConfiguration;
import software.amazon.awssdk.services.bedrockagentruntime.model.RerankDocument;
import software.amazon.awssdk.services.bedrockagentruntime.model.RerankDocumentType;
import software.amazon.awssdk.services.bedrockagentruntime.model.RerankQuery;
import software.amazon.awssdk.services.bedrockagentruntime.model.RerankQueryContentType;
import software.amazon.awssdk.services.bedrockagentruntime.model.RerankRequest;
import software.amazon.awssdk.services.bedrockagentruntime.model.RerankResponse;
import software.amazon.awssdk.services.bedrockagentruntime.model.RerankSource;
import software.amazon.awssdk.services.bedrockagentruntime.model.RerankSourceType;
import software.amazon.awssdk.services.bedrockagentruntime.model.RerankTextDocument;
import software.amazon.awssdk.services.bedrockagentruntime.model.RerankingConfiguration;
import software.amazon.awssdk.services.bedrockagentruntime.model.RerankingConfigurationType;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Embedding and reranking via AWS Bedrock with separate model paths for code and text.
 *
 * <h3>Code embeddings — {@code code_embeddings} table</h3>
 * Used by {@code EmbeddingIndexer} (indexing code symbols), {@code DocsEmbeddingService}
 * (indexing generated markdown docs with {@code symbol_type=DOCUMENTATION}), and
 * {@code SemanticSearchTool} (embedding search queries).
 * All three share the same table and must use the same model so cosine similarity is meaningful.
 * Configured via {@code bedrock.code.embedding.model} (default: {@code cohere.embed-multilingual-v3}).
 *
 * <h3>Text embeddings — {@code knowledge_embeddings} table</h3>
 * Used by {@code KnowledgeIndexerService} (indexing Jira/Confluence/web docs) and
 * {@code KnowledgeSearchService} (embedding knowledge queries).
 * Configured via {@code bedrock.text.embedding.model} (default: {@code amazon.titan-embed-text-v2:0}).
 *
 * <p>Both tables use {@code vector(1024)}, and both default models produce 1024-dimensional
 * vectors — no database migration is required when switching models.
 *
 * <h3>Reranking</h3>
 * Configured via {@code bedrock.rerank.model} (default: {@code amazon.rerank-v1:0}).
 * Only the code-search pipeline ({@code SemanticSearchTool}) uses reranking.
 *
 * <h3>Credentials & GDPR</h3>
 * Credentials are resolved via the standard AWS credential chain (IAM task role on ECS/EC2;
 * {@code AWS_ACCESS_KEY_ID}/{@code AWS_SECRET_ACCESS_KEY} env vars or {@code ~/.aws} in dev).
 * No API key required. Data stays in the configured region (default: {@code eu-central-1}).
 * AWS Bedrock does not use customer inputs to train foundation models.
 * Apply an AI services opt-out policy in AWS Organizations to block any broader AWS AI usage.
 */
@ApplicationScoped
public class BedrockEmbeddingService {

    private static final Logger LOG = Logger.getLogger(BedrockEmbeddingService.class);

    /** Cohere Embed v3: max texts per InvokeModel call. */
    private static final int COHERE_MAX_BATCH = 96;
    /** Titan Embeddings V2: only one text per InvokeModel call. */
    private static final int TITAN_BATCH = 1;

    @Inject ObjectMapper mapper;
    @Inject SettingsService settingsService;

    /** Holds the original document index and its relevance score after reranking. */
    public record RerankResult(int index, double relevanceScore) {}

    private BedrockRuntimeClient runtimeClient;
    private BedrockAgentRuntimeClient agentRuntimeClient;

    @PostConstruct
    void init() {
        try {
            String region = settingsService.get("bedrock.region", "eu-central-1");
            runtimeClient = BedrockRuntimeClient.builder()
                    .region(Region.of(region))
                    .credentialsProvider(DefaultCredentialsProvider.create())
                    .build();
            agentRuntimeClient = BedrockAgentRuntimeClient.builder()
                    .region(Region.of(region))
                    .credentialsProvider(DefaultCredentialsProvider.create())
                    .build();
            LOG.infof("Bedrock clients initialised (region=%s, codeModel=%s, textModel=%s)",
                    region,
                    settingsService.get("bedrock.code.embedding.model", "cohere.embed-multilingual-v3"),
                    settingsService.get("bedrock.text.embedding.model", "amazon.titan-embed-text-v2:0"));
        } catch (Exception e) {
            LOG.warnf("Failed to initialise Bedrock clients — embedding/reranking disabled: %s", e.getMessage());
        }
    }

    public boolean isConfigured() {
        return runtimeClient != null && agentRuntimeClient != null;
    }

    // -------------------------------------------------------------------------
    // Code embeddings  (→ code_embeddings table, SemanticSearchTool)
    // -------------------------------------------------------------------------

    /**
     * Embeds a single code text using the code embedding model.
     *
     * @param text      code text to embed
     * @param inputType {@code "document"} when indexing, {@code "query"} when searching
     */
    public float[] embedSingle(String text, String inputType) {
        List<float[]> results = embed(List.of(text), inputType);
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * Embeds multiple code texts in batches using the code embedding model
     * ({@code bedrock.code.embedding.model}, default {@code cohere.embed-multilingual-v3}).
     *
     * @param texts     code texts to embed
     * @param inputType {@code "document"} when indexing, {@code "query"} when searching
     */
    public List<float[]> embed(List<String> texts, String inputType) {
        if (!isConfigured()) {
            LOG.warn("Bedrock not configured — skipping code embedding");
            return List.of();
        }
        String model = settingsService.get("bedrock.code.embedding.model", "cohere.embed-multilingual-v3");
        return embedWithModel(texts, inputType, model);
    }

    // -------------------------------------------------------------------------
    // Text embeddings  (→ knowledge_embeddings table, KnowledgeSearch/IndexerService, DocsEmbeddingService)
    // -------------------------------------------------------------------------

    /**
     * Embeds a single text document using the text embedding model.
     *
     * @param text      text to embed
     * @param inputType {@code "document"} when indexing, {@code "query"} when searching
     */
    public float[] embedSingleText(String text, String inputType) {
        List<float[]> results = embedText(List.of(text), inputType);
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * Embeds multiple text documents in batches using the text embedding model
     * ({@code bedrock.text.embedding.model}, default {@code amazon.titan-embed-text-v2:0}).
     *
     * @param texts     texts to embed
     * @param inputType {@code "document"} when indexing, {@code "query"} when searching
     */
    public List<float[]> embedText(List<String> texts, String inputType) {
        if (!isConfigured()) {
            LOG.warn("Bedrock not configured — skipping text embedding");
            return List.of();
        }
        String model = settingsService.get("bedrock.text.embedding.model", "amazon.titan-embed-text-v2:0");
        return embedWithModel(texts, inputType, model);
    }

    // -------------------------------------------------------------------------
    // Reranking  (→ SemanticSearchTool, code search pipeline only)
    // -------------------------------------------------------------------------

    /**
     * Reranks documents by relevance to the query using the Bedrock Rerank API.
     *
     * @param query     search query
     * @param documents candidate document texts
     * @param topK      how many top results to return
     * @return list of {@link RerankResult} sorted by descending relevance score; empty on failure
     */
    public List<RerankResult> rerank(String query, List<String> documents, int topK) {
        if (!isConfigured()) {
            LOG.warn("Bedrock not configured — skipping reranking");
            return List.of();
        }
        if (query == null || query.isBlank() || documents.isEmpty()) {
            return List.of();
        }

        try {
            String region = settingsService.get("bedrock.region", "eu-central-1");
            String rerankModel = settingsService.get("bedrock.rerank.model", "amazon.rerank-v1:0");
            String modelArn = String.format("arn:aws:bedrock:%s::foundation-model/%s", region, rerankModel);

            List<RerankSource> sources = documents.stream()
                    .map(doc -> RerankSource.builder()
                            .type(RerankSourceType.INLINE)
                            .inlineDocumentSource(RerankDocument.builder()
                                    .type(RerankDocumentType.TEXT)
                                    .textDocument(RerankTextDocument.builder().text(doc).build())
                                    .build())
                            .build())
                    .collect(Collectors.toList());

            RerankRequest request = RerankRequest.builder()
                    .queries(List.of(
                            RerankQuery.builder()
                                    .type(RerankQueryContentType.TEXT)
                                    .textQuery(RerankTextDocument.builder().text(query).build())
                                    .build()
                    ))
                    .sources(sources)
                    .rerankingConfiguration(RerankingConfiguration.builder()
                            .type(RerankingConfigurationType.BEDROCK_RERANKING_MODEL)
                            .bedrockRerankingConfiguration(BedrockRerankingConfiguration.builder()
                                    .modelConfiguration(BedrockRerankingModelConfiguration.builder()
                                            .modelArn(modelArn)
                                            .build())
                                    .numberOfResults(topK)
                                    .build())
                            .build())
                    .build();

            RerankResponse response = agentRuntimeClient.rerank(request);

            List<RerankResult> results = new ArrayList<>();
            // Use FQN to distinguish SDK's RerankResult from our own RerankResult record
            for (software.amazon.awssdk.services.bedrockagentruntime.model.RerankResult item : response.results()) {
                results.add(new RerankResult(item.index(), item.relevanceScore()));
            }

            results.sort((a, b) -> Double.compare(b.relevanceScore(), a.relevanceScore()));
            LOG.debugf("Bedrock rerank returned %d results (model=%s)", results.size(), rerankModel);
            return results;

        } catch (Exception e) {
            LOG.errorf("Bedrock rerank API call failed: %s", e.getMessage());
            return List.of();
        }
    }

    // -------------------------------------------------------------------------
    // Shared embedding implementation
    // -------------------------------------------------------------------------

    private List<float[]> embedWithModel(List<String> texts, String inputType, String model) {
        if (texts.isEmpty()) {
            return List.of();
        }

        boolean isTitan = model.startsWith("amazon.titan-embed");
        int batchSize = isTitan ? TITAN_BATCH : COHERE_MAX_BATCH;

        List<float[]> allEmbeddings = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += batchSize) {
            List<String> batch = texts.subList(i, Math.min(i + batchSize, texts.size()));
            List<float[]> batchResult = isTitan
                    ? callTitanApi(batch, model)
                    : callCohereEmbedApi(batch, model, inputType);
            allEmbeddings.addAll(batchResult);
        }
        return allEmbeddings;
    }

    // --- Cohere Embed v3 (cohere.embed-multilingual-v3 / cohere.embed-english-v3) ---

    private List<float[]> callCohereEmbedApi(List<String> texts, String model, String inputType) {
        try {
            var requestBody = mapper.createObjectNode();
            var inputArray = requestBody.putArray("texts");
            texts.forEach(inputArray::add);
            requestBody.put("input_type", toCohereInputType(inputType));
            requestBody.put("truncate", "END");

            InvokeModelResponse response = invokeModel(model, requestBody);
            JsonNode root = mapper.readTree(response.body().asUtf8String());
            JsonNode embeddingsNode = root.get("embeddings");

            if (embeddingsNode == null || !embeddingsNode.isArray()) {
                LOG.error("Bedrock Cohere embed response missing 'embeddings' array");
                return List.of();
            }

            List<float[]> embeddings = new ArrayList<>();
            for (JsonNode embNode : embeddingsNode) {
                if (!embNode.isArray()) {
                    embeddings.add(null);
                    continue;
                }
                float[] vec = new float[embNode.size()];
                for (int j = 0; j < embNode.size(); j++) {
                    vec[j] = (float) embNode.get(j).asDouble();
                }
                embeddings.add(vec);
            }

            LOG.debugf("Bedrock Cohere embed: %d vectors (dim=%d, model=%s)",
                    embeddings.size(), embeddings.isEmpty() ? 0 : embeddings.get(0).length, model);
            return embeddings;

        } catch (Exception e) {
            LOG.errorf("Bedrock Cohere embed API call failed: %s", e.getMessage());
            return List.of();
        }
    }

    // --- Amazon Titan Text Embeddings V2 (amazon.titan-embed-text-v2:0) ---
    // Titan processes one text per call; the batch loop in embedWithModel() drives iteration.

    private List<float[]> callTitanApi(List<String> texts, String model) {
        List<float[]> results = new ArrayList<>();
        for (String text : texts) {
            try {
                var requestBody = mapper.createObjectNode();
                requestBody.put("inputText", text);
                requestBody.put("dimensions", 1024);
                requestBody.put("normalize", true);

                InvokeModelResponse response = invokeModel(model, requestBody);
                JsonNode root = mapper.readTree(response.body().asUtf8String());
                JsonNode embNode = root.get("embedding");

                if (embNode == null || !embNode.isArray()) {
                    LOG.error("Bedrock Titan embed response missing 'embedding' field");
                    results.add(null);
                    continue;
                }
                float[] vec = new float[embNode.size()];
                for (int j = 0; j < embNode.size(); j++) {
                    vec[j] = (float) embNode.get(j).asDouble();
                }
                results.add(vec);

            } catch (Exception e) {
                LOG.errorf("Bedrock Titan embed call failed: %s", e.getMessage());
                results.add(null);
            }
        }
        return results;
    }

    private InvokeModelResponse invokeModel(String modelId, Object requestBody) throws Exception {
        String json = mapper.writeValueAsString(requestBody);
        InvokeModelRequest request = InvokeModelRequest.builder()
                .modelId(modelId)
                .contentType("application/json")
                .accept("*/*")
                .body(SdkBytes.fromUtf8String(json))
                .build();
        return runtimeClient.invokeModel(request);
    }

    /**
     * Maps the Voyage-style input type strings (used by callers) to Cohere's expected values.
     * Titan does not use input type; this is only applied on the Cohere path.
     */
    private static String toCohereInputType(String inputType) {
        return switch (inputType) {
            case "document" -> "search_document";
            case "query"    -> "search_query";
            default         -> inputType;
        };
    }
}
