package com.eneve.agent.agent.store;

import com.eneve.agent.model.RoadmapProposal;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC store for {@code roadmap_item_proposals}.
 * Proposals are AI-generated rewrites of Jira issues that live only in the
 * database until a user explicitly accepts them.
 */
@ApplicationScoped
public class RoadmapItemProposalStore {

    private static final Logger LOG = Logger.getLogger(RoadmapItemProposalStore.class);

    @Inject
    AgroalDataSource dataSource;

    /**
     * Inserts a new proposal and returns its generated UUID.
     */
    public RoadmapProposal create(String roadmapId, String issueKey, String issueType,
                                   String parentKey,
                                   String proposedSummary, String proposedDescription,
                                   String proposedCriteria, String proposedTechnical,
                                   String aiExplanation) {
        String sql = """
                INSERT INTO roadmap_item_proposals
                    (roadmap_id, issue_key, issue_type, parent_key,
                     proposed_summary, proposed_description, proposed_criteria, proposed_technical,
                     ai_explanation, status)
                VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT')
                RETURNING id, created_at, updated_at
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, roadmapId);
            ps.setString(2, issueKey);
            ps.setString(3, issueType);
            ps.setString(4, parentKey);
            ps.setString(5, proposedSummary);
            ps.setString(6, proposedDescription);
            ps.setString(7, proposedCriteria);
            ps.setString(8, proposedTechnical);
            ps.setString(9, aiExplanation);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String id = rs.getString("id");
                    Timestamp createdAt = rs.getTimestamp("created_at");
                    Timestamp updatedAt = rs.getTimestamp("updated_at");
                    return new RoadmapProposal(id, roadmapId, issueKey, issueType, parentKey,
                            proposedSummary, proposedDescription, proposedCriteria, proposedTechnical,
                            aiExplanation, "DRAFT", null,
                            createdAt != null ? createdAt.toInstant() : null,
                            updatedAt != null ? updatedAt.toInstant() : null);
                }
            }
        } catch (SQLException e) {
            LOG.errorf("RoadmapItemProposalStore.create: %s / %s: %s", roadmapId, issueKey, e.getMessage());
            throw new RuntimeException("Failed to create proposal", e);
        }
        throw new RuntimeException("Insert did not return a row for proposal " + issueKey);
    }

    /** Returns all proposals for a roadmap + issue key, newest first. */
    public List<RoadmapProposal> findByRoadmapAndIssueKey(String roadmapId, String issueKey) {
        String sql = """
                SELECT id, roadmap_id, issue_key, issue_type, parent_key,
                       proposed_summary, proposed_description, proposed_criteria, proposed_technical,
                       ai_explanation, status, jira_result_key, created_at, updated_at
                FROM roadmap_item_proposals
                WHERE roadmap_id = ?::uuid AND issue_key = ?
                ORDER BY created_at DESC
                """;
        List<RoadmapProposal> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, roadmapId);
            ps.setString(2, issueKey);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) results.add(mapRow(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("RoadmapItemProposalStore.findByRoadmapAndIssueKey: %s / %s: %s",
                    roadmapId, issueKey, e.getMessage());
        }
        return results;
    }

    /** Returns a single proposal by its UUID. */
    public Optional<RoadmapProposal> findById(String proposalId) {
        String sql = """
                SELECT id, roadmap_id, issue_key, issue_type, parent_key,
                       proposed_summary, proposed_description, proposed_criteria, proposed_technical,
                       ai_explanation, status, jira_result_key, created_at, updated_at
                FROM roadmap_item_proposals
                WHERE id = ?::uuid
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, proposalId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("RoadmapItemProposalStore.findById: %s: %s", proposalId, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Updates the editable text fields of a proposal (allowed at any status).
     */
    public void updateFields(String proposalId,
                              String proposedSummary, String proposedDescription,
                              String proposedCriteria, String proposedTechnical) {
        String sql = """
                UPDATE roadmap_item_proposals
                SET proposed_summary     = ?,
                    proposed_description = ?,
                    proposed_criteria    = ?,
                    proposed_technical   = ?,
                    updated_at           = now()
                WHERE id = ?::uuid
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, proposedSummary);
            ps.setString(2, proposedDescription);
            ps.setString(3, proposedCriteria);
            ps.setString(4, proposedTechnical);
            ps.setString(5, proposalId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("RoadmapItemProposalStore.updateFields: %s: %s", proposalId, e.getMessage());
            throw new RuntimeException("Failed to update proposal fields", e);
        }
    }

    /**
     * Updates the status of a proposal (ACCEPTED or REJECTED) and optionally records
     * the resulting Jira key when accepted.
     */
    public void updateStatus(String proposalId, String status, String jiraResultKey) {
        String sql = """
                UPDATE roadmap_item_proposals
                SET status          = ?,
                    jira_result_key = ?,
                    updated_at      = now()
                WHERE id = ?::uuid
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setString(2, jiraResultKey);
            ps.setString(3, proposalId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("RoadmapItemProposalStore.updateStatus: %s → %s: %s", proposalId, status, e.getMessage());
            throw new RuntimeException("Failed to update proposal status", e);
        }
    }

    /**
     * Hard-deletes a proposal row. Allowed at any status.
     */
    public void delete(String proposalId) {
        String sql = "DELETE FROM roadmap_item_proposals WHERE id = ?::uuid";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, proposalId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("RoadmapItemProposalStore.delete: %s: %s", proposalId, e.getMessage());
            throw new RuntimeException("Failed to delete proposal", e);
        }
    }

    private RoadmapProposal mapRow(ResultSet rs) throws SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new RoadmapProposal(
                rs.getString("id"),
                rs.getString("roadmap_id"),
                rs.getString("issue_key"),
                rs.getString("issue_type"),
                rs.getString("parent_key"),
                rs.getString("proposed_summary"),
                rs.getString("proposed_description"),
                rs.getString("proposed_criteria"),
                rs.getString("proposed_technical"),
                rs.getString("ai_explanation"),
                rs.getString("status"),
                rs.getString("jira_result_key"),
                createdAt != null ? createdAt.toInstant() : null,
                updatedAt != null ? updatedAt.toInstant() : null
        );
    }
}
