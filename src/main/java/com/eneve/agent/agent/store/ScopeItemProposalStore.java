package com.eneve.agent.agent.store;

import com.eneve.agent.model.ScopeProposal;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC store for {@code scope_item_proposals}.
 * Proposals are AI-generated rewrites of Jira issues that live only in the
 * database until a user explicitly accepts them.
 */
@ApplicationScoped
public class ScopeItemProposalStore {

    private static final Logger LOG = Logger.getLogger(ScopeItemProposalStore.class);

    @Inject
    AgroalDataSource dataSource;

    private static final String SELECT_COLS = """
            id, scope_id, issue_key, issue_type, parent_key,
            proposed_summary, proposed_description, proposed_criteria, proposed_technical,
            ai_explanation, proposed_label, proposed_priority, status, jira_result_key,
            created_at, updated_at, updated_by, synced_by
            """;

    /** Inserts a new proposal (without label/priority) and returns it. */
    public ScopeProposal create(String scopeId, String issueKey, String issueType,
                                 String parentKey,
                                 String proposedSummary, String proposedDescription,
                                 String proposedCriteria, String proposedTechnical,
                                 String aiExplanation) {
        return create(scopeId, issueKey, issueType, parentKey,
                proposedSummary, proposedDescription, proposedCriteria, proposedTechnical,
                aiExplanation, null, null);
    }

    /** Inserts a new proposal (with label and priority) and returns it. */
    public ScopeProposal create(String scopeId, String issueKey, String issueType,
                                 String parentKey,
                                 String proposedSummary, String proposedDescription,
                                 String proposedCriteria, String proposedTechnical,
                                 String aiExplanation,
                                 String proposedLabel, String proposedPriority) {
        String sql = """
                INSERT INTO scope_item_proposals
                    (scope_id, issue_key, issue_type, parent_key,
                     proposed_summary, proposed_description, proposed_criteria, proposed_technical,
                     ai_explanation, proposed_label, proposed_priority, status)
                VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT')
                RETURNING id, created_at, updated_at
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scopeId);
            ps.setString(2, issueKey);
            ps.setString(3, issueType);
            ps.setString(4, parentKey);
            ps.setString(5, proposedSummary);
            ps.setString(6, proposedDescription);
            ps.setString(7, proposedCriteria);
            ps.setString(8, proposedTechnical);
            ps.setString(9, aiExplanation);
            ps.setString(10, proposedLabel);
            ps.setString(11, proposedPriority);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String id = rs.getString("id");
                    Timestamp createdAt = rs.getTimestamp("created_at");
                    Timestamp updatedAt = rs.getTimestamp("updated_at");
                    return new ScopeProposal(id, scopeId, issueKey, issueType, parentKey,
                            proposedSummary, proposedDescription, proposedCriteria, proposedTechnical,
                            aiExplanation, proposedLabel, proposedPriority, "DRAFT", null,
                            createdAt != null ? createdAt.toInstant() : null,
                            updatedAt != null ? updatedAt.toInstant() : null,
                            null, null);
                }
            }
        } catch (SQLException e) {
            LOG.errorf("ScopeItemProposalStore.create: %s / %s: %s", scopeId, issueKey, e.getMessage());
            throw new RuntimeException("Failed to create proposal", e);
        }
        throw new RuntimeException("Insert did not return a row for proposal " + issueKey);
    }

    /** Returns all proposals for a scope + issue key, newest first. */
    public List<ScopeProposal> findByScopeAndIssueKey(String scopeId, String issueKey) {
        String sql = "SELECT " + SELECT_COLS + """
                FROM scope_item_proposals
                WHERE scope_id = ?::uuid AND issue_key = ?
                ORDER BY created_at DESC
                """;
        List<ScopeProposal> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scopeId);
            ps.setString(2, issueKey);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) results.add(mapRow(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("ScopeItemProposalStore.findByScopeAndIssueKey: %s / %s: %s",
                    scopeId, issueKey, e.getMessage());
        }
        return results;
    }

    /**
     * Returns the most recent DRAFT proposal for a scope + issue key, if any.
     * Used by {@code initProposal} to avoid creating duplicate drafts.
     */
    public Optional<ScopeProposal> findDraftByScopeAndIssueKey(String scopeId, String issueKey) {
        String sql = "SELECT " + SELECT_COLS + """
                FROM scope_item_proposals
                WHERE scope_id = ?::uuid AND issue_key = ? AND status = 'DRAFT'
                ORDER BY created_at DESC
                LIMIT 1
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scopeId);
            ps.setString(2, issueKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("ScopeItemProposalStore.findDraftByScopeAndIssueKey: %s / %s: %s",
                    scopeId, issueKey, e.getMessage());
        }
        return Optional.empty();
    }

    /** Returns a single proposal by its UUID. */
    public Optional<ScopeProposal> findById(String proposalId) {
        String sql = "SELECT " + SELECT_COLS + """
                FROM scope_item_proposals
                WHERE id = ?::uuid
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, proposalId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("ScopeItemProposalStore.findById: %s: %s", proposalId, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Updates the editable text fields of a proposal (allowed at any status).
     * Null values leave the corresponding column unchanged.
     */
    public void updateFields(String proposalId,
                              String proposedSummary, String proposedDescription,
                              String proposedCriteria, String proposedTechnical) {
        updateFields(proposalId, proposedSummary, proposedDescription,
                proposedCriteria, proposedTechnical, null, null, null);
    }

    /** Updates editable fields including label and priority (updatedBy left unchanged). */
    public void updateFields(String proposalId,
                              String proposedSummary, String proposedDescription,
                              String proposedCriteria, String proposedTechnical,
                              String proposedLabel, String proposedPriority) {
        updateFields(proposalId, proposedSummary, proposedDescription,
                proposedCriteria, proposedTechnical, proposedLabel, proposedPriority, null);
    }

    /** Updates all editable fields including label, priority, and the saving user. */
    public void updateFields(String proposalId,
                              String proposedSummary, String proposedDescription,
                              String proposedCriteria, String proposedTechnical,
                              String proposedLabel, String proposedPriority,
                              String updatedBy) {
        String sql = """
                UPDATE scope_item_proposals
                SET proposed_summary     = COALESCE(?, proposed_summary),
                    proposed_description = COALESCE(?, proposed_description),
                    proposed_criteria    = COALESCE(?, proposed_criteria),
                    proposed_technical   = COALESCE(?, proposed_technical),
                    proposed_label       = COALESCE(?, proposed_label),
                    proposed_priority    = COALESCE(?, proposed_priority),
                    updated_by           = COALESCE(?, updated_by),
                    updated_at           = now()
                WHERE id = ?::uuid
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, proposedSummary);
            ps.setString(2, proposedDescription);
            ps.setString(3, proposedCriteria);
            ps.setString(4, proposedTechnical);
            ps.setString(5, proposedLabel);
            ps.setString(6, proposedPriority);
            ps.setString(7, updatedBy);
            ps.setString(8, proposalId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("ScopeItemProposalStore.updateFields: %s: %s", proposalId, e.getMessage());
            throw new RuntimeException("Failed to update proposal fields", e);
        }
    }

    /**
     * Updates the status of a proposal (ACCEPTED or REJECTED) and optionally records
     * the resulting Jira key and the user who triggered the sync.
     */
    public void updateStatus(String proposalId, String status, String jiraResultKey, String syncedBy) {
        String sql = """
                UPDATE scope_item_proposals
                SET status          = ?,
                    jira_result_key = ?,
                    synced_by       = CASE WHEN ? = 'ACCEPTED' THEN ? ELSE synced_by END,
                    updated_at      = now()
                WHERE id = ?::uuid
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setString(2, jiraResultKey);
            ps.setString(3, status);
            ps.setString(4, syncedBy);
            ps.setString(5, proposalId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("ScopeItemProposalStore.updateStatus: %s → %s: %s", proposalId, status, e.getMessage());
            throw new RuntimeException("Failed to update proposal status", e);
        }
    }

    /** Hard-deletes a proposal row. Allowed at any status. */
    public void delete(String proposalId) {
        String sql = "DELETE FROM scope_item_proposals WHERE id = ?::uuid";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, proposalId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("ScopeItemProposalStore.delete: %s: %s", proposalId, e.getMessage());
            throw new RuntimeException("Failed to delete proposal", e);
        }
    }

    private ScopeProposal mapRow(ResultSet rs) throws SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new ScopeProposal(
                rs.getString("id"),
                rs.getString("scope_id"),
                rs.getString("issue_key"),
                rs.getString("issue_type"),
                rs.getString("parent_key"),
                rs.getString("proposed_summary"),
                rs.getString("proposed_description"),
                rs.getString("proposed_criteria"),
                rs.getString("proposed_technical"),
                rs.getString("ai_explanation"),
                rs.getString("proposed_label"),
                rs.getString("proposed_priority"),
                rs.getString("status"),
                rs.getString("jira_result_key"),
                createdAt != null ? createdAt.toInstant() : null,
                updatedAt != null ? updatedAt.toInstant() : null,
                rs.getString("updated_by"),
                rs.getString("synced_by")
        );
    }
}
