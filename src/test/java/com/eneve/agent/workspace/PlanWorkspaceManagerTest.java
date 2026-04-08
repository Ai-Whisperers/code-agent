package com.eneve.agent.workspace;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PlanWorkspaceManagerTest {

    @Test
    void acquireCreatesWorkspaceForNewPlan() throws IOException {
        PlanWorkspaceManager manager = new PlanWorkspaceManager();
        String planId = "test-plan-new-ws-12345678";

        WorkspaceContext ws = manager.acquire(planId);
        try {
            assertNotNull(ws);
            assertTrue(Files.exists(ws.getRoot()));
        } finally {
            manager.release(planId);
        }
    }

    @Test
    void acquireReturnsSameWorkspaceForSamePlan() throws IOException {
        PlanWorkspaceManager manager = new PlanWorkspaceManager();
        String planId = "test-plan-reuse-12345678";

        WorkspaceContext ws1 = manager.acquire(planId);
        WorkspaceContext ws2 = manager.acquire(planId);
        try {
            assertSame(ws1, ws2, "Same workspace should be returned for the same planId");
        } finally {
            manager.release(planId);
        }
    }

    @Test
    void acquireReturnsDifferentWorkspacesForDifferentPlans() throws IOException {
        PlanWorkspaceManager manager = new PlanWorkspaceManager();
        String planId1 = "plan-a-12345678-0000-0000-0000-000000000001";
        String planId2 = "plan-b-12345678-0000-0000-0000-000000000002";

        WorkspaceContext ws1 = manager.acquire(planId1);
        WorkspaceContext ws2 = manager.acquire(planId2);
        try {
            assertNotSame(ws1, ws2);
            assertNotEquals(ws1.getRoot(), ws2.getRoot());
        } finally {
            manager.release(planId1);
            manager.release(planId2);
        }
    }

    @Test
    void releaseDeletesWorkspaceDirectory() throws IOException {
        PlanWorkspaceManager manager = new PlanWorkspaceManager();
        String planId = "test-plan-release-1234";

        WorkspaceContext ws = manager.acquire(planId);
        Path root = ws.getRoot();
        assertTrue(Files.exists(root));

        manager.release(planId);
        assertFalse(Files.exists(root), "Workspace directory should be deleted after release");
    }

    @Test
    void releaseIsIdempotentForUnknownPlan() {
        PlanWorkspaceManager manager = new PlanWorkspaceManager();
        assertDoesNotThrow(() -> manager.release("nonexistent-plan-id"));
    }

    @Test
    void hasWorkspaceReturnsTrueAfterAcquire() throws IOException {
        PlanWorkspaceManager manager = new PlanWorkspaceManager();
        String planId = "test-plan-has-ws-1234567";

        assertFalse(manager.hasWorkspace(planId));
        manager.acquire(planId);
        assertTrue(manager.hasWorkspace(planId));
        manager.release(planId);
        assertFalse(manager.hasWorkspace(planId));
    }

    @Test
    void closeOnAcquiredWorkspaceDoesNotDeleteIt() throws IOException {
        PlanWorkspaceManager manager = new PlanWorkspaceManager();
        String planId = "test-plan-close-noop-1234";

        WorkspaceContext ws = manager.acquire(planId);
        Path root = ws.getRoot();
        ws.close(); // try-with-resources in a handler — should be no-op
        assertTrue(Files.exists(root), "Plan workspace must survive handler close()");

        manager.release(planId);
        assertFalse(Files.exists(root));
    }
}
