package com.eneve.agent.agent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * PostgreSQL-backed store for agent comment context.
 * Maps Bitbucket comment IDs to the review context that produced them,
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
                    (comment_id, pr_id, workspace, repo_slug, file_path, line_number,
                     category, severity, finding_text, review_job_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (comment_id) DO NOTHING
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, commentId);
            ps.setString(2, ctx.prId());
            ps.setString(3, ctx.workspace());
            ps.setString(4, ctx.repoSlug());
            ps.setString(5, ctx.filePath());
            ps.setInt(6, ctx.line());
            ps.setString(7, ctx.category());
            ps.setString(8, ctx.severity());
            ps.setString(9, ctx.findingText());
            ps.setString(10, ctx.reviewJobId());
            ps.executeUpdate();
            LOG.debugf("Stored agent comment %d for PR #%s (%s/%s)",
                    commentId, ctx.prId(), ctx.workspace(), ctx.repoSlug());
        } catch (SQLException e) {
            LOG.errorf("Failed to store agent comment %d: %s", commentId, e.getMessage());
        }
    }

    public Optional<CommentContext> find(long commentId) {
        String sql = """
                SELECT pr_id, workspace, repo_slug, file_path, line_number,
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
}
