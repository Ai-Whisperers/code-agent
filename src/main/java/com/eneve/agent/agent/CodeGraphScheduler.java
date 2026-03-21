package com.eneve.agent.agent;

import io.quarkus.scheduler.Scheduled;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Periodically triggers {@link CodeGraphBuildService} to pre-build
 * code graphs for repos that don't have one yet.
 */
@ApplicationScoped
public class CodeGraphScheduler {

    private static final Logger LOG = Logger.getLogger(CodeGraphScheduler.class);

    @Inject
    CodeGraphBuildService buildService;

    @ConfigProperty(name = "code-graph.scheduler.enabled", defaultValue = "true")
    boolean enabled;

    @Scheduled(every = "24h", delayed = "5m",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void buildMissingGraphs() {
        if (!enabled) {
            return;
        }
        LOG.debug("Code graph scheduler triggered");
        buildService.buildMissingGraphs();
    }
}
