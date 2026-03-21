package com.eneve.agent.agent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shared thread pools used by agent job handlers for non-blocking parallel phases
 * (e.g. running linter scans concurrently with prompt resolution).
 */
public final class AgentPools {
    private AgentPools() {}

    /** Unbounded cached pool for brief parallel phases within a single job. */
    public static final ExecutorService PARALLEL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "agent-parallel-phase");
        t.setDaemon(true);
        return t;
    });
}
