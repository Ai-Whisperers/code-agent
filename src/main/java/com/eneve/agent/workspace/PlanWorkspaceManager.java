package com.eneve.agent.workspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.ConcurrentHashMap;

import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Manages one shared {@link WorkspaceContext} per execution plan.
 *
 * <p>Multi-phase plans submit sequential FIX jobs that all operate on the same branch.
 * Rather than cloning the repository on every step, the orchestrator uses this manager
 * so each step can reuse the workspace from the previous step.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>First job in a plan calls {@link #acquire} — a new plan-managed workspace is created.</li>
 *   <li>Subsequent jobs call {@link #acquire} — the same workspace is returned; clone is skipped
 *       because {@link WorkspaceContext#hasClonedRepo()} returns {@code true}.</li>
 *   <li>When the plan reaches a terminal state (COMPLETED or FAILED), the orchestrator calls
 *       {@link #release} which force-deletes the workspace directory.</li>
 * </ol>
 */
@ApplicationScoped
public class PlanWorkspaceManager {

    private static final Logger LOG = Logger.getLogger(PlanWorkspaceManager.class);

    private final ConcurrentHashMap<String, WorkspaceContext> workspaces = new ConcurrentHashMap<>();

    /**
     * Returns the existing workspace for {@code planId}, or creates a new plan-managed one.
     *
     * @param planId the execution plan ID
     * @return the shared workspace for this plan
     * @throws IOException if a new workspace directory cannot be created
     */
    public WorkspaceContext acquire(String planId) throws IOException {
        try {
            return workspaces.computeIfAbsent(planId, k -> {
                try {
                    WorkspaceContext ws = WorkspaceContext.createPlanManaged(k);
                    LOG.infof("PlanWorkspaceManager: created workspace for plan %s", k);
                    return ws;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /**
     * Force-closes and removes the workspace for {@code planId}.
     * Safe to call even if no workspace was created (e.g. plan failed before first FIX step).
     *
     * @param planId the execution plan ID
     */
    public void release(String planId) {
        WorkspaceContext ws = workspaces.remove(planId);
        if (ws != null) {
            LOG.infof("PlanWorkspaceManager: releasing workspace for plan %s", planId);
            ws.forceClose();
        }
    }

    /**
     * Returns {@code true} if a workspace exists for the given plan.
     */
    public boolean hasWorkspace(String planId) {
        return workspaces.containsKey(planId);
    }
}
