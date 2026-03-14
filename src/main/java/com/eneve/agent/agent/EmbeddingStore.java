package com.eneve.agent.agent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class EmbeddingStore {

    private static final Logger LOG = Logger.getLogger(EmbeddingStore.class);

    @Inject
    AgroalDataSource dataSource;

    public record SearchResult(
            String workspace,
            String repoSlug,
            String filePath,
            String symbolName,
            String symbolType,
            String sourceText,
            Integer lineStart,
            Integer lineEnd,
            double score
    ) {}

    public void upsertEmbedding(String workspace, String repoSlug, String filePath,
                                String symbolName, String symbolType, String sourceText,
                                Integer lineStart, Integer lineEnd, float[] embedding) {
        String sql = """
                INSERT INTO code_embeddings
                    (workspace, repo_slug, file_path, symbol_name, symbol_type,
                     source_text, line_start, line_end, embedding, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::vector, now())
                ON CONFLICT (workspace, repo_slug, file_path, symbol_name)
                DO UPDATE SET symbol_type = EXCLUDED.symbol_type,
                              source_text = EXCLUDED.source_text,
                              line_start  = EXCLUDED.line_start,
                              line_end    = EXCLUDED.line_end,
                              embedding   = EXCLUDED.embedding,
                              updated_at  = now()
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspace);
            ps.setString(2, repoSlug);
            ps.setString(3, filePath);
            ps.setString(4, symbolName);
            ps.setString(5, symbolType);
            ps.setString(6, sourceText);
            if (lineStart != null) ps.setInt(7, lineStart); else ps.setNull(7, Types.INTEGER);
            if (lineEnd != null) ps.setInt(8, lineEnd); else ps.setNull(8, Types.INTEGER);
            ps.setString(9, toVectorLiteral(embedding));
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("Failed to upsert embedding for %s in %s/%s: %s",
                    symbolName, workspace, repoSlug, e.getMessage());
        }
    }

    /**
     * Search for similar code across all repos in a workspace.
     */
    public List<SearchResult> searchSimilar(float[] queryVector, String workspace, int topK) {
        String sql = """
                SELECT workspace, repo_slug, file_path, symbol_name, symbol_type,
                       source_text, line_start, line_end,
                       1 - (embedding <=> ?::vector) AS score
                FROM code_embeddings
                WHERE workspace = ?
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """;
        return executeSearch(sql, queryVector, workspace, null, topK);
    }

    /**
     * Search for similar code within a specific repo.
     */
    public List<SearchResult> searchSimilar(float[] queryVector, String workspace, String repoSlug, int topK) {
        String sql = """
                SELECT workspace, repo_slug, file_path, symbol_name, symbol_type,
                       source_text, line_start, line_end,
                       1 - (embedding <=> ?::vector) AS score
                FROM code_embeddings
                WHERE workspace = ? AND repo_slug = ?
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """;
        return executeSearch(sql, queryVector, workspace, repoSlug, topK);
    }

    private List<SearchResult> executeSearch(String sql, float[] queryVector,
                                             String workspace, String repoSlug, int topK) {
        List<SearchResult> results = new ArrayList<>();
        String vecLiteral = toVectorLiteral(queryVector);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            ps.setString(idx++, vecLiteral);
            ps.setString(idx++, workspace);
            if (repoSlug != null) {
                ps.setString(idx++, repoSlug);
            }
            ps.setString(idx++, vecLiteral);
            ps.setInt(idx, topK);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Integer ls = rs.getObject("line_start") != null ? rs.getInt("line_start") : null;
                    Integer le = rs.getObject("line_end") != null ? rs.getInt("line_end") : null;
                    results.add(new SearchResult(
                            rs.getString("workspace"),
                            rs.getString("repo_slug"),
                            rs.getString("file_path"),
                            rs.getString("symbol_name"),
                            rs.getString("symbol_type"),
                            rs.getString("source_text"),
                            ls, le,
                            rs.getDouble("score")
                    ));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Semantic search failed for workspace %s: %s", workspace, e.getMessage());
        }
        return results;
    }

    public void deleteAllForRepo(String workspace, String repoSlug) {
        String sql = "DELETE FROM code_embeddings WHERE workspace = ? AND repo_slug = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspace);
            ps.setString(2, repoSlug);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("Failed to delete embeddings for %s/%s: %s", workspace, repoSlug, e.getMessage());
        }
    }

    public void deleteForFile(String workspace, String repoSlug, String filePath) {
        String sql = "DELETE FROM code_embeddings WHERE workspace = ? AND repo_slug = ? AND file_path = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspace);
            ps.setString(2, repoSlug);
            ps.setString(3, filePath);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("Failed to delete embeddings for file %s in %s/%s: %s",
                    filePath, workspace, repoSlug, e.getMessage());
        }
    }

    public boolean hasEmbeddings(String workspace, String repoSlug) {
        String sql = "SELECT 1 FROM code_embeddings WHERE workspace = ? AND repo_slug = ? LIMIT 1";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspace);
            ps.setString(2, repoSlug);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to check embeddings existence for %s/%s: %s",
                    workspace, repoSlug, e.getMessage());
            return false;
        }
    }

    /**
     * Converts a float array to a pgvector literal string, e.g. "[0.1,0.2,0.3]".
     */
    private static String toVectorLiteral(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vec[i]);
        }
        sb.append(']');
        return sb.toString();
    }
}
