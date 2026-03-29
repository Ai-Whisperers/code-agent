package com.eneve.agent.planner;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.eneve.agent.model.RepoCoordinates;
import com.eneve.agent.scm.GitPlatformService;
import com.eneve.agent.util.UrlUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * PostgreSQL-backed store for execution plans.
 * Plan content is stored as JSONB in the {@code plan_data} column.
 */
@ApplicationScoped
public class PlanStore {

    private static final Logger LOG = Logger.getLogger(PlanStore.class);
    @Inject ObjectMapper mapper;

    private static final String SELECT_COLS = """
            plan_id, status, source_type, source_ref, repo_url, target_branch,
            title, plan_data, created_at, updated_at, approved_at, summary, error_message, pr_url,
            conversation_id, markdown_content, workspace_path, archived, created_by
            """;

    @Inject
    AgroalDataSource dataSource;

    @Inject
    GitPlatformService platformService;

    public void create(ExecutionPlan plan) {
        String sql = """
                INSERT INTO execution_plans
                    (plan_id, status, source_type, source_ref, repo_url, target_branch,
                     title, plan_data, created_at, updated_at, approved_at, summary, error_message, pr_url,
                     conversation_id, markdown_content, workspace_path, archived, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, plan.planId());
            ps.setString(2, plan.status());
            ps.setString(3, plan.sourceType());
            setNullableString(ps, 4, plan.sourceRef());
            ps.setString(5, UrlUtils.stripCredentials(plan.repoUrl()));
            ps.setString(6, plan.targetBranch() != null ? plan.targetBranch() : "main");
            setNullableString(ps, 7, plan.title());
            ps.setString(8, toJson(plan.planData()));
            ps.setTimestamp(9, timestampOf(plan.createdAt()));
            ps.setTimestamp(10, timestampOf(plan.updatedAt()));
            ps.setTimestamp(11, timestampOf(plan.approvedAt()));
            setNullableString(ps, 12, plan.summary());
            setNullableString(ps, 13, plan.errorMessage());
            setNullableString(ps, 14, plan.prUrl());
            setNullableString(ps, 15, plan.conversationId());
            setNullableString(ps, 16, plan.markdownContent());
            setNullableString(ps, 17, plan.workspacePath());
            ps.setBoolean(18, plan.archived());
            setNullableString(ps, 19, plan.createdBy());
            ps.executeUpdate();
            LOG.debugf("Created execution plan %s (status=%s)", plan.planId(), plan.status());
        } catch (SQLException e) {
            LOG.errorf("Failed to create execution plan %s: %s", plan.planId(), e.getMessage());
        }
    }

    public Optional<ExecutionPlan> find(String planId) {
        String sql = "SELECT " + SELECT_COLS + " FROM execution_plans WHERE plan_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, planId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to find execution plan %s: %s", planId, e.getMessage());
        }
        return Optional.empty();
    }

    /** Lists all non-archived plans ordered by creation date descending. */
    public List<ExecutionPlan> listAll() {
        return listAll(false);
    }

    /** Lists plans ordered by creation date descending, optionally including archived. */
    public List<ExecutionPlan> listAll(boolean includeArchived) {
        String sql = "SELECT " + SELECT_COLS + " FROM execution_plans"
                + (includeArchived ? "" : " WHERE archived = false")
                + " ORDER BY created_at DESC";
        List<ExecutionPlan> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                results.add(mapRow(rs));
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to list execution plans: %s", e.getMessage());
        }
        return results;
    }

    public List<ExecutionPlan> listByStatus(String status) {
        String sql = "SELECT " + SELECT_COLS + " FROM execution_plans"
                + " WHERE status = ? AND archived = false ORDER BY created_at DESC";
        List<ExecutionPlan> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to list execution plans by status %s: %s", status, e.getMessage());
        }
        return results;
    }

    public void updatePlanData(String planId, PlanData planData) {
        String sql = """
                UPDATE execution_plans
                SET plan_data = ?::jsonb, updated_at = now()
                WHERE plan_id = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, toJson(planData));
            ps.setString(2, planId);
            ps.executeUpdate();
            LOG.debugf("Updated plan_data for execution plan %s", planId);
        } catch (SQLException e) {
            LOG.errorf("Failed to update plan_data for %s: %s", planId, e.getMessage());
        }
    }

    public void updateStatus(String planId, String status) {
        String sql = """
                UPDATE execution_plans
                SET status = ?, updated_at = now()
                WHERE plan_id = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setString(2, planId);
            ps.executeUpdate();
            LOG.debugf("Updated status of execution plan %s to %s", planId, status);
        } catch (SQLException e) {
            LOG.errorf("Failed to update status for %s: %s", planId, e.getMessage());
        }
    }

    public void approve(String planId) {
        String sql = """
                UPDATE execution_plans
                SET status = 'APPROVED', approved_at = now(), updated_at = now()
                WHERE plan_id = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, planId);
            ps.executeUpdate();
            LOG.infof("Approved execution plan %s", planId);
        } catch (SQLException e) {
            LOG.errorf("Failed to approve execution plan %s: %s", planId, e.getMessage());
        }
    }

    /**
     * Marks a plan as archived. Archived plans are hidden from the default list view
     * but are not deleted from the database.
     */
    public void archive(String planId) {
        String sql = """
                UPDATE execution_plans
                SET archived = true, updated_at = now()
                WHERE plan_id = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, planId);
            ps.executeUpdate();
            LOG.infof("Archived execution plan %s", planId);
        } catch (SQLException e) {
            LOG.errorf("Failed to archive execution plan %s: %s", planId, e.getMessage());
        }
    }

    /**
     * Updates the plan status and error_message in a single statement.
     * Pass {@code null} for {@code errorMessage} to clear the field.
     */
    public void updateStatusAndError(String planId, String status, String errorMessage) {
        String sql = """
                UPDATE execution_plans
                SET status = ?, error_message = ?, updated_at = now()
                WHERE plan_id = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            setNullableString(ps, 2, errorMessage);
            ps.setString(3, planId);
            ps.executeUpdate();
            LOG.debugf("Updated status/error of execution plan %s to %s", planId, status);
        } catch (SQLException e) {
            LOG.errorf("Failed to update status/error for %s: %s", planId, e.getMessage());
        }
    }

    /**
     * Updates the {@code status}, {@code jobId}, and {@code errorMessage} of a single step
     * inside the JSONB {@code plan_data} column, identified by {@code stepId}. All other
     * step fields are preserved. If the step is not found the plan_data is left unchanged.
     * Pass {@code null} for {@code errorMessage} to leave it unset (successful steps).
     *
     * <p>Uses a {@code SELECT ... FOR UPDATE} inside a transaction to prevent lost
     * updates when multiple steps in the same phase complete concurrently.
     */
    public void updateStepInPlan(String planId, String stepId, String stepStatus, String jobId, String errorMessage) {
        String selectSql = """
                SELECT plan_data
                FROM execution_plans
                WHERE plan_id = ?
                FOR UPDATE
                """;
        String updateSql = """
                UPDATE execution_plans
                SET plan_data = ?::jsonb, updated_at = now()
                WHERE plan_id = ?
                """;
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement sel = conn.prepareStatement(selectSql)) {
                sel.setString(1, planId);
                try (ResultSet rs = sel.executeQuery()) {
                    if (!rs.next()) {
                        LOG.warnf("updateStepInPlan: plan %s not found", planId);
                        conn.rollback();
                        return;
                    }
                    PlanData current = fromJson(rs.getString("plan_data"));
                    if (current == null || current.phases() == null) {
                        conn.rollback();
                        return;
                    }

                    boolean[] found = {false};
                    List<PlanPhase> updatedPhases = new ArrayList<>();
                    for (PlanPhase phase : current.phases()) {
                        List<PlanStep> updatedSteps = new ArrayList<>();
                        for (PlanStep step : phase.steps()) {
                            if (step.stepId().equals(stepId)) {
                                found[0] = true;
                                PlanStep updated = step.withStatus(stepStatus);
                                if (jobId != null) {
                                    updated = updated.withJobId(jobId);
                                }
                                if (errorMessage != null) {
                                    updated = updated.withErrorMessage(errorMessage);
                                }
                                updatedSteps.add(updated);
                            } else {
                                updatedSteps.add(step);
                            }
                        }
                        updatedPhases.add(new PlanPhase(phase.order(), phase.name(), phase.gateOnSuccess(), updatedSteps));
                    }

                    if (!found[0]) {
                        LOG.warnf("updateStepInPlan: step %s not found in plan %s", stepId, planId);
                        conn.rollback();
                        return;
                    }

                    try (PreparedStatement upd = conn.prepareStatement(updateSql)) {
                        upd.setString(1, toJson(new PlanData(updatedPhases)));
                        upd.setString(2, planId);
                        upd.executeUpdate();
                    }
                    conn.commit();
                    LOG.debugf("updateStepInPlan: updated step %s in plan %s to %s", stepId, planId, stepStatus);
                }
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            LOG.errorf("updateStepInPlan: failed for step %s in plan %s: %s", stepId, planId, e.getMessage());
        }
    }

    public boolean delete(String planId) {
        String sql = "DELETE FROM execution_plans WHERE plan_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, planId);
            int rows = ps.executeUpdate();
            if (rows > 0) {
                LOG.debugf("Deleted execution plan %s", planId);
            }
            return rows > 0;
        } catch (SQLException e) {
            LOG.errorf("Failed to delete execution plan %s: %s", planId, e.getMessage());
            return false;
        }
    }

    /**
     * Approves and merges the pull request for a completed plan.
     */
    public void approvePlanPr(String planId, String prUrl) {
        Optional<ExecutionPlan> planOpt = find(planId);
        if (planOpt.isEmpty()) {
            throw new RuntimeException("Plan not found: " + planId);
        }
        ExecutionPlan plan = planOpt.get();
        try {
            RepoCoordinates coords = RepoCoordinates.parse(plan.repoUrl());
            String prId = extractPrIdFromUrl(prUrl);
            platformService.mergePullRequest(coords.organization(), coords.project(), coords.repository(), prId);
            LOG.infof("Plan %s PR approved and merged: %s", planId, prUrl);
        } catch (Exception e) {
            LOG.errorf("Failed to approve PR for plan %s: %s", planId, e.getMessage());
            throw new RuntimeException("Failed to merge PR: " + e.getMessage(), e);
        }
    }

    /**
     * Rejects and declines the pull request for a completed plan.
     */
    public void rejectPlanPr(String planId, String prUrl, String reason) {
        Optional<ExecutionPlan> planOpt = find(planId);
        if (planOpt.isEmpty()) {
            throw new RuntimeException("Plan not found: " + planId);
        }
        ExecutionPlan plan = planOpt.get();
        try {
            RepoCoordinates coords = RepoCoordinates.parse(plan.repoUrl());
            String prId = extractPrIdFromUrl(prUrl);
            platformService.declinePullRequest(coords.organization(), coords.project(), coords.repository(), prId);
            LOG.infof("Plan %s PR rejected: %s (reason: %s)", planId, prUrl, reason);
        } catch (Exception e) {
            LOG.errorf("Failed to reject PR for plan %s: %s", planId, e.getMessage());
            throw new RuntimeException("Failed to decline PR: " + e.getMessage(), e);
        }
    }

    private String extractPrIdFromUrl(String prUrl) {
        if (prUrl != null && prUrl.contains("/pull-requests/")) {
            String[] parts = prUrl.split("/pull-requests/");
            if (parts.length > 1) {
                return parts[1].split("/")[0];
            }
        }
        throw new IllegalArgumentException("Cannot extract PR ID from URL: " + prUrl);
    }

    // ─── Private helpers ────────────────────────────────────────────────

    public void updatePrUrl(String planId, String prUrl) {
        String sql = """
                UPDATE execution_plans
                SET pr_url = ?, updated_at = now()
                WHERE plan_id = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            setNullableString(ps, 1, prUrl);
            ps.setString(2, planId);
            ps.executeUpdate();
            LOG.debugf("Updated pr_url for execution plan %s", planId);
        } catch (SQLException e) {
            LOG.errorf("Failed to update pr_url for %s: %s", planId, e.getMessage());
        }
    }

    private ExecutionPlan mapRow(ResultSet rs) throws SQLException {
        Timestamp createdTs = rs.getTimestamp("created_at");
        Timestamp updatedTs = rs.getTimestamp("updated_at");
        Timestamp approvedTs = rs.getTimestamp("approved_at");
        PlanData planData = fromJson(rs.getString("plan_data"));
        return new ExecutionPlan(
                rs.getString("plan_id"),
                rs.getString("status"),
                rs.getString("source_type"),
                rs.getString("source_ref"),
                rs.getString("repo_url"),
                rs.getString("target_branch"),
                rs.getString("title"),
                planData,
                createdTs != null ? createdTs.toInstant() : null,
                updatedTs != null ? updatedTs.toInstant() : null,
                approvedTs != null ? approvedTs.toInstant() : null,
                rs.getString("summary"),
                rs.getString("error_message"),
                rs.getString("pr_url"),
                rs.getString("conversation_id"),
                rs.getString("markdown_content"),
                rs.getString("workspace_path"),
                rs.getBoolean("archived"),
                rs.getString("created_by")
        );
    }

    public List<ExecutionPlan> findByConversationId(String conversationId) {
        String sql = "SELECT " + SELECT_COLS + " FROM execution_plans"
                + " WHERE conversation_id = ? ORDER BY created_at DESC";
        List<ExecutionPlan> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to find plans by conversation ID %s: %s", conversationId, e.getMessage());
        }
        return results;
    }

    public void updateMarkdownContent(String planId, String markdownContent) {
        String sql = """
                UPDATE execution_plans
                SET markdown_content = ?, updated_at = now()
                WHERE plan_id = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            setNullableString(ps, 1, markdownContent);
            ps.setString(2, planId);
            ps.executeUpdate();
            LOG.debugf("Updated markdown content for plan %s", planId);
        } catch (SQLException e) {
            LOG.errorf("Failed to update markdown content for plan %s: %s", planId, e.getMessage());
        }
    }

    public void updateWorkspacePath(String planId, String workspacePath) {
        String sql = """
                UPDATE execution_plans
                SET workspace_path = ?, updated_at = now()
                WHERE plan_id = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            setNullableString(ps, 1, workspacePath);
            ps.setString(2, planId);
            ps.executeUpdate();
            LOG.debugf("Updated workspace path for plan %s", planId);
        } catch (SQLException e) {
            LOG.errorf("Failed to update workspace path for plan %s: %s", planId, e.getMessage());
        }
    }

    public void updateConversationId(String planId, String conversationId) {
        String sql = """
                UPDATE execution_plans
                SET conversation_id = ?, updated_at = now()
                WHERE plan_id = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            setNullableString(ps, 1, conversationId);
            ps.setString(2, planId);
            ps.executeUpdate();
            LOG.debugf("Updated conversation ID for plan %s", planId);
        } catch (SQLException e) {
            LOG.errorf("Failed to update conversation ID for plan %s: %s", planId, e.getMessage());
        }
    }

    private String toJson(PlanData planData) {
        if (planData == null) {
            return "{\"phases\":[]}";
        }
        try {
            return mapper.writeValueAsString(planData);
        } catch (JsonProcessingException e) {
            LOG.warnf("Failed to serialize PlanData: %s", e.getMessage());
            return "{\"phases\":[]}";
        }
    }

    private PlanData fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new PlanData(List.of());
        }
        try {
            return mapper.readValue(json, PlanData.class);
        } catch (JsonProcessingException e) {
            LOG.warnf("Failed to deserialize PlanData: %s", e.getMessage());
            return new PlanData(List.of());
        }
    }

    private void setNullableString(PreparedStatement ps, int index, String value) throws SQLException {
        if (value != null) {
            ps.setString(index, value);
        } else {
            ps.setNull(index, Types.VARCHAR);
        }
    }

    private static Timestamp timestampOf(Instant instant) {
        return instant != null ? Timestamp.from(instant) : Timestamp.from(Instant.now());
    }
}
