package com.eneve.agent.agent.scheduler;

import com.eneve.agent.loganalysis.LogAnalysisService;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Scheduled log analyser — runs every 30 minutes and delegates to
 * {@link LogAnalysisService#analyzeAll()}.
 *
 * <p>Enable/disable per customer environment via
 * {@code EnvironmentConfig.logAnalysis.enabled = true} in the customer registry.
 * There is no global on/off switch — the service simply skips if no environment is enabled.
 *
 * <p>Concurrent runs are skipped ({@link ConcurrentExecution#SKIP}) to avoid
 * overlapping CloudWatch queries and duplicate AI triage calls.
 */
@ApplicationScoped
public class LogAnalysisScheduler {

    private static final Logger LOG = Logger.getLogger(LogAnalysisScheduler.class);

    @Inject
    LogAnalysisService logAnalysisService;

    @Scheduled(every = "30m", delayed = "5m",
               concurrentExecution = ConcurrentExecution.SKIP)
    void runLogAnalysis() {
        LOG.debug("LogAnalysisScheduler: triggered");
        try {
            logAnalysisService.analyzeAll();
        } catch (Exception e) {
            LOG.errorf("LogAnalysisScheduler: unexpected error: %s", e.getMessage());
        }
    }
}
