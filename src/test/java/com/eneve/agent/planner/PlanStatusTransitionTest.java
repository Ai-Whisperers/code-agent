package com.eneve.agent.planner;

import com.eneve.agent.agent.JobQueue;
import com.eneve.agent.agent.store.CodeMetricsStore;
import com.eneve.agent.scm.GitPlatformService;
import com.eneve.agent.settings.SettingsService;
import com.eneve.agent.workspace.PlanWorkspaceManager;
import jakarta.enterprise.event.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the pause/resume/cancel state-machine logic in
 * {@link PlanOrchestratorService}, verifying that status transitions use
 * {@code EXECUTING} exclusively (no {@code RUNNING} string literals).
 *
 * <p>All CDI dependencies are mocked via reflection so no container is needed.
 */
class PlanStatusTransitionTest {

    private PlanStore planStore;
    private PlanTrackedJobStore trackedJobStore;
    private PlanWorkspaceManager planWorkspaceManager;
    private PlanOrchestratorService orchestrator;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        planStore             = Mockito.mock(PlanStore.class);
        trackedJobStore       = Mockito.mock(PlanTrackedJobStore.class);
        planWorkspaceManager  = Mockito.mock(PlanWorkspaceManager.class);

        orchestrator = new PlanOrchestratorService();

        injectField(orchestrator, "planStore",            planStore);
        injectField(orchestrator, "trackedJobStore",      trackedJobStore);
        injectField(orchestrator, "planWorkspaceManager", planWorkspaceManager);
        injectField(orchestrator, "jobQueue",             Mockito.mock(JobQueue.class));
        injectField(orchestrator, "codeMetricsStore",     Mockito.mock(CodeMetricsStore.class));
        injectField(orchestrator, "platformService",      Mockito.mock(GitPlatformService.class));
        injectField(orchestrator, "settings",             Mockito.mock(SettingsService.class));
        injectField(orchestrator, "planCompletedEvent",   Mockito.mock(Event.class));
        injectField(orchestrator, "orchestratorEvent",    Mockito.mock(Event.class));

        when(trackedJobStore.findAll()).thenReturn(List.of());
    }

    // ─── pausePlan ────────────────────────────────────────────────────────────

    @Test
    void pausePlan_whenExecuting_setsStatusToPaused() {
        String planId = "p1";
        when(planStore.find(planId)).thenReturn(Optional.of(plan(planId, "EXECUTING")));

        orchestrator.pausePlan(planId);

        verify(planStore).updateStatus(planId, PlanStatus.PAUSED.name());
    }

    @Test
    void pausePlan_whenDraft_throws() {
        String planId = "p2";
        when(planStore.find(planId)).thenReturn(Optional.of(plan(planId, "DRAFT")));

        assertThrows(IllegalStateException.class, () -> orchestrator.pausePlan(planId));
        verify(planStore, never()).updateStatus(anyString(), anyString());
    }

    @Test
    void pausePlan_whenAlreadyPaused_throws() {
        String planId = "p3";
        when(planStore.find(planId)).thenReturn(Optional.of(plan(planId, "PAUSED")));

        assertThrows(IllegalStateException.class, () -> orchestrator.pausePlan(planId));
    }

    // ─── resumePlan ───────────────────────────────────────────────────────────

    @Test
    void resumePlan_whenPaused_restoresExecuting() {
        String planId = "p4";
        ExecutionPlan paused = plan(planId, "PAUSED");
        // After status update, find returns a plan with EXECUTING status and empty phases so
        // continueExecution returns immediately without submitting jobs.
        when(planStore.find(planId))
                .thenReturn(Optional.of(paused))
                .thenReturn(Optional.of(plan(planId, "EXECUTING")));

        orchestrator.resumePlan(planId);

        // The critical assertion: must set EXECUTING, not "RUNNING"
        verify(planStore).updateStatus(planId, PlanStatus.EXECUTING.name());
        verify(planStore, never()).updateStatus(planId, "RUNNING");
    }

    @Test
    void resumePlan_whenNotPaused_throws() {
        String planId = "p5";
        when(planStore.find(planId)).thenReturn(Optional.of(plan(planId, "EXECUTING")));

        assertThrows(IllegalStateException.class, () -> orchestrator.resumePlan(planId));
    }

    // ─── cancelPlan ───────────────────────────────────────────────────────────

    @Test
    void cancelPlan_whenExecuting_setsCancelled() {
        String planId = "p6";
        when(planStore.find(planId)).thenReturn(Optional.of(plan(planId, "EXECUTING")));

        orchestrator.cancelPlan(planId);

        verify(planStore).updateStatus(planId, PlanStatus.CANCELLED.name());
    }

    @Test
    void cancelPlan_whenPaused_setsCancelled() {
        String planId = "p7";
        when(planStore.find(planId)).thenReturn(Optional.of(plan(planId, "PAUSED")));

        orchestrator.cancelPlan(planId);

        verify(planStore).updateStatus(planId, PlanStatus.CANCELLED.name());
    }

    @Test
    void cancelPlan_whenCompleted_throws() {
        String planId = "p8";
        when(planStore.find(planId)).thenReturn(Optional.of(plan(planId, "COMPLETED")));

        assertThrows(IllegalStateException.class, () -> orchestrator.cancelPlan(planId));
        verify(planStore, never()).updateStatus(anyString(), eq(PlanStatus.CANCELLED.name()));
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static ExecutionPlan plan(String planId, String status) {
        return new ExecutionPlan(
                planId, status, "FREE_TEXT", null, "https://example.com/repo.git",
                null, "main", "Test plan", new PlanData(List.of()),
                Instant.now(), Instant.now(), null, null, null, null, null, null, null,
                false, "test-user");
    }

    private static void injectField(Object target, String fieldName, Object value) throws Exception {
        var field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static java.lang.reflect.Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        try {
            return clazz.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            if (clazz.getSuperclass() != null) {
                return findField(clazz.getSuperclass(), name);
            }
            throw e;
        }
    }
}
