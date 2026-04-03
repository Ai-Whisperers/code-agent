package com.eneve.agent.agent;

import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Shared thread pools used by agent job handlers for non-blocking parallel phases
 * (e.g. running linter scans concurrently with prompt resolution).
 *
 * <p>The inner {@link Lifecycle} bean observes the Quarkus {@link ShutdownEvent} and
 * shuts down all pools gracefully so in-flight tasks can complete before the JVM exits.
 */
public final class AgentPools {

    private static final Logger LOG = Logger.getLogger(AgentPools.class);

    private AgentPools() {}

    /** Unbounded cached pool for brief parallel phases within a single job. */
    public static final ExecutorService PARALLEL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "agent-parallel-phase");
        t.setDaemon(true);
        return t;
    });

    @ApplicationScoped
    public static class Lifecycle {
        void onShutdown(@Observes ShutdownEvent event) {
            shutdownPool("AgentPools.PARALLEL", PARALLEL);
        }

        private static void shutdownPool(String name, ExecutorService pool) {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                    LOG.warnf("%s did not terminate in 10 s — forcing shutdown", name);
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pool.shutdownNow();
            }
        }
    }
}
