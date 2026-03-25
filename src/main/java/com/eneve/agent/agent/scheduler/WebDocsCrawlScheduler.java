package com.eneve.agent.agent.scheduler;

import com.eneve.agent.agent.service.KnowledgeIndexerService;
import com.eneve.agent.settings.SettingsService;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Scheduled weekly re-crawl of all registered web documentation sources.
 *
 * <p>The cron expression and timezone are resolved from {@code application.properties}
 * (or environment variables) at JVM startup — they cannot be changed at runtime without
 * a restart. The enable/disable toggle ({@code knowledge.crawler.scheduler.enabled}) is
 * read at runtime via {@link SettingsService} so it can be changed without a restart.
 *
 * <p>Default schedule: every Friday at 22:00 Europe/Amsterdam time.
 */
@ApplicationScoped
public class WebDocsCrawlScheduler {

    private static final Logger LOG = Logger.getLogger(WebDocsCrawlScheduler.class);

    @Inject KnowledgeIndexerService indexer;
    @Inject SettingsService settingsService;

    @Scheduled(
            cron     = "{knowledge.crawler.scheduler.cron}",
            timeZone = "{knowledge.crawler.scheduler.timezone}",
            concurrentExecution = ConcurrentExecution.SKIP
    )
    void crawlWebDocs() {
        if (!"true".equalsIgnoreCase(
                settingsService.get("knowledge.crawler.scheduler.enabled", "false"))) {
            return;
        }

        LOG.info("WebDocsCrawlScheduler: starting weekly web docs re-crawl");

        var results = indexer.indexAllWebDocSources();

        int totalIndexed = results.stream()
                .mapToInt(KnowledgeIndexerService.IndexResult::chunksIndexed).sum();
        int totalErrors  = results.stream()
                .mapToInt(r -> r.errors().size()).sum();

        LOG.infof("WebDocsCrawlScheduler: complete — %d sources, %d chunks indexed, %d errors",
                results.size(), totalIndexed, totalErrors);
    }
}
