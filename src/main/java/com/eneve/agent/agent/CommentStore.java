package com.eneve.agent.agent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * PostgreSQL-backed store for agent comment context.
 * Maps platform comment IDs to the review context that produced them,
 * enabling conversational replies when developers respond in-thread.
 */
@ApplicationScoped
public class CommentStore {

    private static final Logger LOG = Logger.getLogger(CommentStore.class);

    @Inject
    AgroalDataSource dataSource;

    public void save(long commentId, CommentContext ctx) {
        String sql = """
                INSERT INTO agent_comments
                    (comment_id, pr_id, workspace, project, repo_slug, file_path, line_number,
                     category, severity, finding_text, review_job_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (comment_id) DO NOTHING
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, commentId);
            ps.setString(2, ctx.prId());
            ps.setString(3, ctx.organization());
            ps.setString(4, ctx.project());
            ps.setString(5, ctx.repository());
            ps.setString(6, ctx.filePath());
            ps.setInt(7, ctx.line());
            ps.setString(8, ctx.category());
            ps.setString(9, ctx.severity());
            ps.setString(10, ctx.findingText());
            ps.setString(11, ctx.reviewJobId());
            ps.executeUpdate();
            LOG.debugf("Stored agent comment %d for PR #%s (%s/%s)",
                    commentId, ctx.prId(), ctx.organization(), ctx.repository());
        } catch (SQLException e) {
            LOG.errorf("Failed to store agent comment %d: %s", commentId, e.getMessage());
        }
    }

    public Optional<CommentContext> find(long commentId) {
        String sql = """
                SELECT pr_id, workspace, project, repo_slug, file_path, line_number,
                       category, severity, finding_text, review_job_id
                FROM agent_comments WHERE comment_id = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, commentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new CommentContext(
                            rs.getString("pr_id"),
                            rs.getString("workspace"),
                            rs.getString("project"),
                            rs.getString("repo_slug"),
                            rs.getString("file_path"),
                            rs.getInt("line_number"),
                            rs.getString("category"),
                            rs.getString("severity"),
                            rs.getString("finding_text"),
                            rs.getString("review_job_id")
                    ));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to look up agent comment %d: %s", commentId, e.getMessage());
        }
        return Optional.empty();
    }

    public boolean contains(long commentId) {
        String sql = "SELECT 1 FROM agent_comments WHERE comment_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, commentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to check agent comment %d: %s", commentId, e.getMessage());
            return false;
        }
    }

    /**
     * Return all unresolved inline findings the agent posted on this PR.
     * Excludes summary comments and file-level-only comments (line = 0).
     */
    public List<OpenFinding> findOpenInlineComments(String prId, String org, String repo) {
        String sql = """
                SELECT comment_id, file_path, line_number, finding_text, severity
                FROM agent_comments
                WHERE pr_id = ? AND workspace = ? AND repo_slug = ?
                  AND resolved = false
                  AND file_path NOT IN ('', '__summary__')
                  AND line_number > 0
                """;
        List<OpenFinding> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, prId);
            ps.setString(2, org);
            ps.setString(3, repo);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new OpenFinding(
                            rs.getLong("comment_id"),
                            rs.getString("file_path"),
                            rs.getInt("line_number"),
                            rs.getString("finding_text"),
                            rs.getString("severity")
                    ));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to query open inline comments for PR #%s: %s", prId, e.getMessage());
        }
        return results;
    }

    public void markResolved(long commentId) {
        String sql = "UPDATE agent_comments SET resolved = true WHERE comment_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, commentId);
            ps.executeUpdate();
            LOG.debugf("Marked agent comment %d as resolved", commentId);
        } catch (SQLException e) {
            LOG.errorf("Failed to mark comment %d as resolved: %s", commentId, e.getMessage());
        }
    }

    /**
     * Look up the platform comment ID of the persistent review summary for this PR.
     * Returns empty if no summary has been posted yet.
     */
    public Optional<Long> findSummaryCommentId(String prId, String org, String repo) {
        String sql = """
                SELECT comment_id FROM agent_comments
                WHERE pr_id = ? AND workspace = ? AND repo_slug = ? AND file_path = '__summary__'
                LIMIT 1
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, prId);
            ps.setString(2, org);
            ps.setString(3, repo);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rs.getLong("comment_id"));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to look up summary comment for PR #%s: %s", prId, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Persist (or replace) the review summary comment ID for this PR.
     * Deletes any existing __summary__ row first so the comment ID is always current.
     */
    public void saveSummaryComment(long commentId, String prId, String org, String project,
                                   String repo, String reviewJobId) {
        String delete = """
                DELETE FROM agent_comments
                WHERE pr_id = ? AND workspace = ? AND repo_slug = ? AND file_path = '__summary__'
                """;
        String insert = """
                INSERT INTO agent_comments
                    (comment_id, pr_id, workspace, project, repo_slug, file_path, line_number,
                     category, severity, finding_text, review_job_id)
                VALUES (?, ?, ?, ?, ?, '__summary__', 0, '', '', 'summary', ?)
                """;
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement del = conn.prepareStatement(delete);
                 PreparedStatement ins = conn.prepareStatement(insert)) {
                del.setString(1, prId);
                del.setString(2, org);
                del.setString(3, repo);
                del.executeUpdate();

                ins.setLong(1, commentId);
                ins.setString(2, prId);
                ins.setString(3, org);
                ins.setString(4, project);
                ins.setString(5, repo);
                ins.setString(6, reviewJobId);
                ins.executeUpdate();
                conn.commit();
                LOG.debugf("Saved summary comment %d for PR #%s (%s/%s)", commentId, prId, org, repo);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to save summary comment %d for PR #%s: %s", commentId, prId, e.getMessage());
        }
    }
}
