package com.eneve.agent.workspace;

import io.quarkus.scheduler.Scheduled;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

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
 *
 * <p>A periodic sweep removes workspaces that have been held for more than
 * {@value #STALE_HOURS} hours, guarding against leaks when the orchestrator fails to
 * call {@link #release} on an error path.
 */
@ApplicationScoped
public class PlanWorkspaceManager {

    private static final Logger LOG = Logger.getLogger(PlanWorkspaceManager.class);

    /** Workspaces older than this are considered stale and force-released by the sweep. */
    static final long STALE_HOURS = 4;

    private final ConcurrentHashMap<String, WorkspaceContext> workspaces = new ConcurrentHashMap<>();
    /** Tracks when each workspace was first acquired for stale-detection. */
    private final ConcurrentHashMap<String, Instant> acquiredAt = new ConcurrentHashMap<>();

    /**
     * Returns the existing workspace for {@code planId}, or creates a new plan-managed one.
     *
     * @param planId the execution plan ID
     * @return the shared workspace for this plan
     * @throws IOException if a new workspace directory cannot be created
     */
    public WorkspaceContext acquire(String planId) throws IOException {
        try {
            WorkspaceContext ws = workspaces.computeIfAbsent(planId, k -> {
                try {
                    WorkspaceContext w = WorkspaceContext.createPlanManaged(k);
                    LOG.infof("PlanWorkspaceManager: created workspace for plan %s", k);
                    return w;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            acquiredAt.putIfAbsent(planId, Instant.now());
            return ws;
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
        acquiredAt.remove(planId);
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

    /**
     * Periodic sweep that force-releases workspaces held for more than {@value #STALE_HOURS} hours.
     * Guards against leaks when the orchestrator fails to call {@link #release} on an error path.
     */
    @Scheduled(every = "1H", delayed = "1H")
    void sweepStaleWorkspaces() {
        Instant cutoff = Instant.now().minus(STALE_HOURS, ChronoUnit.HOURS);
        List<String> stale = new ArrayList<>();
        acquiredAt.forEach((planId, acquired) -> {
            if (acquired.isBefore(cutoff)) stale.add(planId);
        });
        for (String planId : stale) {
            LOG.warnf("PlanWorkspaceManager: force-releasing stale workspace for plan %s (acquired >%dh ago)",
                    planId, STALE_HOURS);
            release(planId);
        }
    }
}
