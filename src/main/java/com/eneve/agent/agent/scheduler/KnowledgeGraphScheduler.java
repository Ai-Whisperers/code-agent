package com.eneve.agent.agent.scheduler;

import com.eneve.agent.agent.JobQueue;
import com.eneve.agent.agent.store.JobStore;
import com.eneve.agent.model.JobRecord;
import com.eneve.agent.model.KnowledgeGraphRequest;
import com.eneve.agent.settings.SettingsService;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.UUID;

/**
 * Weekly scheduled job that analyses git history across all configured repos to build
 * the Team Knowledge Graph.
 *
 * <p>Enabled via {@code knowledge-graph.scheduler.enabled=true} (default: {@code false}).
 * Lookback window is configurable via {@code knowledge-graph.lookback-days} (default: 365).
 */
@ApplicationScoped
public class KnowledgeGraphScheduler {

    private static final Logger LOG = Logger.getLogger(KnowledgeGraphScheduler.class);

    @Inject JobStore jobStore;
    @Inject JobQueue jobQueue;
    @Inject SettingsService settingsService;

    @Scheduled(every = "168h", delayed = "30m",
               concurrentExecution = ConcurrentExecution.SKIP)
    void computeKnowledgeGraph() {
        if (!"true".equalsIgnoreCase(settingsService.get("knowledge-graph.scheduler.enabled", "false"))) {
            return;
        }

        int lookbackDays = Integer.parseInt(
                settingsService.get("knowledge-graph.lookback-days", "365"));

        String jobId = UUID.randomUUID().toString();
        KnowledgeGraphRequest request = new KnowledgeGraphRequest(null, lookbackDays);
        JobRecord job = new JobRecord(jobId, request);
        jobStore.put(job);

        if (jobQueue.submit(job)) {
            LOG.infof("KnowledgeGraphScheduler: queued job %s (lookbackDays=%d)", jobId, lookbackDays);
        } else {
            LOG.warnf("KnowledgeGraphScheduler: queue full, job %s not submitted", jobId);
        }
    }
}
