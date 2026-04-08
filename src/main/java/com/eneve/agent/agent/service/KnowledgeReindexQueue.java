package com.eneve.agent.agent.service;

import com.eneve.agent.agent.store.KnowledgeEmbeddingStore;
import com.eneve.agent.settings.SettingsService;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Set;
import java.util.concurrent.*;

/**
 * Lightweight, bounded, deduplicating async queue for webhook-triggered knowledge
 * reindexing of Jira issues and Confluence pages.
 *
 * <p>Each entity (Jira issue key or Confluence page ID) is tracked in an in-memory
 * set while pending or running. Duplicate submissions for the same entity are silently
 * dropped — the most recent webhook event that triggered the indexing is sufficient.
 *
 * <p>When the queue is at capacity, new submissions are discarded via
 * {@link ThreadPoolExecutor.DiscardPolicy}. The periodic full reindex or the next
 * incoming webhook will catch any missed events.
 *
 * <p>Parallelism and queue size are runtime-configurable via {@link SettingsService}:
 * <ul>
 *   <li>{@code knowledge.reindex.max-parallel} — concurrent indexing threads (default 2)</li>
 *   <li>{@code knowledge.reindex.max-queue-size} — pending task cap (default 50)</li>
 * </ul>
 */
@ApplicationScoped
public class KnowledgeReindexQueue {

    private static final Logger LOG = Logger.getLogger(KnowledgeReindexQueue.class);

    @Inject KnowledgeIndexerService indexer;
    @Inject KnowledgeEmbeddingStore store;
    @Inject SettingsService settings;

    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private ThreadPoolExecutor executor;

    void onStart(@Observes StartupEvent ev) {
        int parallel  = Integer.parseInt(settings.get("knowledge.reindex.max-parallel",  "2"));
        int queueSize = Integer.parseInt(settings.get("knowledge.reindex.max-queue-size", "50"));
        executor = new ThreadPoolExecutor(
                parallel, parallel,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueSize),
                new ThreadPoolExecutor.DiscardPolicy()
        );
        LOG.infof("KnowledgeReindexQueue started: maxParallel=%d, maxQueueSize=%d", parallel, queueSize);
    }

    void onStop(@Observes ShutdownEvent ev) {
        if (executor != null) {
            executor.shutdown();
        }
    }

    /**
     * Enqueue a reindex of a single Jira issue (delete-then-reindex).
     *
     * @param issueKey Jira issue key (e.g. "ENG-123")
     * @return {@code true} if accepted, {@code false} if already pending/running or queue is full
     */
    public boolean submitJiraIssue(String issueKey) {
        String key = "jira:" + issueKey;
        if (!pending.add(key)) {
            LOG.debugf("KnowledgeReindexQueue: skipping duplicate reindex for %s", issueKey);
            return false;
        }
        try {
            executor.submit(() -> {
                try {
                    LOG.infof("KnowledgeReindexQueue: reindexing Jira issue %s", issueKey);
                    indexer.indexJiraIssue(issueKey);
                } catch (Exception e) {
                    LOG.warnf("KnowledgeReindexQueue: error reindexing Jira issue %s: %s", issueKey, e.getMessage());
                } finally {
                    pending.remove(key);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            pending.remove(key);
            LOG.debugf("KnowledgeReindexQueue: queue full, dropped reindex for Jira issue %s", issueKey);
            return false;
        }
    }

    /**
     * Enqueue a reindex of a single Confluence page (delete-then-reindex).
     *
     * @param pageId    Confluence page ID
     * @param pageTitle page title (used as chunk title)
     * @return {@code true} if accepted, {@code false} if already pending/running or queue is full
     */
    public boolean submitConfluencePage(String pageId, String pageTitle) {
        String key = "confluence:" + pageId;
        if (!pending.add(key)) {
            LOG.debugf("KnowledgeReindexQueue: skipping duplicate reindex for Confluence page %s", pageId);
            return false;
        }
        try {
            executor.submit(() -> {
                try {
                    LOG.infof("KnowledgeReindexQueue: reindexing Confluence page %s (%s)", pageId, pageTitle);
                    store.deleteBySource("confluence", pageId);
                    indexer.indexConfluencePage(pageId, pageTitle);
                } catch (Exception e) {
                    LOG.warnf("KnowledgeReindexQueue: error reindexing Confluence page %s: %s", pageId, e.getMessage());
                } finally {
                    pending.remove(key);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            pending.remove(key);
            LOG.debugf("KnowledgeReindexQueue: queue full, dropped reindex for Confluence page %s", pageId);
            return false;
        }
    }

    public int getPendingCount() {
        return pending.size();
    }

    public int getActiveCount() {
        return executor != null ? executor.getActiveCount() : 0;
    }

    public long getQueueDepth() {
        return executor != null ? executor.getQueue().size() : 0;
    }
}
