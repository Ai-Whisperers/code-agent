package com.eneve.agent;

import com.eneve.agent.aikido.AikidoIssueInfo;
import com.eneve.agent.model.ProductSecuritySummary;
import com.eneve.agent.model.SecurityIssueRow;

import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * In-memory cache for the security issues snapshot.
 *
 * <p>The snapshot is expensive to build (N+1 Aikido API calls across all repos), so it is
 * computed once and held in memory. It is refreshed via three triggers:
 * <ol>
 *   <li>Automatically every 5 minutes via {@link #scheduledRefresh()}.</li>
 *   <li>On any Aikido webhook event via {@link #invalidate()} called from
 *       {@code AikidoWebhookResource}.</li>
 *   <li>On a manual user-triggered refresh via {@code GET /security/issues?refresh=true}.</li>
 * </ol>
 *
 * <p>Uses a {@link ReentrantLock} to prevent concurrent rebuilds — the same pattern
 * used by {@code AikidoService} for its OAuth token cache.
 */
@ApplicationScoped
public class SecurityIssuesCacheService {

    private static final Logger LOG = Logger.getLogger(SecurityIssuesCacheService.class);

    @Inject SecurityIssuesService securityIssuesService;

    private volatile List<ProductSecuritySummary> snapshot = null;
    private volatile Instant cachedAt = null;
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Returns the cached snapshot, building it synchronously if the cache is empty.
     */
    public List<ProductSecuritySummary> getSnapshot() {
        if (snapshot != null) {
            return snapshot;
        }
        return rebuild();
    }

    /**
     * Returns the timestamp of the last successful cache build, or {@code null} if
     * the cache has never been populated.
     */
    public Instant getCachedAt() {
        return cachedAt;
    }

    /**
     * Returns all cached {@link AikidoIssueInfo} objects for the given repo slug,
     * rebuilding the cache if necessary.
     *
     * <p>This avoids a direct Aikido API call for callers that only need the issue list
     * for a single repo (e.g. {@code QualityReportCollector}, {@code ContextSelectionService}).
     * The returned list is derived from the cached {@link SecurityIssueRow} records, so it
     * reflects the same snapshot used by the security issues page.
     *
     * @param repoSlug the repository slug to look up (case-insensitive)
     * @param actionableOnly if {@code true}, only SAST and SCA issues are returned
     */
    public List<AikidoIssueInfo> getIssuesForRepo(String repoSlug, boolean actionableOnly) {
        if (repoSlug == null || repoSlug.isBlank()) return List.of();
        String slugLower = repoSlug.toLowerCase();

        List<AikidoIssueInfo> issues = getSnapshot().stream()
                .flatMap(p -> p.repos().stream())
                .filter(r -> r.repoSlug().equalsIgnoreCase(slugLower)
                        || r.repoSlug().toLowerCase().endsWith("/" + slugLower))
                .flatMap(r -> r.issues().stream())
                .map(this::toAikidoIssueInfo)
                .collect(Collectors.toList());

        if (actionableOnly) {
            issues = issues.stream()
                    .filter(i -> isActionableType(i.issueType()))
                    .collect(Collectors.toList());
        }
        return issues;
    }

    private AikidoIssueInfo toAikidoIssueInfo(SecurityIssueRow row) {
        return new AikidoIssueInfo(
                row.issueGroupId(),
                row.issueType(),
                row.title(),
                row.description(),
                row.severity(),
                row.severityScore(),
                row.packageName(),
                row.currentVersion(),
                row.fixedVersion(),
                row.cveId(),
                null,   // cveDescription not stored in cache
                row.cvssScore(),
                row.repoName(),
                row.repoUrl(),
                row.containerImage(),
                null,   // changelogSummary not stored in cache
                row.howToFix(),
                row.relatedCveIds(),
                row.groupStatus(),
                row.timeToFixMinutes(),
                row.discoveredAt(),
                row.aikidoDueDate()
        );
    }

    private static boolean isActionableType(String type) {
        if (type == null) return false;
        return switch (type.toLowerCase()) {
            case "sca", "dependency", "dependencies", "open_source",
                 "software_composition_analysis",
                 "sast", "code", "static_analysis", "code_security" -> true;
            default -> false;
        };
    }

    /**
     * Clears the cached snapshot. The next call to {@link #getSnapshot()} will trigger
     * a synchronous rebuild.
     */
    public void invalidate() {
        snapshot = null;
        LOG.debug("Security issues cache invalidated");
    }

    /**
     * Scheduled background refresh — runs every 5 minutes, skipping if a rebuild is
     * already in progress.
     */
    @Scheduled(every = "1h", delayed = "5m", concurrentExecution = ConcurrentExecution.SKIP)
    void scheduledRefresh() {
        LOG.debug("Security issues cache: scheduled refresh starting");
        rebuild();
    }

    // ─── Private ──────────────────────────────────────────────────────────────

    private List<ProductSecuritySummary> rebuild() {
        lock.lock();
        try {
            // Double-checked: another thread may have rebuilt while we waited for the lock
            if (snapshot != null) {
                return snapshot;
            }
            LOG.info("Security issues cache: rebuilding snapshot");
            List<ProductSecuritySummary> built = securityIssuesService.buildSnapshot();
            snapshot = built;
            cachedAt = Instant.now();
            LOG.infof("Security issues cache: snapshot ready (%d products)", built.size());
            return built;
        } catch (Exception e) {
            LOG.errorf("Security issues cache: rebuild failed: %s", e.getMessage());
            return snapshot != null ? snapshot : List.of();
        } finally {
            lock.unlock();
        }
    }
}
