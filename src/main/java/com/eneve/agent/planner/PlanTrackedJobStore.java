package com.eneve.agent.planner;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * PostgreSQL-backed store for in-flight plan jobs.
 *
 * <p>A row exists for every job that was dispatched by {@link PlanOrchestratorService}
 * but has not yet completed. Persisting this mapping allows the orchestrator to
 * rehydrate its in-memory {@code trackedJobs} map after a process restart so that
 * job-completion events can still be correlated with the correct plan and step.
 */
@ApplicationScoped
public class PlanTrackedJobStore {

    private static final Logger LOG = Logger.getLogger(PlanTrackedJobStore.class);

    @Inject
    AgroalDataSource dataSource;

    public void insert(String jobId, String planId, String stepId, int phaseOrder, boolean isMetrics) {
        String sql = """
                INSERT INTO plan_tracked_jobs (job_id, plan_id, step_id, phase_order, is_metrics)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (job_id) DO NOTHING
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, jobId);
            ps.setString(2, planId);
            ps.setString(3, stepId);
            ps.setInt(4, phaseOrder);
            ps.setBoolean(5, isMetrics);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("Failed to insert plan_tracked_jobs row for job %s / plan %s: %s",
                    jobId, planId, e.getMessage());
        }
    }

    public void delete(String jobId) {
        String sql = "DELETE FROM plan_tracked_jobs WHERE job_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, jobId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("Failed to delete plan_tracked_jobs row for job %s: %s", jobId, e.getMessage());
        }
    }

    public void deleteByPlanId(String planId) {
        String sql = "DELETE FROM plan_tracked_jobs WHERE plan_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, planId);
            int rows = ps.executeUpdate();
            LOG.debugf("Deleted %d plan_tracked_jobs rows for plan %s", rows, planId);
        } catch (SQLException e) {
            LOG.errorf("Failed to delete plan_tracked_jobs rows for plan %s: %s", planId, e.getMessage());
        }
    }

    /** Returns all rows — used on startup to rehydrate in-memory state. */
    public List<TrackedJobRow> findAll() {
        String sql = "SELECT job_id, plan_id, step_id, phase_order, is_metrics FROM plan_tracked_jobs";
        List<TrackedJobRow> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                results.add(new TrackedJobRow(
                        rs.getString("job_id"),
                        rs.getString("plan_id"),
                        rs.getString("step_id"),
                        rs.getInt("phase_order"),
                        rs.getBoolean("is_metrics")
                ));
            }
        } catch (SQLException e) {
            LOG.errorf("Failed to load plan_tracked_jobs: %s", e.getMessage());
        }
        return results;
    }

    public record TrackedJobRow(String jobId, String planId, String stepId, int phaseOrder, boolean isMetrics) {}
}
