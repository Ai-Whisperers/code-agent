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
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    AgroalDataSource dataSource;

    public void create(ExecutionPlan plan) {
        String sql = """
                INSERT INTO execution_plans
                    (plan_id, status, source_type, source_ref, repo_url, target_branch,
                     title, plan_data, created_at, updated_at, approved_at, summary, error_message)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, plan.planId());
            ps.setString(2, plan.status());
            ps.setString(3, plan.sourceType());
            setNullableString(ps, 4, plan.sourceRef());
            ps.setString(5, plan.repoUrl());
            ps.setString(6, plan.targetBranch() != null ? plan.targetBranch() : "main");
            setNullableString(ps, 7, plan.title());
            ps.setString(8, toJson(plan.planData()));
            ps.setTimestamp(9, timestampOf(plan.createdAt()));
            ps.setTimestamp(10, timestampOf(plan.updatedAt()));
            ps.setTimestamp(11, timestampOf(plan.approvedAt()));
            setNullableString(ps, 12, plan.summary());
            setNullableString(ps, 13, plan.errorMessage());
            ps.executeUpdate();
            LOG.debugf("Created execution plan %s (status=%s)", plan.planId(), plan.status());
        } catch (SQLException e) {
            LOG.errorf("Failed to create execution plan %s: %s", plan.planId(), e.getMessage());
        }
    }

    public Optional<ExecutionPlan> find(String planId) {
        String sql = """
                SELECT plan_id, status, source_type, source_ref, repo_url, target_branch,
                       title, plan_data, created_at, updated_at, approved_at, summary, error_message
                FROM execution_plans
                WHERE plan_id = ?
                """;
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

    public List<ExecutionPlan> listAll() {
        String sql = """
                SELECT plan_id, status, source_type, source_ref, repo_url, target_branch,
                       title, plan_data, created_at, updated_at, approved_at, summary, error_message
                FROM execution_plans
                ORDER BY created_at DESC
                """;
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
        String sql = """
                SELECT plan_id, status, source_type, source_ref, repo_url, target_branch,
                       title, plan_data, created_at, updated_at, approved_at, summary, error_message
                FROM execution_plans
                WHERE status = ?
                ORDER BY created_at DESC
                """;
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
     * Updates the {@code status} and {@code jobId} of a single step inside the JSONB
     * {@code plan_data} column, identified by {@code stepId}. All other step fields are
     * preserved. If the step is not found the plan_data is left unchanged.
     *
     * <p>Uses a {@code SELECT ... FOR UPDATE} inside a transaction to prevent lost
     * updates when multiple steps in the same phase complete concurrently.
     */
    public void updateStepInPlan(String planId, String stepId, String stepStatus, String jobId) {
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

    // ─── Private helpers ────────────────────────────────────────────────

    private ExecutionPlan mapRow(ResultSet rs) throws SQLException {
        Timestamp createdTs = rs.getTimestamp("created_at");
        Timestamp updatedTs = rs.getTimestamp("updated_at");
        Timestamp approvedTs = rs.getTimestamp("approved_at");
        String planDataJson = rs.getString("plan_data");
        PlanData planData = fromJson(planDataJson);
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
                rs.getString("error_message")
        );
    }

    private static String toJson(PlanData planData) {
        try {
            return MAPPER.writeValueAsString(planData);
        } catch (JsonProcessingException e) {
            LOG.warnf("Failed to serialize PlanData: %s", e.getMessage());
            return "{\"phases\":[]}";
        }
    }

    private static PlanData fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new PlanData(List.of());
        }
        try {
            return MAPPER.readValue(json, PlanData.class);
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
