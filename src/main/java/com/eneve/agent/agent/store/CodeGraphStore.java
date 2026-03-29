package com.eneve.agent.agent.store;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class CodeGraphStore {

    private static final Logger LOG = Logger.getLogger(CodeGraphStore.class);

    @Inject
    AgroalDataSource dataSource;

    public void upsertNode(String workspace, String repoSlug, String filePath,
                           String symbolName, String symbolType,
                           Integer lineStart, Integer lineEnd, String modifiers) {
        String sql = """
                INSERT INTO code_graph_nodes
                    (workspace, repo_slug, file_path, symbol_name, symbol_type,
                     line_start, line_end, modifiers, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (workspace, repo_slug, file_path, symbol_name)
                DO UPDATE SET symbol_type = EXCLUDED.symbol_type,
                              line_start  = EXCLUDED.line_start,
                              line_end    = EXCLUDED.line_end,
                              modifiers   = EXCLUDED.modifiers,
                              updated_at  = now()
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspace);
            ps.setString(2, repoSlug);
            ps.setString(3, filePath);
            ps.setString(4, symbolName);
            ps.setString(5, symbolType);
            if (lineStart != null) ps.setInt(6, lineStart); else ps.setNull(6, java.sql.Types.INTEGER);
            if (lineEnd != null) ps.setInt(7, lineEnd); else ps.setNull(7, java.sql.Types.INTEGER);
            ps.setString(8, modifiers);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("Failed to upsert code graph node %s in %s/%s: %s",
                    symbolName, workspace, repoSlug, e.getMessage());
        }
    }

    public void upsertEdge(String workspace, String repoSlug,
                           String sourceNode, String targetNode, String edgeType,
                           String sourceFile, String targetFile) {
        String sql = """
                INSERT INTO code_graph_edges
                    (workspace, repo_slug, source_node, target_node, edge_type,
                     source_file, target_file, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (workspace, repo_slug, source_node, target_node, edge_type)
                DO UPDATE SET source_file = EXCLUDED.source_file,
                              target_file = EXCLUDED.target_file,
                              updated_at  = now()
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspace);
            ps.setString(2, repoSlug);
            ps.setString(3, sourceNode);
            ps.setString(4, targetNode);
            ps.setString(5, edgeType);
            ps.setString(6, sourceFile);
            ps.setString(7, targetFile);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("Failed to upsert code graph edge %s->%s in %s/%s: %s",
                    sourceNode, targetNode, workspace, repoSlug, e.getMessage());
        }
    }

    public void deleteNodesForFile(String workspace, String repoSlug, String filePath) {
        String sql = "DELETE FROM code_graph_nodes WHERE workspace = ? AND repo_slug = ? AND file_path = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspace);
            ps.setString(2, repoSlug);
            ps.setString(3, filePath);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("Failed to delete nodes for file %s in %s/%s: %s",
                    filePath, workspace, repoSlug, e.getMessage());
        }
    }

    public void deleteEdgesForSourceFile(String workspace, String repoSlug, String sourceFile) {
        String sql = "DELETE FROM code_graph_edges WHERE workspace = ? AND repo_slug = ? AND source_file = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspace);
            ps.setString(2, repoSlug);
            ps.setString(3, sourceFile);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("Failed to delete edges for source file %s in %s/%s: %s",
                    sourceFile, workspace, repoSlug, e.getMessage());
        }
    }

    public void deleteAllForRepo(String workspace, String repoSlug) {
        String sqlNodes = "DELETE FROM code_graph_nodes WHERE workspace = ? AND repo_slug = ?";
        String sqlEdges = "DELETE FROM code_graph_edges WHERE workspace = ? AND repo_slug = ?";
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(sqlEdges)) {
                ps.setString(1, workspace);
                ps.setString(2, repoSlug);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(sqlNodes)) {
                ps.setString(1, workspace);
                ps.setString(2, repoSlug);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to delete all graph data for %s/%s: %s",
                    workspace, repoSlug, e.getMessage());
        }
    }

    public record GraphStatus(String workspace, String repoSlug, Instant lastUpdatedAt, long nodeCount) {}

    public List<GraphStatus> getGraphStatusAll() {
        String sql = """
                SELECT workspace, repo_slug, MAX(updated_at) AS last_updated, COUNT(*) AS node_count
                FROM code_graph_nodes
                GROUP BY workspace, repo_slug
                ORDER BY workspace, repo_slug
                """;
        List<GraphStatus> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Instant lastUpdated = rs.getTimestamp("last_updated") != null
                        ? rs.getTimestamp("last_updated").toInstant()
                        : null;
                results.add(new GraphStatus(
                        rs.getString("workspace"),
                        rs.getString("repo_slug"),
                        lastUpdated,
                        rs.getLong("node_count")));
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to get graph status: %s", e.getMessage());
        }
        return results;
    }

    public record EdgeResult(String sourceNode, String sourceFile) {}

    public record CrossRepoEdgeResult(String repoSlug, String sourceNode, String sourceFile) {}

    public List<EdgeResult> findCallers(String workspace, String repoSlug, String symbolName) {
        String sql = """
                SELECT source_node, source_file FROM code_graph_edges
                WHERE workspace = ? AND repo_slug = ? AND target_node = ? AND edge_type = 'CALLS'
                """;
        return queryEdges(sql, workspace, repoSlug, symbolName);
    }

    public List<EdgeResult> findImplementations(String workspace, String repoSlug, String interfaceName) {
        String sql = """
                SELECT source_node, source_file FROM code_graph_edges
                WHERE workspace = ? AND repo_slug = ? AND target_node = ?
                  AND edge_type IN ('IMPLEMENTS', 'EXTENDS')
                """;
        return queryEdges(sql, workspace, repoSlug, interfaceName);
    }

    public List<EdgeResult> findDependents(String workspace, String repoSlug, String symbolName) {
        String sql = """
                SELECT source_node, source_file FROM code_graph_edges
                WHERE workspace = ? AND repo_slug = ? AND target_node = ?
                """;
        return queryEdges(sql, workspace, repoSlug, symbolName);
    }

    public List<EdgeResult> findCallees(String workspace, String repoSlug, String symbolName) {
        String sql = """
                SELECT target_node, target_file FROM code_graph_edges
                WHERE workspace = ? AND repo_slug = ? AND source_node = ? AND edge_type = 'CALLS'
                LIMIT 10
                """;
        List<EdgeResult> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspace);
            ps.setString(2, repoSlug);
            ps.setString(3, symbolName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new EdgeResult(rs.getString("target_node"), rs.getString("target_file")));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to query callees for symbol %s in %s/%s: %s",
                    symbolName, workspace, repoSlug, e.getMessage());
        }
        return results;
    }

    public List<String> findSymbolsInFile(String workspace, String repoSlug, String filePath) {
        String sql = """
                SELECT symbol_name FROM code_graph_nodes
                WHERE workspace = ? AND repo_slug = ? AND file_path = ?
                """;
        List<String> symbols = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspace);
            ps.setString(2, repoSlug);
            ps.setString(3, filePath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    symbols.add(rs.getString("symbol_name"));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to find symbols in file %s for %s/%s: %s",
                    filePath, workspace, repoSlug, e.getMessage());
        }
        return symbols;
    }

    public boolean hasGraph(String workspace, String repoSlug) {
        String sql = "SELECT 1 FROM code_graph_nodes WHERE workspace = ? AND repo_slug = ? LIMIT 1";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspace);
            ps.setString(2, repoSlug);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to check graph existence for %s/%s: %s",
                    workspace, repoSlug, e.getMessage());
            return false;
        }
    }

    public int countDistinctReposUsing(String workspace, String excludeRepo, String symbolName) {
        String sql = """
                SELECT COUNT(DISTINCT repo_slug) FROM code_graph_edges
                WHERE workspace = ? AND repo_slug != ? AND target_node = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspace);
            ps.setString(2, excludeRepo);
            ps.setString(3, symbolName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to count repos using symbol %s in workspace %s: %s",
                    symbolName, workspace, e.getMessage());
            return 0;
        }
    }

    public List<CrossRepoEdgeResult> findCallersAcrossWorkspace(String workspace, String excludeRepo,
                                                                String symbolName) {
        String sql = """
                SELECT repo_slug, source_node, source_file FROM code_graph_edges
                WHERE workspace = ? AND repo_slug != ? AND target_node = ? AND edge_type = 'CALLS'
                LIMIT 20
                """;
        return queryEdgesAcrossWorkspace(sql, workspace, excludeRepo, symbolName);
    }

    public List<CrossRepoEdgeResult> findImplementationsAcrossWorkspace(String workspace, String excludeRepo,
                                                                        String symbolName) {
        String sql = """
                SELECT repo_slug, source_node, source_file FROM code_graph_edges
                WHERE workspace = ? AND repo_slug != ? AND target_node = ?
                  AND edge_type IN ('IMPLEMENTS', 'EXTENDS')
                LIMIT 20
                """;
        return queryEdgesAcrossWorkspace(sql, workspace, excludeRepo, symbolName);
    }

    public List<CrossRepoEdgeResult> findDependentsAcrossWorkspace(String workspace, String excludeRepo,
                                                                   String symbolName) {
        String sql = """
                SELECT repo_slug, source_node, source_file FROM code_graph_edges
                WHERE workspace = ? AND repo_slug != ? AND target_node = ?
                LIMIT 20
                """;
        return queryEdgesAcrossWorkspace(sql, workspace, excludeRepo, symbolName);
    }

    private List<CrossRepoEdgeResult> queryEdgesAcrossWorkspace(String sql, String workspace,
                                                                String excludeRepo, String symbolName) {
        List<CrossRepoEdgeResult> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspace);
            ps.setString(2, excludeRepo);
            ps.setString(3, symbolName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new CrossRepoEdgeResult(
                            rs.getString("repo_slug"),
                            rs.getString("source_node"),
                            rs.getString("source_file")));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to query cross-repo edges for symbol %s in workspace %s: %s",
                    symbolName, workspace, e.getMessage());
        }
        return results;
    }

    private List<EdgeResult> queryEdges(String sql, String workspace, String repoSlug, String symbolName) {
        List<EdgeResult> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workspace);
            ps.setString(2, repoSlug);
            ps.setString(3, symbolName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new EdgeResult(rs.getString("source_node"), rs.getString("source_file")));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to query edges for symbol %s in %s/%s: %s",
                    symbolName, workspace, repoSlug, e.getMessage());
        }
        return results;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Workspace-agnostic variants (used in chat mode when workspace is unknown)
    // ──────────────────────────────────────────────────────────────────────

    public List<EdgeResult> findCallersByRepo(String repoSlug, String symbolName) {
        String sql = """
                SELECT source_node, source_file FROM code_graph_edges
                WHERE repo_slug = ? AND target_node = ? AND edge_type = 'CALLS'
                """;
        return queryEdgesByRepo(sql, repoSlug, symbolName);
    }

    public List<EdgeResult> findImplementationsByRepo(String repoSlug, String interfaceName) {
        String sql = """
                SELECT source_node, source_file FROM code_graph_edges
                WHERE repo_slug = ? AND target_node = ?
                  AND edge_type IN ('IMPLEMENTS', 'EXTENDS')
                """;
        return queryEdgesByRepo(sql, repoSlug, interfaceName);
    }

    public List<EdgeResult> findDependentsByRepo(String repoSlug, String symbolName) {
        String sql = """
                SELECT source_node, source_file FROM code_graph_edges
                WHERE repo_slug = ? AND target_node = ?
                """;
        return queryEdgesByRepo(sql, repoSlug, symbolName);
    }

    public int countDistinctReposUsingByRepo(String excludeRepo, String symbolName) {
        String sql = """
                SELECT COUNT(DISTINCT repo_slug) FROM code_graph_edges
                WHERE repo_slug != ? AND target_node = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, excludeRepo);
            ps.setString(2, symbolName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to count repos using symbol %s (excluding %s): %s",
                    symbolName, excludeRepo, e.getMessage());
            return 0;
        }
    }

    private List<EdgeResult> queryEdgesByRepo(String sql, String repoSlug, String symbolName) {
        List<EdgeResult> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, repoSlug);
            ps.setString(2, symbolName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new EdgeResult(rs.getString("source_node"), rs.getString("source_file")));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to query edges for symbol %s in repo %s: %s",
                    symbolName, repoSlug, e.getMessage());
        }
        return results;
    }
}
