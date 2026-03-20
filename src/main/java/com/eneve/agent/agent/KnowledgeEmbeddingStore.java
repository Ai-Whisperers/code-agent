package com.eneve.agent.agent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * PostgreSQL/pgvector store for knowledge embeddings (Jira issues, Confluence
 * pages, Jira attachments).  Mirrors the structure of {@link EmbeddingStore}
 * but operates on the {@code knowledge_embeddings} table.
 */
@ApplicationScoped
public class KnowledgeEmbeddingStore {

    private static final Logger LOG = Logger.getLogger(KnowledgeEmbeddingStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    AgroalDataSource dataSource;

    public record KnowledgeChunk(
            String sourceType,
            String sourceId,
            String productId,
            String customerId,
            String title,
            String contentChunk,
            Map<String, Object> metadata
    ) {}

    public record KnowledgeSearchResult(
            String sourceType,
            String sourceId,
            String productId,
            String customerId,
            String title,
            String contentChunk,
            Map<String, Object> metadata,
            double score
    ) {}

    /**
     * Insert or update a knowledge chunk and its embedding.
     * Conflicts on (source_type, source_id, md5(content_chunk)) are silently updated.
     */
    public void upsert(KnowledgeChunk chunk, float[] embedding) {
        String sql = """
                INSERT INTO knowledge_embeddings
                    (source_type, source_id, product_id, customer_id, title,
                     content_chunk, metadata, embedding, indexed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::vector, now())
                ON CONFLICT (source_type, source_id, md5(content_chunk)) DO UPDATE
                    SET product_id    = EXCLUDED.product_id,
                        customer_id   = EXCLUDED.customer_id,
                        title         = EXCLUDED.title,
                        metadata      = EXCLUDED.metadata,
                        embedding     = EXCLUDED.embedding,
                        indexed_at    = now()
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, chunk.sourceType());
            ps.setString(2, chunk.sourceId());
            setNullable(ps, 3, chunk.productId());
            setNullable(ps, 4, chunk.customerId());
            setNullable(ps, 5, chunk.title());
            ps.setString(6, chunk.contentChunk());
            ps.setString(7, toJson(chunk.metadata()));
            ps.setString(8, toVectorLiteral(embedding));
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("Failed to upsert knowledge chunk %s/%s: %s",
                    chunk.sourceType(), chunk.sourceId(), e.getMessage());
        }
    }

    /**
     * Delete all chunks for a given source (e.g. all chunks of a Jira issue).
     */
    public int deleteBySource(String sourceType, String sourceId) {
        String sql = "DELETE FROM knowledge_embeddings WHERE source_type = ? AND source_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sourceType);
            ps.setString(2, sourceId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("Failed to delete knowledge chunks for %s/%s: %s", sourceType, sourceId, e.getMessage());
            return 0;
        }
    }

    /**
     * Delete all chunks for a product (used before a full reindex).
     */
    public int deleteByProduct(String productId) {
        String sql = "DELETE FROM knowledge_embeddings WHERE product_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, productId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("Failed to delete knowledge chunks for product %s: %s", productId, e.getMessage());
            return 0;
        }
    }

    /**
     * Check whether a source has already been indexed (fast membership test).
     */
    public boolean isIndexed(String sourceType, String sourceId) {
        String sql = "SELECT 1 FROM knowledge_embeddings WHERE source_type = ? AND source_id = ? LIMIT 1";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sourceType);
            ps.setString(2, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to check indexed status for %s/%s: %s", sourceType, sourceId, e.getMessage());
            return false;
        }
    }

    /**
     * Cosine similarity search.
     *
     * @param queryVector  the embedded query vector
     * @param topK         maximum results to return
     * @param sourceTypes  whitelist of source_type values; empty means all sources
     * @param productId    optional product scoping; null means all products
     */
    public List<KnowledgeSearchResult> searchSimilar(float[] queryVector, int topK,
                                                      List<String> sourceTypes, String productId) {
        StringBuilder sql = new StringBuilder("""
                SELECT source_type, source_id, product_id, customer_id, title,
                       content_chunk, metadata,
                       1 - (embedding <=> ?::vector) AS score
                FROM knowledge_embeddings
                WHERE 1=1
                """);

        if (sourceTypes != null && !sourceTypes.isEmpty()) {
            sql.append(" AND source_type = ANY(?) ");
        }
        if (productId != null && !productId.isBlank()) {
            sql.append(" AND product_id = ? ");
        }
        sql.append(" ORDER BY embedding <=> ?::vector LIMIT ?");

        List<KnowledgeSearchResult> results = new ArrayList<>();
        String vecLiteral = toVectorLiteral(queryVector);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setString(idx++, vecLiteral);
            if (sourceTypes != null && !sourceTypes.isEmpty()) {
                ps.setArray(idx++, conn.createArrayOf("TEXT", sourceTypes.toArray()));
            }
            if (productId != null && !productId.isBlank()) {
                ps.setString(idx++, productId);
            }
            ps.setString(idx++, vecLiteral);
            ps.setInt(idx, topK);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new KnowledgeSearchResult(
                            rs.getString("source_type"),
                            rs.getString("source_id"),
                            rs.getString("product_id"),
                            rs.getString("customer_id"),
                            rs.getString("title"),
                            rs.getString("content_chunk"),
                            fromJson(rs.getString("metadata")),
                            rs.getDouble("score")
                    ));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Knowledge search failed: %s", e.getMessage());
        }
        return results;
    }

    /**
     * Count of indexed chunks by source type, optionally scoped to a product.
     */
    public int countBySource(String sourceType, String productId) {
        String sql = productId != null
                ? "SELECT COUNT(*) FROM knowledge_embeddings WHERE source_type = ? AND product_id = ?"
                : "SELECT COUNT(*) FROM knowledge_embeddings WHERE source_type = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sourceType);
            if (productId != null) ps.setString(2, productId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to count knowledge chunks for source_type=%s: %s", sourceType, e.getMessage());
            return 0;
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────

    private static String toVectorLiteral(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vec[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    private static void setNullable(PreparedStatement ps, int idx, String value) throws SQLException {
        if (value != null) {
            ps.setString(idx, value);
        } else {
            ps.setNull(idx, Types.VARCHAR);
        }
    }

    private static String toJson(Map<String, Object> metadata) {
        if (metadata == null) return "{}";
        try {
            return MAPPER.writeValueAsString(metadata);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}
