package com.eneve.agent.agent.scheduler;

import com.eneve.agent.agent.service.CodeGraphBuildService;
import com.eneve.agent.settings.SettingsService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Periodically triggers {@link CodeGraphBuildService} to pre-build
 * code graphs for repos that don't have one yet.
 */
@ApplicationScoped
public class CodeGraphScheduler {

    private static final Logger LOG = Logger.getLogger(CodeGraphScheduler.class);

    @Inject
    CodeGraphBuildService buildService;

    @Inject
    SettingsService settingsService;

    @Scheduled(every = "24h", delayed = "5m",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void buildMissingGraphs() {
        if (!"true".equalsIgnoreCase(settingsService.get("code-graph.scheduler.enabled", "true"))) {
            return;
        }
        LOG.debug("Code graph scheduler triggered");
        buildService.buildMissingGraphs();
    }
}
