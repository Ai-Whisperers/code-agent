package com.eneve.agent.agent.store;

import com.eneve.agent.model.ScopeRecord;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.*;
import java.util.*;

@ApplicationScoped
public class ScopeStore {

    private static final Logger LOG = Logger.getLogger(ScopeStore.class);

    @Inject
    AgroalDataSource dataSource;

    // ─── CRUD ─────────────────────────────────────────────────────────────────

    public ScopeRecord create(String name, List<String> labels,
                               String epicIssuetype, String featureIssuetype,
                               String userstoryIssuetype) {
        return create(name, labels, epicIssuetype, featureIssuetype, userstoryIssuetype, "po");
    }

    public ScopeRecord create(String name, List<String> labels,
                               String epicIssuetype, String featureIssuetype,
                               String userstoryIssuetype, String scopeType) {
        String id = UUID.randomUUID().toString();

        // Use the first label as the legacy label column value (backward-compat)
        String primaryLabel = labels != null && !labels.isEmpty() ? labels.get(0) : "";
        String type = (scopeType != null && !scopeType.isBlank()) ? scopeType : "po";

        String sqlScope = """
                INSERT INTO scopes (id, name, label, epic_issuetype, feature_issuetype, userstory_issuetype, scope_type)
                VALUES (?::uuid, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(sqlScope)) {
                    ps.setString(1, id);
                    ps.setString(2, name);
                    ps.setString(3, primaryLabel);
                    ps.setString(4, epicIssuetype);
                    ps.setString(5, featureIssuetype);
                    ps.setString(6, userstoryIssuetype);
                    ps.setString(7, type);
                    ps.executeUpdate();
                }
                replaceLabels(conn, id, labels);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            LOG.errorf("ScopeStore: failed to create scope: %s", e.getMessage());
            throw new RuntimeException("Failed to create scope", e);
        }
        return findById(id).orElseThrow();
    }

    public Optional<ScopeRecord> findById(String id) {
        String sqlScope = """
                SELECT id, name, epic_issuetype, feature_issuetype, userstory_issuetype, created_at, scope_type
                FROM scopes WHERE id = ?::uuid
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sqlScope)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    List<String> labels = loadLabels(conn, id);
                    return Optional.of(mapRow(rs, labels));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("ScopeStore: failed to find scope %s: %s", id, e.getMessage());
        }
        return Optional.empty();
    }

    public List<ScopeRecord> findAll() {
        String sqlScope = """
                SELECT id, name, epic_issuetype, feature_issuetype, userstory_issuetype, created_at, scope_type
                FROM scopes ORDER BY created_at DESC
                """;
        List<ScopeRecord> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sqlScope);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String id = rs.getString("id");
                List<String> labels = loadLabels(conn, id);
                results.add(mapRow(rs, labels));
            }
        } catch (SQLException e) {
            LOG.errorf("ScopeStore: failed to list scopes: %s", e.getMessage());
        }
        return results;
    }

    public List<ScopeRecord> findAllByType(String scopeType) {
        String sqlScope = """
                SELECT id, name, epic_issuetype, feature_issuetype, userstory_issuetype, created_at, scope_type
                FROM scopes WHERE scope_type = ? ORDER BY created_at DESC
                """;
        List<ScopeRecord> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sqlScope)) {
            ps.setString(1, scopeType);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString("id");
                    List<String> labels = loadLabels(conn, id);
                    results.add(mapRow(rs, labels));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("ScopeStore: failed to list scopes by type %s: %s", scopeType, e.getMessage());
        }
        return results;
    }

    public void update(String id, String name, List<String> labels,
                       String epicIssuetype, String featureIssuetype,
                       String userstoryIssuetype) {
        String primaryLabel = labels != null && !labels.isEmpty() ? labels.get(0) : "";

        String sqlScope = """
                UPDATE scopes SET name = ?, label = ?,
                    epic_issuetype = ?, feature_issuetype = ?, userstory_issuetype = ?
                WHERE id = ?::uuid
                """;
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(sqlScope)) {
                    ps.setString(1, name);
                    ps.setString(2, primaryLabel);
                    ps.setString(3, epicIssuetype);
                    ps.setString(4, featureIssuetype);
                    ps.setString(5, userstoryIssuetype);
                    ps.setString(6, id);
                    ps.executeUpdate();
                }
                replaceLabels(conn, id, labels);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            LOG.errorf("ScopeStore: failed to update scope %s: %s", id, e.getMessage());
            throw new RuntimeException("Failed to update scope", e);
        }
    }

    public void delete(String id) {
        String sql = "DELETE FROM scopes WHERE id = ?::uuid";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("ScopeStore: failed to delete scope %s: %s", id, e.getMessage());
            throw new RuntimeException("Failed to delete scope", e);
        }
    }

    // ─── Product links ────────────────────────────────────────────────────────

    public void linkProduct(String scopeId, String productId) {
        String sql = """
                INSERT INTO scope_products (scope_id, product_id)
                VALUES (?::uuid, ?)
                ON CONFLICT (scope_id, product_id) DO NOTHING
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scopeId);
            ps.setString(2, productId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("ScopeStore: failed to link product %s to scope %s: %s",
                    productId, scopeId, e.getMessage());
            throw new RuntimeException("Failed to link product", e);
        }
    }

    public void unlinkProduct(String scopeId, String productId) {
        String sql = "DELETE FROM scope_products WHERE scope_id = ?::uuid AND product_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scopeId);
            ps.setString(2, productId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("ScopeStore: failed to unlink product %s from scope %s: %s",
                    productId, scopeId, e.getMessage());
            throw new RuntimeException("Failed to unlink product", e);
        }
    }

    public List<String> listLinkedProductIds(String scopeId) {
        String sql = "SELECT product_id FROM scope_products WHERE scope_id = ?::uuid ORDER BY created_at";
        List<String> ids = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getString("product_id"));
            }
        } catch (SQLException e) {
            LOG.errorf("ScopeStore: failed to list linked products for scope %s: %s",
                    scopeId, e.getMessage());
        }
        return ids;
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    /** Deletes all existing labels for the scope and inserts the new ordered list. */
    private void replaceLabels(Connection conn, String scopeId, List<String> labels) throws SQLException {
        try (PreparedStatement del = conn.prepareStatement(
                "DELETE FROM scope_labels WHERE scope_id = ?::uuid")) {
            del.setString(1, scopeId);
            del.executeUpdate();
        }
        if (labels == null || labels.isEmpty()) return;
        String insertSql = "INSERT INTO scope_labels (scope_id, label, position) VALUES (?::uuid, ?, ?)";
        try (PreparedStatement ins = conn.prepareStatement(insertSql)) {
            for (int i = 0; i < labels.size(); i++) {
                String lbl = labels.get(i);
                if (lbl == null || lbl.isBlank()) continue;
                ins.setString(1, scopeId);
                ins.setString(2, lbl.trim());
                ins.setInt(3, i);
                ins.addBatch();
            }
            ins.executeBatch();
        }
    }

    /** Loads labels for the given scope id, ordered by position. */
    private List<String> loadLabels(Connection conn, String scopeId) {
        String sql = "SELECT label FROM scope_labels WHERE scope_id = ?::uuid ORDER BY position, label";
        List<String> labels = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) labels.add(rs.getString("label"));
            }
        } catch (SQLException e) {
            LOG.warnf("ScopeStore: failed to load labels for scope %s: %s", scopeId, e.getMessage());
        }
        return labels;
    }

    private ScopeRecord mapRow(ResultSet rs, List<String> labels) throws SQLException {
        return new ScopeRecord(
                rs.getString("id"),
                rs.getString("name"),
                labels,
                rs.getString("epic_issuetype"),
                rs.getString("feature_issuetype"),
                rs.getString("userstory_issuetype"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("scope_type")
        );
    }
}
